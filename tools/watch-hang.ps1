# watch-hang.ps1 - waits for Imperialism to freeze and captures a full memory dump.
#
#   powershell -ExecutionPolicy Bypass -File watch-hang.ps1
#
# Runs without admin rights. The game process is NOT terminated.
#
# Method: x86 cdb.exe (.dump /ma, non-invasive) - produces a NATIVE 32-bit
# dump. ProcDump is only used as a fallback.
#
# Two reasons for this choice, both measured on a real machine:
#  * "rundll32 comsvcs.dll,MiniDump" does NOT work without elevation: it
#    produces a 0-byte file whose ACL only contains SYSTEM and Administrators -
#    the user cannot even read or delete it.
#  * The Sysinternals package from the Microsoft Store ships an x64-only
#    procdump.exe. Its dump of a 32-bit process has ProcessorArchitecture = 9
#    (AMD64) with x64 contexts (1232 bytes), even without the -64 flag. The x86
#    cdb instead yields ProcessorArchitecture = 0 (x86) with x86 contexts
#    (716 bytes).
# See the "Diagnosing freezes" chapter in README.md for details.

[CmdletBinding()]
param(
    [string] $ProcessName    = 'Imperialism',
    [int]    $PollSeconds    = 5,
    [int]    $ConfirmSeconds = 10,
    [string] $OutDir         = $PSScriptRoot,
    [switch] $WithSymbols,       # fetch symbols from msdl.microsoft.com (needs internet)
    [switch] $TestNow            # dry run: dump immediately without waiting for a hang
)

$ErrorActionPreference = 'Stop'
$stamp    = Get-Date -Format 'yyyyMMdd-HHmmss'
$dumpName = "imperialism-hang-$stamp.dmp"
$dumpPath = Join-Path $OutDir $dumpName
$txtPath  = Join-Path $OutDir "imperialism-hang-$stamp.stacks.txt"

function Say($msg, $color = 'Gray') {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg) -ForegroundColor $color
}

function Get-GameProcess {
    $all = @(Get-Process -Name $ProcessName -ErrorAction SilentlyContinue)
    if ($all.Count -eq 0) { return $null }
    $withWindow = @($all | Where-Object { $_.MainWindowHandle -ne 0 })
    if ($withWindow.Count -gt 0) { return $withWindow[0] }
    return $all[0]
}

function Get-Cdb32 {
    $pkg = Get-AppxPackage Microsoft.WinDbg -ErrorAction SilentlyContinue
    if (-not $pkg) { return $null }
    $cdb = Join-Path $pkg.InstallLocation 'x86\cdb.exe'
    if (Test-Path $cdb) { return $cdb }
    return $null
}

# --- Full dump via x86 cdb (produces a NATIVE 32-bit dump) -------------------
# Measured on a real machine:
#   x86 cdb  .dump /ma  -> ProcessorArchitecture = 0 (x86),   CONTEXT 716 bytes
#   procdump -ma        -> ProcessorArchitecture = 9 (AMD64), CONTEXT 1232 bytes
# The Store Sysinternals only ship an x64 procdump.exe, hence cdb is the
# primary path. -pv = non-invasive: does not disturb the hung process and does
# not terminate it on detach.
function Invoke-CdbDump([int] $TargetPid, [string] $FinalPath) {
    $cdb = Get-Cdb32
    if (-not $cdb) { throw 'x86 cdb.exe not found (install WinDbg from the Microsoft Store)' }
    Say "cdb -pv .dump /ma $TargetPid -> $FinalPath"
    & $cdb -pv -p $TargetPid -c ".dump /ma /o $FinalPath; q" 2>&1 | Out-Null
    if (-not (Test-Path $FinalPath)) { throw 'cdb did not produce a file' }
    $len = (Get-Item $FinalPath).Length
    if ($len -lt 1MB) { throw "Dump implausibly small ($len bytes)" }
    return @{ Size = $len; Method = 'cdb (x86) .dump /ma' }
}

# --- Fallback: ProcDump (yields the x64 outer view, but better than nothing) --
function Invoke-ProcDumpDump([int] $TargetPid, [string] $FinalPath) {
    $pd = Get-Command procdump.exe -ErrorAction SilentlyContinue
    if (-not $pd) { throw 'procdump.exe not found' }
    Say "procdump -ma $TargetPid -> $FinalPath"
    & $pd.Source -accepteula -ma $TargetPid $FinalPath 2>&1 | Out-Null
    if (-not (Test-Path $FinalPath)) { throw 'procdump did not produce a file' }
    $len = (Get-Item $FinalPath).Length
    if ($len -lt 1MB) { throw "Dump implausibly small ($len bytes)" }
    return @{ Size = $len; Method = 'procdump -ma (FALLBACK, x64 view)' }
}

# --- Verification: read the SystemInfo stream (7) ----------------------------
function Test-DumpArch([string] $Path) {
    $fs = [IO.File]::OpenRead($Path)
    $br = New-Object System.IO.BinaryReader($fs)
    try {
        if ($br.ReadUInt32() -ne 0x504D444D) { return $null }   # 'MDMP'
        $null     = $br.ReadUInt32()
        $nStreams = $br.ReadUInt32()
        $dirRva   = $br.ReadUInt32()
        for ($i = 0; $i -lt $nStreams; $i++) {
            $fs.Position = $dirRva + ($i * 12)
            $type = $br.ReadUInt32(); $null = $br.ReadUInt32(); $rva = $br.ReadUInt32()
            if ($type -eq 7) {
                $fs.Position = $rva
                return $br.ReadUInt16()
            }
        }
        return $null
    } finally { $fs.Close() }
}

# --- Thread stacks as text, read from the dump (does not disturb the process) -
# Imperialism is 32-bit under WoW64. Without !wow64exts.sw, cdb shows the x64
# outer view (wow64cpu!CpupSyscallStub) instead of the real 32-bit registers.
function Write-ThreadStacks([string] $DumpPath, [string] $TxtPath, $Arch) {
    $cdb = Get-Cdb32
    if (-not $cdb) { Say 'x86 cdb.exe not found - skipping stacks' 'DarkGray'; return $false }

    # Only an x64 dump (arch 9) needs the switch into guest mode.
    # On a native 32-bit dump (arch 0) !wow64exts.sw would fail.
    $prefix = ''
    if ($Arch -eq 9) { $prefix = '!wow64exts.sw; ' }
    $cmds = $prefix + '.echo ==== REGISTERS ====; r; .echo ==== ALL THREAD STACKS ====; ~*kb; .echo ==== MODULES ====; lm; q'
    $argList = @()
    if ($WithSymbols) { $argList += @('-y', 'srv*C:\symcache*https://msdl.microsoft.com/download/symbols') }
    $argList += @('-z', $DumpPath, '-c', $cmds)

    Say "cdb -> $TxtPath"
    $raw = & $cdb @argList 2>&1
    $raw | Where-Object { $_ -notmatch 'NatVis script|Repository :|Extensions Gallery|ExtensionRepository|Nuget|AllowNuget|NonInteractiveNuget|UseExperimentalFeature|AllowParallel|Configuring repositories' } |
        Out-File -FilePath $TxtPath -Encoding utf8
    return (Test-Path $TxtPath)
}

# ============================ Main loop ======================================
Say "Watching '$ProcessName'. Poll ${PollSeconds}s, confirmation ${ConfirmSeconds}s." 'Cyan'
Say "Output folder: $OutDir" 'Cyan'
if (-not (Get-Cdb32)) {
    Say 'WARNING: x86 cdb.exe missing - only the procdump fallback (x64 view) is available.' 'Yellow'
    if (-not (Get-Command procdump.exe -ErrorAction SilentlyContinue)) {
        Say 'ERROR: procdump.exe is missing too - no dump will be possible!' 'Red'
    }
}
Say 'Now start the game and play normally. Abort with Ctrl+C.' 'Cyan'

$seen = $false
while ($true) {
    $proc = Get-GameProcess

    if (-not $proc) {
        if ($seen) { Say 'Process exited - no hang captured. Loop keeps running.' 'Yellow'; $seen = $false }
        Start-Sleep -Seconds $PollSeconds
        continue
    }

    if (-not $seen) { Say "Process found: PID $($proc.Id)" 'Green'; $seen = $true }

    if (-not $TestNow) {
        if ($proc.Responding) { Start-Sleep -Seconds $PollSeconds; continue }

        # --- Suspected hang: confirm -----------------------------------------
        Say "Not responding (PID $($proc.Id)) - waiting ${ConfirmSeconds}s to confirm..." 'Yellow'
        Start-Sleep -Seconds $ConfirmSeconds

        $proc = Get-GameProcess
        if (-not $proc)       { Say 'Process has disappeared - no dump possible.' 'Red'; continue }
        if ($proc.Responding) { Say 'Just a stutter, responding again. Continuing.' 'Green'; continue }
    } else {
        Say 'TESTNOW: dumping immediately without waiting for a hang.' 'Magenta'
    }

    # --- Confirmed hang ------------------------------------------------------
    $targetPid = $proc.Id
    Say "HANG CONFIRMED (PID $targetPid) - taking full dump..." 'Red'

    $res = $null
    try { $res = Invoke-CdbDump -TargetPid $targetPid -FinalPath $dumpPath }
    catch {
        Say "cdb dump failed: $($_.Exception.Message)" 'Yellow'
        try { $res = Invoke-ProcDumpDump -TargetPid $targetPid -FinalPath $dumpPath }
        catch { Say "procdump fallback failed: $($_.Exception.Message)" 'Red' }
    }
    if (-not $res) {
        Say 'Both dump methods failed. Loop keeps running.' 'Red'
        continue
    }
    $size = $res.Size

    Say ("Dump written: {0} ({1:N1} MB) via {2}" -f $dumpName, ($size / 1MB), $res.Method) 'Green'

    # --- Verify architecture -------------------------------------------------
    $arch = Test-DumpArch -Path $dumpPath
    if     ($arch -eq 0) { Say 'Verified: ProcessorArchitecture = 0 (x86) - native 32-bit dump.' 'Green' }
    elseif ($arch -eq 9) { Say 'ATTENTION: ProcessorArchitecture = 9 (AMD64) - x64 outer view, WOW64 context needed!' 'Yellow' }
    else                 { Say "ProcessorArchitecture could not be read ($arch)." 'Yellow' }

    $haveStacks = $false
    try { $haveStacks = Write-ThreadStacks -DumpPath $dumpPath -TxtPath $txtPath -Arch $arch }
    catch { Say "Stack extraction failed: $($_.Exception.Message)" 'Yellow' }

    # --- Summary file --------------------------------------------------------
    $stacksLine = 'not produced'
    if ($haveStacks) { $stacksLine = [System.IO.Path]::GetFileName($txtPath) }

    if ($arch -eq 0) {
        $archLines = @(
            '- ProcessorArchitecture: **0 (x86)** - native 32-bit dump, verified',
            '- Thread contexts:       x86 CONTEXT (716 bytes)',
            '',
            '## Analysis',
            '',
            'No WOW64 detour needed. The dump is native 32-bit; a regular',
            'minidump parser reads the ThreadListStream directly.'
        )
    } else {
        $archLines = @(
            "- ProcessorArchitecture: **$arch (AMD64)** - x64 outer view (fallback path)",
            '- Thread contexts:       x64 CONTEXT (1232 bytes)',
            '',
            '## Analysis - ATTENTION',
            '',
            'The cdb primary path did not work; this is a procdump dump.',
            'For the real 32-bit registers switch into guest mode first:',
            '',
            '    cdb -z <dump> -c "!wow64exts.sw; r; ~*kb; q"',
            '',
            'Without that, RIP only shows wow64cpu!CpupSyscallStub. A custom parser',
            'must read the WOW64_CONTEXT, not the native thread context.'
        )
    }

    $report = (@(
        '# Hang capture successful',
        '',
        "- Time:       $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
        "- PID:        $targetPid",
        "- Dump file:  $dumpName",
        "- Size:       $size bytes ($([math]::Round($size / 1MB, 1)) MB)",
        "- Method:     $($res.Method)",
        "- Stacks:     $stacksLine"
    ) + $archLines + @(
        '',
        'The game process was NOT terminated.'
    )) -join "`r`n"
    Set-Content -Path (Join-Path $OutDir 'HANG-CAPTURE-RESULT.md') -Value $report -Encoding utf8

    for ($i = 0; $i -lt 3; $i++) { [console]::Beep(1200, 250); Start-Sleep -Milliseconds 120 }
    Say 'Done. Script exits.' 'Green'
    break
}

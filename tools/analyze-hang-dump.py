#!/usr/bin/env python3
"""Analyze an Imperialism hang dump (32-bit minidump, e.g. from watch-hang.ps1).

Usage:    python3 analyze-hang-dump.py imperialism-hang-<stamp>.dmp
Requires: pip install minidump

Prints EIP/ESP/EBP for every thread, annotates addresses that fall inside
Imperialism.exe against known landmarks (patch sites, DirectPlay wrapper,
spin-wait helper, phase state machine) and scans each stack for return
addresses into .text. The main thread's EIP tells you where the game is
stuck: inside Imperialism's .text it is a game loop, inside ntdll it is a
blocking system call (look one frame up for the caller).
"""
import sys
from minidump.minidumpfile import MinidumpFile

TEXT_LO, TEXT_HI = 0x401000, 0x63DE95      # .text of Imperialism.exe (GOG build)
IMG_LO, IMG_HI = 0x400000, 0x708000        # whole image incl. the GOG .patch section

# (address, name) marks a single address, (lo, hi, name) marks a range.
LANDMARKS = [
    (0x408F8F, "r55 hook: disconnect null-deref fix"),
    (0x4148AD, "Fix: CD-ROM drive scan skipped (setMgr.cpp)"),
    (0x414870, 0x414960, "Movie path resolver / CD-ROM drive scan (setMgr.cpp)"),
    (0x47A8A0, "CDib::AttachResource (r55 rewrite)"),
    (0x47C0AB, 0x47C1AB, "r55 guard trampolines (.text code cave)"),
    (0x47C1A0, 0x47C1CC, "Fix: turn-end loop fail-safe stubs"),
    (0x47E000, 0x481800, "DirectPlay wrapper (DirectPlay.cpp)"),
    (0x4808A0, 0x480956, "DirectPlay Receive wrapper (DPERR_BUFFERTOOSMALL retry loop)"),
    (0x480850, 0x48088B, "DirectPlay Send wrapper"),
    (0x493200, 0x493232, "SPIN-WAIT loop (timeGetTime, no Sleep!)"),
    (0x493250, 0x49325A, "GetTime16 helper (timeGetTime>>4)"),
    (0x5421E0, 0x5422F4, "PhaseId->name (kPh* debug function)"),
    (0x542170, 0x5421CA, "Phase lookup in phase object [0x6A43C8]"),
    (0x59E4F0, 0x59E7D0, "Turn-end distribution function (contains the fixed loop 0x59E6B3-0x59E6F3)"),
    (0x5C3B40, 0x5C3B53, "Delay wrapper around spin-wait"),
    (0x5DFC10, 0x5DFD14, "GetMoviePath (builds Movies/<name>.avi)"),
    (0x5E3400, 0x5E3900, "DirectPlay error text formatting (DPERR_*)"),
]

def annotate(addr):
    for lm in LANDMARKS:
        if len(lm) == 2 and lm[0] == addr:
            return lm[1]
        if len(lm) == 3 and lm[0] <= addr < lm[1]:
            return f"{lm[2]} (+{addr - lm[0]:#x})"
    if TEXT_LO <= addr < TEXT_HI:
        return "Imperialism .text"
    if IMG_LO <= addr < IMG_HI:
        return "Imperialism (data / other section)"
    return None

def read_stack(reader, esp, size=0x1200, chunk=0x100):
    """Read the stack in small chunks so a segment boundary doesn't abort the whole read."""
    out = bytearray()
    pos = esp
    while len(out) < size:
        try:
            reader.move(pos)
            out += reader.read(chunk)
        except Exception:
            break
        pos += chunk
    return bytes(out)

def main(path):
    mf = MinidumpFile.parse(path)
    arch = mf.sysinfo.ProcessorArchitecture if mf.sysinfo else None
    is_x86 = str(arch).endswith('INTEL') or arch == 0
    print(f"Dump: {path}")
    print(f"ProcessorArchitecture: {arch} ({'x86, OK' if is_x86 else 'ATTENTION: not x86 - WoW64 outer view, see README'})")
    buff_reader = mf.get_reader().get_buffered_reader()

    for t in mf.threads.threads:
        ctx = t.ContextObject
        if ctx is None:
            print(f"\nThread {t.ThreadId:#x}: no context")
            continue
        eip = getattr(ctx, 'Eip', None)
        esp = getattr(ctx, 'Esp', None)
        ebp = getattr(ctx, 'Ebp', None)
        if eip is None:
            print(f"\nThread {t.ThreadId:#x}: x64 context (WoW64 outer view?) - Rip={getattr(ctx, 'Rip', 0):#x}")
            continue
        note = annotate(eip)
        print(f"\nThread {t.ThreadId:#x}: EIP={eip:#010x} ESP={esp:#010x} EBP={ebp:#010x}"
              + (f"   <== {note}" if note else ""))
        data = read_stack(buff_reader, esp)
        if not data:
            print("   stack not readable")
            continue
        hits = []
        for off in range(0, len(data) - 3, 4):
            v = int.from_bytes(data[off:off + 4], 'little')
            if TEXT_LO <= v < TEXT_HI:
                hits.append((esp + off, v))
        for sp, v in hits[:40]:
            note = annotate(v)
            print(f"   [esp+{sp - esp:#06x}] {v:#010x}" + (f"  {note}" if note else ""))
        if len(hits) > 40:
            print(f"   ... {len(hits) - 40} more")

if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])

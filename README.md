# ImpSave v0.25

[![CI](https://github.com/Panxatony/impsave/actions/workflows/ci.yml/badge.svg)](https://github.com/Panxatony/impsave/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/Panxatony/impsave)](https://github.com/Panxatony/impsave/releases/latest)

See [CHANGELOG.md](CHANGELOG.md) for what changed in each release.

ImpSave is a helpful companion for the [Imperialism PC game](https://en.wikipedia.org/wiki/Imperialism_%28video_game%29).

It has two primary functions:
  1. Automatically backing up saved games, allowing to restore to an earlier turn.
  2. Patching the Imperialism.exe binary (GOG version) to fix a few crash bugs.

To use it, simply place the ImpSave-0.25.jar inside your Imperialism folder and run it
(you'll need Java 17 or newer), or use the Windows bundle, which needs no Java at all.
See *Installing a release* below.

The program will bring up a window that lets you start Imperialism and apply patches
to it (if it's a known version of the binary, such as the GOG release).

While the program is running, it will automatically monitor the `Save` folder for
new and updated saved games and make back ups. A restore tab lets you restore any
saved game and annotate saves with additional comments.

## Installing a release (JAR)

1. **Install Java** - or skip this step by using the Windows bundle (see 2).
   ImpSave is a Java desktop application; any Java Runtime (JRE) version 17
   or newer works. Check with `java -version` in a command prompt. If the
   command is unknown, install a JRE, e.g. from [Adoptium](https://adoptium.net/).
2. **Download a release** from the
   [Releases page](https://github.com/Panxatony/impsave/releases). Two
   variants are published:
   - `ImpSave-<version>.jar` - the portable JAR; needs Java 17 or newer.
   - `ImpSave-<version>-windows-x64.zip` - a Windows build with an embedded
     Java runtime; no Java installation needed. Unzip it so that the
     `ImpSave` folder it contains sits inside the game folder, then run
     `ImpSave\ImpSave.exe`.
3. **Put the JAR into the Imperialism game folder** - the same folder that
   contains `Imperialism.exe` and the `Save` sub-folder (for the GOG release
   typically `C:\Program Files (x86)\GOG Galaxy\Games\Imperialism` or
   `C:\GOG Games\Imperialism`). This is required: ImpSave uses its working
   directory to find the game binary and the `Save` folder.
4. **Run it**: double-click the JAR, or open a command prompt in the game
   folder and run

   ```
   java -jar ImpSave-0.25.jar
   ```

   ImpSave needs write access to the game folder (for backups and for
   patching). If the game lives under `Program Files` and writing fails, start
   the command prompt once *as administrator* for the patch step, or move the
   game to a user-writable location.
5. **First run - patching (optional).** The *Launch* tab shows whether your
   `Imperialism.exe` is a known version (unpatched GOG release, an older
   ImpSave patch level, or already up to date). Click *Patch* to apply the
   fixes; the original is kept as `Imperialism.exe.old`. Afterwards the status
   should read "up to date". The current patched binary has the MD5
   `7ceebaafae07e8713c010dcbd01b4d1e`.
6. **Keep ImpSave running while you play** so it can back up every save.

To build it yourself you need JDK 17 or newer (21 recommended): `mvn package`
compiles the JAR into `target/` and runs the patch-table sanity tests (they
verify, without the game binary, that every patch lands inside `.text` and the
file). The *Release* workflow builds the same JAR plus the Windows bundle (via
`jpackage`) whenever a tag `v<version>` that matches `pom.xml` is pushed.

## Save game backups

This program addresses a major limitation with Imperialism - only a single autosave slot.

By backing up autosaves (and other save slots) continuously, the program allows you to
restore the game to an arbitrary past turn. This way, you can reconsider your decisions 
from many turns ago, or recover from a deterministic crash bug in your most recent save
file.

The functionality is also supported and very useful in multiplayer, since all players
require an autosave at the same turn in order to restore a saved game. Without ImpSave,
this is very hard to manage, especially when one or more players crash or lose connection.

## Patching functionality

In addition to save game backups, ImpSave includes the functionality to patch the
Imperialism.exe binary with some bug fixes. This is an optional feature and is not
required for the backup functionality, but the patches address a number of crash bugs
that exist in the program. The patching functionality assumes a base version of the
GOG Imperialism binary.

## Diagnosing freezes (hang capture tools)

Not every "crash" is a crash. Two of the bugs fixed by ImpSave (the CD-ROM drive
scan before movies and the infinite loop at turn end) were **freezes**: the game
stops processing window messages, Windows reports an *Application Hang*
(`AppHangB1`) and terminates it. Such freezes leave **no exception code and no
faulting address** in the event log, so the usual crash-dump tools find nothing.
They can only be diagnosed from a memory dump taken *while* the game is hung.
The `tools/` folder contains two scripts for exactly that.

### Prerequisites

- **WinDbg from the Microsoft Store** (free). It provides the x86 `cdb.exe`
  that the watcher uses to take a *native 32-bit* dump. Note that the
  Sysinternals `procdump.exe` from the Store is 64-bit only; its dump of the
  32-bit game shows only the WoW64 outer view and is much harder to analyze.
  The watcher uses ProcDump only as a clearly-labelled fallback.
- For the analyzer: **Python 3** and `pip install minidump`.
- No administrator rights are needed; the game process is never killed.

### Capturing a freeze

1. Copy `tools/watch-hang.ps1` into the game folder and start it **before**
   the game session:

   ```
   powershell -ExecutionPolicy Bypass -File watch-hang.ps1
   ```

   Leave the window open. It reports as soon as it has found the game process.
2. Play normally. When the game freezes, **do nothing** - don't click the
   window away, don't kill the process. The watcher waits 10 seconds to
   confirm it is a real hang, takes the dump, beeps three times and exits.
3. You now have, next to the script:

   | File | Content |
   |---|---|
   | `imperialism-hang-<timestamp>.dmp` | full memory dump (~50-200 MB) |
   | `imperialism-hang-<timestamp>.stacks.txt` | readable registers and all thread stacks |
   | `HANG-CAPTURE-RESULT.md` | summary incl. the verified dump architecture |

   The script exits after one capture; start it again for the next session.
   `-TestNow` takes a dump immediately (dry run), `-WithSymbols` downloads
   Microsoft symbols so system frames get readable names.

### Locating the bug

```
python3 tools/analyze-hang-dump.py imperialism-hang-<timestamp>.dmp
```

The analyzer prints `EIP/ESP/EBP` for every thread and annotates addresses that
fall inside `Imperialism.exe` with known landmarks (patch sites, the DirectPlay
wrapper, the spin-wait helper, the phase state machine). Read the **main
thread** (the first one):

- `EIP` inside Imperialism's `.text` means the game is spinning in one of its
  own loops - the annotated return addresses on the stack show which function.
- `EIP` inside `ntdll`/`KERNELBASE` means the game is blocked in a system call
  (e.g. a drive or file query); the first Imperialism frame in `.stacks.txt`
  shows the caller.

The `.stacks.txt` from `cdb` is the cross-check for the same information. When
reporting a freeze, attach `HANG-CAPTURE-RESULT.md`, the `.stacks.txt` and the
analyzer output; the `.dmp` itself is usually only needed on request.

## Origins

This program was conceived when the author would play Imperialism with a group of
friends in a LAN setting in the basement of a video store, circa 2013. Since
stability issues plagued Imperialism, he decided to write a handy utility to
automatically back up saved games, so that games could be restored reliably in a
multiplayer setting.

In addition, since the group encountered a few common crash bugs, the author also
reverse-engineered the binary and fixed a few of them, and built the functionality
to patch the Imperialism binary into the program.

It is now many years later when ImpSave is being shared publicly via GitHub, with
a bit of recent rework and improvements. ImpSave is being released in memory of
Aaron Kaufman, who was a regular in our play group and who tragically passed away
in 2021. RIP and may you live on in our memories.

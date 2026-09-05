# Changelog

All notable changes to ImpSave are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.25] - 2026-09-05

### Added
- Two patches for *Application Hang* (`AppHangB1`) freezes in the GOG
  `Imperialism.exe`, diagnosed from live hang dumps and merged upstream as
  asvitkine/impsave#1:
  - The CD-ROM drive scan (C:-Z:, volume label `IMPERIALISM`) that ran before
    every movie is skipped; on some machines a drive query blocked for seconds
    and froze the game at startup (intro) and at council votes.
  - The turn-end distribution loop that made no progress when a candidate was
    skipped by its flags now has a fail-safe iteration counter, like the
    developers' own guard on the neighbouring loop. This was the "random"
    multiplayer freeze after ending a turn.
  The patched binary now has the MD5 `7ceebaafae07e8713c010dcbd01b4d1e`.
- `tools/watch-hang.ps1`: captures a native 32-bit full dump automatically when
  the game freezes (x86 `cdb`, non-invasive, no admin rights).
- `tools/analyze-hang-dump.py`: locates where the game is stuck from such a dump.
- GitHub Actions: CI on JDK 17 and 21 for every push and pull request, and a
  release workflow (tag `v<version>`) that publishes the portable JAR, a
  Windows bundle with an embedded Java runtime (`jpackage`) and `SHA256SUMS`.
- JUnit sanity tests over the patch table: every patch must map inside the
  binary and its `.text` section, and patches must not overlap.
- README chapters *Installing a release (JAR)* and *Diagnosing freezes*.

### Changed
- Java baseline: the project now compiles with `maven.compiler.release` 17
  (build with JDK 17 or newer, 21 recommended) instead of the 1.7
  `source`/`target` pair, which current JDKs no longer accept. The JAR runs on
  Java 17+; the Windows bundle needs no Java at all.
- Compiler, Surefire and JAR plugins updated; tests live under `test/`.

### Fixed
- `PatchSet.calcJmpOrCall` formatted the jump displacement with `%x`, dropping
  leading zeros and corrupting the jump whenever the displacement's low byte is
  `0x00`. Now `%08x`. No existing patch was affected (reported upstream as
  asvitkine/impsave#2).
- `Context` resolves the game folder correctly when ImpSave runs from the
  Windows bundle (`<game>/ImpSave/app/` next to `runtime/`).

## [0.24] - 2021-12-27

Last upstream release by asvitkine; see
<https://github.com/asvitkine/impsave/releases/tag/v0.24>. Earlier history is
not tracked in this file.

[0.25]: https://github.com/Panxatony/impsave/releases/tag/v0.25
[0.24]: https://github.com/asvitkine/impsave/releases/tag/v0.24

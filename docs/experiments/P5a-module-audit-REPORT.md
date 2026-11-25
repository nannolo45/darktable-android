# P5a: module coverage audit (report)

Branch `p5a-module-audit`. Brief: `docs/briefs/P5a-module-audit.md`. The audit itself is
`P5a-module-audit.md`; this file holds only the CLAUDE.md items. No file under `upstream/` changed; no app or bridge
code changed (audit only: a new bridge test, its runner block, a script).

## 1. Asked and delivered

| Item | State |
|---|---|
| 1. `P5a-module-audit.md`: one table per default module group, deprecated ones apart, columns as asked | done (hidden modules in a third short table) |
| 2. `P5a-results/modules.json` produced by `tools/module-audit.py` from the sources, re-runnable for P5b | done (`--check` exits 1 when the sources give a different file) |
| 3. Short list at the top: what to offer the owner first | done, 10 entries, not decided |
| Renders: every module switchable with defaults, bridge vs `darktable-cli`, one Bayer and one X-Trans image, one loop, own file, next free test id | done: **C40**, `engine/tests/dtb_test_p5a.c` (`dtb_test modaudit`) and `engine/tests/p5a_hook.sh`, in `run_tests.sh` for the cr2 and raf cases; added to the Linux build's cache key in `g0-app.yml` |

The verdicts (works / partly / missing), the gap texts and their sizes are the auditor's judgement, kept in one table
(`FINDINGS`) inside the script so P5b diffs them with everything else; every other column is read from the sources.

## 2. How to reproduce

```sh
# the pinned darktable source (only src/ is read; the submodules fetch-darktable.sh adds are not needed)
tools/fetch-darktable.sh /tmp/dt
python3 -I tools/module-audit.py /tmp/dt --report docs/experiments/P5a-module-audit.md
python3 -I tools/module-audit.py /tmp/dt --check          # P5b: after the bump, shows whether anything moved
# C40 on CI (the cached Linux darktable + bridge), only this test:
gh workflow run g0-app.yml --ref p5a-module-audit -f tests="C40"
# or locally on a Linux build:
engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests> <out> C40
```

The C40 files in `P5a-results/` (`renders-*.txt`, `results-*.txt`, `bridge-modules-*.json`) are copied from the CI
logs of run 45666694744 (`ci-logs` branch, `runs/45666694744-a1/g0-linux/p5a_*`).

## 3. Results

CI run **45666694744** (workflow_dispatch, tests `C40`, commit d787bf2): linux job success, 3 passes, 0 failures.

| | Bayer (`mire1.cr2`, 3908x2602) | X-Trans (`xtransIV.raf`) |
|---|---|---|
| modules in the pipe | 97 | 97 |
| switched on with defaults and rendered | 84 | 80 |
| identical to darktable-cli (max difference 0) | 84 | 80 |
| differing | 0 | 0 |
| already on in the test edit (covered by T1) | 13 | 16 |
| no on/off switch | 0 | 1 (raw chromatic aberrations, Bayer-only) |
| bridge stopped (crash) | 0 | 0 |
| time for the loop (x86_64 CI runner, not phone numbers) | 407 s | 1653 s |

Undo after each module returned the edit exactly (modules and history) every time (the driver's check).

Editing on the phone (74 current modules): works 50, partly 19, missing 5. Deprecated (16): works 6, partly 4,
missing 6. Hidden: 7.

Limits, said plainly: C40 checks each module **with its default parameters only**. Modules that need input
(retouch, liquify, spots, colour look up table, LUT 3D, raster masks, composite, color mapping, perspective, crop)
render unchanged with defaults; for them "renders as desktop with input" is C32 (retouch, colour look up table), C33
(perspective), C20 (crop), and **not checked** for the others. Text watermarks on Android (fonts through fontconfig)
are not checked; C40 runs on Linux.

## 4. Upstream surprises

- `src/iop/defringe.c`, `default_group()`: returns `IOP_GROUP_CORRECT | IOP_GROUP_TECHNICAL | IOP_FLAGS_DEPRECATED`.
  The deprecated flag sits in the group bitmask, not in `flags()`, so darktable treats defringe as a current module,
  although its `deprecated_msg()` says to use chromatic aberrations instead. The app follows darktable (listed as
  current, found by search).
- `src/libs/modulegroups.c`, the "modules: all" preset: `AM("profile")` names no module (the operation is
  `profile_gamma`, "unbreak input profile"), so that module is in no group of "modules: all". Legacy "color balance"
  (`colorbalance`, not deprecated) is in no group either. Both are reached by search, on desktop and on the phone.

Found in this repository (not upstream): the override notes of colour look up table, colour zones ("edit by area",
"interpolation method") and retouch ("mask opacity") still say "a later package", but P3e-b and P3m built those
controls in hand-made panels; `tools/schema-unbound-controls.py` therefore still lists them as open. The audit script
accounts for it (`APP_PANEL_CONTROLS`); the notes were not changed (no fixes in this package).

## 5. Open questions for the reviewer

1. C40 runs in a full suite run too (`want C40` is true when no test is named) and adds about 35 minutes on the
   runner (mostly the X-Trans loop). Keep it there, or run it only when named (a one-line change in `run_tests.sh`)?
2. Should C40 also save a small hash of each module's picture against the test edit's, so P5b can see which modules
   change the picture with their defaults (today it only proves bridge = darktable-cli)?
3. The stale override notes (section 4): fix them in P5c with the test hygiene, or leave them?
4. LUT 3D: where should LUT files come from on the phone (a folder on the phone, or synced from the PC through
   LightPhotoLibrary)? This decides the size of that item.

## Reviewer's decisions (25 Nov 2026)

- C40 runs only when it is named (`tests=C40`), not in the full suite (about 35 minutes); P5b names it after the engine bump.
- A picture hash per module for P5b, and the stale override notes: taken into P5b and P5c.
- Where LUT 3D files come from is a question for the owner.

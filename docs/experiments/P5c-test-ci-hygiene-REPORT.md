# P5c: test and CI hygiene, report

Branch `p5c-test-ci-hygiene` (from p5a-module-audit, then origin/main merged after P5a was merged).

## 1. Brief items

| # | Item | Result |
|---|---|---|
| 1 | p2b permission allow-list, `pipefail` | done |
| 2 | Registry of bridge test ids | done |
| 3 | Stale override notes | done |
| 4 | CurvePick fallback row | done |
| 5 | Emulator tap tests | partly: R11 and retouch have one; colour look up table and perspective guides have none |

### 1. Workflows
- `p2b-real-engine.yml`: the permission check has an explicit allow-list (INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, plus the AndroidX receiver permission); anything else fails the step. Before, the check's list lacked the three newer permissions and its `exit 1` ran inside a `{ ... } | tee` group, so it could not have failed the step.
- `set -o pipefail` added to the steps that pipe into tee/grep/sed and rely on the step failing: g0 and p2b "Build the APKs" and "Check and measure the APKs", p2a "Rule check", "Build the interface preview APK", "Check the APK", engine.yml "Fetch darktable source", and the `tools/ci/tags.sh | tee` steps of deps, engine, emulator-test, g0, p2b. Steps that already read `PIPESTATUS` (gradle with retries, apk-only, p2c) were left as they are. `grep -q` after a pipe in the touched APK checks became `grep ... >/dev/null` (with pipefail, `grep -q` ending early can fail its producer with SIGPIPE).
- Consequence worth knowing: p2a's "Rule check" step (`:checkScreenRules | tee`) can now fail the step; before, a failing rule check did not.
- Evidence: p2b run 45682471445 and 45685848830 passed "Check and measure the APKs" with the allow-list.

### 2. Registry
`engine/tests/TEST-IDS.md` (52 ids: id, command, source, brief, what) and `engine/tests/check_test_ids.sh`, run first by `run_tests.sh`. It fails on: an id used in `run_tests.sh` without a row, a row without a test, an id twice in the table, a test block with the same id twice (T6/C6 share one block and are the only exception). C28b (a sub-test inside C28) has its own row. Negative cases tried by hand (second `want C37`, unknown C41, duplicate row): each exits 1. CLAUDE.md has the one line. g0 run 45678355919: bridge tests exit 0, 499 passes, 0 failures with the check in place. Briefs and sources in the table were taken from run_tests.sh comments, the dispatch table of `dtb_test.c` and the file headers (git history is shallow); a wrong brief name in a row is possible for C13 to C16 and C23.

### 3. Override notes
Checked every note against the app. Colour zones ("edit by area", "interpolation method": band editor), retouch ("mask opacity": RetouchPanel) and colour look up table (patch, lightness, green-magenta, blue-yellow, saturation: LutPanel; "target color" left out by design) are built: their notes now say so, with the new class `f`. The tools read class `f`: `schema-unbound-controls.py` no longer lists those 9 controls (list goes from 21 to 12 and says "9 more are built in hand-made panels"), and `module-audit.py` derives `built_in_app_panel` from class `f` instead of its hard-coded table. Base curve and tone curve "scale for graph": the curve editor exists but does not build the scale; text corrected ("not built: the curve editor (P3e) draws the graph linear (P4c)"). Unchanged and still true: colour calibration (colour checker tool), colour balance rgb (mask preview), LUT 3D (LUT folder), all not built; one JVM test asserts the colour calibration text. Re-run of `module-audit.py` with the P5a data: `modules.json` changed only in those `note` texts (11 lines); the audit table `P5a-module-audit.md` did not change. `docs/experiments/P2j-results/unbound-controls-modules-all.txt` regenerated (p2a `--check` passes).

### 4. CurvePick fallback
`CurvePick.REC2020_Y` is now (0.2790405, 0.6753473, 0.0456121). Upstream: `_colorspaces_create_linear_rec2020_rgb_profile` in `src/common/colorspaces.c` (Rec2020_Primaries at D65, lcms adapts the profile to D50) is the profile `dt_ioppr_set_pipe_work_profile_info` in `src/common/iop_profile.c` falls back to ("replaced with linear Rec2020"); the row is the Y row of that profile's matrix. The row is not written as numbers in upstream; a new JVM test computes it from the primaries (Bradford, D50) and agrees within 5e-6 (lcms rounds to s15Fixed16). Two existing tests asserted the old rounded coefficients and were updated to the new row; one (`rgbCurveCompensateMiddleGreyIsTheLabLightnessOver100`) asserted that white maps to exactly 1.0 and now allows 1e-6, because the row sums to 0.9999999. This was the only failure of p2a run 45678362519.

### 5. Emulator tap tests
New `P5cTapTest` (app-engine androidTest, added to `tools/app/emulator-p2b.sh`), Compose test library added to the androidTest dependencies (the same one lpl-shell's instrumented tests use; no other new dependency). Taps are injected by Compose's test library on the real screens; results are read from the engine.
- t1 R11 (P3l): done. Library banner, tap Review, tap Update; the style the stub PC shared is in the engine's `styles()`. Passed in run 45692809310.
- t2 retouch (P3m): done. Tap the modules tab, search "retouch", tap the header, tap "add circle", tap the photo: one heal circle, history 11 to 12, `retouch` in the saved XMP. Passed in run 45692809310. "Move a patch" (dragging the heal source) is not tested: it needs the mapping from image to screen coordinates.
- t3 colour look up table (P3m) and t4 perspective guides (P3n): **no emulator tap test.** Both failed in run 45692809310 (and earlier rounds) before reaching their action: the test appended the module name to the search text that the darkroom keeps from the previous test ("color look up tableretouch"), so no module matched. The fix (replace the text) was committed but not run, because the allowed rounds were used (four emulator rounds: compile error, tab state, search text, and the run above). The tests are removed from the source, not disabled. Not known: whether the long-press menu "delete" and the drawn-lines fit (needs at least 2 lines per direction, MINIMUM_FITLINES) would work by taps.
- Things learned: the darkroom controller is a singleton, so tab and search text leak from test to test; the emulator screen is 320 x 640 px, so panel nodes must be scrolled into view before tapping (`performScrollTo`).
- Run time of the emulator job: before (p2b runs on main, 45665669176 and 45653810928): 15.5 and 15.3 minutes. Run 45692809310: 19.1 minutes (instrumentation 920 s), of which about 3 minutes are the two failing tests waiting out their 90 s timeouts. Expected with t1 and t2 only: about 1 minute more than before, well under the 5 minute limit; not measured on a green run.

## 2. CI runs
| Run | What | Result |
|---|---|---|
| 45678355919 | g0 (items 1 to 4, after P5a branch) | success, bridge tests 499 passes, 0 failures |
| 45678362519 | p2a | failure: the white-point test above (fixed) |
| 45678368443 | p2b | failure: same test in the JVM step |
| 45682477680 | p2a | success |
| 45682471445 | p2b | failure: compile error in the new test file (missing import) |
| 45685848830 | p2b | t1 passed, t2 to t4 failed (modules tab state) |
| 45692809310 | p2b | t1, t2 passed, t3, t4 failed (search text); all 127 other tests passed |
| 45698727039 | g0 (full, without C40) on the merge with main (P5a) | success |
| 45698733053 | p2a on the same | success |

No p2b run was made on the final state (the reviewer closed the emulator rounds); t1 and t2 are unchanged since run 45692809310, t3 and t4 only removed.

# P6c: LUT download option on the phone; flaky P5cTapTest.t3 - report

Branch `p6c-lut-download-option`. Brief: `docs/briefs/P6c-lut-download-option.md`. App and contract text only; no LPL server change is needed (the calls of PLAN 4.6 are unchanged; only the phone decides what to download). LPL's copy of the contract, `docs/luts-r19.md`, has the old phone paragraph and should be updated with PLAN 4.6's new "Phone (app)" paragraph.

## 1. Item by item

| Brief item | State | Where |
|---|---|---|
| Setting "Download LUT files from LightPhotoLibrary automatically", off by default, separate from "sync automatically" | done | `SyncSettings.lutAutoDownload` / `setLutAutoDownload` (key `lut_auto_download` in the same settings file, `auto_sync` kept); switch on `LutFilesScreen`; read by `LutSync` through `LplAgent` |
| Off: a pass downloads nothing; list of files LPL has and the phone lacks with "Get", "Get all" (count and size first) | done | `LutSync.exchange`; list in `LutFilesScreen` (the "Get all" button reads "Get all (2 files, 2.7 MB)"); `LutSync.fetchAll`; "Get from LightPhotoLibrary" in the module unchanged |
| A changed file the phone already has shows "newer version in LightPhotoLibrary" with "Update", never replaced without asking | done | new state `LutSyncState.NEWER_IN_LPL`; "Update" calls `LutSync.fetch` |
| On: a pass downloads only files the phone lacks (not removed here, not .gmz); changed files not downloaded; nothing downloaded again | done | `LutSync.exchange` |
| PLAN 4.6 phone rules rewritten (conflict rules unchanged) | done | `docs/PLAN.md` 4.6 "Phone (app)" |
| JVM tests: off downloads nothing; on only missing; changed never auto-replaced; Update replaces | done | `P6bLutsTest` (21 tests, was 17): `withTheDownloadOptionOffAPassDownloadsNothingEvenOnSyncNow`, `withTheOptionOnOnlyMissingFilesComeAndAChangedOneIsNotReplaced`, `getAndGetAllTakeMissingFilesAndUpdateReplacesAChangedOne`, `theDownloadOptionIsKeptInTheSettingsFileAndAutoSyncKeepsItsOwnValue`; `aPcFileArrives...` changed to expect "offered, not taken". The older P6b tests that rely on a pass downloading run with the option on (`Device(autoDownload = true)`, the default of the test helper) |
| Screenshot of Settings › LUT files in three skins | done | `P6cLutFilesScreenshotTest` (also taps "Update", "Get all", the switch); `phone-{darktable,modern,l1}-p6c-01-lut-files.png` in p2a runs' `screenshots/` |
| Extend `P6bTapTest` with a tap on "Get" in Settings | not done | the emulator tests have no LPL server (`StubLpl` is a JVM test fixture), so Settings has no "Get" to tap there; the JVM test and the Robolectric test above cover Get / Get all / Update |
| Flaky `P5cTapTest.t3`: cause found from the tree dumps and fixed by waiting for the real condition | done | section 3 |

## 2. How to reproduce

- JVM: `cd app && gradle :lpl:test` (p2b runs it: 845 tests, 0 failed, P6bLutsTest 21/21).
- Screenshots: p2a-app (`:darkroom:testDebugUnitTest`), the three PNGs above.
- Emulator: `gh workflow run p2b-real-engine.yml --ref p6c-lut-download-option`.

## 3. The flaky P5cTapTest.t3 (and t4, which has the same code)

Evidence: tree dumps `p5c-tree-t3.txt` of runs 45723595363 and 45775913088 on the `ci-logs` branch: the search field is focused and holds "color look up table", but every node of the module panel is 0 px high (tabs, search row, list at t=370..370) and the whole darkroom column ends at y=405 of the 640 px screen. The missing 235 px are the soft keyboard. With no height the lazy module list composes no row, so `tapDesc("color look up table")` times out. It is a race: the keyboard of the emulator comes up some time after the text is typed.

Why the earlier fix (PR #14, `hideSoftInputFromWindow` in a loop until the row exists) was not enough:
1. `hideSoftInputFromWindow` alone sometimes does not close it.
2. The loop ended as soon as the row existed once, which can be before the keyboard has appeared; the keyboard then comes up, the row disappears again, and `tapDesc` waits 90 s without closing the keyboard (seen as t4 in run 45805096711, where no waiting record was written at all).
3. While trying a version that only acted when the window insets said "keyboard visible", p2b run 45799493184 (t4) showed the insets reported no keyboard for 90 s while the input method service had `mInputShown=true`; the insets cannot be used as the condition.

Fix (`TestFiles.waitRowAndKeyboardGone`, used by `P5cTapTest` and `P6bTapTest`): after typing, repeat until the row exists AND `dumpsys input_method` says the keyboard is not shown, on two checks in a row; each round takes the focus off the search field, calls `hideSoftInputFromWindow`, asks the window's insets controller, and every 10th round sends Back while the service says the keyboard is shown. If it ever times out, `p5c-results.txt` gets a `<tag>_module_wait` line with the service's state (`mInputShown`, `mCurFocusedWindow`, ...).

## 4. CI

- p2a-app 45800966553 and 45805135184: green (the first p2a, 45799489133, failed in `checkScreenRules` configuration because Maven Central answered 429; rerun).
- p2b-real-engine:
  - 45799493184 (insets-gated version): JVM 845 tests 0 failed; emulator 132/133, t3 passed, t4 failed (insets did not report the keyboard).
  - 45805096711 (row-only wait): emulator 132/133, t4 failed (keyboard came after the row was first seen).
  - final test code, commit 55a3665bb2: **45811029127** green (133/133, t3 and t4 passed, P6bTapTest t1 and t2 passed); **45815990998** see the pull request text / last line below.

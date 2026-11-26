# Brief P6c: LUT download option on the phone; flaky tap test

Branch `p6c-lut-download-option` from `main`. Read CLAUDE.md, PLAN.md 4.6 (R19) and the P6b report. Small package; app and contract text only, no LPL server change expected (say so if one is needed).

## 1. Owner's decision (26 Nov 2026)
"I'd rather have the option to sync or not, and just download missing ones, not all of them all the time." So on the phone:
- A setting in Settings › LUT files: **"Download LUT files from LightPhotoLibrary automatically"**, **off by default**. It is separate from the general "sync automatically" (that one still governs uploads of the phone's own LUT files and the state report).
- **Off:** a sync pass downloads nothing. Files come to the phone only when asked: "Get from LightPhotoLibrary" on the missing-file line of the module (as now), and a list in Settings › LUT files of the files LightPhotoLibrary has that the phone lacks, each with "Get" (and "Get all" with the count and total size shown first). A file the phone already has and that changed in LPL shows "newer version in LightPhotoLibrary" with "Update" (never replaced without asking).
- **On:** a sync pass downloads only files the phone **lacks** (not removed here, not `.gmz`), as before; a file the phone already has and that changed in LPL is **not** downloaded automatically either: it shows "newer version" with "Update" as above. Nothing already on the phone is downloaded again.
- Change PLAN 4.6's phone rules to say exactly this (the conflict rules stay: a phone-side change and an LPL-side change of the same path still give mine / theirs / keep both).
- Tests: JVM for both settings (nothing downloaded when off; only missing when on; a changed file never auto-replaced; Update replaces it), and the screenshot of Settings › LUT files in the three skins. Extend `P6bTapTest` with one tap on "Get" in Settings if it fits in under a minute of emulator time.

## 2. Flaky `P5cTapTest.t3`
`t3` (colour look up table: patch deleted by long press) failed on main in p2b 45723595363 and once on the P6b branch, after PR #14's keyboard fix: the module row did not appear after the search. Find the cause from the tree dumps in the `ci-logs` branch (runs/45723595363-a1/p2b-emulator/p2b-test/ and the P6b run 45775913088) and fix the test so it waits for the real condition (not a longer timeout alone). Run p2b at least twice on the final commit; both green.

## Economy and report
One agent, no sub-agents. `docs/experiments/P6c-lut-download-option-REPORT.md` as CLAUDE.md says, with CI run ids.

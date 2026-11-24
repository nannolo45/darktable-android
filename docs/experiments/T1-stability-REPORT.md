# T1 report: test stability and two follow-ups

Branch `t1-stability` (from `main` ff4eb14). Final code commit: 09509b14; this report is the only later change.

## 1. Brief items
1. Flaky emulator tests: done (aaf1ac74).
   - P3e tone equalizer drag: the app dropped a quick drag (DarkroomController.kt); fixed in the app.
   - P3c exposure spot: the test read the pick before the area pick landed; PickerSession exposes a pick counter and P3cScenarioTest waits on it. No assertion loosened.
2. C28 follow-up (undo, redo, compress at a lower history end): done. Bridge test `undomask` (engine/tests/dtb_test_p3h.c, run_tests.sh C28b) checks the picture and the XMP against darktable-cli after undo, redo and compress. Undo/redo lost the masks above the end; fixed in 09509b14. Compress at a lower end drops the items above the end, as darktable desktop does (dt_history_compress_on_image), so no mask survives there by design; the test asserts the picture at that end is unchanged and equals darktable-cli.
3. CI: done. p2b-real-engine artifact is named `p2b-emulator-${{ github.run_attempt }}` in the upload and the compare download. Limit: if only the compare job is rerun alone, the artifact of the earlier attempt has another name and is not found; rerun the whole workflow.

## 2. Reproduce
`engine/tests/run_tests.sh` (C28b); `gh run rerun <id>` for the workflows below.

## 3. CI evidence (all on commit 09509b14, GitHub x86_64 runners)
- p2b-real-engine run 45362252696: attempts 1, 3 and 4 all success (attempt 2 was cancelled by the earlier agent stop).
- g0-app run 45362252695: attempt 2 success.
- p2a-app: run 45352474580 success on aaf1ac74 (the later commit changes only engine files, outside its paths); dispatch on 09509b14: run 45382481570 success.

## 4. Surprises
None in upstream.

## 5. Open questions
Should compare also tolerate a lone rerun of its own job (look up the latest `p2b-emulator-*`)? Not done: out of scope.

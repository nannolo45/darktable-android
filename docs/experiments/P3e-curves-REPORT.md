# P3e-a: the shared curve editor (pull request description)

Branch `p3e-curves`. Last code commit: see "CI" below. No pull request was opened (the brief forbids it); this file is the description.
Measurements here are x86_64 emulator and CI numbers, not phone numbers. The darktable user manual was NOT read for this package; the desktop source (tonecurve.c, rgbcurve.c, rgblevels.c, toneequal.c, choleski.h, luminance_mask.h) was followed instead, and the places where the app differs are listed under "Differences".

## 1. What the brief asked, item by item

1. One curve editor component (44 px nodes, drag, tap to add, long press or drag off the graph to remove, double tap to reset, one history item per lifted finger, order and limits kept): **done**. `ui/graph/CurveEditor.kt`, model in `CurveModel.kt`.
2. Tone curve: channel tabs L, a, b, interpolation, existing controls stay: **done**.
3. RGB curve: channel tabs, linked/independent, `set_nodes_from_area` through P3c's picker: **done**.
4. RGB levels: three handles over the histogram, per channel when independent, pickers stay: **done**.
5. Tone equalizer: nine-band graph on the `advanced` tab, smoothing as desktop, vertical drag on the photo editing the band of the exposure under the finger: **done** (picker-based: `toneEqStart/Move/End` in `DarkroomController`).
6. "Control not available" placeholders replaced, l1 layout shows the editor in the open module: **done** (`Controls.kt` Unsupported branch).
7. Tests: JVM tests on the stand-in engine, screenshots (both skins on phone, one tablet), emulator with the real engine, darktable-cli compare per module: **done**, see 3.
8. Docs: brief copied, `docs/design/ui-spec.md` section 11 (bands from P3e-b keep section 10), this report: **done**.

## 2. How to reproduce
- Rules: `python3 tools/app/check-screen-rules.py`
- JVM and screenshot tests: workflow `p2a-app` (Gradle `test`, `P3eCurvesTest`, `CurveModelTest`, `P3eCurvesScreenshotTest`).
- Real engine: workflow `p2b-real-engine` (emulator job runs `P3eScenarioTest`; the compare job renders `p3e-tonecurve`, `p3e-rgbcurve`, `p3e-rgblevels`, `p3e-toneeq` with darktable-cli and compares with the app's export).
- Logs: branch `ci-logs`, `runs/<run id>-a1/`.

## 3. Results
See the CI section at the end (run ids filled in from the last runs). Compare cases (picture difference app export vs darktable-cli of the same XMP, 3908x2602): tonecurve PSNR 43.4, rgblevels 43.2, toneeq 43.6; for comparison P3d masks 44 to 45 and P3e-b bands 43.6 to 46.1 in the same run. Emulator: tone curve drag changes the picture by mean 46.0 (0..255), tone equalizer drag by 12.6, levels black 0.3 on green by 35.9; one history item per drag in each.

## 4. Surprises in upstream / the engine
- `set_nodes_from_area` (engine/src/actions3.c, `_act_rgbcurve_nodes`) can leave two nodes at x = 1.0 (the default end node and the picked maximum). Run 45110625053 / emulator: 6 nodes `..., (1,1), (1,1)`. The render of such a curve differs wildly between the app's export and darktable-cli (PSNR 5.9, run 45113113113), so the curve is undefined. No engine change was allowed; the app now drops the later node before sealing the history item (`DarkroomController.dropDuplicateNodes`), and the editor's `CurveLayout.read` also tolerates it. The C test (`dtb_test_p1b2.c`) accepts 5 or 6 nodes, so this was known but not guarded. Proposal: the engine skips a node whose x equals an existing one (desktop's `_add_node`), then the app guard can go.
- Enum parameters must be written as constant names (`DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS`); a number is silently ignored. The stand-in accepts numbers, so JVM tests cannot catch this. The editor's own writes use names.
- The fake engine cannot infer the two-index array shapes of these modules from the schema; `FakeModuleCatalog.SHAPES` gives them (the real engine is unaffected).

## 5. Differences from desktop (no manual read)
- A node dragged past its neighbour stops at a gap of 0.001 instead of deleting it; removal is by long press or by dragging off the graph.
- Tone curve a and b are editable only with independent channels, as desktop; double tap on a dimmed graph switches to independent.
- The tone equalizer photo drag works at fit zoom only, with no picker, shape drawing or crop active.

## 6. Open questions
- Should the engine skip duplicate x in `set_nodes_from_area` (then the app guard is dead code)?
- Screenshots in CI are of the stand-in engine; real-engine screenshots of these modules exist only for the compare cases (no emulator screenshots).
- Merge with main (P3e-b bands, f54750b3) conflicts, all resolved keeping both sides: `p2b-real-engine.yml` (two compare loops, the fail condition and the log tag carry `p3e` and `p3eb`), `DarkroomController.kt` (`Drag.writes` is main's list form; the curve writes are converted with `toList()`; one `writeDrag`), `Controls.kt` (Unsupported branch: picker, then the band editor, then the curve editor), `emulator-p2b.sh` (both scenario classes), `ui-spec.md` (bands 10, curves 11). `tokens.json`, `Tokens.kt`, `Theme.kt` merged without conflict.

## CI
Last code commit: 4cbd3a97 (merged with main f54750b3).
- p2a-app run 45119443708: success.
- p2b-real-engine run 45119443688: success (apk, emulator `P3eScenarioTest` 4 of 4, compare).
Earlier failures, fixed: p2a 45103530155 (curve touch test: node under the tab bar, off screen; test moved to the visible half); p2b 45104818505 and 45107163626 (test used the params after closing the photo; levels needed the enum name); p2b 45113113113 (rgbcurve compare PSNR 5.9: duplicate end node, fixed in the app); p2b 45115700738 (pick and clean-up were two history items; now one history group).
Compare in the last run: see `runs/45119443688-a1/p2b-compare/compare.txt` on `ci-logs`.

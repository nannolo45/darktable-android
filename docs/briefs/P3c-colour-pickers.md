# P3c: colour pickers on the photo

The interface for the picker calls of P1b part 2 (`docs/design/engine-api.md` section 6.3, 6.4 and the module
actions; report `docs/experiments/P1b2-engine-masks-pickers-REPORT.md`). The owner (20 Nov 2026): "color pickers are
not implemented (like in color calibration, tone curve, and others) but they are very useful". Branch
`p3c-colour-pickers` from `main`. Report `docs/experiments/P3c-colour-pickers-REPORT.md`, running log
`docs/experiments/P3c-colour-pickers.md` (pushed at least every 30 minutes). **No change under `engine/src`,
`engine/include` or `upstream/`**; `engine/tests` may change for deliverable 6 only. If a call is missing or wrong,
describe it in the report and build against the fake engine.

## Reference
The darktable user manual: "color pickers" in the processing modules' common controls (point and area, the picker
button on a control, one picker active at a time, ctrl-click for an area on desktop), and the pages of the modules
below. On desktop a picker belongs to a control; the app keeps that.

## Deliverables
1. **One picker tool on the photo**, shared by every module: a picker button on each control that has one on desktop
   (the control schema marks them; say how, and extend the extractor's data through `schema/overrides/` where it does
   not). Tapping it arms the picker for that control: the photo shows a marker; **a tap places a point, a drag draws
   an area** (a rectangle with handles that can be moved and resized afterwards, like the crop frame's); the module's
   value updates as desktop's does; tapping the button again or leaving the module disarms it. Only one picker is
   armed at a time. Pinch and pan keep working (a drag that starts on the marker or the area belongs to the picker).
   The picked area is in image coordinates (`coords_transform`), so it stays on the same part of the picture when the
   view is zoomed or the edit changes the geometry.
2. **The modules the engine has actions for**, each as desktop's own picker does, with the wording of its controls:
   white balance (from an area); exposure (spot: area mode "correction" and "measure", target lightness); rgb levels
   (black, grey, white); rgb curve (nodes from an area); color calibration (illuminant from an area; area mapping in
   correction mode with its target controls). The P2j controls that said "needs the colour picker" become usable
   where their action exists; the rest keep their line. Update the kept list of
   `tools/schema-unbound-controls.py`.
3. **Pickers that only set a slider from the picked value** (the plain picker button many sliders carry on desktop,
   for example a hue or a lightness taken from the picture): built generically from `color_pick` and the schema's
   knowledge of what the slider takes (cite upstream's `dt_iop_color_picker` callbacks per module you cover). Cover
   the modules of the owner's edits first: color balance rgb, color equalizer, tone equalizer, filmic/sigmoid/AgX
   where they have pickers, color calibration's remaining pickers. List what is covered and what is not.
4. **A readout**: with a picker armed, the picked values (as desktop's global colour picker shows them: RGB and Lab,
   mean, and min and max for an area) in the line under the photo.
5. **Second priority: the three controls P3b left** (its report, open question 4): a control kind that reads a
   module action per photo, for input colour profile's list, denoise (profiled)'s current profile and white
   balance's fine tuning.
6. **The arithmetic of the engine's picker actions against upstream's own code** (review of P1b part 2): the bridge
   re-implements what desktop's callbacks compute. Add an engine test that compiles upstream's functions verbatim
   (as `tools/p3a-crop-reference.py` does for crop) for each action where upstream's code can be isolated, and
   compares on the three test images; where it cannot be isolated, say why. Report any difference first.
7. **Tests.** JVM: arming, one at a time, point and area gestures, coordinates under zoom and under a crop and a
   rotation, each module's action called with the right area, the read-only case; screenshot tests (phone and
   tablet) of an armed point and an armed area. Emulator: white balance from an area and exposure's spot through
   real touches, the XMP written, darktable-cli's render equal to the app's export within the usual bound; the
   picked area's values equal the engine test's for the same area. Both workflows green on the last code commit; APK
   from the usual release step; owner instructions in the report.

## Not in this package
Masks and blending screens; graph editors (the curve modules get their picker here only as far as it sets values;
their graphs come later); color calibration's "measure" mode and the colour checker tool (not in the engine).

## Rules
`CLAUDE.md`. Colours, sizes and icons through tokens and the icon registry; no module names in screen code.
Nothing personal in the repository. SPDX line on new files. Do not weaken a test. Never push to `main`, no pull
requests, no throwaway branches left on the remote. Report shortfalls first, plainly. The build workspace has no
Android SDK: darkroom and app tests run only in CI. Maven Central and the package mirrors are flaky in CI: rerun
before suspecting the change.

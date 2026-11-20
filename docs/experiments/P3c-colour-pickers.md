# P3c: colour pickers on the photo (running log)

Brief: `docs/briefs/P3c-colour-pickers.md`. Branch `p3c-colour-pickers` from main 5fe4c41. Report (pull request
text): `docs/experiments/P3c-colour-pickers-REPORT.md`.

Measurements labelled "workspace" are x86_64, 2 cores; "CI" are GitHub runners; "emulator" is CI's x86_64 Android
emulator. None is a phone.

## Log

- Start. Read CLAUDE.md, the brief, engine-api.md 6.3 to 6.5, the P1b part 2 report, ui-spec.md, the crop tool
  (CropSession.kt, Photo.kt, CropTool.kt), the P2j overrides and Computed.kt. Upstream at 04477d3 read for
  `gui/color_picker_proxy.c` and the modules' `color_picker_apply` / `dt_color_picker_new` calls. Branch added to the
  push triggers of p2a-app.yml and p2b-real-engine.yml (to be removed before merging).
- Schema: the extractor already marks every control with a desktop picker: `quad.colorpicker` on sliders and lists,
  kind `colorpicker_button` for picker toggle buttons (78 marks in 25 modules, equal to the extractor's
  `colorpicker_buttons` + `colorpicker_quads` counts). Read into `ModuleSchema.pickerMarks`. New override operations
  `picker` and `picker_setting` (`P3cOverrides.kt`, README); overrides for temperature, exposure, rgblevels (new
  file), rgbcurve, channelmixerrgb, tonecurve and colorzones ("show color" pickers: values only).
- Upstream (04477d3) `gui/color_picker_proxy.c`: arming a module picker switches the module on and samples at once
  (point in the middle, or the area 2 %..98 % from `dt_lib_colorpicker_reset_box_area`, or the picker's last
  selection), unless `DT_COLOR_PICKER_DEFERRED_AREA`; the box is kept in original-image coordinates
  (`dt_color_picker_backtransform_box`, 8 floats = 4 corners). The app does the same.
- Darkroom: `PickerState`, controller section "brief P3c", gestures in `ui/Photo.kt`, picker buttons in
  `ui/Controls.kt`, readout in the line under the photo (phone) / under the photo (tablet).
- CI p2a 44774490433: compile error (smart casts across modules) fixed; 44775851752: 4 of 13 new JVM tests failed
  (test helpers counted get_wb reads; the stand-in keeps rgblevels' levels[3][3] as one row of nine, so channels
  1 and 2 are answered in the test's engine wrapper; rotation test compared floats at 1e-9); screenshots passed and
  were looked at (phone and tablet, point and area).
- Deliverable 6: `engine/tests/pickref_extract.py` copies the callbacks verbatim at build time; `pickref_<op>.c`
  stub the interface (pickref_stubs.h) and run them on a module copy; `dtb_pickref` compares with the bridge's
  action field by field (C25). Checked locally with `gcc -fsyntax-only -Wall` against darktable's headers (darktable
  configured, not built, in /tmp on the workspace): no error, no warning. Linking and running: CI only.
- Emulator: `P3cScenarioTest` (white balance and exposure by injected touches, picks for fixed areas); p2b compare
  job: darktable-cli renders of p3c-wb.xmp and p3c-exposure.xmp, picks against `dtb_pickref` on Linux.

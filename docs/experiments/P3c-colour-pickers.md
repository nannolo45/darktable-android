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

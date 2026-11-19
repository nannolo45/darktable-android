# P2d: module layouts as in darktable

Brief: `docs/briefs/P2d-module-layouts.md`. Branch `p2d-module-layouts`. Pull request text:
`P2d-module-layouts-REPORT.md`.

## Running log (newest first)

- 2026-11-19: layout model and codec, built-in layouts, quick access mapping, `.dtpreset` read/write in
  `app/schema` (package `layout`), `LayoutStore` + `FileLayoutStore` in `engine-api`. A real darktable
  (Linux build at 04477d3, own config dirs) stores exactly the 30 built-in layouts of the data file, exported
  a layout with its own code (fixture `desktop-export.dtpreset`) and imported the app's two files
  (`docs/experiments/P2d-results/verify-with-darktable.txt`). Schema and engine-api tests pass here.
- 2026-11-19: `tools/layouts/extract-builtin-layouts.py` and `schema/builtin-layouts.json`.
- 2026-11-19: started. Read CLAUDE.md, the brief, PLAN 3.4 to 3.6 and 3.9, ui-spec, engine-api section 3,
  P2a write-up and report, S5 write-up, upstream `src/libs/modulegroups.c`, `src/common/presets.c`,
  `src/gui/preferences.c`, `src/gui/presets.c`, `src/libs/lib.c` at 04477d3. Branch created.

Next: store tests, darkroom on the current layout, layout editor, import/export in the app, CI.

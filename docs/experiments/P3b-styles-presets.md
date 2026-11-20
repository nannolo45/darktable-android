# P3b: styles and presets in the app (running log)

Brief: `docs/briefs/P3b-styles-presets-screens.md`. Report: `P3b-styles-presets-REPORT.md`. Branch
`p3b-styles-presets` from main 6667ad0.

Measurements: **CI** = GitHub runners (x86_64); **emulator** = CI's x86_64 Android emulator (API 33). None is a phone.

## 20 Nov 2026

- Read CLAUDE.md, the brief, engine-api.md section 5, PresetsStyles.kt, DarktableEngine.kt, FakeEngine.kt, PLAN.md 3.9,
  ui-spec.md, the P1b report, Sheets.kt, ModuleList.kt, the layout editor and its import.
- Upstream reference (pinned commit 04477d3): `src/gui/presets.c` (`dt_gui_presets_popup_menu_show_internal`, the
  iop menu's `_iop_is_disabled` = other module version, `_iop_is_active`; rows from `dt_presets_menu_rows_iop`, which
  leaves out presets whose filter does not match the image), `src/libs/styles.c` (apply mode combobox
  `plugins/lighttable/style/applymode`, default 0 = append; import's "overwrite style?" dialog with "apply this
  option to all existing styles"; remove's "remove style?"), `src/gui/styles_dialog.c` (create: name, description,
  items with "include"), `src/views/darkroom.c` (quick styles: `dt_styles_apply_to_dev`, always append, message
  "applied style `%s' on current image"), `src/common/utility.c` `dt_util_localize_segmented_name` (shown names
  drop `_builtin_` and each segment's `_l10n_`).
- darktable's 534 shipped styles are all named `_l10n_darktable|…` (data/styles at the pin).
- Branch added to the push triggers of p2a-app.yml and p2b-real-engine.yml.
- First code: `darkroom/StylesPresets.kt` (logic: names as desktop shows them, the folding tree, presets and styles
  actions, the import with desktop's overwrite question, favourites/recent/apply mode in the app's settings),
  `darkroom/ui/StylesSheets.kt` (sheets on the phone, the right panel on the tablet), "styles…" in the photo menu,
  two controller hooks (`engineRead`, `engineChange`, beside `stylesPresets`), the real app's file pickers (styles and
  presets; module layouts, which the real app had not wired), icons `favorite`/`favoriteOn`. Tests: JVM
  `P3bStylesPresetsTest` (10), screenshots `P3bScreenshotTest` (phone and tablet), emulator `P3bScenarioTest` (3), and
  the p3b comparison in p2b-real-engine's compare job. Nothing compiled on the workspace (no Android SDK): first CI run.

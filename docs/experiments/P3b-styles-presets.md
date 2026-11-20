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
- Run p2a-app 44746037935 (a8fa9dd): rule check, JVM and screenshot tests all passed (P3bStylesPresetsTest 10 of 10,
  PhoneP3bScreenshots 5, TabletP3bScreenshots 5); screenshots looked at (ci-logs `runs/44746037935-a1/p2a-app/screenshots/*p3b*`).
  p2b-real-engine 44746037680 failed to compile app-engine: in `AppHost`, `app` is the Application property, so the
  fully qualified `app.dtandroid.darkroom.FileStyleSettings` did not resolve. Fixed with an import.
- Module layouts in an import (desktop's preferences → presets → export writes them as `modulegroups_<name>.dtpreset`
  next to the presets) now go into the layout store as "manage module layouts → import…" does, instead of a line
  saying where to import them.
- p2b-real-engine 44747923468 (fa83d3b): **success**, every job; emulator 59 of 59 (P3bScenarioTest s1-s3 included);
  compare: p3b style append 47.72 dB, overwrite 48.25 dB (bound 40 dB). styles() on the emulator: 534 styles,
  109 / 53 / 45 ms (first, then twice more); read through the darkroom 79 ms; the list from the cache 5 µs.
  p2a-app 44747923709 (fa83d3b): success. Deliverables 1-4 done and green: deliverable 6 started.
- Deliverable 6, part: the two P2j controls whose values are fixed writes outside a field's limits now go through
  the engine's module actions: framing's "image" and "constant border" (`borders set_aspect` 0 / -1) and lens'
  "use latest algorithm" (`lens use_latest_md`, shown while md_version is the old one, as gui_changed). A
  `ModuleWrite` (`action` in the override JSON) on value-list entries and switches; the controller's
  chooseComputed / toggleComputed call `module_action` for them. Schema JVM tests run on the workspace (the schema
  module is pure Kotlin): 60 tests, 0 failed, P2jComputedTest 17. **Test changed on purpose:**
  `P2jComputedTest.framingListsAndPositions` asserted that "image" and "constant border" are greyed out; they are
  now enabled and write through set_aspect (the assertion now checks that). Not done of deliverable 6: input colour
  profile's list, denoise (profiled)'s current profile, white balance's fine tuning (lists or values the app must
  read from the engine per photo; no model for that in the schema's computed controls yet).
- `tools/schema-unbound-controls.py`'s kept list (`docs/experiments/P2j-results/unbound-controls-modules-all.txt`)
  regenerated: lens' "use latest algorithm" leaves it (33 → 32 controls; class c 4 → 3).

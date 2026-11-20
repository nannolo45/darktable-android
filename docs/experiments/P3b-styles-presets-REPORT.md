# P3b: styles and presets in the app — report (pull request description)

Branch `p3b-styles-presets` from `main` at 6667ad0. Brief: `docs/briefs/P3b-styles-presets-screens.md`. Running log:
`docs/experiments/P3b-styles-presets.md`. Last code commit **d0ae88e**; runs on it: p2a-app **44751209350**
(success: rule check, unbound-controls check, 267 JVM and screenshot tests, 0 failed), p2b-real-engine
**44751209351** (success, every job: JVM tests and rule check, APKs, emulator 60 of 60, darktable-cli comparisons). APK release tag **dt-android-d0ae88e7d08a** (interface preview:
app-preview-d0ae88e7d08a).

No change under `engine/` or `upstream/`, **none under `app/engine-api` or `app/engine-jni`** (every call the brief
needs exists there), no new dependency. Changed outside the darkroom: `app/app-engine` (the file pickers, the
settings file), `app/schema` and `schema/overrides/` (deliverable 6), `app/ui-icons/svg` (two icons), the two
workflows (branch trigger; the p3b comparison) and `tools/app/emulator-p2b.sh` (the new scenario class).

## 1. Failures and limits first

1. **Deliverable 6 is partly done: 2 of the 5 controls.** Done: framing's "image" and "constant border" (through
   `borders set_aspect`) and lens correction's "use latest algorithm" (through `lens use_latest_md`). **Not done:**
   input colour profile's list, denoise (profiled)'s current profile, white balance's fine tuning. Those three need
   the app to read a list or value from the engine per photo (`colorin list_profiles`, `denoiseprofile
   list_profiles` with `current`, `temperature get_wb` `tuning`) and show it in a list or slider; the schema's computed
   controls are built from parameters only, so this needs a new kind of computed control fed by a module action
   (open question 4). The kept list of `tools/schema-unbound-controls.py` was regenerated for the part that is done.
2. **One existing test changed on purpose:** `P2jComputedTest.framingListsAndPositions` asserted that framing's
   "image" and "constant border" are greyed out. They are now usable (deliverable 6), so the test asserts that they
   are enabled and that they write `set_aspect` 0 and -1. Nothing else was changed, weakened, skipped or deleted.
3. **Presets whose filter does not match the photo are left out of the list, not shown marked.** Desktop's menu
   query (`dt_presets_menu_rows_iop`) leaves them out; what desktop marks is a preset stored for another version of
   the module (greyed out, cannot be applied: `_iop_is_disabled`), and the app does the same.
4. **A preset's description cannot be edited, nor its filter and auto-apply** (the latter is outside the brief).
   Desktop's "edit this preset…" is one dialog (name, description, filters); the app's edit page offers "rename…"
   and "delete this preset" only. "update preset" is offered for the user's own presets only (desktop also offers it
   for a built-in one and then refuses it as write-protected).
5. **darktable's 534 shipped styles cannot be removed in the app.** Desktop can remove them, but
   `dt_import_default_styles` brings every missing one back at the next start (P1b), so the app does not offer it and
   says why. They sit in one folded "darktable" folder.
6. **Names are not translated:** desktop translates built-in preset names and the shipped styles' segments
   (`_builtin_`, `_l10n_`); the app has no translations and only drops those prefixes.
7. **Not built (outside the brief or no engine call):** exporting a style, exporting a preset from the phone, a
   picture preview per style.
8. Measurements are the CI emulator's (x86_64, 4 cores), not a phone's (section 4).
9. Difference found between the stand-in engine and the real one, left as it is (`app/engine-api` untouched):
   `presetStore(overwrite = true)` with an empty description keeps the stored description in FakeEngine and clears
   it in the bridge (`engine/src/presets.c` writes any non-NULL description). The app always passes the preset's
   current description for "update", so both behave the same through the app.
10. On the phone the styles sheet covers the lower half of the photo (the sheet's height token); applying a style
    closes it so the photo is seen. On the tablet the list stays beside the photo.

## 2. Brief item by item

| # | Item | State | Evidence |
|---|---|---|---|
| 1 | Presets on a module: desktop's menu (built-in, then the user's; `|` folders; other version greyed out; current one checked), tap applies (one history item), store new (name, description), update, rename, delete with desktop's confirmations; read-only: list shown, nothing applied | **done** (limits 3, 4) | JVM `presetListAndApplyIsOneHistoryItem`, `storeOverwriteUpdateRenameDelete`, `readOnlyPhotoListsButAppliesNothing`; screenshots p3b-01, 02, 09; emulator s3 |
| 2 | Styles: "styles…" in the darkroom's menu; tree that folds by `|`; search over names and descriptions; list opens and scrolls without a wait (time measured, cache said); a style's content by module display name; apply append / overwrite (default as desktop's preference, remembered), one undo step; create from this edit (name, description, items); delete the user's own | **done** (limit 5) | JVM `importTreeSearchApplyBothModesUndo`, `createAndDeleteStyle`, `prefetchReadsTheListOnceAndFavouritesComeFirst`, `treeOfNamesThatAreAlsoFolders`; screenshots p3b-03 to 06, 10; emulator s1, s2 |
| 3 | Import `.dtstyle` (one or many) and `.dtpreset` through Android's file picker; a result line per file (imported, replaced, refused and why); asks before replacing, with "for all" | **done** | JVM `importAsksBeforeReplacingWithForAll`, `presetFilesImportAndRefusals`; screenshots p3b-07, 08; emulator s2 |
| 4 | Recently used (last 5) on top and a star for favourites, in the app's settings | **done** | JVM `settingsFileKeepsFavouritesRecentAndMode`, `prefetch…`; screenshot p3b-03 |
| 5 | Tablet: the list beside the photo | **done** | tablet screenshots p3b-01 to 06 |
| 6 | The P2j controls that waited for the engine | **partly**: framing's two entries, lens' "use latest algorithm"; not the input profile list, denoise's current profile, white balance's fine tuning (limit 1) | schema `P2jComputedTest` (17), JVM `framingImageAndConstantBorderAndLensLatestAlgorithmGoThroughModuleActions`, emulator s4; kept list regenerated |
| 7 | Tests: JVM for every action, refusals and read-only; screenshots (preset list, style tree, detail, import result; phone and tablet); emulator import, both modes, XMP, darktable-cli equals the app's export within the usual bound; a preset stored and applied on a second instance; both workflows green; APK; owner instructions | **done** | sections 3 to 6 |

**What the sheets do (darktable's behaviour and words, `src/gui/presets.c`, `src/libs/styles.c`,
`src/gui/styles_dialog.c`, `src/views/darkroom.c` at the pinned commit):**
- *Presets* (the ≡ icon on a module's header, as before): built-in presets, then "your presets"; a folder per `|`;
  the preset equal to the module's settings has a check mark; tap applies it and closes the list. Tail as desktop:
  when the current preset is the user's, "edit this preset…" and "delete this preset"; otherwise "store new preset…"
  and "update preset <the last one applied here>". Each user preset also has a pencil (rename…, delete). Desktop's
  questions: "overwrite preset?" / "preset `x' already exists. do you want to overwrite?", "update preset?",
  "delete preset?"; "please give preset a name" for an empty name or desktop's placeholder "new preset".
- *Styles* (the photo menu ⋮ → "styles…"): "filter style names" line; "create style from this edit…" and
  "import…"; then favourites, recently used, and all styles as a folding tree with the number of styles in each
  folder. Tapping a style shows its description, its modules by display name (an item the engine cannot load is
  greyed with the reason), "includes a module order" when it has one, the mode (append / overwrite, remembered) and
  "apply"; also "add to favorites" and "remove" (the user's own). Applying says desktop's
  "applied style `x' on current image"; undo takes it back in one step.
- *Create style:* name, description, the history items up to the current one with "include" boxes, "select all" /
  "select none" (desktop's dialog); an existing name asks "overwrite style?"; "can't create style out of unaltered
  image" when the history is empty.
- *Import* (from either sheet): any mix of `.dtstyle` and `.dtpreset` files, and module layouts exported with the
  presets (`modulegroups_<name>.dtpreset`, stored as "manage module layouts → import…" does). A name that exists asks
  desktop's "overwrite style?" / "overwrite preset?" with "overwrite", "skip", "cancel" and "apply this option to
  all existing styles" (for the rest of that import). Result per file: imported / replaced / skipped / refused and
  why: not a darktable style or preset file, could not be read, a module unknown to this app, a module version newer
  than this app's, a built-in preset's name; a style with such a module is imported (as desktop) and the line says it
  cannot be applied here and why; a preset stored for an older module version is imported and greyed out.
- The real app's darkroom also gets the file picker for "manage module layouts → import… / export…", which only the
  interface preview had (the real app passed no picker, so those entries were missing there).

**Cache:** the style list is read once per app process, in the background as soon as a photo is open (the
darkroom's background work, which waits for the user's work), and again only after an import, a creation or a
removal. Opening the list reads nothing; the list is lazy (rows are composed as they scroll in).

## 3. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p3b-styles-presets
# schema tests (pure Kotlin, run on the workspace)
cd app && gradle --offline --no-daemon :schema:test
# the kept list of controls still waiting
python3 -I tools/schema-unbound-controls.py "scene-referred (sigmoid)" "modules: all" --check docs/experiments/P2j-results/unbound-controls-modules-all.txt
# darkroom JVM and screenshot tests need the Android SDK (CI): p2a-app.yml step "Unit and screenshot tests"
$GRADLE --no-daemon :engine-api:test :schema:test :ui-theme:testDebugUnitTest :darkroom:testDebugUnitTest
# emulator and darktable-cli comparison: p2b-real-engine.yml (tools/app/emulator-p2b.sh runs P3bScenarioTest;
# the compare job renders p3b-append.xmp and p3b-overwrite.xmp with darktable-cli)
```
Screenshots: ci-logs branch `runs/44751209350-a1/p2a-app/screenshots/*p3b*`; emulator results:
`runs/44751209351-a1/p2b-emulator/p2b-test/p3b-results.txt`; comparisons: `runs/44751209351-a1/p2b-compare/compare.txt`.

## 4. Results with numbers

**Time to list the styles** (emulator x86_64, 4 cores; the engine with darktable's 534 shipped styles, run
44751209351, `p3b-results.txt`): `styles()` 94 ms the first time, then 60 and 52 ms; through the darkroom
122 ms (of which `styles()` 119 ms). The darkroom reads it once in the background
when a photo opens; **opening the list after that takes 6 µs** (the cache), and the lazy list composes only the rows
on screen. The earlier run 44747923468 measured 109 / 53 / 45 ms and 79 ms. Not measured on a phone.

**Import** of 6 files at once (a public style, three of darktable's example styles renamed as a pack, one with a
module no darktable knows, one text file) through the app's import: 71 ms; the six result lines are in section 2's
evidence (`s2_import_line`).

**Emulator** (P3bScenarioTest on the real engine; all 60 instrumented tests of the run passed):
- s2: append adds the style's 2 items (history 11 → 13), **one undo brings it back to 11 with an identical history**;
  overwrite on an edit with a hand change (the module `rasterfile` switched on): the change is gone, darktable's default
  edit plus the style remain (history 14 → 13); one undo brings the hand change back. The style with the unknown
  module is refused at apply, nothing changes.
- s3: a preset stored from exposure and applied to a new exposure instance: one history item (13 → 14), the second
  instance's parameters equal the first's.
- s4 (deliverable 6): framing "image" writes aspect 0.0 and "constant border" -1.0, lens' "use latest algorithm"
  writes `DT_IOP_LENS_EMBEDDED_METADATA_VERSION_2`; three history items, no error.

**darktable-cli of the app's XMPs against the app's export** (compare job; bound: same size and PSNR ≥ 40 dB, as
every comparison of the app's XMPs):

| XMP | size | max | PSNR |
|---|---|---|---|
| style applied in append mode (`p3b-append.xmp`) | 3908 × 2602 | 11 | 47.72 dB |
| style applied in overwrite mode (`p3b-overwrite.xmp`) | 3908 × 2602 | 12 | 48.25 dB |

Both edits are on darktable's default edit, which holds sigmoid: the same level as the other comparisons on the
default edit (P2j 44.3-47.0 dB, P3a 43.8 dB), whose cause is sigmoid compiled by clang on Android vs gcc on Linux
(P1b report section 2), not the styles.

**JVM** (p2a-app 44751209350): 267 tests, 0 failed, among them `P3bStylesPresetsTest` 11, `PhoneP3bScreenshots` 5,
`TabletP3bScreenshots` 5, schema `P2jComputedTest` 17 (one test new). Screenshots looked at: presets list
(phone, tablet), folder opened, style tree with favourites and recently used, search, a style's content, after
apply, import result, import question, store preset, create style.

## 5. Upstream, for the record

- `src/gui/presets.c` `dt_gui_presets_popup_menu_show_internal`: the tail shows "edit this preset…" / "delete this
  preset" only when the current preset is the user's, else "store new preset…" and "update preset <last applied>",
  where the last applied can be a built-in preset that `_update_preset` then refuses as write-protected.
- `dt_presets_menu_rows_iop` (`common/presets.c`) filters by the image in SQL, so non-matching presets are absent,
  not greyed; greyed is only `_iop_is_disabled` (another module version).
- `plugins/lighttable/style/applymode` has no entry in `data/darktableconfig.xml.in`: `dt_conf_get_int` gives 0,
  append. The darkroom's quick styles button (`dt_styles_apply_to_dev`) always appends, whatever that preference.
- Desktop's preferences → presets → "export…" writes every user preset, module layouts included, as
  `<operation>_<name>.dtpreset` (`gui/preferences.c` `export_preset`), so a folder exported there mixes presets and
  layouts; the app's import takes both.
- `common/utility.c` `dt_util_localize_segmented_name`: `_builtin_` and per-segment `_l10n_` prefixes; every shipped
  style is named `_l10n_darktable|…`.

## 6. Owner instructions

Install the APK of release **dt-android-d0ae88e7d08a** (`dt-android-arm64-v8a.apk`) over the old one.

**On the desktop (darktable), export what you want on the phone:**
- *Styles:* lighttable → right panel → **styles** module → select the styles in its list (ctrl-click or
  shift-click for several, or a whole folder's styles) → **export…** → choose a folder. Each style becomes one
  `.dtstyle` file. A look pack downloaded as `.dtstyle` files can be copied as it is, without importing it on the
  desktop first.
- *Presets:* the gear icon (preferences) → **presets** tab → **export…** → choose a folder. Every preset you made
  becomes `<module>_<name>.dtpreset`, your module layouts too. (A single preset: in its module's presets menu,
  "edit this preset…" → "export…".)
- Put the folder where the phone can see it (USB cable into the phone's Download folder, a cloud folder the phone's
  Files app shows, …).

**On the phone:**
- Open a photo. **⋮ → styles… → import…** (or a module's **≡** presets icon → **import styles and presets…**). In
  the file picker select all the files at once (long-press the first, then tap the others; any mix of `.dtstyle` and
  `.dtpreset`). The app lists each file: imported, replaced, skipped, or refused and why. If a style or preset of
  the same name is already on the phone it asks: overwrite, skip, or cancel; tick "apply this option to all…" to
  answer once for the rest.
- **Styles:** ⋮ → styles…. Your packs are folders (darktable's `|` in the name). Tap a style to see its modules,
  choose **append** (added on top of your edit) or **overwrite** (darktable's default edit plus the style; your
  edit is replaced), then **apply**. The choice is remembered. **Undo** takes a style back in one step. The star
  keeps a style under "favorites" on top; the last five applied are under "recently used". "create style from this
  edit…" makes a style from the photo's history.
- **Presets:** open a module, tap its **≡** icon: tap a preset to apply it (the current one has a check mark); "store
  new preset…" keeps the module's settings under a name; the pencil next to your own presets renames or deletes them.
- A module layout exported with the presets is imported too; choose it with the ≡ menu next to the module groups.
- Also new: framing's aspect list can choose "image" and "constant border", and lens correction's "use latest
  algorithm" works (it shows only on edits made with the old algorithm).

## 7. Open questions for the reviewer

1. darktable's 534 shipped styles are mostly its camera styles (one per camera model) and 24 examples; they fill one folded "darktable"
   folder. Hide that folder behind a setting?
2. Editing a preset's description: the bridge's `preset_store(overwrite)` keeps the parameters only when the preset
   is the current one. A `preset_set_description` call (or the app calling store only on the current preset) would
   complete desktop's "edit this preset…". Wanted?
3. FakeEngine keeps a description that `presetStore(overwrite)` is given as "" (the bridge clears it). Align the
   fake with the bridge in the next engine package?
4. The remaining three controls of deliverable 6 need a computed control fed by a module action per photo (the
   input profile list, denoise's profile list with its current entry, white balance's tuning). Proposal: a
   `bind_list_action` override kind (entries and current from a `list_*` action read when the list opens, the
   chosen entry written with `param_set`/`apply_preset`), in the next package.
5. The real app now has the module layouts' import/export file picker that only the interface preview had. Keep?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqnbz6rKrRZTvLsBxk

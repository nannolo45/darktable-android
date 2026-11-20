# P2j: controls that desktop keeps outside a module's parameters (report)

Branch `p2j-unbound-controls`. Brief: `docs/briefs/P2j-unbound-controls.md`. Running log:
`docs/experiments/P2j-unbound-controls.md`.

## 1. Failures and limits (read first)

1. **Not every control is built.** Of the 60: **26 built** (all 21 of class a, 5 of the 11 of class b), **1 hidden**
   (class e), **33 still disabled**, each with one plain line under it saying what it waits for (23 class d,
   6 class b not built, 4 class c). The checker's list is `docs/experiments/P2j-results/unbound-controls-modules-all.txt`.
2. **Class b not built (6):** white balance "settings" (buildable with the existing calls `list_presets`,
   `apply_preset`, `get_wb`, but the list must come from the engine at run time; not done in this package), lens
   correction "mm", "f/", "d" (the focal list needs the lens' range from `list_lenses`; the aperture list needs the
   lens' smallest f-number, which no call returns), LUT 3D's file (the phone has no LUT folder) and watermark's file
   list. They show "not available yet on the phone" or what they need.
3. **Bridge refuses values desktop writes.** Desktop's callbacks write some values outside a field's `$MIN`/`$MAX`;
   the bridge's range check (`engine/src/params.c`) refuses them:
   - framing's aspect entries "image" (0) and "constant border" (-1) (limits 1 to 3): **greyed out in the app's
     list**; "constant border" is the module's default, so it is reached by resetting the module;
   - lens correction's "use latest algorithm" writes `scale_md_v1 = 0` (limits 0.9 to 1.1): **class c**, not built
     (it is hidden in every state the extractor saw anyway);
   - color calibration's hue and chroma write a temperature from `xy_to_CCT`, which can exceed 25000 K for
     blue illuminants: **clamped to 1667..25000 K**. The render does not change (a custom illuminant renders from x
     and y only, `illuminant_to_xy` in `src/common/illuminants.h`), but such an XMP holds 25000 where desktop would
     write the larger number.
4. **c, needs a bridge call (4):** input profile (the image's own profiles), denoise profile and white balance
   finetune (setting works through existing calls, showing the current entry does not), lens "use latest
   algorithm" (see 3). Calls described in section 6.
5. **Desktop's own quirk kept:** framing's "DIN" (sqrt 2) and "A4" (297/210) are within desktop's 0.01 match of
   each other, so after choosing DIN the list shows A4, as on desktop (`borders.c` `gui_changed`).
6. **Two existing tests changed** because they asserted the state this brief removes, not weakened:
   `LayoutTest.quickAccessMappingTable` asserted color calibration's hue and chroma were UNAVAILABLE in the quick
   panel (now CONTROL, with the binding checked); `SchemaTest.unverifiedRulesNeverHide` picked the one unverified
   visibility rule of spektrafilm, which `spektrafilm.json` now replaces by desktop's own condition (the
   millimetre slider shows only on "custom"); the test now takes an unverified sensitivity rule, same property.
7. The workspace has no Android SDK: the darkroom and app modules compile and test only in CI. Local numbers are
   from the pure-Kotlin modules (x86_64 workspace, 2 cores).

## 2. Classification of the 60 controls (deliverable 1)

Upstream: darktable development commit 04477d3 (`upstream/darktable`), files under `src/iop/` unless said otherwise.
Classes: **a** computed (the callback writes parameters by arithmetic), **b** a list filled at run time whose choice is
stored in a parameter, **c** needs a bridge call that does not exist, **d** belongs to a later tool (pickers, graph
editors, masks), **e** not part of the edit (desktop keeps it in its configuration).

"Existing call" means a `dtb_module_action` action that the bridge already has (`engine/include/dtbridge.h`).

| # | Module | Control | Class | Upstream (file, function) | What the control does on desktop | Plan |
|---|---|---|---|---|---|---|
| 1 | base curve | scale for graph | d | `basecurve.c` `logbase_callback` | scales the curve graph's axes (logarithmic view); `g->loglogscale` in the interface data, not in the parameters | disabled, "needs the curve editor" |
| 2 | exposure | area mode | d | `exposure.c` `_spot_settings_changed_callback` | "correction"/"measure" for the area colour mapping; uses the colour picker's area | disabled, "needs the colour picker" |
| 3 | exposure | lightness (target) | d | `exposure.c` `_spot_settings_changed_callback` | target lightness of the picked area; sets `exposure` from the picker's reading | disabled, "needs the colour picker" |
| 4 | white balance | settings | b | `temperature.c` `_preset_tune_callback`, `gui_update` | the white balance mode or camera preset; writes `preset` and the four multipliers from the camera's own data (Exif, wb_presets) | buildable with existing calls `list_presets`, `apply_preset`, `get_wb` (entries "from image area" and "user modified" stay disabled: picker, panel memory) |
| 5 | white balance | finetune | c | `temperature.c` `_preset_tune_callback`, `_match_camera_preset` | the fine tuning of a camera preset (mired); writes the multipliers | setting works through `apply_preset {name, tuning}`, but showing the current tuning needs `get_wb` to return it (`_match_camera_preset`'s `tuning`) |
| 6 | tone equalizer | curve smoothing | a | `toneequal.c` `smoothing_callback`, `gui_update` | `smoothing = sqrt(2)^(1 + v)`; shown as `log(smoothing)/log(sqrt(2)) - 1` | build (formula) |
| 7 | filmic rgb | auto tune levels | d | `filmicrgb.c` `color_picker_apply` (`g->auto_button`, a colour picker on an empty list) | fits the levels to the picked area | disabled, "needs the colour picker" |
| 8 | rgb curve | interpolation method | a | `rgbcurve.c` `interpolator_callback`, `gui_update` | entry i writes `curve_type[0..2] = i` (cubic, centripetal, monotonic); shown from `curve_type[0]` | build (fixed table; the brief listed it under b, but its entries are fixed, not filled at run time) |
| 9 | spektrafilm | film stock | b | `spektrafilm.c` `_film_changed`, `gui_update`, `_scan_profiles` | entries are the data pack's film profiles (`profiles/*.json`, sorted by name); writes `film_hash` (`_name_hash(stock)`), `development_min`, and on a change of film kind `scan_film` and the scan corrections, `print_development_min` on auto paper, stamps `lut_hash` | build: the pack is in the APK; table from the pinned pack, checked in CI |
| 10 | spektrafilm | print paper | b | `spektrafilm.c` `_paper_changed`, `gui_update`, `_auto_paper_entry` | "none (scan the film)", "auto (paper)", then the pack's paper profiles; writes `scan_film`, `paper_hash`, `print_development_min`, stamps `lut_hash` | build (same table) |
| 11 | spektrafilm | format | a | `spektrafilm.c` `_format_changed`, `_format_mm_to_preset` | a preset writes `film_format_mm` from a fixed table; "custom" shows the millimetre slider | build (fixed table; listed under b in the brief, entries are fixed) |
| 12 | spektrafilm | spectral upsampling | b | `spektrafilm.c` `_upsampling_changed`, `gui_update`, `_scan_tables` | the pack's spectral tables; writes `upsampling_hash`, clears `pack_hash` and `lut_hash`; insensitive when there is only one table (the pinned pack has one) | build (shows the pack's table, greyed out as on desktop while there is one) |
| 13 | tone curve | interpolation method | a | `tonecurve.c` `interpolator_callback`, `gui_update` | entry i writes `tonecurve_type[0..2] = i`; shown from `tonecurve_type[0]` | build (fixed table) |
| 14 | tone curve | scale for graph | d | `tonecurve.c` `logbase_callback` | scales the curve graph | disabled, "needs the curve editor" |
| 15 | color calibration | hue | a | `channelmixerrgb.c` `_illum_xy_callback`, `gui_changed`, `dt_xyY_to_Lch` / `dt_Lch_to_xyY` (`common/colorspaces_inline_conversions.h`) | custom illuminant's hue in CIE Luv (L = 100) around D50; writes `x`, `y` and `temperature` (`xy_to_CCT`, below 3000 K `CCT_reverse_lookup`, `common/illuminants.h`) | build first |
| 16 | color calibration | chroma | a | same | custom illuminant's chroma; same writes | build first |
| 17 | color calibration | area mode | d | `channelmixerrgb.c` `_spot_settings_changed_callback` | area colour mapping with the colour picker | disabled, "needs the colour picker" |
| 18 | color calibration | take channel mixing into account | d | `channelmixerrgb.c` `_spot_settings_changed_callback` (configuration `darkroom/modules/channelmixerrgb/use_mixing`) | part of area mapping | disabled, "needs the colour picker" |
| 19 | color calibration | lightness (target) | d | `channelmixerrgb.c` `_spot_settings_changed_callback` | target of the area mapping | disabled, "needs the colour picker" |
| 20 | color calibration | hue (target) | d | same | same | disabled, "needs the colour picker" |
| 21 | color calibration | chroma (target) | d | same | same | disabled, "needs the colour picker" |
| 22 | color calibration | chart | d | `channelmixerrgb.c` `_checker_changed_callback` | colour checker chart to calibrate against (configuration) | disabled, "needs the colour checker tool" |
| 23 | color calibration | optimize for | d | `channelmixerrgb.c` `_optimize_changed_callback` | chart fit priority (configuration) | disabled, "needs the colour checker tool" |
| 24 | color calibration | patch scale | d | `channelmixerrgb.c` `_safety_changed_callback` | size of the sampled chart patches | disabled, "needs the colour checker tool" |
| 25 | color balance rgb | checkerboard size | d | `colorbalancergb.c` `checker_size_callback` (configuration) | checkerboard behind the mask preview | disabled, "needs the mask preview" (a display setting kept in desktop's configuration; d because the brief puts mask preview helpers there) |
| 26 | color look up table | patch | d | `colorchecker.c` `patch_callback` | selects the patch the sliders below edit (`target_L/a/b[patch]`) | disabled, "needs the patch editor" |
| 27 | color look up table | lightness | d | `colorchecker.c` `target_L_callback` | the selected patch's target L | disabled, "needs the patch editor" |
| 28 | color look up table | green-magenta offset | d | `colorchecker.c` `target_a_callback` | the selected patch's target a | disabled, "needs the patch editor" |
| 29 | color look up table | blue-yellow offset | d | `colorchecker.c` `target_b_callback` | the selected patch's target b | disabled, "needs the patch editor" |
| 30 | color look up table | saturation | d | `colorchecker.c` `target_C_callback` | the selected patch's chroma | disabled, "needs the patch editor" |
| 31 | color look up table | target color | d | `colorchecker.c` `target_callback` | relative or absolute display of the target | disabled, "needs the patch editor" |
| 32 | input color profile | input profile | c | `colorin.c` `_profile_changed`, `update_profile_list` | the image's own profiles (embedded ICC, standard and enhanced matrix, depending on the image) then darktable's profiles and installed ICC files; writes `type` and `filename` | needs a bridge call listing the profiles this image offers (`list_profiles` for colorin: `{name, type, filename, kind: input/work}`) |
| 33 | input color profile | working profile | b | `colorin.c` `_workicc_changed` | darktable's built-in profiles (`work_pos`), plus installed ICC files; writes `type_work`, `filename_work` | build the built-in entries (no image dependency, no ICC folder on the phone); an installed ICC file would need the same call as 32 |
| 34 | output color profile | output intent | a | `colorout.c` `_intent_changed`, `_preference_changed` | writes `intent` = entry; desktop shows it only with the preference "force LittleCMS2" | bind to `intent`; stays hidden as on desktop by default (the phone has no such preference) |
| 35 | output color profile | export profile | b | `colorout.c` `_output_profile_changed` | darktable's built-in profiles (`out_pos`) plus installed ICC files; writes `type`, `filename` | build the built-in entries |
| 36 | color zones | edit by area | d | `colorzones.c` `_edit_by_area_callback` | how the curve editor's pointer edits the curve (interface data) | disabled, "needs the curve editor" |
| 37 | color zones | interpolation method | d | `colorzones.c` `_interpolator_callback` | writes `curve_type[channel]` for the channel tab shown above the graph | disabled, "needs the curve editor" (which channel it changes is the graph's tab) |
| 38 | color harmonizer | anchor hue | a | `colorharmonizer.c` `_anchor_hue_slider_changed`, `gui_update`, `_ryb_to_ucs_fast` / `_ucs_to_ryb_fast`, `_build_hue_luts` | RYB degrees on the slider, `anchor_hue` stored as darktable UCS hue through two 720-entry tables | build (port of the table construction) |
| 39 | color harmonizer | (custom hue 1) | a | `colorharmonizer.c` `_custom_hue_changed`, `_update_visibility` | `custom_hue[0]` through the same tables; shown in custom mode for node < `num_custom_nodes` | build |
| 40 | color harmonizer | (custom hue 2) | a | same | `custom_hue[1]` | build |
| 41 | color harmonizer | (custom hue 3) | a | same | `custom_hue[2]` | build |
| 42 | color harmonizer | (custom hue 4) | a | same | `custom_hue[3]` | build |
| 43 | color harmonizer | vectorscope two-way sync | e | `colorharmonizer.c` `_sync_to_vectorscope_toggled` (configuration `plugins/darkroom/colorharmonizer/sync_to_vectorscope`) | keeps desktop's vectorscope overlay in step with the module; not a parameter (comment in `gui_init`) | hidden |
| 44 | color harmonizer | hue 1 (saturation) | a | `colorharmonizer.c` `_node_saturation_changed` | writes `node_saturation[0]` directly | build (bound to the parameter) |
| 45 | color harmonizer | hue 2 (saturation) | a | same | `node_saturation[1]` | build |
| 46 | color harmonizer | hue 3 (saturation) | a | same | `node_saturation[2]` | build |
| 47 | color harmonizer | hue 4 (saturation) | a | same | `node_saturation[3]` | build |
| 48 | LUT 3D | (file) | b | `lut3d.c` `_filepath_callback`, `update_filepath_combobox` | the files under the LUT folder of desktop's preferences (`plugins/darkroom/lut3d/def_path`); writes `filepath`. Desktop greys it out while no folder is set, as here | not built: the phone has no LUT folder yet (existing call `set_file`); stays greyed out with "needs a LUT folder" |
| 49 | saturation curve | protect near-neutrals | a | `satcurvergb.c` `_protect_center_width_changed`, `protect_center_width_to_from_to`, `protect_from_to_to_center_width` | centre of `gf_protect_from`..`gf_protect_to` | build |
| 50 | saturation curve | protection transition width | a | same | width of the same range | build |
| 51 | denoise (profiled) | profile | c | `denoiseprofile.c` `profile_callback`, `gui_update` | the camera's noise profiles (darktable's noiseprofiles.json) and the automatic entry; writes `a`, `b` | choosing works with the existing calls `list_profiles` and `set_profile`, but showing which entry the edit holds needs the profiles' `a` and `b` (desktop compares them in `gui_update`): `list_profiles` should return them |
| 52 | lens correction | mm | b | `lens.cc` `_lens_comboentry_focal_update`, `_lens_set` | editable list: focal lengths from a fixed table limited to the lens' range; writes `focal` | buildable with the existing `list_lenses` (range); not built in this package unless time allows |
| 53 | lens correction | f/ | b | `lens.cc` `_lens_comboentry_aperture_update`, `_lens_set` | editable list of f-numbers from the lens' smallest; writes `aperture` | as 52 |
| 54 | lens correction | d | b | `lens.cc` `_lens_comboentry_distance_update`, `_lens_set` | editable list of distances; writes `distance` | as 52 |
| 55 | lens correction | use latest algorithm | c (was a) | `lens.cc` `_use_latest_md_algo_callback`, `gui_changed` | writes `md_version = 2`, `scale_md_v1 = 0`; shown only while `md_version` is the old one | not built: `scale_md_v1 = 0` is outside the field's limits (0.9 to 1.1), which the bridge refuses (`engine/src/params.c`); needs a bridge path that writes what the callback writes. Hidden in every state the extractor saw |
| 56 | retouch | mask opacity | d | `retouch.c` `rt_mask_opacity_callback` | opacity of the selected shape (in the masks) | disabled, "needs the mask tools" |
| 57 | framing | aspect | a | `borders.c` `_aspect_changed`, `gui_changed` | entry writes `aspect` from `_aspect_ratios`; shown by matching within 0.01, otherwise "custom..." | build |
| 58 | framing | horizontal position | a | `borders.c` `_position_h_changed`, `gui_changed` | `pos_h` from `_pos_h_ratios` | build |
| 59 | framing | vertical position | a | `borders.c` `_position_v_changed`, `gui_changed` | `pos_v` from `_pos_v_ratios` | build |
| 60 | watermark | (file) | b | `watermark.c` `_watermark_callback`, `refresh_watermarks` | the SVG files in darktable's data and configuration folders (`watermarks/`); writes `filename` | not built in this package: the app would list the engine's data folder (no bridge call needed); stays disabled with "needs the watermark list" |

Totals: **a 21** (6, 8, 11, 13, 15, 16, 34, 38-42, 44-47, 49, 50, 57-59), **b 11** (4, 9, 10, 12, 33, 35, 48,
52-54, 60), **c 4** (5, 32, 51, 55; 55 moved from a while building), **d 23** (1-3, 7, 14, 17-31, 36, 37, 56), **e 1** (43). 60 in all.

Differences from the brief's guesses: rgb curve's and tone curve's interpolation and spektrafilm's format are fixed
tables (a, not b); color zones' interpolation is d, because it changes the curve of the channel tab shown above
the graph; white balance finetune and denoise's profile can be set through existing calls but not shown without a
small addition to the bridge (c); input profile needs a list of the image's own profiles (c).

## 3. Deliverables, item by item

| # | Brief | Status | Where |
|---|---|---|---|
| 1 | Classify all 60 with the upstream function | **done** (section 2, pushed before building) | this report |
| 2 | Build every a. both directions, enabled and hidden as desktop; color calibration first | **done for all 21 a** (one, lens' "use latest algorithm", turned out c while building) | overrides below, `Formulas.kt`, `Computed.kt` |
| 3 | Build every b. that needs no new bridge call; spektrafilm first, then denoise, profiles, lens | **partly**: spektrafilm's film, paper and spectral upsampling, export profile, working profile built; denoise is c (section 1.4); lens lists, white balance settings, LUT and watermark files not built (section 1.2) | `SpektrafilmStocks.kt`, `spektrafilm.json`, `colorout.json`, `colorin.json` |
| 4 | d. and e. stop looking broken | **done**: 23 d controls (and the 10 open b/c) show one line under them; the e control is not shown; through the overrides (`note`, `hide`), no module names in screen code | `Controls.kt` `ControlNote`, `P2jOverrides.kt` |
| 5 | The checker tells the truth; output kept and checked in CI | **done**: lists only what is open, with its class and what it waits for; a control no override mentions is listed as class "?"; p2a-app fails if the list changes | `tools/schema-unbound-controls.py`, `P2j-results/unbound-controls-modules-all.txt` |
| 6 | Tests: JVM round trips against upstream's formulas, rules; emulator for color calibration, spektrafilm and one more vs darktable-cli; both workflows green; APK; owner instructions | **done** (results in section 5) | `P2jComputedTest`, `P2jDarkroomTest`, `P2jScenarioTest`, workflow compare step |

### What was built

| Module | Control(s) | How (override → app/schema) | Upstream |
|---|---|---|---|
| color calibration | hue, chroma (custom illuminant) | `computed` `illuminant_hue_chroma`: shown from x, y (`dt_xyY_to_Lch`, hue only above chroma 0, else the last hue set); written as x, y (`dt_Lch_to_xyY`, L = 100) and temperature (`xy_to_CCT`, below 3000 K `CCT_reverse_lookup`); visible only for illuminant "custom" (schema rule) | `channelmixerrgb.c` `_illum_xy_callback`, `gui_changed` |
| spektrafilm | film stock, print paper, spectral upsampling | `stock_list`: the pack's 31 profiles and its table (generated from the pinned pack by `tools/spektrafilm-stock-table.py`, checked against the pack in p2b CI); film writes `film_hash`, `development_min`, on a change of film kind `scan_film` and the scan corrections, on auto paper `print_development_min`, stamps `lut_hash`; paper writes `scan_film`, `paper_hash`, `print_development_min`; "auto" names the paper it resolves to; the paper list is greyed out for a positive film; upsampling greyed out while the pack has one table (as desktop) | `spektrafilm.c` `_film_changed`, `_paper_changed`, `_upsampling_changed`, `gui_update`, `_auto_paper_entry`, `_name_hash` |
| spektrafilm | format | `value_list` on `film_format_mm` (0.01 mm), "custom" otherwise; the millimetre slider shows only on custom (`visibility`) | `_format_changed`, `_format_mm_to_preset` |
| framing | aspect, horizontal and vertical position | `value_list` on `aspect`, `pos_h`, `pos_v` (0.01), "custom..." otherwise | `borders.c` `_aspect_changed`, `_position_*_changed`, `gui_changed` |
| rgb curve, tone curve | interpolation method | `value_list` writing the three curve types, shown from the first | `interpolator_callback`, `gui_update` |
| saturation curve | protect near-neutrals, transition width | `computed` `protect_center_width` (greyed out unless the guided filter is on: schema rule) | `satcurvergb.c` `_protect_center_width_changed`, `protect_*` |
| tone equalizer | curve smoothing | `computed` `sqrt2_log` | `toneequal.c` `smoothing_callback`, `gui_update` |
| color harmonizer | anchor hue, custom hues 1-4 | `computed` `ryb_hue` through upstream's two 720-entry tables (computed by upstream's own code: `tools/colorharmonizer-hue-tables.py`, checked in p2a CI); custom rows shown in custom mode for the first `num_custom_nodes` | `_anchor_hue_slider_changed`, `_custom_hue_changed`, `_build_hue_luts`, `_update_visibility` |
| color harmonizer | hue 1-4 (saturation) | `bind_param` `node_saturation[i]`, shown for the rule's nodes (`visibility` table) | `_node_saturation_changed`, `_update_visibility` |
| output color profile | output intent | `bind_param` `intent`; hidden as on desktop without the LittleCMS2 preference | `colorout.c` `_intent_changed` |
| output color profile | export profile | `value_list` of the 12 built-in profiles (`type`, `filename` "") | `_output_profile_changed` |
| input color profile | working profile | `value_list` of the 11 built-in profiles (`type_work`, `filename_work` "") | `colorin.c` `_workicc_changed` |
| color harmonizer | vectorscope two-way sync | `hide` (class e) | `_sync_to_vectorscope_toggled` |

Darkroom: a computed slider drags like any other (each step writes single fields, so a drag is one history item);
a computed list opens the usual sheet, with darktable's section headings; a choice is one history step
(`DarkroomController.chooseComputed`, `toggleComputed`, `computedValue`). The rules that name an interface-only
control ("control 3" in spektrafilm's sensitivity tables) now see the computed entry (`ParamRuleInputs.control`).

## 4. How to reproduce

```
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle-p2j)
cd app
$GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :schema:test :lpl:test :darkroom:testDebugUnitTest :checkScreenRules
cd ..
python3 -I tools/schema-unbound-controls.py "scene-referred (sigmoid)" "modules: all" \
  --check docs/experiments/P2j-results/unbound-controls-modules-all.txt
# reference values and tables from upstream's own C code (needs gcc and the upstream files listed in
# .github/workflows/p2a-app.yml, step "P2j tables and reference values are upstream's")
python3 -I tools/p2j-reference-vectors.py <darktable src> | diff - app/schema/src/test/resources/p2j-reference.json
python3 -I tools/colorharmonizer-hue-tables.py <darktable src> --check
# the spektrafilm table against the pack the APK carries (ref SF_REF of p2b-real-engine.yml)
python3 -I schema/extractor/fetch_spektrafilm_pack.py /tmp/sf b15cfe18fb35ec15084f78c986b0ebc730338e6e
python3 -I tools/spektrafilm-stock-table.py /tmp/sf/darktable/spektrafilm --check
```
Emulator and darktable-cli: workflow p2b-real-engine (`P2jScenarioTest` in `tools/app/emulator-p2b.sh`; the
compare job renders `p2j-*.xmp` with darktable-cli, with the same spektrafilm pack, and compares with the app's
export: same size and PSNR >= 40 dB, as G0).

## 5. Results

Last code commit **f64cacf**. Both workflows green on it:
- **p2a-app 44698450917**: success; 186 tests, 0 failed (`P2jComputedTest` 16, `P2jDarkroomTest` 4); the P2j
  upstream check (tables and reference values computed again from upstream at the pin) and the checker's list check
  pass. Interface preview `app-preview-f64cacf1d111`.
- **p2b-real-engine 44698450965**: success (13 min 55 s); JVM 242 tests, 0 failed, 1 skipped (as on main);
  emulator 43 of 43 (`P2jScenarioTest` j1, j2, j3 pass); compare green. **APK release tag `dt-android-f64cacf1d111`**.

Emulator (CI x86_64 emulator, not a phone), `P2j-results/emulator-44698450965.txt`:
- j1 color calibration, illuminant custom, hue dragged to 210°, chroma set to 35: x 0.302937, y 0.343846,
  temperature 6876.5 K; shown back as hue 210.0001°, chroma 35.0.
- j2 spektrafilm: Kodak Portra 400 (film_hash 2476060976), Kodak Endura Premier (paper_hash 830359118), 6x7
  (69 mm); lut_hash stamped 1449086660 (the pack's table).
- j3 framing: 16:9 (aspect 1.7777778), horizontal 1/3; output 5397 x 3036.

darktable-cli on Linux rendering the app's XMPs vs the app's exports (`P2j-results/compare-44698450965.txt`):

| Case | Size | PSNR | Max |
|---|---|---|---|
| color calibration (hue, chroma) | 3908 x 2602 | 44.34 dB | 20 |
| spektrafilm (film, paper, format) | 3908 x 2602 | 46.99 dB | 20 |
| framing (aspect, position) | 5397 x 3036 | 46.11 dB | 18 |

All within the bound (>= 40 dB). Lower than G0's test XMP (75.7 dB in the same run): these three XMPs are fresh
edits with the scene-referred defaults (exposure, sigmoid, color calibration on), where G0's test edit is
exposure only; the same comparison was not measured on main for a fresh default edit, so I cannot say how much
of the gap is the defaults and how much the modules set here. Open question 3.

JVM reference values: color calibration's x, y and temperature for 36 hue/chroma pairs within 1e-5 (x, y) and
2e-3 relative (temperature) of upstream's C functions; hue and chroma shown back within 0.01° / 1e-4;
saturation curve and tone equalizer within 1e-6; spektrafilm's stock hashes equal; harmonizer lookups within 1e-5.

## 6. Bridge calls the next engine package would need (class c), described, not built

1. `colorin: list_profiles` → `[ {name, type, filename, pos, kind: "input"|"work"} ]`: the image's own profiles
   (embedded ICC, embedded matrix, standard, enhanced, vendor, alternate matrix, as `colorin.c` `update_profile_list`
   builds them for this image) and darktable's profiles with `in_pos`; then the app can show and set the input
   profile (and installed ICC files for working and export profile).
2. `denoiseprofile: list_profiles` to return each entry's `a` and `b` (or a `current` index computed as
   `gui_update` does), so the app can show which profile the edit holds.
3. `temperature: get_wb` to return the fine tuning of a matched camera preset (`_match_camera_preset`'s `tuning`),
   and the preset position, so the finetune slider can be shown; setting already works with `apply_preset`.
4. A way to write what a callback writes outside a field's `$MIN`/`$MAX` (framing's "image" 0 and "constant
   border" -1; lens' `scale_md_v1 = 0`): either `param_set` with a flag `{"unchecked": true}` limited to values a
   module's own callback writes, or module actions `borders: set_aspect` and `lens: use_latest_md`.
5. `lens: list_lenses` to include `min_aperture` (the aperture list of `_lens_set`).

## 7. Upstream surprises

- `src/iop/borders.c`: `_aspect_ratios` holds 0 and -1 while the field's limits are 1 to 3; `gui_changed` matches
  within 0.01, so DIN (1.4142) shows as A4 (1.4143).
- `src/iop/lens.cc` `_use_latest_md_algo_callback` writes `scale_md_v1 = 0`, below its `$MIN` 0.9.
- `src/iop/channelmixerrgb.c` `_illum_xy_callback` stores `xy_to_CCT`'s result without clamping (can exceed the
  field's 25000 K).
- `src/iop/spektrafilm.c` builds the film and paper lists from the pack's directory at run time and stores a 32-bit
  hash of the stock name (`_name_hash`, djb2 variant), not a position; the schema's "control 2/3" rules are
  positions, which only stay valid while the pack is the one the schema was extracted with (checked in CI).
- `src/iop/colorharmonizer.c` converts its hue sliders through tables built at load by a gamut search
  (`_build_hue_luts`); the extractor saw the custom-mode rows hidden in every state, so their visibility comes
  from `_update_visibility` in the override.

## 8. Owner instructions (phone)

Install the APK from release `dt-android-f64cacf1d111`. Now usable:
- **color calibration**: choose illuminant "custom": **hue** and **chroma** work (drag, double tap for an exact
  number, double tap the label to reset). At chroma 0 the hue keeps what you set.
- **spektrafilm**: **film stock**, **print paper** (including "none (scan the film)" and "auto (…)"), **format**
  (the millimetre slider appears with "custom"); **spectral upsampling** shows the pack's table, greyed out as on
  desktop while there is only one.
- **framing**: aspect and horizontal/vertical position lists ("image" and "constant border" are greyed out: reset
  the module to get the constant border back).
- **rgb curve / tone curve**: interpolation method. **saturation curve**: protect near-neutrals and its width
  (with the guided filter on). **tone equalizer**: curve smoothing. **color harmonizer**: anchor hue, custom hues,
  and the per-node saturation sliders. **output color profile**: export profile (built-in profiles).
  **input color profile**: working profile (built-in profiles).
- Still greyed out, with a line saying why: colour picker controls (exposure, color calibration's area mapping,
  filmic's auto tune), the colour checker tool, curve and patch editors, mask tools ("a later package"); white
  balance settings, lens focal/aperture/distance, LUT 3D and watermark files ("not available yet"); input profile,
  denoise profile and white balance finetune ("needs the engine…").
- Not shown any more: color harmonizer's "vectorscope two-way sync" (it only steered desktop's vectorscope).

## 9. Open questions for the reviewer

1. Framing's "image"/"constant border" and lens' "use latest algorithm": which of the two bridge options in
   section 6.4 do you prefer?
2. Color calibration's temperature is clamped to the field's limits where desktop stores the unclamped number
   (render unchanged). Acceptable, or should it wait for 6.4 too?
3. The PSNR of fresh default edits (44-47 dB) is lower than G0's; worth a measurement of an untouched default edit
   on main to separate the defaults from these modules?
4. White balance "settings" can be built with the existing calls; it needs the list from the engine at run time
   (an asynchronous list in the darkroom). In the next package, or with the lens lists?
5. The checker in CI lists only "modules: all"; it fails on any new interface-only control (class "?") until an
   override classifies it. Keep that strictness?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqnbz6rKrRZTvLsBxk

# P2j: controls that desktop keeps outside a module's parameters (report)

Branch `p2j-unbound-controls`. Brief: `docs/briefs/P2j-unbound-controls.md`. Running log:
`docs/experiments/P2j-unbound-controls.md`.

**Status: classification done (section 2); building in progress.** This file is updated as the work goes on; the
first section will list failures and limits first once the build is finished.

## 1. Failures and limits

(filled in at the end)

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

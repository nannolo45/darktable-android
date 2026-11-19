# S5: generated control descriptions (Phase 0)

## Running log (newest first)
- 19 Nov 2026: done. Schema committed, regenerates byte for byte; coverage, hand check and verdict below. Pull request text in `S5-control-schema-REPORT.md`.
- 19 Nov 2026: fixed grid child order (comparator bug), timings out of the merged file, weak pointers after a crash in color balance on the X-Trans run.
- 19 Nov 2026: extractor plugin builds out of tree and runs inside unmodified darktable under Xvfb; first full run.

**darktable changes: none.** Nothing under `upstream/` or in the darktable source, build or install tree was changed; `patches/` does not exist. The extractor is a "lib" plugin loaded from a private module directory.

**Verdict: go, with conditions** (section 8). Measurements were taken on the build workspace (x86_64, 2 cores); none of them are phone numbers, and none of this experiment runs on the phone.

## 1. What was built

| Path | What it is |
|---|---|
| `schema/extractor/schema_extractor.c` | darktable lib plugin. Inert unless `DT_SCHEMA_OUT` is set. In the darkroom it walks every processing module's widget tree, sweeps lists and switches, and writes one JSON file per module. |
| `schema/extractor/CMakeLists.txt` | builds it out of tree; compile definitions, include paths and forced includes are read from darktable's own compile line for `src/libs/history.c` (`ninja -t commands`), the same mechanism `engine/CMakeLists.txt` uses. |
| `schema/extractor/gen_bauhaus_private.py` | at build time, copies the private bauhaus widget structs verbatim out of `src/bauhaus/bauhaus.c` (see 2.2). |
| `schema/extractor/merge.py` | merges the per-module files, derives visibility and sensitivity rules, checks them, adds counts. |
| `schema/extractor/coverage.py` | the tables in this document. |
| `schema/extractor/fetch_spektrafilm_pack.py` | installs spektrafilm's public data pack into a private `XDG_DATA_HOME` (see 2.4). |
| `tools/schema-extract.sh` | one command: build plugin, private module dir, run darktable under `xvfb-run`, recover from crashes, merge. |
| `tools/schema-diff.py` | human-readable difference between two schema files. |
| `schema/ui-schema.json` | the schema for `04477d3`, generated from `mire1.cr2` (darktable-tests). |
| `docs/experiments/S5-results/` | run log, coverage tables, list of unbound controls, Bayer vs X-Trans diff, determinism check. |

### Regenerate (clean Ubuntu 24.04)

```
apt-get install -y xvfb python3 <packages listed at the top of engine/tests/build-darktable-linux.sh>
git clone https://github.com/nannolo45/darktable-android && cd darktable-android
git submodule update --init --depth 1 upstream/darktable
engine/tests/build-darktable-linux.sh /tmp/dt-build /tmp/dt-install        # about 7 min, -j2
engine/tests/fetch-test-images.sh /tmp/dt-tests
tools/schema-extract.sh upstream/darktable /tmp/dt-build /tmp/dt-install \
    /tmp/dt-tests/images/mire1.cr2 /tmp/s5-work
tools/schema-diff.py <old ui-schema.json> schema/ui-schema.json
```

On the workspace the darktable build already existed, so it was used read-only from `/home/claude/dt/{src,build,install}` (same commit). `schema-extract.sh` took 41 s wall time including the plugin build and the data pack download (x86_64, 2 cores); the darktable part is about 25 s. Two regenerations from scratch produce byte-identical files (`S5-results/determinism.txt`).

## 2. How it works, and what it relies on in darktable

### 2.1 Loading and triggering
- darktable loads every `lib*.so` in `<moduledir>/plugins/lighttable` (`dt_module_load_modules`, `src/common/module.c`; `dt_lib_load_module`, `src/libs/lib.c`). The script makes a symlink copy of the installed `lib/darktable` tree, adds the plugin, and starts `darktable --moduledir <copy> --configdir --cachedir --library :memory: --conf write_sidecar_files=never --conf ui/show_welcome_screen=false <image>`.
- The plugin's `views()` is the darkroom; `view_enter` starts a timer. When every non-hidden module has `module->widget` (darkroom `enter()`, `src/views/darkroom.c`, calls `dt_iop_gui_init` for all of them), it runs, then calls `dt_control_quit()`.
- The plugin's version check (`dt_module_dt_version`) passes only because it is compiled with darktable's exact flags.

### 2.2 Reading the controls
- Tree: GTK containers from `module->widget` down, in visual order (GtkBox pack-end children last and reversed, GtkGrid by row then column). Recognised: bauhaus widgets (`DT_IS_BAUHAUS_WIDGET`), notebooks (tabs, with page labels and tooltips), collapsible sections (`DtGtkExpander` bodies made by `dt_gui_new_collapsible_section`, `src/gui/gtk.c`), section headings (labels with CSS class `dt_section_label`), stacks (`GtkStack` pages), rows (horizontal boxes with two or more controls), grids, buttons and toggle buttons, colour picker buttons (`DT_COLOR_PICKER_INSTANCE_KEY`), drawing areas (graphs), labels, entries.
- Binding: `dt_bauhaus_widget_get_field()` gives a pointer into `module->params` (`dt_bauhaus_*_from_params`, `src/develop/imageop_gui.c`). The byte offset is resolved to an introspection name through `get_introspection_linear()`, including array elements and structs inside arrays (`tonecurve[1][3].x` style). All 630 bound controls resolved to a name.
- Public getters used: label, field, type, hard/soft range, default, step, digits, combobox length/default/current, toggle default, `dt_bauhaus_slider_get_text` (the formatted value with its unit, recorded at default and at both soft limits).
- **No public getter exists for** the slider's format (unit) string, display factor and offset, the widget's section, the quad button (colour picker icon, tooltip) and the combobox entries' values. These are read through a verbatim copy of the private structs `dt_bauhaus_slider_data_t`, `dt_bauhaus_combobox_data_t`, `dt_bauhaus_combobox_entry_t`, `dt_bauhaus_toggle_data_t` and `struct _DtBauhausWidget` (`src/bauhaus/bauhaus.c`, lines 42-144 at this commit), generated at build time from the source darktable was built from. The generator fails if a needed field name disappears, and at run time every value that also has a public getter is compared with the copy: **0 disagreements** over 727 bauhaus widgets. This reads a private layout; it is not a change to darktable. It is the most fragile part of the extractor (section 8).
- Strings: darktable runs in the C locale, so labels are the untranslated English source strings. `dt_bauhaus_widget_set_label` stores `Q_(label)`, which strips any gettext context, so **the context is not recorded** for widget labels; for bound controls the raw introspection description (`param_description`) is recorded, which is the msgid as written in the module's params struct. Action paths (`action_path`, the shortcut system's ids, also untranslated) are recorded for widgets that have one; they identify icon buttons that have no label.
- Per module: operation, display name, aliases, version, pipe position, default group (`default_group()` bits), flags (deprecated, hidden, blending, masks, one instance), deprecation message, whether the module has `gui_post_expose`, `mouse_moved`, `button_pressed`, `scrolled`, `mouse_actions`, `color_picker_apply`, and every scalar introspection field with type, range, default, enum values.

### 2.3 Visibility rules
- **Sweep.** From default parameters, every list and switch that is visible and sensitive is set to each of its selectable values (`dt_bauhaus_combobox_set`, `dt_bauhaus_toggle_set`: the same path as a user's click, which writes the parameter and calls the module's `gui_changed` through `dt_iop_gui_changed`). For every value that changed the panel, every other reachable list and switch is swept again, and the state "second choice kept, first choice undone" is observed too. After each state, the visibility and sensitivity of every recorded widget is snapshotted; then `dt_iop_gui_update` is called (as undo or a history step would) and visibility snapshotted again. Before each state the module goes back to its default parameters and the interface-only lists to their start position. Insensitive list entries and list headings are never selected (a desktop user cannot pick them; selecting a heading in white balance's preset list crashed darktable in `_preset_tune_callback`, `src/iop/temperature.c:1940`, in the first run).
- Collapsed sections count as open; stack pages that are not the current page count as hidden.
- **Rules.** `merge.py` treats each bound parameter (several widgets may share one: demosaic has four `demosaicing_method` lists, one per sensor type) and each interface-only list or switch as a variable. A variable enters a control's rule only if two observed states that differ in that variable alone show the control differently. The rule is the AND of "variable is one of {values where the control was seen}". Each rule is then checked against every observed state. If the AND form mispredicts any state, the rule is replaced by the table of observed value combinations, which is exact for those combinations and silent about others. Controls that only follow their container get no rule of their own.
- 1374 states were observed over 90 modules; no module hit the cap of 1500 states.

### 2.4 spektrafilm's data pack
spektrafilm hides every control behind "no data pack installed" until its data pack exists (`_pack_dir`, `src/iop/spektrafilm.c`; `src/common/spektra_fetch.c`). Without it the first runs saw 115 of its widgets hidden in every state. The script therefore downloads the public pack listed in `darktable-org/darktable-spektrafilm`'s `manifest.json` (branch `main`; pack `irradiance_xy_tc@0.3.3`, 33 files, 8.9 MB), checks each file's SHA-256 against the manifest, and puts it into `<work>/xdg-data`, passed to darktable as `XDG_DATA_HOME`. This is a network dependency the brief did not mention; `SKIP_SPEKTRAFILM_PACK=1` turns it off. The data repository's `main` is not pinned (open question 3).

## 3. Overall numbers (`S5-results/coverage-all.md`)

- 97 modules in the darkroom's module list; 90 have a panel and were walked (16 of them deprecated); 7 have none (`demosaicscale`, `rotatepixels`, `mask_manager`, `finalscale`, `overexposed`, `rawoverexposed`, `gamma`). **No module crashed or was skipped** in the final runs.
- **902 controls**: 727 sliders, lists, switches and bauhaus buttons; 103 other buttons; 26 colour picker buttons; 46 graphs and other custom widgets. 52 sliders carry a colour picker as their quad button.
- **630 controls are bound to a parameter**: 69.8 % of all controls, **86.7 % of sliders/lists/switches**; 625 distinct parameters.
- **97 sliders/lists/switches are not bound** (list in `S5-results/unbound-controls.md`). They fall into four groups: values derived from parameters (white balance temperature and tint from the channel coefficients, color calibration's illuminant hue and chroma from `x`,`y`, color balance's HSL sliders from its RGB arrays, color harmonizer's anchor hue, filmic v1's many sliders); resource choosers (white balance and denoise profiles, lens focal/aperture/distance, spektrafilm film stock, paper, format and spectral table, LUT 3D file, input/output profiles); interface preferences (color balance's slider layout, colorbalancergb checkerboard, area-mapping modes); and one-shot actions (auto-tune lists).
- 711 scalar parameters (outside arrays) in introspection; **582 (81.9 %) have a control**. Of 33 021 values inside arrays (curve nodes, bands, LUT tables, retouch forms) 43 have a slider; the rest belong to graphs or to on-image tools.
- 32 modules have graphs or other custom widgets; **12 draw on or take input from the image** (rotate and perspective, crop and rotate, liquify, retouch, tone equalizer, crop, graduated density, color calibration, basic adjustments, color equalizer, rgb levels, vignetting). Retouch and spot removal edit drawn shapes through the masks framework, which the flags above do not show.
- Containers: 18 tab sets, 30 collapsible sections, 65 section headings, 13 stacks.
- **Visibility rules: 145.** 143 are AND rules that agree with every observed state; 2 needed the table form (highlight reconstruction "noise level", spektrafilm "frame long edge"); 1 of those disagrees with the observations even as a table (spektrafilm, section 5). 17 rules depend on an interface-only list or switch. 62 sensitivity (greyed-out) rules.
- **90 widgets were hidden in every observed state** (20 modules): they depend on something the sweep does not vary (the image, a slider, a legacy parameter value, a notebook tab).
- In 1 state (spektrafilm) re-running `gui_update` gave a different visibility than the user's click; everywhere else the two agree.
- 2 widgets (color balance's section labels) are destroyed and recreated by the module during an update (`_configure_slider_blocks`, `src/iop/colorbalance.c:1711`); they are recorded but marked as gone.

## 4. Image dependence (`S5-results/diff-bayer-cr2-vs-xtrans-raf.txt`)

The same extraction on `xtransIV.raf` (Fujifilm X-Trans) differs from `mire1.cr2` (Canon Bayer) in 11 modules: which of demosaic's four method lists is shown and the rules under it; raw chromatic aberrations (Bayer page vs "other"); highlight reconstruction loses "guided laplacians" and its three rules; lens correction switches from the Lensfun page to the embedded-metadata page; white balance presets, denoise profiles, input profile list and many defaults (coefficients, black levels, crop factor). These are not errors: the panel really differs per image. A single schema generated from one image describes that image class only. The `tools/schema-diff.py` output above is also the demonstration of the diff tool.

## 5. Hand check of visibility rules against the source

Eight modules, 48 rules, read against the module's `gui_changed` / `gui_update` at `04477d3`:

| Module | Source | Rules | Result |
|---|---|---|---|
| blurs | `src/iop/blurs.c`, `gui_changed` (line 867) | 7 | all match: lens → blades, concavity, linearity, rotation; motion → direction, curvature, offset; gaussian hides both sets |
| demosaic | `src/iop/demosaic.c`, `gui_changed` (1875), lines 1914-1958 | 5 | all match for a Bayer image (`median_thrs` = PPG, `greeneq` not passthrough, `color_smoothing` not passthrough and not dual, `dual_thrs` = dual, `lmmse_refine` = LMMSE). The per-sensor lists (1914-1917) and the `!xtrans` part of `greeneq` are image conditions: correct here, different on X-Trans (section 4) |
| color calibration | `src/iop/channelmixerrgb.c`, `_update_illuminants` (3402); sensitivity line 4433 | 9 (+1 sensitivity) | all match: no CAT hides illuminant, picker, CCT; F → F source, LED → LED source, D/Planckian → temperature, custom → hue/chroma; adaptation greyed for "as shot"/"from white balance". The detect-edges/surfaces branch is unreachable because those entries are not in the list (old edits only) |
| highlight reconstruction | `src/iop/highlights.c`, `gui_changed` (1207), lines 1240-1251 | 8 | 7 match. `noise_level` is visible for `laplacian OR (segmentation AND recovery ≠ off)`: the AND form fails and the table form is correct on the observed combinations only. `strength` (segmentation AND recovery ≠ off) was found only after adding the "first choice undone" states. Laplacian rules also need a Bayer image (`bayer &&`) |
| denoise (profiled) | `src/iop/denoiseprofile.c`, `gui_changed` (3053), `gui_update` (3151) | 11 | 8 match. **3 miss a condition**: `wavelet_color_mode`, `shadows`, `bias` also need `use_new_vst` (lines 3145-3147); its switch is only shown when it is off (line 3177, edits from old versions), so the sweep from defaults never changes it. `compensate_hilite_pres` depends on the image's ISO (line 3180) and is hidden on this image |
| exposure | `src/iop/exposure.c`, `gui_changed` (1015) | 2 | match: the manual/deflicker stack page follows `mode` |
| tone equalizer | `src/iop/toneequal.c`, `show_guiding_controls` (2369) | 5 | all match, including contrast compensation only for the non-averaged filters |
| spektrafilm | `src/iop/spektrafilm.c`, lines 544 and 5782 | 1 | **does not match**: "frame long edge" is shown when `film_format_mm` matches no format preset, i.e. it depends on a slider's value and an unbound list; the rule is wrong as AND and inconsistent as a table |

Result: 43 of 48 rules are exactly the source's condition (for this image class and current-version edits); 1 is correct only as a table; 3 miss a condition on a parameter that only old edits change; 1 cannot be expressed by this method.

## 6. What the method cannot capture
1. **Dependence on the image** (sensor type, raw or not, ISO, lens found in Lensfun, camera presets): visible in section 4. One image gives one image class.
2. **Dependence on a slider's value** (spektrafilm frame long edge; color harmonizer's fourth custom node row depends on the "nodes" slider; denoise auto modes recompute sliders). Sliders are not swept.
3. **Dependence on parameters that the panel only shows for old edits** (denoise `use_new_vst`, `fix_anscombe_and_nlmeans_norm`; color calibration's detect modes). Rules are learned from default parameters.
4. **OR conditions** are detected (the AND rule fails its check) and fall back to a table that says nothing about unobserved combinations.
5. **Dependence on two lists at once** is captured only when one of them changes the panel by itself (the pair sweep starts from such values). A pair where neither changes anything alone is not tried.
6. **Pages switched by notebook tabs** rather than lists: color equalizer shows one of three slider pages per tab through a `GtkStack`; the pages for tabs 2 and 3 count as hidden in every state.
7. **What buttons and pickers do**: the schema identifies them (action path, tooltip) but their callbacks (flip's four buttons set `orientation`, auto-tune, fit) are code.
8. Panels that depend on the module's internal state (preview data, pipe results) such as live labels ("CCT: 3927 K") are recorded as they were; the diff tool ignores plain label text by default.
9. The blending and masks panel (common to all modules, outside `module->widget`) was not walked; masks and blending are a Phase 3 hand-built part of the plan anyway.
10. Gettext contexts of widget labels (stripped by `Q_()` before the widget stores the label).

## 7. Coverage of the 29 priority modules

Counts from `S5-results/coverage-priority.md`; the judgement is mine, from the schema and the module source. "Generated" means a usable panel needs only the schema plus the shared slider/list/switch/tabs/sections renderer; colour pickers and display-only graphs are left out of that judgement where the module works without them.

| Module | Bound / scalar params | Unbound / buttons / pickers / graphs | Rules | Panel from schema alone | What is missing |
|---|---|---|---|---|---|
| color calibration | 32 controls; 14/17 scalars, 18/32 array values (channel tabs) | 10 / 3 / 1 / 3; on image | 9 | partly | illuminant hue/chroma sliders are derived from `x`,`y` (unbound); colour picker and area mapping; colour checker calibration (buttons, on-image chart) |
| crop | 4; 4/6 | 1 / 0 / 0 / 0; on image | 0 | partly | margins are generated; aspect list unbound (`ratio_n`/`ratio_d` set by code); the real tool is on-image (Tier 3) |
| orientation | 0; 0/1 | 0 / 4 / 0 / 0 | 0 | no | four icon buttons whose callbacks set `orientation`; identified by action path, needs a small override |
| rotate and perspective | 10; 10/15 | 0 / 6 / 0 / 0; on image | 3 | partly | rotation, lens shift, shear, cropping, lens model generated with rules; fit and structure buttons are on-image tools |
| lens correction | 16; 16/23 | 4 / 4 / 0 / 0 | 6 | partly | camera and lens model choosers (buttons with menus filled from Lensfun), focal/aperture/distance editable lists (unbound); metadata page is image-dependent |
| demosaic | 15; 12/15 | 0 / 0 / 0 / 0 | 5 | **yes** | which method list applies depends on the sensor (image condition); moiré parameters have no control |
| white balance | 5; 5/6 | 4 / 3 / 1 / 0 | 1 | partly | the main controls are unbound: preset list, temperature, tint, finetune (derived from coefficients); 3 buttons and a picker. Only the channel coefficient sliders are generated |
| tone curve | 2; 2/4 | 2 / 0 / 1 / 1 | 1 | no | graph editor (Tier 2); interpolation list unbound (array parameter per channel) |
| spektrafilm | 67; 67/74 | 4 / 10 / 2 / 1 | 1 | partly (most of the panel) | film stock, print paper, format and spectral table lists are unbound (they store hashes resolved through the data pack); exposure buttons; 1 rule not capturable |
| AgX | 38; 38/38 | 0 / 3 / 5 / 1 | 19 | **yes** | auto-tune pickers and buttons; curve graph is display-only |
| rgb levels | 2; 2/2 | 0 / 1 / 4 / 1; on image | 1 | no | levels graph with handles, pickers (Tier 2) |
| tone equalizer | 17; 17/18 | 1 / 1 / 0 / 1; on image | 5 | partly | simple and masking tabs are generated; advanced graph and on-image drag are hand-built; curve smoothing unbound |
| exposure | 7; 7/7 | 2 / 0 / 1 / 2 | 2 | **yes** | area exposure mapping section (interface-only, picker, swatches); highlight preservation switch is image-dependent |
| grain | 3; 3/4 | 0 / 0 / 0 / 0 | 0 | **yes** | `channel` has no control on desktop either |
| contrast equalizer | 1; 1/2 | 0 / 0 / 0 / 1 | 0 | no | band graph (Tier 2); tabs only select the curve |
| blurs | 9; 9/9 | 0 / 0 / 0 / 1 | 7 | **yes** | kernel preview graph is display-only |
| diffuse or sharpen | 15; 15/15 | 0 / 0 / 0 / 0 | 0 | **yes** | - |
| retouch | 4; 4/9 | 1 / 16 / 1 / 2; on image | 0 | no | shapes, tools, wavelet scales: all buttons and graphs (Tier 3); 13 widgets depend on a selected shape |
| chromatic aberrations | 5; 5/5 | 0 / 0 / 0 / 0 | 0 | **yes** | - |
| denoise (profiled) | 14; 14/14 | 1 / 0 / 0 / 1 | 11 | partly (most of the panel) | profile list unbound (it fills arrays); wavelet curves are graphs; 3 rules miss the legacy `use_new_vst` condition |
| color zones | 3; 3/4 | 2 / 1 / 2 / 2 | 0 | no | band graph editor (Tier 2) |
| LUT 3D | 2; 2/3 | 1 / 1 / 0 / 0 | 0 | partly | file button and LUT name list are unbound (the path is a string parameter, not scalar) |
| rgb curve | 3; 3/3 | 1 / 0 / 2 / 1 | 1 | no | graph editor (Tier 2) |
| color balance rgb | 33; 33/33 | 1 / 2 / 6 / 1 | 0 | **yes** | pickers on 6 sliders; mask graph is display-only |
| color equalizer | 32; 32/32 | 0 / 0 / 2 / 1; on image | 4 | partly | slider pages are switched by tabs through a stack (not a rule, see 6.6): a one-line override; graph editing is Tier 2 |
| color balance | 17; 5/5, 12/12 array values | 9 / 0 / 9 / 0 | 23 | partly | in the default "HSL" slider mode the shown hue/saturation sliders are unbound (derived from the RGB arrays); auto optimizers |
| color harmonizer | 6; 6/7 | 10 / 2 / 5 / 8 | 11 | partly | anchor hue and the four saturation sliders unbound; vectorscope graphs; custom node rows depend on a slider |
| color look up table | 0; 0/1 | 6 / 0 / 1 / 1 | 0 | no | every control edits the selected patch of a table (unbound); patch grid (Tier 3 in the plan) |
| rgb primaries | 8; 8/8 | 0 / 0 / 0 / 0 | 0 | **yes** | - |

**Summary: 9 yes, 12 partly, 8 no.** Compared with PLAN.md section 6 ("usable in Phase 2 with generated controls"), the schema alone does **not** give usable panels for white balance (its main controls are derived), orientation (buttons), LUT 3D (file), lens correction (camera/lens choosers), and gives only most of spektrafilm, denoise (profiled), color balance, color equalizer and color harmonizer. Each of these needs a small, specific override, not a hand-written screen.

## 8. Verdict: go, with conditions

**Go**, because:
- The extraction works on unmodified darktable, finds every module and every control, and resolves every bound control to its parameter name with exact ranges, defaults, units, digits, factors and list values (0 getter disagreements, 0 unresolved offsets).
- 86.7 % of sliders/lists/switches and 81.9 % of scalar parameters are bound, so the generic renderer covers most of most panels; tabs, sections, collapsible groups and stacks come with them.
- Visibility rules for list- and switch-driven panels are good where they apply (143 of 145 rules consistent with every observed state; 43 of 48 hand-checked rules exactly right), and the self-check flags the ones that are not.
- It is fast (about 25 s of darktable time) and deterministic, so it can run in CI on every upstream bump and the diff tool shows what changed.

**Conditions**:
1. **Overrides are required, not optional**, for the unbound groups in section 3 (derived values, resource choosers, button actions). PLAN 3.3's `schema/overrides/` should be planned as a real work item for about 10 priority modules, and the Phase 2 list in PLAN section 6 adjusted (section 7).
2. **One schema per image class, or rules evaluated at run time.** At least Bayer and X-Trans (and a non-raw image) should be extracted and merged, or the app should ask the engine which variant applies. A bridge call that reports the sensor type is cheap; image-dependent rules otherwise come out wrong.
3. **The app must not hide a control because of a rule when the edit's parameters lie outside what the sweep saw** (legacy values such as `use_new_vst = false`); show it instead. Rules are learned from default parameters.
4. **The private-struct copy** (2.2) is the weak point on upstream updates. It is generated at build time and cross-checked, so a layout change fails loudly rather than silently, but it may need adjustment on bumps. Asking upstream for getters of format, factor, offset, section and entry values would remove it; this would be an upstream pull request, not a patch here.
5. Sliders, tab-driven stacks and pairs of lists that do nothing alone are not swept; the few modules affected (spektrafilm, color equalizer, color harmonizer) need overrides.
6. The spektrafilm data pack download (2.4) becomes part of the CI job, preferably pinned to a fixed ref.

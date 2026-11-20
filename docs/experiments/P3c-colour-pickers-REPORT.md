# P3c: colour pickers on the photo (pull request description)

Branch `p3c-colour-pickers` from main 5fe4c41. Brief: `docs/briefs/P3c-colour-pickers.md`. Running log:
`docs/experiments/P3c-colour-pickers.md`. Last code commit and runs: section 6.

Measurements: **workspace** = build workspace, x86_64, 2 cores; **CI** = GitHub runners, x86_64; **emulator** = CI's
x86_64 Android emulator. None of them is a phone.

## 1. Failures, limits and shortfalls (read first)

1. **Deliverable 3 is partly done: 10 of the plain "set a slider" pickers, in 2 modules.** Built: filmic rgb (middle
   gray luminance, white and black relative exposure, auto tune levels) and color balance rgb (the hue of offset,
   lift, power and gain with their chroma, white fulcrum, contrast gray fulcrum). **Not built:** AgX (5 pickers: its
   callback also moves the curve's pivot and gamma through `_update_pivot_x`, `_adjust_pivot`, which the app has no
   copy of), color equalizer (node placement, white level), and the modules outside the owner's list (table in 2.3).
   Tone equalizer and sigmoid **have no colour picker on desktop** (tone equalizer's wand buttons compute from its own
   mask histogram, not a picker); color calibration has one picker, built in deliverable 2.
2. **Every pick is one history item and one undo step.** The bridge seals the history before and after every module
   action (`engine/src/history.c` `dtb__change_begin` / `dtb__change_end` with `DTB_CHANGE_OTHER`), so arming (which
   picks at once, as desktop) and then drawing an area gives two items of the module; desktop merges consecutive
   picks of one module into one item. The app cannot change that without an engine change (open question 1). The
   pickers of deliverable 3 (`params_set`) are sealed after each pick for the same behaviour.
3. **With a picker armed, one finger never pans.** A one-finger drag away from the selection draws a new area (the
   brief: "a drag draws an area"); panning a zoomed photo needs two fingers then. Pinch and double tap work as before.
4. **Pickers are only in the module's panel**, not in the quick access panel (its controls show no picker button).
5. **Working profile.** The formulas of deliverable 3 that need the working profile's matrix (color balance rgb's
   hue, chroma and fulcrums; filmic's "luminance Y" norm) take linear Rec2020, darktable's default working profile.
   With another working profile (input colour profile's second list) those values differ slightly from desktop's.
   filmic's default norm (power norm) and its white and black pickers (max RGB) do not depend on it.
6. **Two values are written within the field's limits** where desktop writes them as they come: color balance rgb's
   picked hue (desktop: 30..390°; the app: the same angle within 0..360°) and its chroma (clamped to 0..1). The
   bridge refuses values outside a field's limits.
7. **Not built because the engine has no action** (unchanged from P1b part 2): color calibration's area mapping in
   "measure" mode (the entry is greyed out with the reason), color zones' "create curve", rgb levels' "auto region".
   The "show color" pickers of rgb curve, tone curve and color zones show the values in the line under the photo only
   (desktop marks them on the curve graph, which is a later package).
8. **The pickers' settings are kept for the app's run only** (exposure's target lightness and area mode, color
   calibration's target and "take channel mixing into account"); desktop keeps them in darktablerc. Exposure's
   "measure" also stores the target in the engine's own configuration (bridge behaviour, P1b part 2).
9. **An area picker gets a small area from a tap** (one touch target wide), since it takes no points.
10. **A turned selection is shown as its bounding box**: the selection is kept as four corners in original-image
    coordinates; after a rotation in the edit the photo shows the box around the turned corners, and that box is
    what is picked.
11. **Emulator test:** the pickers are armed through the controller (as the crop scenario opens its tool); the
    areas are drawn and moved with touch events injected into the system. Arming by tapping the button on the screen
    is covered by the screenshot tests (Robolectric, `performClick`).
12. **Stand-in engine limits met in the JVM tests:** its only geometry is the orientation (the crop case uses a test
    wrapper that crops its coordinates), and it keeps rgb levels' `levels[3][3]` as one row of nine (the second and
    third channels' picks are answered by the test wrapper; the real engine is used by C25).
13. **One existing test changed on purpose:** `P2jComputedTest.controlsThatWaitSayWhat` asserted that exposure's
    area mode has the note "needs the colour picker"; that control is a picker setting now, so the test asserts that
    it has no note and is usable, and checks the note on a control that still waits (color calibration's chart).
    Nothing else was changed, weakened, skipped or deleted.
14. Changes outside `app/` and `schema/`: `engine/tests` (C25, deliverable 6 only), `tools/` (two new scripts, the
    unbound-controls tool), the three workflows (this branch in the triggers, which must be removed before merging;
    the Linux cache keys and the p2b compare step should stay), `tools/app/emulator-p2b.sh` (P3cScenarioTest),
    `docs/design/ui-spec.md` (section 7). No change under `engine/src`, `engine/include` or `upstream/`.

## 2. Deliverable 6 first: the picker actions against darktable's own code

**No difference.** Test C25 (`engine/tests/dtb_pickref.c`, run by `run_tests.sh` on cr2, raf and arw) runs each of
the bridge's picker actions and then darktable's own callback, **compiled verbatim** out of the darktable source the
engine is built from (`engine/tests/pickref_extract.py` copies them at build time: temperature.c
`color_picker_apply`; exposure.c `color_picker_apply`, `_auto_set_exposure`, `_exposure_set_white`,
`_exposure_set_black` and the bias helpers; rgblevels.c `color_picker_apply`; rgbcurve.c `color_picker_apply`,
`_add_node_from_picker`, `_add_node`; channelmixerrgb.c `color_picker_apply`, `_auto_set_illuminant`,
`_check_if_close_to_daylight`), on a copy of the module whose parameters are those from before the action and whose
`picked_color`, `picked_color_min`, `picked_color_max` are the values the action picked, with the bridge's own
pipe (for the input and working profiles). The desktop interface calls are stubbed (`pickref_stubs.h`: sliders and
lists read and write the test's own values, history and redraws are no-ops). The parameters are compared field by
field.

17 cases per image: white balance (2 areas), exposure correction to 50 % and 30 % and measure (the answer's
lightness against the slider value desktop's callback writes), rgb levels black / gray / white on a point and an area,
rgb curve linked and independent with middle grey compensation, color calibration neutral, a target without and
with channel mixing. **Largest difference 0 on all three images** (bound 1e-6; CI g0-app 44781177183, logs
`runs/44781177183-a1/g0-linux/<cr2|raf|arw>/C25.log` on the ci-logs branch). Every action could be isolated; none
needed to be left out. What C25 cannot see: the picked statistics themselves (the bridge calls desktop's own picker
functions for them, P1b part 2) and color calibration's "measure" mode (not in the engine).

## 3. Item by item

| # | Item | State |
|---|---|---|
| 1 | One picker tool on the photo, buttons where desktop has them, arm/disarm, point and area, move and resize, one at a time, coordinates in image coordinates | **done** (limits 1.3, 1.4, 1.10) |
| 2 | The five modules with engine actions, with their settings; P2j notes; kept list | **done** |
| 3 | Pickers that set sliders | **partly**: filmic rgb, color balance rgb (1.1) |
| 4 | Readout | **done** |
| 5 | Input profile list, denoise profile, white balance fine tuning | **done** (the emulator's photo has no noise profile and no camera preset match: those two are tested on the stand-in and through the bridge's own tests C14) |
| 6 | Picker arithmetic against upstream's own code | **done**, no difference (section 2) |
| 7 | Tests | **done** (section 5) |

### 3.1 The tool (deliverable 1)

- **How the schema marks pickers.** The extractor already records them: a slider or list whose quad button is a
  picker (`quad.colorpicker: true`) and the picker toggle buttons (`kind: colorpicker_button`). `SchemaLoader` reads
  them into `ModuleSchema.pickerMarks` (78 marks in 30 modules, equal to the extractor's
  `colorpicker_buttons + colorpicker_quads` counts; `P3cPickerTest.theExtractorsMarksAreRead`). What a picker does in
  the app comes from two new override operations (`schema/overrides/README.md`, "Brief P3c"): `picker` (kinds
  point/area as desktop's flags, the module action with its arguments, settings and tab arguments, or a
  `set_slider` formula, or nothing: values only) and `picker_setting` (interface-only controls that hold a picker's
  settings, bound to the new `Binding.Setting`). Every operation names its node and checks the label or action path
  it expects there, and refuses a node without a picker mark.
- **The button**: at the right of a marked slider or list, or as its own row for a picker button ("from image area",
  "black", "gray", "white", "show color", "create curve", ...), icon `colorPicker` (`app/ui-icons/svg/`); greyed out
  where no picker is built; shown on (`color.selected` behind it) while armed. No module name in screen code: which
  control has which picker is schema and override data.
- **Arming** (`DarkroomController.togglePicker`), as desktop's `gui/color_picker_proxy.c`
  `_color_picker_callback_button_press`: the previous picker goes; the module is switched on; the picker picks at once
  with its last selection (kept per control) or desktop's first one (`dt_lib_colorpicker_reset_point`: the middle;
  `_reset_box_area`: 2 %..98 % for area-only pickers). Disarmed by its button, by opening another module or closing
  the module's panel, by another photo, or when the module is gone.
- **On the photo** (`ui/Photo.kt`, `PickerTool` in app/schema): tap = point; one-finger drag away from the selection =
  new area; a drag on the area moves it (inside) or resizes it (sides, corners: within a touch target); on the point
  moves it; a second finger before the selection moved is a pinch and puts the selection back. Each lifted finger
  picks again. Drawn with tokens `color.pickerFrame`, `color.pickerShadow`, `size.pickerLine`, `size.pickerHandle`,
  `size.pickerPoint`.
- **Coordinates**: the selection is kept as its corners (or point) in original-image coordinates through
  `coords_transform` (desktop keeps its picker box so too, `dt_color_picker_backtransform_box`), and mapped back onto
  the picture after any change of another module. The finger's position is mapped through `PhotoGeometry`
  (zoom and centre), the same mapping the photo is drawn with.

### 3.2 The five modules (deliverable 2)

| Module | Control | Engine action | Settings |
|---|---|---|---|
| white balance | "from image area" button | `wb_from_area` | |
| exposure | exposure slider's picker | `spot` | area mode (correction / measure), target lightness; measure writes the measured lightness back to the target; a new target in correction picks again |
| rgb levels | black, gray, white buttons | `pick {which, channel}` | channel = the tab shown (tabs hidden with linked channels: R, as desktop) |
| rgb curve | "create curve" button | `set_nodes_from_area {channel}` | channel from the tab; "show color": values only |
| color calibration | the CAT tab's picker | `illuminant_from_area` (neutral target: lightness 50, chroma 0, desktop's area mapping off) or `spot {target, use_mixing}` | area mode ("measure" greyed out), take channel mixing into account (default on, as desktop without a stored value), lightness, hue, chroma; a changed target in correction picks again |

The P2j notes "needs the colour picker" of exposure's and color calibration's area mapping and of filmic's auto tune
levels are gone (the controls work); the kept list of `tools/schema-unbound-controls.py`
(`docs/experiments/P2j-results/unbound-controls-modules-all.txt`) went from 32 to 21 controls (with deliverable 5's
three), no class c left. The tool now also counts `picker_setting`, `picker` and `action_list` as making a control
usable.

### 3.3 Pickers that set sliders (deliverable 3)

Generic: `set_slider {formula, writes}` in the override; the app picks with `color_pick` on the module (input, the
module's picker colour space, desktop's denoise flag where desktop sets it) and writes what `PickerFormulas` computes
with `params_set`, one history item. Each formula repeats one module's callback:

- filmic rgb (`filmicrgb.c`: `apply_auto_grey`, `apply_auto_black`, `apply_auto_white_point_source`,
  `apply_autotune`, `get_pixel_norm`, `_compute_output_power`);
- color balance rgb (`colorbalancergb.c` `color_picker_apply`, `pipe_RGB_to_Ych`: hue of the picked colour's
  opponent, chroma Y·c, `log2` of the max's Y for the white fulcrum, the mean's Y for the gray fulcrum).

Checked against upstream's functions compiled verbatim: `tools/p3c-picker-reference.py <darktable src>` writes
`app/schema/src/test/resources/p3c-picker-reference.json` (filmic: 360 cases, 4 picks × 3 parameter sets × 6 norms;
color balance: 36 cases); `P3cPickerTest` (app/schema) compares: largest relative difference 2.6e-7 (filmic) and
5.6e-7 (color balance), float against double.

Pickers per module (built / marked; the rest stay greyed out):

| Module | Built | Not built |
|---|---|---|
| white balance, exposure, color calibration, rgb curve, color balance rgb, filmic rgb, tone curve | all | |
| rgb levels | 3 of 4 | auto region (no engine action) |
| color zones | 1 of 2 | create curve (no engine action) |
| AgX | 0 of 5 | white / black relative exposure, auto tune levels, pivot relative exposure, pivot target output (limit 1.1) |
| color equalizer | 0 of 2 | node placement, white level |
| color balance (legacy) 0/9, negadoctor 0/7, color harmonizer 0/5, filmic (legacy) 0/4, unbreak input profile 0/4, levels (legacy) 0/3, basic adjustments 0/2, framing 0/2, split-toning 0/2, spektrafilm 0/2, color look up table, colorize, graduated density, invert, monochrome, fill light, retouch, saturation curve, watermark (1 each) | 0 | outside the owner's list |

20 of the 78 marked pickers are built.

### 3.4 Readout (deliverable 4)

While a picker is armed, the line under the photo (phone) or a line under the photo (tablet) shows desktop's global
colour picker values for the selection (`color_pick` with no module: the pipe's display RGB): `mean RGB r g b Lab L a
b`, plus `min` and `max` lines for an area, as `libs/colorpicker.c` `_update_sample_label` formats them (RGB as
`round(v·255)`, Lab with two decimals, L clamped to 0..100). Lab from the display sRGB through sRGB's primaries and
darktable's D65 white adapted to D50 (`PickerReadout`, tested: white 100/0/0, 50 % grey L 53.39, sRGB red
54.29/80.80/69.89). It is read again after every change while the picker is armed.

### 3.5 The three controls of P3b (deliverable 5)

New override `action_list` (P3b's proposal "bind_list_action"): a list whose entries and current entry a module
action gives per photo, read again when the instance's parameters or the photo change. Input colour profile: the
input entries of `colorin list_profiles`, the chosen entry's `type` and `filename` written with one `params_set`
(only the fields that change). Denoise (profiled): `denoiseprofile list_profiles` (with `current`), the chosen one
through `set_profile {index}`. White balance's finetune: `bind_action` gained `args_from`, `needs` and `round`:
the slider shows `get_wb`'s `tuning`, writes `apply_preset {name: the matched preset, tuning}` in whole steps, and
is greyed out while `tuning` is null (no camera preset).

## 4. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p3c-colour-pickers
# schema and formulas (pure Kotlin, runs on the workspace)
cd app && gradle --offline --no-daemon :schema:test && cd ..
# reference values of deliverable 3 from upstream's own code (needs gcc and the darktable source)
tools/fetch-darktable.sh /tmp/dt-src
python3 -I tools/p3c-picker-reference.py /tmp/dt-src > app/schema/src/test/resources/p3c-picker-reference.json
# kept list of controls still waiting
python3 -I tools/schema-unbound-controls.py "scene-referred (sigmoid)" "modules: all" --check docs/experiments/P2j-results/unbound-controls-modules-all.txt
# deliverable 6: C25 (builds dtb_pickref with the bridge)
tools/build-engine-linux.sh --deps-only
DTA_WORK=/tmp/dta-work tools/build-engine-linux.sh /tmp/dt-src /tmp/dt-linux
tools/engine-test/fetch-paths.sh /tmp/dtt images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw \
  0001-exposure/exposure.xmp 0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp \
  0179-spektrafilm/spektrafilm.xmp
engine/tests/run_tests.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/results C25
# darkroom JVM and screenshot tests need the Android SDK (CI: p2a-app); emulator: p2b-real-engine
$GRADLE --no-daemon :engine-api:test :schema:test :ui-theme:testDebugUnitTest :darkroom:testDebugUnitTest
```

## 5. Results with numbers

RESULTS

## 6. Owner instructions

OWNER

## 7. Open questions for the reviewer

1. Desktop merges consecutive picks of one module into one history item; the bridge seals around every module action
   (limit 1.2). Should the next engine package let picker actions merge into the instance's open item (as
   `param_set` does until `history_seal`), so that arming and re-picking give one item, as on desktop?
2. With a picker armed, one finger draws areas and two fingers pan (limit 1.3). Keep, or should a long press start an
   area instead, leaving one-finger pan to the zoomed photo?
3. Deliverable 3's formulas take linear Rec2020 as the working profile (limit 1.5). An engine call that returns the
   pipe's working profile matrix (or `color_pick` returning XYZ or Ych as well) would remove that assumption. Wanted?
4. AgX's and color equalizer's pickers need more of their modules' code (AgX's pivot and gamma, color equalizer's
   hue nodes). Next package, or as engine actions like deliverable 2's?
5. The CI trigger lines for this branch in the three workflows must go before merging; the Linux cache keys (which now
   include the C25 files), the p2b compare step for P3c and the P3c emulator test should stay.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqnbz6rKrRZTvLsBxk

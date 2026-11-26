# P6a: small module gaps from the audit (pull request description)

Branch `p6a-small-module-gaps` from main 7173c2a. Brief: `docs/briefs/P6a-small-module-gaps.md`.

Measurements: **workspace** = build workspace, x86_64, 2 cores (a Linux build of the pinned darktable and the bridge
was made there this time, so the bridge tests ran locally before CI); **CI** = GitHub runners, x86_64; **emulator** =
CI's x86_64 Android emulator. None of them is a phone.

## 1. Failures, limits and shortfalls (read first)

1. **Item 3 is partly done: 12 of the 15 modules of the brief's list are done, 1 partly, 2 not done** (table 2.3).
   Not done: **spektrafilm's two pickers** (desktop solves its whole film simulation for the picked highlight,
   `_ensure_sim`, `_solve_boost_for_lightness`, `_solve_scale_for_lightness`: the engine cannot call the module's
   static code, and a copy of the simulation is not small) and **color balance's two pickers** ("optimize luma" and
   "neutralize colors" use the patches its other seven pickers keep in the panel, `luma_patches`, `color_patches`:
   desktop panel state, which the brief said to leave). Partly: **basic adjustments** (the middle gray picker is
   done; "auto" and its area picker, an auto exposure from a histogram of the module's input, are not). Also not
   built: **levels' "auto"** (from the histogram desktop's pipe collects) and **colour harmonizer's "analyze" and
   "import"** (analyze reads a hue histogram its `process()` collects; import reads a colour palette) - the brief
   listed "analyze" with the harmonizer's hue pickers, which are done.
2. **Item 2's on-photo line is not built** (2.2); the slider is. The slider's range 0..100 % is the app's choice:
   darktable's introspection declares no limits for `offset`.
3. **Differences from desktop** (each also in 2.x):
   - white balance: choosing a camera preset whose tunings do not include the current finetune value gives its
     untuned entry; desktop keeps the old multipliers under the new name (its `_preset_tune_callback` finds neither an
     exact nor an interpolated tuning and stops). After a "from image area" pick the list shows "from image area" as
     desktop does right after the pick; desktop shows "user modified" once the photo is reopened (its `gui_update`).
     "user modified" cannot be chosen (desktop restores its panel's memory of the last slider values).
     Colour calibration's lock of the white balance controls (`dt_dev_cat_overrides_wb`, which asks colour
     calibration's panel) is not repeated, as before P6a for the other white balance controls.
   - colour zones' "create curve": flat only (desktop raises or lowers the nodes with ctrl or shift held).
   - AgX's "read exposure": the exposure module's effective exposure is found as desktop does, but in deflicker mode
     it is 0 (desktop takes the value its exposure panel computed); C43 gives desktop's callback the value the bridge
     found (desktop's `dt_dev_exposure_get_effective_exposure` answers only in the darkroom view), so C43 checks the
     arithmetic after that, not the search.
   - AgX's "reset primaries" is a menu on desktop; the app shows its three entries as three buttons in the row.
   - colour equalizer's node placement and fill light's picker show the picked values under the photo only (desktop
     marks them on its graph or slider; neither sets a parameter).
4. **Pickers are one history item per pick** (P3c limit 1.2, unchanged).
5. **Three existing tests changed on purpose**: `P3cPickerTest` (darkroom) asserted that finetune waits for `tuning`;
   it waits for `finetune_min` now and is hidden, not greyed out (2.1). `P3cPickerTest` (schema) asserted that colour
   zones' "create curve" and rgb levels' "auto region" are not built; they are now, and two pickers that are still not
   built (spektrafilm's, graduated density's hue) are checked instead. The unbound-controls kept list lost white
   balance's "settings" (12 → 11 controls). Nothing else was changed, weakened, skipped or deleted.
6. Changes outside `app/`, `schema/` and `engine/`: `tools/module-audit.py` (verdicts), `tools/app/emulator-p2b.sh`
   (P6aTapTest), `.github/workflows/g0-app.yml` (the Linux cache key gains `dtb_test_p6a.c`), docs. No change under
   `upstream/`; the LPL contract and the repository layout are unchanged.

## 2. Item by item

| # | Item | State |
|---|---|---|
| 1 | White balance "settings" list and finetune | **done** |
| 2 | Graduated density: position of the filter line | **partly**: the slider is done; the on-photo line is **not built** (1.2) |
| 3 | Missing pickers and "auto" buttons | **partly**: per module below |
| - | Audit re-run | **done**: works 56 → 61, partly 23 → 19, missing 11 → 10 (current modules: works 50 → 54, partly 19 → 15, missing 5 → 5) |

### 2.1 Item 1: white balance's settings list

- **Engine** (`engine/src/actions.c`): new action `temperature list_settings`: desktop's list in its order (gui_init,
  `_generate_preset_combo`): "as shot", "from image area", "user modified", "camera reference", the camera's maker
  and model as a heading, then the camera's presets from `wb_presets.json` (at most 50, as desktop). `current` is the
  entry desktop's `gui_update` shows. Each camera entry carries the tuning `_preset_tune_callback` would take (the
  finetune slider's value: the current preset's tuning, 0 without one). `get_wb` gained `finetune_min`,
  `finetune_max`, `finetune_default` (null where desktop hides the slider: no matched camera preset, or one without
  fine tuning). The matching code of `get_wb` moved into `_wb_match` unchanged. `apply_preset` was already there (C14).
- **App**: the settings list is an `action_list` (override `temperature.json`), which gained headings, entries that
  cannot be chosen (greyed out with the reason) and an entry that arms a picker. Choosing a mode or preset calls
  `apply_preset {name, tuning}`: one history item. "from image area" arms the picker of the button below, as desktop's
  callback does. The finetune slider (`bind_action`, new `hide` and `range_from`) is hidden unless the current preset
  has fine tuning, and then has that preset's tunings as limits and its untuned entry as default, as desktop.
- **Tests**: C41 (every camera preset of the three test cameras, and every fine-tuning step of the RX100M3's
  "daylight", -7..7, against `wb_presets.json` read by the test itself with desktop's interpolation; one history item
  each; the finetune range; darktable-cli parity of the written XMP); C43 adds the temperature and tint of every
  preset tuning against desktop's own `_prepare_matrices` and `_mul2temp` compiled verbatim; JVM tests (schema and
  darkroom, on the stand-in engine, which got a small camera preset table); screenshots in the three skins
  (`phone-{darktable,modern,l1}-p6a-01-wb-preset.png`, `...-p6a-02-wb-settings-list.png`).

### 2.2 Item 2: graduated density's offset

- New override operation `add_slider` (`app/schema` `Overrides.kt`): a slider for a float parameter that desktop sets
  only on the photo, inserted after a given node with the next free id. `graduatednd.json` puts **offset** after
  rotation.
- **Range**: the brief asked for the introspection's range, but darktable declares none for `offset` (no `$MIN`/`$MAX`:
  the schema has -FLT_MAX..FLT_MAX). The slider uses **0..100 %** (desktop's default 50 = the centre): with rotation 0
  that is the line from the top edge to the bottom edge (`_set_grad_from_points`: offset = 100 × the line's height).
  A turned line can reach a little beyond (about -20.7..120.7 at 45°) by dragging on desktop; the slider cannot.
- **On-photo line: not built.** Crop's frame (P3a) and perspective's lines (P3n) are their own gesture code with no
  shared "line with two handles" tool; a new one, with desktop's mapping between the two handles and
  rotation/offset (`_set_grad_from_points`, `_set_points_from_grad`), is a package of its own, not a small addition.
- **Tests**: C42 (offset written as the slider writes it, one history item, darktable-cli parity of the XMP); JVM
  (the slider's place, id, limits; `add_slider` refuses a wrong label, an unknown parameter, a parameter that already
  has a slider).

### 2.3 Item 3: pickers and auto buttons, per module

Route: **engine actions** for all (P3c's route of deliverable 2: the bridge picks with desktop's picker code and
repeats the callback's arithmetic; P3c's other route, app formulas, would need the working profile in the app). Where
the arithmetic is more than a few lines it is **copied verbatim** from the pinned source into the bridge (AgX's curve,
rgb levels' `_auto_levels`, colour equalizer's `_pipe_RGB_to_Ych`, colour zones' `_add_node`, colour harmonizer's
`_picked_color_to_hue`), marked as such, with a check that the parameter size matches. Test **C43**
(`dtb_pickref <...> p6a`) compiles desktop's own callbacks (`color_picker_apply` and the button callbacks, with
their GUI calls stubbed, as C25) and runs them on a copy of the module with the same picked values (for rgb levels:
on the same input buffer), field by field.

| Module | Pickers and buttons | State | Engine action | C43 (largest difference, 3 images) |
|---|---|---|---|---|
| rgb levels | "auto", "auto region" | done | `auto_levels {channel?, area?}` | 0 (whole, region, independent G, no norm) |
| AgX | 5 pickers, read exposure, reset primaries (3 entries), set from above | done | `pick {which}`, `read_exposure`, `reset_primaries {preset}`, `primaries_from_above` | 0 (pickers with and without "keep the pivot on the diagonal") |
| colour equalizer | white level; node placement | done (node placement: values only, as desktop sets nothing) | `white_level` | 0 |
| colour zones | "create curve" | done (flat) | `set_curve_from_area {channel}` | 0 (hue tab, lightness tab, select by lightness) |
| colour harmonizer | anchor hue, 4 custom hues; "analyze" | pickers done; "analyze", "import" not built | `pick_hue {which}` | 0 |
| split-toning | 2 hue pickers | done | `pick {which}` | 0 |
| colorize | hue picker | done | `pick` | 0 |
| spektrafilm | 2 pickers | **not done** (1.1) | - | - |
| unbreak input profile | 3 pickers, auto tune levels | done | `pick {which}` | 0 |
| basic adjustments | middle gray picker; "auto" and its area | **partly** (middle gray) | `pick_grey` | 0 |
| fill light | picker | done (values only: desktop sets no parameter) | - | - |
| color balance | optimize luma, neutralize colors | **not done** (1.1) | - | - |
| invert | film colour | done | `pick` | 0 |
| levels | 3 pickers; "auto" | pickers done; "auto" not built | `pick {which}` | 0 |
| white balance (item 1) | temperature and tint of every preset tuning | - | `get_wb` | 0 K, tint 0 |

App side: the pickers are P3c's `picker` overrides with an `action` (one file per module); the buttons use a new
override operation **`action_button`** (`app/schema` `P6aOverrides.kt`): a desktop button done by a module action,
with a menu's entries as choices and `tab_args` as a picker's. rgb levels' "auto region" is desktop's own area tool,
not a colour picker; the app uses its picker tool to draw the area.

## 3. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p6a-small-module-gaps
tools/fetch-darktable.sh /tmp/dt
tools/build-engine-linux.sh --deps-only
DTA_WORK=/tmp/dta-work tools/build-engine-linux.sh /tmp/dt /tmp/dt-linux
tools/engine-test/fetch-paths.sh /tmp/dtt images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw \
  0001-exposure/exposure.xmp 0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp \
  0179-spektrafilm/spektrafilm.xmp
engine/tests/run_tests.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/results C14 C19 C25 C41 C42 C43
# audit
python3 -I tools/module-audit.py /tmp/dt --report docs/experiments/P5a-module-audit.md
# JVM, screenshots: CI p2a-app; emulator: p2b-real-engine (P6aTapTest); Linux suite and Android engines: g0-app
```

## 4. Results with numbers

Last code commit **d7e24972** (later commits change `docs/` only).

| Workflow | Run | Result |
|---|---|---|
| p2a-app | 45731530940 | success: 647 JVM and screenshot tests, 0 failed (new: schema P6aModuleGapsTest 8, darkroom P6aModuleGapsTest 3, PhoneP6aScreenshots 3); rule check; kept list |
| g0-app | 45730511265 | Linux bridge suite **514 PASS, 0 FAIL** (C41, C42, C43 on cr2, raf, arw included); Android engines arm64-v8a and x86_64 built; its apk job failed on P6aTapTest's missing import (fixed in d7e24972, then built by p2b and by g0 45738468827) |
| p2b-real-engine | 45735375991 | success: emulator **OK, 131 tests** (P6aTapTest t1, t2 included); compares as before; APK release **dt-android-d7e24972df5b** |
| p2b-real-engine | 45732776612 | failure: `P3mRetouchLutTest.algorithmsSettingsOpacityRemove` (JVM, stand-in engine) "expected null, but was (0.33, 0.47)": not touched by P6a, passed in both p2a runs and in the next p2b; a timing flake, reported here, not changed |
| g0-app | 45738468827 | `tests=C40`: success: **C40** cr2 84 modules switched on and rendered, 84 same as darktable-cli, 0 differ; raf 80 rendered, 80 same, 0 differ (the touched modules included); its apk job built the instrumented tests with P6aTapTest; emulator job success |

| What | Result | Where |
|---|---|---|
| C41 white balance list and presets | 3 cameras (Canon EOS 40D 6 presets, Fujifilm X-T3 7, Sony DSC-RX100M3 15 with "daylight" -7..7): multipliers vs `wb_presets.json` **0** difference, every fine-tuning step 0; desktop's order; one current entry; darktable-cli parity of the XMP: identical (psnr inf) | g0 `g0-linux/<image>/C41.log`; workspace |
| C42 graduated density offset | one history item; darktable-cli parity identical (psnr inf) on 3 images | same |
| C43 P6a actions vs desktop's callbacks | **0** largest difference (bound 1e-6) on 3 images: 4 rgb levels auto cases, 3 levels, invert, 2 split-toning, colorize, 4 unbreak profile, basic adjustments, colour equalizer (and its colour space), 3 colour zones, 2 colour harmonizer, 10 AgX pickers, 5 AgX buttons; white balance temperature and tint of 6 + 7 + 141 preset tunings vs `_mul2temp`: 0 K, 0 | g0 `g0-linux/<image>/C43.log` |
| C4, C14, C19, C25, C38 (touched code nearby) | PASS on 3 images | g0; workspace (37 PASS) |
| emulator P6aTapTest | t1 rgb levels "auto" by tap: levels R [0.00142, 0.50071, 1.0], history 11 → 13 (module switched on, then auto, as desktop); t2 AgX black relative exposure picker by tap: -10 → -10.4046 EV (denoised area), pivot 0.6155, history 11 → 13 | p2b `p2b-emulator/p2b-test/p6a-results.txt` |
| screenshots, three skins | `phone-{darktable,modern,l1}-p6a-01-wb-preset.png` (daylight with finetune, +0 mired), `...-p6a-02-wb-settings-list.png` (heading, greyed "user modified" with its reason); looked at | p2a `p2a-app/screenshots/` |
| audit | works 56 → 61, partly 23 → 19, missing 11 → 10, n/a 7 (current: works 50 → 54, partly 19 → 15, missing 5 → 5) | `docs/experiments/P5a-module-audit.md`, `P5a-results/modules.json` |

Workspace runs (x86_64, 2 cores) used the same commands as CI; no timings were taken.

## 4b. Owner instructions

Install **dt-android-d7e24972df5b** (`dt-android-arm64-v8a.apk`) over the old one.

- **White balance → settings**: tap it: the list has as shot, from image area, user modified (greyed: it is set by
  the sliders), camera reference, then your camera's name and its presets (daylight, cloudy, shade, flash, ...).
  Choose one: the photo changes, one history step. Where the preset has fine tuning, the **finetune** slider appears
  below it (in mired, the camera's own range). "from image area" switches the picker on, as on the PC.
- **Graduated density → offset** (below rotation): moves the filter line, 50 % = through the centre (for an untilted
  line 0 % is the top edge and 100 % the bottom edge).
- **New pickers and buttons** (all as on the PC): rgb levels "auto" and its area picker; AgX's five pickers and its
  buttons "read exposure", "reset primaries" (blender-like, smooth, unmodified), "set from above"; colour equalizer's
  white level; colour zones' "create curve"; colour harmonizer's hue pickers; split-toning's and colorize's hue
  pickers; unbreak input profile's four pickers; basic adjustments' middle gray; invert's film colour (invert is
  usable now); levels' black, gray, white. Fill light's and colour equalizer's node placement pickers show the
  values under the photo.
- **Please check** on a photo you also edit on the PC: white balance with a preset and a finetune step (the channel
  coefficients should match the PC's), and AgX's "auto tune levels" on the same area.

## 5. Surprises in upstream

- **finetune was never shown in the app.** The extractor's sweep never met a camera preset with fine tuning, so
  `schema/ui-schema.json` has white balance's finetune hidden in every state, and `Rules.isVisible` hid it although
  P3c had bound it (P3c deliverable 5). Found in P6a's first screenshot (daylight chosen, no finetune); a slider bound
  to a module action with `hide` is now shown by the engine's answer (`app/schema` `Rules.kt`).
- **The bridge picked colour equalizer in JzCzhz for every picker** (`engine/src/pickers.c` `_picker_cst`, fixed per
  module): desktop's white level picker is `dt_color_picker_new` (the module's default colour space), only its hue
  shift picker is `dt_color_picker_new_with_cst(..., IOP_CS_JZCZHZ)`. New `dtb__pick_cst`; C43 checks the colour
  space. Per-control picker colour spaces may matter for later modules too.
- **AgX's pickers are denoised** (`DT_COLOR_PICKER_AREA | DT_COLOR_PICKER_DENOISE`, `agx.c` gui_init); C43 cannot see
  that (both sides take the bridge's picked values), so it was checked by reading gui_init for every module here.
- **AgX's pickers do not write the curve gamma** although `color_picker_apply` computes it: it is set on the slider
  inside `DT_ENTER_GUI_UPDATE`, which does not reach the parameters. The bridge leaves it too (C43 agrees).
- **`dt_dev_exposure_get_effective_exposure`** (`develop/develop.c`) answers 0 outside the darkroom view and without
  the exposure panel's proxy; the bridge finds the exposure instance itself (`p6a_agx.c` `_effective_exposure`).
- `agx.c` has one definition with a leading space (` static inline float _scale(`); `pickref_extract.py` now accepts it.

## 6. Open questions for the reviewer

1. **Copies of upstream code in the bridge.** Where the arithmetic is long (AgX's curve, about 300 lines), the bridge
   carries a verbatim copy of the pinned source instead of a hand-written port, with a parameter-size check, and C43
   compiles desktop's own callbacks to compare. On an engine bump the copies must be refreshed by hand (C43 would
   show a difference, the size check stops a changed structure). Acceptable, or should the bridge generate them at
   build time from the source (as `pickref_extract.py` does for the tests)?
2. **graduated density's on-photo line**: worth a package of its own (a reusable "line with two handles" tool would
   also serve later modules), or is the slider enough?
3. **color balance's pickers** need the patches its seven other pickers keep; the app could keep them per photo (as
   P3c keeps picker settings) and pass them to an engine action. Wanted, given that desktop lists the module in no
   group?
4. **spektrafilm's pickers** would need the film simulation in the bridge: a copy of `_ensure_sim` and the solvers, or
   an upstream helper that a module action could call. Wanted?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoZyuuWXQcumWd2fsR

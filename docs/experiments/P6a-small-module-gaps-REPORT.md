# P6a: small module gaps from the audit (pull request description)

Branch `p6a-small-module-gaps` from main 7173c2a. Brief: `docs/briefs/P6a-small-module-gaps.md`.

Measurements: **workspace** = build workspace, x86_64, 2 cores (a Linux build of the pinned darktable and the bridge
was made there this time, so the bridge tests ran locally before CI); **CI** = GitHub runners, x86_64; **emulator** =
CI's x86_64 Android emulator. None of them is a phone.

## 1. Failures, limits and shortfalls (read first)

@@LIMITS@@

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

@@MODULES@@

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

@@RESULTS@@

## 5. Surprises in upstream

@@SURPRISES@@

## 6. Open questions for the reviewer

@@QUESTIONS@@

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoZyuuWXQcumWd2fsR

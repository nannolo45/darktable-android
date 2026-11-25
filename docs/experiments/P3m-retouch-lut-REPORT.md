# P3m: retouch and colour look up table

Branch `p3m-retouch-lut`. Brief: `docs/briefs/P3m-retouch-lut.md`. No file under `upstream/` changed; the app reaches the
engine only through `dtbridge.h` (new calls in engine-api.md 6.10: `dtb_retouch`, `dtb_retouch_add`,
`dtb_retouch_shape_set`, `dtb_retouch_shape_remove`, `dtb_retouch_select`).

**Not done, said first:** retouch item 4 (the uncropped picture with the crop frame while retouch is focused) is not
built; see section 5. Everything else in the brief is done.

## 1. What was asked and what is delivered

### Retouch

| Item | State |
|---|---|
| 1. Panel: shape count (all scales), shape buttons circle/ellipse/path/brush, algorithm buttons clone/heal/fill/blur, long press = ctrl+click (algorithm of the selected shape; several shapes for a shape button), "show and edit shapes", "display masks", "temporarily switch off shapes"; selected-shape section (name/type, fill mode, fill colour with picker, brightness, blur type, blur radius, mask opacity, each only when it applies) | done: `ui/RetouchPanel.kt`, `Retouch.kt`; bridge test C32, JVM `P3mRetouchLutTest` |
| 2. On the photo: tap places target and source at desktop's default offset; drag moves source or target; circle/ellipse press-and-drag places both; two fingers pan/zoom only; brush with the finger, no pressure; no "pick source first" | done (`Retouch.kt`, `ui/Photo.kt`); emulator m1/m2, JVM tests |
| 2. Rotation of a shape | as the masks tools: a "rotation" slider (ellipse), no rotation gesture on the photo (the masks tools have none either). See section 6 |
| 3. Wavelet: engine renders scales > 0; panel line "wavelet decompose: n scales · edit on desktop"; only shapes of the current scale shown/edited; wavelet params never changed | done: C32 (shape edited on an XMP with 4 scales, `num_scales`, `curr_scale`, `merge_from_scale`, `preview_levels` unchanged, darktable-cli parity), JVM `waveletScalesShowTheCurrentScaleOnly` |
| 4. Uncropped picture with crop frame while retouch is focused | **not done**, proposed in section 5 |

### Colour look up table

| Item | State |
|---|---|
| 1. Patch grid 6x4 for 24 patches, 7x7 above; outline on altered patches; selected marked; patch combo below; tap selects; picker selects the nearest patch | done: `Lut.kt`, `ui/LutPanel.kt`; JVM `lookUpTablePatchEdits`, `moreThan24PatchesAreASevenBySevenGrid`, `hitTestsAndMoves` |
| 2. Sliders lightness, green-red, blue-yellow, saturation (target offsets) | done (ranges from colorchecker.c gui_init) |
| 3. Long-press menu: replace source with picked colour, reset, delete; "add a patch from the picked colour" button | done |
| 4. Presets from the existing presets menu | done (no new code; the module's presets are listed as for every module) |
| 5. No "target color" / "number of patches" rows | done (left out) |

### Tests

| Item | State |
|---|---|
| Bridge tests for the new calls | done: C32 (`engine/tests/dtb_test_p3m.c`) |
| XMP round trip read back by darktable-cli: retouch each algorithm, each shape, source moved | done: C32 parity `p3m_shapes`, `p3m_source`, `p3m_settings`, `p3m_removed`; emulator XMPs `p3m-heal`, `p3m-all` in p2b compare |
| XMP round trip: look up table changed, added, deleted, reset | done: C32 parity `p3m_lut_changed`, `p3m_lut_added`, `p3m_lut_reset`, `p3m_lut_deleted` (reset and delete added in this round); emulator `p3m-lut` |
| Controller/JVM tests | done: `P3mRetouchLutTest` (10 tests) |
| Screenshot tests in three skins | done: `PhoneP3mScreenshots` (6 tests), `phone-{darktable,modern,l1}-p3m-0{1..4}-*.png` |
| Emulator scenario: heal circle placed, a patch changed, render changes | done: `P3mScenarioTest` m1 (heal tap, source drag, switch off), m2 (every algorithm and shape), m3 (patch 13 changed) |
| Wavelet survives open / shape edit / save | done: C32 step 6 |

## 2. Reproduce

```
# bridge tests (Linux reference build, as CI job g0-app/linux): C32 and its darktable-cli parity
engine/tests/run_tests.sh            # see the g0-app workflow for the environment (G0_RAW, G0_XMP, DTA_WORK)
cd app
gradle :darkroom:testDebugUnitTest --tests '*P3mRetouchLutTest'
gradle :darkroom:testDebugUnitTest --tests '*PhoneP3mScreenshots'   # Robolectric jars need network
# emulator scenario and compare: workflow p2b-real-engine (P3mScenarioTest, compare step "p3m")
gh workflow run g0-app.yml           --repo nannolo45/darktable-android --ref p3m-retouch-lut
gh workflow run p2a-app.yml          --repo nannolo45/darktable-android --ref p3m-retouch-lut
gh workflow run p2b-real-engine.yml  --repo nannolo45/darktable-android --ref p3m-retouch-lut
```

## 3. Results (x86_64 CI runners, not phone numbers)

| Workflow | Run id | Commit | Result |
|---|---|---|---|
| g0-app | 45507750345 | 490cf4d | success (bridge tests 461 passes, 0 failures; apk; emulator) |
| p2b-real-engine | 45507844583 | 490cf4d | success (emulator and compare, `p3m` ok) |
| p2a-app | 45507560631 | a3cfc34 | success (the one later commit changes only `engine/tests/`, which p2a does not build) |

Earlier failures, fixed on this branch:

- g0-app 45495680990 (5cf164b), job apk: `P3mScenarioTest.kt:183/190 Unresolved reference 'key'`; fixed by ede40ab
  (import of the instance key extension).
- Same run, job linux: C32 failed on all three cameras at `dtb_test_p3m.c:135` "moving the source changes the
  picture" (render max diff 0), and parity `p3m_source` bridge vs darktable-cli differed (cr2 PSNR 45.31, raf 41.43,
  arw 30.87). Cause and fix in section 4; fixed by a3cfc34. After the fix: source moved, render max diff 118 (cr2), and
  `p3m_source` parity max 0 on cr2, raf and arw.

C32 (cr2, run 45507750345): five shapes vs none max 255 mean 2.020; heal source moved max 118 mean 0.0685; reopen equal,
max 0; wavelet after edits `num_scales 4 curr_scale 2 merge_from 3 preview_levels[0] -2.5`; look up table reset max 79
mean 4.22; delete max 7 mean 0.165, 24 patches. Every C32 parity file (9 per camera) bridge vs darktable-cli: max 0.

Emulator (p2b 45507844583): m1 heal placed, items 11 -> 12, preview diff 0.2892; source dragged, preview diff 0.3718;
shapes switched off, diff to the plain picture 0.0000; m2 ellipse/clone, brush/fill, path/blur, items 11 -> 19; m3 24
patches, patch changed, preview diff 1.0588. App export vs darktable-cli of the app's XMP (3908x2602, threshold as the
other editors): p3m heal PSNR 44.05 (max 20), p3m all PSNR 44.14 (max 20), p3m lut PSNR 43.97 (max 20).

Logs: `ci-logs` branch, `runs/45507750345-a1/` and `runs/45507844583-a1/`.

## 4. Upstream surprises

- `develop/imageop.c`, `dt_iop_commit_params`: a piece's mask group enters its hash only while the module "is
  blending": blend `mask_mode` not off, or the module is the focused one (`dt_dev_gui_module()`). Retouch
  (`IOP_FLAGS_NO_MASKS`) keeps `mask_mode` off and its params hold only the shapes' ids and settings, so its shapes'
  geometry and sources reach the hash only through focus. On desktop the module is always focused while a shape is
  edited; the bridge has no focused module, so a moved source rendered from the old cache line. Fix in the bridge
  (`engine/src/render.c`, `_shapes_into_hash`): after each pipe synchronisation the group's hash
  (`dt_masks_group_hash_ext`) is added to such pieces, once (the commit's hash is kept, so a piece not committed again
  is not hashed twice). It covers undo/redo too (C32: undo puts the source back, max 0).
- `iop/retouch.c` `rt_select_algorithm_callback`: ctrl+click only swaps clone <-> heal and blur <-> fill; the bridge
  refuses other swaps with the same rule.

## 5. Proposal: retouch item 4 (uncropped picture)

The pipeline can already show the uncropped picture (the crop tool, brief P3a, `CropToolState.picture`). Using it for
retouch needs the retouch outlines and touch positions mapped to that picture instead of the processed one
(`coordsTransform` today maps to the processed picture), the frame drawn read-only, and the view reset when retouch
opens/closes. That is more than a small finish, so it is left for a follow-up package; until then retouch works on the
cropped picture, and a shape outside the crop cannot be placed or seen.

## 6. Where the app differs from desktop, and why

- **Rotation** of an ellipse: a slider in the panel (as the masks tools), not shift+scroll on the photo; the masks tools
  have no rotation gesture, and the owner's rule was "tap and drag are enough".
- **Size, feather, flatness, hardness**: sliders (desktop: mouse wheel with modifiers on the photo); same reason.
- **ctrl+click** (several shapes with one button; algorithm of the selected shape): long press on the button.
- **Path**: corner points tapped one by one, closed with "done" or a tap on the first point; no bezier handles while
  drawing (desktop: click-drag for curved nodes). Brush: no pressure (approved).
- **Moving a target** moves the shape; its source stays where it is.
- **"temporarily switch off shapes"** ends when the panel closes, as desktop's (it applies only while focused).
- **Wavelet decompose**: not editable (owner: later); shown as one line, shapes of the current scale only.
- **Uncropped view while retouching**: not done (section 5).
- **Colour look up table**: "target color: relative/absolute" switch and the mockup's "number of patches" row left out
  (brief item 5); sliders show relative offsets, desktop's default. Mouse actions are a long-press menu and an "add"
  button (brief item 3).

## 7. Open questions for the reviewer

1. Rotation on the phone: is the slider enough, or should a two-finger twist rotate the selected ellipse? Two fingers
   now always pan/zoom the photo (brief), so a twist would need a mode or a handle; I suggest a rotation handle on the
   selected ellipse's outline in a later package, shared with the masks tools.
2. The hash fix in `render.c` applies to any blending-capable module whose blend group has shapes while `mask_mode` is
   off (today only retouch in practice). Agree to keep it general rather than retouch-only?
3. Item 4 (uncropped view): a follow-up package, as proposed in section 5?
4. Moving a target leaves the source in place. Should the source follow the target (keeping the offset)? I did not
   check desktop's exact behaviour for every shape type.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoep891vjXMfqGv9Mc

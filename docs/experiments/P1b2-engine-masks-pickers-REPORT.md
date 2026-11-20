# P1b part 2: blending, masks, pickers and coordinates in the engine (pull request description)

Branch `p1b2-engine-masks-pickers` from main 6667ad0. Brief: `docs/briefs/P1b2-engine-masks-pickers.md`. Running log:
`docs/experiments/P1b2-engine-masks-pickers.md`. The calls are specified in `docs/design/engine-api.md` section 6
(complete for everything built). Last code commit and runs: section 6.

Measurements: **workspace** = build workspace, x86_64, 2 cores; **CI** = GitHub runners, x86_64; **emulator** = CI's
x86_64 Android emulator. None of them is a phone.

## 1. Failures, limits and shortfalls (read first)

1. **Not built:** color calibration's area picker in **"measure"** mode (`channelmixerrgb spot {mode: "measure"}`
   answers `ARG`): it needs `_get_corrected_illuminant_xy` (iop/channelmixerrgb.c), which reads the white balance
   through the interface. Its "correction" mode (area mapping to a target) and the CAT tab's "from image area"
   (`illuminant_from_area`) are built. The read-only curve marks of `tonecurve`/`rgbcurve` are not a separate action
   (`dtb_color_pick` on the module gives the same numbers). `rgblevels`' automatic levels from a region (its
   histogram based "auto") is not built; its three point pickers are.
2. **"Compared with the value desktop's own code computes"** (deliverable 4): the picked values themselves come from
   desktop's own functions (`dt_color_picker_backtransform_box`, `dt_color_picker_box`, `dt_color_picker_helper`,
   called by the bridge, section 3 item 4). The module actions' arithmetic is desktop's code copied into the bridge
   (the functions are static in the module files and tied to their interface), and the tests check it against an
   independent recomputation of desktop's formula on the same picked values (temperature: 1.2e-7; rgblevels: equal)
   or against a property desktop's code guarantees (exposure: measured lightness after a correction to 50 % is
   50.0000; color calibration: the area is neutral afterwards, spread 6e-4). No test compiles desktop's own module
   functions verbatim as P3a did for crop: I judged the copies short enough to read against upstream; a verbatim
   harness would be the stronger proof (open question 3).
3. **Masks follow the pinned commit's flexi model, not the classic per-member combine of the brief's wording.** At
   04477d3 a module's mask is a tree of groups whose operator is per group (`dev-doc/masks_data_model.md`); a classic
   per-member "union / intersection / ..." no longer exists for new masks (old edits are converted on load). So
   "combine mode" is the group's operator (`dtb_mask_group_set`), inversion and opacity are the member's
   (`dtb_mask_attach`, `dtb_mask_member_set`). engine-api.md 6.7 says so. Raster and parametric members (new form
   types of that model) are listed but cannot be created through the bridge; moving a member inside a group is not
   built.
4. **Two upstream assumptions the bridge had to work around (no upstream change):** (a) `dt_iop_commit_params`
   hashes a piece's mask through the *global* develop object's forms, so in the bridge an edited shape kept its old
   cached render (found by C22, max 82); the bridge points that object's form list at the session's while a pipe
   synchronises. (b) Mask display: blend.c shows a mask only for the module with the focus on the develop object's
   full pipe, and the mask channel is set to 0 by colorin's blue-mapping path and by colorout in the bridge's
   export-type pipe; the bridge sets focus and pipe for the one render and carries the channel through same-window
   modules behind the shown one. Both are described in section 4; a cleaner fix would be upstream (open question 1).
5. **A pre-existing bridge bug found and fixed on the way:** `dtb_instance_duplicate` (P1a) called
   `dt_masks_iop_use_same_as`, which works on the global develop object: a duplicated instance lost its drawn mask in
   the session. Now the same steps run on the session's forms (C22 checks it; render = darktable-cli, max 0).
6. **Brush strokes have no pressure** (as the brief says): every sample gets the default size, hardness and density.
7. **FakeEngine** keeps blending and masks as data (undo included) and draws neither; its pickers pick on its own
   picture (a module's "input" is the picture with the module left out); its "show mask" render is its picture in
   grey; its brush stroke is not simplified; it records no history item for a form no instance uses (the bridge
   records one for darktable's mask manager); its XMP carries neither masks nor blending.
8. Workspace only: T5 `cr2 on-defringe region 0` above its bound (as in P1b part 1, thread-count dependent; CI
   passes); **T8 (cancel) is timing-marginal on the 2-core workspace**: the third cancel lands within ±0.01 s of the
   end of the render; main's bridge and this branch both passed 4 of 4 on an idle workspace, this branch failed 2 of 3
   while other tests ran beside it. CI passes it.
9. Changes outside `engine/` and `app/engine-*`: the crop tool (`app/darkroom` `CropSession.kt`,
   `DarkroomController.kt`, `DarkroomState.kt`, and one condition in `ui/Photo.kt` so the tool's sharp region is
   drawn), its tests, the emulator crop scenario; `g0-app.yml` (this branch in the triggers, four darktable-tests
   XMPs with masks in the fetch list, `dtb_test_p1b2.c` in the bridge build's cache key), `p2b-real-engine.yml`,
   `p2a-app.yml` (this branch in the triggers). Those trigger lines must be removed before merging.

## 2. Item by item

| # | Item | State | Evidence |
|---|---|---|---|
| 1 | Render that bypasses one instance (preview, region, size) | **done** | C17 (3 images), contract `aBypassRenderLeavesTheEditAlone`, `aRedoStepSurvivesBypassRenders` |
| 2 | Crop tool uses it; redo survives; XMP byte-equal; sharp region when zoomed | **done** | `P3aCropToolTest` (changed tests listed below), emulator c1/c2 byte-equal |
| 3 | Undo/redo keep the XMP's history hashes | **done** (timestamp: not moved, as desktop) | C18 (3 images), contract `anUndoBackGivesTheXmpOfThatState` |
| 4 | Pickers (point, area; input, output; global), histogram, module actions | **done except** channelmixerrgb "measure" (item 1.1) | C19 (3 images), contract `colorPickersAndHistogram`, `moduleActionsThatPick` |
| 5 | `coords_transform` both directions | **done** | C20 (3 images), contract `coordinatesBetweenOriginalAndPicture` |
| 6 | Blending get/set with desktop's constraints, grouped like `param_set` | **done** (raster source not settable) | C21 (3 images, renders = darktable-cli max 0), contract `blendingGetSetValidateAndGroup` |
| 7 | Drawn masks: list, create (circle, ellipse, gradient, path, brush), update, delete, rename, groups, attach/detach, shared forms; XMP parity; desktop XMPs | **done** in the flexi model (item 1.3) | C22 (3 images, 4 parity renders each, max 0), C23 (4 desktop XMPs, max 0), contract `drawnMasksCreateAttachShareEditDelete` |
| 8 | Mask display render, preview and region | **done** | C24 (3 images), contract `maskDisplayRender` |
| 9 | FakeEngine, contract suite, engine tests, both ABIs, real-engine workflow | **done** (see section 6 for the runs) | |

**Item 1.** `dtb_render_size_ex`, `dtb_render_preview_ex`, `dtb_render_region_ex` take `{"bypass": [...],
"show_mask": ...}`. For the one call the bridge switches the instance's piece off in its own synchronised pipe and
changes the piece's hash, then restores both and recomputes the dimensions. The hash matters: darktable's cache hash
(`pixelpipe_cache.c` `_dev_pixelpipe_cache_basichash`) includes a piece whenever `module->enabled || piece->enabled`,
so without it the modules after a bypassed one would reuse the cache lines of the render with it. C17: modules,
history, undo state and the XMP bytes equal before and after bypass renders; the next plain preview and region
byte-equal to the ones before; bypass = switched off for real, max 0 (exposure and crop; preview and a 1/2 scale
region); size with crop bypassed = crop's input size; a redo step survives bypass renders.

**Item 2.** The crop tool's uncropped picture is `renderSize(1, bypass crop)` + `renderPreview(size, bypass crop)`.
Gone: the switch-off/render/undo bracket, `cropBracketOpen`, `CROP_NOT_WRITTEN`, `RedoGuard`. `CropToolState.options`
keeps the options; zoomed in, the region is `renderRegion(…, options)` of the uncropped picture (P3a limit 3) and
`Photo.kt` draws it while the tool shows that picture (`regionOfCropTool`, not while the picture turns). Without
options the controller calls the plain overloads (wrappers in tests count those). **Tests changed to pin the new
behaviour** (`app/darkroom/src/test/.../P3aCropToolTest.kt`):
- `theToolOpensOnTheUncroppedPictureWithoutChangingTheEdit`: no switch-off, no undo, undo state unchanged (was: one
  undo, a hidden redo step);
- `aSyncDuringTheBracketWaitsAndWritesTheEdit`: the sync's write arrives during the bypass render (was: after the
  switch-off); XMP equal, no undo;
- `theBracketsRedoStepIsNeverOffered`: now the user's redo step survives opening and closing the tool (was: the
  bracket's step hidden by `RedoGuard`);
- `anUndoThatFailsStopsEveryWrite`: now a failing uncropped render leaves the tool closed, the edit as it was, and
  every write working (was: a failing undo stopped all writes);
- `theRotationSliderTurnsThePictureThenWritesOneItem`: counts the bypass render as "rendered again" (was: the
  bracket's undo);
- new: `openingAndClosingTheToolWithoutAChangeLeavesTheXmpByteEqual`, `zoomedInTheToolShowsItsSharpRegion`;
- emulator `P3aScenarioTest` c1 and c2 now also require the XMP byte-equal (CI: "differences: none", "identical:
  true").

**Item 3.** Cause: `dt_history_snapshot_restore` deletes the image's hash row and writes back only `current_hash`;
it also clears the change timestamp and detaches `darktable|changed`. Each undo entry now keeps the hash row and
whether the tag was attached; restoring writes them back. The change timestamp is kept as it was before the undo:
desktop's `_pop_undo` (libs/history.c) writes the history but does not set the timestamp (only
`dt_dev_add_history_item` does). Deliberate difference: the `changed` tag follows the state (desktop keeps it once
set), so an undo back to the opened state gives the opened XMP apart from the timestamp. C18: opened XMP = XMP after
two undos (timestamp aside); XMP after one undo = XMP at that state; redo back to the last state: byte-equal.

**Item 4.** `dtb_color_pick(op, priority, request)`: module picker (input and output, before blending, in the
module's picker colour space as its `gui_init` sets it: `default_colorspace`, or the fixed ones of temperature,
colorzones, colorequal, satcurvergb), or with `op` NULL the pipe's output (global picker). The module is forced to run
once on the preview pipe at scale 1 (hash changed for that render); the statistics are taken inside its
`process_plain` (the times.c wrapper), with desktop's box functions and helper. `dtb_histogram`: desktop's RGB
histogram binning (`common/histogram.c` `_bin_rgb`: 256 bins, `(size_t)CLAMP(255 v, 0, 255)`) of the preview pipe's
picture. Actions: `temperature wb_from_area`, `exposure spot` (correction, measure), `rgblevels pick` (black, grey,
white), `rgbcurve set_nodes_from_area`, `channelmixerrgb illuminant_from_area` and `spot` (correction). C19 (3
images): global picker vs the 8-bit picture over the same box 0.41 / 0.001 / 0.30 levels; exposure's output =
(input − black) × 2^EV on every statistic (spread ≤ 7.6e-8); a pick through a crop lands at 1.5× the box without it;
histogram counts sum to the pixels; plain preview after picks byte-equal; actions as in section 1.2.

**Item 5.** `dtb_coords_transform`: `dt_dev_distort_transform_plus` / `_backtransform_plus`, all directions, on the
full pipe, with optional bypass. C20: round trips 0.000 px (cr2, arw), 0.512 px (raf, lens correction, darktable's
approximate inverse); crop and orientation relations exact against the same points with the module left out; lens +
rotation 4° + crop + orientation + framing round trip 0.002 / 0.512 / 0.052 px.

**Item 6.** `dtb_blend_get` / `dtb_blend_set` (`engine/src/blend.c`). C21: refusals change nothing; one history item
for a `param_set` and two `blend_set` without a seal; renders = darktable-cli of the written XMP, max 0 (uniform 60 %;
parametric with refinement and inverted combine; Lab colour space with "Lab a-channel" reversed), 3 images; undo.

**Item 7.** `engine/src/masks.c`. C22 (3 images): five forms created and attached (5 history items); the brush
stroke of 6 samples became 5 brush points; the mask switched on as flexi; a duplicated instance keeps it; one circle
in two instances' masks (inverted in the second); member opacity and inversion, group operator "screen"; update,
rename, detach, delete; refusals; renders = darktable-cli of the written XMP after each stage, max 0; the written XMP
reopened lists the same masks (JSON equal) and renders max 0. C23: darktable-tests' desktop XMPs 0004-masks,
0081-mask-groups, 0090-mask-combine, 0144-masks-combine-sum (mire1.cr2): listed (6/4/8/4 shapes, 1/4/4/1 groups),
bridge render = darktable-cli of the desktop XMP, max 0, and = darktable-cli of the XMP the bridge writes from them,
max 0.

**Item 8.** C24 (3 images): with a circle on color balance rgb and on exposure, inside the circle 255 255 0, far
outside grey (equal channels); session unchanged; plain preview afterwards byte-equal; refusal without a mask.

## 3. `dtbridge.h` changes (one line each)

- New `dtb_render_size_ex(scale, options_json, w, h)`: size with render options (bypass).
- New `dtb_render_preview_ex(max_size, options_json, out, w, h)`: preview with bypass / show_mask.
- New `dtb_render_region_ex(x, y, w, h, scale, options_json, out)`: region with bypass / show_mask.
- `dtb_render_size` / `_preview` / `_region`: unchanged, now the `_ex` calls without options.
- `dtb_undo` / `dtb_redo` (behaviour): restore the history hash row and the `darktable|changed` tag of the state; the change timestamp is not moved.
- `dtb_module_action`: new actions `temperature wb_from_area`, `exposure spot`, `rgblevels pick`, `rgbcurve set_nodes_from_area`, `channelmixerrgb illuminant_from_area`, `channelmixerrgb spot`.
- `dtb_instance_duplicate` (behaviour): the copy keeps the base's drawn mask in the session (was lost).
- New `dtb_color_pick(op, priority, request_json, json)`: module or global colour picker.
- New `dtb_histogram(request_json, json)`: RGB histogram of the preview pipe's picture.
- New `dtb_coords_transform(request_json, json)`: points between original image and picture.
- New `dtb_blend_get(op, priority, json)`: blend settings by name.
- New `dtb_blend_set(op, priority, values_json)` [M]: blend settings, validated, grouped like param_set.
- New `dtb_masks(json)`: every form of the session.
- New `dtb_mask_create(type, geometry_json, name, json)` [M]: circle, ellipse, gradient, path, brush (or stroke).
- New `dtb_mask_update(id, geometry_json)` [M].
- New `dtb_mask_rename(id, name)` [M].
- New `dtb_mask_delete(id)` [M].
- New `dtb_mask_attach(op, priority, id, options_json)` [M]: form into an instance's mask.
- New `dtb_mask_detach(op, priority, id)` [M].
- New `dtb_mask_member_set(op, priority, id, options_json)` [M]: inverse, opacity, disabled.
- New `dtb_mask_group_create(member_ids_json, name, json)` [M]: a flexi group of forms.
- New `dtb_mask_group_set(id, options_json)` [M]: a group's operator, invert, bypass, opacity, name.

## 4. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p1b2-engine-masks-pickers
tools/build-engine-linux.sh --deps-only
tools/fetch-darktable.sh /tmp/dt-src
DTA_WORK=/tmp/dta-work tools/build-engine-linux.sh /tmp/dt-src /tmp/dt-linux
tools/engine-test/fetch-paths.sh /tmp/dtt images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw \
  0001-exposure/exposure.xmp 0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp \
  0179-spektrafilm/spektrafilm.xmp 0004-masks/masks.xmp 0081-mask-groups/mask-groups.xmp \
  0090-mask-combine/mask-combine.xmp 0144-masks-combine-sum/masks-combine-sum.xmp
engine/tests/run_tests.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/results C17 C18 C19 C20 C21 C22 C23 C24
# JVM: contract suite on FakeEngine (engine-api), crop tool (darkroom, needs the Android SDK: CI p2a-app)
cd app && $GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :darkroom:testDebugUnitTest
```
CI: pushes to this branch run `g0-app` (engines for both ABIs, Linux bridge tests), `p2b-real-engine` (contract suite
and scenarios on the emulator, comparisons) and `p2a-app`.

## 5. Upstream surprises

- `develop/imageop.c` `dt_iop_commit_params`: the piece hash takes the mask group from `darktable.develop`
  (`dt_masks_get_from_id(darktable.develop, ...)`, `dt_masks_group_hash` over `darktable.develop->forms`), not from the
  pipe's develop object: correct only when the two are the same (desktop), stale caches otherwise.
- `develop/masks/masks.c` `dt_masks_iop_use_same_as`, `dt_masks_form_remove`, `dt_masks_group_add_members_of`: work
  on `darktable.develop` whatever module they get.
- `develop/pixelpipe_cache.c` `_dev_pixelpipe_cache_basichash`: a piece counts when `module->enabled ||
  piece->enabled`, so switching a piece off alone does not change the cache key.
- `common/history_snapshot.c` `dt_history_snapshot_restore`: deletes the hash row, the `changed` tag and the change
  timestamp; writes back only `current_hash`.
- `develop/blend.c` `dt_develop_blend_process`: "show mask" only for `dt_iop_has_focus(self) && piece->pipe ==
  self->dev->full.pipe`. `iop/colorin.c` `_process_cmatrix_bm` sets the fourth channel to 0 (`cam[3] = 0.0f`), and
  colorout drops it, so a mask of a module before them cannot reach gamma in a pipe that runs them.
- `common/color_picker.c` `dt_color_picker_box` and `dt_color_picker_backtransform_box` read
  `darktable.develop->preview_pipe` for sizes and distortions.
- `iop/channelmixerrgb.c` `color_picker_apply` → `_auto_set_illuminant`: the CAT tab's picker and the area mapping
  are one function, steered by interface state (spot mode, target sliders, "use mixing").

## 6. Results and runs

(filled in with the last code commit's runs)

## 7. Open questions for the reviewer

1. The two workarounds of item 1.4 (global form list during synchronisation; focus, full pipe and mask channel for
   mask display) touch darktable's global develop object for the length of a call. Acceptable, or should an
   upstream change be proposed (pass the pipe's forms to `dt_iop_commit_params`; let colorin/colorout keep the
   fourth channel when a mask is shown)?
2. Undo restores the `changed` tag with the state (desktop keeps it once set). Keep this, so that an undo back to the
   opened state gives the opened XMP (apart from the timestamp)?
3. Should the module actions' arithmetic be checked against verbatim compiled copies of the upstream functions, as
   P3a did for crop (stronger than the recomputation in C19)?
4. Masks: is the flexi vocabulary (group operator for "combine", member inversion and opacity) what the mask
   screens (P3d) should use? Raster and parametric members, member order and color calibration's "measure" mode are
   the remaining pieces.
5. The CI trigger lines for this branch in the three workflows must go before merging; the fetch list and cache key
   additions in `g0-app.yml` should stay.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqnbz6rKrRZTvLsBxk

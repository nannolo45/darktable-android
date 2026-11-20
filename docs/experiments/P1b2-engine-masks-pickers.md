# P1b part 2: blending, masks, pickers and coordinates in the engine (running log)

Brief: `docs/briefs/P1b2-engine-masks-pickers.md`. Report: `P1b2-engine-masks-pickers-REPORT.md`. Calls:
`docs/design/engine-api.md` section 6. Workspace numbers are x86_64, 2 cores; CI numbers are GitHub runners
(x86_64, 4 cores). Neither are phone numbers.

## 2026-11-20

- 13:15 Branch `p1b2-engine-masks-pickers` from main 6667ad0. Read CLAUDE.md, the brief, engine-api.md, the P1b part 1
  report and log, the P3a report, the bridge, JNI and Kotlin interface, the crop tool's controller code.
- **Build:** the Linux build of darktable 04477d3 and the bridge that P1b part 1 made on this workspace is reused
  (darktable source, build tree and install prefix in a work folder outside the repository; the bridge's build tree
  was configured on this checkout's `engine/`). No rebuild of darktable needed; the bridge rebuilds in seconds.
- 13:40 engine-api.md section 6 (exact signatures and JSON of every call of this package) pushed.
- **Deliverable 1 (bypass).** `dtb_render_size_ex`, `dtb_render_preview_ex`, `dtb_render_region_ex` with
  `{"bypass": [...]}`. The bridge switches the bypassed pieces of its own (synchronised) pipe off for the one call and
  changes their hash, then puts both back. Why the hash: darktable's cache hash
  (`pixelpipe_cache.c` `_dev_pixelpipe_cache_basichash`) includes a piece whenever `module->enabled || piece->enabled`,
  so the modules after a bypassed one would otherwise hit the cache lines of the render with it. Engine test C17
  (3 images): session unchanged (modules, history, undo state, XMP bytes); the next plain render byte-equal to the one
  before; bypass render = render with the instance switched off for real, max 0 (exposure and crop, preview and a
  region at 1/2); a redo step survives bypass renders.
- **Deliverable 3 (undo hashes).** Cause confirmed: `dt_history_snapshot_restore` deletes the hash row and writes
  only `current_hash`; it also clears the change timestamp and detaches `darktable|changed`. Now each undo entry keeps
  the hash row and whether the tag was attached; restore writes them back; the change timestamp is kept as it was
  before the undo (desktop's `_pop_undo` does not move it). Engine test C18 (3 images): XMP after undo to the opened
  state = opened XMP apart from `change_timestamp`; after one undo = XMP at that state; redo to the last state:
  byte-equal. The tag is a deliberate difference from desktop (it keeps the tag); engine-api.md 6.2 says so.
- **Deliverable 2 (crop tool).** `CropSession`/`DarkroomController`: the uncropped picture is
  `renderSize(1, bypass crop)` + `renderPreview(size, bypass crop)`; the switch-off, render and undo bracket,
  `cropBracketOpen`, `CROP_NOT_WRITTEN` and `RedoGuard` are gone. `CropToolState.options` keeps the options, and the
  sharp region of a zoomed-in view is rendered with them while the tool is open (P3a limit 3; `Photo.kt`: one
  condition, the tool's own region is drawn, not while the picture turns). Kotlin: `RenderOptions`, `InstanceRef`,
  overloads `renderSize/renderPreview/renderRegion(…, options)` on `DarktableEngine`, the JNI engine and `FakeEngine`
  (the fake renders the instance as off); contract tests `aBypassRenderLeavesTheEditAlone`,
  `aRedoStepSurvivesBypassRenders`, `anUndoBackGivesTheXmpOfThatState` (engine-api JVM: green on the workspace).
- **P3a tests changed to pin the new behaviour** (`P3aCropToolTest`): `theToolOpensOnTheUncroppedPicture…` (no
  switch-off, no undo, undo state unchanged instead of a hidden redo step), `aSyncDuringTheBracketWaitsAndWritesTheEdit`
  (waits for the bypass render instead of the switch-off; no undo), `theBracketsRedoStepIsNeverOffered` (now: the
  user's redo step survives opening and closing the tool), `anUndoThatFailsStopsEveryWrite` (now: a failing render
  of the uncropped picture leaves the edit and every write as they were). New: `openingAndClosingTheToolWithoutAChange
  LeavesTheXmpByteEqual`, `zoomedInTheToolShowsItsSharpRegion`. Emulator `P3aScenarioTest` c1 and c2 now also require
  the XMP to be byte-equal.
- 13:42 p2a-app 44745746266 (79017c8): 2 of 131 darkroom tests failed. `P2iFitViewTest.zoomInShowsThePreviewAtOnce…`:
  the controller called the new `renderRegion(…, options)` overload also without options, which the test's counting
  wrapper does not see; the controller now calls the plain overloads when there are no options (test unchanged).
  `P3aCropToolTest.theRotationSliderTurnsThePictureThenWritesOneItem` counted the bracket's undo as "the uncropped
  picture rendered again": now counts the bypass renders (a sixth P3a test changed to pin the new behaviour).
- **Deliverable 4.** `dtb_color_pick` (module picker, input and output, and the global picker with op NULL),
  `dtb_histogram`, module actions `temperature wb_from_area`, `exposure spot` (correction and measure),
  `rgblevels pick` (black, grey, white), `rgbcurve set_nodes_from_area`. The box mapping and the statistics are
  desktop's own functions (`dt_color_picker_backtransform_box`, `dt_color_picker_box`, `dt_color_picker_helper`), run
  with the global develop object's preview pipe pointed at the bridge's pipe for the call; the module is forced to
  run once (its piece hash changed for that render) on the preview pipe at scale 1, as desktop's preview pipe.
  Engine test C19 (3 images): global picker vs the 8-bit preview over the same box: 0.41 / 0.001 / 0.30 levels;
  exposure's input vs output on the same box: out = (in - black) * 2^EV on every statistic (spread < 1e-7); a pick
  through a crop lands at 1.5 x the box without it (crop keeping the bottom right quarter); temperature
  coefficients = desktop's formula on the picked mean (max diff 1.2e-7); exposure spot to 50 % then measure: 50.0000;
  rgblevels black = desktop's rule; the plain preview after picks byte-equal to the one before.
- **Deliverable 5.** `dtb_coords_transform` (normalised points, both directions, optional bypass) over
  `dt_dev_distort_transform_plus` / `_backtransform_plus`, DIR_ALL, on the full pipe. Engine test C20 (3 images):
  round trips 0.000 px (cr2, arw as opened), 0.512 px (raf, whose XMP has lens correction: darktable's approximate
  inverse); crop and orientation relations exact (0.000 px, against the same points with the module left out);
  lens + rotation 4° + crop + orientation + framing round trip 0.002 / 0.512 / 0.052 px.
- **Deliverable 6 (blending).** `dtb_blend_get` / `dtb_blend_set` (`engine/src/blend.c`): mask mode by name and as
  flags, blend colour space (offered as desktop's panel offers it; a change resets the parametric channels and the
  mode as `_blendif_change_blend_colorspace`), blend mode (desktop's list for the colour space), reverse, fulcrum,
  opacity, combine (with desktop's polarity rule for unused channels), refinement (feathering and its guide, blur,
  contrast, brightness, details), parametric channels per colour space (active, invert, range in order, boost),
  raster (read; invert settable), mask_lock. Grouped like `param_set`. Engine test C21: refusals change nothing;
  param_set + two blend_set without a seal = one item; renders of the bridge vs darktable-cli of its XMP: max 0
  (uniform 60 %, parametric with refinement, Lab colour space with "Lab a-channel" reversed; cr2, workspace).
  Kotlin `blendGet` / `blendSet`, `Blend`; FakeEngine keeps blend settings as data in its history (it does not blend
  its picture); contract `blendingGetSetValidateAndGroup`.
- 14:05 g0-app and p2b-real-engine triggers added for this branch; first engine CI run on items 1 to 6.

### Interim summary (items 1 to 5), 14:20

Built and tested on the workspace, pushed (98565262), CI on it running (g0-app 44749591994, p2b-real-engine
44749592073; p2a-app 44749591773 green):
1. bypass renders (`render_*_ex`), C17 on 3 images: session and XMP byte-equal, picture = switched off (max 0);
2. crop tool without the bracket (bypass render; redo survives; XMP byte-equal after open/close; sharp region when
   zoomed in), JVM tests changed as listed above (p2a-app green on 56b291c and 98565262);
3. undo/redo keep the hash row and the changed tag, timestamp as desktop (not moved), C18 on 3 images;
4. pickers (module and global), histogram, actions temperature / exposure / rgblevels / rgbcurve, C19 on 3 images;
   channelmixerrgb's area actions not built;
5. coordinates both ways with optional bypass, C20 on 3 images.
Items 6 to 8 are also built locally (blending C21; masks C22, C23 with darktable-tests' desktop XMPs; mask display C24)
and will be pushed when the running CI run has finished.

- 14:15 **Masks: an upstream assumption the bridge has to meet.** `dt_iop_commit_params` (develop/imageop.c) hashes a
  piece's mask through `dt_masks_get_from_id(darktable.develop, ...)` and `dt_masks_group_hash`, which walk the
  *global* develop object's forms, not the session's. In the bridge that object has no forms, so a shape edited after
  it was attached left the piece hash, and the cached output, unchanged: the render showed the old shape until the
  edit was reopened (C22, max 82 vs darktable-cli). Fix in the bridge: while a pipe synchronises, the global object's
  form list is the session's (render.c `_forms_swap_in`). After it: C22 renders = darktable-cli of the written XMP,
  max 0, and a reopened session renders max 0 against the session.
- **Mask display.** blend.c honours "display mask" only for the module with the focus on the develop object's full
  pipe; the bridge sets those for the one render, forces the module to run (hash) and runs the pipe with gamma. The
  mask channel was dropped by colorin's blue-mapping path (`_process_cmatrix_bm`: `cam[3] = 0.0f`) and by colorout
  (which the darkroom's presented pipe does not run); the bridge carries the channel through same-window modules
  behind the shown one while a mask is shown. C24: inside the circle 255 255 0, outside grey; exposure (before
  colorin) and color balance rgb.
- **Desktop XMPs with masks** (darktable-tests 0004-masks, 0081-mask-groups, 0090-mask-combine,
  0144-masks-combine-sum, all on mire1.cr2): listed (6/4/8/4 shapes, 1/4/4/1 groups), bridge render = darktable-cli of
  the desktop XMP, max 0, and = darktable-cli of the XMP the bridge writes from them, max 0 (workspace; C23).
- 14:40 CI on 98565262 (items 1 to 6): **g0-app 44749591994 success** (Linux bridge tests 314 PASS lines, 0 FAIL;
  C17 to C21 on three images; T8 passed there; T5 cr2 on-defringe within its bound on CI), both ABIs built;
  **p2b-real-engine 44749592073 success** (emulator: OK, 63 tests, the new contract tests among them; P3a c1 "sync
  during tool differences: none", c2 "XMP identical: true"); p2a-app 44749591773 success.
- Workspace: T8 (cancel) cr2 is timing-marginal on the 2-core workspace: the third cancel lands 0.01 s before or
  after the end of the render; main's bridge and this branch both pass 4 of 4 on an idle workspace, and this branch
  failed 2 of 3 while other tests ran beside it. Not a change of behaviour; CI passes it.
- **Color calibration** (`channelmixerrgb illuminant_from_area`, `spot` in correction mode) ported from
  `_auto_set_illuminant`; C19: after `illuminant_from_area` the area's output of color calibration is neutral
  (0.18416 0.18419 0.18430, spread 6e-4). Its "measure" mode is not built (it reads the white balance through the
  interface, `_get_corrected_illuminant_xy`).
- 14:42 masks, mask display, colour calibration pushed (CI on it next).
- 14:44 **A pre-existing bug, fixed:** `dtb_instance_duplicate` (P1a) called `dt_masks_iop_use_same_as`
  (develop/masks/masks.c), which works on the global develop object; a duplicated instance lost its drawn mask in the
  session. The bridge now runs the same steps on the session's forms (`dtb__masks_use_same_as`); C22 checks it
  (render = darktable-cli of the written XMP, max 0).
- Workspace regression subset after the masks commits (T3 T9 X1 C3 C9 C11 C12 C22, three images): 94 PASS, 0 FAIL.
- 14:45 pushed 64a627fb (header comments; last code commit). p2b-real-engine's first attempt failed in "Build
  darktable for Linux" (package mirrors, three install attempts); failed jobs rerun.

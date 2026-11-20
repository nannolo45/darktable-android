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

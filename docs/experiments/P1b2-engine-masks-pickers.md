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

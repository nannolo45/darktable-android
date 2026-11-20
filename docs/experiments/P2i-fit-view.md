# P2i running log

Brief: `docs/briefs/P2i-fit-view-and-sync-switch.md`. Branch `p2i-fit-view`. Report: `P2i-fit-view-REPORT.md`.
Times UTC. Numbers from the build workspace are x86_64 on 2 cores; from CI, the x86_64 emulator of the runners. Not phone numbers.

## 2026-11-20

- Branch made from main 8bedc4c. Read CLAUDE.md, the brief, P2f report sections 2 and 3, P2h report (section 0 and
  open questions), `DarkroomController`, `ui/Photo.kt`, `AppHost`, `AppViewModel`, `Timings`, `LplAgent`, `WorkStore`,
  the emulator tests.
- Workflow triggers: branch added to `p2a-app.yml` and `p2b-real-engine.yml` (push branches only).
- The preview pipe's size limit, read in upstream at the pinned commit 04477d3 (the submodule is not checked out on
  this workspace; the file was read from the darktable repository at that commit): the bridge's `dtb_render_preview`
  runs on `DT_MIPMAP_F` and never scales up (`scale = min(1, max_size / processed size)`). `dt_mipmap_cache_init`
  (`src/common/mipmap_cache.c`) sizes `DT_MIPMAP_F` as mip 3, 1440 x 900, or mip 4, 1920 x 1200, when the
  preference `highres_preview_mip` is on; the bridge does not set it (default off). `_init_f` fits the sensor image
  into that box: a 3:2 sensor gives 1350 x 900, a 4:3 one 1200 x 900, before any crop. So the preview pipe cannot give
  more than about 1350 px on the longer side of an uncropped 3:2 photo; asking more returns that size.
- Phone-side cost per preview size, estimated from the brief's numbers (owner's phone, heavy edit, preview after an
  exposure change; the drag table gives 768 px 424 ms and 1024 px 877 ms, so the time grows about as pixels^1.25):
  1024 px 877 ms (measured); 1080 px about 1000 ms; 1152 px about 1180 ms; 1200 px about 1300 ms; 1350 px (the
  pipe's limit for 3:2) about 1750 ms. A first preview after opening costs about 1.4 times as much (1245 ms at 1024).
  Target chosen: a change at fit shown within about 1.2 s with that edit, so the preview at fit is the frame's
  longer side, at least 1024 px (never less than before), at most 1152 px. A phone's landscape frame (1080 px)
  gets 1080 px; a portrait photo on the phone (frame about 1400 to 1500 px high) gets 1152 px, scaled up by about
  1.25 on screen. Before P2i the sharp picture at fit came 5.8 s after a change (878 + 5781 ms in the brief).
- Code (not compiled here: no Android SDK on this workspace; `:lpl:test` and `:engine-api:test` did run locally,
  108 tests, 0 failures, including the new `P2iLplTest` 6/6):
  - `DarkroomController`: `previewSize()`/`previewSizeFor`, `requestRender`/`renderNow` render the region only when
    zoomed in, `renderRegion` reads the viewport on every try and marks "rendering…" (`regionPending`), zoom back
    to fit stops a region render in flight (`cancelRegionRender`, only that render) and drops the region; a frame
    of another size gets a preview of its size. `openLocked` always tries (7a). Timeline lines for thumbnail
    renders and stores. `regionRenders` counter for tests.
  - `ui/Photo.kt`: "rendering…" when zoomed in and the region renders.
  - `JniEngine`: `regionCalls`/`previewCalls` counters (tests).
  - `Timings`: a line for the preview at the darkroom's fit size after an exposure change; fit and 100 % region kept.
  - `LplAgent.writeIfHeld` (7b) under the record lock `remove` holds; `AppHost.thumbs` writes LPL thumbnails and
    pictures through it. `SyncSettings` + `syncAfterDone`, `syncPendingAutomatically`, `fetchAhead`, heartbeat
    message; `AppHost`, `SyncJob`, `AppViewModel`, `LplScreens` (switch), `Screens` (header and rows) use them.
- Tests: JVM `P2iFitViewTest` (darkroom), `P2iLplTest` (lpl); `DragAndBeforeTest`'s drag test now zooms in (the
  region is only rendered when zoomed in); `P2hUnreadableTest` and f8 expect the user's second open to try the file
  (7a changes that behaviour on purpose). Emulator: f10 (new), f4 and f8 changed, l4 keeps its timeline, l7 (new).
  Screenshot `list-*-05-auto-sync-off`.

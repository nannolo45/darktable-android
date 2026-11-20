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
- Run 44701978982 (p2a, 735be90): green; `P2iFitViewTest` 7/7 (three changes at fit: preview shown 405, 393,
  412 ms after the change, its render 399, 389, 399 ms; stand-in region 3 s). Run 44701978994 (p2b, 735be90): green,
  emulator 44/44. f10 measured at the emulator's own 304 x 250 frame (preview 1024 px): preview shown 424, 236, 239 ms
  after a change at fit (render 398, 214, 218 ms), each module once, no region, no `render_region`. Zoom in: region
  338 ms; back to fit: region render cancelled, nothing pending after 10 ms. The cancelled region was retried once by
  `wanted` (it returned nothing, but the timeline said "a cancel not meant for it"): fixed in 710b957.
- 710b957: f10 on a 1080 x 1500 frame. p2a 44704862454 green; p2b 44704862471: f10 failed ("timed out: preview of the
  heavy edit at the frame's size"): the frame changed while a preview render (asked at 1024 px) was in flight and the
  delayed check skipped because a render was running. Fixed in 010cb15 (wait for the render, then compare). The same
  run's l4 timeline: `+917 ms lpl: 51/mire1.cr2 removed from the phone (folder deleted)`, then `+1012 ms thumbnail
  pass: 51/mire1.cr2 render start`: the background thumbnail pass renders the released photo after its removal
  (darktable opened the deleted file). The test ended before that render was stored. l4 now waits for it (010cb15).
- 010cb15: p2a 44707660011 failed on a Maven 429 (robolectric jar), green on rerun; p2b 44707660180: apk job failed
  on Maven ("could not find kotlin-stdlib"), rerun.
- 010cb15, last code commit: p2a 44707660011 green on attempt 2; p2b 44707660180 green on attempt 3 (two apk-job
  Maven failures). Emulator 44/44. f10 at 1080 x 1500: preview shown 458, 281, 243 ms after a change at fit (render
  425, 260, 222 ms, 1080 px), no region, no `render_region`; zoom in: region 2380 ms; back to fit: cancelled, 91 ms.
  l4: 51's thumbnail stored before the removal this time (no late render). APK release `dt-android-010cb158501d`.
- Report written: `P2i-fit-view-REPORT.md`.

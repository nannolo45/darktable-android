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

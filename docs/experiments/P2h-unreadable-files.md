# P2h running log

Brief: `docs/briefs/P2h-unreadable-files.md`. Branch `p2h-unreadable-files`. Report: `P2h-unreadable-files-REPORT.md`.
Times UTC. Numbers from the build workspace are x86_64 on 2 cores; from CI, the x86_64 emulator of the runners. Not phone numbers.

## 2026-11-20

- Branch made from main 70129eb. Read CLAUDE.md, the brief, P2f report sections 0 and 4, DarkroomController
  (`hasPicture`, `openLocked`, `renderThumbnails`, `withPhotoSession`), `AppHost.previewPicture`, the bridge's
  `dtb_image_info` and `_pipe_ensure`.
- Reading (not run): `dtb_image_info` builds the full pipe first; `_pipe_ensure` returns DTB_E_ENGINE "image could not
  be loaded (mipmap N)" when the mipmap buffer is empty. The P2f report says 0 x 0 for the junk file; the P2f code only
  handled 0 x 0. Which one the emulator gives is deliverable 1: an emulator test records it.
- Workflow triggers: branch added to `p2a-app.yml` and `p2b-real-engine.yml` (push branches only).

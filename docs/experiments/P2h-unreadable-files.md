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
- d25037b: the change. `DarkroomController.pictureOrClose` (replaces `hasPicture`): after `open`, a picture of 0 x 0
  or any engine error from `image_info` except CANCELLED means no picture; the session is closed there and then, the
  file is remembered with its size and modification time. Used by `openLocked`, `renderThumbnails`,
  `withPhotoSession` (before its block runs) and the stand-in thumbnail loop of `start`. `knownUnreadable` skips a
  remembered file without opening it, and forgets it when its size or modification time changed. `openLocked` also
  closes the session on any engine error now (no photo shows as open, so no session stays open).
  `AppHost.previewPicture` maps an `image_info` error (not CANCELLED) to the same sentence.
  JVM: `P2hUnreadableTest` (stand-in engine wrapped to answer 0 x 0, an error, or CANCELLED once). Emulator: `f7`
  (records what open and image_info answer for the junk file, with the engine log) and `f8` (darkroom, LPL preview,
  good photo) in `P2fScenarioTest`, so the CI script's class list is unchanged and f9 still runs last.
- The build workspace has no Android SDK (`SDK location not found`) and Maven Central answered 429 to the first
  download; the JVM tests run in CI only for this package.
- p2a run 44691789989 failed at compile: Kotlin refuses `in` on a ConcurrentHashMap in the test. Fixed in f210e04.
- 44693487694 (a64282a), emulator: 42/42 passed. **Deliverable 1: `open` OK, then `image_info` fails with ENGINE (-9)
  "image could not be loaded (mipmap 12)"**, not 0 x 0 as the P2f report says. f8 passed: sentence after 17 ms, LPL
  preview of the good photo 1920x1278 right after, good photo preview shown. Each junk file decoded once in the whole
  run. Evidence copied to `P2h-results/emulator-44693487694.txt`. (Its compare job was cancelled by the next push.)
- 51ef5a0: timeline text only (the error's name appeared twice: "ENGINE: ENGINE: ..."). Last code commit.
- Report written; waiting for both workflows on 51ef5a0.
- 51ef5a0: p2a 44695287334 green. p2b 44695287219 attempt 1: 41/42, l4 "released: gone from the phone" failed (jobs
  all done; a thumbnail written after the release can make the folder again, `AtomicFiles.write` mkdirs; reported,
  not fixed). Emulator job rerun (attempt 2) with no change: 42/42, compare green. Release dt-android-51ef5a052fb9.
- Report finished. Evidence in `P2h-results/emulator-44695287219.txt`.

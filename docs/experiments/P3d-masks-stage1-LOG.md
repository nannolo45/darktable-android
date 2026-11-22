# P3d stage 1: running log

- Read CLAUDE.md, engine-api.md 6.5 to 6.8, the mockup (copied to docs/design/mockups/P3d-Masks.html), the crop tool's
  and colour picker's touch handling (Photo.kt). The darktable manual pages could not be fetched from the build
  workspace (the web fetch needed an approval nobody gave); the screens follow the engine API, the mockup and the
  brief; see the report's open questions.
- No engine change: every call the screens need exists (blend_get/set, masks, mask_create/attach/detach/member_set,
  mask_group_set, mask_rename/delete, coords_transform, render options show_mask).
- Run 45030989679: first push, cancelled by the next push.
- Run 45031137615: compile error (missing import of the `key` extension in MasksPanel.kt).
- Run 45031803696: JVM tests: 2 of 8 P3dMasksTest cases timed out with the tab's module back to none; Robolectric
  "MavenArtifactFetcher" errors in the p2a screenshot job (infra). The p2b-apk job's screenshots were fine.
  First guess (an older reload overwriting the choice) fixed in e640fdd0 but run 45032898868 failed the same way.
- Cause: `controller.masks` was created lazily without a lock; opening the tab launched a reload on another thread,
  so two instances existed and the test's choice landed in the one not kept. Fixed in 698d13f2 (synchronized lazy).
- Screenshots looked at (run 45031803747): the modern skin's selected button colour equals its button colour, so
  the chosen mode did not show; it now has a ring in the accent colour (the mockup's ring). Brush strokes showed
  beads where their pieces overlapped; each stroke is now laid over as one layer.
- Run 45033767124 (emulator): d1 and d2 failed: blend_get said "other" after a shape joined a mask switched to
  drawn (DRAWN | FLEXI). Fixed in b3271d71/0cadfced (the app reads it as drawn; the stand-in's attach now does as
  the bridge). The same run showed a finished shape as two history items (mask_manager, exposure).
- Run 45036342736 (p2a): green (P3dMasksTest 8/8, PhoneMasksScreenshots 7/7, TabletMasksScreenshots 1/1).
- Run 45036342754 (p2b): the apk job's JVM step failed to fetch Robolectric's native runtime (infra) in the run and
  in its rerun; the next push (this log and the report) starts it again.
- Run 45036342754 (third attempt): emulator d1 failed its show-mask check (share of yellowish pixels: the test
  chart already has 8.7 %); d2 passed, its export matched darktable-cli (PSNR 45.05). The check now looks at a
  point on the shape (yellow) and one away from it (grey): 896f89f8.
- Runs 45042969844 (p2a) and 45042969878 (p2b): green on 896f89f8, emulator OK (75 tests), compare ok.

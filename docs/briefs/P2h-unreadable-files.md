# P2h: a file darktable cannot read must end cleanly in the app

Small follow-up to P2f, from its review (20 Nov 2026). App side only: **no change under `engine/` or `upstream/`**.
Branch `p2h-unreadable-files` from `main`. Report: `docs/experiments/P2h-unreadable-files-REPORT.md`; running log
`docs/experiments/P2h-unreadable-files.md` (push it at least every 30 minutes, with whatever work exists).

## Background
P2f made the app ask `image_info` after `open` and refuse to render when the picture is 0 x 0
(`DarkroomController.hasPicture`, `AppHost.previewPicture`), because a render of an undecodable file hung the engine
thread (P2f report, section 0). Reading the bridge, `dtb_image_info` first loads the full image
(`engine/src/session.c` → `dtb__full_pipe()` → `_pipe_ensure(..., DT_MIPMAP_FULL)` in `engine/src/render.c`), which
returns `DTB_E_ENGINE` "image could not be loaded" when there is no picture. So the likely result for such a file is an
engine **error**, not 0 x 0. This was read, not run. If so, today:
- `openLocked`: the exception leaves `hasPicture` before the session is closed; the message is the engine's text, not
  the app's sentence; the session stays open until the next photo is opened, and background work that needs its own
  session (`withPhotoSession`, `renderThumbnails`) fails meanwhile with a state error.
- The file is not added to `unreadable`, so every thumbnail pass opens it again.
- No test opens such a file in the darkroom.

## Deliverables
1. **Establish the fact first**: what `image_info` answers on the emulator for the junk file (error and its text, or
   0 x 0), written in the report with the log lines.
2. **One behaviour for both answers**: after `open`, a picture of 0 x 0 **or** an engine error from `image_info`
   (anything but CANCELLED) means "no picture": the session is closed at once, the file is remembered as unreadable for
   this process (thumbnail pass and LightPhotoLibrary preview skip it without opening it), and the darkroom says
   "darktable found no picture in this file (not a RAW it can read, or a broken download)". The same in
   `previewPicture` and `renderThumbnails`. Never a render on such a session.
3. A file remembered as unreadable is tried again when the file itself changed (size or modification time), so a
   repaired download works without restarting the app.
4. **Tests.** JVM (fake engine): both answers (0 x 0, and an error from `imageInfo`) give the sentence, a closed
   session, and no second `open` of that file by the thumbnail pass. Emulator: open the junk file in the darkroom →
   the sentence, no hang; then open the good test photo → preview shown; then the LightPhotoLibrary preview of the
   good photo is made (background sessions work again). Keep `HangGuard` on the new tests.
5. Workflows: add the branch to the triggers of `p2a-app.yml` and `p2b-real-engine.yml`, nothing else. Both green on
   the last code commit; APK from the usual release step.

## Not in this package
Engine item E1 (`dtb_open` refusing such files), the per-module timings call, anything in the interface beyond the
one sentence.

## Rules (as always)
`CLAUDE.md`. Nothing personal in the repository (no names of people, phone or camera models, layout names, addresses,
private links). SPDX line on new files. Do not weaken a test. Never push to `main`, no pull requests: push the branch
and write the pull request text into the report. Report shortfalls first, plainly. Maven Central is flaky in CI: rerun
before suspecting the change.

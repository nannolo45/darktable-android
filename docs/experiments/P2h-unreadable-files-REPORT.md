# P2h: a file darktable cannot read ends cleanly in the app — pull request description

Branch `p2h-unreadable-files` into `main`. Brief: `docs/briefs/P2h-unreadable-files.md`. Running log:
`docs/experiments/P2h-unreadable-files.md`. Results kept under `docs/experiments/P2h-results/`. App side only: nothing
under `engine/` or `upstream/` changed.

## 0. Failures, limits and corrections first

- **The P2f report was wrong about the answer, and the brief's reading was right.** P2f section 0 says `image_info`
  reports 0 x 0 for the junk file. On the emulator it answers an **engine error**: `open` returns OK, then
  `image_info` fails with `DTB_E_ENGINE` (-9) "image could not be loaded (mipmap 12)" (section 1). So the P2f guard
  (`hasPicture`, 0 x 0 only) never matched that file: the exception left `hasPicture` before the session was closed,
  the darkroom showed the engine's text, the file was not remembered. The P2f runs passed because the exception still
  stopped the render. This package handles both answers the same way.
- **No local JVM run.** The build workspace has no Android SDK (`SDK location not found`) and Maven Central answered
  429 to the first downloads; the JVM tests and the rule check ran in CI only (both workflows, section 3).
- **Two CI runs needed a rerun for Maven Central** ("could not find" / robolectric fetch): 44692381418 and
  44693487742, both green on rerun with no change.
- **What is not shown.** Only the one junk file (2000 bytes of junk) was tried on the real engine. A truncated real
  RAW may answer differently (for instance 0 x 0, or an error from `open` itself); the app handles 0 x 0, an
  `image_info` error and an `open` error, but only the second was seen on the real engine. The engine fix (E1, the
  bridge refusing such files at `open`) is still open and not part of this package.
- The emulator test for the LightPhotoLibrary preview calls the editor port (`AppHost.Editor.renderPreview`) the agent
  uses, not the whole sync against the stub server; l1 to l6 (the stub-server scenarios) still pass and include
  `OTHER_0023.CR2` as before.
- The new emulator tests are `f7` and `f8` in `P2fScenarioTest`, not a new class: the CI script
  (`tools/app/emulator-p2b.sh`) lists the classes to run, and the brief allows changes to the workflows' triggers
  only. `HangGuard` (12 minutes) covers them as the rest of that class, and `f9` (no overlapping bridge calls) still
  runs last and so covers them too.
- All timings are from the x86_64 emulator of the CI runners (4 cores). Not phone numbers.

## 1. Deliverable 1: what `image_info` answers for the junk file

Emulator test `f7_imageInfoOfAnUndecodableFile` (run 44693487694, commit a64282a): the same bytes as l1's placeholder
`OTHER_0023.CR2` (2000 bytes, `ByteArray(2000) { it.toByte() }`), opened with no XMP, `image_info` asked, closed; no
render. Recorded in `P2h-results/emulator-44693487694.txt`:

```
open answered OK (20 ms); image_info answered error ENGINE (-9): ENGINE: image could not be loaded (mipmap 12) (0 ms)

   194.4064 [exiv2 dt_exif_read] /data/user/0/app.dtandroid/files/p2h/JUNK_F7.CR2: ...: The file contains data of an unknown image type
   194.4156 [exiv2 dt_exif_read] ...JUNK_F7.CR2: The file contains data of an unknown image type
   194.4159 [rawspeed] JUNK_F7.CR2 corrupt: virtual std::unique_ptr<RawDecoder> rawspeed::RawParser::getDecoder(const CameraMetaData *), line 97: No decoder found. Sorry.
   194.4162 [exiv2 dt_exif_read] ...JUNK_F7.CR2: The file contains data of an unknown image type
   194.4173 [dt_dev_load_raw] loading the image. took 0.007 secs (0.015 CPU)
```

and in logcat (`logcat-app.txt` of the run):

```
11-20 07:27:00.841  3739  7004 I dt-android-test: P2h f7: open answered OK (20 ms); image_info answered error ENGINE (-9): ENGINE: image could not be loaded (mipmap 12) (0 ms)
```

That is `_pipe_ensure` in `engine/src/render.c` failing for the full mipmap (`!p->buf.buf || !p->buf.width ||
!p->buf.height`), reached from `dtb_image_info` → `dtb__full_pipe()`, as the brief read it. The P2f hang came from the
*preview* pipe of the same kind of session (`dtb_render_preview`), whose mipmap was not empty (rawprepare logged
"size=0x0" crop errors and the render never returned).

## 2. Brief item by item

| # | Item | State |
|---|------|-------|
| 1 | Establish what `image_info` answers on the emulator, with log lines | **done**: an engine error, section 1 |
| 2 | One behaviour for 0 x 0 and an `image_info` error (not CANCELLED): session closed at once, file remembered, the sentence, the same in `previewPicture` and `renderThumbnails`, never a render | **done** |
| 3 | A remembered file is tried again when its size or modification time changed | **done** |
| 4 | Tests: JVM for both answers; emulator junk file → sentence, no hang → good photo preview → LPL preview of the good photo; `HangGuard` on the new tests | **done** (emulator LPL preview through the editor port, section 0) |
| 5 | Branch in the triggers of `p2a-app.yml` and `p2b-real-engine.yml`, nothing else; both green on the last code commit; APK | **done**: section 3 |

What changed (`app/darkroom/.../DarkroomController.kt`, `app/app-engine/.../AppHost.kt`):

- `pictureOrClose(e, rawPath, stamp)` replaces `hasPicture`. After `open`: `image_info` answers a picture larger than
  0 x 0 → that `ImageInfo` is returned (and used, no second `image_info`). 0 x 0, or any `EngineException` except
  CANCELLED → the session is closed there and then, the file is remembered with the size and modification time it had
  just before the `open`, a timeline line names the answer, and null is returned. CANCELLED is passed on (the
  background task runs again later; the caller closes the session).
- Used in every place that opens a photo: `openLocked` (darkroom), `renderThumbnails` (background thumbnail pass),
  `withPhotoSession` (the LightPhotoLibrary preview's own session, checked **before** its block runs, so the block can
  never render such a session) and the stand-in engine's thumbnail loop in `start`.
- `knownUnreadable(rawPath)`: a remembered file whose size and modification time are unchanged is skipped **without
  opening it** by all four; one that changed is forgotten and opened again (deliverable 3). For this process only, as
  before.
- The darkroom says `cannot open <name>: darktable found no picture in this file (not a RAW it can read, or a broken
  download)`, also when the file is skipped without opening. P2f showed `cannot open <name>: IO: darktable found …`
  (`EngineException` puts the error's name first); `isNoPicture` recognises the app's answer and the name is left out.
- `openLocked` now closes the session on any engine error: the state says no photo is open, so no session stays open
  that would refuse background work with a state error until the next photo is opened.
- `withPhotoSession` answers a file without a picture with `EngineException(IO, NO_PICTURE)` (not null, which means
  "another photo is open, try later"). `AppHost.Editor.renderPreview` turns it into `false`, as other engine errors.
- `AppHost.previewPicture` (second guard, also called directly by test f2): an `image_info` error other than
  CANCELLED becomes the same no-picture error, as 0 x 0 already did; it never renders then.

## 3. How to reproduce, and results

JVM tests and rule check (as CI runs them; needs an Android SDK with platform 36 and build-tools 35.0.0):

```
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :schema:test :lpl:test :darkroom:testDebugUnitTest :checkScreenRules
```

Emulator: the `emulator` job of `p2b-real-engine.yml` (`tools/app/emulator-p2b.sh`), which runs `P2fScenarioTest`
with the others.

**JVM** (`app/darkroom/src/test/kotlin/app/dtandroid/darkroom/P2hUnreadableTest.kt`, 7 tests): the stand-in engine
wrapped so that one file answers `image_info` with 0 x 0, with the bridge's error ("image could not be loaded (mipmap
6)"), or with CANCELLED once; it counts opens per file and renders of a session without a picture.
- `pictureOf0x0InTheDarkroom`, `errorFromImageInfoInTheDarkroom`: the sentence, no photo open, session closed, a
  background session on another photo works right after, two thumbnail passes and the preview session do not open the
  file again (1 open in all), opened again from the filmstrip: the sentence without an open; 0 renders.
- `pictureOf0x0InTheThumbnailPass`, `errorFromImageInfoInTheThumbnailPass`: the thumbnail pass meets it first: one
  open, closed; the next pass, the preview session and the darkroom do not open it again.
- `previewSessionMeetsTheFileFirst` (both answers): closed before the preview's work could render it; not reopened.
- `aRepairedFileIsTriedAgain`: after a size change, and after a modification-time-only change, the file is opened
  again, gets its thumbnail and opens in the darkroom.
- `aCancelledImageInfoIsNotNoPicture`: CANCELLED is not "no picture": the work runs again, the file is not remembered.

Result: 7 of 7 passed in p2a run 44693487742 (after a rerun for a robolectric download) and in the apk job of
p2b run 44693487694.

**Emulator** (run 44693487694, commit a64282a; `P2h-results/emulator-44693487694.txt`): 42 of 42 tests passed,
instrumentation 210.5 s.
- f7: section 1.
- f8 (`f8_undecodableFileInTheDarkroomThenTheGoodPhoto`): the darkroom opened the junk file (a fresh name) and showed
  `cannot open JUNK_F8.CR2: darktable found no picture in this file (not a RAW it can read, or a broken download)`
  17 ms after the open was asked; the timeline line `JUNK_F8.CR2 has no picture (image_info answered ...
  image could not be loaded (mipmap 12)), session closed, not rendered` came at +15 ms. Right after, with no photo
  open, the LightPhotoLibrary preview of the good photo: 1920 x 1278, 638658 bytes, 1083 ms. The junk file's own
  preview: refused without a session ("known to have no picture, no session opened"). Then the good photo in the
  darkroom: preview shown (1024 x 681) 376 ms after it was asked. The junk file again from the filmstrip: the
  sentence, "not opened". Exactly one "has no picture" line for that file.
- Over the whole run darktable decoded each undecodable file once: `OTHER_0023.CR2` (l1's placeholder, met by the
  background work) 1, `JUNK_F7.CR2` 1, `JUNK_F8.CR2` 1 ("No decoder found" lines of darktable's own log).
- f9: 0 overlapping bridge calls.

**Last code commit** 51ef5a0 (only the timeline text: the error's name was written twice). Runs on it:
RUNS_PLACEHOLDER

## 4. Surprises in upstream

None new. For the record (read, not changed): the full mipmap of an undecodable file is empty, so `_pipe_ensure`
refuses it and `image_info` fails; the preview mipmap of the same session is not empty (P2f: rawprepare ran with
"size=0x0" crop errors and `dtb_render_preview` never returned). Why the two sizes differ was not looked into (the
`upstream/` submodule is not checked out on this workspace); it belongs to E1.

## 5. Open questions for the reviewer

1. E1 (bridge): with this answer known, `dtb_open` could load the full mipmap (or check `load_status`) and refuse the
   file with an error; the app would then never hold such a session. Until then the app's guard costs one full-image
   load per open, which `image_info` already did before P2h.
2. The remembered files are per process. Should they survive a restart (for instance in the thumbnail store, keyed by
   size and modification time)? Today a restart costs one `open` + `image_info` per such file, about 20 ms on the
   emulator, and no render.
3. LightPhotoLibrary keeps the preview of such a photo pending and asks again at each sync; each ask is now refused
   without opening the file. Should the agent mark the photo (for instance "no picture") instead?

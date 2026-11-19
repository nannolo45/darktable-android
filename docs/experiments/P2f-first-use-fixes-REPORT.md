# P2f: fixes after the first real use — pull request description (DRAFT, results pending)

Branch `p2f-first-use-fixes` into `main` (it contains `p2g-engine-start`). Brief: `docs/briefs/P2f-first-use-fixes.md`
with its addendum, and the reviewer's additions of 19 Nov (copied to the top of the running log
`docs/experiments/P2f-first-use-fixes.md`, which has the whole history). Results kept under
`docs/experiments/P2f-results/`.

## 0. Failures, limits and the hang first

**The emulator hang (runs 44596781567, 44616800258, 44623988726) and the native heap corruption.**
- What happened: P2f added a background pass that renders thumbnails of every photo the phone holds that has none (or
  an outdated one). In the emulator tests one such photo is l1's placeholder `OTHER_0023.CR2`, 2000 bytes of junk on
  purpose. darktable opened it without an error (`dtb_open` returned OK), logged "No decoder found", "invalid crop
  parameters ... for size 0x0" and `load_status=3`, and the render of that session (`dtb_render_preview`) never
  returned: the engine thread stayed inside it, the darkroom's lock stayed with the background task, and every later
  test waited (run 44623988726; the dump with the engine thread's stack and the log lines are in
  `P2f-results/hang-44623988726.txt`). In run 44616800258 the same sequence ended with "Scudo ERROR: corrupted chunk
  header" (native heap corruption, detected while freeing an unrelated buffer), and in 44596781567 (no time limits
  yet) the job ran for 105 minutes. Before P2f the app never rendered a photo the user had not opened, so it never met
  this file.
- What it means for the phone: a broken or partial download, or a file darktable cannot read, would freeze the engine
  (and may damage the native heap) as soon as anything renders it. That is a defect in the engine (section 7, E1)
  which P2f's background pass exposed.
- What the app does now (368b272): after `open`, `image_info` must report a picture larger than 0 x 0, otherwise the
  photo is never rendered: the darkroom says "darktable found no picture in this file (not a RAW it can read, or a
  broken download)", the thumbnail pass and the LightPhotoLibrary preview skip it and remember it.
  {{RERUN}}
- **What is shown and what is not.** Shown: where the engine stopped (inside `renderPreview` of the undecodable file's
  session), the scheduler's state, the log lines that identify the file. Not shown: that the heap corruption comes
  from that render (it followed the same sequence in the one run where it occurred, and section 4 finds no other P2f
  change that touches native memory or the bridge's threading rule), and not shown that `image_info` reports 0 x 0 for
  every undecodable file (it does for this one). The engine fix E1 is what closes it; until then the check above is the
  app's guard.

**Other limits.**
- Nothing ran on a phone or on arm64. All timings are from the x86_64 emulator of the CI runners (4 cores) or the
  build workspace (2 cores); they are not phone numbers.
- LightPhotoLibrary's side of R13 to R16 was not run against a real server: the client follows the shapes the reviewer
  sent and was tested against the stub server, both as a server that has them and as an older one (404 or fields left
  out).
- Deliverable 4's speed options change the picture or need the bridge: proposals only, nothing changed (section 3).
- Crop: a typed custom ratio ("x:y") is not done. The list, the flip, margins keeping the ratio and the XMP round trip
  are. A ratio typed on desktop shows as desktop shows it ("7:3 2.33").
- The phone top bar at 360 dp shows a 14-character name whole; a longer one is shortened in the middle and keeps its
  first letters and last four characters, not necessarily the whole number before the extension (screenshot).

## 1. Brief item by item

| # | Item | State | Where |
|---|---|---|---|
| 1 | Cause of the black picture and the wait, with evidence, before any fix | **done**: timeline before the fix (run 44588278381): open waited 10.2 s behind the LightPhotoLibrary export, first picture 10.8 s after the tap, nothing on screen before. The list's black thumbnail: `FolderThumb.load` dropped any thumbnail older than the XMP, which a merged XMP from LightPhotoLibrary makes true | `P2f-results/f1-before-44588278381.txt`, `P2fScenarioTest.f1` |
| 2 | Interactive work first; background renders cancelled or paused and picked up again; never while dragging; say how the engine thread orders work | **done** (order in section 2) | `DarkroomController.fg/bg`, `AppHost.Editor.renderPreview`, `P2fDarkroomTest` |
| 3 | Never black: best picture already there with a quiet progress line; thumbnails for every photo ever rendered | **done** | `DarkroomState.placeholder/working`, `Photo.kt` (`WorkingNote`), `ThumbnailStore.storePicture`, `FolderThumb` |
| 4 | Per-module table for the open photo's edit (preview and drag size), the heavy edit measured on CI, options with numbers; addendum: fit region, `modules()` calls, drag size screenshots, pinning, history and modules in the text; reviewer: timings on any photo source | **done** (proposals only for anything that changes the picture) | `Timings.kt`, `ModuleTimes.kt`, `AppViewModel.runTimings`, `f3`, `f4`, section 3 |
| 5 | List screen and darkroom top bar designed; messages correct in number; screenshot tests | **done** | `ui/PhotoList.kt`, `Screens.kt`, `DarkroomScreen.kt`, `ListScreenshotTest.kt` |
| 6 | Sync progress (R16, app side) in the list, the darkroom and the heartbeat; reviewer: jobs from LightPhotoLibrary visible and correct | **done** | `LplAgent` (`steps`, `notes`, `runSyncOrRelease`), `Model.kt`, `P2fLplTest` |
| 7 | Edit record (R15, app side): `agent` on XMP posts, last edit in the list and the darkroom, absent without R15; `schema/module-names.json` from the schema by a script | **done** | `LastEdit.kt`, `LplClient`, `tools/schema-module-names.py`, `schema/module-names.json` |
| 8 | Spektrafilm: where it appears, its data packaged, a render test against darktable-cli | **done** {{SF}} | `SpektrafilmPack.kt`, `app-engine/build.gradle.kts`, workflow, `f5`, `P2fDarkroomTest` |
| 9 | Tests (JVM and emulator), workflow green, APK, owner instructions | {{CI}} | section 5 and 8 |
| 10 | Crop's aspect list (reviewer): darktable's entries and order, ratio and frame as desktop, flip, margins keep the ratio, XMP both ways, through an override; list of other unbound controls | **done** except the typed ratio | `schema/overrides/crop.json`, `CropAspect.kt`, `CropAspectTest`, `f6`, `P2f-results/unbound-controls-scene-referred.txt` |
| + | Reviewer: R13 (jobs run once), R14 (password check) | **done** | `RanJobs`, `LplClient.check` |

## 2. What changed, in short

**How the engine thread orders work now (item 2).** All bridge calls still run one at a time on the one engine thread
(`JniEngine`, unchanged rule). Above it, the darkroom has two kinds of work:
1. Interactive work (open a photo, a change, the drag picture, the preview and the visible region, before, export,
   writing the XMP) runs through `fg`, in the order asked, under the darkroom's lock.
2. Background work (the LightPhotoLibrary preview, thumbnails of photos not on screen) runs through `bg`, only when no
   interactive work waits or runs, no finger is down, and the darkroom has been quiet for 300 ms.
3. Interactive work that arrives while background work runs cancels the background task's render (only a render the
   background task marked as its own, and only while it runs), takes the lock first, and the background task is started
   again from the beginning once the darkroom is quiet. A background call that cannot be cancelled (an open, the RAW
   load) is waited for. The LightPhotoLibrary preview is now rendered through the bridge's full pipe (darktable's export
   pipe type, `render_region` over the whole photo at 1920 px) and encoded with Android's JPEG encoder, because
   `dtb_export` cannot be cancelled. Test f2 compares the two: {{F2}}.
4. Thumbnails are no longer rendered before the photo the user asked for; a photo without a thumbnail gets one in the
   background when no photo is open (the interface preview on the stand-in engine, which keeps none, still renders its
   generated pictures first).

**Never black (item 3).** While a photo opens: its last picture (kept at 1024 px when it closes) or its thumbnail, scaled
to the frame, with a line over the bottom of the photo in plain words ("getting NAME…", "downloading NAME… 40 %" with a
thin bar, "opening NAME…", "rendering…"), gone when the preview is there. Thumbnails older than the XMP are shown and
rendered again in the background.

**After the fix (f1, emulator, same heavy edit):** {{F1AFTER}}

## 3. Lag with a real edit (item 4 and the addendum)

Measured on the CI emulator (x86_64, 4 cores, not a phone) with the heavy edit of f1 on the public test RAW
`mire1.cr2` (3908 x 2602, Bayer; denoise (profiled), diffuse or sharpen, local contrast and sharpen switched on with
their defaults), run 44634077994, the full text in `P2f-results/timings-heavy-edit-emulator-44634077994.txt`:

| Step | ms | Where the time goes (per module, slowest first) |
|---|---|---|
| first preview 1024 px, every module | 680 | denoise (profiled) 183, diffuse 169, local contrast 62, color calibration 54 |
| preview after an exposure change | 373 | diffuse 98, color balance rgb 98, local contrast 68, color calibration 41 |
| drag picture 384 px after an exposure step | 62 (median of 6) | demosaic 35, highlights 27, denoise 27 |
| sharp region at fit 1080 x 719 after a change | 663 | denoise 181, **demosaic 176**, diffuse 85, local contrast 52, highlights 49, raw black/white point 22, white balance 18 |
| sharp region at 100 % 1080 x 1500 | 2410 | denoise 1044, diffuse 661, local contrast 232 |
| `modules()` (bridge / JNI string / parse) | 10.2 / 0.5 / 8.2 | |
| export full size | 9635 | |

**The sharp picture at fit (the addendum's 1.3 s on the phone).** Confirmed from upstream and the table: outside
thumbnails demosaic always works at full resolution (`src/iop/demosaic.c`, `_demosaic_full()`; `modify_roi_in` asks
for scale 1.0), so at fit the whole sensor goes through raw black/white point, white balance, highlight reconstruction
and demosaic before anything is scaled down: 265 ms of the 663 here on a 10 MP Bayer file; on the phone's 24 MP X-Trans
file Markesteijn demosaic at full size is the bulk of 1295 ms, against 167 ms for the 1024 px preview from the
downscaled input. Desktop darkroom does the same at fit (its main pipe at fit is this pipe; it shows its small preview
first and the full pipe's picture when ready), so the app behaves as desktop, only on a slower machine. Options, none
applied (each changes the picture or needs the bridge):
- A. At fit on a phone, show the preview pipe's picture at the view's size (about 1080 px: about the 1024 px preview's
  cost, 167 ms on the phone instead of 1295) and render the sharp region only when zoomed in. Saves about 1.1 s per
  change on the phone. Changes the picture at fit: the preview pipe works from darktable's downscaled input, so denoise,
  sharpen and diffuse act on downscaled data there (desktop shows exactly this picture first).
- B. Keep the sharp region but start it only after the photo has been still for about 0.6 s (and cancel it on any
  change, as now). No change to the picture; the phone still spends 1.3 s each time, later.
- C. (bridge, E3) a demosaic shortcut for renders at small scales, as darktable's own thumbnail pipes have
  (`_demosaic_full` returns false for them). Changes the picture at fit.

**`modules()` calls.** None during a drag and one when the finger lifts (JVM test
`modulesAreReadOnceAfterADragAndNeverDuringIt`); one after each other change (`refreshLocked`). 18.9 ms per call on the
emulator, about 28 ms on the phone; nothing to remove without the bridge returning only what changed. `image_info`
after every change costs 0.5 ms once loaded.

**The drag picture's size.** The app chooses it from measured times for a 50 ms target (320 px on the owner's phone,
384 px on the emulator). How 320, 512, 768 and 1024 px look on a 1080 px wide screen: {{F3}}. Per the phone's own table,
512 px costs about 45 ms and 768 px about 97 ms after an exposure step: a 512 px drag picture would follow at about
20 updates a second, 768 px at about 10.

**The per-module table on the phone** shows the same lines for the owner's own edit; with the pinning line from P2g, the
photo's source, its history items and the modules on (all in the text he shares).

## 4. The bridge's threading promise and what P2f does (the reviewer's point 3)

**What the contract says.** `engine/include/dtbridge.h`: one engine, at most one open image (session); "every function
except dtb_render_cancel() must be called from the same thread, the engine thread. dtb_render_cancel() may be called
from any thread while a render runs on the engine thread", and "must not be called concurrently with dtb_close()".
`docs/design/engine-api.md` section 1: "One engine thread owns the session. Every call except `render_cancel` runs on
it". Nothing allows two sessions or calls from two threads.

**What could run concurrently in P2f that could not before, and the answer for each.**
- Background sessions next to the darkroom's: no second session ever exists. A background task opens its own session only
  under the darkroom's lock and only while the darkroom has no photo open (`withPhotoSession`, `renderThumbnails`), and
  closes it before releasing the lock; `dtb_open` itself refuses a second session (`DTB_E_STATE`).
- All bridge calls: still queued to the one engine thread by `JniEngine.onEngine`; the queue runs one task at a time. P2f
  added a counter of overlapping bridge calls (`JniEngine.bridgeOverlaps`), and test f9 asserts it stayed 0 over the
  whole emulator run: {{F9}}.
- Cancel from other threads: P2f made it frequent (interactive work stopping background renders). P2b already cancelled
  whenever a change arrived, also when no render ran, which is outside the header's "while a render runs". Now
  `JniEngine.renderCancel` passes the cancel on only while a render runs on the engine thread, and never at the same
  time as open, close, cache drop or shutdown (one lock); cancels asked outside a render are counted, not passed on
  ({{F9C}} in the emulator run). In the bridge's code the cancel only writes the embedded pipe's `shutdown` field
  (`render.c`, `dt_dev_pixelpipe_set_shutdown`), so even before this it could not free or write freed memory; it is now
  within the letter of the header anyway.
- Sessions closed right after a render: on the engine thread, after the render returned; ordered by the queue.
- Per-module timings: `-d perf` makes darktable print one line per module from the thread that runs the pipe; the app
  reads the JNI layer's log ring (`NativeBridge.logSince`, its own mutex, not a bridge call). No engine state is touched.
- Thumbnail and preview queue: the trigger of the hang (section 0), by giving the engine a file it cannot decode, not by
  calling it from two threads.

**So:** the app stays inside the bridge's promise (one thread for all calls, one session, cancel only during a render and
never alongside close), shown by f9 on the emulator. The hang and, by all signs, the corruption come from the engine
accepting an undecodable file and then hanging in its render (E1); the app avoids that pattern by not rendering a
session whose picture is 0 x 0.

## 5. Tests and how to reproduce

```
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :schema:test :lpl:test :darkroom:testDebugUnitTest :checkScreenRules
# screenshots (Robolectric; CI): $GRADLE --no-daemon :darkroom:testDebugUnitTest   -> darkroom/build/outputs/screenshots
python3 -I tools/schema-module-names.py --check
python3 -I tools/schema-unbound-controls.py
# emulator (as CI): tools/app/emulator-p2b.sh <x86_64 apk> <test apk> <mire1.cr2> <exposure.xmp> <out> <hlrecovery.arw> <spektrafilm.xmp>
```

New tests: JVM `EnginePinningTest` (P2g), `ModuleTimesTest`, `P2fDarkroomTest` (interactive work cancels background work,
background waits while dragging, a photo opens with its last picture, `modules()` once per drag and none during it,
spektrafilm where desktop puts it, crop round trip), `WordsTest`, `CropAspectTest`, `P2fLplTest` (agent on posts, R15
with and without, step and progress in the heartbeat only while running, download share, jobs run once, R14 with
fallback, the five job cases), screenshot tests `ListPhoneScreenshots`, `ListTabletScreenshots`, `TopBarScreenshots`;
emulator `P2fScenarioTest` f1 to f6 and f9, `HangGuard` (a time limit per test and a dump on failure).

{{RESULTS}}

## 6. Jobs from LightPhotoLibrary ("save edited to NAS" for the phone)

PLAN.md 4.1 says jobs `sync` and `release` addressed to the phone are carried out; it does not say what to do with
changes in the photo open in the darkroom. What the app does, and tells the user (rule proposed for PLAN.md):
- The sync writes the darkroom's current edit first (`writeIfOpen`), then posts it with `force`. With changes since the
  last "done": "saved your open changes and sent them · as LightPhotoLibrary asked". Without: "sent the edit as of your
  last "done" (no changes since)". Not open: "sent the edit". The photo stays open and editable.
- Not reachable or refused: "LightPhotoLibrary asked for this photo; not sent: <reason>; tried again later" (status
  "waiting to sync"). Read-only: "not sent: edited with a newer darktable".
- `release`: removed only after the edit was sent; a photo open in the darkroom stays ("it stays while it is open in the
  darkroom"); a photo whose changes could not be sent stays ("it has changes LightPhotoLibrary does not have yet"). Never
  removed with unsent changes (test).
- Where: the server header of the list ("LightPhotoLibrary asked to save 1 photo: 1 photo sent"), the photo's row, and in
  the darkroom as its message line, with the R16 step and bar while it runs. A job handed out again (R13) that already
  ran is reported, not run twice.

## 7. For the next engine package (no change under `engine/` here)

- **E1 (from the hang): `dtb_open` must fail on a file darktable cannot decode**, with `DTB_E_IO` and the loader's
  reason, instead of opening a session whose image is 0 x 0 (`load_status=3` from `dt_imageio_open`, "No decoder
  found" from rawspeed). And no render should start on an empty input: `_pipe_ensure` should refuse a mipmap buffer of
  size 0 (it checks `!p->buf.width`, but the hang shows the render went on: `_pipe_run` / the pipe's input of 17 x 25
  pixels after "1:1 copy/crop" suggests a fallback buffer). Evidence: `P2f-results/hang-44623988726.txt`. A bridge test
  with a junk file should open with IO and never hang.
- E2: per-module timings without `-d perf`: a bridge call that returns the last render's per-module times as JSON
  (darktable measures them per piece already), so the app need not parse darktable's log.
- E3: an option for the darkroom's view at fit (section 3), if the reviewer chooses one that needs the bridge.
- P2g's three bridge points (threads and CPU sets) stand.

## 8. Owner instructions

What to look at on the phone, in this order (install the APK from the release named below over the old one):
1. **Opening a photo right after "done".** Edit a photo from LightPhotoLibrary, tap "done", and open it again at once
   from the list. The photo should show immediately (its last picture), with a quiet line over the bottom ("opening…",
   then "rendering…"), and become sharp within about a second; never black.
2. **The list.** One row per photo: thumbnail, the file name (long names are shortened in the middle, the number at the
   end stays), one line of status, the last edit ("last edited on Desktop · 18 Nov · exposure, …" once your
   LightPhotoLibrary is updated), and a thin bar while it downloads or syncs. Tap a row to open it; the three dots give
   "sync now", "open in LightPhotoLibrary", "remove from this phone". The header says this phone's name, whether
   LightPhotoLibrary answers, what is waiting, and has one "sync now".
3. **"Save edited to NAS" from the PC.** Leave a photo open on the phone and press it in LightPhotoLibrary. The phone
   should say in the darkroom and in the list what it did: "saved your open changes and sent them" or "sent the edit as
   of your last "done"". "Remove" from the PC never removes a photo with changes not yet sent.
4. **The top bar.** The photo's name should be readable with "before", the three dots and "done" beside it.
5. **Crop.** In crop, "aspect" can now be chosen; the button beside it swaps portrait and landscape; moving a margin keeps
   the ratio. An edit made this way opens on the PC with the same ratio.
6. **Spektrafilm.** It is found by typing "spektra" (or "film") in the module search. With the default layout it is in no
   group, exactly as on the PC; "modules: all" has it under "tone". Its film data is in the app now (about page:
   "spektrafilm data …, 8.9 MB").

**How to send the per-module timings.** Open the photo you want measured (from LightPhotoLibrary or from the phone's
files), tap the three dots in the darkroom, "timings…", then "run". It takes a minute or two; the photo's edit is kept.
Tap "share" and send the text. It now says which photo source it was, how many history items the edit has, which
modules are on, whether the engine was pinned to the fast cores, and for each step which modules took the time. If no
photo is open, it runs on the last photo you opened.

## 9. Surprises in upstream

- `src/iop/demosaic.c` `_demosaic_full()`: outside thumbnails, demosaic always runs at full resolution
  (`modify_roi_in` asks for scale 1.0), so the sharp picture at fit processes the whole sensor through the early
  modules; there is no "zoomed out" demosaic option in this version.
- `src/libs/modulegroups.c`: with the default workflow "scene-referred (sigmoid)" the "workflow: scene-referred" layout
  lists spektrafilm in no group; only "modules: all" (group "tone") and the workflow "scene-referred (spektrafilm)"
  (group "base") do. On desktop too, the module is found by search in the default layout.
- `src/iop/crop.c` `_aspect_apply(GRAB_HORIZONTAL)` moves the left side while fitting a taller frame into the image
  ("square" on a 3:2 photo ends at the right edge, not centred).
- `src/common/darktable.h` `dt_get_num_threads()` follows the current affinity with libgomp (P2g report).

## 10. Open questions

- The view at fit: which of the options in section 3 (they change what the picture looks like while it is at fit).
- The rule for jobs on an open photo (section 6): keep "save the open changes and send them"?
- The crop list's typed ratio: wanted now?
- The unbound controls of the default layout (38, `P2f-results/unbound-controls-scene-referred.txt`): which first?

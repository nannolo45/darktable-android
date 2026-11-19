# P2f: fixes after the first real use

Brief: `docs/briefs/P2f-first-use-fixes.md` (with its addendum of 19 Nov, evening). Branch `p2f-first-use-fixes`,
from the tip of `p2g-engine-start`. Pull request text: `P2f-first-use-fixes-REPORT.md`.

## Additions from the reviewer (19 Nov, during P2g)

- **Deliverable 4, timings on any photo.** The owner could not run the timings on a photo from LightPhotoLibrary:
  `AppViewModel.runTimings` falls back to the file-picker working folder (`work.items()`) when no photo is open,
  and answers "open a photo first" when that folder is empty. Wanted: the timings (with the per-module table) run on
  the open photo whatever its source, otherwise on the last opened photo of either source; the text says the
  source (no server or person names), the number of history items and the modules on; check whether "timings…"
  from inside the darkroom still has the photo open; a test for the LightPhotoLibrary case.
- **New deliverable 10, crop's aspect** (after 1 to 8, before the final tests and report): crop's "aspect" combobox
  has `param: null` / `bound_to: null` in the schema, so it is shown disabled. Wanted, following the manual and
  `crop.c`: darktable's aspect list in its order, choosing one sets `ratio_n` / `ratio_d` and adjusts the frame and
  margins as desktop does, the orientation flip, dragging keeps the ratio, XMP round trip both ways; done through
  `schema/overrides/` (as `flip.json`, `temperature.json`); a typed "x:y" ratio may wait; JVM tests for the mapping
  and an emulator or contract test for the round trip. Also list (not fix) other interface-only controls without a
  parameter binding in the built-in "workflow: scene-referred" layout's modules.

- **R13 to R16 on LightPhotoLibrary's side** (reviewer, 19 Nov): build the client to these shapes; it must still work
  when the server answers 404 or leaves fields out; do not edit PLAN.md section 4. XMP post adds `agent=<agent id>`
  (URL-encoded). `GET /api/photos/{id}/edits` (optional `limit`) → `{"edits": [event…]}` newest first;
  `GET /api/photos/{id}` carries `"last_edit": event | null`; an event has `time` (seconds, number; the only field to
  rely on), `source` (`agent`, `outside`, `import`, `local`), `device`, `device_os`, `modules`, `changes`, `summary`;
  the line is "last edited on <device> · <time> · <summary>", `outside` said in plain words. Heartbeat items may add
  `step` (`downloading`, `sending edit`, `rendering preview`, `sending preview`) and `progress` 0..1, left out when the
  step ends. R13: a job never reported done comes back with the same id; report every job, and a job id already run
  is reported done again, not run twice. R14: `GET /api/auth/token` with `x-lpl-token` → 200
  `{"required", "ok"}`, 401 when wrong or missing; on 404 fall back to today's check.

- **Jobs from LightPhotoLibrary made visible** (reviewer, for deliverable 6): when a `sync` or `release` job
  arrives, say so where the user is (list header, the photo's row, the darkroom), with step and progress, and the
  outcome; establish what happens when the job's photo is open (with and without changes since the last "done"),
  tell the user which edit was sent; a `release` never removes a photo with unsent changes; follow PLAN.md 4.1 or
  propose the rule; tests.

## Running log (newest first)

- 2026-11-20 00:25 UTC: **run 44634077994 (368b272, rerun of the failed jobs): no hang.** Instrumentation 153 s
  (it was 26 to 50 minutes and more with the hang); 35 of 39 tests pass, the compare job is green (G0 75.7 dB, the
  app's XMP 74.4 dB, **spektrafilm with the packed data 60.45 dB**, the app's crop read by darktable-cli as 2:3);
  f9: 0 overlapping bridge calls, 100 cancels asked outside a render and not passed on. f1 after the fix: first picture
  169 ms after the tap (stored picture), the real preview 862 ms after it; the LightPhotoLibrary render was cancelled
  147 ms after it started and finished later (it was 10.8 s with nothing on screen before). Four failures, all in my
  tests: f1's check read the cancelled render's end as "finished" (marks now distinguish "cancelled" and "end"); f2
  and f3 used the engine directly and a closing darkroom from the test before closed their session ("STATE: no image
  open"; they now run inside the darkroom's own lock, `withEngine`); f5 expected spektrafilm in a group of the default
  layout, which desktop does not do (the JVM test already said so; fixed). Results kept in
  `P2f-results/emulator-44634077994.txt` and the heavy-edit timings in `timings-heavy-edit-emulator-44634077994.txt`.

- 2026-11-19 23:45 UTC: run 44634077994 (368b272): the apk job stopped on Maven Central "could not find" three times
  (no test ran); the emulator job was skipped. Failed jobs rerun. p2a-app of the same commit green after a rerun
  (44634077961). Report draft pushed (89316bd) with the hang, the bridge contract and the job rules; numbers follow
  from the rerun.

- 2026-11-19 23:10 UTC: **run 44623988726 (1a997dc), emulator red, and the cause found.** The new dump (kept in
  `P2f-results/hang-44623988726.txt`) shows, at l2's failure: darkroom lock held by a background task, one
  interactive task waiting, and the engine thread inside `NativeBridge.renderPreview` (native) for minutes. The app
  log just before: the background thumbnail pass (new in P2f) opened `OTHER_0023.CR2`, l1's placeholder photo, which
  is 2000 bytes of junk on purpose: rawspeed "No decoder found", `rawprepare` "invalid crop parameters ... for size
  0x0", `[mimpmap _init_f] load_status=3`, and the engine's render of that session never returned. Every later test
  waited behind it. Run 44616800258's heap corruption ("Scudo ERROR: corrupted chunk header") came after the same
  pattern (same placeholder file, same tests timing out). So: `dtb_open` accepts a file darktable cannot decode, and a
  render of that session hangs (and, by all signs, damages the native heap). Before P2f the app rendered only photos the
  user opened; P2f's background pass rendered every held photo with a file, including this one. Not a slow test and not
  the new scheduler: an engine bug the app newly walked into. On the phone the same would happen with a broken or
  partial download or a non-RAW file.
  Fixed in the app now: after `open`, `image_info` must report a picture larger than 0 x 0, else the photo is never
  rendered (darkroom: "darktable found no picture in this file"; thumbnails and the LightPhotoLibrary preview: skipped,
  and remembered). Also: the bridge is now called strictly as `dtbridge.h` allows (cancel only while a render runs,
  never alongside open/close; a counter of overlapping bridge calls, asserted 0 by the new test f9). Pushed; next:
  read the rerun, then the report.

- 2026-11-19 22:15 UTC: a reading that fits l2's timeline (photo opened, no preview after it, and none for any later
  photo): the canceller of a waiting interactive task cancelled *every* render while a background task was marked
  active, so a cancel issued just as the background task ended could land on the interactive render that took the
  lock next; that render ended CANCELLED, which the darkroom treats as "superseded" and never asks again. Fixed two
  ways: only a background task's own render can now be cancelled for interactive work (`bgEngine` marks its renders,
  `cancelBackgroundRender` checks the mark under a lock that the render's end also takes), and an interactive preview
  or region that ends CANCELLED while still wanted is run again (at most twice, marked in the timeline). Pushed on
  top of the diagnostics, so the rerun shows either a green job or where it still waits.

- 2026-11-19 22:00 UTC: run 44616800258 (2f2f375): with the time limits the emulator job ended (26 minutes of
  instrumentation, red). 29 of 39 tests ran and passed (the contract suite, P2b's scenarios, l1, l6); l2, l3 and l5 failed
  waiting 5 minutes for the darkroom to open a photo, l4 timed out after 10 minutes, then the process died with
  "Scudo ERROR: corrupted chunk header" (native heap corruption) and the P2f tests never ran. The darkroom's
  timeline in l2 shows the photo opened in the engine (open end at +28.9 s) but no preview after it, then a
  LightPhotoLibrary preview render (+30.0 to +30.9 s), then nothing: the darkroom stopped working from there on.
  Not yet known why: the thread dump came back empty (`Thread.getAllStackTraces()`), and logcat was cut to its last
  4000 lines, filled with heartbeat lines. **This is treated as a bug in the app, not a slow test.**
  Done now: (a) `JniEngine`: `renderCancel` and the calls that replace the session's pipes (open, close, cache drop,
  shutdown) exclude each other (dtbridge.h: cancel must not run concurrently with close; P2f made cancels from other
  threads frequent and background sessions close right after their render). Read of the bridge: the pipes are
  embedded in the session, so this alone may not explain the corruption. (b) Diagnostics: the darkroom scheduler's
  state, every thread's stack (by thread group) and the timeline are written on any test failure; the app's own log
  lines (without darktable's per-module lines) are kept whole. Next: rerun, read where it waits, fix.

- 2026-11-19 21:20: **the emulator job of run 44596781567 (b9dbd85) ran 105 minutes without ending; cancelled.**
  What is known: the instrumentation output reached the 27th of 39 tests (`JniEngineContractTest.
  exposureMakesThePictureBrighter`, which passed on every earlier run) before the log stops; the output was block
  buffered (`tr`), so the test that was really running is not known, and no logcat was kept because the script never
  finished. The p2a run of the same commit is green (166 JVM tests including the new list and top bar screenshots).
  Next, before anything else: every emulator test class now has a time limit (`HangGuard`: 10 or 12 minutes, the
  stack of every thread and the darkroom's timeline written to `failure-<test>.txt` on a failure), the
  instrumentation is bounded (50 minutes) and written line by line, and the emulator job has `timeout-minutes: 75`
  (was 120). A suspect read from the code is not found yet; the rerun will say where it waits. Pushed with the
  commits since b9dbd85 (aspect list, jobs note, unbound-controls list, compact top bar, merge of `p2g-engine-start`).

- 2026-11-19 22:30: deliverable 10 (crop's aspect list through override `aspect_list`, `CropAspect` with crop.c's
  arithmetic, flip, margins keep the ratio; JVM tests both directions; emulator f6 with an XMP round trip and a
  darktable-cli check of the app's crop) and the jobs note above (the agent says per photo and per server what a
  job did; texts in the list, header and darkroom; JVM tests for open with/without changes and release). Run
  44593499922 (65a0ef1) failed only on my new screenshot tests (a missing test runner annotation, a two-node text
  match); fixed. Its screenshots of the list look as intended.

- 2026-11-19 20:40: **deliverable 1, the cause, measured before any fix** (run 44588278381, b263e06, emulator, test
  RAW with denoise (profiled), diffuse or sharpen, local contrast and sharpen on): done → XMP posted 122 ms → LPL
  preview (dtb_export, 1920 px) runs from 153 ms to 10 213 ms → open requested at 163 ms → the darkroom's engine open
  starts only at 10 217 ms (after the export) → first picture 10 819 ms after the tap, and that was the real preview:
  nothing at all was on screen before. The reviewer's reading is confirmed: the export runs on the one engine
  thread under the darkroom's lock, cannot be cancelled, and opening waits behind it with a black frame. Also from the
  code: the list's thumbnail was dropped whenever the XMP was newer than it (`FolderThumb.load`), which a merged XMP
  from LightPhotoLibrary makes true. Kept in `P2f-results/f1-before-44588278381.txt` with the heavy edit's XMP.
  Then in code: deliverables 4 (per-module tables from darktable's "-d perf", timings on any source), 5 (list and top
  bar on a shared `PhotoListScreen`, middle-shortened names, a "more" icon), 6 (R16 steps in the agent, the heartbeat,
  the list and the darkroom) and 7 (R15 `agent` on XMP posts, `last_edit`, lines in the list and darkroom), plus
  R13 (jobs run once) and R14 (`/api/auth/token` with fallback). JVM tests for these pass on the workspace.

- 2026-11-19 19:45 (local, not pushed yet: the "before" run is still on the emulator and a push would cancel it):
  deliverables 2 and 3 in code. Interactive work goes first (`DarkroomController.fg`/`bg`, order described in the
  controller and in the report); the LightPhotoLibrary preview is rendered through the full pipe (cancellable) and
  encoded with Android's JPEG encoder instead of `dtb_export` (not cancellable); thumbnails are no longer rendered
  before the photo opens (background, when no photo is open); a stored last picture (1024 px) and the thumbnail are
  shown while a photo opens, with a line saying what happens and a thin bar for downloads; thumbnails older than
  the XMP are shown (and refreshed in the background) instead of black. Darkroom and LPL JVM tests pass on the
  workspace; tests that asserted all thumbnails right after `start` now assert the new order (open photo first,
  the others in the background).

- 2026-11-19 19:00: branch created from `p2g-engine-start` (a4def3f). First step, deliverable 1 before any fix:
  a timeline recorder (`darkroom/Timeline.kt`, marks in the darkroom's open and preview and around the LPL preview
  render) and emulator test `P2fScenarioTest.f1` (heavy edit: denoise (profiled), diffuse or sharpen, local
  contrast, sharpen on with defaults; done; reopen from the list while the LPL preview renders). Pushed unchanged
  otherwise, so CI records the timeline **before** the fix; f1 is expected to fail its acceptance checks there.
  Reading of the code so far (to be confirmed by the timeline): the LPL preview is darktable's 1920 px export
  under the darkroom controller's lock (`AppHost.Editor.renderPreview` → `withPhotoSession`), so `openPhoto` waits
  for it while the screen has `preview = null` (black); and the list's thumbnail (`FolderThumb.load`) is refused when
  the XMP is newer than it, which a merged answer from LPL (or any XMP write after the darkroom closed) makes true.

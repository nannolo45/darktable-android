# P3g: export with a progress bar — report

Branch `p3g-export` from `main` 4fc2974. Brief: `docs/briefs/P3g-export.md`.

**First, what is not as the brief asked:**
- **LightPhotoLibrary as a destination is not built.** PLAN.md 4.3 defines R5 (`POST /api/photos/{id}/export`) as a
  request to LPL, but `app/lpl` has no call for it and the brief forbids touching `app/lpl` beyond calling what
  exists. Built: "this device" and "share to an app". Proposed LPL addition below.
- **A series stops when the darkroom is left** (after the photo under way): the series export opens each photo in
  the darkroom, and leaving the darkroom closes the photo. One photo always finishes. The export also goes on
  with the export screen closed (top-bar indicator) and on any other screen while the darkroom keeps its photo.
- **One C27 check that I wrote wrongly failed in run 45127185130 and was changed.** I expected the export after
  the cancels to equal the first export byte for byte. It did not on any test image, while the first export
  equals darktable-cli's pixel for pixel (max 0). darktable's JPEG carries the export time in its metadata. The
  test now writes a second export with nothing in between (reported as byte-equal or not) and compares the
  decoded pixels of that one, of the export after the cancels and of `dtb_export`'s with the first, at max 0
  (`run_tests.sh`). The other C27 checks passed unchanged. Run 45140533602 confirms it: "two exports in a row:
  not equal byte for byte", and all four exports equal pixel for pixel (max 0) on cr2, raf and arw.

## 1. Item by item

| Brief item | State |
|---|---|
| 1. Engine progress and cancel during export; the upstream hook used | **done.** `dtb_export_ex` (callback) and `dtb_export_cancel` (any thread), `engine-api.md` section 7. The progress comes from the pipeline itself: "module n of N". No estimate was needed (see 4). |
| 2. Export screen as B3-Export (scope, destination, format, quality, bit depth, max size, profile, with the manual's defaults), progress bar and steps, cancel, top-bar indicator | **done.** `app/darkroom/.../ui/ExportScreen.kt`, `Exports.kt`, `ui-spec.md` section 13. Desktop has a quality slider; here it is a list (100…50). |
| 3. Destinations: this device (`Pictures/darktable`), share sheet, LightPhotoLibrary if section 4 allows | **partly:** device and share done; LPL not built (see above). |
| 4. Keeps running in the foreground on another screen; say what happens in the background | **partly:** see above for a series. In the background: nothing stops it while Android keeps the process; with no foreground service Android may kill the process and the export with it (later package). |
| Bridge tests: events in order, 100 %; cancel leaves no partial file; output unchanged against darktable-cli | **done** (C27; one check changed as said above). |
| JVM tests of the progress state and the series counter (stand-in engine) | **done** (`P3gExportTest`, 7 tests). |
| Screenshots idle, running, series, done, indicator, darktable and modern | **done** (`P3gExportScreenshotTest`, also the l1 indicator and two tablet shots). |
| Emulator: one photo with progress observed, cancel mid-way, two-photo series | **done** (`P3gScenarioTest`, run 45127185106). |
| CI triggers: `g0-app.yml`, `p2a-app.yml`, `p2b-real-engine.yml` | **done.** |

## 2. How to reproduce

- Bridge: `engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests> <out> C27 X1` (as `g0-app.yml`).
  Driver alone: `dtb_test <work> exportprog <raw> <xmp> <outdir>`.
- JVM: `cd app && gradle :darkroom:testDebugUnitTest --tests '*P3gExportTest'`.
- Screenshots: the `p2a-app.yml` job (`*P3gExportScreenshot*`, files `phone-p3g-*.png`, `tablet-p3g-*.png`).
- Emulator: `tools/app/emulator-p2b.sh` (class `app.dtandroid.real.P3gScenarioTest`), results in
  `files/p2b-test/p3g-results.txt`.

## 3. Results

**Bridge C27** (run 45127185130; the same checks again in 45140533602, CI Linux runner, x86_64 on 2 cores, not phone numbers):

| image | export | modules seen / enabled | cancel half-way returns after | cancel from a thread, then returns after |
|---|---|---|---|---|
| cr2 | 0.91 s | 8 / 8 | 0.33 s | 0.07–0.09 s |
| raf | 8.28 s | 11 / 11 | 3.79 s | 1.34 s, 2.16 s |
| arw | 2.95 s | 11 / 11 | 0.81 s | 0.97 s, 0.11 s |

Events on every image: preparing once at 0, one processing event per module (1..N, in order), writing once at
0.90, done once at 1.0, fraction never back. Every cancel (while preparing, first, middle and last module, while
writing, over an older file, from another thread at 30 % and 60 %) returned `CANCELLED` with no file at the path
and no temporary file; an older file at the path stayed as it was. The first export equals darktable-cli's
(`PASS C27 raf: size 6250x4168 max 0 … psnr inf`, likewise cr2 and arw). A cancel takes effect when the module
running at that moment ends: up to 2.2 s on the raf here.

**Emulator** (run 45127185106, API 33 x86_64; the public test RAW; `p3g-results.txt`):
- s1: steps `preparing | processing · module 1 of 7 … 7 of 7 | writing the file | saving to this device`;
  percents `0 5 16 28 39 51 62 74 86 95 100`; done in 1124 ms; `p3g_one_1.jpg` found in MediaStore.
- s2: cancelled at "processing · module 3 of 7": stopped 164 ms after the cancel; nothing delivered, no file left.
- s3: labels `photo 1 of 2 | photo 2 of 2`; 2 of 2 finished, two files, back on the first photo.
- 98 of 98 instrumentation tests passed.

**JVM** (`P3gExportTest`): one photo, steps in order and 100 %; cancel mid-way (nothing delivered, no file, the next
export not cancelled); a series of 3 counts finished photos exactly and returns to the photo it started from; a
series cancelled on photo 2 counts 1; no second export while one runs; darktable's defaults and their engine
options; the words. The series test caught a real bug (the overall bar went back between photos), fixed in
4f77cf58.

## 4. Upstream: where the progress comes from

darktable has no progress callback for one export: desktop's export job reports only "image n of N"
(`src/control/jobs/control_jobs.c`), and `dt_imageio_export_with_flags` (`src/imageio/imageio.c`) keeps its pipe on
its own stack. Two places reach into it without a source change:
- `module->process_plain` (`default_process`, `src/develop/imageop.c`; copied from `so->process_plain` in
  `dt_iop_load_module_by_so`), which the bridge already wraps for module times (`engine/src/times.c`, P1b). The
  wrapper now calls `dtb__export_hook` before each module: it counts the export pipe's modules
  (`dt_pipe_is_export`) and, after a cancel, calls `dt_dev_pixelpipe_set_shutdown(pipe, DT_DEV_PIXELPIPE_STOP_PIECE)`.
- the format's `write_image`: the export gets a copy of the format module whose `write_image` reports "writing" and
  refuses to write after a cancel.

Surprise: `_module_pipe_stop` (`src/develop/pixelpipe_hb.c`) ignores `STOP_NODES`, `STOP_HQ`, `STOP_ZOOM` and
`STOP_DATA` after colorout ("late in the pipe"), so the stopper the render cancel uses (`STOP_DATA`) would not stop
an export late in the pipe. `STOP_PIECE` (what `dt_dev_piece_shutdown` sets from inside a module) is never ignored.
The file is written under a temporary name in the same folder and renamed only on success.

Limits: the bar counts modules, not time (demosaic and heavy modules take more of the time than of the bar); a
module processed by OpenCL would not be seen (the bridge runs on the CPU).

## 5. Proposed LPL addition (not built)

R5 as written in PLAN.md 4.3 is enough for the server side. The app needs, in `app/lpl` (a later package, since
this brief forbids touching it): `LplClient.postExport(photoId, fileName, bytes, seriesId?, position?) ->
storedPath`, streaming the body with a byte counter so the export screen can show "sending to LightPhotoLibrary ·
n %" (the step word is already reserved in `ExportJob.STEP_LPL`), and a way to wait for the NAS when it is not
reachable (mockup note: "the export waits and goes out with the next sync"), e.g. the agent's kept jobs. Then
`ExportTarget.LPL` delivers through it.

## 6. Open questions for the reviewer

1. Series export opens each photo in the darkroom (simple and uses the same open path, LPL fetch included). Should
   a later package export a series without showing each photo (an engine session of its own is not possible: the
   bridge has one session)?
2. Quality as a list instead of desktop's slider: acceptable, or should the screen reuse the slider control?
3. Should the bar weight modules by time (from `render_times` of the last full render) instead of counting them?

## CI

Last code commit **849fbf53** (CI cache key; app and engine code last changed in d97b0504):

| Workflow | Run | Commit | Result |
|---|---|---|---|
| g0-app (bridge tests on Linux) | 45140533602 | 849fbf53 | success: 374 passed, 0 failed, C27 on cr2, raf, arw |
| p2b-real-engine (APK, emulator, compare) | 45140533641 | 849fbf53 | success (emulator job included) |
| p2a-app (JVM tests, screenshots) | 45134611280 | d97b0504 | success (849fbf53 changes no path this workflow watches) |

Earlier runs: 45127185106 (p2b, emulator, success; the emulator numbers in section 3), 45127185125 (p2a, success),
45127185130 (g0: C27 byte-equality check failed, see the top), 45134611252 (g0: ran the old C27 driver because the
bridge build's cache key did not list `dtb_test_p3g.c`; fixed in 849fbf53), 45125877604 (p2a: the overall bar went
back between photos of a series; fixed in 4f77cf58), and compile errors fixed in d8d6fa7b, 27371e81, a4a39e7e.

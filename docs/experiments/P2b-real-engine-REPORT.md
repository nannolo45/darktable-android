# P2b: the real engine in the app — pull request description

Branch `p2b-real-engine` into `main`. Brief: `docs/briefs/P2b-real-engine.md`. Running log, write-up and owner
instructions: `docs/experiments/P2b-real-engine.md`. Reproductions: `docs/experiments/P2b-results/`.

**Failures first.**
- **Contract suite on the real engine: 17 of 18 pass on the emulator (run 44534730162); `guardAndReadOnlySession` fails** because of a
  bridge gap (not fixed here: `engine/` is outside this package). A read-only session opened with an XMP that names
  an unknown module reports `history()` `end = 10` with 9 items, and `history_goto(10)` is then refused
  (`ARG: end 10 outside 0..9`). Reproduced on the Linux bridge (`P2b-results/probe-history.c`). The app works
  around it for "before" (goes back to `min(end, items)`; tested, `t4_readOnlyPath`); the contract test is left
  failing, unchanged.
- **The 50 ms drag target is not reached on the CI emulator** (no phone here): the emulator's preview render has a
  fixed part of about 70 ms whatever the size (256 px 72.6 ms, 384 px 80.9 ms; run 44528584881). The drag picture
  then takes the smallest size (320 px); the median drag step was 75.7 ms. Whether 50 ms is reached on the phone is
  only known from the owner's timings (G0's phone preview was 0.09 s at 1024 px with 4 fast cores).
- **Nothing arm64 has run anywhere.** The arm64 APK is built, signed and published; every execution was x86_64
  (emulator, workspace, Linux).

## 1. Brief item by item

| # | Item | State | Where |
|---|---|---|---|
| 1 | JNI engine: every core call through `DarktableEngine`, error codes, cancel, threads, margin as the bridge provides; JSON parsed into the fake's types; `dtbridge.h` unchanged | **done** | `app/engine-jni/.../JniEngine.kt`, natives in `dtjni.c`, `FastCores.kt` |
| 2 | Contract suite against the real engine on the x86_64 emulator, real RAW from the public set; every fake/real difference reported, fake fixed | **partly**: 17/18 pass; 1 fails on a bridge gap (above). 6 differences found, 5 fixed in fake and contract (section 3) | `app-engine/src/androidTest/.../JniEngineContractTest.kt` |
| 3 | App build with the real engine, arm64-v8a and x86_64, engine packed and unpacked as G0, fast cores pinned; fake stays for JVM/screenshots/no-native build | **done** (pinning exercised only where clocks differ: not on the emulator) | `app/app-engine`, `Engine.prepare`, `AppEngine.kt` |
| 4 | Photos in and out: file picker (several, optional `.xmp`) into a private working folder, filmstrip = folder; done writes XMP; export JPEG and save XMP to a chosen place; RAW never written; read-only honoured | **done** (picker and save dialog not driven in a test: no UI automation of the system picker; the copy logic has JVM tests) | `darkroom/work/WorkFolder.kt`, `AppViewModel.kt`, `MainActivity.kt` |
| 5 | Feel: smaller picture while dragging, full preview + sharp region on lift, cancel/restart, "before" kept per session, size from measurements, thumb never waits | **done**; target **not met on the emulator** (above) | `DarkroomController.applyDrag`, `DragPicture.kt` |
| 6 | Timings page, shareable text, no device model in the text | **done** | `Timings.kt`, `Screens.kt` (`TimingsScreen`) |
| 7 | Tests: contract suite; open-change-undo-write-reopen; export vs darktable-cli; read-only path; cancel while dragging; JVM tests | **done** (contract: one failure, above) | `RealEngineScenarioTest.kt`, JVM: `WorkFolderTest`, `DragPictureTest`, `DragAndBeforeTest` |
| 8 | CI: both ABIs from released engine archives, emulator tests, arm64 APK published with a distinct name, owner-key signing | **done** | `.github/workflows/p2b-real-engine.yml`, `tools/app/emulator-p2b.sh` |
| 9 | Owner instructions, write-up, this description | **done** | `P2b-real-engine.md` |

Acceptance:
- Contract suite on the real engine: **17 of 18**; the failure is explained above with numbers.
- **XMP written by the app, rendered by darktable-cli on Linux, vs the app's own export: same size 3908 x 2602, PSNR
  74.44 dB, max difference 34, 0.071 % of values differ** (G0 bound: same size, PSNR >= 40 dB). The G0 comparison (test XMP) gives same size, PSNR 75.73 dB, max 26, 0.064 % (as G0 measured).
- arm64 APK published: release `app-engine-554c2c1c14a0`, https://github.com/nannolo45/darktable-android/releases/download/app-engine-554c2c1c14a0/dt-android-engine-arm64-v8a.apk (30 815 865 bytes, owner key). Emulator only: everything executed; phone needed: arm64, speed, fast-core
  pinning on a big.LITTLE CPU, the system file picker and save dialog, sharing, background/foreground behaviour.

## 2. How to reproduce

CI: push to `p2b-real-engine` or start workflow **p2b-real-engine** by hand. Locally (Ubuntu 24.04), with the engine
release `engine-04477d32-992bc04fa900` unpacked into `<dir>` (`engine-arm64-v8a/`, `engine-x86_64/`):

```
export ANDROID_HOME=<sdk with platforms;android-36, build-tools;35.0.0, ndk;30.0.16248370, cmake;3.22.1>
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :darkroom:testDebugUnitTest :checkScreenRules
$GRADLE --no-daemon -Pdta.engineDir=<dir> [-Pdta.keystore=<keystore>] \
  :app-engine:assembleArm64Debug :app-engine:assembleX86_64Debug :app-engine:assembleX86_64DebugAndroidTest
tools/app/emulator-p2b.sh app-engine/build/outputs/apk/x86_64/debug/app-engine-x86_64-debug.apk \
  app-engine/build/outputs/apk/androidTest/x86_64/debug/app-engine-x86_64-debug-androidTest.apk \
  <darktable-tests>/images/mire1.cr2 <darktable-tests>/0001-exposure/exposure.xmp /tmp/emu   # needs an emulator
```
Bridge reproductions: `docs/experiments/P2b-results/README.md`.

## 3. Results

Final run **44534730162** (commit 554c2c1; logs on `ci-logs` under `runs/44534730162-a1/`): rule check and
120 JVM tests (fake engine, screenshots included) pass; both APKs built and signed with the owner key (APK and
test APK: certificate SHA-256 6f9e8867…); arm64 APK published; emulator 24 of 25 (all 7 scenario tests, 17 of 18
contract tests); both comparisons with darktable-cli pass. Earlier runs: 44525769883 (19/25, comparisons passed),
44528584881 (22/25), 44531388217 (Linux package install failed; retried since), 44521382732 and 44522813496
(superseded / reference step failed without log).

Emulator, API 33 x86_64, 4 cores, 6 GB, GitHub runner (**not phone numbers**; runner-to-runner spread is large, two
runs shown):

| | run 44528584881 | run 44534730162 |
|---|---|---|
| unpack (first start) / dtb_init | 4150 / 1529 ms | 3633 / 1275 ms |
| open / image info (full load) | 148 / 168 ms | 162 / 258 ms |
| first preview 1024x681 | 191 ms | 510 ms |
| preview after a late change (color balance rgb) | 188 ms | 692 ms |
| preview after an early change (exposure) | 219 ms | 466 ms |
| drag picture 320 px, 6 steps, median (min-max) | 75.7 (70-112) ms | 284 (148-441) ms |
| drag by size 256/384/512/640/768/1024 px | 72.6/80.9/95.3/110.5/159.8/443.8 ms | 200/257/343/297/238/234 ms |
| sharp region at fit 1080x719 / at 100 % 1080x1500 after a change | 544 / 452 ms | 746 / 493 ms |
| modules(): bytes; bridge / JNI / Kotlin parse | 185 219; 9.4 / 0.5 / 9.2 ms | 185 219; 9.6 / 0.5 / 8.8 ms |
| full-size JPEG export (timings page / scenario test) | 2436 / 1034 ms | 2437 / 1333 ms |
| threads, cores | 4 of 4, not pinned (emulator reports no clocks) | same |

Scenario tests (all runs since 44525769883): open-change-undo-write-reopen gives the same history and modules and a
pixel-identical preview (max 0); the RAW's MD5 is unchanged; read-only shows the banner, refuses the slider and
save XMP, never rewrites the XMP, and "before" works (end 10, 9 items); the drag test sees renders cancelled, one
history item for 20 drag steps, no error, the full preview and region after the lift (930 ms from the first
step to the sharp picture in the last run); the timings sequence runs and restores the edit; the app starts.


### Fake and real engine: every difference found

| # | Point | Fake (before) | Real engine (truth) | Done |
|---|---|---|---|---|
| 1 | `set_threads(0)` | ARG | restores the count of init; larger n clamped (header) | fake and contract corrected |
| 2 | `set_thread_cpus([])` | ARG | removes the pinning (header) | fake and contract corrected |
| 3 | `history_goto` out of range | RANGE | ARG | fake and contract corrected |
| 4 | compress | dropped modules that end up off | keeps them, off (`dt_history_compress_on_image`: "keep disabled modules as documented"); drops items above the end | fake and contract corrected |
| 5 | `in_history` | items up to the end | any item in the list, also above the end (`_in_history`, engine/src/params.c) | fake and contract corrected |
| 6 | Exif `datetime` | `2026:06:01 18:30:00` | `YYYY-MM-DDTHH:MM:SS` (header) | fake and preview series corrected |
| 7 | reopening an image without XMP in one process | default edit | the history left in the private library by the earlier session, items above the end included (`P2b-results/probe-reopen.c`) | not a fake change: the JNI suite gives each test its own copy of the RAW; open question 2 |
| 8 | read-only + unknown module: `history().end` | = items | end 10, 9 items; `history_goto(end)` refused | bridge gap, open question 1; contract test fails |

### Threading model (deliverable 1)
One engine thread per process (`Engine`, 64 MB stack, a queue). Every `JniEngine` call is a task on it; the caller
suspends. A task whose caller was cancelled before it started is skipped, so stale renders never start; a running
render is stopped only by `renderCancel()` (bridge, between modules). The controller keeps its own lock so that
engine calls that belong together (goto 0 + preview + goto back) are not interleaved. The engine and session live in
the process: an activity recreated by Android finds the same session (the controller is in a ViewModel); export and
save run in a process scope and finish when the activity goes away; `onStop` writes the edit.

### Memory and pixels
Region renders go straight into the Java array (`GetByteArrayElements`; on ART it was the array itself: "rendered in
place (no JNI copy): true" on the emulator); previews take one copy from the bridge's buffer. Bitmaps are filled with
one `copyPixelsFromBuffer` (no per-pixel loop). The filmstrip keeps 128 px thumbnails (it kept the 1024 px preview
before, about 3 MB each), cached on disk per photo. No full-size buffer is ever held in Kotlin (export writes a file).

### `modules()` (deliverable 5's question)
185 219 bytes; bridge call 9.4 ms, JNI string 0.5 ms, Kotlin parse 9.2 ms (emulator x86_64, medians of 5). So the
re-read costs about 19 ms on the emulator: it is not done during a drag (only when the finger lifts); a per-module
call was not needed for that.

## 4. Upstream surprises
- `src/develop/develop.c` `_dev_add_history_item_ext()`: after `dt_dev_history_seal()` a change to the top item's
  module starts a new item only when something differs; the bridge's grouping relies on it (no surprise in the
  tests, noted for readers).
- `src/common/history.c` `dt_history_compress_on_image()` keeps disabled modules on purpose; the contract had it wrong.
- darktable keeps an image's history in its library; with `:memory:` that lasts for the process, so
  `open(raw, NULL)` of an image opened earlier in the same process shows the earlier session's state.

## 5. Open questions for the reviewer
1. Bridge: `history()` end in a read-only session with an unknown module (end counts the dropped entry). Fix in the
   bridge (clamp `end`, or keep a placeholder item), or document? The app clamps for "before".
2. Bridge/contract: should `open(raw, NULL)` mean "default edit" (as the fake and the contract assume) or "whatever the
   library holds" (as now)? The app always opens with the sidecar when one exists, so it is not affected today.
3. Application id `app.dtandroid.real` (label "dt-android engine") so it installs next to the preview; should the real
   app take `app.dtandroid` once the preview is retired?
4. Optional slider colours (reviewer's late addition): **not done**. `schema/ui-schema.json` records only the
   *number* of gradient stops per slider (`gradient_stops: 20`, from `d->grad_cnt` in
   `schema/extractor/schema_extractor.c`), not their positions and colours; painting them needs the extractor to
   record `grad_pos`/`grad_col` and the schema to be regenerated (S5 territory). Not attempted, to keep the brief's
   deliverables safe. 114 sliders in 13 modules carry stops.
5. The 50 ms target vs the emulator's ~70 ms fixed part per preview render: if the phone shows the same pattern, the
   fixed part (pipeline set-up per run) is the thing to look at, not the picture size.

## 6. Other notes
- No changes under `engine/`, `upstream/`; no patches; `dtbridge.h` unchanged.
- New dependencies: none beyond what the modules already used (AndroidX test libraries in the new module's tests,
  `testFixtures(project(":engine-api"))`).
- The reviewer's signing change (main 0071d6a) is merged; the APKs and the test APK carry the owner key (apk-report).
- My first commit (log only) was merged into main early through pull request #1 by the owner; nothing else of the
  branch is on main.
- Commit trailers name the model that wrote them (Claude Opus 5.5); the brief asked for a different model name, which
  would have been inaccurate.
- Measurements: emulator = API 33 x86_64, 4 cores, on GitHub's runner; workspace = x86_64, 2 cores. None are phone
  numbers.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

# Pull request: G0 test app (includes S4)

Branch `g0-test-app` into `main`. Brief: `docs/briefs/G0-test-app.md`. Write-up and owner instructions:
`docs/experiments/G0-test-app.md`.

**Upstream patches: none.** `upstream/` is untouched and `patches/` does not exist.
**Nothing failed that the brief asks for**, on the emulator. The arm64 build has **not been executed** anywhere:
the phone is the only place that can show whether it runs (gate G0).

## 1. Deliverables

| # | Brief item | Status | Where |
|---|---|---|---|
| 1 | One source of truth for the upstream pin | **done** | `tools/darktable-pin.sh` reads the submodule commit from the tree; `fetch-darktable.sh`, `tags.sh` and through them all workflows use it; the literal commit id is removed |
| 2 | Bridge built for arm64-v8a and x86_64 inside the engine build, from darktable's build tree, shipped in the engine archive; Linux bridge tests still working | **done** | `tools/build-engine-android.sh`, `tools/build-engine-linux.sh`, `engine/CMakeLists.txt`; Linux tests run in CI job `linux` |
| 3 | App `app.dtandroid`, min API 29, arm64-v8a and x86_64, thin JNI over `dtbridge.h` | **done** | `app/engine-jni`, `app/g0` |
| 3 | S4: unpack engine into app storage, start with `dtb_init`, show plugin count, fail visibly | **done (emulator only)** | 111 of 111 darktable plugins, 152 of 152 plugin files `dlopen()`-able, 42 of 42 libraries |
| 3 | Open RAW (copied into app storage), optional XMP, `dtb_xmp_check` first, read-only on failure | **done** | `DiagViewModel.pickRaw/pickXmp/openImage` |
| 3 | Preview, exposure slider re-rendering with cancel in flight, then screen-sized region with 16 px margin, times shown | **done** | `DiagViewModel.renderLoop`, `EngineOps.region` |
| 3 | Switch list of modules in the history | **done** | |
| 3 | Export JPEG full size to Pictures via MediaStore; Save XMP via the system dialog; export time | **done** (not exercised on a device through the UI; the export and XMP paths are exercised by the instrumented test) | |
| 3 | Benchmark: open, first preview, preview after change, region after change, full-resolution render, export, with 1, 4, all threads; table with device facts; Share | **done** | `Benchmark.kt`; thread count changed at run time with the new `dtb_set_threads` (no engine restart) |
| 3 | Collapsible, shareable engine log | **done** | stdout/stderr of the process captured in `dtjni.c` |
| 4 | CI: debug APK per ABI, release files with a commit-derived tag, instrumented test on x86_64 API 33 emulator (push a public RAW, open, plugins, render, export, compare with Linux reference), logs to `ci-logs`, APK sizes | **done** | `.github/workflows/g0-app.yml`, `tools/app/emulator-g0.sh` |
| 5 | Owner instructions in plain language, incl. the open S3 hand check | **done** | `G0-test-app.md`, section "For the owner" |
| 6 | Write-up: packing/unpacking, sizes, emulator results, what needs the phone | **done** | `G0-test-app.md`, section "Write-up" |

## 2. How to reproduce

CI: push to `g0-test-app` or start workflow **g0-app** by hand. It reuses `deps.yml` and `engine.yml` (both skip
an ABI whose release file exists), then builds and publishes the APKs, runs the Linux bridge tests and the
emulator test.

Locally (Ubuntu 24.04), after unpacking `engine-arm64-v8a.tar.gz` and `engine-x86_64.tar.gz` from the engine
release into `<dir>`:

```
export ANDROID_HOME=<sdk with platforms;android-36, build-tools;35.0.0, ndk;30.0.16248370>
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app && $GRADLE --no-daemon -Pdta.engineDir=<dir> assembleArm64Debug assembleX86_64Debug assembleX86_64DebugAndroidTest
tools/app/emulator-g0.sh g0/build/outputs/apk/x86_64/debug/g0-x86_64-debug.apk \
  g0/build/outputs/apk/androidTest/x86_64/debug/g0-x86_64-debug-androidTest.apk \
  <darktable-tests>/images/mire1.cr2 <darktable-tests>/0001-exposure/exposure.xmp /tmp/emu   # needs an emulator
```

## 3. Results (CI)

RESULTS_PLACEHOLDER

## 4. Changes to `engine/include/dtbridge.h`

All additions; nothing existing changed.
- `char *dtb_plugins_json(void)`: plugin files in the module directory versus plugins darktable loaded, per kind
  (iop, format, storage), names of those not loaded, and counts of view and lighttable plugins (not loaded without
  the desktop interface). Needed for S4.
- `int dtb_set_threads(int n)` / `int dtb_get_threads(void)`: change `darktable.num_openmp_threads` while the
  engine runs, as `dt_init --threads` does. Capped at the count in effect after `dtb_init`, because darktable sizes
  some per-thread buffers at start-up; the app starts with all cores and goes down. Needed for the benchmark
  without restarting the engine (darktable is not known to survive `dt_cleanup` + `dt_init` in one process).
- `int dtb_cache_drop(void)`: with no image open, evict the last image's full and downscaled float buffers and
  thumbnails from the mipmap cache, so each benchmark round starts from the RAW.

Also `engine/CMakeLists.txt`: `NO_CMAKE_FIND_ROOT_PATH` for libdarktable (cross builds) and option
`DTB_BUILD_TESTS`.

## 5. Deviations from the brief and other choices

- **Thread count without restart** (see 4), instead of restarting the engine between settings.
- **Benchmark protocol:** before the benchmark the current edit is written to a snapshot XMP in the cache
  (for a read-only session the picked XMP is used); each round closes the image, drops caches and reopens from
  it; afterwards the session is reopened from the snapshot. Preview size 1024 px (as S3's T7, for comparison);
  "screen-sized region" = a region of the screen's pixel size at 100 % from the image centre, rendered with a
  16 px margin and cropped. Both the region and the full-size render are timed after an exposure change, and
  the first region render is timed separately.
- **Library `:memory:`** in the app, so a reopened file never shows a stale history from an earlier session.
- **Engine tag** (`tools/ci/tags.sh`) now hashes only what the engine is built from (deps/, bridge sources,
  engine build scripts, pin) instead of all of `tools/`; with the bridge added it also covers `engine/`. Test
  script changes no longer rebuild the engine.
- **`engine.yml`** skips an ABI whose archive already exists (like `deps.yml`), with a `force` input.
- **Linux CI job** runs `engine/tests/run_tests.sh` (S3 T1 to T9, X1) on the Linux reference build and fails on
  any failure except T5, which S3 documents as a known failure caused by upstream modules. T5 still runs and its
  numbers are logged.
- **No Gradle wrapper jar** is committed: `tools/app/setup-gradle.sh` downloads Gradle 8.14.3 and checks its
  SHA-256 (`app/gradle-version.properties`). Versions: AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01,
  compileSdk 36, targetSdk 34, minSdk 29, NDK r30 (as the engine).
- **Dex compressed** in the APK (`useLegacyPackaging`): about 25 MB less to download for an unshrunk debug build.
- **Fixed in passing:** `fetch-darktable.sh`'s clean-tree check passed silently when copying from the submodule.
- New tools the brief did not name: none beyond Gradle/AGP/Compose (needed for an Android app) and AndroidX test
  libraries (instrumented test).
- Each CI build signs with a fresh debug key, so a newer build may need the old one uninstalled (said in the
  instructions). No keystore is committed.

## 6. Surprises in upstream

- `src/common/file_location.c` `dt_loc_init()`: share and locale directories are derived from the executable
  path (whereami). In an app process that is `/system/bin/app_process64`, so darktable logs that
  `/system/bin/../share/locale` and `/system/bin/../share` cannot be opened. Harmless here (data and modules come
  from `--datadir`/`--moduledir`; lensfun falls back to `<datadir>/../lensfun`), but the share directory also
  goes into `XDG_DATA_DIRS`.
- `dtb_render_cancel()` semantics bite easily: a cancel sent while `dtb_render_size()` synchronises the pipe is
  cleared by the next `dtb_render()`. My first test fell into exactly that; the app's slider path is not affected
  because each new value triggers a new render anyway.

## 7. Open questions for the reviewer

1. Is changing the thread count at run time (capped at the start-up count) acceptable for the API, or should the
   app restart the engine in a separate process in Phase 1?
2. The engine tag no longer covers all of `tools/`; is that the intended meaning?
3. Should the arm64 APK eventually be signed with a stable key (stored as a repository secret) so the owner can
   update without uninstalling? Not done: the brief allows a CI-generated debug key and asks for no secrets.
4. 4 threads were slower than 1 for the full-size render on the emulator (one run); worth watching on the phone.

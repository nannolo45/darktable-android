# G0: test app for the owner's phone (includes S4)

Brief: `docs/briefs/G0-test-app.md`. Branch `g0-test-app`.

## Running log (newest first)

- 2026-11-19: run 44433614716 (commit 873e4a3): cancel check fixed, permission check added.
- 2026-11-19: first CI run 44429110671 (commit 0791818): all jobs green. Engine with bridge for both ABIs, APKs
  published as `g0-app-0791818be8ac`, emulator test passed, Linux bridge tests as in S3 (only T5 fails). The
  cancel check in that run did not exercise a cancel (cancel sent before dtb_render started); fixed.
- 2026-11-19: first CI run 44429110671 started (engine with bridge, APKs, emulator test, Linux bridge tests).
- 2026-11-19: branch created; pin, bridge build, app and workflow written. Local checks: bridge builds against a
  Linux darktable build of the pinned commit; the app compiles (Gradle 8.14.3, AGP 8.13.2) against a stub bridge.

Next steps: the owner runs the arm64 APK on the target phone (section "For the owner").

---

## For the owner: how to try the test app

This is a test tool, not the real app. It shows whether darktable's engine works on your phone and how fast it is.
Nothing is sent anywhere: the app has no internet permission.

### 1. Download

1. On the phone, open https://github.com/nannolo45/darktable-android/releases in the browser.
2. Find the newest entry whose title starts with **"G0 test app"**.
3. Under "Assets", tap **dt-android-g0-arm64-v8a.apk** (about 32 MB). Do not take the "x86_64" file: that one is
   for the test computer.

### 2. Install

1. Open the downloaded file (from the browser's download message, or in the "Files" app under Downloads).
2. Android asks whether the browser (or the file manager) may install apps. Tap **Settings**, switch on
   **Allow from this source**, go back, and tap **Install**.
3. Play Protect may warn that the app is unknown. Choose **Install anyway** (the wording varies). The app is a
   debug build signed with a temporary key, which is why it is unknown.
4. **If a newer test build refuses to install** ("App not installed" or "conflicts with an existing package"):
   uninstall the old "dt-android G0 test" first (long-press its icon, **Uninstall**), then install again. Each test
   build is signed with a new temporary key.

### 3. Five things to try

Have one RAW file from your camera on the phone (for example a RAF), and if you like the `.xmp` file darktable
wrote for it on the PC.

1. **Start.** Open "dt-android G0 test". The first start unpacks the engine (a few seconds). The box at the top
   must say **"All ... module plugins loaded"**. If it is red or says "NOT ALL PLUGINS LOADED", stop here and send
   a screenshot.
2. **Open a photo.** Tap **Open RAW** and pick the RAW file. Optionally tap **Pick XMP** and pick its `.xmp`.
   The app shows "XMP check: ok" or explains why the photo opens read-only. Tap **Open**. A preview appears.
3. **Move exposure.** Drag the exposure slider. The preview should follow, and after you stop, a sharper
   screen-sized crop from the middle of the photo (at 100 %) appears below. Each shows how many milliseconds it
   took.
4. **Try the edit and export.** Switch modules in the list "modules in the history" off and on and watch the
   preview. Then tap **Export JPEG**. When it is done, the time is shown and the JPEG is in your gallery, in the
   album "dt-android" (Pictures/dt-android).
5. **Benchmark.** Tap **Run benchmark** and wait (a few minutes; the screen shows "benchmark running"). A table
   appears. Tap **Share results** and send it, for example by e-mail or a messenger, to yourself and on to the
   project.

### 4. One check on the PC (still open from S3)

This checks that darktable on the PC reads what the phone writes.

1. Open the RAW in the app (with its XMP if you have one), move the exposure slider, and do **not** export.
2. Tap **Save XMP**. Android's file dialog opens with a name like `DSCF1234.RAF.xmp`. Save it, for example in
   Downloads.
3. Copy that `.xmp` file to the PC, next to a copy of the same RAW, with exactly the name the dialog proposed
   (RAW file name plus `.xmp`). Use a copy of the RAW in a new folder, so your real edit is not touched.
4. Open that folder in darktable on the PC (the same 5.7 development line as the app's engine, see the version line
   in the app's top box). Open the photo in the darkroom.
5. Check: does the history show the exposure change at the top, and does the photo look like the app's preview?

### 5. What to send back

- The shared benchmark text.
- Whether anything crashed, froze or looked wrong, and what you did just before. A screenshot helps.
- The result of the PC check (step 4): history as expected yes or no, picture the same yes or no.
- If something went wrong: tap **Show engine log**, then **Share log**, and send that text too.

---

## Write-up

Numbers below come from GitHub Actions runs (standard `ubuntu-24.04` runners, 4 cores); logs are on the
`ci-logs` branch under `runs/<run id>-a1/`. Emulator numbers are **x86_64 on a cloud runner, not phone numbers**.

### One source of truth for the upstream pin
`tools/darktable-pin.sh` reads the commit from the parent repository's tree (`git rev-parse HEAD:upstream/darktable`)
and the URL from `.gitmodules`; the literal commit id is gone. `tools/fetch-darktable.sh` refuses a checked-out
submodule at a different commit, and `tools/ci/tags.sh` uses the same value. The workflows get the commit only
through these scripts. While doing this I found that the clean-tree check in `fetch-darktable.sh` passed silently
when the source was copied from the submodule (the copy's `.git` file points nowhere, `git status` failed, the
empty output counted as clean); it now checks the submodule itself.

### Bridge in the engine build
`tools/build-engine-android.sh` configures `engine/` right after darktable with the same NDK toolchain arguments
and `DT_BUILD_DIR` = darktable's own build tree, so `engine/CMakeLists.txt` takes the compile definitions and
include paths of `src/common/darktable.c` from that tree (S3 condition 4). The list it used is printed in
`engine-<abi>-report.txt` ("bridge: darktable compile definitions"). One CMake change was needed:
`find_library(... NO_CMAKE_FIND_ROOT_PATH)`, because the NDK toolchain re-roots absolute paths under its sysroot.
`tools/build-engine-linux.sh` does the same for the Linux reference build, and CI runs the S3 bridge tests on it.

The archive now holds `lib/libdtbridge.so` (40 KB on x86_64) and `lib/load-order.txt` (42 entries, from
`tools/engine-load-order.sh`).

### How the engine is packed and unpacked (S4)
- The APK has one flavor per ABI. A Gradle task zips that ABI's engine tree without `bin/` (an app cannot run
  executables from its storage) into `assets/engine/engine.zip`, stored without recompression, plus
  `assets/engine/engine.id` (first 16 hex digits of the zip's SHA-256).
- First start: `EngineInstaller` unzips into `files/engine/` (via `files/engine.tmp/` and a rename) and writes
  `.unpacked-id`. Later starts skip this unless the id changed (new APK).
- Android cannot add app storage to the dynamic linker's search path. The app therefore `System.load()`s every
  library in `lib/load-order.txt` by absolute path, dependencies first; then `System.loadLibrary("dtjni")` (the
  JNI layer, the only native library in the APK's `lib/`), whose `NEEDED libdtbridge.so` resolves to the copy
  already loaded. darktable's plugins, opened by darktable itself with `g_module_open()`, find `libdarktable.so`,
  GTK and the rest by soname the same way. Nothing in darktable had to change for this.
- Before `dtb_init`, the app also `dlopen()`s every plugin file (152) itself and reports failures; after it,
  `dtb_plugins_json()` compares the plugin files with what darktable loaded (processing modules, formats,
  storages; view and lighttable plugins are not loaded without the desktop interface and are only counted).
- Environment set before `dtb_init`: `HOME`, `TMPDIR`, `XDG_*` into app storage. Library: `:memory:`, so each
  start reads the XMP the user picked rather than an older history.

### Sizes (run 44429110671)
| | arm64-v8a | x86_64 |
|---|---|---|
| APK (debug, dex compressed) | 32.2 MB (32,246,615 B) | 33.4 MB (33,358,301 B) |
| of which `assets/engine/engine.zip` | 21.9 MB | 23.0 MB |
| of which app code and libdtjni | about 10.3 MB | about 10.3 MB |
| engine tree, unpacked into app storage | about 61.3 MB (tree incl. a 24 KB darktable-cli that is not packed) | 63.46 MB, 991 files (measured on the emulator; `du` 67.8 MB on disk) |

The arm64 unpacked size is the size of the engine tree as built; it was not measured on a device.

### What the emulator test showed (run 44429110671, API 33 x86_64, 4 cores, 6 GB)
`EngineOnDeviceTest` (four tests, all passed, 24.9 s in total) on `mire1.cr2` with `0001-exposure`:
- **S4: loading from app storage works on Android 13 (emulator).** 42 of 42 libraries loaded by path, 152 of
  152 plugin files accepted by `dlopen()`, darktable loaded 97 of 97 processing modules, 8 of 8 formats and 6 of
  6 storages (111 of 111). Fresh unpack 4.2 s, `dtb_init` 2.4 s.
- Open 454 ms, first preview (1024 x 681) 853 ms, preview after an exposure change 252 ms, a 1080 x 2400 region
  at 100 % 279 ms first and 217 ms after a change.
- Full-size JPEG export 2.9 s. **The export matches darktable-cli on Linux**: same size (3908 x 2602), PSNR
  75.7 dB, max difference 26 levels in 0.064 % of the values (both JPEG quality 95; clang and newer libraries on
  Android, gcc and Ubuntu's libraries on Linux, as in S1).
- XMP written by the bridge passes the version guard; the RAW copy's MD5 is unchanged.
- No native crash in logcat.
- Benchmark inside the test (1 and 4 threads; ms):

| | 1 thread | 4 threads |
|---|---|---|
| open | 114 | 122 |
| preview 1024 px, first | 291 | 260 |
| preview after exposure change | 187 | 85 |
| screen region, first | 319 | 136 |
| screen region after exposure change | 296 | 145 |
| full-size render after exposure change | 1155 | 1390 |
| export JPEG full size | 1812 | 979 |

The full-size render being slower with 4 threads than with 1 is unexplained; it is one run on a shared cloud
runner and the emulator's cores are virtual. It must be measured on the phone.

Engine log lines worth knowing: darktable derives its locale and share directories from the executable, which
in an app is `/system/bin/app_process64`, so it logs that `/system/bin/../share/locale` and `/system/bin/../share`
do not exist. Data, modules and the lens database come from `--datadir`/`--moduledir` and are unaffected; the
share directory is only used for `XDG_DATA_DIRS` and desktop shortcuts.

### Linux bridge tests (S3) in CI
Run 44429110671, job `linux`: 67 checks pass; the only failures are T5 (region = crop of the full render) with
the same numbers as in S3 (cr2 max 182 / 75 / 64, raf 40 / 12 / 17, arw 21). The job fails on any other failure.

### What could not be checked without the real phone
- Any arm64 code. The arm64 APK is built and inspected (sizes, contents) but never executed.
- Speed on the Snapdragon 865 (big and little cores; 1, 4 and 8 threads), memory pressure with a 40 MP RAF,
  how long the first unpack takes on the phone's storage.
- Loading from app storage on the vendor's Android 13 build with its SELinux policy (the emulator is Google's).
- The system file picker with the phone's file manager and cloud providers; export into the gallery; the share
  sheet.
- That desktop darktable reads the XMP the app writes (the hand check in section 4 above).

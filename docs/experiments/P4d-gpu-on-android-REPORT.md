# P4d: make darktable find the phone's GPU (OpenCL on Android)

Branch `p4d-gpu-android`. The phone's answer is still to come: nothing here has run on an Adreno GPU. What this package
does is make the next paste from the phone say exactly why darktable did not use the GPU. If the cause is the library
loading, the build should already work around it.

**What the owner's earlier message already tells us.** The text was "darktable found no GPU it can use (OpenCL library
libOpenCL)". `libOpenCL` is the name darktable stores when its own `dt_dlopencl_init` opened the library
(`common/dlopencl.c` `ocllib[0]`; `common/dynload.c` `dt_gmodule_open` keeps names that start with `lib`, and GLib then
adds `.so`). So the library was most likely **loaded**. Then one of these happened: it reported no platform, the platform
was filtered out, darktable rejected the device, or the kernels did not build. The new diagnostics tell these cases apart.

## 1. Brief items

| Item | Status |
|---|---|
| 1. Diagnostics in one tap | **done, phone not tested.** Settings › processing › "copy GPU details", under the GPU switch. It is enabled after the GPU has been switched on, both from the switch and at an app start with the setting on. The copied text has these parts: (a) the app's `System.loadLibrary("OpenCL")` and `("OpenCL_adreno")` calls, each with its exception text; (b) every line naming OpenCL in `/system/etc`, `/vendor/etc`, `/product/etc` and `/system_ext/etc` `public.libraries*.txt`, or "not readable"; (c) the OpenCL files mapped into the process (`/proc/self/maps`, paths only); (d) every library the engine tried, in order, with `dlopen`/`dlerror`, whether `clGetPlatformIDs` exists, its status and the platform count; (e) the library handed to darktable and the one darktable opened; (f) every platform and device the library reports (name, vendor, version, driver, OpenCL C version, type, available, image support, little endian, memory, largest buffer, extensions), whatever darktable decided; (g) darktable's state; (h) **darktable's decisions**: the lines of its log that say what it rejected and why (`*** … ***`, `NOT active`, `not FULL_PROFILE`, build failures with the compiler messages after them); (i) darktable's own OpenCL start-up log (`-d opencl -d verbose`), the first 24 000 and last 16 000 characters. There is no phone brand or model and no personal data. The line under the switch now gives the reason too (for example "darktable did not use the device: insufficient global memory …"). |
| 2. Fixes for the likely causes | **done (library and platform); a device rejection stops at a diagnostic, as the brief says.** *Library:* before darktable starts, the bridge tries these in order: the names the app's `System.loadLibrary` loaded, then darktablerc's `opencl_library`, `libOpenCL.so`, `libOpenCL.so.1`, `libOpenCL_adreno.so`, `/vendor/lib64/libOpenCL.so`, `/vendor/lib64/libOpenCL_adreno.so`, `/system/vendor/lib64/libOpenCL.so` and `/vendor/lib/libOpenCL.so`. It asks each for its platforms. The first with a platform (otherwise the first with the symbol) becomes darktable's `opencl_library`. So if `libOpenCL.so` loads but reports no platform and `libOpenCL_adreno.so` works, darktable gets the second. Upstream is not changed. *Manifest:* checked in the built APK, not only in the source. The merged manifest has both `uses-native-library` entries inside `<application>`, `required=false`, with targetSdk 34 and minSdk 29 (p2b 45615733474, "Check and measure the APKs"). That check now fails the step if they are missing. *Platform key:* `clplatform_other=true` is still set at the switch-on (P4b). The Qualcomm platform has no key of its own in darktable (`common/opencl.c dt_opencl_init`: unknown keys fall back to `clplatform_other`). If the log shows `NOT active` anyway, the paste will say so. |
| 3. Tests | **done.** JVM `P4dGpuDetailsTest` (darkroom): candidate order with a fake loader; only what loaded is preferred; public.libraries and maps filtering; the reason line for each case (not opened, no platform, platform not active, device rejected, kernels not built); the text has every section; a long log keeps its start and end; `GpuCheck` asks the engine for the app's libraries **before** `openclSet(true)`. Bridge test **C4d** (`engine/tests/dtb_test_p4d.c`): with PoCL, the runner's library is given by absolute path after a path that does not exist (details below). All earlier tests are unchanged and pass. |

## 2. How to reproduce

```
gh workflow run p2a-app.yml          --repo nannolo45/darktable-android --ref p4d-gpu-android   # JVM tests incl. P4dGpuDetailsTest
gh workflow run p2b-real-engine.yml  --repo nannolo45/darktable-android --ref p4d-gpu-android   # APKs, manifest check, emulator
gh workflow run g0-app.yml           --repo nannolo45/darktable-android --ref p4d-gpu-android   # bridge tests incl. C4d (-f tests="C36 C4d" for only these)
```
Logs: `ci-logs` branch, `runs/<run id>-a1/g0-linux/p4d_opencl.txt`, `C4d.log` (darktable's log on stdout), `bridge-tests-summary.txt`.

## 3. Results (CI runner, x86_64, PoCL on the CPU: not phone numbers)

- **p2a-app 45609366440**: success (JVM tests, screenshots, rule check).
- **p2b-real-engine 45609371168**: success (APKs, emulator contract and scenario tests). **45615733474** (after the
  manifest check was moved, see section 5): success; its APK step printed the merged manifest quoted in item 2.
- **g0-app 45609377024**: success, **487 PASS, 0 FAIL**. This run used the old test id "C37", which other branches already use for other tests, so it was renamed "C4d" (see the final run below). C4d result:
  - first try `/nonexistent/libOpenCL.so` (from app): not opened, dlerror "cannot open shared object file: No such file or directory";
  - second try `/lib/x86_64-linux-gnu/libOpenCL.so.1`: opened, status 0, 1 platform. It was handed to darktable, and darktable opened exactly that file (its log: "opencl library '/lib/x86_64-linux-gnu/libOpenCL.so.1' found … preference …");
  - reported: platform Portable Computing Language, device cpu-haswell (type CPU, image support, 13 935 MB). OpenCL running;
  - darktable's log was caught: 34 441 bytes, ending with "[opencl_init] FINALLY". It is also still on stdout (C4d.log);
  - `dtb_opencl_library` after the start: refused with DTB_E_STATE.
  - C36 (P4b) is unchanged: PoCL running, PSNR 95.77 dB, export on the GPU.
- **g0-app 45623559755** (final commit, `tests="C36 C4d"`): success, PASS C36, PASS **C4d**, PASS safety (RAW files unchanged).

## 4. Upstream notes

- `common/dlopencl.c` `dt_dlopencl_init` / `common/dynload.c` `dt_gmodule_open`: a name without `/` goes through
  `g_module_build_path`, which leaves names that start with `lib` alone. That is why darktable reported "libOpenCL" and
  not "libOpenCL.so".
- `common/opencl.c` `dt_opencl_init` keeps `cl->dlocl` open when no device is usable (it is closed only in
  `dt_opencl_cleanup`). The bridge reads the platforms through darktable's own symbol table afterwards.
- Possible rejections an Adreno could hit, all in `_opencl_device_init`: global memory < 800 MB; no image support; device
  version 1.0/1.1; `clplatform_*` not active; platform not `FULL_PROFILE` (`dt_opencl_init`); and any kernel program that
  fails to build ("failed to compile program"), which drops the whole device. If the paste shows one of these, the smallest
  option-based change will be proposed then. No upstream change is made here.
- darktable logs with `printf` (`dt_print_nts_ext`). The bridge points stdout at a temporary file (`dup2`, GLib file
  calls) only while `dt_opencl_init` runs, then writes the text back to stdout.

## 5. Open questions for the reviewer

1. **The APK check in p2b could not fail the step.** The block runs as `{ … } | tee` without `pipefail`. The permission
   allow-list (`INTERNET|ACCESS_NETWORK_STATE|RECEIVE_BOOT_COMPLETED`) already stops it on main, because the APK now also
   asks for `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`. Every check after that line,
   including the signature print, never runs, yet the step passes. I moved the manifest check before the permissions and
   made only that check fail the step. I did not touch the allow-list: should those three permissions be allowed (they
   came from another package), and should the block get `pipefail`?
2. **Test ids collide between branches** (C37 to C39 are already used by other branches). The new test is called "C4d".
   Should ids be given out centrally?
3. The JSON from `dtb_opencl_diagnostics` can be up to about 300 KiB (log plus extensions), and the copied text up to
   about 60 KB. Is that acceptable for a paste?

## 6. What the owner should do

1. Install the APK from this branch (release file from the p2b run).
2. Library › menu › "processing…". If "use the GPU" is on, switch it off.
3. Switch "use the GPU" **on** and wait until the line under the switch changes.
4. Tap **"copy GPU details"** and paste the whole text. If the line under the switch says "GPU on: …", paste that too.
   Copying sends nothing anywhere.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoep891vjXMfqGv9Mc

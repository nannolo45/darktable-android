# P4b: speed (OpenCL opt-in, benchmark, cache and resource tuning, lighter work during a gesture)

Branch `p4b-speed`. Brief: `docs/briefs/P4b-speed.md`. No file under `upstream/` changed. The app reaches the engine only
through `dtbridge.h` (four new calls, engine-api.md 11). The LPL contract (PLAN.md 4) and the repository layout are
unchanged.

## 0. Said first

- **No phone numbers exist yet.** The GPU path was run on the CI runner through PoCL (OpenCL on the runner's CPU), not
  on an Adreno. Every time below is from x86_64: the Linux CI runner (4 cores), the API 33 x86_64 emulator (4 cores,
  5.9 GB) or the JVM. The phone checklist is in section 6.
- **Memory choices are reasoned, not measured on the phone.** The 12 GB values (section 3) come from darktable's model
  and the P4a numbers. What the emulator could show is in section 3: going back to a photo, cache hits, memory held.
- **Deliverable 4 (lighter work during a gesture): no code change.** Slider drags have been merged since P2b (a
  conflated channel; one small drag picture at a time; the full picture when the finger lifts). The measurement shows
  the gain is already there (60 steps gave 8 drag pictures). I found nothing more to skip without changing results.
  This package only adds the test that the last value is never dropped.
- **Two fixes made during the work:**
  - the first decode counter read darktable's `stats_misses`, which is never counted for the bridge's blocking
    reads (section 4);
  - diffuse's preset "lens deblur | medium" was not found by name on the emulator. The heavy edit now sets that
    preset's parameters directly.

## 0b. After the review (25 Nov 2026)

The reviewer accepted the 40 dB threshold, fetching the test RAW on first use, and `clplatform_other`. Two fixes were
asked for and are made:
- **Compiled kernels outside the cache folder.** darktable puts them under its cache folder
  (`common/opencl.c _opencl_init_device`: `dt_loc_get_user_cache_dir`), and only reads that path while
  `dt_opencl_init` runs. The new start-up option `opencl_kernel_dir` points `darktable.cachedir` at a folder of its own
  for that one call. The app passes `noBackupFilesDir/opencl-kernels`. C36 checks that the `cached_v…` folder is there.
- **Exports are counted.** `dtb_export` counts as a render (cpu / gpu / fallback). `JniEngine` checks for OpenCL
  trouble after an export as after a render, with the same crash-log entry and the same switch-off when darktable stops
  OpenCL. C36 checks that an export with the GPU on is counted.

Final CI runs on `a2d4e654d`, all green:
- **g0-app 45588492571**: 486 PASS, 0 FAIL. In C36 PoCL started in 70.4 s and the render ran on the GPU with PSNR 95.8 dB. The kernels were in `…/work/kernels/cached_v8_stdkernels_for_…`, and the export was counted as "gpu".
- **p2b-real-engine 45588499292**: passed.
- **p2a-app 45588505271**: passed.

The runs in section 3 are from before the review.

## 1. Item by item

| Brief item | State |
|---|---|
| 1. OpenCL opt-in: built in, manifest, off by default, device check, comparison with the CPU (PSNR threshold), fallback to the CPU logged, darktable's defaults | **done, phone not tested.** The engine is built with `USE_OPENCL=ON` (Android and Linux), and the kernels ship in `share/darktable/kernels`. darktable always starts with `--disable-opencl`; `dtb_opencl_set(1)` runs `dt_opencl_init` once, later. The manifest declares `libOpenCL.so` and `libOpenCL_adreno.so` (`required="false"`). Settings › processing › "use the GPU (OpenCL)" is off by default. When it is switched on, `GpuCheck` checks the device, then renders a CPU and a GPU test picture (1024 px, public test RAW). The GPU stays on only if the render ran on the GPU with no OpenCL error and the PSNR is ≥ **40 dB**. Otherwise it switches back off and says why (screen and crash log). darktable already finishes a render on the CPU after an OpenCL error. The bridge counts such renders, and the app logs each one in the crash log (kind "GPU (OpenCL)"). When darktable stops OpenCL after repeated errors, the app also switches the setting off. darktable's scheduling profile and device priorities are its defaults. One change, `clplatform_other=true`, is set when the GPU is switched on (section 4). |
| 2. Benchmark the owner can run | **done.** Settings › processing › "measure speed". It fetches darktable-tests' `images/mire1.cr2` once (pinned commit, size and SHA-256 checked). It renders the default edit and a heavier edit (diffuse with "lens deblur \| medium" parameters, local contrast, color calibration, sigmoid). Views: whole photo fitted into 2400 × 1080, and a 1080 × 1080 region at 100 %. Each runs on the CPU and, when the GPU is on, on the GPU. Each combination gets a first render after opening plus 3 renders after an exposure change. "copy" puts the text on the clipboard; nothing is uploaded. |
| 3. Cache and tiling tuning: resource level by RAM, pipeline cache, image cache, named constants with reasons, "processing memory: normal / more / less", emulator numbers | **done** (`ProcessingResources`): three phone classes by total memory × three choices. Each sets darktable's resource level and values, the two bridge pipelines' cache fractions and the number of decoded photos kept. The choice applies at the next start. Measured on the runner and the emulator (section 3). Tiling: darktable decides it against the "available" memory set here; no separate tiling setting was changed. |
| 4. Lighter work during a gesture, only where measurement shows a gain | **no change**; measured, see section 0 and section 3 |
| 5. Tests | **done**: OpenCL off path unchanged (all bridge tests and every JVM/emulator test green); comparison logic with a fake GPU (agree / differ / no device / built without / error mid-render / darktable's fallback); benchmark sequence and text; screen in three skins with copy; resource choice by RAM; coalescing keeps the last value; bridge C35 and C36; emulator P4bScenarioTest a–d |

## 2. How to reproduce

```
# JVM (build workspace)
cd app
ANDROID_HOME=<sdk> gradle :darkroom:testDebugUnitTest --tests '*P4b*'    # P4bSpeedTest, P4bCoalescingTest, P4bProcessingScreenshotTest
gradle :engine-api:test
# CI
gh workflow run g0-app.yml           --repo nannolo45/darktable-android --ref p4b-speed                   # bridge C35, C36 (all tests)
gh workflow run g0-app.yml           --repo nannolo45/darktable-android --ref p4b-speed -f tests="C35 C36"   # only those
gh workflow run p2b-real-engine.yml  --repo nannolo45/darktable-android --ref p4b-speed                   # JVM + emulator P4bScenarioTest
gh workflow run p2a-app.yml          --repo nannolo45/darktable-android --ref p4b-speed                   # JVM, screenshots
```

Logs: `ci-logs` branch, `runs/<run id>-a1/`:
- g0: `g0-linux/bridge-tests-summary.txt`, `p4b_opencl.txt`, `p4b_resources_{3,1}_<camera>.txt`, `clinfo.txt`, `cr2/C36.log` (darktable's `-d opencl` output);
- p2b: `p2b-emulator/p2b-test/p4b-results.txt` and `p4b-speed.txt`;
- p2a: `p2a-app/screenshots/*p4b*`.

## 3. Results with numbers

**CI runs:**
- **g0-app 45575858408** (commit `ea5999ec3`): bridge suite on all three cameras, **486 PASS, 0 FAIL**, with darktable now built with OpenCL. Every darktable-cli reference runs with `--disable-opencl`. The g0 emulator job passed too.
- **g0-app 45585667090** (`759e3bb0f`, only C35 C36, with the runner's OpenCL set-up recorded): **all PASS**.
- **p2b-real-engine 45576095250** (`ea5999ec3`): JVM 781 tests in 105 classes, 0 failed. Emulator **123 instrumented tests, 0 failed**, P4bScenarioTest a–d included.
- **p2a-app 45576101393** (`ea5999ec3`): passed.

Earlier runs:
- g0 45564621165 was cancelled after 83 minutes in the bridge tests. No log came back, and the cause is unknown. Before the re-run, C36 got a 40-minute bound and the darktable-cli references got `--disable-opencl`; the re-run took its usual time.
- p2b 45566360272 and p2a 45566364090 passed on `7ccebb33f`, before the decode-counter fix.

The commits after `759e3bb0f` change only this report.

**C36, OpenCL through PoCL on the Linux runner (x86_64, not phone numbers, `p4b_opencl.txt`):**

| Step | Result |
|---|---|
| before the switch | `started false`, no library opened |
| `dtb_opencl_set(1)` | 59.1 s (darktable built its kernels with PoCL), 1 device, library `libOpenCL.so.1` |
| 1024 px preview on the OpenCL device | ran as `gpu`, **PSNR 95.8 dB** against the CPU picture, largest difference 1 of 255 |
| speed | CPU 0.091 s; OpenCL (PoCL on the same CPU, first use of each kernel) 1.670 s |
| switched off again | the CPU picture, byte for byte |

In the first full run (45575858408), PoCL found no device. The workflow now also installs `ocl-icd-libopencl1` and
`clinfo` and records `clinfo -l`.

**C35, resources and switching photos (Linux runner, x86_64, 4 cores).** Wrong options are refused before darktable
starts (unknown member, bad level, three numbers, wrong array, negative count, wrong type). Values `384 8 32 700` reach
darktable: available memory = total / 1024 × 384. Pipeline fractions are 8 and 32. The second preview is answered from
the pipeline cache. With OpenCL not allowed, no library is opened and `dtb_opencl_set` gives STATE. Times for going
back to photo A after photo B, with the same pictures (byte for byte) in both runs:

| camera | 3 decoded photos kept: back to A (s), RAW decodes | 1 kept: back to A (s), decodes |
|---|---|---|
| cr2 (mire1) | 0.183, 0 | 0.295, 1 |
| raf (X-Trans) | 0.494, 0 | 1.031, 1 |
| arw | 0.501, 0 | 0.581, 1 |

**Emulator (x86_64, API 33, 5.9 GB, 4 cores, `p4b-results.txt`):**
- **Memory plan.** The emulator counts as a "6 to 8 GB phone": level default `256 8 32 700`. darktable counts 4899 MB and plans with 1224 MB. The pipeline caches may hold up to 153 + 38 MB (full + preview); 2 decoded photos are kept.
- **Series.** Photo A open + preview + region 469 ms; the same preview again 7 ms (pipeline cache); photo B 308 ms; back to A 333 ms with 0 decodes. Memory then: pipelines 114 + 78 MB, image cache 39 + 40 MB in 2 of 2 buffers, engine RSS 590 MB, app PSS 275 MB.
- **GPU on.** The emulator has no OpenCL library. The setting went back off with "GPU off: no OpenCL library was found on this phone", the reason was in the crash log, and renders afterwards ran on the CPU.
- **Measure speed** (`p4b-speed.txt`, CPU, whole run 61 s; the default edit was the first render after opening):

| edit, view | first render after opening (ms) | 3 renders after an exposure change (ms) | median |
|---|---|---|---|
| default, fit 2400×1080 | 425 | 200 194 178 | 194 |
| default, 100 % 1080×1080 | 199 | 190 140 141 | 141 |
| heavy, fit | 4817 | 4826 4837 4771 | 4826 |
| heavy, 100 % | 9787 | 9763 9931 9738 | 9763 |

The heavy edit's time is almost all diffuse or sharpen (16 iterations). It sits after exposure, so every slider move
recomputes it.

**Gesture (JVM, fake engine with 90 ms per drag picture, the delay gate G0 measured; local run, not phone numbers):** 60
drag steps 16 ms apart gave 8 drag pictures, then the full picture on release (1048 ms from the first step). The
exposure ended at the last value (2.0) and the drag made one history item.

**Memory plans** (named constants with reasons in `ProcessingResources.kt`; desktop's "default" is `512 8 128 700`, its
main pipeline cache 1/8, its preview 1/32, and decoded photos kept = worker threads + 1, which is 8 on an 8 GB / 4-thread
machine):

| total memory | normal | more | less |
|---|---|---|---|
| ≥ 9 GiB (12 GB phones) | default `384 8 32 700`, caches 1/8 + 1/32, 3 kept | `512 8 64 700`, 1/8 + 1/32, 4 kept | `256 8 32 700`, 1/16 + 1/64, 2 kept |
| ≥ 5.5 GiB (6–8 GB) | default `256 8 32 700`, 1/8 + 1/32, 2 kept | `384 8 32 700`, 1/8 + 1/32, 3 kept | small `160 4 16 400`, 1/16 + 1/64, 2 kept |
| smaller | small `128 4 16 400`, 1/16 + 1/64, 2 kept | small `192 4 32 400`, 1/8 + 1/32, 2 kept | small `96 4 16 400`, 1/16 + 1/64, 1 kept |

On a 12 GB phone (about 11.2 GiB counted), "normal" means darktable plans with about 4.2 GB. The full pipeline may then
cache about 540 MB, the preview pipeline about 135 MB, and 3 decoded photos stay in memory (about 60 to 100 MB each for
24 MP, P4a).

## 4. Upstream surprises (file, function)

- **`common/darktable.c` `dt_init`:** without an interface it calls `dt_opencl_init` synchronously, which opens the
  library and builds every kernel at every start. Hence `--disable-opencl` always, and a late `dt_opencl_init` from
  `dtb_opencl_set`. That is safe because modules only record kernel names at load (`common/opencl.c`
  `dt_opencl_create_kernel`), and kernels are made per device on first use (`_check_kernel`).
- **`common/opencl.c` `dt_opencl_init`:** a platform with no darktablerc key of its own is used only when
  `clplatform_other` is true, and its default is false (`data/darktableconfig.xml.in`). Qualcomm's "QUALCOMM
  Snapdragon(TM)" platform has no key, so desktop's defaults would never use the Adreno. The bridge sets
  `clplatform_other=true` when the GPU is switched on. This is the one deliberate difference from desktop's OpenCL
  defaults. It is written to the app's darktablerc, like `opencl_library` when a vendor path was needed.
- **`common/mipmap_cache.c` `dt_mipmap_cache_get_with_caller`:** `stats_requests` and `stats_misses` are counted only
  for best-effort gets. Blocking gets (the bridge's pipelines) count `stats_fetches` only. The bridge reports decodes
  from `stats_fetches`.
- **`common/mipmap_cache.c` `dt_mipmap_cache_init` and `common/darktable.c` `dt_worker_threads`:** the full and f
  caches keep worker threads + 1 decoded inputs, which is 8 on a machine with ≥ 7.5 GB and ≥ 4 threads. That explains
  the P4a finding that earlier photos stay in memory. The bridge now sets the quota (`inputs_kept`).
- **`develop/pixelpipe_hb.c` `dt_dev_pixelpipe_init`:** pipeline cache fraction 8 (available memory / 8). On a 12 GB
  phone with desktop's default level, each of the bridge's two pipelines could cache about 700 MB. The bridge sets 8
  for the full pipe and desktop's preview value of 32 for the preview pipe.

## 5. Open questions for the reviewer

1. **PSNR threshold of 40 dB** (`GpuCheck.PSNR_MIN_DB`) for "the pictures agree": PoCL gave 95.8 dB. Is 40 too lenient or too strict for Adreno's fast maths? The phone's first run will tell.
2. **The test photo is fetched from GitHub** (raw.githubusercontent.com, pinned commit, 16 MB) when the owner first taps "measure speed" or switches the GPU on. The alternative is to carry it in the APK (+16 MB). Which do you prefer?
3. ~~Kernels in the cache folder~~: moved to the app's no-backup files after the review (section 0b).
4. ~~Exports not counted~~: fixed after the review (section 0b).
5. **The 12 GB "normal" values** (384/1024 to plan with, 3 decoded photos) are a choice. If the crash log shows "stopped by Android: low memory" on the phone, "less" is the first step.
6. **Diffuse's built-in preset was not found by name through `dtb_preset_apply` on the emulator** ("diffuse has no preset 'lens deblur | medium'"; p2b run 45566360272, `p4b-speed.txt`). Not investigated; left for the leftovers package. What I found: the name is the one in the pinned `iop/diffuse.c` `init_presets` (`dt_gui_presets_add_generic(_("lens deblur | medium"), …)`). `develop/imageop.c` deletes and re-adds built-in presets for modules with `pref_based_presets` (the workflow-dependent ones). So the presets table the bridge reads may not hold this preset, or may hold it under another name. A first step: list `dtb_presets("diffuse")` on the emulator. The benchmark sets the preset's parameters directly, so it does not depend on this.

## 6. Owner's phone checklist

1. Install the APK from this branch. Library › menu › "processing…".
2. With "use the GPU" **off**, tap "measure speed". It takes a few minutes; the first time it also fetches the 16 MB test photo. Tap "copy" and paste the text.
3. Switch "use the GPU" **on** and wait (the first time can take minutes while darktable prepares its GPU programs). Paste the line under the switch: either "GPU on: … PSNR … dB" or "GPU off: <reason>".
4. If it stayed on: "measure speed" again, then "copy" and paste.
5. Edit a few photos for 10 minutes with the GPU on. Then About › crash log: paste any "GPU (OpenCL)" entries (or say there were none).
6. Optional: "processing memory: more", restart the app, run "measure speed", switch to two or three other apps and back. Paste the times and any "stopped by Android: low memory" entry.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoep891vjXMfqGv9Mc

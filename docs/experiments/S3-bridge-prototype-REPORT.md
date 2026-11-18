# Pull request: S3 bridge prototype (branch `s3-bridge-prototype` → `main`)

**First, the failure: T5 fails on all three test images.** A 512 × 512 region rendered at scale 1 is not equal to the same crop of the full render: max difference 182 / 40 / 21 levels (cr2 / raf / arw), mean ≤ 0.30, 14 of 18 comparisons. The causes are named in upstream:
- demosaic's `modify_roi_in()` asks for no border around the region (edge band of 3 px Bayer, 12 px X-Trans); a 16 px render margin removes it in most cases.
- spektrafilm processes a smaller output window differently; mechanism not verified.

T8 passes, but its worst cancel latency is 2.8 s on the 26 MP X-Trans file. **Zero upstream patches held.** Details and verdict: `docs/experiments/S3-bridge-prototype.md`.

All measurements: x86_64, 2 cores, build workspace. Not phone numbers.

## 1. Brief items

| Item | Status |
|---|---|
| 1. Pin upstream at 04477d3, document needed darktable submodules | **done**: `upstream/darktable` (shallow); write-up section 1 |
| 2. Linux build with the listed options, cmake command recorded, darktable-cli exports a test image | **done**: `engine/tests/build-darktable-linux.sh`; 7 min 20 s |
| 3. Bridge: `dtbridge.h`, `src/*.c`, out-of-tree `CMakeLists.txt`, every listed function | **done**: plus `dtb_mask_add_circle` for T9 and small helpers (write-up section 8) |
| 4. T1 render parity | **pass**: bit-identical on 3 images |
| T2 parameter change | **pass**: history +1, max 0 vs cli |
| T3 instances | **pass**: fresh session and cli reproduce, names/priorities right |
| T4 history | **pass**: after fixing `history_end` after compress |
| T5 region | **fail**: see above |
| T6 version guard | **pass**: newer version and unknown op reported, all writes refused, files unchanged |
| T7 timing | **done**: measured; the cache does not help full-res renders of 20–26 MP files |
| T8 cancel | **pass, with a caveat**: always cancels; latency up to 2.8 s at 26 MP full resolution |
| T9 drawn mask (stretch) | **pass**: local effect, cli reproduces (max 0) |
| 5. Write-up with verdict | **done**: verdict **go, with conditions** |
| Extra: X1 (preview, JPEG export, move, delete) | **pass** for move/delete/export; the preview is measured only (PSNR 26–32 dB vs cli's downscaled export) |

## 2. How to reproduce

See section 10 of `docs/experiments/S3-bridge-prototype.md`. In short:

```
engine/tests/build-darktable-linux.sh <dt-build> <dt-prefix>
engine/tests/fetch-test-images.sh <tests>
cmake -S engine -B <bridge-build> -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DDT_SOURCE_DIR=$PWD/upstream/darktable -DDT_BUILD_DIR=<dt-build> -DDT_INSTALL_PREFIX=<dt-prefix>
ninja -C <bridge-build>
engine/tests/run_tests.sh <bridge-build> <dt-prefix> <tests> <out>
```

The committed logs of the runs behind this report are in `docs/experiments/S3-results/`.

## 3. Results

| | cr2 (10 MP) | raf (26 MP X-Trans) | arw (20 MP) |
|---|---|---|---|
| T1 max / mean / PSNR | 0 / 0 / inf | 0 / 0 / inf | 0 / 0 / inf |
| T5 region max (3 regions) | 182 / 75 / 64 | 40 / 12 / 17 | 21 / 0 / 0 |
| T5 with 16 px margin | 0 / 0 / 0 | 0 / 10 / 0 | 21 / 0 / 0 |
| open, s | 0.20 | 0.95 | 0.13 |
| first full render, s | 0.90 | 11.12 | 3.89 |
| full render after late change, s | 0.31 | 12.96 | 4.47 |
| 1920 × 1080 region after late change, s | 0.34 | 1.38 | 1.79 |
| preview 1024 px first / after late change, s | 0.16 / 0.05 | 0.64 / 0.08 | 0.22 / 0.09 |
| T8 worst cancel latency, s | 0.20 | 2.78 | 0.47 |

Bugs found and fixed in the bridge by the tests, each in its own commit:
- `history_end` after compress.
- Invalid JSON for NaN parameters.
- Two windows in which a cancel was lost.

Two test mistakes found and fixed, both explained in the commits and in write-up section 8:
- T6's "newer" XMP was not newer than the engine.
- T8 could hit a cached render.

## 4. Surprises in upstream (file / function)

- No installed headers; struct layout depends on compile definitions → the bridge reads darktable's exact flags from its build (`engine/CMakeLists.txt`).
- `develop/develop.c` `dt_dev_read_history_ext`: a newer module version silently falls back to default parameters; an unknown operation is dropped.
- `develop/develop.c` `dt_dev_add_history_item` does nothing without the GUI; `dt_dev_history_truncate` / `dt_dev_reload_history_items` dereference the darkroom pipes; `dt_dev_module_remove` keeps history items without the GUI.
- `common/history.c` `dt_history_compress_on_image` leaves `history_end` stale.
- `iop/demosaic.c` `modify_roi_in` (no margin); `iop/spektrafilm.c` `modify_roi_in` / `_sf_work_window` (T5).
- `common/iop_profile.h` includes `<CL/cl.h>` unconditionally; `common/metadata_export.h` has no include guard.
- `iop/temperature.c` keeps NaN in an unused parameter.
- Upstream ships `src/mcp/dt_bridge.c` (darktable-mcp), a headless driver much like this bridge.

## 5. Open questions for the reviewer

1. **T5:** accept the render-with-margin approach for Phase 1 and keep T5 as a measurement? Or require region = crop, which would mean full renders or upstream changes (demosaic margin, spektrafilm window)?
2. Should the spektrafilm region difference be reported upstream once someone confirms the mechanism?
3. **Interactive renders:** the cache did not help full-resolution 20–26 MP renders at `resourcelevel=default`, and cancel latency equals one module step (filmic rgb 3.1 s, lens 2.6 s at 26 MP here). Is screen-sized region rendering, as PLAN.md 3.8 already plans, enough, or should Phase 1 test other resource levels?
4. **Desktop read-back:** someone with desktop darktable 5.7 should open the XMPs that `run_tests.sh` writes (`param.xmp`, `instances.xmp`, `mask.xmp`) once. Here `darktable-cli` was the stand-in.
5. The bridge loads `data.db` (presets and styles) like `darktable-cli`. Is that the wanted default for the app?
6. Should the list of desktop logic re-implemented in the bridge (write-up section 6) go into `docs/UPDATE.md` as a per-bump checklist?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

# P4e: darktable's OpenCL kernels on the Adreno compiler (report)

Branch `p4e-adreno-kernels`. Measurements from the build workspace and CI are x86_64 numbers (PoCL on the CPU), not
phone numbers. A quick untested APK of the first commit (dt-android-8b50144f37fa, apk-only) went to the owner first,
at his request; the phone result (supervisor, 25 Nov): darktable initialised OpenCL on the Adreno 650, every kernel
compiled, GPU vs CPU test picture PSNR 91.8 dB, largest difference 1/255. The same phone then measured the GPU 4 to 26
times slower than the CPU; the second half of this round (section 6) adds what is needed to see why.

## 1. Brief items

| Item | State |
|---|---|
| 1. Find every vector subscript (tool + output) | done: `tools/check-vector-subscripts.py`, `docs/experiments/P4e-vector-subscripts.txt` |
| 2. Patch them, `patches/`, upstream text | done: `patches/0001-basic-cl-no-vector-subscripts.patch`; upstream text in section 4 |
| 2b. Applied by the build and by `tools/bump-upstream` | done: `tools/apply-patches.sh` from `tools/fetch-darktable.sh` (every engine build, Android and Linux); new `tools/bump-upstream`; `patches/` is part of ENGINE_TAG and of the Linux build cache keys |
| 3. Nothing changed: PoCL compile, C36, before/after render, strict compile | see section 3 (CI ids) |
| 4. Other Adreno pitfalls | listed in section 5; nothing else patched |
| 5. GPU details keep showing a failing kernel's build log | unchanged from P4d (darktable's init log is in "copy GPU details") |
| Supervisor, round 2: per-module timing, GPU speed check | done in code, see section 6; unit tests and C36 cover it, phone numbers pending |

## 2. How to reproduce

```
# the check, on a darktable source tree at the pinned commit (clang 18)
python3 -I tools/check-vector-subscripts.py <darktable>/data/kernels clang-18
# patches applied the way the build does it
tools/fetch-darktable.sh /tmp/dt        # fetches the pin, checks it is clean, applies patches/
# before/after through OpenCL (needs PoCL and the Linux build of brief G0)
engine/tests/p4e_kernels_before_after.sh <bridge build> <prefix> <darktable-tests dir> /tmp/p4e
# moving to a new darktable
tools/bump-upstream <tag or commit>
```

## 3. Results

The check (clang 18, all 54 `.cl` files compile): before the patch 3 places, after it 0
(`docs/experiments/P4e-vector-subscripts.txt`):

- `data/kernels/basic.cl:1643:32` `linear[j]` (colorin_rgb)
- `data/kernels/basic.cl:1649:34` `linear[j]` (colorin_rgb, gamut clipping)
- `data/kernels/basic.cl:1649:61` `clipped[i]` (colorin_rgb, gamut clipping)

The fix: `.x/.y/.z` for the constant row index, a private `float[3]` for the loop index; every component is summed in
the same order (i = 0, 1, 2), `.w` stays 0.

CI (commit 0187cc6, 25 Nov 2026, all green): g0-app 45653810325 (bridge suite incl. C36 and the before/after kernel check), p2b-real-engine 45653810928 (emulator), p2a-app 45653810866 (unit tests).

## 4. Upstream issue / pull request text (not opened)

> **OpenCL: basic.cl uses `[]` on float4, which the Adreno compiler rejects**
>
> `colorin_rgb` in `data/kernels/basic.cl` indexes `float4` vectors with `[]` (`linear[j] += cmat[3*j+i] * cam[i]`,
> `clipped[i]`). Subscripting a vector is a Clang extension, not OpenCL C (OpenCL C 2.0, 6.1.7 allows only
> component and swizzle access), so a strict compiler refuses it. Qualcomm's (Adreno 650, compiler E031.37.12.07)
> stops with `error: Attempt to use subscript to obtain element of 'float4'`, `basic.cl` fails to build and darktable
> drops the device (`[opencl_init] no suitable devices found`). A clang AST check (`ArraySubscriptExpr` whose base is
> an `ext_vector_type`) over all of `data/kernels/*.cl` finds only these three places.
>
> The patch writes the 3x3 product with `.x/.y/.z` and copies the clamped vector into a `float[3]`. Each component is
> summed in the same order as before, so results do not change (PoCL: before/after renders identical). Perhaps worth
> adding the same AST check to `testcompile_opencl_kernels`, since clang accepts the extension silently.

## 5. Other possible Adreno pitfalls (not patched)

No `double`, no extension pragmas. Valid OpenCL C that a strict driver might still mind: `#pragma OPENCL FP_CONTRACT OFF`
(demosaic_amaze.cl, spektrafilm.cl), `#pragma unroll` (diffuse.cl), `#pragma once` (bilinear.h, colorspace.h). The
phone compiled every kernel, so none of them is a problem on the Adreno 650.

## 6. Round 2: why the GPU is slower (supervisor, 25 Nov)

- Bridge: `dtb_render_trace(arm, &json)` (dtbridge.h): the next render or export runs with darktable's `-d perf -d tiling
  -d opencl -d memory` output caught (stdout to a file for that render, as P4d does for `dt_opencl_init`), at most
  256 KiB, also written to the normal log. JNI: `jsonGet` codes 9 and 10; `DarktableEngine.renderTrace(arm)`.
- `PerfLog`: darktable's `[dev_pixelpipe] took … processed `module' on GPU|CPU [with tiling]` lines as a table (module,
  device, ms, tiled), with sums and the tiling, OpenCL-trouble and memory lines (first few).
- "measure speed" with the GPU on: one more render per case (after an exposure change, not part of the timed runs),
  traced; its table follows the times in the copied text, and "copy GPU details" carries the tables too.
- GPU check: after the timed test renders, both are repeated traced (not timed) for the tables. If the GPU's timed test
  render is slower than the CPU's, the GPU goes off: "GPU off: slower than the processor on this phone (CPU x ms, GPU
  y ms); the pictures agree (…)". The processing screen then offers "use the GPU anyway", which asks a second time
  ("yes, use the GPU" / "cancel"); the note then says "GPU on at your request, although …".
- Tests: C36 checks the trace (module lines present, armed flag used up; `RESULT render_trace`); unit tests
  `gpuSlowerThanTheCpuGoesOffAndSaysSo`, `perfLogTable`, and the existing GPU check and benchmark tests extended.

## 7. Open questions for the reviewer

- The check compares one test render each (1024 px preview from the RAW, decode included). A GPU that is faster only on
  large exports would be switched off; the user can force it on. A better rule may come out of the phone's tables.
- apk-only no longer has its kernel overlay: it uses the engine archive, which now carries the patches.

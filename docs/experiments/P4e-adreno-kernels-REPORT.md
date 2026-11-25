# P4e: darktable's OpenCL kernels on the Adreno compiler (quick round)

**Tests were skipped at the owner's request**: he asked for an APK with the fix as fast as possible.
The PoCL compile in CI, P4b's C36 GPU-vs-CPU comparison and the before/after OpenCL render check
(brief items 3 and 5) have not run yet; they come in a separate round, with the full report.

## Done in this round
- `tools/check-vector-subscripts.py`: clang 18 `-x cl -cl-std=CL2.0 -cl-fast-relaxed-math` JSON AST of every
  `data/kernels/*.cl` (include path: the kernel folder and `src/common` for `hdr-transfer.h`, as darktable's
  build copies it there), listing each `ArraySubscriptExpr` whose base has a vector type.
  Output: `docs/experiments/P4e-vector-subscripts.txt`. At darktable 04477d32 there are exactly 3 places,
  all in `basic.cl` `colorin_rgb` (lines 1643, 1649 twice), the ones in the owner's log. All 54 kernel files compiled.
- `patches/0001-basic-cl-no-vector-subscripts.patch`: `.x/.y/.z` for the matrix product, a private
  `float[3]` for the clamped vector; same summation order per component, so the same result.
- `.github/workflows/apk-only.yml`: applies `patches/*.patch` to `share/darktable/kernels` of the unpacked
  engine archive (kernels ship as source and are built on the phone, so no engine rebuild), and runs on
  pushes to this branch.

## Not done yet / open
- The engine build (`tools/build-engine-android.sh`, engine.yml, `tools/bump-upstream`) does not apply
  `patches/` yet; only apk-only does. Doing it there changes ENGINE_TAG and needs an engine rebuild.
- Quick scan for other Adreno pitfalls: no `double`, no extension pragmas; `#pragma OPENCL FP_CONTRACT OFF`
  (demosaic_amaze.cl, spektrafilm.cl), `#pragma unroll` (diffuse.cl) and `#pragma once` (bilinear.h,
  colorspace.h) are valid but could still trip a driver. Only the phone's next log will tell.

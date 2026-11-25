# Brief P4e: darktable's OpenCL kernels on the Adreno compiler

Branch `p4e-adreno-kernels` from `main`. Read CLAUDE.md (rule 1 and PLAN.md's `patches/` rules), the P4b and P4d reports, and `docs/UPDATE.md`.

## What the owner's phone showed (25 Nov 2026, build dt-android-b57e0351b61d, Adreno 650, OpenCL 2.0, compiler E031.37.12.07)
The library loads and darktable sees the Adreno device, but the device is dropped because `basic.cl` does not compile:
```
BC-src-code:1643:38: error: Attempt to use subscript to obtain element of 'float4'.
    for(int i = 0; i < 3; i++) linear[j] += cmat[3*j+i] * cam[i];
BC-src-code:1649:40/68: same, for linear[j] and clipped[i]
[dt_opencl_device_init] failed to compile program `basic.cl'
[opencl_init] no suitable devices found.
```
Subscripting a vector with `[]` is a Clang extension, not OpenCL C; the Adreno compiler refuses it. darktable stops at the first kernel file that fails, so other files may have the same pattern.

## Decision (reviewer)
A change to upstream's kernel sources is accepted, as a patch series in `patches/` (PLAN.md 2.x, 5): each patch with its reason, applied by the build exactly like the rest of `patches/`, so `tools/bump-upstream` re-applies it. Nothing edited under `upstream/` directly.

## Deliverables
1. **Find every case**, not just the three reported: a reproducible check over all of `data/kernels/*.cl` (and their includes) that lists every array subscript on a vector type (e.g. compile each kernel with clang `-x cl -cl-std=CL2.0 -Xclang -ast-dump` with darktable's include path and look for `ArraySubscriptExpr` whose base is an `ExtVectorType`, or an equivalent matcher). Commit the tool under `tools/` and its output under `docs/experiments/`.
2. **Patch them** with the smallest change that is valid OpenCL C and keeps results identical: component access where the index is constant, otherwise a private float array or `((float *)&v)[i]` on a private vector. One patch file per kernel file (or one well-described patch), with a written reason and an upstream-ready description (darktable will want this too: write the text of an upstream issue/PR in the report; do not open it).
3. **Prove nothing changed in the result:** the patched kernels compile with PoCL in CI and every existing OpenCL comparison (P4b's C36 GPU vs CPU) still passes; add a check that renders a heavy edit through OpenCL before and after the patch and compares (PSNR, max diff). Also compile every patched kernel with clang in strict OpenCL C mode with vector subscripting rejected, if clang offers such a flag; otherwise the AST check from item 1 must come back empty.
4. **Other Adreno pitfalls in the same pass**, only if found by the same compile log or obvious from the AST check (e.g. `double`, unsupported extensions): list them; patch only what the phone's log shows or what is certainly invalid OpenCL C.
5. GPU details (P4d) keep showing the build log of any kernel file that still fails, so the next phone paste shows the next problem, if any.

## Report
`docs/experiments/P4e-adreno-kernels-REPORT.md` as CLAUDE.md says: CI ids, the list of patched places, the upstream issue text, what the owner must do (install, switch GPU on, paste GPU details, run measure speed).

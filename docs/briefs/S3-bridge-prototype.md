# Brief S3: bridge prototype on Linux

Phase 0 experiment. Read `CLAUDE.md` and `docs/PLAN.md` (sections 2, 2.1, 3.1, 3.2, 8) before starting.

## Question this experiment answers
Can upstream darktable, started without its desktop interface, be driven through a small C API of ours to do what an interactive editor needs: open a RAW with its XMP, list and change module parameters, switch modules on and off, manage instances and history, render a region, and write an XMP that desktop darktable reads back identically? All on Linux x86_64, with **no change to upstream source**.

If the answer is "only with patches" or "no", that is a valid and important result. Say so plainly.

## Environment
- Ubuntu 24.04, root, `apt-get` works, 2 CPU cores, about 7 GB RAM. Use `-j2`.
- Network: GitHub is reachable (clone, raw files). gnome.org, Termux and ghcr.io are not.
- A shallow clone of darktable's development branch is at `/home/claude/dt/src`, checked out at commit `04477d3` (18 Nov 2026). Its git submodules are not initialised. You may reuse it for building, or clone fresh.
- Test images: darktable's own integration test set, `https://github.com/darktable-org/darktable-tests` (RAW files with XMP sidecars and expected outputs). Use only public test images.

## Tasks
1. **Pin upstream.** Add `upstream/darktable` as a git submodule at commit `04477d3`. Document in `docs/experiments/S3-bridge-prototype.md` which of darktable's own submodules are needed (rawspeed, LibRaw, OpenCL headers, libxcf, whereami are likely) and how to fetch them shallowly.
2. **Build darktable for Linux** from that commit into a local prefix outside the repository, Release build, with: Lua, AI, G'MIC, camera support, map, print, colord, libsecret, kwallet, PortMidi, SDL2 off; OpenMP on; OpenCL off. If Ubuntu's version of an optional library is too old, turn that feature off instead of building the library. Record the exact cmake command line. Confirm `darktable-cli` from this build exports one test image.
3. **Write the bridge prototype** in `engine/`: `include/dtbridge.h`, `src/*.c`, a `CMakeLists.txt` that builds `libdtbridge` out of tree against the installed libdarktable and upstream headers. Prototype API (names may be adjusted, keep it small and documented in the header):
   - `dtb_init` (config, cache, data, module, tmp dirs; private library) and `dtb_shutdown`
   - `dtb_xmp_check(xmp_path)`: the version guard. Without loading the image for editing, report every history entry whose module is unknown to this engine or whose module version is newer than this engine's.
   - `dtb_open(raw_path, xmp_path)` / `dtb_close`
   - `dtb_modules_json`: module instances in pipeline order with operation, instance name, instance priority, enabled flag, and scalar parameters as name and value read through darktable's introspection
   - `dtb_param_set_float` / `_int` / `_bool` by parameter name; `dtb_set_enabled`
   - `dtb_instance_new`, `_duplicate`, `_delete`, `_move`
   - `dtb_history_json`, `dtb_history_goto`, `dtb_history_compress`
   - `dtb_render(x, y, w, h, scale, rgba8_out)` for a region of the processed image, and a small preview render; a way to cancel a render in flight
   - `dtb_xmp_write`
   - `dtb_export_jpeg`
   Use the paths upstream already provides for callers without an interface (see PLAN.md F3, F4). One thread owns the session.
4. **Tests** in `engine/tests/`, runnable with one command, on at least three test images:
   - T1 Render parity: the bridge's full-image render equals this build's `darktable-cli` export for the same image and XMP. Report max and mean absolute difference and PSNR on 8-bit output. Target: identical or within 1 level.
   - T2 Parameter change: set exposure to +1 EV through the bridge, write the XMP, export with `darktable-cli` from that XMP; it equals the bridge's render after the change, and history grew by one entry.
   - T3 Instances: duplicate the exposure instance, change the copy, switch one off and on; a fresh bridge session and `darktable-cli` both reproduce the result from the written XMP; instance names and priorities are right.
   - T4 History: going back to an earlier step renders as that step did; compress keeps the result.
   - T5 Region: a 512 × 512 region rendered at scale 1 equals the same crop of the full render. If a module makes this differ by design, name it.
   - T6 Version guard: an XMP edited to contain a module version one higher than the engine's, and one with an unknown operation, are both reported by `dtb_xmp_check`, and the bridge refuses to write over them.
   - T7 Timing on this machine (label it x86_64, 2 cores): open, first full render, re-render after changing a late module (does the pipeline cache help?), preview render.
   - T8 Cancel: a render in flight returns promptly after a cancel request.
   - T9 (stretch) Drawn mask: add a circle mask to an exposure instance through code, render, and show the effect is local; write the XMP and confirm `darktable-cli` reproduces it.
5. **Write up** `docs/experiments/S3-bridge-prototype.md`: commands, results table for T1 to T9, the upstream functions the bridge relies on (file and function), anything that assumed a desktop interface and how you got around it, and whether zero patches held. End with a clear verdict: go, go with conditions, or no-go, and why.

## Limits
- Do not modify `upstream/`. If you are blocked without a patch, stop, write the patch as a file in `patches/` with the reason, and flag it at the top of the write-up. Do not apply patches silently in the build.
- This is a prototype: no JNI, no Android, no Kotlin, no UI schema. Do not start S5 or S1.
- Do not touch the LPL repository or the Project documents.
- Branch `s3-bridge-prototype`, pull request to `main`, do not merge it.

# Brief P1a: engine core (bridge API v1, sections 1 and 2)

Phase 1. Read `CLAUDE.md`, `docs/PLAN.md`, `docs/design/engine-api.md` (your specification), and the write-ups `docs/experiments/S3-bridge-prototype.md` and `G0-test-app.md`.

## Goal
Turn the bridge prototype in `engine/` into the tested implementation of **Engine API v1, sections 1 and 2** ("Core"), on Linux, with the same sources building for Android as today. Section 3 ("Extended") is the next work package; leave room for it but do not implement it, except that the existing prototype `dtb_mask_add_circle` and its test stay working.

## Deliverables
1. `engine/include/dtbridge.h` matching the specification function for function (C names `dtb_<name>`), with each function documented. Where the prototype's names or behaviour differ from the specification, the specification wins; list every rename in your report. If you find the specification wrong or impossible to implement on unmodified darktable, do not improvise: implement what is possible, and describe the problem and a proposal in the report.
2. Implementation split into readable files under `engine/src/` (lifecycle, session, params, instances, history and undo, render, output, guard, actions). No darktable types in the public header.
3. Points that need care:
   - `modules()` with the full parameter tree through introspection (structs, arrays, enums by constant name), `can_switch`, and the other flags.
   - `param_set` with paths into structs and arrays, type and range checks; `params_set` as one history item.
   - `module_action` mechanism and the actions the specification lists. For each, find how the module's desktop panel computes the same thing and follow it; cite file and function in a comment. If an action needs data that only the desktop interface holds, report it instead of approximating.
   - Undo and redo over whole-history states.
   - `render_region` with the internal margin, so that a region equals the crop of the full render. The S3 write-up names demosaic (3 px Bayer, 12 px X-Trans) and one spektrafilm case; choose the margin from what the active modules need if upstream exposes that, else a fixed safe value, and say which.
   - `set_threads` and `set_thread_cpus` (pinning the engine's OpenMP worker threads; on Linux use the same mechanism Android has, `sched_setaffinity` per thread).
   - `export` options through darktable's export path; `xmp_write` atomic; `metadata_reload`.
   - Read-only sessions refuse every mutating call.
4. Tests in `engine/tests/`, extending the existing driver and runner, covering section 4 of the specification on the three test images. The region test must now pass; if a module other than demosaic still breaks it, show which, with numbers, and keep the test failing for that case rather than excluding it silently. Tests run in the existing CI job for Linux and fail the job on any failure.
5. The Android engine build still builds `libdtbridge.so` for both ABIs, and the G0 app still builds against the header (adapt its JNI calls to renames; do not extend the G0 app).
6. Write-up `docs/experiments/P1a-engine-core.md` (what changed, upstream functions newly relied on, any place where desktop logic had to be re-implemented, to be listed later in `docs/UPDATE.md`) and report `docs/experiments/P1a-engine-core-REPORT.md`.

## Limits
- Do not modify `upstream/`. No patches unless agreed; describe the need instead.
- Do not touch `app/` beyond keeping the G0 JNI layer compiling. Do not touch `schema/`. Another agent works in `app/` at the same time.
- Branch `p1a-engine-core`, do not merge.

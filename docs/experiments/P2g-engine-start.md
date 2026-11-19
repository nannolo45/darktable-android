# P2g: the engine must always start

Brief: `docs/briefs/P2g-engine-start.md`. Branch `p2g-engine-start` (from `main` 6682e81). Pull request text:
`P2g-engine-start-REPORT.md`.

## Running log (newest first)

- 2026-11-19 18:30: code written; on the workspace `:engine-api:test` passes (44 tests, 12 new in
  `EnginePinningTest`) and `:app-engine` compiles (Kotlin, x86_64, main and instrumented tests, against the
  released engine `engine-04477d32-6a4466ffd1f4`). Workflows `p2a-app` and `p2b-real-engine` now also run on
  `p2g-engine-start` and `p2f-first-use-fixes`. Pushing for CI.
  Two additions from the reviewer for P2f arrived meanwhile and are noted in the P2f running log once that
  branch exists (they are not part of P2g): (a) deliverable 4: the timings must run on the open photo whatever
  its source (LightPhotoLibrary included; today `AppViewModel.runTimings` falls back to the file-picker working
  folder only), and say the source, the number of history items and the modules on; check whether
  "timings…" from the darkroom still has the photo open; test for the LightPhotoLibrary case. (b) new
  deliverable 10: crop's aspect list (ratio_n / ratio_d through a schema override, the orientation flip, the
  frame keeping the ratio, XMP round trip, tests) and a list of other interface-only controls without a
  parameter binding in the built-in scene-referred layout.
- 2026-11-19 18:00: cause confirmed from the code (see the report, section 1). `FastCores.detect()` reads
  `/sys/devices/system/cpu/online`, the CPUs the device has; `dtb_set_thread_cpus` checks against the engine
  thread's `sched_getaffinity` at `dtb_init`. A process started while Android keeps it in a smaller cpuset gets
  exactly the phone's message.
- 2026-11-19 17:45: branch created. Read CLAUDE.md, both briefs, PLAN.md 3.8, engine-api.md, `engine/src/core.c`
  (threads), `AppEngine`, `FastCores`, `Timings`, `Screens`, the fake engine.

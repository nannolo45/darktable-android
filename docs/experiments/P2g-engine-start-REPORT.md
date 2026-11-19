# P2g: the engine must always start — pull request description

Branch `p2g-engine-start` into `main`. Brief: `docs/briefs/P2g-engine-start.md`. Running log:
`docs/experiments/P2g-engine-start.md`.

**Limits first.**
- **The owner's exact case (a process started while Android allows the slow cores only) was not reproduced on a
  device or emulator.** No way was found to start the app's process with a narrowed CPU set from a test: Android
  offers no public call to put a process into another cpuset, `taskset` on the emulator needs root and acts on a
  running process (after `dtb_init` has already read its set), and the emulator's cpusets all hold every CPU. What
  is tested instead: the choice among allowed CPUs (JVM), a start with a stand-in engine that refuses the pinning
  exactly as the bridge does (JVM, the acceptance test), and the real bridge refusing a CPU outside the engine
  thread's start-up set with the phone's own message "CPU 4 is not available to this process" (emulator, `t1b`):
  the engine stays unpinned and renders.
- **Whether Android drops a thread's pinning when it moves the process between cpusets is established from the
  kernel's source, not measured on a phone** (section 4). The app now measures it on the phone at every return to
  the foreground; the timings text shows the result ("last return to the foreground: …").
- All execution was x86_64 (emulator, 4 equal CPUs, so the app correctly does not pin there). Nothing arm64 ran.

## 1. Cause (confirmed from the code)

- `AppEngine.start` asked `FastCores.detect()` for the fast cores and called `setThreads` and `setThreadCpus`
  inside the same `try` as the engine's start; any `EngineException` became `Status.Failed`, with no way back
  except killing the app.
- `FastCores.detect()` reads `/sys/devices/system/cpu/online` and each CPU's highest clock: the CPUs the **device**
  has, not those this process may use now.
- `dtb_set_thread_cpus` (`engine/src/core.c`) refuses any CPU not in `g_init_cpus`, the engine thread's
  `sched_getaffinity` at `dtb_init`, with `DTB_E_ARG` "CPU %d is not available to this process". An engine thread
  created while the process sat in a small cpuset (background, restricted: typically the slow cluster) has only
  those CPUs, so asking for CPU 4 gives exactly the owner's three lines.

## 2. Brief item by item

| # | Item | State | Where |
|---|---|---|---|
| 1 | Pinning can never stop the start: fast cores chosen among the CPUs allowed now; none allowed or refused → unpinned with a sensible thread count, no error | **done** | `engine-api/.../EnginePinning.kt` (`plan`, `apply`, `pin`), `AppEngine.start` |
| 2 | Pin again when the app comes to the foreground, through the engine's queue; a refusal stays unpinned without an error; establish whether Android drops a thread's pinning when it moves the process | **done** (re-pin: `MainActivity.onStart` → `AppEngine.onForeground`); **the question: from source, measured on the phone from now on** (section 4) | `AppEngine.onForeground` |
| 3 | Start report and timings say "pinned to CPUs …" or "not pinned" with the reason | **done**: about page ("engine pinned to CPUs 4-7 (the fast cores)" / "engine not pinned: …", and the state at start when it differs), timings lines `pinning`, `engine thread CPUs at start`, `last return to the foreground` | `Screens.kt` (`AboutScreen`), `Timings.kt` |
| 4 | "try again" on the "engine did not start" page | **done** (a retry after a failure later than `dtb_init` does not call `dtb_init` twice) | `Screens.kt` (`StartScreen`), `AppEngine.retry` |
| 5 | JVM tests: fast cores allowed, partly allowed, none allowed, list unreadable; start with an engine that refuses (stand-in can refuse). Emulator test with a narrowed CPU set if possible | **done** (JVM, 12 tests); emulator: the real bridge's refusal (`t1b`), **not** a process started narrowed (see limits) | `EnginePinningTest`, `FakeEngine.cpusAtInit`, `RealEngineScenarioTest.t1b` |
| 6 | Describe the bridge changes for the next engine package | **done** (section 5) | this file |

How the choice works (`EnginePinning.plan`): fast cores = `FastCores.detect()` (unchanged rule: leave out the slowest
cluster). Allowed now = `Cpus_allowed_list` of `/proc/self/status` (the process's main thread, which is never pinned,
so it shows the process's cpuset at that moment). Start-up set = the engine thread's own `Cpus_allowed_list` read on
the engine thread right after `dtb_init` (`JniEngine.engineThreadCpus`, `/proc/thread-self/status`), which is what the
bridge checks against. Pin to fast ∩ allowed ∩ start-up set; if that is empty, do not pin. An unreadable list is not
a reason to skip: the fast cores are tried and a refusal falls back. Threads: pinned → as many as CPUs pinned
(set before the CPUs, as before); unpinned → `set_threads(0)`, the count darktable chose at init. An earlier pinning
is undone with `set_thread_cpus([])` only when there was one (otherwise the kernel's own, possibly wider, set is left
alone). Never throws except for cancellation.

FastCores moved from `engine-jni` to `engine-api` (same package, imports unchanged) so the choice runs in JVM tests;
its `describe()` now describes the device's CPUs only ("8 CPUs, max clocks 1.80 / 2.42 / 2.84 GHz; fast cores 4-7"),
because the old "(pinned)" in it was a guess, not what happened.

## 3. How to reproduce

```
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon :engine-api:test --tests '*EnginePinningTest*'
# emulator (as CI): tools/app/emulator-p2b.sh <x86_64 apk> <test apk> <mire1.cr2> <exposure.xmp> <out>;
# RealEngineScenarioTest.t1 and t1b write the pinning lines to files/p2b-test/results.txt
```

## 4. Results

- CI run **44587806953** (`p2b-real-engine`, commit a4def3f, workflow green): JVM 183 tests, 0 failed, 1 skipped (the
  real-server run, as on main); emulator **33/33 passed** (API 33 x86_64, 4 CPUs); comparisons with darktable-cli
  pass (PSNR 75.7 and 74.4 dB). Run 44586629369 (`p2a-app`, 2494b8e) green. Run 44586629446 (2494b8e) stopped on
  Maven Central answering "could not find" for existing artifacts three times in a row; the workflow now waits
  longer between its retries (a4def3f).
- APK: release **`dt-android-a4def3fc8008`** (`dt-android-arm64-v8a.apk`, signed with the owner key).
- Emulator lines (workspace emulator, not a phone): `cores 4 CPUs`; `pinning_at_start not pinned: all CPUs are equally
  fast; …`; `engine_thread_cpus_at_start 0-3`; `p2g_refused not pinned: the engine refused CPUs 4 (ARG: CPU 4 is not
  available to this process); the engine uses every CPU it may`; engine_info after it: `"threads":4,"thread_cpus":[]`;
  a preview rendered afterwards; `p2g_foreground … now not pinned: …`.

**Does Android drop a thread's pinning when it moves the process?** From the kernel's source: yes, on the kernels
API 33 phones ship (5.10, 5.15), unless the vendor backported a later change.
- Android moves an app between cpusets (`top-app`, `foreground`, `background`, `restricted`, …) with its process
  state, by writing the threads into `/dev/cpuset/<group>/tasks` (task profiles).
- In Linux, `cpuset_attach()` (`kernel/cgroup/cpuset.c`) calls `set_cpus_allowed_ptr(task, <the cpuset's CPUs>)` for
  each moved thread, which replaces a mask set with `sched_setaffinity`. So the engine's pinning to 4-7 becomes the
  new group's CPUs (for example 0-7 in `top-app`, 0-3 in `background`).
- Linux 6.2 changed this ("sched: Enforce user requested affinity", commit da019032819a, Waiman Long, 2022): the
  mask a thread asked for is kept (`user_cpus_ptr`) and intersected with the cpuset. Some vendor 5.15 kernels carry
  a backport of it. On such a kernel the pinning survives, but in a small cpuset it is narrowed or, when the
  intersection is empty, falls back to the cpuset's CPUs.
- Either way the app pins again on every return to the foreground. On the phone the timings text now says which
  happened: "the pinning to CPUs 4-7 was still in place" or "… had been reset by Android to CPUs 0-7".

Sources: the series' cover letter "sched: Persistent user requested affinity" (lkml, Aug 2022) and the commit above.

## 5. For the next engine package (no change under `engine/` here)

1. **Test a CPU against what the process may use now, not against the start-up set.** `dtb_set_thread_cpus` should
   read the allowed set at the time of the call from a thread that is never pinned: `sched_getaffinity(getpid(), …)`
   (the main thread; its mask follows the cpuset), not `g_init_cpus` and not the engine thread's own mask (which is the
   pinning once pinned). Proposed rule: apply `requested ∩ allowed-now`; return `DTB_E_ARG` only when that is empty;
   report what was applied in `engine_info.thread_cpus`.
2. **What `set_thread_cpus([])` should restore:** the process's allowed set at the time of the call
   (`sched_getaffinity(getpid())`), not `g_init_cpus`. Today it restores the start-up set; for a process started in
   the background that is the slow cluster, so "unpinning" would keep the engine on the slow cores for the life of the
   process even in the foreground. (The app avoids calling it unless it pinned before.)
3. **Thread count chosen at init when the process starts with few CPUs allowed.** `dt_init` sets
   `darktable.num_openmp_threads = dt_get_num_procs()` = `omp_get_num_procs()` (`src/common/darktable.c`,
   `src/common/darktable.h`), and `--threads n` is clamped to that too. With LLVM's OpenMP (the NDK's libomp),
   `omp_get_num_procs()` is the number of CPUs in the affinity mask when the runtime initialised. A process started in
   a 4-CPU (or 2-CPU) cpuset therefore gets 4 (or 2) threads for its whole life: `dtb_set_threads` clamps to
   `g_init_threads`. Things sized from the count at init: `dt_points_init(darktable.points, dt_get_num_threads())`
   (per-thread random states), `backthumbs.capable` (needs >= 4), `dt_worker_threads()` (4 or 7 background workers),
   and per-thread scratch buffers sized with `dt_get_num_threads()` at each use. Raising the count above the init value
   later would overrun `darktable.points`; that is why the cap must stay unless those are sized for the device.
   Proposal: before `dt_init`, the bridge sets the count from the device's CPUs (`sysconf(_SC_NPROCESSORS_CONF)`), for
   example by starting libomp with affinity disabled (`KMP_AFFINITY=disabled`, so `omp_get_num_procs()` counts the
   online CPUs) and passing `--threads` = the number of fast cores the app found; the engine then runs fewer threads
   while pinned (`dtb_set_threads`) and the cap is never the start-up cpuset's size. To verify on a phone started in the
   background (for example by a job) before relying on it.
4. A note on order, kept by the app: threads are lowered before the CPU set is narrowed and the CPU set widened before
   threads are raised. With libgomp (Linux) `dt_get_num_threads()` follows the current mask at each call and a
   narrower mask than `num_openmp_threads` would undersize per-thread buffers; libomp (Android) reads the mask once.

## 6. Surprises in upstream

- `dt_get_num_threads()` is `CLAMP(omp_get_num_procs(), 1, num_openmp_threads)`, so with libgomp the per-thread buffer
  count changes with the thread's current affinity while parallel loops use `num_openmp_threads`
  (`src/common/darktable.h`). Harmless with libomp, which the Android build uses.

## 7. Open questions for the reviewer

- Should the next engine package also widen the thread cap (item 3 of section 5)? Without it, a process started in
  the background stays at the start-up cpuset's thread count until it is killed, pinned or not.
- The about page shows the pinning; should the start page show it while the engine starts? (It is not an error, so
  it is not shown there now.)

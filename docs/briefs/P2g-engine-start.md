# Brief P2g: the engine must always start

Phase 2, small and urgent. Read `CLAUDE.md`, `docs/PLAN.md` section 3.8 and `docs/design/engine-api.md` (threads). Do this before P2f; P2f branches from this branch's tip.

## What the owner saw
The app's start page, on the phone that had run the app and its timings page successfully the same day:

    the engine did not start
    the engine did not start: ARG: CPU 4 is not available to this process
    CPU 4 is not available to this process

## Cause, read from the code by the reviewer (confirm it)
- `AppEngine.start` (`app/app-engine`) asks `FastCores.detect()` for the fast cores and calls `engine.setThreadCpus(cores.chosen)`. Any error there ends in `Status.Failed`: the app is unusable until it is killed.
- `dtb_set_thread_cpus` (`engine/src/core.c`) refuses a CPU that was not in the engine thread's set at `dtb_init` (`g_init_cpus`).
- Android limits the CPUs of a process by its state. A process started in the background (for example by a link, a job or a restore) or under a system restriction may be allowed the slow cores only at that moment; the fast cores then "are not available". `FastCores.detect()` reads the list of CPUs the device has, not the ones this process may use now.

## Deliverables
1. **Pinning can never stop the engine from starting.** Choose the fast cores among the CPUs the process may use at that moment. If none of the fast cores is allowed, or the bridge refuses, the engine starts unpinned with a sensible thread count and the app works. No error is shown to the user for this.
2. **Apply the pinning again when the app comes to the foreground**, because the allowed CPUs change with the app's state (establish, with a source or a measurement, whether Android also drops a thread's pinning when it moves the process between its groups; say what you found). The call goes through the engine's own queue like every other call. If the bridge still refuses (start-up set narrower than the present one), stay unpinned without an error.
3. **Say what happened**: the start report and the timings page state "pinned to CPUs …" or "not pinned" with the reason in plain words, so a timing run shows which case it measured.
4. **A "try again" control on the "engine did not start" page**, so a failed start does not need the app to be killed.
5. **Tests**: JVM tests for the choice among allowed CPUs (fast cores allowed, partly allowed, none allowed, list unreadable) and for a start with an engine that refuses the pinning (the stand-in engine can refuse). An emulator test if you find a way to start the process with a narrowed CPU set; if not, say so.
6. **For the next engine package, describe precisely** (do not change `engine/` here): the bridge should test a CPU against what the thread may use now, not against the start-up set; what `set_thread_cpus([])` should restore; and what the thread count chosen at `init` is when the process starts with few CPUs allowed, with the consequences for buffers sized from it.

## Limits
- No changes under `engine/` or `upstream/`.
- No person's name, device model, server address or private link anywhere.
- Branch `p2g-engine-start`; push it as soon as the workflow is green on it and the report is written (`docs/experiments/P2g-engine-start-REPORT.md`), so the reviewer can merge it and the owner gets a build while P2f goes on. Do not merge, no pull requests.

## Acceptance
- With the stand-in engine refusing the pinning, the app reaches the ready state unpinned; test in the workflow.
- Workflow green on the last code commit, the signed APK published as usual.

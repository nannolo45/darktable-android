# Brief T1: test stability and two follow-ups

Branch `t1-stability` from `main` (`ff4eb14`). Small package; token use is a priority (grep, line ranges).

1. **Flaky emulator tests on main** (each failed once without a code cause):
   - `P3cScenarioTest.p2_exposureSpotByTouch` ("measure changes nothing expected:<-0.448> but was:<-0.128>")
   - `P3eScenarioTest.p4_toneEqualizerDragOnThePhoto` ("timed out: a band went up; picker null")
   Find the race in each test or in the app (a pick read before the area pick lands; a drag before the picker is
   armed, etc.). Fix the cause; if it is in the app, fix the app. Never loosen an assertion's meaning.
2. **C28 follow-up**: undo and history compress also reload at a lower history end (as history_goto did before the
   C28 fix in `engine/src/history.c`). Add bridge tests that a drawn mask above the end survives undo/redo and
   compress, with the picture equal to darktable-cli of the XMP; fix in the bridge if not.
3. **CI**: in `p2b-real-engine.yml` the compare job reads the emulator's artifact of a failed earlier attempt after
   "rerun failed jobs" (seen 23 Nov); make compare read only its own attempt's artifact.

Rules: CLAUDE.md binding; never `upstream/`; push after each item; branch in the triggers of g0-app, p2a-app,
p2b-real-engine. Evidence: run p2b three times on the final commit (whole-workflow reruns) and report all three.
Report `docs/experiments/T1-stability-REPORT.md`.

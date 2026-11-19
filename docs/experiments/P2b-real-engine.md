# P2b: the real engine in the app

Brief: `docs/briefs/P2b-real-engine.md`. Branch `p2b-real-engine`. Pull request text: `P2b-real-engine-REPORT.md`.

## Running log (newest first)

- 2026-11-19: instrumented tests (contract suite on the real engine, scenarios), emulator script
  `tools/app/emulator-p2b.sh`, workflow `.github/workflows/p2b-real-engine.yml`. First CI run started.
- 2026-11-19: `app/app-engine` (application id `app.dtandroid.real`, label "dt-android engine") builds locally for
  x86_64 against the released engine (workspace, 2 cores). JniEngine, FastCores, drag picture, WorkFolder,
  thumbnails done; JVM tests of engine-api and darkroom pass on the workspace.

- 2026-11-19: branch created from `main` (237d3a0). Read the brief, the contract, the bridge header, PLAN 2/3/6,
  the G0, P1a, P2a and P2d write-ups and the code of `app/g0`, `app/engine-jni`, `engine-api`, `darkroom`, `app`.
  Engine release for the current tag is `engine-04477d32-992bc04fa900` (both ABIs present).

Next: read the first CI run; fix the fake where the real engine differs; owner instructions; report.

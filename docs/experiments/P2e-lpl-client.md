# P2e: the app works with LightPhotoLibrary

Brief: `docs/briefs/P2e-lpl-client.md`. Branch `p2e-lpl-client`. Pull request text: `P2e-lpl-client-REPORT.md`.

## Running log (newest first)

- 2026-11-19: branch created from `main` (9b9cd7f). Read CLAUDE.md, the brief, PLAN.md 3.5, 3.7 and 4, the P2b
  and P2c reports, `engine-api.md`, the code of `app-engine`, `engine-jni`, `darkroom`, `engine-api`, `lpl-shell`
  and the real-engine workflow. Read the LPL server's agent endpoints (private repository, read only) to learn
  its exact behaviour.

Next: module `app/lpl` (client, pure Kotlin) with JVM tests against a stub server, then the same client
against a real LPL server on the workspace.

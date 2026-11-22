# P2k running log: batches and Stop

- 22 Nov 2026: branch `p2k-lpl-batches` from `origin/main` 2346669.
- Code: `Transfers.kt` (TransferBook, BatchView, Cancel), heartbeat `caps`/`transfers`/`cancel` in `LplClient`/`LplAgent`, `LplAgent.runEditJob` and `finishJob`, `BatchProgress` in `app/darkroom/.../ui`, `ready` status.
- JVM tests on the workspace (`gradle :lpl:test`, x86_64, 2 cores): `P2kBatchesTest` 13 tests pass, the whole `:lpl:test` suite passes. The Android modules cannot be built here (no SDK); CI does that.
- Decision: an edit job is run as a batch only when the server has shown it knows batches (its heartbeat answer has `cancel`). An old server keeps today's path (the hand-off screen downloads and opens).
- Decision: for a batch job `/done` is sent when copying ends; the darkroom is opened after that and the job does not wait for the user.
- Not done: the client's JVM test against the real LPL server (needs a throwaway server with test photos and jobs made through the web API: more than a few commands). Emulator scenario `l8` added to `LplScenarioTest`, not run here.

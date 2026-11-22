# Brief P2k: batches and Stop for LightPhotoLibrary (saved as given)

Work package P2k, branch `p2k-lpl-batches` from `origin/main` (`2346669`). Extend the Android app's LightPhotoLibrary (LPL) client so that LPL can show batch progress for the phone and stop a batch. Keep token use low; the client is `app/lpl/src/main/kotlin/app/dtandroid/lpl/`, its users are in `app/app-engine/src/main/kotlin/app/dtandroid/real/`.

## Rules
- Follow `CLAUDE.md`. Public repository: nothing personal, nothing copied from the private LPL repository (read-only look at the server's `docs/devices-stop-estimate.md`, `server/lpl/transfers.py`, `agent/lpl_agent.py` to understand it).
- Never push to `main`, no pull requests. Push after each deliverable.
- Do not touch `engine/`, `upstream/`, `schema/`, `engine-jni`, existing `ui-theme` token values. Screen code uses tokens and icons only; both skins must work (`DtTheme.colors.accent` for progress).
- No Android SDK on the workspace: builds and tests run in CI. Add the branch to the triggers of `p2a-app.yml` and `p2b-real-engine.yml` only.
- This package extends the LPL contract on purpose (reviewer approved): PLAN.md section 4 is updated here (deliverable 6).

## The protocol (everything is opt-in by `caps`)
Heartbeat request, every heartbeat: `"caps": ["batch","cancel","open"]` and `"transfers": [{"job","state","arrived","present","failed","skipped","photo","bytes"}]`: one entry per edit job held, from the heartbeat after it was received until `/done` was answered; `[]` when none; a job the server thinks is running and that is not listed is taken for lost. `state`: `queued`, `running`, or ended `stopped`, `done`, `failed`. Lists are cumulative. `photo`/`bytes`: the photo being copied and bytes on the device; `null`/`0` between files. No totals. At least every 2 s while a batch runs; at the end one heartbeat at once with the final state, then `POST /api/jobs/{id}/done`.

Heartbeat answer: `"cancel": {"jobs": [...], "photos": [...]}`, always the whole list (replace), repeated until confirmed. `jobs`: stop between files and between chunks (at most 256 KiB per read), keep what arrived, delete the partial file, remove the half-made working-set entry, do not open the darkroom, report `stopped`; unknown id ignored; a job waiting on the device ends as `stopped` without starting. `photos`: do not copy them in any job; the current one is given up, the next follows; listed under `skipped`. A Stop acts within about 2 s of the heartbeat that carries it. Edit jobs may carry `payload.open` (`false` = copy, do not open). With `batch`, `payload.ids` has each id once. `/done` accepts `done|failed|stopped` and optionally the four lists. An old server ignores `caps`/`transfers` and sends no `cancel`: everything works as today.

## Deliver
1. Client and agent: the above in `app/lpl`, the transfer state in one place (pure Kotlin, testable without Android). A stopped file's partial data is deleted, not resumed; a file interrupted by a lost connection keeps resuming.
2. Status `ready` in the working set for photos copied by a job with `open: false` or not opened yet; the existing statuses and tests stay.
3. In the app: one overall bar "photo n of N" (N = ids of the jobs held, no time estimate) with a `stop` button that ends the device's running jobs locally as `stopped`; afterwards "stopped · n of N arrived". One small reusable component (`BatchProgress`): label, fraction or indeterminate, optional step text, optional cancel action (the export screen will use it).
4. Foreground limit: heartbeats only in the foreground, no service, state it. A batch interrupted by leaving the app reports correctly when the app returns: jobs still held are listed again, nothing is reported `done` that is not.
5. Tests (JVM, stub server, extended): caps and transfers; stop between files; stop mid-file; `cancel.photos` for a waiting and the current photo; `open: false`; whole-list semantics; unknown job id; old server; final heartbeat before `/done`. An emulator scenario only if cheap. Optional: the JVM test against the real server, client-side log under `docs/experiments/`.
6. Docs: this brief, `docs/PLAN.md` section 4 (shapes and `ready`), `docs/experiments/P2k-lpl-batches-REPORT.md`, a short log `docs/experiments/P2k-lpl-batches.md`.
7. One question for the report: for a series inside a series folder, what do `series`, `title` and `ids` in the `lpldt://edit` link carry, and does the app need to change for folders? Build nothing for it.

## CI
After pushing, check `gh run list --branch p2k-lpl-batches`; stop when both app workflows are green on the last code commit, or after three failed fix attempts on one problem.

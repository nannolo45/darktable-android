# P2k: batches and Stop: report

Branch `p2k-lpl-batches`. Brief: `docs/briefs/P2k-lpl-batches.md`. No pull request was opened (not allowed for this package); the reviewer merges.

## 1. What the brief asked and what was delivered
| # | Item | Result |
|---|---|---|
| 1 | Client and agent: caps, transfers, cancel, `open`, `/done` with status and lists; transfer state in one pure-Kotlin place | done (`TransferBook` in `Transfers.kt`; `LplClient.heartbeat`/`jobDone`; `LplAgent.runEditJob`/`finishJob`) |
| 1 | Stopped file's partial data deleted, not resumed; lost connection still resumes | done (`CANCELLED` in `download` removes part, etag, half-made entry; an unsynced edit in the folder is never deleted) |
| 2 | Status `ready` | done; used for photos copied by a batch job; existing statuses and tests untouched (`OPENING` still used by `take` outside batches) |
| 3 | Bar "photo n of N" + stop + "stopped · n of N arrived"; reusable component | done in code (`BatchProgress`, `PhotoList.BatchLine`); not seen on a screen: no screenshot test was added |
| 4 | Foreground limit | stated in PLAN.md 4.1; no service; held jobs are reported again by the next heartbeat; nothing is reported `done` unless `/done` was sent |
| 5 | Tests | JVM: done (13 in `P2kBatchesTest`); stub extended (`StubLpl`). Emulator scenario `l8`: added, not run. Real-server run: not done |
| 6 | Docs | done |
| 7 | Question | see below |

## 2. How to reproduce
`cd app && gradle :lpl:test` (JVM, no Android SDK needed; this is what ran on the workspace). CI: `p2a-app.yml`, `p2b-real-engine.yml` on this branch.

## 3. Results
`:lpl:test` passes, `P2kBatchesTest` 13 of 13 (x86_64, 2 cores; 0.6 s). Covered: caps and transfers in every heartbeat, `open: false`, each id once, final heartbeat before `/done` (order checked in the stub's event log), stop between files, stop in the middle of a file (partial file and entry gone, what arrived kept, `stopped` reported, nothing to open), `cancel.photos` for a waiting and for the current photo, whole-list semantics, unknown job id, old server without `cancel`, waiting job stopped without starting, local stop button, batch cut short by leaving the app.

## 4. Surprises
- Today the hand-off screen downloads the photos itself (`AppViewModel.startHandOff`). With a batch server the agent copies first and the screen opens photos already on the phone, so the user waits at the list (with the bar) before the darkroom opens.
- `downloadOriginal` already checked `cancelled` between 64 KiB reads (under the 256 KiB limit); it only needed to be used.
- A read that stalls on the network is not interrupted: a Stop acts at the next chunk or when the read times out (`ClientSettings.downloadReadTimeoutMs`), not within 2 s in that case.

## 5. Open questions for the reviewer
- Is "copy first, then open" acceptable for jobs with `open: true`? An alternative is to open the first photo as soon as it arrives.
- A `stopped` job that LPL hands out again is reported `failed` by the R13 memory (`ran-jobs.txt` stores a boolean). The server does not hand out a listed job twice, so this should not happen.
- `ready` photos become `synced` when a `take` with refresh opens them, as `opening` ones do today.

## Question 7: series inside a series folder
Read from `web/src/devices.ts` (`phoneEditLink`) and the callers in `components/Strip.tsx`, `Board.tsx`, `Mobile.tsx`. For a series that sits in a folder, the link has: `series` = that series' own id; `title` = the series' own name (the folder's name is not carried); `ids` = the photos shown or selected in that series, in its order (at most 200 around `start`). Only when the folder itself is the target: `series` is left out and `title` is the folder's name, `ids` are the photos of the folder as shown. The app reads `series` and `title` in `HandOff.kt` and uses only `title` (the darkroom's list name); `series` is parsed and unused. Nothing in the app needs to change for folders; a folder name above the series name would need a new link parameter and LPL's agreement (contract change, not built).

## What the reviewer must check
CI of both app workflows; the bar in both skins on a phone; `AppHost.runJob` (batch path versus old path); the choice of "batch only when the server's answer has `cancel`".

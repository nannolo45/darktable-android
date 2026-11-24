# P3k: LightPhotoLibrary sync in the background: report

Branch `p3k-background-sync` from `main` c54e1f5. Brief: `docs/briefs/P3k-background-sync.md`. No change in `engine/`,
`upstream/` or the LPL repository; no new dependency (platform APIs only).

**Status:** all items done; p2a and p2b green on the last code commit `e250976` (p2a run 45397603186, p2b run
45397603187: JVM tests including `:lpl:test`, screenshots, emulator 109 of 109, compare). Phone APK: release
`dt-android-e25097699208`. One thing is shown by the JVM test only: the "Sending edits · n of N" title was not seen on
the emulator, because the edit was sent (1.4 s) before the service's first notification update (s1 shows the generic
title "LightPhotoLibrary"); "Downloading · 1 of 2" was seen (s2).

## 1. Item by item

| Brief item | State |
|---|---|
| 1. Sync service: `dataSync` foreground service while LPL work is in flight; starts at activity leaving / "done" / batch start; stops by itself (nothing left, or nothing can progress: then `SyncJob`); never idle, never polls | done: `SyncService.kt`; decisions in `lpl/BackgroundSync.kt` (pure, JVM-tested) |
| 2. Notification "Sending edits · n of N" / "Downloading · n of N", bar, tap opens the app, "stop" for downloads | done (`SyncNotice`, `SendCounter`). Own channel `lpl-sync` ("LightPhotoLibrary sync") and id 4302; the export keeps channel `export` and id 4301, so both can show at once and neither replaces the other. "Stop" calls `agent.stopBatches()`, the same as the list's stop button (P2k) |
| 3. Heartbeat while the service runs, a last one when it stops; jobs then: `sync`/`release` for photos not open, `edit`/`open` kept | done (`AppHost.serviceHeartbeat`, `heartbeatAndKeep` now uses `BackgroundSync.splitJobs`). Kept jobs go to `lpl/kept-jobs.txt` (existing mechanism) and now run from the foreground loop as soon as the app is in front again, not only at the next `onStart`. A `sync`/`release` touching the photo open in the darkroom is kept too (never synced behind the user's back). The service skips its beat while the foreground loop or the export's beat runs |
| 4. Rules kept: auto-sync off (no service for edits; downloads, exports, LPL jobs as before); open photo never synced; no notification permission: work still runs | done (JVM tests; emulator s3). Without the permission `startForeground` still works on Android 13, the notification is just not shown; the app does not ask for the permission at `onPause` |
| 5. Starting from the background | done, see section 4 |
| 6. Battery-optimisation line in the LPL settings | done: `BatteryHint` (darkroom `ui`, tokens and `DtTextButton` only), shown under "sync automatically"; state from `PowerManager.isIgnoringBatteryOptimizations`, refreshed in `onResume`; button opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; no new permission |
| PLAN.md 3.7 and 4.1 | done (3.7: what was built; 4.1: heartbeat row "while the app is in the foreground or its sync service runs", jobs row, the batches' foreground limit) |

**Bug found on the way (pre-existing since P2k/P2e, fixed here):** the foreground heartbeat loop launched its jobs, the
queued-exports send and the first pending sync as *children of the loop*; `AppHost.background()` cancels the loop, so a
batch job copying when the app left the screen was cancelled half-way (its `TransferBook` entry stayed `running`, the
job was never reported) and a sync under way was cut. Found by emulator s2 (run 45385139934). They now run in
`AppEngine.scope`.

## 2. How to reproduce
- JVM (decisions, jobs in background heartbeats, notification words): `cd app && gradle :lpl:test --tests '*P3kBackgroundSyncTest'`
  (runs locally on the build workspace: `gradle -Dorg.gradle.configureondemand=true :lpl:test`; 19 tests).
- Screenshots: p2a-app job, `phone-p3k-01-battery-exempt.png`, `phone-p3k-02-battery-not-exempt.png` (`P3kBatteryHintScreenshotTest`).
- Emulator: p2b-real-engine job, `P3kScenarioTest` (added to `tools/app/emulator-p2b.sh`), results in `p2b-test/p3k-results.txt`
  on the `ci-logs` branch under `runs/<run id>-a1/p2b-emulator/`.

## 3. Results
CI runs on this branch (x86_64 emulator API 33, 2-core runners; not phone numbers):

| Run (p2a / p2b) | Commit | Result |
|---|---|---|
| 45378801007 / 45378800848 | cee4aee | p2a green; p2b emulator: s1, s2, s4 failed (test counted service *creations*, but a service started by an earlier `onPause` was already running; s2 batch never reported: the cancelled-child bug above) |
| 45385139910 / 45385139934 | dabd70e | p2a green; s1 passed; s2 still not reported (same bug); s3, s4 failed in `@Before` waiting for the service that s2's stuck batch kept alive |
| 45392518490 / 45392518528 | c248ce1 | both green, 109/109; notification showed "Downloading · 3 of 4" for a 2-photo batch (ended batch of another server summed in) |
| **45397603186 / 45397603187** | **e250976** | **both green, 109/109; release `dt-android-e25097699208`** |

Emulator `p3k-results.txt` of run 45397603187 (`ci-logs` branch, `runs/45397603187-a1/p2b-emulator/p2b-test/`):
- s1: XMP and preview at the stub 1365 ms after "done" + activity stopped; service seen running, gone after 2056 ms,
  stop reason `STOP_DONE`; posted XMP has the exposure edit.
- s2: batch of 91, 92 copying when the activity was stopped (first original held at 64 KiB until then); job `done`,
  `"arrived":[91,92]` 2.3 s after start; service seen, `STOP_DONE`; notification "Downloading · 1 of 2".
- s3: auto-sync off: no service created or seen in 15 s after "done" + stop; 0 XMP and 0 preview posts.
- s4: server stopped before "done": service ran, stopped after 12.1 s with `STOP_HANDOVER`; job 4201 (`SyncJob`) pending;
  0 XMP posts.
- JVM: `P3kBackgroundSyncTest` 19 tests (start/stop with pending work, auto-sync off, unreachable server, darkroom
  open, download running, stalled passes; heartbeat jobs: edit/open kept, sync/release of the open photo kept, `all`
  jobs; notification words). Whole `:lpl:test` also passes locally (11 classes).
- Screenshots: release `app-preview-e25097699208`, `phone-p3k-01-battery-exempt.png`, `phone-p3k-02-battery-not-exempt.png`.

## 4. Starting a foreground service from the background (item 5)
- Android 12 (API 31) and later throw `ForegroundServiceStartNotAllowedException` for `startForegroundService` from the
  background, with exemptions (visible activity, a notification action the user tapped, exact alarms, high-priority FCM,
  boot, and a few others). A `JobService` is **not** exempt, except an expedited job (`setExpedited`, API 31), which
  this app does not use. So `SyncJob` never starts the service: it does its own sync and export pass, as before.
- `onStop` runs after the activity is no longer visible; on API 31+ the start may already be refused there. The
  service is therefore started in `onPause` (activity still visible), synchronously at "done" (`leaveDarkroom`, on the
  main thread before its coroutine) and when a batch job begins (it begins from the foreground loop, i.e. with the
  activity started). A refused start (`IllegalStateException`; the API 31 class is not caught by name, minSdk is 29)
  falls back to `SyncJob.schedule`.
- Android 15's `dataSync` time limit: `onTimeout` stops the service and schedules `SyncJob`.
- `targetSdk` is 34: the `FOREGROUND_SERVICE_DATA_SYNC` permission and `foregroundServiceType="dataSync"` were already
  declared for P3i.

## 5. Anything in upstream that surprised me
Nothing: no upstream code was touched or needed.

## 6. Open questions for the reviewer
1. `onPause` also fires for a dialog or the permission prompt on top of the activity; the service then starts while the
   app is in front if work waits (it does what the foreground loop would do, then stops). Acceptable, or start only
   in `onStop` when still allowed (API < 31) plus "done"/batch?
2. A batch entry that stayed `running` because of a bug would keep the service alive (heartbeat every 2 s) until the
   process ends or Android 15's limit; no extra watchdog was added. Wanted?
3. An `edit` job that is not a batch (old server, `open` true) is still run from the foreground loop and waits up to 30
   min for the darkroom (unchanged); it is no longer cancelled when the app leaves the screen (see the bug above), so
   it now opens the photos when the user comes back, within those 30 min.
4. The emulator cannot show that the phone vendor's Android would have killed the process without the service: the
   instrumented test keeps the process alive. A phone test with the app swiped away during a batch is the real check.

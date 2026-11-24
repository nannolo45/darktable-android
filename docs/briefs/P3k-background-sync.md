# Brief P3k: LightPhotoLibrary sync in the background

Branch `p3k-background-sync` from `main` (`c54e1f5`). Owner's decision (24 Nov): edits must reach
LightPhotoLibrary even when the app is closed or in the background. PLAN.md 3.7 already says downloads and uploads
run in a foreground service with a notification, because the phone vendor's Android stops background work
aggressively; that part was never built (P2e report, open question 1). Today: `SyncJob` (JobScheduler, network
required, back-off) retries waiting syncs; the heartbeat loop in `AppHost` (around line 420) runs only while the
app is in the foreground; batch downloads of LPL edit jobs (`Transfers`, P2k) stop when Android stops the process.
P3i's `ExportService` (dataSync foreground service) is the pattern to follow.

## Scope
1. **Sync service.** A foreground service of type `dataSync` (`SyncService`) that runs while there is LPL work in
   flight: edits waiting to be sent (XMP, preview, versions, queued exports) and batch downloads already started.
   Started when the app leaves the screen (activity stopped) or the darkroom's "done" with work pending, and when
   a batch download starts; stops by itself when nothing is left or nothing can progress (server unreachable:
   then hand over to `SyncJob` and stop). Never runs idle, never polls for new jobs.
2. **Notification.** One notification for LPL transfers: "Sending edits · n of N" / "Downloading · n of N" with a
   progress bar from the existing step/progress data (R16); tap opens the app; a "stop" action for downloads
   (same as the P2k stop button). Export keeps its own service and notification; the two must not fight (one may
   reuse the other's channel; say what you chose).
3. **Heartbeat while the service runs**, at the existing interval, so LPL shows progress (R16) and receives the
   final states; a last heartbeat when it stops. This extends PLAN.md 4.1 ("heartbeat only while the app is in the
   foreground"): update that row to "while the app is in the foreground or its sync service runs". Jobs handed out
   in those heartbeats are carried out only if they are `sync` or `release` for photos not open; `edit`/`open` jobs
   wait for the app (report how you keep them: do not lose them, R13 is not built on the server).
4. **Rules kept.** "sync automatically" off (P2i): the service does not start for edits; downloads the user
   started, exports the user asked for and LPL jobs work as today. A photo open in the darkroom is never synced
   behind the user's back (existing locks). Android 13 notification permission missing: work still runs.
5. **Starting from the background.** Android 12+ forbids starting a foreground service from the background in most
   cases. Start it while the activity is still visible (`onStop` is too late on some versions: check, use
   `onPause`/lifecycle or start at "done"), and let `SyncJob` do its own work when Android starts it (no service
   start from the job unless it is allowed; document what Android 13 permits).
6. **Battery-optimisation hint.** In the LPL settings: a line saying whether the app is exempt from battery
   optimisation (`PowerManager.isIgnoringBatteryOptimizations`) and a button opening the system list
   (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; no new permission). One short sentence that some phones
   also need "allow background activity / auto-launch" for the app in their own settings. Tokens and existing
   controls only.

## Rules
`CLAUDE.md` binding (public repo, nothing personal, no server addresses). Never push to `main`, no PRs; push after
every deliverable. No change in the LPL repository and none in `engine/`. No new dependencies (platform APIs only).
Token use is a priority: grep and line ranges, no whole-file reads of large files, CI polling with long sleeps.

## Tests
JVM: the decision when the service starts/stops (pending work, auto-sync off, unreachable server, darkroom open,
download running), job handling in background heartbeats (edit/open kept for the app). Emulator, with the
LPL client tests' fake server: (s1) edit a photo, "done", activity stopped right away: XMP and preview arrive,
service seen, then gone; (s2) a two-photo batch download started then the activity stopped: both arrive;
(s3) auto-sync off: no service, nothing sent; (s4) server unreachable: service stops, `SyncJob` scheduled.
Screenshot of the settings line (exempt / not exempt).

## Docs and CI
Report `docs/experiments/P3k-background-sync-REPORT.md` (format in CLAUDE.md); PLAN.md 3.7 and 4.1 updated.
Branch is in the triggers of `p2a-app.yml` and `p2b-real-engine.yml`. Known emulator flakes on main:
`P3cScenarioTest.p2_exposureSpotByTouch`, `P3eScenarioTest.p4_toneEqualizerDragOnThePhoto` (being fixed on
`t1-stability`): if only those fail, rerun. Maven 429: `gh run rerun <id> --failed` (for p2b rerun the whole
workflow). Stop when green, or after three failed fix attempts on one problem, and report it plainly.

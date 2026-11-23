# P3i: export follow-up (LightPhotoLibrary destination, background series, quality slider): report

Branch `p3i-export-followup` from `main` 0207c5a. Brief: `docs/briefs/P3i-export-followup.md`. LPL side: R18 on LPL
branch `exports-r18` (contract in the brief's folder; shapes copied to PLAN.md 4.3 as row R18, see "Not done").

## 1. Item by item

| Brief item | State |
|---|---|
| Quality slider 5..100, default 95, one value per lifted finger, double tap on the bar resets | done (`ExportScreen.kt` `QualitySlider`, `ExportSettings.withQuality`) |
| Destination LightPhotoLibrary, offered by `server_caps` `exports`, else greyed with a one-line reason | done (`LplOffer`, `AppExportDelivery.lplOffer`) |
| Upload as R18 with the photo's id and version, byte progress as the step, heartbeat `step: "exporting"`, `"exports"` in caps | done (`LplClient.postExport`, streamed from file; `LplAgent.sendExport`) |
| Unreachable server: queue, survives restart, sent with the next sync | done (`ExportBook`, `sendQueuedExports`; flushed by the foreground heartbeat loop and by `SyncJob`) |
| Cut-off upload repeated whole, `size` check | done (client retries 2x with the existing back-off; `size` always sent) |
| Series in the background without opening each photo; foreground service `dataSync`, notification "photo n of N", cancel | done (`DarkroomController.withExportSessions`, `ExportService`) |
| Two engine sessions | **not possible** (bridge: one session). Chosen: save and close the darkroom's photo, export every photo on its own session, reopen the darkroom's photo. The darkroom's actions wait meanwhile; its undo stack is lost (the edit is saved to the XMP first). |
| Heartbeat while exporting in the background | done for exports to LPL (`AppHost.exportHeartbeats`) |
| ui-spec export section; CI triggers (p2a, p2b; engine untouched so not g0) | done |
| PLAN.md 4.3 row R18 | **not done** (see below) |

Behaviour change from P3g: a series no longer stops when the darkroom is left; the "stopped: the darkroom was left" path is gone.

## 2. Reproduce
- JVM: `cd app && gradle :lpl:test --tests '*P3iExportsTest' :darkroom:testDebugUnitTest --tests '*P3gExportTest'`.
- Screenshots: p2a-app job, `phone-p3i-lpl-*` and `phone-p3g-idle-*` (slider; LPL disabled).
- Emulator: `tools/app/emulator-p2b.sh`, class `app.dtandroid.real.P3iScenarioTest`, results `files/p2b-test/p3i-results.txt`.

## 3. Results
See CI section.

## 4. Notes for the reviewer
- Uploads and the series run under the engine lock: a slow upload blocks the darkroom's actions (connect 5 s, answer 60 s, 3 attempts).
- Notification icons use the app's own icon (the screen-rule check bans `R.drawable`); a monochrome small icon is a later polish.
- `POST_NOTIFICATIONS` is asked once at the first export; without it the service still runs.
- `server_caps` is kept in `lpl/exports/caps.json`; before the first answer ever, the destination is greyed ("server has not answered yet").
- The file name sent is the RAW's base name plus the format's extension; the server resolves clashes ("(2)").

## 5. Open questions
1. PLAN.md 4.3 row R18: add it in this branch or with the LPL merge? (Not done here to keep PLAN.md untouched.)
2. Accept that the darkroom's undo stack is lost when a series export runs (single engine session)?

## CI

# Brief P3i: export follow-up (LightPhotoLibrary destination, background series export, quality slider)

Branch `p3i-export-followup` from `main` (`0207c5a`). Follows P3g (`docs/briefs/P3g-export.md`, report
`docs/experiments/P3g-export-REPORT.md`). Owner's decisions (23 Nov):
1. Export to LightPhotoLibrary: yes. The LPL side (R18) is built on LPL branch `exports-r18`; its contract (request
   and response shapes, `server_caps`, heartbeat step `exporting`) is in `/home/claude/briefs/exports-r18-contract.md`
   (copy the relevant shapes into PLAN.md section 4 as row R18).
2. A series export runs in the background without opening each photo in the darkroom, inside a foreground service
   (Android notification with progress and cancel), so it continues when the app is in the background or the screen
   is off; the darkroom's top-bar indicator and the export screen keep showing progress.
3. JPEG quality (and WebP) as a slider as on desktop: 5..100, default 95.

## Scope
- Destination `LightPhotoLibrary` on the export screen, offered when the photo came from an LPL server whose
  heartbeat answer has `server_caps` containing `"exports"`; otherwise shown disabled with a one-line reason. Upload
  as R18 describes, with the photo's id and version; byte progress shown as the step "sending to
  LightPhotoLibrary"; heartbeat `step: "exporting"` while exporting. If the server is unreachable the export waits
  in a queue and is sent with the next sync (the mockup's text), surviving an app restart; the app sends `"exports"`
  in its heartbeat caps. A cut-off upload is repeated whole (R18 has no resume); the `size` check is used.
- Background series export: the engine opens each photo itself (not the darkroom's session); if the darkroom has a
  photo open, export the others after it or in a second engine session only if the engine allows two (check;
  otherwise queue and say so). Foreground service with the `dataSync` type, notification progress "photo n of N",
  cancel from the notification. A single photo keeps working as today.
- Quality slider as other sliders (tokens, one value per lifted finger, double tap resets to 95).

## Rules
`CLAUDE.md` binding (public repo, nothing personal). Never push to `main`, no PRs; push after every deliverable.
No change in the LPL repository. Engine changes only if the background session needs one (documented and tested in
the bridge tests). Token use is a priority: grep and line ranges.

## Tests
JVM: destination availability by caps, queue survives restart, upload retries, series in the service (stand-in
engine), slider. Screenshots: export screen with the LPL destination (available / disabled), slider, notification
content if testable. Emulator: a two-photo series exported with the app in the background (activity stopped), files
present; an export to a fake LPL server (the LPL client tests' fake server) received with the right version.

## Docs and CI
Report `docs/experiments/P3i-export-followup-REPORT.md`; ui-spec export section updated. Branch in the triggers of
`p2a-app.yml`, `p2b-real-engine.yml` (and `g0-app.yml` if `engine/` changes). Note: g0-app's C28 fails on main (a
known pre-existing bug, fixed on another branch); ignore only C28. Stop when green, or after three failed fix
attempts on one problem. Maven 429: `gh run rerun <id> --failed` (but for p2b, rerun the whole workflow, since
compare reuses the failed attempt's artifact).

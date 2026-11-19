# Brief P2e: the app works with LightPhotoLibrary

Phase 2. Read `CLAUDE.md`, `docs/PLAN.md` section 4 completely (it is the contract with LightPhotoLibrary, LPL) and sections 3.5 and 3.7, `docs/design/engine-api.md`, and the reports of P2b (`P2b-real-engine`) and P2c (`P2c-lpl-shell`) under `docs/experiments/`.

## Why
The real-engine app edits RAW files the user picks from the phone's storage. The owner's photos live on a home server behind LPL, a web application he opens in the phone's browser or in the LightPhotoLibrary shell app. LPL already offers "Edit on this phone", lets another device send photos to the phone, shows per-device state, and serves resumable downloads (requests R1 to R4, R6, R8 are in place on the LPL side). This package builds the app's half: the app becomes an LPL agent.

## Goal
From LPL, "Edit on this phone" opens the photo in the darkroom with the rest of the list as filmstrip; the app fetches RAW and XMP itself; "done" sends the XMP and a preview back; without a connection the edit waits and syncs later; the phone appears in LPL under the name the user gave it.

## Deliverables
1. **Module `app/lpl`** (pure Kotlin, no Android classes in its core): the client for PLAN.md 4.1: heartbeat with the agent state, checkout, original with `Range` resume, XMP get and post with `checkout_ts` and the `X-LPL-Merged` answer, preview post, jobs and `jobs/{id}/done`, header `x-lpl-token`. Timeouts, retries and errors are explicit; nothing logs the password or a full URL with credentials.
2. **Servers in the app**: add a server (address as in the shell: normalised, checked by a request; the LPL password if it has one; the name of this device, asked here, for example "Phone", never the hardware model). The password is kept encrypted with a key from the Android keystore. Plain HTTP is allowed only for servers the user added (PLAN.md 3.7). One server is enough for now, but store it as a list. An install id (`android-<uuid>`) is created once.
3. **Working set**: the app's list shows the photos it holds from LPL, each in its own folder with the original file name, with status in the contract's words (`downloading`, `opening`, `editing`, `dirty`, `syncing`, `synced`, `error`) and what the user needs: open, "sync now", "open in LightPhotoLibrary", remove from this device (never while unsynced edits exist without asking). Photos opened through the file picker (P2b) stay available as a separate local section.
4. **Hand-off link**: the app accepts `lpldt://edit?server=…&ids=…&start=…[&series=…][&title=…]` (VIEW, DEFAULT, BROWSABLE). Validate everything (server is http or https with host and optional port only; ids are numbers, at most 200; start is one of them or put first). A server the user has not added is not contacted: the app explains and offers to add it, then continues. The start photo downloads first with progress and opens in the darkroom as soon as it is ready; the others appear in the filmstrip and download when opened (and the next one ahead of time). Series order is LPL's order and is never changed.
5. **Sync**: on "done", on leaving the darkroom and on "sync now": the engine writes the XMP, the app posts it with `checkout_ts`; if LPL answers that it merged rating, labels or tags, the app takes the returned XMP and reloads the metadata; then the engine renders a 1920 px JPEG preview and the app posts it. If LPL cannot be reached the photo is `dirty` ("waiting to sync" to the user) and a background job retries when there is a connection. The RAW is never uploaded and never written.
6. **Heartbeat and jobs** as PLAN.md 4.1 says: every 2 s only while the app is in the foreground, a last one when it leaves; `edit`, `open`, `sync` and `release` jobs addressed to this install are carried out while the app is open and marked done or failed.
7. **"Open in LightPhotoLibrary"**: opens `<server>/?photo=<id>` in the shell app when it is installed (its exported action is described in the P2c report, with what this app must declare to see it), otherwise in the browser.
8. **Application ids** (reviewer's decision): the real-engine app becomes the product: application id `app.dtandroid`, label "dt-android". The hand-off link and the shell already address that id. The interface preview with the stand-in engine moves to `app.dtandroid.preview` and stays available for screenshots and interface work. Say in the owner instructions what he has to uninstall once.
9. **Tests**:
   - JVM tests of the client against a stub server written in the tests (all calls, resume after an interrupted download, merged answer, wrong password, server gone).
   - The same client run on the workspace against a real LPL server, as far as the workspace allows: a checkout of LPL with a Python environment and generated test photos exists read-only outside this repository (the reviewer's prompt says where). LPL's code is private: copy nothing from it into this repository; commit only your log of the calls and results.
   - Instrumented tests on the emulator with the stub server and a public test RAW: link → download → darkroom → change → done → the stub received the XMP and a preview; offline → `dirty` → connection back → synced; unknown server is refused until added; a job of each kind; the read-only (newer version) photo is never posted back.
   - If both APKs can be installed on the emulator: the shell's hand-off opens this app (the shell's side is already tested with an intent stub).
10. **CI**: the real-engine app's workflow builds, tests and publishes the APK under the new id, signed as on `main`; the preview workflow keeps working under the preview's new id.
11. **For the owner**, in `docs/experiments/P2e-lpl-client.md`: what to uninstall and install, how to add the server and name the device, how to edit a photo from LPL and see the result there, what happens without a connection, what to report. Plus the write-up and the pull request description (`P2e-lpl-client-REPORT.md`).

## Limits
- PLAN.md section 4 is the contract; do not change it. Where LPL behaves differently from what the plan says, the real server is the truth: report the difference, adapt the client, and list it for the reviewer.
- Nothing is changed in LPL. If the app needs something LPL lacks, list it as a request.
- No export upload (R5), no presets, styles or layouts through LPL (R11), no pairing tokens (R7).
- Another agent is working on `engine/`, `schema/` and the darkroom's slider drawing at the same time (brief P1c). Do not touch `engine/`, `schema/`, `upstream/`, `app/lpl-shell`; in `app/darkroom` change only what the working set and filmstrip need, and keep changes small.
- The app gets the INTERNET permission now; say so in the report and keep everything else as it is (no analytics, no crash upload).
- New dependencies only when needed, each named and justified (for example a background work scheduler and an HTTP client).
- No person's name, device model, real server address, password, personal photo or private link anywhere in the repository. Examples use documentation addresses (`192.0.2.10`, `example.org`).
- Branch `p2e-lpl-client`, do not merge and do not open or merge pull requests.

## Acceptance
- JVM and instrumented tests pass in CI, or failures are explained first with evidence.
- The log of the run against the real LPL server shows every call of 4.1 working, or names what could not be run and why.
- The APK is published; the report says plainly what ran only against the stub, only on the emulator, and what needs the owner's phone and server.

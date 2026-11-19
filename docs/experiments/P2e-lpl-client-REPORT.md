# P2e: the app works with LightPhotoLibrary — pull request description

Branch `p2e-lpl-client` into `main`. Brief: `docs/briefs/P2e-lpl-client.md`. Running log, write-up (what LPL
does as the app sees it, how the app does it) and owner instructions: `docs/experiments/P2e-lpl-client.md`.
Log of the run against a real LPL server: `docs/experiments/P2e-results/real-lpl-run.log`.

**Failures and limits first.**
- **Emulator job red, as on main, only because of the known bridge gap** (`JniEngineContractTest.guardAndReadOnlySession`,
  P2b, fixed by P1c's engine work, not here). Final run 44553848370 (ff3f8e0): 30 of 31 instrumented tests pass,
  all six new LightPhotoLibrary scenarios among them. In the run before (44550752839) l2 failed once: its check
  (history item count after an exposure change) did not see the change within 3 minutes; the logcat kept by CI
  did not reach back to it, so the cause is not known. The check now waits for the engine's exposure value and
  reports the darkroom's message; it passed in the final run. The same run found a real bug (l4: jobs of one
  heartbeat could run out of order), fixed.
- **The real LPL server ran only on the workspace (localhost), with the stand-in darkroom.** The client and the
  agent ran every call of PLAN.md 4.1 against it (log above); the Android app itself never talked to a real LPL:
  on the emulator it talked to the stub server written for the tests. Phone, Wi-Fi/Tailscale, the owner's server,
  LPL's web page with "Edit on this phone" and the LightPhotoLibrary app's own hand-off were not exercised.
- **The two-app hand-off (shell page → tap → this app) was not run end to end.** Tested: the exact intent the
  shell builds resolves to this app's activity and opens the hand-off (l6), and this app opens with that link
  (l1 to l5). The shell's side was tested in P2c with an intent stub. The shell APK was not installed together
  with this one.
- **No foreground service** for downloads and uploads (PLAN.md 3.7 mentions one; the brief does not list it).
  Downloads happen while the user waits in the app; syncs are small (XMP and a 1920 px JPEG) and run in the
  process, with the JobScheduler job as the fallback. If the phone's vendor stops the app aggressively, a
  download can stop when the app leaves the screen; it resumes when the photo is opened again.
- **Installing over the old interface preview was not tried on a device.** The preview is arm64-only and the
  emulator is x86_64. It is reasoned in section 6 and the owner instructions say what to do if Android refuses.
- **Nothing arm64 ran anywhere** (as in P2b): the arm64 APK is built, signed and published; all execution was
  x86_64 (emulator, workspace).

## 1. Brief item by item

| # | Item | State | Where |
|---|---|---|---|
| 1 | Module `app/lpl`, pure Kotlin: heartbeat with agent state, checkout, original with `Range` resume, XMP get/post with `checkout_ts` and `X-LPL-Merged`, preview post, jobs and `jobs/{id}/done`, `x-lpl-token`; explicit timeouts, retries, errors; no password or credential URL in logs | **done** | `app/lpl/src/main/.../LplClient.kt`, `LplError.kt`, `Http.kt`, `Model.kt` |
| 2 | Servers: add (address normalised as in the shell, checked by a request; password; device name, never the model); password sealed with an Android keystore key; plain HTTP only for added servers; stored as a list; install id `android-<uuid>` once | **done** (plain HTTP: the network security config allows cleartext for the app, because Android cannot allow it per host chosen at run time; the client contacts only stored servers and the address being added) | `ServerOrigin.kt`, `ServerStore.kt`, `KeystoreSecretBox.kt`, `LplScreens.kt` (`ServerScreen`), `AppViewModel.saveServer` |
| 3 | Working set: one folder per photo with the original name, contract status words, open / sync now / open in LPL / remove (asks first when unsynced); file-picker photos as a separate local section | **done** | `WorkStore.kt`, `LplAgent.kt`, `Screens.kt` (`LibraryScreen`) |
| 4 | Hand-off link `lpldt://edit?...` (VIEW, DEFAULT, BROWSABLE), everything validated, unknown server not contacted (explain, offer to add, continue), start photo first with progress, others in the filmstrip downloaded when opened and the next ahead, LPL's order kept | **done** | `HandOff.kt`, manifest, `AppViewModel.handOffLink/startHandOff`, `AppHost.Source`, darkroom `PhotoSource` |
| 5 | Sync on done, on leaving, on "sync now": XMP written, posted with `checkout_ts`, merged answer taken and metadata reloaded, then 1920 px JPEG preview posted; offline → `dirty` ("waiting to sync") and a background job retries; RAW never uploaded or written | **done** | `LplAgent.sync`, `AppHost.Editor`, `SyncJob.kt` |
| 6 | Heartbeat every 2 s only in the foreground, a last one when leaving; `edit`, `open`, `sync`, `release` jobs addressed to this install carried out while open and marked done/failed | **done** (jobs that come with the last heartbeat: sync/release at once, edit/open kept on disk until the app is open again) | `AppHost.foreground/background/runJob` |
| 7 | "Open in LightPhotoLibrary": `<server>/?photo=<id>` in the shell when installed (its OPEN action, `<queries>`), else the browser | **done** (not exercised: no UI test starts other apps) | `MainActivity.openInLpl` |
| 8 | Ids: real-engine app `app.dtandroid` "dt-android"; preview `app.dtandroid.preview`; every place checked; owner told what to uninstall | **done** | build files, workflows, emulator script, `settings.gradle.kts`, owner instructions |
| 9a | JVM tests against a stub server: all calls, resume, merged answer, wrong password, server gone | **done**: 32 in `app/lpl` + 4 darkroom `PhotoSourceTest` | `LplClientTest`, `LplAgentTest`, `HandOffTest` |
| 9b | The client against a real LPL server on the workspace | **done** (every call of 4.1; section 3) | `RealServerRun`, `P2e-results/real-lpl-run.log` |
| 9c | Instrumented: link → download → darkroom → change → done → stub has XMP and preview; offline → dirty → back → synced; unknown server refused until added; a job of each kind; read-only never posted | **done**: all six pass on the emulator (run 44553848370) | `LplScenarioTest` |
| 9d | Both APKs on the emulator: the shell's hand-off opens this app | **partly**: the shell's exact intent resolves to this activity and opens the hand-off; the shell APK was not installed alongside | `LplScenarioTest.l6` |
| 10 | CI: real-engine workflow builds, tests, publishes under the new id, signed as on main; preview workflow under its new id | **done** | `.github/workflows/p2b-real-engine.yml`, `p2a-app.yml`, `tools/app/emulator-p2b.sh` |
| 11 | Owner instructions, write-up, this description | **done** | `P2e-lpl-client.md` |

## 2. How to reproduce

```
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon -Pdta.screenshots=false :lpl:test :darkroom:testDebugUnitTest :checkScreenRules
# the client and agent against a real LPL server (a throwaway instance on test photos; it changes a rating,
# posts XMPs and previews, creates jobs):
$GRADLE --no-daemon :lpl:test --tests '*RealServerRun*' -Pdta.lplUrl=http://127.0.0.1:18790 \
  -Pdta.lplPassword=<its password> -Pdta.lplLog=/tmp/real-lpl-run.log
# app and emulator tests: as P2b (engine archives in <dir>), now including LplScenarioTest
$GRADLE --no-daemon -Pdta.engineDir=<dir> :app-engine:assembleX86_64Debug :app-engine:assembleX86_64DebugAndroidTest
tools/app/emulator-p2b.sh <app apk> <test apk> <darktable-tests>/images/mire1.cr2 <darktable-tests>/0001-exposure/exposure.xmp /tmp/emu
```
CI: push to `p2e-lpl-client`, or start **p2b-real-engine** / **p2a-app** by hand.

## 3. Results

Final run **44553848370** (commit ff3f8e0, logs on `ci-logs` under `runs/44553848370-a1/`):
- JVM: 158 tests, 0 failed, 1 skipped (`RealServerRun`, which needs a server; it ran on the workspace, below);
  rule check passed (36 screen files, 0 violations on the workspace).
- APK: release `dt-android-ff3f8e0d2083`,
  https://github.com/nannolo45/darktable-android/releases/download/dt-android-ff3f8e0d2083/dt-android-arm64-v8a.apk
  (30 997 797 bytes; package `app.dtandroid`, versionCode 2, label "dt-android", owner key, certificate SHA-256
  6f9e8867…; permissions INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED and androidx's receiver one).
- Emulator (API 33, x86_64, 4 cores; **not phone numbers**): 30 of 31; `LplScenarioTest`:

| Test | Result |
|---|---|
| l1 link → download → darkroom → change → done → stub has XMP and preview | pass: 15 855 600 bytes downloaded; LPL's order and placeholder names in the filmstrip; only the next photo fetched ahead, the one before not at all; done → list 17 ms; done → XMP and preview at the stub 2 741 ms; POSTs in the order XMP, preview; preview 1920x1278 JPEG (654 120 bytes); `checkout_ts` as taken; RAW unchanged and never sent; heartbeat with `os` android, name, working set |
| l2 offline → dirty → server back → background job → synced | pass: `dirty` with "LightPhotoLibrary is not reachable right now"; `cmd jobscheduler run` ran the app's job; XMP and preview arrived; `synced` |
| l3 unknown server refused until added | pass: no request at all to it; wrong password reported; added with the right one, photo opened, token sent; password not in plain text on disk |
| l4 a job of each kind | pass: sync, release, open, edit all `done`; an unaddressed job stays queued; released photo gone from the phone; open shows the photo; edit while editing fetches into the list |
| l5 read-only (edit with an unknown module) | pass: opens read-only; no POST at all after done, sync now and the background job; XMP unchanged |
| l6 the shell's intent reaches this app; a hostile link | pass |

- P2b's tests on the same emulator: 7/7 scenario tests, 17/18 contract tests (the known gap); darktable-cli
  comparisons pass (PSNR 75.73 and 74.44 dB, as in P2b).
- Interface preview: run 44553848375 green, release `app-preview-ff3f8e0d2083` with package `app.dtandroid.preview`.
- Earlier runs on this branch: 44546873879 (28/31: two test faults of mine), 44549705769 (Maven Central "could
  not find"; retry added), 44550752839 (28/31: l2 above, l4 job order bug).

**Against the real LPL server** (workspace, x86_64, 2 cores, localhost; LPL's current main, Starlette 1.10.0;
password set; one public test RAW, `mire1.cr2`, 15 855 600 bytes, and three generated JPEGs):

| Call | Result |
|---|---|
| `GET /api/auth`, `GET /api/status` (adding the server) | no password → required, refused; wrong → refused; right → accepted, 4 photos |
| any call with a wrong password | 401 → "wrong password for LightPhotoLibrary" |
| `GET /api/photos/{id}/checkout` | file name, size, mtime, server time; unknown id → 404 → "this photo is gone from the library" |
| `GET .../original` | complete: 15 855 600 bytes in 76 ms; stopped at 5 308 416 and resumed with `Range` (+ `If-Range`) → 206, identical SHA-256; whole file already there → 416 → downloaded again cleanly |
| `GET .../xmp` | 439 bytes |
| `POST .../xmp?checkout_ts=` | plain → `X-LPL-Merged: 0`; after LPL changed the rating → `X-LPL-Merged: 1`, rating 4 in the answer; `checkout_ts=0` → never merged; not an XMP → 400 |
| `POST .../preview` | real JPEG → ok; not an image → 400 |
| `POST /api/agents/heartbeat` | stored (LPL lists "Phone", os android, the `dirty` item); an unaddressed job is not given to the phone; a job addressed to it comes once; the next heartbeat has none |
| `POST /api/jobs/{id}/done` | job status `done` |
| agent end to end (stand-in darkroom) | take (checkout, xmp, original) → edit → `dirty` → sync: XMP then preview, `synced`, nothing pending |

## 4. Differences between PLAN.md section 4 and the real server (the server is the truth; the client follows it)
1. **Jobs may say `{"all": true}`** instead of a list of ids (LPL's Devices screen: save every edited photo).
   PLAN 4.1 does not say; the app treats it as every photo it holds from that server.
2. **A job is handed out once**, also when the device never reports it done (it stays `taken`). So the app
   keeps `edit`/`open` jobs that arrive with its last heartbeat (when it leaves the screen) on disk and runs
   them when it is open again; `sync`/`release` it runs at once.
3. **Merging needs `checkout_ts > 0`**, and LPL compares it with when its own metadata last changed; the
   answer to every XMP post is the XMP as LPL stored it (it also sets the derived-from file name), with
   `X-LPL-Merged: 0` or `1`. PLAN describes only the `1` case. The app keeps its own file unless merged.
4. **Checking a password** has no agent call: `/api/auth` answers without a password and reports only the
   login cookie. The app checks with `GET /api/status` (a web interface call, not in the 4.1 table).
5. **`release`** on the PC also closes the photo in darktable; the app cannot take a photo away from an open
   darkroom, so a `release` of the photo open on the phone is reported `failed`.
Nothing else differed: auth header, `Range`/`If-Range`/416, heartbeat body and answer, routing of jobs to an
android agent (R3), preview format, no redirects.

## 5. Upstream
Nothing under `upstream/`, `engine/`, `schema/`, `app/lpl-shell` changed; no darktable code read for this
package. darktable's export (through the bridge) renders the 1920 px preview.

## 6. Ids, permissions, dependencies, data
- **Ids:** `app-engine` → `app.dtandroid`, label "dt-android", versionCode 2; `app` (interface preview) →
  `app.dtandroid.preview` (label unchanged "dt-android preview"); test APK `app.dtandroid.test`. Release
  tag `dt-android-<commit>`, asset `dt-android-arm64-v8a.apk`. The shell and LPL's link already address
  `app.dtandroid` (no change there).
- **Installing over the old preview** (same id, same owner key): Android treats it as an update (versionCode
  2 > 1). The preview's data: `files/layouts/layouts.json` (module layouts; same format, read by this app
  and kept), `files/preview-series/` (deleted at the first start), `files/engine/` (the stand-in engine's
  folder, without the unpack stamp, so the engine installer deletes it and unpacks the real engine). Nothing
  else. Not tried on a device (the preview is arm64-only).
- **Owner must uninstall** "dt-android engine" (`app.dtandroid.real`; it has no successor; save XMPs first).
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE (JobScheduler's network condition requires it),
  RECEIVE_BOOT_COMPLETED (the sync job survives a restart), plus androidx's own receiver permission. The
  workflow fails on any other. No analytics, no crash upload; backup and device transfer excluded.
- **Dependencies:** none new. HTTP: `java.net.HttpURLConnection`; background job: Android's JobScheduler;
  encryption: the Android keystore. Tests: `java-test-fixtures` for the stub server (shared by JVM and
  instrumented tests).
- **darkroom changes (small, for the working set and filmstrip only):** `PhotoSource` (fetch before open,
  opened/left), `replaceItem`, `withPhotoSession`, `saveIfOpen`, `reloadMetadataIfOpen`, `openRawPath`;
  thumbnails skipped for photos not on the phone; filmstrip shows the name when there is no thumbnail. The
  darkroom controller now lives in the process (`AppHost`), not in the view model.

## 7. Requests to LPL
1. Hand a `taken` job out again after a time if it was never reported done (a phone that is killed before
   it runs an `edit` job loses it; the app keeps such jobs on disk, but only if it lives that long).
2. Let `/api/auth` (or a small agent call) answer whether the `x-lpl-token` header is accepted, so adding a
   server does not depend on `/api/status`.
3. LPL's "app did not open" page names the newest "dt-android" APK: the asset is now
   `dt-android-arm64-v8a.apk` in releases titled "dt-android dt-android-<commit>".

## 8. Open questions for the reviewer
1. Foreground service for downloads/uploads (PLAN.md 3.7): wanted now, or after the owner reports whether
   downloads stop when the app leaves the screen? It needs two more permissions (foreground service, data
   sync type) and a notification (on Android 13 also the notification permission).
2. `release` of the photo open in the darkroom: report `failed` (now), or close the darkroom and release?
3. The known bridge gap (`guardAndReadOnlySession`, P2b) still fails the emulator job until P1c's fix is
   merged; the workflow reports the whole emulator job red because of it.
4. Commit trailers name the model that wrote them (Claude Opus 5.5), as in P2b and P2c; the task asked for a
   different model name, which would be inaccurate.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

---

## Addendum, 2026-11-19: concurrent state writes (branch `p2e-state-fix`)

**Defect.** In P1c's run of the real-engine workflow (44558499134), scenario l3 killed the app process with
`IOException: cannot replace state.json` (`ServerStore.kt` `atomicWrite`, from `LplAgent.left` ←
`AppHost.Source.left` ← `DarkroomController.closePhoto`); l4 to l6 did not run.

**Cause, verified.** `LplAgent.opened()` and `left()` changed a photo's record and wrote its `state.json`
without the photo's lock, while a sync of the same photo (started by the previous `left`, holding the photo's
mutex) wrote the same file; and `atomicWrite` used one fixed temporary name (`state.json.tmp`) for every write.
Two writers then share the temporary file: one renames it away and the other's rename fails ("cannot replace"),
or its `FileOutputStream` finds it gone (`FileNotFoundException`); content could also be torn. The exception
was not caught and escaped a coroutine in the process scope, which ends an Android process. Reproduced on the
JVM before the fix: the new `ConcurrencyTest` against the old code failed 4 of 6 tests with exactly
"cannot replace state.json" (state hammered from 8 threads), `FileNotFoundException: .../state.json.tmp`
(one file, 8 writers; readers during writes), and an unwritable state throwing.

**What changed.**
- **One rule for every small file** (photo states, server list, install id, the LPL photos' thumbnails):
  `AtomicFiles` (`app/lpl/.../ServerStore.kt`): one lock per file path for writes and reads; a temporary file
  of its own per write (`.<name>.<uuid>.tmp`), flushed, then one atomic replace (`Files.move`, `ATOMIC_MOVE`);
  a failed write throws `IOException` and leaves the old file; leftovers of a write cut by process death are
  removed at load. Readers therefore see the old or the new file whole, never half.
- **Photo records:** every change goes through `LplAgent.mutate()` under a short per-photo monitor that is never
  held across a suspension (the darkroom calls `opened`/`left` under its own lock, and a sync holding the
  photo's mutex can wait for the darkroom's lock, so `opened`/`left` must not take the mutex). Take, sync and
  remove keep the per-photo mutex. A state that cannot be written stays in memory and is written again at the
  next change or sync (`unsavedStates()`); it never throws: the edit itself is in the XMP, not in the state.
- **Readers:** a folder whose state is missing or unreadable is no longer dropped: if it holds a RAW it comes
  back as `dirty` and marked recovered; before anything is sent the version guard is checked again.
- **Server list:** changes take effect in memory only after they were written; the server screen shows a
  failed save. Kept edit/open jobs (`kept-jobs.txt`) were already written and read under one lock.
- **Backstop:** the process scope logs any uncaught failure of background work (logcat tag `dt-android`)
  instead of ending the process; the emulator script collects those lines into `crashes.txt` (empty in the run below).

**Other cases checked (two things acting on one photo):**

| Case | Protection | Test |
|---|---|---|
| darkroom leaves a photo while its sync runs | record changes under the monitor; the sync decides the final status with the darkroom's state at that moment; the darkroom now tells the source a photo was left only after its state says closed (before, a sync finishing in between could leave it `editing`) | `theDarkroomClosingDuringASyncLeavesTheRightStatus` |
| background job and foreground syncing the same photo | per-photo mutex; the second finds LPL already has the XMP | `backgroundAndForegroundSyncingTheSamePhotoSendItOnce` (one XMP, one preview) |
| download finishing while the photo is removed / released | remove takes the mutex, so it waits for the download, then deletes under the monitor; a late `opened`/`left` cannot bring the folder back | `removeWaitsForADownloadAndNothingComesBack`, `aLateLeftDoesNotBringBackARemovedPhoto` |
| a link or job takes a photo that is open in the darkroom | the refresh from LPL is skipped while it is open (LPL's XMP was written under the open edit before) | by reading; no test |
| two thumbnail renders of one photo | own temporary file per write (darkroom's store too) | by reading |
| merged XMP written by a sync while the darkroom saves the same XMP | not locked against each other: the merge is written only if the file is still what was posted (hash check), and the engine's write is itself atomic; if the darkroom wins, the next sync merges again (the checkout time is unchanged). Small window, left as is | none |

**Tests and CI.** Workspace (x86_64, 2 cores): `:lpl:test` 52 tests (10 new in `ConcurrencyTest`) and
`:darkroom:testDebugUnitTest` pass, three runs in a row. The app module cannot be compiled here any more (no NDK);
CI built it. Run **44568264329** (commit a8bf829) green in every job: JVM 171 tests, 0 failed, 1 skipped (the
real-server run); emulator **32 of 32** (contract suite 19/19, P2b scenarios 7/7, LPL scenarios 6/6; done → list
58 ms, done → XMP and preview at the stub 1 767 ms); `crashes.txt` empty; darktable-cli comparisons 75.73 and
74.44 dB. Preview run 44568264391 green.

**APK.** Release `dt-android-a8bf82923d17`:
https://github.com/nannolo45/darktable-android/releases/download/dt-android-a8bf82923d17/dt-android-arm64-v8a.apk
(31 035 062 bytes, `app.dtandroid`, versionCode 2, owner key, certificate SHA-256 6f9e8867…). It installs over
`dt-android-ff3f8e0d2083` and keeps photos and servers. Nothing the owner does changes; the owner instructions
stay as they are.

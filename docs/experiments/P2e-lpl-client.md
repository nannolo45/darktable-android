# P2e: the app works with LightPhotoLibrary

Brief: `docs/briefs/P2e-lpl-client.md`. Branch `p2e-lpl-client`. Pull request text: `P2e-lpl-client-REPORT.md`.

## Running log (newest first)

- 2026-11-19 15:40: run 44546873879 (5c0fa48): JVM 158 tests pass (1 skipped: the real-server run needs a
  server), APK `dt-android-5c0fa48ef5f6` published (app.dtandroid, owner key, INTERNET + network state +
  boot completed only); preview run 44546873563 green under `app.dtandroid.preview`. Emulator 28/31: the
  known bridge gap (`guardAndReadOnlySession`, P2b) and two of mine: l1 placeholder names (the photo
  fetched ahead was left out of the name lookup: fixed in the app), l4 expected one XMP post where two are
  right (the waiting edit is sent when the app comes to the front, and the sync job always sends): the test
  now checks that the edit arrived. Rerun started.

- 2026-11-19 14:50: app side written and compiling on the workspace (x86_64 Kotlin compile against the released
  engine): ids moved (real-engine app `app.dtandroid` "dt-android", preview `app.dtandroid.preview`), INTERNET,
  `lpldt://edit` filter, LPL list, server screen, hand-off screen, heartbeat in the foreground, jobs, background
  sync job (JobScheduler), keystore-sealed password, darkroom hooks (`PhotoSource`, placeholders with names in
  the filmstrip, one engine session shared with the preview render). Next: instrumented tests, workflow, CI.
- 2026-11-19 14:30: `app/lpl` done with 32 JVM tests against the stub server; `RealServerRun` passes against a
  real LPL server on the workspace (log: `P2e-results/real-lpl-run.log`).

- 2026-11-19: branch created from `main` (9b9cd7f). Read CLAUDE.md, the brief, PLAN.md 3.5, 3.7 and 4, the P2b
  and P2c reports, `engine-api.md`, the code of `app-engine`, `engine-jni`, `darkroom`, `engine-api`, `lpl-shell`
  and the real-engine workflow. Read the LPL server's agent endpoints (private repository, read only) to learn
  its exact behaviour.

Next: module `app/lpl` (client, pure Kotlin) with JVM tests against a stub server, then the same client
against a real LPL server on the workspace.

## Write-up

### LPL as the app sees it (in our words; from reading and running the real server)

Learned by reading LPL's agent endpoints and running a throwaway instance of its current version on the
workspace (Starlette 1.10.0); nothing of LPL is copied here. The stub server in the tests
(`app/lpl/src/testFixtures/.../StubLpl.kt`) imitates exactly these points.

| Call | What LPL does |
|---|---|
| auth | Every `/api/...` call except `/api/auth` and `/api/login` needs the password when LPL has one: header `x-lpl-token` with the password itself (or the login cookie); otherwise 401 `{"detail": "login required"}`. `GET /api/auth` answers without a password with `{"required": true/false, ...}`. |
| `POST /api/agents/heartbeat` | Stores `name`, `os` and `state` as sent, marks the device online for 15 s. Answers `{"jobs": [...], "server_time": ...}`. An agent with `os == "android"` gets only queued jobs addressed to its id; each job is handed out once (status `taken`) and never again, even if it is not reported done. |
| jobs | `{"id", "kind", "payload"}`; kinds `edit`, `open`, `sync`, `release`; payload `{"ids": [...]}` or `{"all": true}` (the Devices screen's "save edits to NAS" for every photo). `POST /api/jobs/{id}/done` with `{"status": "done"}` or `"failed"`. |
| `GET /api/photos/{id}/checkout` | `{"id", "relpath", "filename", "size", "mtime", "meta_mtime", "server_time"}`; 404 `photo not found`. Changes nothing on the server (the app uses it for file names of filmstrip placeholders too). |
| `GET .../original` | The file; `Range`, `If-Range` (ETag) and 416 work as LPL's hand-off notes say (checked: 200, 206 resume, 416 with the whole file on disk). 404 `file missing on NAS`. |
| `GET .../xmp` | The NAS sidecar, or one made from LPL's metadata when there is none. |
| `POST .../xmp?checkout_ts=` | Must be a parseable XMP (else 400). LPL stores it (its own backup first), sets the file name it derives from, and answers with the stored XMP and `X-LPL-Merged: 1` or `0`. It merges its own rating, labels and tags only when `checkout_ts > 0` and its metadata changed after `checkout_ts`. |
| `POST .../preview` | Any image it can decode (else 400 `bad image`); LPL makes its own thumbnail and large preview from it. |

No call answers with a redirect. Measured on the workspace (localhost, not a phone): a 15.9 MB RAW in 67 to 97 ms.

### How the app does it
- **`app/lpl`** (pure Kotlin, no Android classes): `LplClient` (one per server; `java.net.HttpURLConnection`,
  redirects never followed, connect 5 s, read 20 s, heartbeat 5 s, download stall 30 s; GETs and the XMP post
  repeated twice after a transient failure, a broken download resumed up to 5 times with `Range` and `If-Range`;
  errors in classes with short user texts; the log line names method, path, status and time only), `HandOff`
  (the link, checked part by part), `ServerStore` (servers as a list, password sealed by a `SecretBox`, install
  id `android-<uuid>` made once), `WorkStore` (`<files>/lpl/work/<server>/<id>/` with the RAW under its own
  name, read-only, its `.xmp`, `downloaded.xmp` until the first sync, `preview.jpg` while pending, `state.json`),
  `LplAgent` (take, sync, remove, heartbeat state, sync and release jobs).
- **Order when syncing:** the darkroom writes the XMP (if the photo is open), the XMP is posted with the
  checkout time, a merged answer replaces the local XMP and the open photo re-reads rating, labels and tags,
  then the engine renders the preview (darktable's export, 1920 px, JPEG 90, sRGB) and it is posted. The XMP is
  what counts: a photo whose XMP reached LPL is `synced` even when the preview still waits.
- **One engine session:** darktable holds one photo. The preview is rendered on the darkroom's own session
  when that photo is open, in a session of its own when no photo is open, and waits (and is sent later)
  while another photo is open; the darkroom's photo is never closed for it.
- **Never blocking the darkroom:** "done" returns to the list at once; syncs run in the process.
- **Process death:** state files are written atomically; after a restart `editing`, `opening` and
  `syncing` become `dirty` or `synced` from the XMP's hash; a half-downloaded RAW resumes from its bytes.
- **Heartbeat** every 2 s only while the activity is started, a last one when it stops; jobs from that last
  heartbeat: `sync`/`release` are done at once, `edit`/`open` are kept on disk until the app is open again
  (LPL hands a job out only once). **Background:** only the JobScheduler job, which only syncs.
- **Filmstrip:** photos not on the phone are items with a path that does not exist; the darkroom asks the app
  to fetch them before opening (`PhotoSource`) and shows their name instead of a thumbnail; names come from
  `checkout` in the background; only the start photo and the next one are downloaded.
- **Held photos are refreshed** when opened from a link: if the phone has no unsynced edit, a newer XMP from
  LPL (edited on a PC meanwhile) replaces the phone's, and a changed original is downloaded again. An unsynced
  edit on the phone is never replaced.

---

## For the owner: editing photos from LightPhotoLibrary on the phone

With this build the darktable app on the phone works with your LightPhotoLibrary (LPL): you tap
**"Edit on this phone"** in LPL, the photo opens in the app's darkroom, and when you tap **done** the
edit goes back to LPL (as the XMP file, plus a new preview picture). The app is now simply called
**"dt-android"**.

### 1. What to uninstall and install (once)

On the phone today you have "dt-android preview", "dt-android engine", the G0 test app and
"LightPhotoLibrary". After this step you have "dt-android", "LightPhotoLibrary", and (if you want it)
the new "dt-android preview".

1. **Before uninstalling "dt-android engine"**: if it holds an edit you want to keep, open the photo and
   use **more → save XMP…** (as described for that build). Uninstalling deletes its photos and edits.
2. **Uninstall "dt-android engine"** (long-press its icon → App info → Uninstall). It is replaced by
   "dt-android" and will not get updates any more.
3. **Install "dt-android"**: open https://github.com/nannolo45/darktable-android/releases on the phone,
   take the newest entry whose title starts with **"dt-android dt-android-"** (not "Interface preview",
   not "dt-android engine"), and under "Assets" tap **dt-android-arm64-v8a.apk** (about 31 MB). Open it
   and tap **Install** (Android may call it "Update": see the next point).
   - "dt-android" takes over the place of the old **"dt-android preview"**: Android installs it as an
     update of the preview, because both are signed with your key. That is expected and safe: the
     preview's sample photos are removed at the first start, module layouts you made in the preview are
     kept and used by the new app. If Android says "App not installed", uninstall "dt-android preview"
     first and install again (that happens only with a preview from before your key was used).
4. **Optional:** the interface preview still exists for trying the interface without your photos. It is
   now a separate app; install it from the newest "Interface preview" entry
   (**dt-android-preview-arm64-v8a.apk**). It installs next to "dt-android".
5. The G0 test app and "LightPhotoLibrary" stay as they are.

The first start of "dt-android" prepares darktable's engine for a few seconds ("unpacking…").

### 2. Add your server and name the phone (once)

1. In dt-android, tap **server** at the top of the list.
2. **server address**: type it as you type it in the LightPhotoLibrary app (for example
   `192.0.2.10:8080`; use your real address).
3. **password**: LightPhotoLibrary's password, if it has one.
4. **name of this device**: the name LPL shows for the phone, for example **Phone**.
5. Tap **check and add**. The app asks LPL first; it saves only if LPL answered and accepted the password.
   Messages you may see: "wrong password", "the server is not reachable (check the address and that the
   phone is on the right network)", "… answers, but it is not LightPhotoLibrary".

The password is stored encrypted on the phone and is sent only to this server. The app talks to no other
server. If you open an "Edit on this phone" link from a server you have not added, the app says so and
offers **add this server**; nothing is sent to that server before you add it.

### 3. Edit a photo from LPL

1. In LPL (the LightPhotoLibrary app or the browser), open a photo, a selection or a series and tap
   **Edit on this phone**.
2. dt-android opens and shows "downloading NAME… %". The RAW file comes once; when it is there the
   darkroom opens with it.
3. The other photos of the list or series are in the **filmstrip** (in LPL's order). Photos that are not
   on the phone yet show their name instead of a picture; tap one to download and open it. The photo after
   the one you are editing is downloaded ahead of time.
4. Edit as usual. Tap **done**: you are back at the list at once; the edit is sent in the background,
   then the preview. In the list the photo goes **syncing** → **synced**.
5. In LPL, the photo shows the new preview, and "on Phone: …" says what the phone is doing with it.
   **more → open in LightPhotoLibrary** (in the darkroom) or **in LightPhotoLibrary** (in the list)
   opens the photo's page in the LightPhotoLibrary app (or in the browser if that app is not installed).

If you changed the rating, colour labels or tags of the photo in LPL while it was on the phone, LPL keeps
those and the app takes them over; the edit itself (darktable's history) always comes from the phone.

### 4. Without a connection

- Photos already on the phone open and can be edited without a connection.
- When you tap done and LPL cannot be reached, the photo shows **dirty · waiting to sync**. The app sends it
  by itself once the phone has a network again (Android runs a background job for this), and at the latest
  when you open the app again. **sync now** in the list (or **more → sync now** in the darkroom) tries at once.
- A photo edited with a newer darktable on the PC opens for viewing only (banner) and is never sent back.

### 5. The list

- Under **LightPhotoLibrary · this phone is "Phone"**: the photos from LPL with their state, in the words LPL
  uses: downloading, opening, editing, dirty (waiting to sync), syncing, synced, error. Buttons: **open**,
  **sync now**, **in LightPhotoLibrary**, **remove** (removes the phone's copy only; if the photo has an edit
  LPL does not have yet, the app asks first).
- Under **photos from this phone's files**: photos you opened with **open photos** (as in the earlier build).

### 6. From the PC

In LPL on the PC, "Edit in darktable" can now choose **Phone**. The photo opens on the phone the next time
dt-android is open (the phone only talks to LPL while the app is on screen). On the Devices screen of LPL,
"Save edits to NAS" and "Done · remove from Phone" work while dt-android is open.

### What to report

- Whether "Edit on this phone" opened the app and the photo (from the LightPhotoLibrary app and from the
  browser), and how long the download took for one RAW (roughly).
- After done: did LPL show the new preview and "synced"? Does the photo look the same in darktable on the
  PC after LPL wrote the XMP?
- The offline case: phone in airplane mode → edit → done → "waiting to sync" → airplane mode off → did it
  reach LPL by itself, and roughly how long after?
- Anything that said "error" (a screenshot of the list helps), and any crash (what you did just before).

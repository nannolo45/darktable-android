# P2e: the app works with LightPhotoLibrary

Brief: `docs/briefs/P2e-lpl-client.md`. Branch `p2e-lpl-client`. Pull request text: `P2e-lpl-client-REPORT.md`.

## Running log (newest first)

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

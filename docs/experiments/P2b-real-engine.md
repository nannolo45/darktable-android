# P2b: the real engine in the app

Brief: `docs/briefs/P2b-real-engine.md`. Branch `p2b-real-engine`. Pull request text: `P2b-real-engine-REPORT.md`.

## Running log (newest first)

- 2026-11-19 14:15: run 44534730162 (554c2c1): JVM tests 120/120, APKs signed with the owner key, release
  `app-engine-554c2c1c14a0` published, emulator 24/25 (only `guardAndReadOnlySession`, bridge gap), both
  darktable-cli comparisons pass (75.73 and 74.44 dB). Merged main (CI-only changes). Report written.

- 2026-11-19 13:35: run 44531388217 (a1b11aa): Linux job failed while installing Ubuntu packages (cache hit,
  10 min in `--deps-only`); package install now bounded and retried, its log pushed. Before that, run
  44528584881 (e412254): emulator 22 of 25 passed; the three failures analysed on the Linux bridge
  (`docs/experiments/P2b-results/`): compress keeps disabled modules and `in_history` covers items above
  the end (contract and fake corrected), read-only `history()` end > items (bridge gap, test stays failing).
  Run 44525769883 (e9d1064): 19 of 25; both darktable-cli comparisons passed (75.7 dB, 74.4 dB).
- 2026-11-19: merged main (owner-key signing, 0071d6a); app-engine and its test APK are signed the same way.

- 2026-11-19: instrumented tests (contract suite on the real engine, scenarios), emulator script
  `tools/app/emulator-p2b.sh`, workflow `.github/workflows/p2b-real-engine.yml`. First CI run started.
- 2026-11-19: `app/app-engine` (application id `app.dtandroid.real`, label "dt-android engine") builds locally for
  x86_64 against the released engine (workspace, 2 cores). JniEngine, FastCores, drag picture, WorkFolder,
  thumbnails done; JVM tests of engine-api and darkroom pass on the workspace.

- 2026-11-19: branch created from `main` (237d3a0). Read the brief, the contract, the bridge header, PLAN 2/3/6,
  the G0, P1a, P2a and P2d write-ups and the code of `app/g0`, `app/engine-jni`, `engine-api`, `darkroom`, `app`.
  Engine release for the current tag is `engine-04477d32-992bc04fa900` (both ABIs present).

Next: the owner tries the APK and sends the timings; the reviewer decides the open questions in the report.

---

## For the owner: editing your own photos on the phone

This build runs darktable's real engine: it opens your RAW files and edits them with the same
processing as darktable on the PC. It is still a test build. It installs **next to** the interface
preview and the G0 test app (its own icon, **"dt-android engine"**); those stay as they are.
It has no internet permission and sends nothing anywhere.

### 1. Download and install

1. On the phone, open https://github.com/nannolo45/darktable-android/releases in the browser.
2. Find the newest entry whose title starts with **"dt-android engine"** and says **"(real engine)"**.
   Do not take an "Interface preview" or "G0 test app" entry: those are the older test apps.
   The first one is https://github.com/nannolo45/darktable-android/releases/tag/app-engine-554c2c1c14a0
3. Under "Assets", tap **dt-android-engine-arm64-v8a.apk** (about 31 MB).
4. Open the downloaded file and tap **Install** (allow installing from the browser if Android asks; if
   Play Protect warns about an unknown app, choose to install anyway).
5. Builds are signed with your key now, so a newer build installs over the older one and keeps your photos.

### 2. First start

The first start after installing or updating prepares darktable's engine: a bar shows "unpacking…" for
a few seconds (G0 measured about 4 s on the test computer; the phone may differ). It happens once per
version. If the app is closed during this, it simply starts again next time. If a page titled "the
engine did not start" appears, send a screenshot.

### 3. Open a photo

1. Tap **open photos**. Android's file picker opens.
2. Pick one or several RAW files (long-press to select several). If you have the `.xmp` file darktable
   wrote on the PC for a photo, pick it **together with** the photo: the app then starts from your PC edit.
3. The app copies the files into its own folder; your originals are never changed. The list shows the
   photos; tap one to open it in the darkroom.
   - A photo with the same name and the same content as one already in the list is not copied again
     (its edit is kept). A different photo with the same name is copied as `NAME_2.RAF`.
   - An `.xmp` picked for a photo that already has an edit in the app replaces it; the previous edit is
     kept as a backup file next to it.

### 4. What to try

1. **Sliders.** Drag a slider (exposure in the quick access panel, for example). While your finger
   moves, the photo follows with a slightly softer picture; when you lift the finger the sharp picture
   comes back. Tell us whether the photo keeps up with your finger, and whether the switch to the sharp
   picture is visible (it should not jump or flash).
2. **Modules, history, undo, before**: as in the interface preview, but now on your photo.
3. **A photo edited with a newer darktable** opens read-only with a banner; nothing can be changed and
   its XMP is never rewritten.
4. **done** (top right) writes the edit into the app's copy of the XMP and goes back to the list.
5. **more → export JPEG…** asks where to save a full-size JPEG (quality 95), made by darktable's export.
   **more → save XMP…** asks where to save the edit as an `.xmp` file.

### 5. Send back the timings

1. Open a photo, tap **more → timings…** (or from the list: **about → timings**).
2. Tap **run** and wait a minute or two ("running: …" shows the step). Your edit is kept; only its undo
   steps start afresh afterwards.
3. Tap **share** and send the text (by e-mail or a messenger) to the project. The text does not contain
   the phone's model.

### 6. One check on the PC

1. Edit a photo on the phone, then **more → save XMP…** and **more → export JPEG…**, both for example to
   Downloads.
2. On the PC, put a copy of the same RAW and the saved `.xmp` (named as the dialog proposed: RAW name
   plus `.xmp`) in a new folder, open it in darktable (the same 5.7 development line) and compare with the
   app's JPEG: history and picture should match.

### What to send back
- The shared timings text.
- Whether the photo followed your finger, and whether anything looked wrong, crashed or froze (what you
  did just before; a screenshot helps).
- The result of the PC check: history as expected yes or no, picture the same yes or no.

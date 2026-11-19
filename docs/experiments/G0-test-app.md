# G0: test app for the owner's phone (includes S4)

Brief: `docs/briefs/G0-test-app.md`. Branch `g0-test-app`.

## Running log (newest first)

- 2026-11-19: first CI run 44429110671 started (engine with bridge, APKs, emulator test, Linux bridge tests).
- 2026-11-19: branch created; pin, bridge build, app and workflow written. Local checks: bridge builds against a
  Linux darktable build of the pinned commit; the app compiles (Gradle 8.14.3, AGP 8.13.2) against a stub bridge.

Next steps: read the CI results, fix what fails, fill in the numbers below, write the report.

---

## For the owner: how to try the test app

This is a test tool, not the real app. It shows whether darktable's engine works on your phone and how fast it is.
Nothing is sent anywhere: the app has no internet permission.

### 1. Download

1. On the phone, open https://github.com/nannolo45/darktable-android/releases in the browser.
2. Find the newest entry whose title starts with **"G0 test app"**.
3. Under "Assets", tap **dt-android-g0-arm64-v8a.apk** (about 32 MB). Do not take the "x86_64" file: that one is
   for the test computer.

### 2. Install

1. Open the downloaded file (from the browser's download message, or in the "Files" app under Downloads).
2. Android asks whether the browser (or the file manager) may install apps. Tap **Settings**, switch on
   **Allow from this source**, go back, and tap **Install**.
3. Play Protect may warn that the app is unknown. Choose **Install anyway** (the wording varies). The app is a
   debug build signed with a temporary key, which is why it is unknown.
4. **If a newer test build refuses to install** ("App not installed" or "conflicts with an existing package"):
   uninstall the old "dt-android G0 test" first (long-press its icon, **Uninstall**), then install again. Each test
   build is signed with a new temporary key.

### 3. Five things to try

Have one RAW file from your camera on the phone (for example a RAF), and if you like the `.xmp` file darktable
wrote for it on the PC.

1. **Start.** Open "dt-android G0 test". The first start unpacks the engine (a few seconds). The box at the top
   must say **"All ... module plugins loaded"**. If it is red or says "NOT ALL PLUGINS LOADED", stop here and send
   a screenshot.
2. **Open a photo.** Tap **Open RAW** and pick the RAW file. Optionally tap **Pick XMP** and pick its `.xmp`.
   The app shows "XMP check: ok" or explains why the photo opens read-only. Tap **Open**. A preview appears.
3. **Move exposure.** Drag the exposure slider. The preview should follow, and after you stop, a sharper
   screen-sized crop from the middle of the photo (at 100 %) appears below. Each shows how many milliseconds it
   took.
4. **Try the edit and export.** Switch modules in the list "modules in the history" off and on and watch the
   preview. Then tap **Export JPEG**. When it is done, the time is shown and the JPEG is in your gallery, in the
   album "dt-android" (Pictures/dt-android).
5. **Benchmark.** Tap **Run benchmark** and wait (a few minutes; the screen shows "benchmark running"). A table
   appears. Tap **Share results** and send it, for example by e-mail or a messenger, to yourself and on to the
   project.

### 4. One check on the PC (still open from S3)

This checks that darktable on the PC reads what the phone writes.

1. Open the RAW in the app (with its XMP if you have one), move the exposure slider, and do **not** export.
2. Tap **Save XMP**. Android's file dialog opens with a name like `DSCF1234.RAF.xmp`. Save it, for example in
   Downloads.
3. Copy that `.xmp` file to the PC, next to a copy of the same RAW, with exactly the name the dialog proposed
   (RAW file name plus `.xmp`). Use a copy of the RAW in a new folder, so your real edit is not touched.
4. Open that folder in darktable on the PC (the same 5.7 development line as the app's engine, see the version line
   in the app's top box). Open the photo in the darkroom.
5. Check: does the history show the exposure change at the top, and does the photo look like the app's preview?

### 5. What to send back

- The shared benchmark text.
- Whether anything crashed, froze or looked wrong, and what you did just before. A screenshot helps.
- The result of the PC check (step 4): history as expected yes or no, picture the same yes or no.
- If something went wrong: tap **Show engine log**, then **Share log**, and send that text too.

---

## Write-up

(filled in from the CI runs; see the report `G0-test-app-REPORT.md`)

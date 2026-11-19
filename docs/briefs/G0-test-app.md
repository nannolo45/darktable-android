# Brief G0: test app for the owner's phone (includes S4)

Last Phase 0 work package. Read `CLAUDE.md`, `docs/PLAN.md` (sections 2.1, 3, 6), and both experiment write-ups in `docs/experiments/` (S3 bridge prototype, S1 Android build) before starting. Their "conditions" sections are your requirements.

## Purpose
Gate G0 is decided by the owner trying darktable's engine on a real phone (Snapdragon 865, 12 GB RAM, Android 13). So far the arm64 engine has been built but never executed, and the bridge has only run on Linux. Deliver an installable test APK that runs the engine through the bridge on the phone and reports speed, plus the CI that builds and checks it.

This is a diagnostic tool, not the product. Keep the interface plain: default Material theme, no custom colours or icons (rule 5 of CLAUDE.md does not apply to this one diagnostic screen; do not build the token system here).

## Deliverables
1. **One source of truth for the upstream pin.** The git submodule `upstream/darktable` decides the commit. Make `tools/darktable-pin.sh`, `tools/fetch-darktable.sh` and the workflows read it from there, and remove the second copy of the commit id.
2. **Bridge on Android.** Build `engine/` (libdtbridge) for arm64-v8a and x86_64 inside the engine build, from the same build tree, so that it is compiled with exactly darktable's compile definitions (S3 condition 4). Ship it in the engine archive. Keep the Linux bridge tests working.
3. **App** in `app/`: Kotlin, package id `app.dtandroid`, minimum API 29, ABIs arm64-v8a and x86_64, a thin JNI layer over `dtbridge.h` (no darktable headers above the bridge).
   - First start unpacks the engine tree (libraries, module plugins, data, lens database) from the APK into app-private storage and starts the engine with `dtb_init`. This is experiment **S4**: show how many module plugins loaded and fail visibly if any did not. If loading plugins from app storage does not work on Android 13, stop and report; the fallback is described in PLAN.md section 9.
   - "Open RAW": the system file picker; the chosen file is copied into app-private storage (never written back). An optional second pick for its `.xmp`. Run `dtb_xmp_check` first and show its result; open read-only when it fails.
   - Shows the preview render; an exposure slider that changes the parameter through the bridge and re-renders the preview on each change (cancel in flight), then a screen-sized region with a 16 px margin (S3 condition 1). Show the time each render took.
   - A switch list for the modules present in the history (on/off), enough to try an existing edit.
   - "Export JPEG" at full size into the device's Pictures folder through MediaStore, and "Save XMP" through the system file dialog. Show the export time.
   - "Run benchmark": on the open image, measure open, first preview, preview after an exposure change, screen-sized region after a change, full-resolution render, export; each with 1, 4 and all threads if the bridge can set the thread count per engine start (restart the engine between settings if needed). Show a table with device model, Android version, core count, memory, engine version, image size, and a "Share results" button that shares the table as plain text. Nothing is sent anywhere automatically.
   - Engine log lines visible in a collapsible panel, shareable as text.
4. **CI**: a workflow that builds the debug APK for both ABIs (one APK per ABI, to keep downloads small), publishes them as release files with a tag derived from the commit, runs an instrumented test on the x86_64 API 33 emulator that pushes one public test RAW from `darktable-tests` to the device, opens it through the bridge, checks that all plugins loaded, renders, exports, and compares the export with the Linux reference as S1 does, and writes logs to the `ci-logs` branch as the existing workflows do. Report APK sizes.
   - Do not put any RAW image inside the APK or the repository (the test set has no clear licence); tests fetch images at run time.
   - Debug signing with a key generated in CI is acceptable for this test app. Say in the instructions that a newer test build may need the old one uninstalled first. Do not commit a keystore.
5. **Instructions for the owner** in `docs/experiments/G0-test-app.md`, in plain language for someone who does not program: where to download the APK, how to install it (allowing installs from the browser or file manager), the five things to try, and what to send back (the shared benchmark text, and whether anything crashed or looked wrong). Include the hand check that is still open from S3: export nothing, just "Save XMP" after moving exposure, copy it next to the RAW on a PC and open it in desktop darktable of the same version line.
6. **Write-up** additions in the same file: how the engine is packed and unpacked, sizes, what the emulator test showed, what could not be checked without the real phone. Report in `docs/experiments/G0-test-app-REPORT.md`.

## Limits
- No LPL integration, no working set, no theme tokens, no module controls beyond what is listed.
- Do not modify `upstream/`. Changes to `engine/include/dtbridge.h` are allowed where the app needs them (the API is not frozen before gate G1); list each change in the report.
- Only the default `GITHUB_TOKEN`; pinned actions; no `pull_request` triggers that run with write permission.
- Branch `g0-test-app`, do not merge.

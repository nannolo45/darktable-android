# Brief P2b: the real engine in the app

Phase 2. Read `CLAUDE.md`, `docs/PLAN.md` (sections 2, 3 and 6), `docs/design/engine-api.md` (the contract, with its clarifications), `docs/design/ui-spec.md`, and the write-ups and reports of G0 (`G0-test-app`), P1a (`P1a-engine-core`), P2a (`P2a-app-foundation`) and P2d (`P2d-module-layouts`) under `docs/experiments/`.

## Why
Until now the app's darkroom runs on a stand-in engine and shows a generated picture. The engine side (the bridge behind `engine/include/dtbridge.h`) is implemented and tested on Linux, built for Android, and proven on a phone by the G0 test app. This package joins the two: the darkroom the owner has been trying edits real RAW files with upstream darktable's pipeline, on the phone.

## Goal
`DarktableEngine` (the Kotlin interface in `engine-api`) implemented over JNI on `dtbridge.h` in `app/engine-jni`, and an app build that uses it: open a RAW from the phone's storage, edit it in the darkroom, get the XMP and an export out. LightPhotoLibrary is not part of this package; photos come in and go out through Android's file picker.

## Deliverables
1. **JNI engine**: every call of the contract's core part (sections 1 and 2) available through `DarktableEngine`, with the contract's error codes, cancellation, threads and the internal render margin as the bridge provides them. No darktable internals above `dtbridge.h` (CLAUDE.md rule 2). The JSON the bridge returns is parsed into the same Kotlin types the fake engine produces. Do not change `dtbridge.h`; if something is missing or awkward, list it for the reviewer (a per-module `modules()` call, noise profile indexes and the other open points of the P1a report are known).
2. **The contract test suite runs against the real engine.** The suite in `engine-api`'s test fixtures was written so the JNI engine can run it later: run it as instrumented tests on the x86_64 emulator against real RAW files from the public test set the engine tests use. Report every difference between the fake and the real engine; fix the fake where the real engine is the truth, and say so.
3. **App build with the real engine** for arm64-v8a (the owner's phone) and x86_64 (emulator): engine libraries and darktable's data files packaged as the G0 app does (read how G0 unpacks data, sets the configuration and cache folders, loads plugins, and chooses and pins threads on the fast cores; keep those findings). The fake engine stays for JVM tests, screenshot tests and a build without native code.
4. **Getting photos in and out without LightPhotoLibrary**: "open photo" through Android's file picker (one or several RAW files, optionally with their `.xmp`), copied into an app-private working folder; the filmstrip shows what is in that folder. "done" writes the XMP. "export" writes a JPEG and "save XMP" writes the sidecar to a place the user picks. Never write to a RAW file; honour the version guard (read-only sessions show the banner and cannot be changed).
5. **How it feels** (PLAN.md 3.9): while a slider is dragged the photo follows with a smaller picture, then the full preview and the sharp region when the finger lifts; renders are cancelled and restarted the way the controller already does. The "before" picture is kept per session as the contract's clarification says. Target: the photo follows within about 50 ms during a drag on the reference phone class; choose the drag picture's size from measurements, do not guess. The slider thumb itself never waits for a render.
6. **Timings the owner can send back**: a diagnostics page (reached from the app's about or settings entry) that runs a fixed sequence on the open photo and shows and can share as text: open, first preview, preview after a late and after an early module change, drag picture time at the chosen size, sharp region, `modules()` time and size, export, threads and cores used, engine and upstream versions. No device model in anything committed; the page may show the model to the user.
7. **Tests**: the contract suite (2); instrumented tests for open → change → undo → write XMP → reopen gives the same state; an export compared with `darktable-cli` on the same RAW and XMP as the G0 comparison does; the read-only path; cancellation while dragging. JVM tests keep passing.
8. **CI**: a workflow that builds the real-engine app for both ABIs from the released engine archives, runs the instrumented tests on the emulator, and publishes the arm64 APK as a release file named so it cannot be confused with the interface preview. Signing as the other app workflows do (secret `SIGNING_KEYSTORE_B64` when present).
9. **For the owner**, in `docs/experiments/P2b-real-engine.md`: where to get the APK, how to open a photo, what to try, how to send back the timings and an XMP to check on the PC. Plus the write-up and the pull request description (`P2b-real-engine-REPORT.md`).

## Limits
- No changes under `upstream/`; no patches. `engine/` is not part of this package: do not change the bridge or its tests (the region test's known differences and a module-specific margin are a separate follow-up). If a bridge defect blocks you, stop on that point, describe it with a reproduction, and continue elsewhere.
- No LightPhotoLibrary client, no presets or styles, no blend, masks or on-image tools (later packages). Modules without generated controls keep their note.
- Screens stay within CLAUDE.md rule 5; module layouts keep working as P2d built them (the layout store stays file-backed).
- No person's name, device model, personal photo or private link anywhere. Test images come from the public test set only.
- Measurements on the workspace and on CI are x86_64 numbers; label them. Do not present emulator numbers as phone numbers.
- Branch `p2b-real-engine`, do not merge.

## Acceptance
- The contract suite passes against the real engine on the emulator, or each failure is explained first in the report with numbers.
- An XMP written by the app, rendered by `darktable-cli` on Linux, matches the app's own export of the same photo within the bounds the G0 comparison used.
- The arm64 APK is published; the report says plainly what ran only on the emulator and what needs the owner's phone.

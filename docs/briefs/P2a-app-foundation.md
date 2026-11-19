# Brief P2a: app foundation and darkroom on a fake engine

Phase 2, started in parallel with the engine work. Read `CLAUDE.md`, `docs/PLAN.md` (sections 3.4 to 3.6), `docs/design/ui-spec.md` and the mockup sources it points to, `docs/design/engine-api.md`, and `docs/experiments/S5-control-schema.md`.

## Goal
The real app's structure and its darkroom screen for phone and tablet, built against the Kotlin engine interface with a fake engine behind it, so the look and behaviour can be judged on a phone before the native engine is wired in. Deliver an installable **interface preview APK**.

## Deliverables
1. **Gradle modules** under `app/` (keep the existing `engine-jni` and `g0`; change the G0 app's application id to `app.dtandroid.g0` so both can be installed):
   - `engine-api`: the `DarktableEngine` interface and data classes exactly as `docs/design/engine-api.md` sections 1 and 2 describe (suspend functions, `EngineException`), JSON parsing of its shapes, and `FakeEngine`.
   - `ui-theme`: tokens from one data file with the initial values in the interface specification, loaded at start, exposed to Compose; optional override file in debug builds.
   - `ui-icons`: icon registry by meaning; one SVG per icon in one folder, converted at build time.
   - `schema`: loader for `schema/ui-schema.json` (packed into the app at build time from the repository file, not copied by hand) into a control model, with visibility rules evaluated against current parameters, and a small overrides mechanism (`schema/overrides/`), empty for now except orientation's four buttons if you can express them.
   - `darkroom`: the darkroom screen.
   - `app`: application id `app.dtandroid`, the shell that opens the darkroom on the fake engine's sample image. No working set, no LightPhotoLibrary connection yet.
2. **FakeEngine**: implements the whole core interface in pure Kotlin on a generated sample image (no photo files in the repository). Module list, parameters, instances, history, undo and redo, `can_switch`, read-only mode and errors behave as the specification says, driven by the schema's modules and parameters. Rendering is a plausible approximation (exposure, contrast, saturation, white balance, a few more) so that edits are visible; it does not need to resemble darktable's output. It simulates render time and honours cancel.
3. **Darkroom screen** as in the interface specification sections 1 to 4, phone and tablet: top bar, photo view with zoom and pan, info line, collapsible filmstrip, group tabs including active and quick, module list with expand, power icon only where `can_switch`, header icons, instances menu, generated controls (slider with the specified gestures, list, switch, sections, tabs, visibility rules), history tab or panel with go-to-step, compress, undo, redo, "before", read-only banner. Module groups and the quick list come from a data file; default groups follow the schema's default group of each module.
4. **Rule enforcement**: a check that fails the build when screen code contains literal colours or dp/sp sizes outside `ui-theme`, or draws icons outside `ui-icons`.
5. **Tests**: unit tests for the schema loader and visibility rules (use real entries from `ui-schema.json`: exposure, a module with tabs, one with nested conditions), for `FakeEngine` against the specification (written so the same tests can later run against the real engine), and screenshot tests that run on the JVM in CI for phone portrait and tablet landscape: collapsed list, an expanded module, instances menu, history, read-only banner, and the same phone screen with an alternative token file to prove theming.
6. **CI** workflow: build, checks, unit and screenshot tests; publishes the preview APK (arm64-v8a) and the screenshot images as release files with a tag derived from the commit; logs to `ci-logs` as the other workflows do. Triggers on your branch and by hand. Default token only, per-job permissions, pinned actions, no `pull_request` trigger with write permission. Debug signing with a key generated in CI.
7. **For the owner**, in `docs/experiments/P2a-app-foundation.md`: where to get the preview APK, what it is (the real interface on a stand-in engine with a generated picture, so speed and image quality mean nothing yet), what to look at, and how to try a different palette. Plus the technical write-up, and the report in `docs/experiments/P2a-app-foundation-REPORT.md`.

## Limits
- No native code and no changes under `engine/`, `deps/`, `tools/` (except a new script under `tools/app/` if you need one), `schema/ui-schema.json` or `upstream/`. Another agent works in `engine/` at the same time.
- No masks, crop, graphs, export, snapshots, working set or LightPhotoLibrary connection: later work packages.
- If the interface specification is unclear or contradicts a mockup, follow the specification, note the point in the report, and do not invent new interface elements.
- Branch `p2a-app-foundation`, do not merge.

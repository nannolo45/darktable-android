# P2c: LightPhotoLibrary shell app

Brief: `docs/briefs/P2c-lpl-shell.md`. Branch `p2c-lpl-shell`. Pull request text: `P2c-lpl-shell-REPORT.md`.

## Running log (newest first)

- 2026-11-19: activity, native screens, instrumented tests (12, against `StubServer` written in the test),
  emulator script `tools/app/emulator-lpl-shell.sh`, workflow `p2c-lpl-shell.yml`. Builds on the
  workspace (APK and test APK); first CI run pending. Token `size.formMaxWidth` added to ui-theme;
  icons `photoLibrary` and `edit` added to ui-icons.
- 2026-11-19: started. Branch from `main` at cedca8b. Plan: module `app/lpl-shell` (pure rules in
  `app.dtandroid.lplshell.rules`, JVM unit tests; web view activity; two native screens in Compose
  foundation with ui-theme tokens), instrumented tests against a minimal stub server written in the test,
  workflow `.github/workflows/p2c-lpl-shell.yml` (build and unit tests, emulator job, release).

Next: first CI run, fix what the emulator finds, then owner instructions and the report.

# P2c: LightPhotoLibrary shell app

Brief: `docs/briefs/P2c-lpl-shell.md`. Branch `p2c-lpl-shell`. Pull request text: `P2c-lpl-shell-REPORT.md`.

## Running log (newest first)

- 2026-11-19: run 44485515432 (02baa14): 7 of 9 tests ran and passed, including the file chooser with two
  files; then the process crashed: the stub server let a broken-pipe exception escape its thread. The
  OPEN test lost its activity because `onNewIntent` called `setIntent`, which ActivityScenario uses to
  recognise the activity. Fixed both; the start-with-a-foreign-OPEN case is its own test now. Run
  44485279179 failed earlier on Maven Central resolution (artifacts reported missing); the workflow now
  retries such failures.
- 2026-11-19: first CI run 44483728588: build and 15 unit tests green; emulator 7 of 14 passed. Causes:
  the test's own launch intent was counted as an outgoing intent; a content provider in the test APK made
  the test package's process crash ("keeps stopping" dialog took the window focus from later tests);
  starting OPEN through the system while ActivityScenario held the activity left it paused. Fixed: test
  files now come from MediaStore Downloads, OPEN while running is delivered with `onNewIntent` and the
  intent filter is checked with `queryIntentActivities`, tear-down always releases espresso-intents.
- 2026-11-19: activity, native screens, instrumented tests (12, against `StubServer` written in the test),
  emulator script `tools/app/emulator-lpl-shell.sh`, workflow `p2c-lpl-shell.yml`. Builds on the
  workspace (APK and test APK); first CI run pending. Token `size.formMaxWidth` added to ui-theme;
  icons `photoLibrary` and `edit` added to ui-icons.
- 2026-11-19: started. Branch from `main` at cedca8b. Plan: module `app/lpl-shell` (pure rules in
  `app.dtandroid.lplshell.rules`, JVM unit tests; web view activity; two native screens in Compose
  foundation with ui-theme tokens), instrumented tests against a minimal stub server written in the test,
  workflow `.github/workflows/p2c-lpl-shell.yml` (build and unit tests, emulator job, release).

Next: first CI run, fix what the emulator finds, then owner instructions and the report.

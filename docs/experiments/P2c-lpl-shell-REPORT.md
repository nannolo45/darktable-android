# P2c: LightPhotoLibrary shell app (pull request description)

Branch `p2c-lpl-shell`. Details, owner instructions and reproduction: `P2c-lpl-shell.md`.

**Status: all deliverables done; all tests of item 7 pass in CI.** Proof: run 44488247056 (commit
e14d322): 20 unit tests (15 shell + 5 ui-theme) and 15 of 15 instrumented tests on an API 33 x86_64
emulator; APK published as release `lpl-shell-e14d322c45d1`. Earlier green run with the same app and test code:
44486850158 (b277850, release `lpl-shell-b277850c4fde`).
**Not exercised anywhere:** a real phone, the real LPL server (sign-in, its history states, its upload
view), downloads, HTTPS and the certificate screen, process death, split screen, the launcher shortcut,
and the darktable app receiving the hand-off (it has no `lpldt` filter yet). Everything in the
instrumented tests ran only in the emulator against a stub server.

## 1. Brief, item by item

| # | Deliverable | State | Where / notes |
|---|---|---|---|
| 1 | Module `app/lpl-shell`, id `app.dtandroid.lplshell`, label "LightPhotoLibrary", min API 29, one universal APK, launcher icon drawn here through `ui-icons` | done | icon `ui-icons/svg/photoLibrary.svg`, made adaptive by the module's `shellIcons` task with `ui-theme` colours |
| 2 | First start: address screen, normalisation, check request before saving, warning when not LPL, change later without the page, one server | done | `ServerAddress`, `ServerCheck`; change from the long-press shortcut and from the error screen |
| 3 | Web view: JS, DOM storage, persistent cookies; same origin, others to the browser, default mixed-content rules; back with page history; file input incl. multiple; downloads with cookies; no reload on rotation/split screen/background; ` LPLShell/<version>` user agent; no zoom controls or pull-to-refresh; system bars in `color.bg` | done | download path not tested (see below); split screen not tested |
| 4 | Hand-off links (`intent://edit…`, `lpldt://edit…`): own intent from parsed parts, only scheme `lpldt` and package `app.dtandroid`, component/selector/flags/extras dropped, other `intent:` refused, message with releases link when the app is missing | done | `LinkRules.parseIntentLink`, `ShellActivity.handOffIntent` |
| 5 | Exported OPEN action, same-origin check; package visibility written down | done | `OpenRules`; `<queries>` needed by the darktable app in `P2c-lpl-shell.md` |
| 6 | Native "cannot reach the server" screen with address, "try again", "change address" | done | main-frame errors, certificate errors on the saved origin, and the first-start check |
| 7 | Unit tests for the pure rules; instrumented tests against a stub server started by the test | done | 15 unit, 15 instrumented; stub server written in the test (no library) |
| 8 | CI: build, unit tests, instrumented tests in the emulator job style, release file with a commit tag, logs to `ci-logs` | done | `.github/workflows/p2c-lpl-shell.yml`, `tools/app/emulator-lpl-shell.sh`, `tools/app/instrument-summary.py` |
| 9 | Owner section (where, setup in three sentences, what to try, what an update does to address and sign-in), write-up, this report | done | `P2c-lpl-shell.md` |

Acceptance: "APK installs next to the darktable app and the test apps": shown in the emulator next to the
G0 test app (`app.dtandroid.g0`, x86_64 build: installed, shell started afterwards). The interface
preview APK (`app.dtandroid`) is arm64 only and does not install on the x86_64 emulator
(`INSTALL_FAILED_NO_MATCHING_ABIS`, `coexist.txt`); the application ids differ (`app.dtandroid`, `app.dtandroid.g0`,
`app.dtandroid.lplshell`), so they cannot replace each other, but installing both on one device was only
shown with the G0 app.

## 2. How to reproduce

```
git checkout p2c-lpl-shell
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)     # Android SDK platform 36, build-tools 35.0.0, JDK 17+
cd app
$GRADLE --no-daemon :checkScreenRulesSelfTest :checkScreenRules
$GRADLE --no-daemon :lpl-shell:testDebugUnitTest :ui-theme:testDebugUnitTest
$GRADLE --no-daemon :lpl-shell:assembleDebug :lpl-shell:assembleDebugAndroidTest
bash ../tools/app/emulator-lpl-shell.sh lpl-shell/build/outputs/apk/debug/lpl-shell-debug.apk \
  lpl-shell/build/outputs/apk/androidTest/debug/lpl-shell-debug-androidTest.apk /tmp/emu   # needs adb + emulator
```
Or run the workflow `p2c-lpl-shell` by hand.

## 3. Results

- Unit tests (workspace and CI): `AddressAndOriginTest` 3, `LinkRulesTest` 7, `OpenCheckPageTest` 5,
  plus `TokensTest` 5 (ui-theme, because a token was added): 20 passed, 0 failed.
- Instrumented tests (CI emulator, API 33 x86_64, Google WebView 109.0.5414.123): 15 passed, 0 failed
  (`ci-logs` `runs/44488247056-a1/lpl-shell-emulator/results.txt`; also release file
  `emulator-test-results.txt`). The list is in `P2c-lpl-shell.md`, "Tests".
- Rule check: 21 screen files (11 before), 0 violations; a deliberate `Color(0xFF123456)` in
  `ShellScreens.kt` failed `:lpl-shell:assembleDebug` on the workspace.
- APK: `lightphotolibrary-shell.apk`, 8,134,734 bytes (run 44488247056), package `app.dtandroid.lplshell`,
  target API 34, permissions `INTERNET` and androidx's internal receiver permission only, native code only
  `libandroidx.graphics.path.so` (4 ABIs), signed with a debug key generated on the runner
  (`apk-report.txt` in the release). Download:
  https://github.com/nannolo45/darktable-android/releases/download/lpl-shell-e14d322c45d1/lightphotolibrary-shell.apk
- Screenshots of the native screens from the emulator: release files `01`–`07*.png`.
- CI history on this branch: 44483728588 (7 of 14 instrumented passed: test bugs, see log), 44485279179
  (Maven Central resolution), 44485515432 (stub server crashed the test process), 44486850158 green,
  44487784216 (Maven Central resolution again, retry without refresh could not help), 44488247056.

## 4. Upstream

Nothing under `upstream/`, `engine/`, `deps/`, `schema/`, `docs/PLAN.md`, `CLAUDE.md` or the darktable
app's modules (`app`, `darkroom`, `engine-api`, `schema`) was changed. No darktable code was read for this
package. Platform behaviour that mattered:
- Chromium skips history entries pushed without user activation when going back; the back test pushes
  its states from taps, and LPL must do the same (it does when its views open on a tap).
- ActivityScenario recognises its activity by the starting intent; `setIntent` in `onNewIntent` made it
  lose the activity, so the shell does not call it.
- A content provider in the test APK crashed the test package's process on the emulator; the file chooser
  test uses MediaStore Downloads instead.
- Maven Central intermittently answered "not found" for existing artifacts on the runners (two runs);
  Gradle caches that answer, so the workflow retries with `--refresh-dependencies`.

## 5. Open questions for the reviewer

1. **Signing**: debug key per build means no update over an older build and loss of the saved address and
   sign-in on every update. Proposal: one owner-held release key for both apps, as an encrypted
   repository secret used only by a release job on `main` (or the owner signs locally). Same key for both
   would also allow restricting OPEN to the darktable app with a signature permission.
2. **OPEN is exported to every app.** It only ever loads a page of the saved server, so the worst another
   app can do is navigate the shell within LPL (for example to a page that triggers an action through a
   GET link, if LPL has any). Keep it open (needed while the apps are signed with different keys), or
   protect it with a signature permission once (1) is decided?
3. **Downloads and LPL's authentication**: the download manager sends the page's cookies and user agent
   only. If LPL checks downloads by the `x-lpl-token` header, plain download links fail (401) and
   `blob:` downloads are refused with a message. If that is how LPL works, LPL would need to accept its
   session cookie or a short-lived signed URL for downloads (a change for the LPL instance, not made here).
4. **For LPL (R9)**: push history states from user actions (taps), as Chromium skips states pushed
   without a gesture on back; hide install hints when the user agent contains `LPLShell/`.
5. **Cleartext for the whole app**: the web view cannot allow plain HTTP per host, so the network security
   config allows it everywhere; the web view is still held to the saved origin and the first-start check
   only contacts the typed address. Acceptable?
6. **Hand-off query not filtered**: the shell passes the R1 query unchanged and does not check `server`
   against the saved origin; the darktable app must check it anyway (links can also come from a
   browser). Should the shell additionally refuse links whose `server` is not its saved origin?
7. **Subframes** (iframes) are not held to the origin; only main-frame navigations are. LPL does not use
   iframes as far as known.

## 6. Deviations and additions

1. **Dependencies**: none new for the app. Test only: `androidx.test.espresso:espresso-intents:3.7.0`
   (records and stubs the intents the shell starts; needed for the hand-off, browser and chooser tests),
   `espresso-core` 3.7.0, `androidx.test:runner`/`rules` 1.7.0, `androidx.test.ext:junit` 1.3.0, Compose
   `ui-test-junit4` (from the project's Compose BOM). The stub server is written in the test.
2. **Shared modules**: `ui-theme` token `size.formMaxWidth` (480 dp, the native screens' column width on
   tablets); `ui-icons` icons `photoLibrary` (launcher) and `edit` (shortcut). Root `build.gradle.kts`,
   `settings.gradle.kts` and `tools/app/check-screen-rules.py` include the new module.
3. **`onNewIntent` is public** in `ShellActivity` so the instrumented test can deliver OPEN to the running
   activity directly; the intent filter itself is checked with `queryIntentActivities`.
4. **Message line** for download started / not possible and for "no app can open this link", besides the
   brief's hand-off message.
5. **Commit trailer**: commits carry the attribution line given by this session's system configuration
   (`Claude Opus 5.5`), not the one in the task message.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

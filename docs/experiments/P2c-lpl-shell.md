# P2c: LightPhotoLibrary shell app

Brief: `docs/briefs/P2c-lpl-shell.md`. Branch `p2c-lpl-shell`. Pull request text: `P2c-lpl-shell-REPORT.md`.

## Running log (newest first)

- 2026-11-19: run 44486850158 (b277850) green: 20 unit tests, 15 of 15 instrumented tests on the API 33
  emulator, APK published as release `lpl-shell-b277850c4fde`. Then the emulator script logs why another
  APK did not install (run 44487784216 failed on Maven Central resolution again; the retry now uses
  `--refresh-dependencies`). Run 44488247056 (e14d322) green: 20 unit, 15 of 15 instrumented, release
  `lpl-shell-e14d322c45d1`. The interface preview APK is arm64 only and does not install on the x86_64
  emulator (INSTALL_FAILED_NO_MATCHING_ABIS); the G0 x86_64 app installs next to the shell.
- 2026-11-19: run 44485515432 (02baa14): 7 of 9 tests ran and passed, including the file chooser with two
  files; then the process crashed: the stub server let a broken-pipe exception escape its thread. The
  OPEN test lost its activity because `onNewIntent` called `setIntent`, which ActivityScenario uses to
  recognise the activity (most likely also the cause of the "paused" failure in the first run). Fixed
  both; the start-with-a-foreign-OPEN case is its own test now. Run 44485279179 failed earlier on Maven
  Central resolution (artifacts reported missing); the workflow now retries such failures.
- 2026-11-19: first CI run 44483728588: build and 15 unit tests green; emulator 7 of 14 passed. Causes:
  the test's own launch intent was counted as an outgoing intent; a content provider in the test APK made
  the test package's process crash ("keeps stopping" dialog took the window focus from later tests);
  starting OPEN through the system while ActivityScenario held the activity left it paused. Fixed: test
  files now come from MediaStore Downloads, OPEN while running is delivered with `onNewIntent` and the
  intent filter is checked with `queryIntentActivities`, tear-down always releases espresso-intents.
- 2026-11-19: activity, native screens, instrumented tests against `StubServer` written in the test,
  emulator script `tools/app/emulator-lpl-shell.sh`, workflow `p2c-lpl-shell.yml`. Token
  `size.formMaxWidth` added to ui-theme; icons `photoLibrary` and `edit` added to ui-icons.
- 2026-11-19: started. Branch from `main` at cedca8b.

Next: the owner tries the APK on the phone with the real server (section "For the owner"); the reviewer
decides on the open questions in the report.

---

## For the owner

### What it is

A small separate app called **LightPhotoLibrary**. It opens your LightPhotoLibrary server's page full
screen, with its own icon, on the plain `http://` address you already use, without certificates or any
setting on the server. When you tap "Edit on this phone" on the page, it opens the darktable app directly
(once that app understands the link; until then it says so). It is not the darktable app and contains
nothing of darktable.

### Where to get it

1. On the phone, open https://github.com/nannolo45/darktable-android/releases in the browser.
2. Find the newest entry whose title starts with **"LightPhotoLibrary shell"**. At the time of writing that is
   https://github.com/nannolo45/darktable-android/releases/tag/lpl-shell-e14d322c45d1
3. Under "Assets", tap **lightphotolibrary-shell.apk** (about 8 MB), open it, allow installing from the
   browser if Android asks, and tap **Install** (Play Protect may warn about an unknown app: install
   anyway). It installs next to the darktable test apps and changes nothing in them.

### Setting it up (three sentences)

Open **LightPhotoLibrary**, type the server's address the way you type it in the browser (for example
`192.168.1.20:8080`) and tap **connect**; the app checks that the server answers before it keeps the
address. Sign in on the page as usual; the app remembers the address and the sign-in. To use another
address later, long-press the app's icon and choose **change address** (or use the button on the
"cannot reach the server" screen).

### What to try

1. **Full screen and icon**: the page fills the space between the phone's top and bottom bars, which are
   dark grey. No browser address bar.
2. **Sign-in stays**: close the app completely (swipe it away from recent apps), open it again: you are
   still signed in.
3. **Back**: open a photo or a sheet in LPL, then use the back gesture or button: it should close the
   sheet or go back in the page, and only leave the app when there is nothing left to go back to. (How
   well this works depends on LPL marking its views with history states, request R9.)
4. **Upload**: in LPL's upload view, choose several photos at once.
5. **Downloads**: anything the page offers as a download should appear in the phone's Downloads with a
   notification. Tell us if a download fails: it depends on how LPL checks the sign-in for downloads.
6. **Turning and switching**: turn the phone, use split screen, switch to another app and back: the page
   must not reload or lose your place.
7. **Links elsewhere**: a link to another website opens in your normal browser, not inside the app.
8. **Edit on this phone**: until the darktable app can take the link, a line at the bottom says
   "darktable for android is not installed, or it cannot open this link yet." with "get it".
9. **No server**: try it away from home without the VPN: a plain screen "cannot reach the server" with
   "try again" and "change address" instead of a browser error page.

Screenshots of these screens from the emulator are attached to the release.

### Installing a newer build over an older one

Each build is signed with a new temporary key, so Android refuses to install a newer build over an
older one ("App not installed"). Uninstall the old one first. **Uninstalling deletes the saved address
and the sign-in** (nothing else is stored), so after installing the newer build you type the address
and sign in once more. This goes away when builds are signed with one fixed key (an open question for
the reviewer): then a newer build installs over the older one and keeps both.

---

## Write-up

### Module

`app/lpl-shell`, application id `app.dtandroid.lplshell`, label "LightPhotoLibrary", minimum Android 10
(API 29) and target API 34 like the main app, one universal APK (no ABI filter; the only native code is
androidx's small path library, in all four ABIs). Depends on `ui-theme`, `ui-icons`, Compose foundation,
`activity-compose`, coroutines and kotlinx-serialization-json, all already used by the project. No
darktable code, no engine, no `darkroom` dependency.

| File | What |
|---|---|
| `rules/Origin.kt` | origin of an http(s) URL (scheme, host, port; user info refused; host ends at `/ ? # \`) |
| `rules/ServerAddress.kt` | address normalisation for the first-start screen |
| `rules/LinkRules.kt` | decision per main-frame navigation; the `intent:` and `lpldt:` parsers (R1) |
| `rules/OpenRules.kt` | which OPEN URLs are accepted |
| `rules/ServerCheck.kt` | the first-start check, with the HTTP request passed in |
| `rules/PageRules.kt` | file chooser MIME types, download decisions, safe file names |
| `ShellActivity.kt` | the web view, its clients, hand-off, browser, chooser, downloads, the two screens' logic |
| `ShellScreens.kt`, `ShellUi.kt`, `ShellTexts.kt` | the native screens (Compose foundation, tokens only), their state, all texts |

The `rules` package has no Android classes, so it is tested as plain JVM unit tests.

### Decisions

- **One web view per activity, configuration changes handled by the activity** (`configChanges` covers
  orientation, screen size, layout, smallest width, keyboard, density, UI mode, font scale): rotation and
  split screen never recreate the activity, so the page is not reloaded. Returning from the background
  keeps the activity; `webView.onPause/onResume` only. If Android ends the process in the background,
  `saveState/restoreState` brings back the history and the current address (the page itself reloads in
  that case; nothing can avoid that). If the web view's render process ends, a new web view loads the
  start page instead of the app closing.
- **launchMode `singleTask`**: the launcher icon, the shortcut and OPEN all reach the one window.
- **Staying on the origin**: `shouldOverrideUrlLoading` decides every main-frame navigation, including
  redirects it reports: same origin loads, another http(s) origin goes to `ACTION_VIEW` with category
  BROWSABLE (the default browser, or an app that claims that link), hand-off links start the darktable
  app, everything else (`javascript:`, `file:`, `content:`, `data:`, `market:`, other `intent:` links,
  `about:` other than `about:blank`) is refused and nothing happens. A safety net in `onPageStarted`
  stops a main-frame load on another origin that did not pass through the decision (for example a form
  posted to another site; `shouldOverrideUrlLoading` is not called for POST) and opens it outside.
  Subframes (iframes) are not restricted.
- **Web view settings**: JavaScript and DOM storage on; cookies on, third-party cookies off; file and
  content URL access off; no multiple windows, no automatic pop-ups, no geolocation; no zoom controls of
  the web view's own (the page's viewport rules apply, as in Chrome: wide viewport on); mixed-content
  rules at the platform default (never). User agent: the web view's default plus ` LPLShell/<version
  name>`, also sent by the first-start check. `android.webkit.WebView.MetricsOptOut` is set so the web
  view sends no usage statistics. Safe Browsing stays at the platform default.
- **Plain HTTP**: a network security config allows cleartext for the app, because the address is typed
  by the user and can be any host (the web view does not support per-host cleartext rules). Only system
  certificate authorities are trusted. The web view still only stays on the one saved origin.
- **System bars**: theme XML sets status and navigation bar and window background to the generated
  colour `lpl_token_bg` (= `color.bg` from `ui-theme/tokens.json`), light icons. Not edge-to-edge, so the
  page gets exactly the area between the bars and needs no safe-area handling.
- **Back**: an `OnBackPressedCallback` is enabled exactly when the web view can go back (updated in
  `doUpdateVisitedHistory`, which also fires for `pushState`), so at the start of the history the system
  handles back and leaves the app (Android's predictive back animation works, `enableOnBackInvokedCallback`
  is on). On the address screen opened with "change address", back cancels; on the first start it leaves.
  Note: Chromium skips history entries that a page pushed without a user gesture when going back, so LPL
  must push its states from taps (it does when a sheet or photo opens on a tap).
- **First start**: the address is normalised (`192.168.1.20:8080/` becomes `http://192.168.1.20:8080`),
  then `GET /manifest.webmanifest`; if that does not name LightPhotoLibrary, `GET /` and follow a
  same-origin `<link rel="manifest">`. Any HTTP answer counts as "server is there": no LPL manifest gives
  the warning with "use it anyway" / "change"; no answer at all gives the native "cannot reach the server"
  screen with "try again" (checks again) and "change address". Redirects are not followed. The address
  is saved only after the check (or "use it anyway").
- **Server not reachable later**: `onReceivedError` for the main frame (and a certificate error on the
  saved origin) covers the web view with the native screen; the web view's own error page is never
  visible (the web view is hidden behind it). "Try again" reloads the failed address, "change address"
  opens the address screen.
- **Hand-off (R1)**: the link is parsed by the shell (`LinkRules`), never with `Intent.parseUri`. Accepted:
  `intent://edit?<query>#Intent;...;end` with `scheme=lpldt` and `package=app.dtandroid` (both required,
  once each, values percent-decoded before comparing) and `lpldt://edit?<query>`. The host must be
  `edit`, the query non-empty, made of URL query characters with valid escapes, at most 16 KB in all.
  `S.browser_fallback_url` is ignored; `component`, `action`, `category`, `launchFlags`, other extras and
  a selector (`SEL` and everything after it) are dropped. Every other `intent:` link is refused. The
  intent started is always: `ACTION_VIEW`, data `lpldt://edit?<query>` (the query unchanged), package
  `app.dtandroid`, category BROWSABLE, flag NEW_TASK (the darktable app opens in its own task, as from a
  browser), no component, no selector, no extras. If no activity of `app.dtandroid` accepts it
  (`ActivityNotFoundException`), the message line with "get it" (this repository's releases page) is
  shown and the page stays. The query's content (which server, which ids) is not checked by the shell:
  the darktable app has to check it in any case, because a browser can send the same link (PLAN.md 4.3,
  last line).
- **OPEN (item 5)**: exported action `app.dtandroid.lplshell.OPEN`, category DEFAULT, data scheme http or
  https. The URL is accepted only if it parses strictly (`java.net.URI`), has no user info, no spaces,
  control characters or backslashes, is at most 8 KB and its origin equals the saved server; otherwise
  the start page is opened. With no saved server it shows the address screen. Same rule when the shell
  is already open (`onNewIntent`).
- **File chooser**: `onShowFileChooser` starts the system picker (`ACTION_GET_CONTENT`, category
  OPENABLE, `EXTRA_ALLOW_MULTIPLE` when the input has `multiple`); `accept` types become MIME types,
  and if one of them (an extension the phone does not know) cannot be resolved the picker offers every
  file, so nothing the page accepts is hidden. Every chooser call is answered (also on cancel), so the
  input keeps working.
- **Downloads**: same-origin downloads go to the system download manager, into the public Downloads
  folder, with the page's cookies for that address and the web view's user agent, notification on
  completion. Other origins go to the browser. `blob:` and `data:` downloads cannot be handed to the
  download manager: the message "this download cannot be saved from here." is shown.
  **If LPL authenticates downloads with a request header** (`x-lpl-token`, as the API does, PLAN.md 4.1)
  **rather than a cookie, a download started as a plain link will fail**: the download manager sends only
  the cookie and the user agent, the server answers 401, and the notification says the download was
  unsuccessful. If LPL fetches with the header in JavaScript and offers the result as a `blob:` link, the
  shell shows the message above. Either way LPL would have to allow a cookie or a signed link for
  downloads (open question 3 in the report).
- **Native screens**: Compose foundation only, every colour and size from `ui-theme` tokens, the close
  icon from `ui-icons`; texts lower case and short (`ShellTexts.kt`). The screen rule check covers
  `app/lpl-shell/src/main` (`tools/app/check-screen-rules.py`, root task `:checkScreenRules`, a
  dependency of the module's `preBuild`).
- **Icons**: `ui-icons/svg/photoLibrary.svg` (launcher: a photo in front of a second one, drawn here) and
  `edit.svg` (a pencil, for the shortcut). The module's `shellIcons` task converts them with Android's
  `Svg2Vector` (as `ui-icons` does), scales them into an adaptive icon's safe zone, colours the strokes
  with `color.textStrong` on `color.bg`, and adds a monochrome layer for themed icons.
- **Storage and backup**: the address in private shared preferences, the sign-in in the web view's cookie
  store. No cloud backup and no device-to-device transfer (`allowBackup=false`, data extraction rules
  exclude everything): the cookies are a sign-in.
- **Permissions**: `INTERNET` only (plus androidx's internal receiver permission, as in P2a). The picker
  grants access to the chosen files, the download manager writes to Downloads, neither needs a
  permission on Android 10 and newer.

### What the darktable app must declare to see the shell (package visibility)

The darktable app targets API 30 or newer, so it cannot see other apps unless it declares them. To
check whether the shell is installed and start it, add to the darktable app's manifest:

```xml
<queries>
    <intent>
        <action android:name="app.dtandroid.lplshell.OPEN" />
        <data android:scheme="http" />
    </intent>
    <intent>
        <action android:name="app.dtandroid.lplshell.OPEN" />
        <data android:scheme="https" />
    </intent>
</queries>
```

(or `<package android:name="app.dtandroid.lplshell" />`). Then
`Intent("app.dtandroid.lplshell.OPEN", Uri.parse("<origin>/?photo=<id>")).setPackage("app.dtandroid.lplshell")`
with `resolveActivity` / `startActivity`; when it does not resolve, open the same URL in the browser
(PLAN.md 4.2). Strictly, `startActivity` alone works without `<queries>`; the declaration is needed to
decide beforehand. For the hand-off in the other direction the darktable app needs an activity with an
intent filter for `ACTION_VIEW`, categories DEFAULT and **BROWSABLE**, data scheme `lpldt` host `edit`
(the shell adds BROWSABLE, as browsers do, so a filter without it will not match).

### Signing and updates

The APK is a debug build signed with a key generated on the CI runner for that run only (as P2a);
nothing is stored. Consequences: an update never installs over an older build, and uninstalling deletes
the saved address and the sign-in. The version code is the workflow's run number, so later builds are
always newer. Proposal (not decided here): one release key for both apps, made and kept by the owner,
given to CI as an encrypted repository secret (or used to sign locally), used only by a release job on
`main`. Same key for both apps would also allow protecting OPEN with a signature permission later, so
only the darktable app could send it.

### Tests

Unit tests (JVM, `app/lpl-shell/src/test`), 15:

| Class | Tests | What |
|---|---|---|
| `AddressAndOriginTest` | 3 | normalisation (scheme added, path, slash, query, default port removed, IPv6, case), refused addresses (other schemes, user info, ports, hosts), origins of URLs incl. `@` and backslash tricks |
| `LinkRulesTest` | 7 | same/other origin, refused schemes, the R1 link, the direct form, hostile `intent:` links stripped to the query (component, action, category, flags, extras, selector with its own scheme and package), refused links (other package, other scheme, missing or duplicated attributes, scheme/package only in the selector, malformed structure, other host, fragment, too long), encoded attribute values, query characters |
| `OpenCheckPageTest` | 5 | OPEN acceptance (16 refused URLs), the server check (manifest, linked manifest, not LPL, 401, foreign manifest not followed, unreachable), manifest parsing, chooser MIME types, downloads and file names |

Instrumented tests (`app/lpl-shell/src/androidTest`, API 33 x86_64 emulator, Google WebView 109), 15,
against `StubServer`: a minimal HTTP server written in the test (about 100 lines, on 127.0.0.1 inside the
emulator; no library, no new service). Links are followed by real taps on the web view (Espresso), so the
page has user activation as with a finger; outgoing intents are recorded and stubbed with
espresso-intents (added as a test dependency for this).

| Test | Shows |
|---|---|
| `firstStartChecksTheAddressAndSavesIt` | address typed with a path, check request, saved normalised, page loads, user agent ends with ` LPLShell/<version>` |
| `firstStartWarnsWhenTheServerIsNotLplAndLetsTheUserContinue` | warning, nothing saved until "use it anyway" |
| `firstStartRefusesABadAddressWithoutAsking` | `ftp://` refused with the plain message |
| `firstStartWithNoServerShowsTheNativeScreenThenChangeAddress` | native screen with the address, "change address" back to the filled field, connect works once the server is up |
| `unreachableSavedServerShowsTheNativeScreenAndTryAgainRecovers` | native screen covers the web view, "try again" loads once the server is up |
| `sameOriginLinkStaysInside` | link to the same origin loads inside, no intent |
| `otherOriginOpensOutside` | link to another origin: `ACTION_VIEW` + BROWSABLE intent, page unchanged, no request reached the server |
| `backGoesThroughHistoryStatesThenLeaves` | two `pushState` states (on taps), back steps through them and the page, then the shell stops taking back and the activity leaves the foreground |
| `fileChooserIsOfferedAndSeveralFilesReachThePage` | `ACTION_GET_CONTENT` + OPENABLE + ALLOW_MULTIPLE; two files (MediaStore Downloads, as a picker returns them) arrive in the page with names and sizes |
| `handOffLinkStartsTheDarktableAppWithAnIntentBuiltByTheShell` | hostile R1 link (component, action, flags, extras, selector) and the direct `lpldt://` link both give exactly: VIEW, data, package, BROWSABLE, NEW_TASK, no component, selector or extras; page stays |
| `otherIntentLinksAreRefused` | other package, other scheme, a settings component link: no intent at all, page stays |
| `handOffWithoutTheDarktableAppShowsAMessageAndStaysOnThePage` | not installed: the message line with "get it", page stays |
| `openActionLoadsOnlyPagesOfTheSavedServer` | OPEN resolves to the shell for http and https, starts on the given page; while open another origin gives the start page, the saved origin the given page |
| `openActionWithAnotherOriginStartsOnTheStartPage` | started with a foreign OPEN URL: start page |
| `rotationAndBackgroundDoNotReloadThePage` | landscape and back, stop and resume: same activity, a page variable survives, no second request |

Screenshots taken by the tests: release files and `ci-logs` `runs/<run>/lpl-shell-emulator/shots/`.

### How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p2c-lpl-shell
# Android SDK platform 36 and build-tools 35.0.0, JDK 17 or newer
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon :checkScreenRulesSelfTest :checkScreenRules
$GRADLE --no-daemon :lpl-shell:testDebugUnitTest :ui-theme:testDebugUnitTest
$GRADLE --no-daemon :lpl-shell:assembleDebug :lpl-shell:assembleDebugAndroidTest
# with an emulator or a phone connected (adb):
bash ../tools/app/emulator-lpl-shell.sh lpl-shell/build/outputs/apk/debug/lpl-shell-debug.apk \
  lpl-shell/build/outputs/apk/androidTest/debug/lpl-shell-debug-androidTest.apk /tmp/emu
```
Or run the workflow `p2c-lpl-shell` by hand.

### What was exercised where

- Build workspace (x86_64, 2 cores): unit tests, APK and test APK builds, rule check (a deliberate
  `Color(0xFF123456)` in `ShellScreens.kt` made `:lpl-shell:assembleDebug` fail: "21 files, 1
  violations").
- Emulator only (CI, API 33, Google WebView 109, no real network): everything in the instrumented table.
- **Nowhere yet**: a real phone; the real LPL server and its sign-in, cookies surviving an app restart,
  LPL's own history states and its upload view; downloads (no test: the download manager in the
  emulator was not exercised); the "get it" link and the browser actually opening; HTTPS servers and the
  certificate-error screen; process death and restore; split screen; the launcher shortcut; the themed
  (monochrome) icon; the darktable app actually receiving the hand-off (it has no `lpldt` filter yet).

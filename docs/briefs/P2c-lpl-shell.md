# Brief P2c: LightPhotoLibrary shell app

Phase 2, small work package. Starts after `p2a-app-foundation` is merged. Read `CLAUDE.md`, `docs/PLAN.md` (sections 3.5, 3.6, 3.7 and 4), and the P2a report for how the Gradle project, theme tokens, icons and CI are set up.

## Why
LightPhotoLibrary (LPL) is a web application the user runs on a home server and opens on a private-network address over plain HTTP. Browsers only install a web app as a full-screen app when it is served over HTTPS, and setting up certificates and name resolution is not always possible on the user's network. The shell gives LPL its own icon and a full-screen window on the plain address, with nothing to configure outside the app. It also hands the "Edit on this phone" link to the darktable app directly instead of relying on how each browser treats such links.

## Goal
A second, very small Android app in the same Gradle project that shows one LPL server's web interface and nothing else. No darktable code, no engine, no native libraries.

## Deliverables
1. **Module `app/lpl-shell`**, application id `app.dtandroid.lplshell`, label "LightPhotoLibrary", minimum Android version as the main app, one universal APK. Launcher icon: a neutral one drawn in this repository through `ui-icons`; do not copy artwork from anywhere else.
2. **First start**: one screen asking for the server address (for example `http://192.168.1.20:8080`), with the address normalised (scheme added when missing, trailing slash and path removed) and checked by a request to the server before it is saved. If the server answers but does not look like LPL (no `manifest.webmanifest` naming it), warn and let the user continue. The address can be changed later from an entry reachable without the web page (for example the app's long-press shortcut and the error screen). One server only.
3. **The web view**:
   - Loads the saved address; JavaScript, DOM storage and persistent cookies on, so the user stays signed in to LPL. The shell never sees or stores the LPL password itself.
   - Stays on the saved origin. Any link to another origin opens in the phone's default browser. Plain HTTP is allowed because the user typed the address; do not relax the platform's default mixed-content rules.
   - Back button and back gesture go back in the page's history; at the start of the history they leave the app.
   - `<input type="file">` works, including choosing several files (LPL has an upload view). Downloads started by the page are saved with the system download manager, with cookies carried over.
   - Rotation, split screen and return from the background do not reload the page.
   - The user agent gets ` LPLShell/<version name>` appended, so LPL can tell it runs inside the shell.
   - Pinch and drag gestures belong to the page; the shell adds no zoom controls and no pull-to-refresh.
   - System bars in the theme's dark background colour through `ui-theme` tokens; the page gets the area between them (no edge-to-edge unless you verify the page's safe-area handling works in the web view).
4. **Hand-off to the darktable app**: links of the form `intent://edit?...#Intent;scheme=lpldt;package=app.dtandroid;...;end` and `lpldt://edit?...` (PLAN.md 4.3, R1) are not loaded as pages. The shell starts the darktable app with them. Accept only the `lpldt` scheme and the package `app.dtandroid`; strip any component, selector, flags and extras the link carries apart from what R1 defines; every other `intent:` link is refused. If the darktable app is not installed or does not accept the link yet, show a short message with a link to this repository's releases page, and stay on the page.
5. **Opening the shell from the darktable app**: an exported activity action `app.dtandroid.lplshell.OPEN` whose data is a full URL. The shell opens it only if its origin equals the saved server; otherwise it ignores the URL and opens its start page. This is what the darktable app's "open in LightPhotoLibrary" will use later (PLAN.md 4.2); write down in the report what the darktable app must declare to see the shell (package visibility).
6. **When the server cannot be reached** (wrong network, server off): a native screen saying so with the address, "Try again" and "Change address". No raw browser error pages.
7. **Tests**:
   - Unit tests for the pure rules: address normalisation, same-origin decision, which `intent:` and `lpldt:` links are accepted and what the resulting intent contains, which OPEN URLs are accepted.
   - Instrumented tests on the emulator against a small stub server started by the test itself (no new service; say which library you use for it and why, or write a minimal one): first-start flow, a same-origin link stays inside, another origin leaves, back navigation after history states pushed by the page, file chooser is offered, the hand-off link produces the expected intent (assert with an intent stub, the darktable app need not be installed), unreachable server shows the native screen.
8. **CI**: extend the app workflow or add one: build, unit tests, the instrumented tests in the existing emulator job style, and publish the shell APK as a release file with a tag derived from the commit; logs to `ci-logs` as the other workflows do.
9. **For the owner**, in `docs/experiments/P2c-lpl-shell.md`: where to get the APK, how to set it up (three sentences), what to try, and what happens to the saved address and sign-in when a newer build is installed over an older one. Plus the technical write-up and the pull request description (`docs/experiments/P2c-lpl-shell-REPORT.md`).

## Limits
- Rules of `CLAUDE.md` apply: no literal colours, sizes or icon drawings in screen code; no real server addresses, names or device models anywhere (examples use documentation addresses such as `192.168.1.20` or `example.org`).
- No changes to the darktable app's modules, `engine/`, `deps/`, `schema/` or `upstream/`. Reuse `ui-theme` and `ui-icons`; if you need a token or icon that does not exist, add it there the way P2a established.
- No analytics, no crash upload, no permissions beyond network access and what file choosing and downloads need.
- Do not add a signing key to the repository. Describe in the report how the APK is signed and what that means for updates; propose, do not decide, how both apps should be signed for the owner's use.
- The LPL web application itself is not in this repository and is not changed here. If something in the page would have to change for the shell to work well, list it under open questions.
- Branch `p2c-lpl-shell`, do not merge.

## Acceptance
- All tests in 7 pass in CI, with the run linked or its log on `ci-logs`.
- The APK installs next to the darktable app and the test apps.
- Report states plainly what was exercised only in the emulator and what needs the owner's phone and a real LPL server.

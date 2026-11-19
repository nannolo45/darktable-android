# P2a: app foundation and darkroom on a fake engine

Brief: `docs/briefs/P2a-app-foundation.md`. Branch `p2a-app-foundation`. Pull request text:
`P2a-app-foundation-REPORT.md`.

## Running log (newest first)

- 2026-11-19: CI run 44479626410 (commit 7500d36) green: 68 tests, preview APK and 19 screenshots published as
  release `app-preview-7500d366a908`. Screenshots looked at and fixed (read-only power icons, list entry alignment, banner wording).
- 2026-11-19: first CI run 44478777938: all 67 tests passed; the APK check was too strict (androidx's one
  native library); screenshots reviewed.
- 2026-11-19: darkroom, app shell, rule check, controller and screenshot tests, CI workflow `p2a-app.yml`.
- 2026-11-19: resumed after a server restart (session stopped for several hours).
- 2026-11-19: engine-api, schema (with `schema/overrides/`), ui-theme, ui-icons, each with JVM tests.

Next steps: the owner tries the preview APK (section "For the owner"); the reviewer decides on the open
questions in the report.

---

## For the owner: the interface preview

### What it is

The real darkroom interface of the future app, running on a **stand-in engine**. The stand-in does not
contain darktable: it draws a generated landscape and imitates a few modules (exposure, white balance,
contrast, saturation, tone curve, vignette, grain, rotate and flip) roughly, so you can see that a change
does something. **Speed and image quality mean nothing yet.** Everything else is meant to be judged: the
layout, the greys, the sizes of things under your finger, how sliders feel, the module list, instances,
history, undo, the read-only case. It opens a made-up series of twelve photos. It has no internet
permission and never sees your photos.

### 1. Download and install

1. On the phone, open https://github.com/nannolo45/darktable-android/releases in the browser.
2. Find the newest entry whose title starts with **"Interface preview"**. The first one is
   https://github.com/nannolo45/darktable-android/releases/tag/app-preview-7500d366a908
3. Under "Assets", tap **dt-android-preview-arm64-v8a.apk** (about 9 MB).
4. Open the downloaded file, allow installing from the browser if Android asks, and tap **Install**
   (Play Protect may warn about an unknown app: choose to install anyway). It installs as
   **"dt-android preview"**, next to the G0 test app, which stays as it is.
5. A newer preview may refuse to install over an older one ("App not installed"): uninstall
   "dt-android preview" first. Each build is signed with a new temporary key.

### 2. What to look at

1. **The look.** darktable's flat greys, lowercase labels, no colour accents. Is everything readable in
   daylight? Is anything too small to hit?
2. **Modules.** The tabs under the photo: the power symbol shows the modules that are on, "quick" four
   common sliders, then the groups base, tone, color, correct, effect. Tap a module's name to open it
   (one at a time). The three symbols on an open module: instances (new, duplicate, move, delete,
   rename), reset, presets (not working yet). Modules such as demosaic or the colour profiles have no
   power symbol, as on the PC.
3. **Sliders.** Drag sideways anywhere on a slider's row; the photo follows. Double-tap the label to go
   back to the default; long-press or double-tap elsewhere on the row to type a value. Lists open a sheet;
   switches are the small boxes on the right.
4. **History and undo.** The "history" tab at the bottom: tap a step to go back to it, "compress history
   stack", undo and redo. A **two-finger tap on the photo** is undo. "before" (top) shows the photo
   without your edits.
5. **The photo.** Pinch to zoom, drag to move, double-tap for 100 % and back.
6. **The series.** Tap the file name at the top to show the filmstrip; tap another photo. Your edits of
   each photo are kept while the app is installed. **Photo 5** pretends to come from a newer darktable on
   the PC: it opens read-only with a banner explaining why, and nothing can be changed.
7. **A tablet, if you have one**, or the phone turned sideways in a large window: the tablet layout with
   history on the left and modules on the right appears on screens at least 840 x 480 dp.

Please send back: what felt wrong or too small, what you expected to happen and did not, and screenshots
of anything that looks broken.

### 3. Trying a different palette

Every colour and size comes from one file. The preview reads a replacement file if you put one in its
folder, so you can try colours without a new build:

1. Copy `alt-tokens.json` from https://github.com/nannolo45/darktable-android/blob/p2a-app-foundation/app/ui-theme/alt-tokens.json
   (an example blue-grey palette) or write your own: it lists only the colours you want to change, with
   the names from `app/ui-theme/tokens.json`, for example `{ "color.bg": "#1e2329", "color.text": "#c3cad2" }`.
2. Name it **`theme.json`** and copy it to the phone's folder **`Android/data/app.dtandroid/files/`**
   (with a USB cable from the PC; on the phone itself most file managers cannot open `Android/data`).
3. Close the preview completely (swipe it away from recent apps) and open it again. A line under the
   photo says which palette was used, or what was wrong with the file.
4. Delete `theme.json` to go back to the default greys. Text must stay readable: aim for a contrast of at
   least 4.5 to 1 against its background.

---

## Write-up

### Modules (PLAN.md 3.5)

| Module | Kind | What it holds |
|---|---|---|
| `engine-api` | Kotlin/JVM | `DarktableEngine` (engine-api.md 1 and 2), `EngineException`, the JSON shapes, `FakeEngine`; the engine contract tests as test fixtures |
| `schema` | Kotlin/JVM | control model, `SchemaLoader`, `Rules` (visibility and sensitivity), overrides, `ModuleGroups`, `ValueFormat` |
| `ui-theme` | Android library | `tokens.json`, `DtTokens`, `DtTheme` for Compose |
| `ui-icons` | Android library | `svg/` (one file per icon), conversion with Android's `Svg2Vector`, generated `DtIcon` |
| `darkroom` | Android library | `DarkroomController`, the phone and tablet screens, `InterfacePreview` (sample series) |
| `app` | Android app `app.dtandroid` | activity, view model, the debug palette override |
| `engine-jni`, `g0` | unchanged | now only included with `-Pdta.engineDir` (they need the native archives); G0's application id is `app.dtandroid.g0` |

The screens depend on `DarktableEngine` only; `darkroom` and `app` do not depend on `engine-jni`.

### Engine interface in Kotlin

Every function of sections 2.1 to 2.8 has a suspend function with the same meaning (`renderCancel` is the
one non-suspending call, "from any thread"). Structured results are data classes that parse exactly the
JSON shapes of the specification (`fromJson`) and write them (`toJson`); the fake engine produces its
results as that JSON and parses it back, so the parsing is exercised by every test. Parameter values are
`JsonPrimitive` (number, bool or enum constant name), parameter structures `JsonObject`.

### Fake engine

- Modules, versions, flags, parameter fields (types, ranges, enum constants) and this image's defaults come
  from `schema/ui-schema.json`. `can_switch` is false where desktop hides the switch
  (`hide_enable_button`) and for the 7 modules without a panel.
- A new photo gets darktable's scene-referred default edit (10 history items: rawprepare, white balance,
  highlight reconstruction, demosaic, orientation, exposure +0.7 EV, input profile, color calibration,
  sigmoid, output profile).
- History follows section 2.6: `param_set` calls on one instance merge until `history_seal` or any other
  change; a change below the top discards the items above; compress keeps the last item of each instance
  and drops modules that end up off unless they are on by default; undo and redo restore the whole state
  (100 steps). Instances follow S3 section 6 (new above the source, renumbering when priority 0 is
  deleted, moving switches the module on, `one_instance` refused).
- The version guard reads an XMP's `darktable:operation` and `darktable:modversion` entries; unknown
  modules and newer versions make the session read-only, and every mutating call then throws
  `READ_ONLY`. `xmp_write` refuses to overwrite a file that fails the guard. Its own sidecar is
  darktable-shaped but stores parameters as base64 JSON marked `dtandroid:fake="1"`.
- Rendering: a generated 3000 x 2000 landscape in "camera" colours, then per-pixel approximations in pipe
  order. Simulated time: 60 ms (preview) or 150 ms (region) plus 60 ms per megapixel, spread over the
  modules; cancel is checked between modules.

### Control schema in the app

- `schema/ui-schema.json` and `schema/overrides/*.json` are packed into the `schema` module's jar by a
  Gradle task from the repository files; the APK check confirms they are inside.
- Visibility rules are evaluated against the instance's current parameters. Following S5's conditions:
  a value the extractor never observed, a combination a table rule does not list, and the one unverified
  rule (spektrafilm) never hide a control; controls hidden in every observed state stay hidden; controls
  without a rule follow their container.
- All 559 sliders format their default exactly as desktop showed it (`text_default`), checked by a test.
- **Panels:** of 90 modules with a panel, **79 get generated controls** (71 of the 74 that are not
  deprecated). Without: liquify, color look up table, output color profile (and 8 deprecated modules);
  they show the "editor not available yet" note and can still be switched and have instances.
- **Not rendered:** 47 buttons, 52 toggle buttons, 26 colour picker buttons, 44 graphs and 2 other custom
  widgets (Tier 2 and 3, later work packages). **Shown but disabled** because they have no parameter:
  52 sliders, 39 lists, 4 switches (S5's unbound controls, for example white balance's preset list and
  color calibration's derived hue and chroma).
- **Overrides:** `flip.json` (orientation's four buttons as tables generated from `flip.c`) and
  `temperature.json` (white balance temperature and tint through `get_wb`/`set_wb`). Each names the
  node it changes and what it expects there, and loading fails if the schema no longer matches.

### Theme, icons, rule check

- `app/ui-theme/tokens.json`: the specification's table plus the mockups' other sizes (tab and panel
  sizes, spacing) and four colours the table lacks (`textDim` #ababab, `scrim`, `photoBackground`,
  `banner`). Tests check the specification's values, touch targets of at least 44 dp and 4.5:1 contrast
  for 12 text/background pairs, for the default and the alternative palette.
- `app/ui-icons/svg/`: the specification's ten icons, plus `check` (switch), `rotateLeft`, `rotateRight`,
  `flipHorizontal`, `flipVertical` (orientation) and `sliderMarker` (the triangle under a slider).
- `tools/app/check-screen-rules.py` runs before `darkroom` and `app` build (`:checkScreenRules`, a
  dependency of their `preBuild`): literal `dp`/`sp`, `Color(...)`, named colours, hex colours, colour
  strings, Material components, `painterResource`, `ImageVector`, `Icons.` and path drawing fail the
  build. A self-test checks 12 marked violations in a fixture are found and none in clean code. Shown on
  the workspace: adding one `Color(0xFF123456)` to `Common.kt` made `:darkroom:assembleDebug` fail.

### Tests (CI run 44479626410, x86_64 runner)

| Module | Class | Tests | Failed |
|---|---|---|---|
| engine-api | FakeEngineContractTest (the engine contract, engine-api.md 2.1 to 2.8) | 18 | 0 |
| engine-api | FakeEngineTest (schema use, guard, orientation, white balance, export, JSON shapes) | 6 | 0 |
| schema | SchemaTest (exposure, tabs, nested and combined conditions, table rule, overrides, formats, groups) | 12 | 0 |
| ui-theme | TokensTest (specification values, 44 dp, contrast, overrides) | 5 | 0 |
| darkroom | DarkroomControllerTest (drag grouping, white balance action, series, read-only, before, instances) | 7 | 0 |
| darkroom | PhoneScreenshots (13 screenshots, slider drag, expand) | 14 | 0 |
| darkroom | NarrowPhoneScreenshots | 1 | 0 |
| darkroom | TabletScreenshots | 5 | 0 |
| **total** | | **68** | **0** |

Plus the rule check's self-test (12 of 12 marked violations found, 0 in clean code) and the check itself
(11 screen files, 0 violations). Test time on the runner: 108 s. Logs: branch `ci-logs`,
`runs/44479626410-a1/p2a-app/` (test XML, test summary, APK report, screenshots).

Screenshot tests run Robolectric with native graphics at phone 390 x 844 dp (xhdpi), narrow phone
360 x 740 dp and tablet 1280 x 800 dp (hdpi). They are written for looking at, not compared against
golden files; each also asserts a few facts (no power icon on output color profile, header icons on
tablets, menu entries, the banner, orientation after the rotate button, one history step per drag).

### How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p2a-app-foundation
# Android SDK platform 36 and build-tools 35.0.0, JDK 17 or newer
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE :checkScreenRulesSelfTest :checkScreenRules
$GRADLE :engine-api:test :schema:test :ui-theme:testDebugUnitTest :darkroom:testDebugUnitTest
$GRADLE :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
python3 ../tools/app/test-summary.py .
```
On the build workspace the screenshot tests cannot run (Maven Central sends Robolectric's large jars from
a CDN host the workspace may not reach); `-Pdta.screenshots=false` leaves them out. CI runs everything:
`.github/workflows/p2a-app.yml`.

### What could not be checked here

- Nothing ran on a device or emulator: gestures were tested through Compose's test input on the JVM, not
  with a finger. Smoothness of slider dragging on the phone is untested.
- The theme override path on a real phone (`Android/data/app.dtandroid/files/theme.json`).
- The tablet layout on a tablet (screenshots only).

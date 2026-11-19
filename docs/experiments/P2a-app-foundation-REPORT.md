# P2a: app foundation and darkroom on a fake engine (pull request description)

Branch `p2a-app-foundation`. Details, owner instructions and reproduction: `P2a-app-foundation.md`.

**Status: all deliverables done; nothing failed.** Proof: CI run 44479626410 (commit 7500d36), 68 of 68
tests passed, preview APK and 19 screenshots published as release `app-preview-7500d366a908`.
Not shown anywhere: behaviour on a device (nothing in this package ran on a phone, a tablet or an
emulator) and the theme override on a real phone.

## 1. Brief, item by item

| # | Deliverable | State | Where |
|---|---|---|---|
| 1 | Modules `engine-api`, `ui-theme`, `ui-icons`, `schema`, `darkroom`, `app`; `engine-jni` and `g0` kept; G0 id `app.dtandroid.g0` | done | `app/*`; `engine-jni`/`g0` now included only with `-Pdta.engineDir` |
| 1a | `DarktableEngine`, data classes, `EngineException`, JSON parsing, `FakeEngine` | done | `app/engine-api` |
| 1b | Tokens from one data file, loaded at start, Compose access, debug override file | done | `app/ui-theme/tokens.json`, `DtTokens`, `DtTheme`; override `Android/data/app.dtandroid/files/theme.json` |
| 1c | Icon registry by meaning, one SVG per icon, converted at build time | done | `app/ui-icons/svg`, `convertIcons` (Svg2Vector) generates `DtIcon` |
| 1d | Schema loader from the repository file at build time, control model, visibility rules, overrides with orientation | done | `app/schema`, `schema/overrides/flip.json` (+ `temperature.json`, see deviations) |
| 1e | darkroom, app shell on the sample image | done | `app/darkroom`, `app/app` |
| 2 | FakeEngine: whole core interface, behaviour from the schema, plausible rendering, simulated time, cancel | done | contract suite 18 tests + 6 fake tests |
| 3 | Darkroom phone and tablet: every element listed in the brief | done | see screenshots; list in section 3 |
| 4 | Build fails on literal colours, dp/sp sizes or icon drawing in screen code | done | `tools/app/check-screen-rules.py`, `:checkScreenRules` before `preBuild` of `darkroom` and `app`; self-test |
| 5 | Tests: schema loader and rules on real entries; FakeEngine against the specification (reusable); JVM screenshots phone and tablet incl. alternative palette | done | 68 tests; the contract suite is in `engine-api`'s test fixtures for the JNI engine later |
| 6 | CI: build, checks, tests, screenshots; APK and screenshots as release files with a commit tag; ci-logs; branch + manual triggers; default token, per-job permissions, pinned actions, no `pull_request`; debug key generated in CI | done | `.github/workflows/p2a-app.yml` |
| 7 | Owner section, technical write-up, this report | done | `P2a-app-foundation.md` |

## 2. How to reproduce

```
git checkout p2a-app-foundation
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)     # Android SDK platform 36, build-tools 35.0.0
cd app
$GRADLE :checkScreenRulesSelfTest :checkScreenRules
$GRADLE :engine-api:test :schema:test :ui-theme:testDebugUnitTest :darkroom:testDebugUnitTest
$GRADLE :app:assembleDebug
python3 ../tools/app/test-summary.py .
```
Or run the workflow `p2a-app` by hand. Screenshot tests need Maven Central's CDN (not reachable from the
build workspace): locally add `-Pdta.screenshots=false`.

## 3. Results

- Tests (run 44479626410): engine contract 18, fake engine 6, schema 12, tokens 5, darkroom controller 7,
  phone screenshots 14, narrow phone 1, tablet screenshots 5: **68 passed, 0 failed, 0 skipped**.
- Rule check: 11 screen files, 0 violations; self-test finds 12 of 12 marked violations, 0 false ones; a
  deliberate `Color(0xFF123456)` made `:darkroom:assembleDebug` fail on the workspace.
- APK: `dt-android-preview-arm64-v8a.apk`, 8,549,792 bytes, package `app.dtandroid`, label
  "dt-android preview", no permissions besides androidx's internal receiver permission, one native
  library (`libandroidx.graphics.path.so`, from Compose), schema, overrides, groups and tokens packed,
  signed with a debug key generated on the runner (`apk-report.txt` in the release).
- Download: https://github.com/nannolo45/darktable-android/releases/download/app-preview-7500d366a908/dt-android-preview-arm64-v8a.apk
- Screenshots (release files, and `ci-logs` `runs/44479626410-a1/p2a-app/screenshots/`):
  phone-01 collapsed list, 02 expanded module, 03 instances sheet, 04 history, 05 read-only banner,
  06 alternative palette, 07 quick panel, 08 tabs (color balance rgb), 09 list sheet, 10 filmstrip,
  11 exact entry, 12 orientation after "rotate clockwise", 13 narrow 360 dp phone; tablet-01 to 05
  collapsed, expanded, inline instances menu, history with a dimmed step, read-only.
- **Generated panels:** 90 modules have a panel; **79 get generated controls** (71 of the 74 not
  deprecated). Without generated controls (note shown, switchable): liquify, color look up table, output
  color profile, and 8 deprecated modules (invert, spot removal, legacy equalizer, color transfer,
  channel mixer, filmic, zone system, old local contrast).
- **Not rendered:** buttons without an override (47), toggle buttons (52), colour picker buttons (26),
  graphs (44), other custom widgets (2): Tier 2/3. **Shown disabled** (no parameter): 52 sliders, 39
  lists, 4 switches. Slider curves (non-linear bauhaus sliders, `linear_curve` false) are drawn linear.
  Notebook-switched stacks (color equalizer's pages 2 and 3, S5 6.6) show only the first page.
- All 559 slider value texts equal desktop's recorded `text_default`.

## 4. Upstream

Nothing under `upstream/`, `engine/`, `deps/` or `schema/ui-schema.json` was touched. Read for the
orientation table: `src/iop/flip.c` `do_rotate`, `_flip_h`, `_flip_v`, and the `dt_image_orientation_t`
values in `src/common/image.h`. Nothing surprising beyond what S5 recorded; one note: several enum
constants share a value (`ORIENTATION_FLIP_X` and `ORIENTATION_FLIP_HORIZONTALLY`), so a parameter
read back as a constant name may come back under the other name.

## 5. Specification points that were unclear, contradicted a mockup or could not be followed

1. **`before` has no engine call.** The API cannot render another history position without changing
   `history_end`. The darkroom does `history_goto(0)`, renders a preview, `history_goto(end)` under one
   lock. For that to be harmless I made the fake treat **`history_goto` as navigation: not an undo step,
   and allowed in read-only sessions** (the specification does not say either way; desktop records it
   in undo). Proposal for P1a/G1: either state this for `history_goto`, or add
   `render_preview_at(end, max_size)` (or snapshots, P1b) so `before` does not touch the history.
2. **`color_labels: [..]`** has no element type; the Kotlin type is a list of darktable's label numbers.
3. **`instance_move` direction**: taken as +1 = later in the pipe = up in the list (desktop shows the
   pipe reversed). Please confirm for the C side.
4. **`iop_order`** typed as integer.
5. **Mockup colours not in the token table**: unselected tabs, off modules and instance labels use
   #ababab in the mockups; `textMuted` (#9e9e9e) on the header grey is only 4.2:1, so a token `textDim`
   = #ababab was added. Selected items use `textBright` (#f1f1f1) where the mockups use #e2e2e2; disabled
   menu items use `textDisabled` (#848484) where the mockups use #777777 (specification wins).
6. **Bottom sheet corners**: the mockup rounds them (10 px); the specification says everything except
   buttons is square. Token `radius.sheet` = 0.
7. **Tablet top bar**: the specification puts undo and redo there; the mockup has none. Added as the
   specification says. The mockup's back label "LightPhotoLibrary" is not shown (there is no LPL yet);
   back is the icon only on both layouts.
8. **Tablet image information**: the mockup's "folder" and "source" come from the working set, which does
   not exist yet; shown instead: filename, series, camera, lens, exposure, size.
9. **Read-only banner position** is not specified: below the info line on phones, above the photo on
   tablets. Power icons of a read-only photo keep their on/off tint but cannot be tapped.
10. **Presets icon**: presets are engine API section 3 (P1b). It opens a sheet with a disabled "store new
    preset…" and the line "Presets are not available yet."
11. **Slider drag** moves the value relative to the finger (no jump to where the finger lands), so a
    vertical scroll or a stray touch does not change values; the mockup's range input jumps. Tell me if
    the owner prefers jumping.
12. **Module order**: lists use desktop's order (last in the pipe on top); the mockup lists modules in an
    arbitrary order.
13. **Layout switch**: tablet layout from a window of 840 x 480 dp (Material's "expanded" width plus a
    height minimum, so a phone in landscape keeps the phone layout); both are tokens.
14. **Exact entry and rename** use the same bottom sheet with a text field (the specification asks for
    exact entry but not for its look).

## 6. Deviations from the brief

1. **`schema/overrides/temperature.json`** besides orientation: the default quick panel includes "white
   balance temperature", which has no parameter (S5); it is bound to `module_action get_wb/set_wb`. Drop
   it if overrides must stay orientation-only for now; the quick slider then shows disabled.
2. **Icons beyond the initial set**: `check`, `rotateLeft`, `rotateRight`, `flipHorizontal`,
   `flipVertical`, and `sliderMarker` (a filled 10 x 6 triangle, the one non-stroke icon).
3. **Group "effect"** lists `contrastntexture` and `diffuse` by name, because they carry only darktable's
   newer "effects" group bit and would otherwise be in no group.
4. **Dependencies added** (all official): kotlinx-serialization-json 1.9.0 (JSON shapes, schema),
   Robolectric 4.16 and Roborazzi 1.50.0 (JVM screenshots, test only), Compose ui-test (test only). The
   build uses `com.android.tools:sdk-common`'s `Svg2Vector`, which comes with the Android Gradle plugin.
5. **`settings.gradle.kts`** includes `engine-jni` and `g0` only with `-Pdta.engineDir` (their build
   files fail without it). The G0 workflow passes it, so G0 builds as before.
6. **`app/gradle.properties`**: workers limited to 2 and heap 1.5 GB for the shared workspace; CI raises
   workers to 4 on the command line.
7. New scripts under `tools/app/`: `check-screen-rules.py` (+ fixtures), `test-summary.py`.

## 7. Open questions for the reviewer

1. Points 1 and 3 of section 5 (`history_goto` semantics, `instance_move` direction) need a decision
   before the JNI engine runs the same contract suite.
2. Keep or drop `temperature.json` (deviation 1)?
3. Thumbnails: the engine API has no thumbnail call, so the darkroom opens every photo of the series once
   at start to render a 128 px preview. Fine for the fake; with the real engine thumbnails should come
   from LPL's previews.
4. The `modules()` JSON includes large arrays (lut3d's `c_clut`, retouch's 300 forms). The fake keeps
   them as objects; for the JNI engine, re-reading all modules after every slider step may be slow on
   the phone. Perhaps a `module(op, priority)` call, or params without arrays above a size.
5. Known cosmetic: on the tablet, "image information" truncates the exposure line with an ellipsis.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

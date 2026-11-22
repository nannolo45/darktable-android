# PUI3a: third interface skin `l1`, a second darkroom layout — report

Brief: `docs/briefs/PUI3a-l1-layout.md`. Branch `pui3a-l1-layout` from `main` 511bb98. The `gh` command is not
signed in on the build workspace: this file is the pull request description.

## 1. What the brief asked and what was delivered

| Brief item | State | Notes |
|---|---|---|
| Setting value `l1`, `Skin.L1` layered on `modern-tokens.json` + `l1-tokens.json`, loader stacks several layers | done | `Skin` now lists its files (`files: List<String>`); `DtTokens.load(skin)` stacks them in order. `l1` loads `modern-tokens.json`, then `l1-tokens.json`. |
| Shown in the About screen's interface choice in neutral words, no restart | done | Button label "lightcraft-style" (`Skin.label`), applied at once through the existing `AppSettings.skin` flow. |
| No literal colours or sizes (rule 5) | done | New tokens: `shape.darkroom` (`panels` / `l1`), `color.l1Shade`, `color.l1Pill`, `size.l1*`, `alpha.l1Panel`, `time.l1FadeOut`/`FadeIn`, `count.l1SectionsFull`. They have defaults in `tokens.json` (the loader only takes keys the base file has), and `l1-tokens.json` sets L1's values. Rule check: 55 files, 0 violations. |
| Upright 1: photo full width on black, fitted | done | `PhotoView` got an optional `surround` colour (default: today's surround); l1 passes `color.photoBackground`. |
| Upright 2: top bar floating over the photo on a shade token: back · series pill "n / N" (filmstrip) · undo · compare · export · more; hides while dragging | done | Compare is the existing before/after. Export is the host's own "export…" action (the first `HostAction` whose label starts with "export"); the button is left out where the host has none (tests). More is the existing photo menu. |
| Upright 3: tools row (styles · modules · masks · more, chosen one filled with the accent) and sections row (only with `modules`: `quick`, groups, `active`, marks, no sideways scrolling, icons + short labels when crowded) | done | `quick` appears when the layout has "show quick access panel" on (as on desktop), see open question 2. Above `count.l1SectionsFull` (6) sections, each shows its icon and a tiny label. Mark: the group holds a module that is on and not at its default (on although off by default, or in the history). |
| Upright 4: panel between photo and rows, 95 % panel colour, height capped by a token, scrolling inside; quick (flat list, module name small and dim), group (stacked rows, one open at a time, power on closed rows, open = icons, controls, `mask…`), active (last applied on top, a row opens the module in its group), styles, masks (unchanged, only hosted), more | done, see notes | Rows are the existing `ModuleHeader` + `ModulePanel` + `MaskRow` with a chevron added (parameter). The open row's icons are the existing ones: instances (⋯), reset, presets; a picker button sits beside the slider it belongs to, as today. "more": snapshot, copy, paste and duplicate do not exist in the app, so they are left out; it holds "module layouts · <name>…" (the layouts menu, which the other layouts have beside the group tabs) and the existing history with undo, redo and "compress history stack". |
| 5: dragging a slider fades panel, rows and top bar (250 ms out, 100 ms back, tokens), only the dragged slider stays, pill `module · control · value` | done | Generated sliders report the drag to a `SliderDragWatch` that only the l1 layout provides (`LocalSliderDrag`); without it nothing changes. Crop's rotation slider and the masks tab's sliders are not generated sliders and do not fade the chrome. |
| 6: on-photo tools keep their behaviour; rows hidden while open, back on `done` | done | Rows are left out while the crop tool, a colour picker or shape drawing is open. While drawing, the drawing tool's panel replaces the panel, as on the other phone layout. Graph editors not built (P3e). |
| 7: ⋯ on a module opens the existing instance menu as a bottom sheet | done | Already a sheet on the phone; unchanged. The brief's list names "masks" in that menu; today's menu has no such entry (a module's mask is reached through `mask…` and the mask indicator), so none was added. |
| Sideways phone: photo, panel (fixed width token), sections column, tools column | done | Chosen when the phone window is wider than tall. |
| Tablet unchanged | done | `l1` on a tablet uses the existing tablet layout with the l1 tokens (modern's look). |
| New files, `DarkroomScreen` picks the layout by skin, content reused not copied, small parameters with defaults | done | `ui/l1/L1Darkroom.kt`, `L1Rows.kt`, `L1Panel.kt`, `L1Model.kt`, `ui/SliderDrag.kt`. Parameters added with defaults: `ModuleList(l1)`, `ModuleHeader(chevron, onOpen, modifier)`, `QuickPanel(flat)`, `StylesOverlays(stylesInPanel)`, `PhotoView(surround)`; `Notices`, `LastEditLine`, `SearchLine` made `internal`. `MasksPanel.kt` and `MaskTree.kt` untouched. |
| No change to the controller's public behaviour, engine, bridge, engine-api, LPL, schema | done | None of these files changed. |
| JVM tests | done | See section 3. |
| Screenshots in `l1`: rest, a group with one open module, quick, active, styles, masks, more, dragging, sideways | done | Plus one with the crop tool open. Existing darktable and modern screenshot tests run unchanged and pass. |
| Emulator run of the instrumented suite with `interface = l1` | not done | `p2b-real-engine` has no parameter for the interface setting, and its instrumented tests drive the engine and scenarios, not the darkroom's layout. It ran on this branch with the default skin (see section 3). |

Icons added (`app/ui-icons/svg`): `export.svg`, `compare.svg`, `modules.svg`, `styles.svg`.

## 2. How to reproduce

- JVM tests and screenshots (needs the Android SDK; CI): `cd app && gradle :ui-theme:testDebugUnitTest :darkroom:testDebugUnitTest`.
  Only this package's tests: `--tests '*PUI3aL1ModelTest*' --tests '*L1Screenshots*' --tests '*TokensTest*'`.
  Screenshots are written to `app/darkroom/build/outputs/screenshots/phone-l1-*.png`.
- Rule check: `python3 tools/app/check-screen-rules.py`.
- On a phone: About screen → interface → "lightcraft-style", then open a photo.

## 3. Results

CI (GitHub Actions; the build workspace has no Android SDK, so nothing app-side was built locally):

- p2a-app run 45078881639 (commit 9b4966c): built, one test failed: `PhoneL1Screenshots.groupWithOneOpenModule` — the
  test tapped a second module row that was below the panel's capped height and not composed. Fixed in the test (it
  scrolls the panel to that row first); no product code change was needed for it.
- p2a-app run 45079520806 (commit 7080091): **success**, 358 tests, 0 failed, 0 errors (`PUI3aL1ModelTest` 8,
  `PhoneL1Screenshots` 10, `SidewaysPhoneL1Screenshots` 1, `TokensTest` 10 incl. 2 new); rule check passed; preview
  APK built (release tag `app-preview-708009184f66`).
- p2b-real-engine run 45079520854 (commit 7080091): **success**: JVM tests (p2b-apk, tests exit 0), emulator instrumented suite (success, test exit 0, default skin: no interface parameter), comparison with darktable-cli (g0, edit, sf, crop, p2j, p3a, p3b, p3c, p3d all ok). The run for 9b4966c (45078881635) was cancelled by the next push.

Tests added:
- `ui-theme` `TokensTest`: `l1SkinStacksOnModernAndEveryKeyIsAToken` (every key of `l1-tokens.json` is a token, no
  unknown overrides, modern's values kept, contrast), `skinKeysRoundTrip`.
- `darkroom` `PUI3aL1ModelTest` (no drawing): skin round trip and layout switch; sections from the layout (quick
  first, groups in order, active last, quick only when shown); marks (synthetic states and a module switched on in
  the stand-in engine); a second tap on the chosen section keeps it; tools ↔ views and the styles list; active list
  last applied on top; rows left out while drawing or with the crop tool open and back after; drag hides the top bar
  and gives the pill text.
- `darkroom` `PUI3aL1ScreenshotTest` (drawn, Compose touch input): the states below, and taps: one module open at a
  time (open, open another, close), power on a closed row switches the module and leaves the row closed, a row in
  `active` opens its module in its group, a real slider drag with the finger held down shows the pill and the
  history gets the change after the finger lifts, the tools row disappears with crop open and returns when crop's
  row is closed.

Screenshots (ci-logs branch `runs/45079520806-a1/p2a-app/screenshots/` and the release `app-preview-708009184f66`):
`phone-l1-01-rest`, `-02-group-open-module`, `-03-quick`, `-04-active`, `-05-styles`, `-06-masks`, `-07-more`,
`-08-dragging`, `-09-crop-open`, `-10-sideways` (`.png`). Phone 390 × 844 dp upright, 844 × 390 dp sideways, on the
stand-in engine's generated picture.

## 4. Upstream

Nothing in upstream was touched or needed.

## 5. Open questions for the reviewer

1. **Panel beside or over the photo.** The brief says the photo is "as large as the panel leaves room for" (so they
   do not overlap) and also that the panel is 95 % opaque and fades while dragging. I kept them apart: the photo
   never sits under the panel, so the 95 % and the fade show the black page behind, not the photo. If the panel
   should lie over the lower part of the photo, that is a small change in `L1Darkroom`.
2. **`quick` when the layout hides the quick access panel.** I follow the layout's "show quick access panel" (as
   desktop and the other layouts do). The brief could be read as "always first".
3. **Quick access in the flat list** has no per-module power, reset or presets (the module headers are gone, as the
   brief asks); those stay reachable on the module's own row in its group.
4. **Filmstrip** is the existing one and scrolls sideways; the brief asks for "no sideways scrolling" in L1 but also
   for the existing filmstrip. Left as it is.
5. **Export button** relies on the host's action label starting with "export". A named field on `HostAction` would be
   sturdier; it would touch the app shell, so I did not add one.
6. **Sideways sections column**: with nine sections on a 390 dp high window each cell is about 43 dp, just under the
   44 dp touch size.
7. **Photo surround**: black in l1 (as the brief asks), not darktable's mid-grey, which desktop uses for judging tones.

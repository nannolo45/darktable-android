# P3e-b: the shared band editor (report)

Branch `p3e-bands` from `main` (7c4571c). Brief: `docs/briefs/P3e-bands.md`. The manual pages of contrast equalizer, color zones and color equalizer were read (docs.darktable.org, development); where the manual is silent the behaviour follows desktop's source (`src/iop/atrous.c`, `colorzones.c`, `colorequal.c`, master), named in the code comments.

## 1. What the brief asked and what was delivered

| # | Item | State |
|---|---|---|
| 1 | One band editor component: wide graph, fixed x axis, one value per node, drag vertically (and horizontally where desktop allows), soft brush radius slider, tabs, hue band from data, double tap resets, one history item per lifted finger | done (`app/darkroom/.../ui/graph/BandEditor.kt`, model `BandModel.kt`, interpolation `BandInterp.kt`; drawing and tokens in `ui-theme`: `BandDraw.kt`, `HueBand.kt`, `BandTokens.kt`) |
| 2 | Contrast equalizer: three curves, mix | done: luma, chroma, edges; the denoise splines of luma and chroma are the second curve of those tabs; inner nodes' x moved in the strip under the graph; the existing `mix` slider stays |
| 3 | Color zones: select by, three curves, process mode, picker mark | done: select by (resets as desktop's callback does), lightness / chroma / hue tabs, free nodes (drag in x and y, add by dragging on an empty place, remove by long press), "edit by area" (the brush), interpolation method per curve, the existing process mode and mix; the picker's picked colour is marked on the axis (the module's input in LCh from `color_pick`, no engine change) |
| 4 | Color equalizer: graph next to the generated sliders | done: hue / saturation / brightness, eight fixed nodes (moved by "node placement"), the sliders of the shown tab are shown (the schema's visibility rules showed the hue page only) |
| 5 | Placeholders replaced, l1 shows it | done: `P2j-unbound-controls-list.txt` lines "edit by area" and "interpolation method" are now built; the l1 layout shows the editor in the open module (screenshot) |

Not done / differs from desktop (each with the reason):
- Desktop's contrast equalizer shows the detail levels visible at the current zoom as stripes; the app does not (it would need the photo's zoom and the engine's scale in the panel).
- Color zones: "display selection" (the yellow mask on the photo) and "create a curve from an area" are not built (not in the brief); the zoom of the graph (scroll wheel while a modifier is held) is not built.
- Color equalizer: desktop's scroll-wheel adjustment on the picture and the cursor line of the hue under the pointer are not built (no pointer on a phone). The brush radius slider replaces the mouse-wheel radius; for color equalizer desktop has none (its Gaussian sigma is 35 degrees, which is the slider's default: 13.75 % of the axis).
- The line through the nodes is the editor's own drawing (cubic spline, Catmull-Rom, monotone Hermite; periodic on a hue axis): colour equalizer's real curve is a periodic RBF interpolation, so the line is an approximation of it. The picture is always the engine's.
- A vertical drag that starts on the graph edits the curve instead of scrolling the panel (a gesture conflict a phone cannot avoid; the rest of the panel scrolls as before).
- One value per double tap: a double tap resets the shown tab's curves, including the denoise curve of luma and chroma (desktop resets the curve nearest the pointer only).
- Colour zones' `curve_num_nodes` of 0 (the stand-in engine's schema default) is read as desktop's two nodes; the real engine always has at least one.

## 2. How to reproduce

```
python3 tools/app/check-screen-rules.py                       # screen rules (0 violations)
./gradlew :darkroom:testDebugUnitTest --tests '*P3eBandsTest' --tests '*BandsScreenshots' -Pdta.screenshots=true   # CI: p2a-app / p2b-real-engine
gh run list -R nannolo45/darktable-android -b p3e-bands
```
The emulator scenarios (`app/app-engine/src/androidTest/.../P3eBandsScenarioTest.kt`, listed in `tools/app/emulator-p2b.sh`) and the darktable-cli comparison (`p3eb` in `p2b-real-engine.yml`, cases `p3e-atrous`, `p3e-colorzones`, `p3e-colorequal`) run in `p2b-real-engine`.

## 3. Results

CI numbers are in section 5 (run ids, last code commit). Measurements on the CI runners are x86_64 numbers, not phone numbers.

- JVM, `P3eBandsTest` (22 tests): node limits (zones: order, 0.0025 gap, the seam of a periodic hue axis, the first splines version's fixed ends and the hue's linked end; contrast equalizer: strip moves inner nodes only, between neighbours, both curves of a tab together; color equalizer: x never changes), brush radius effect on neighbours (narrow leaves them, wide moves them less the further away, monotone in the radius, wraps on a hue axis), a drag made from the touch down (not the last step), writes (the parameter paths and the value mapping, e.g. hue 0.75 = 90 degrees, saturation 0.75 = 1.5), add / delete node with the count and the slot that falls out zeroed, reset, "select by", picker marks, the interpolation. On the stand-in engine: one history item per drag in each module, a second drag is a second item, undo, one item for a reset / add / select by, the tab kept by the controller, the editor replaces the graph and the notebook only.
- Screenshots (JVM, Robolectric), phone: `phone-p3eb-{atrous,colorzones,colorequal}-{darktable,modern}`, `phone-p3eb-colorzones-options-*`, `phone-l1-p3eb-{atrous,colorzones}-l1`, a node dragged and reset by touch (`phone-p3eb-drag-and-reset`), a node added by touch (`phone-p3eb-colorzones-node-added`); tablet: `tablet-p3eb-colorequal-darktable`, `tablet-p3eb-atrous-modern`. In the `ci-logs` branch under `runs/<id>-a1/p2a-app/screenshots/`.
- Emulator, real engine, public test RAW: a node moved in each module through the editor's model and the controller's drag calls (the calls the touch handler makes): one history item, the parameters changed, the XMP written and read again with identical parameters, the picture differs from the unedited one; each XMP and the app's export are compared with darktable-cli's render of the same XMP (size, PSNR) in `compare.txt` (`p3e bands <name>` lines).

## 4. Upstream and the stand-in engine

- `atrous.c`: the brush is `get_params` (absolute pull of every node's y towards the finger's y, Gaussian in x, radius default 1/6 of the axis, never below 1/24); the x positions are moved only in the strip under the graph (`y > height`), the inner nodes only, and both curves of a tab share them (`p->x[ch2][k] = p->x[channel][k]`).
- `colorzones.c`: `gui_changed` of "select by" calls `_reset_parameters`, which also resets the mix and the process mode (the app repeats that); the first and last node are tied on a hue axis with the first splines version only (`_move_point_internal`); nodes are added by a drag on an empty place or ctrl+click, removed by the right click.
- `colorequal.c`: the graph's y spans the parameter's whole range (`_area_set_value`), the neutral value is the middle; the node positions are `k / 8 + hue_shift / 360`.
- The stand-in engine (`FakeModuleCatalog`) gave `atrous`'s `x` / `y` and `colorzones`'s `curve` the wrong shape ([1][30], [1][60]): the shape of two-dimensional arrays is not in the schema's field list. A small table of the real shapes ([5][6], [3][20]) was added for these two modules, in the stand-in engine only; the real engine is untouched.

## 5. Open questions for the reviewer

1. Shared files with `p3e-curves`: `ui-theme/tokens.json`, `Tokens.kt` (`val band` in `DtTokens`), `Theme.kt` (`DtTheme.band`), `ui-spec.md` (new section 10), `p2b-real-engine.yml` (the `p3eb` block, a different output name), `tools/app/emulator-p2b.sh` (class list) and `DarkroomController.kt` (`Drag` got an optional `writes`, `bandDrag` / `bandEnd` / `bandApply`). Expect trivial conflicts in the lists.
2. The override notes of color zones' "edit by area" and "interpolation method" (`schema/overrides/colorzones.json`) are now dead (the controls are hidden, the editor builds them); the notes were left because `P2jComputedTest` asserts one of them. Remove both together if wanted.
3. A vertical drag on the graph does not scroll the panel. If that is annoying on a phone, a "lock" (edit only after a long press) is a small change in `bandGesture`.
4. Colour zones' picked mark needs the module on (`color_pick` refuses a module that is off); with it off the mark is simply not shown.

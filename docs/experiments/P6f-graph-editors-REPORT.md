# P6f: graph editors for base curve, saturation curve, lowlight, raw denoise, denoise (profiled); curve zoom

Brief: docs/briefs/P6f-graph-editors.md. Branch `p6f-graph-editors`.

## 1. What the brief asked and what was delivered

### 1. Reuse, do not fork
**Done.** No new editor. The curve editor (`ui/graph/CurveEditor.kt`, `CurveModel.kt`) gained what base curve and
saturation curve need: per-module limits in `CurveLayout` (node gap, add gap, end nodes fixed in x, a node added on the
curve, desktop's two ways of taking a node away, default nodes), any number of channels (tabs found by page count), the
other channel drawn faint, a strip under the graph, and a display scale (`CurveView`, `CurveScale`). The band editor
(`BandEditor.kt`, `BandModel.kt`) gained per-module brush radius limits, axis words for x and y, a channel colour per
tab, the other channels' curves drawn faint (`BandLine.faded`/`color`, ui-theme `BandDraw`), and a notebook chosen by a
parameter (denoise's colour mode). Gestures stay the app's existing ones: drag moves, tap or drag on empty space adds,
long press or drag off removes, double tap resets the shown channel; one history item per finished gesture; undo works
(JVM tests). One change for all curve graphs: a node added on empty space is now written when the finger moves or lifts,
not when it lands (so a second finger can still start rgb curve's pinch); taps and drags give the same result as before
(P3e's touch tests unchanged and green).

### 2. Modules
1. **base curve: done.** `CurveLayout.BASECURVE` (`basecurve[0][k]`, `basecurve_nodes[0]`, `basecurve_type[0]`, 20
   nodes, `init`'s (0,0),(1,1), right click puts an end node back on its corner, double click resets nodes and type).
   No histogram (desktop draws none). While a node is held the graph shows desktop's "x / y ( ±d)" in percent.
   "scale for graph" works (section 6). Presets: the graph reads the parameters, so a preset's curve shows; on the
   emulator the test raw opens with base curve on and a 6-node camera curve, which the graph showed and the drag edited
   (p2b t1). Preserve colors and fusion are untouched generated controls.
2. **saturation curve: done, histogram not drawn.** `CurveLayout.SATCURVE` (`channel[c].curve[k]`, two channels from
   the schema's saturation/brilliance tabs; satcurvergb.c: 0.0025 gap, ends fixed at x 0 and 1, a node added on the
   curve at the finger's x, right click: y back to 0.5 with two nodes, else removed; `reset_channel_curve`). The other
   curve is drawn faint (brilliance in desktop's orange), the white-to-purple strip under the graph. **Picker: done**:
   desktop's point picker marks the picked saturation (band min..max, line at the mean); new bridge action
   `satcurvergb pick` (section 3). **Not done:** the saturation histogram behind the graph (desktop computes it in
   `process` into GUI data; the bridge has no access without a new engine action) and the "display saturation mask"
   toggle (not in the brief).
3. **lowlight vision: done.** Band editor, one curve of six nodes (`transition_x/y`), soft brush (radius 1/6, at least
   0.2/6, lowlight.c `get_params`), x strip for the inner nodes (0.001 from neighbours), double tap resets x and y.
   Words: dark–bright, night vision (bottom)–day vision (top).
4. **raw denoise: done.** Tabs all/R/G/B, five nodes each, y only (desktop never moves an x), brush radius 1/10 (at
   least 0.2/5), the other three curves faint in their colours as desktop draws them.
5. **denoise (profiled): done.** Shown only in the wavelets modes (the schema's visibility rule for the graph). RGB
   colour mode: tabs all/R/G/B (channels 0..3, first notebook); Y0U0V0: luma/chroma (channels 4, 5, second notebook),
   as `denoiseprofile_tab_switch`. Seven nodes, y only, brush 1/14 (at least 0.2/7), coarse–fine, noisy–smooth.
6. **scale for graph / zoom: done, display only.** Tone curve and base curve: desktop's slider (0..40, schema node
   10 / 5) is now the app's (`GraphScaleSlider`), drawing both axes with `to_log` (tone curve: L only); nodes, grid
   (`dt_draw_loglog_grid`), touch and picker mark follow it. Rgb curve (zoom exists in the pinned tree, rgbcurve.c
   `_scrolled`): a two-finger pinch zooms and pans about the fingers' centre (zoom ≥ 1; offsets clamped as desktop).
   Neither writes a parameter (desktop writes none); JVM and screenshot tests check no history item.

Overrides: the two "scale for graph" notes are class f now; `docs/experiments/P2j-results/unbound-controls-modules-all.txt`
regenerated (7 → 5 controls waiting, 13 → 15 built by hand). `satcurvergb.json` has the picker entry.

### 3. Bridge
One new action, `satcurvergb pick {point|area}` → `{picked, saturation, saturation_min, saturation_max, gamut_lut}`
(`engine/src/p6f_actions.c`; documented in `dtbridge.h` and `engine-api.md` 5.4). It copies satcurvergb.c's
`pixel_s_in_norm` and its helpers and the data struct verbatim and computes what `color_picker_apply` computes; only
`_get_gamut_lut_for_picker` is adapted (the bridge's pipe instead of `develop->full.pipe`). It writes nothing. The curves
themselves need no action (`params_set`). New test id C47 (TEST-IDS.md). `upstream/` untouched.

### 4. Tests
- **Bridge C47** (`engine/tests/dtb_test_p6f.c`), g0 `tests="C14 C47"`: **done, green** (section 3).
- **JVM** `P6fGraphEditorsTest` (10 tests): base curve and satcurve layouts against the handlers (add/move/remove,
  gaps, end nodes, node limit, reset writes), one item per drag and undo on the stand-in engine, satcurve channels
  separate; lowlight brush, strip and reset; raw denoise tabs, ghosts, only the shown tab written; denoise tabs by
  colour mode and the notebook chosen; log scale and its inverse, grid, view round trip; rgb pinch maths (point under
  the fingers kept, clamps, limits, pan, picker mark follows); scale sliders write nothing. **Done.**
- **Screenshots** (`P6fGraphScreenshotTest`, three skins): base curve, saturation curve (brilliance tab), lowlight, raw
  denoise (R), denoise luma (Y0U0V0) and B (RGB), tone curve with scale 20, rgb curve zoomed (8 × 3 = 24), plus a
  touch test of base curve (drag → one item) and of rgb curve's pinch (zoom > 2, nothing written; shot
  `phone-darktable-p6f-09-rgbcurve-pinched`). **Done.**
- **Emulator** `P6fTapTest`: t1 base curve drag, t2 lowlight brush drag, t3 raw denoise brush drag: one item each, the
  parameters, the preview's pixels; waits on real conditions. **Done** (two runs, section 3).

`P3eBandsTest.tabSwitchingIsTheControllersAndKeepsTheOtherTabsAsTheyWere` looped over every band module expecting a
notebook; lowlight has none (desktop has none either), so the loop now leaves lowlight out. Nothing else changed in
existing tests.

## 2. Reproduce
- Bridge: `gh workflow run g0-app.yml --ref p6f-graph-editors -f tests="C14 C47"`.
- App tests and screenshots: `gh workflow run p2a-app.yml --ref p6f-graph-editors`.
- Emulator: `gh workflow run p2b-real-engine.yml --ref p6f-graph-editors`.
- Logs: branch `ci-logs`, `runs/<id>-a1/...` (g0: `g0-linux/bridge-tests-summary.txt`, `<image>/C47.log`; p2b:
  `p2b-emulator/p2b-test/p6f-results.txt`, `instrument-summary.txt`; p2a: `p2a-app/screenshots/*p6f*`).

## 3. Results
Measurements are x86_64 (CI runners and emulator), not phone numbers.

- **g0 46091575400** (fe8495f, `tests="C14 C47"`): green, 25 PASS, 0 FAIL. C47 on cr2 / raf / arw, curve vs the
  module with its default curve, pixels differing by more than 2 (8-bit):
  base curve 95.3 % / 99.1 % / 77.4 %; saturation curve 41.0 % / 2.9 % / 30.2 %; lowlight 87.8 % / 12.0 % / 57.0 %;
  raw denoise 61.9 % / 47.6 % / 46.6 %; denoise (profiled) 19.2 % / 4.6 % / 12.7 %. XMP round trip: parameters
  identical and render difference 0 for all fifteen; darktable-cli parity for the fifteen XMPs: max 0, PSNR inf.
  satcurvergb pick at the centre: saturation 0.2253 / 0.0246 / 0.0528, the pipe's gamut LUT found; no history item,
  parameters unchanged. Earlier: g0 46087211920 (5628107) failed C47 on raf and arw: with denoise strength 1 the
  luma curve changed 0 and 1,367 pixels by more than 2 on the clean test raws; the test now sets strength 20 (and the
  apk job of g0 46089507276 failed to compile the new emulator test: a missing import, fixed in fe8495f).
- **p2a 46089497033** (4b40398): green, JVM and screenshot suites including the 10 P6f JVM tests and the 25 P6f screenshots.
  Earlier p2a runs 46086160715 (compile error), 46086829694 and 46088084781 (test fixes: lowlight reset value, lazy
  panel scrolling, the stand-in's denoise mode, pinch position) failed. The p2b runs rebuild and rerun the same
  suites (apk job).
- **p2b 46094089867** (daebae6): green, 141 instrumented tests, 0 failed (JVM, screenshot and apk jobs green too).
  `P6fTapTest`: t1 base curve: items 11 → 12, the camera curve's node (0.460, 0.748) taken and moved to (0.5, 0.906),
  6 nodes before and after, the preview changed; t2 lowlight: items 11 → 12, transition_y [0.50, 0.57, 0.81, 0.57,
  0.50, 0.50]; t3 raw denoise: items 11 → 12, y[0] [0.50, 0.50, 0.89, 0.50, 0.50], R untouched 0.5, module on.
- **Second p2b 46097107978** (3f6e130, which adds only this report to daebae6): green, 141 tests, 0 failed, the same
  P6f numbers (t1 node (0.459, 0.747) → (0.5, 0.906), t2 and t3 identical). The commit after 3f6e130 adds only this
  run's id to the report.
- Earlier p2b 46090694570 (fe8495f): 140 of 141 passed;
  P6f t1 failed because the test expected the identity curve and the raw opens with base curve on and a 6-node camera
  curve; t2 lowlight: items 11 → 12, transition_y [0.50, 0.57, 0.81, 0.57, 0.50, 0.50]; t3 raw denoise: items
  11 → 12, y[0] [0.50, 0.50, 0.89, 0.50, 0.50], R untouched 0.5.

## 4. Upstream surprises
- `src/iop/basecurve.c` `to_lin` returns `(powf(base - 1.0f, x) - 1.0f) / base`, which is not the inverse of its
  `to_log` (`log(x * base + 1) / log(base + 1)`); tonecurve.c's `to_lin` uses `base + 1.0f`. With "scale for graph"
  above 0 desktop's base curve puts a node added by drag away from the pointer. The app uses the true inverse for both
  modules.
- `src/iop/tonecurve.c`: `semilog` is only ever set to 0 in the pinned tree, so the scale is always log-log.
- `src/iop/rgbcurve.c` `_scrolled`: the zoom changes only while `darkroom_skip_mouse_events` is set (the darkroom's
  pan-and-zoom key); there is no upper limit (the app stops at 100).
- lowlight.c, rawdenoise.c and denoiseprofile.c draw their curve with two extra mirrored points
  (`x[BANDS-2] - 1`, `x[1] + 1`); the app draws the Catmull-Rom line through the nodes only (drawing only; the
  parameters and the picture are the engine's).
- The stand-in engine (ours, not upstream) starts denoise (profiled) in "non-local means auto": the schema's `default_pos`
  for `mode` is 1, which `FakeModuleCatalog` reads as a list position (entry 1 is "non-local means auto", value 3)
  while darktable's default is `MODE_WAVELETS` (value 1). Not changed here; the screenshots set the mode.

## 5. Open questions for the reviewer
- Saturation curve's histogram: desktop fills it in `process` (`_update_sat_histogram`) for the GUI. A bridge action
  computing it on the module's input (same formula) would be needed. Wanted?
- Raw denoise shows both pages of its stack, "raw denoising only works for raw images" above the graph even for raws
  (pre-existing: the generic stack shows every page; desktop shows one by `reload_defaults`).
- Adding a node is now written on the finger's move or lift instead of its landing (for rgb curve's pinch). Keep for
  all curve graphs?
- Rgb curve's pinch zoom: upper limit 100 and reset only when the app restarts or the instance changes; desktop resets on
  image change. Add a reset (double tap with two fingers)?
- Fix the stand-in's `default_pos` reading (section 4)?

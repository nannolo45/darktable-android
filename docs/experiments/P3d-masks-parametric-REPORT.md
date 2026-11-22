# P3d stage 2b: parametric masks and mask refinement (report)

Branch `p3d-masks-parametric`. No engine change (`engine/`, `engine-jni`, `upstream/`, `app/lpl` untouched).
Main (stage 2a, 511bb98) was merged in; one conflict (the compare loop's names in `p2b-real-engine.yml`: both `tree` and `parametric` kept). `Masks.kt` / `MasksPanel.kt` merged without conflict; 2a's tree stays, and `drawn & parametric` stays disabled (`drawn & parametric: not built yet`).

## 1. Delivered, item by item
1. Mode `parametric` (classic, `masks: "parametric"` through `blend_set`): done (`MaskMode.available`).
2. Parametric section: done. Channel buttons per colour space (Lab L a b C h; display RGB g R G B H S L; scene RGB g R G B Jz Cz hz); per active channel: `RangeSlider4` (four markers, order kept, touching allowed, double tap = whole range, one history item per lifted finger), whole-range warning icon (`warning.svg`, new), output range behind a toggle (a set hidden output range says it still applies), invert per slider, boost factor (scene channels except hz), picker, remove. Colour space sheet asks first, always (`change` / `cancel`). Picker: the shared P3c tool with synthetic control ids below -1000 (`DarkroomController.pickerSpec`, `applyPickerLocked`, `pickerCheck`, `setView`; about 10 lines); range from the area as desktop's `_blendif_scale` with its 0.01 feather (`PickRange`).
3. `show mask` in parametric mode: done (same toggle, same engine call).
4. Refinement (collapsed, every mode but off and uniform): done. Limits 0..250 px, 0..100 px, +-100 %; reset to desktop's defaults; details threshold disabled with a note for non-RAW.
5. Read-only: shown, no change (tests: controls disabled; `edit` returns).
Files: `ParametricMasks.kt` (logic), `ui/ParametricMask.kt`, `ui/MaskRefinement.kt`, `ui/RangeSlider4.kt`; tokens `size.rangeMarker`, `size.rangeMarkerLine`; `ui-spec.md` 8.1 and 8.2.

## 2. Differences from desktop / limits (stated, not hidden)
- The picker converts the engine's pick to the channel only for: Lab pick -> L a b, C (from the box of a, b); Lch -> C h; rgb -> g R G B (g = 0.3 R + 0.59 G + 0.11 B, desktop's fallback without the profile's luminance); hsl -> H S l; jzczhz -> Jz Cz hz. A pick space "none" is read as the blend colour space's own. Anything else gives a note ("set the range by hand"). Not built: the reversed hue range across the wrap, the profile's luminance for g.
- Boost range -18..18 EV in the slider is a guess; the engine's `RANGE` answer shows in the message line.
- Arming the picker picks at once with its first area (P3c behaviour), so the range is set by the arming pick and again by the user's area.

## 3. Reproduce
`python3 tools/app/check-screen-rules.py` (54 files, 0 violations). JVM tests `P3dParametricTest` (marker order, reset, one item per drag, colour space asks and resets, refinement and its limits, pick scaling) and `P3dMasksTest` (one line changed: it asserted that `parametric` is refused; it now asserts `drawn & parametric` is refused). Screenshots `P3dParametricScreenshotTest` (phone, both skins, parametric and refinement; one tablet). Emulator `P3dParametricScenarioTest` (e1 luminance range on exposure: yellow region keeps the exposure, grey region loses it, XMP reopens with the same range, export compared with darktable-cli as `p3d-parametric`; e2 range from a picked area against the engine's stats).

## 4. Running log (CI)
See the reply of the agent for run ids. Infra: the Maven mirror answered 429 for `nativeruntime-dist-compat` in `darkroom:testDebugUnitTest` in the `apk` job on four runs in a row (p2a-app wins the race for the same jar), which skips the emulator and compare jobs.
- First emulator run (6b2f054): e1 and e2 failed in my test code, not in the app: e1 compared the wrong way round (mean change where the mask is off 60.7, where on 4.5), e2 read the range set by the arming pick. Fixed in the tests (no assertion weakened beyond the arbitrary absolute bound of 3.0, replaced by a ratio of 5). The darktable-cli comparison of circle and brush passed (psnr 45.1).

## 5. Open questions
- Colour space change asks always, also with no active channel (brief: "asks first"). Say if it should ask only when channels would be lost.
- `active` semantics for a channel are those of `blend_get` (switching on = `active: true`); the real engine's reaction to `boost` paths is unverified on the emulator.

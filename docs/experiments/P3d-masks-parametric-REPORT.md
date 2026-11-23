# P3d stage 2b: parametric masks and mask refinement (report)

Branch `p3d-masks-parametric`. No engine change (`engine/`, `engine-jni`, `upstream/`, `app/lpl` untouched).
Main (stage 2a, 511bb98) was merged in; one conflict (the compare loop's names in `p2b-real-engine.yml`: both `tree` and `parametric` kept). `Masks.kt` / `MasksPanel.kt` merged without conflict; 2a's tree stays, and `drawn & parametric` stays disabled (`drawn & parametric: not built yet`).

## 1. Delivered, item by item
0. **Model change after the first emulator run (22 Nov).** The pinned engine has no classic parametric mode: after `blend_set masks: "parametric"` `blend_get` reads `masks: flexi` with a `mask_id` and every classic channel inactive (engine-api.md 6.9; desktop migrates legacy edits into the tree). So parametric is built on stage 2a's calls: a channel button = `mask_add` type `parametric` into the module's group (`group` = `blend_get.mask_id`, null = the module's own new group); ranges, boost, output view = `mask_update` of the element; invert = `mask_member_update`; remove = `mask_member_remove` + `mask_delete` as one history group; the same channel may be added twice. The classic `blend_get.parametric` channels are not used. Refinement stays on `blend_set`.
1. Mode `parametric`: the button asks for `masks: "parametric"` (the engine answers a flexi group); the section shows in modes parametric, drawn and the tree reading as drawn & parametric (`MaskMode.available`).
2. Parametric section: done. Channel buttons per colour space (Lab L a b C h; display RGB g R G B H S L; scene RGB g R G B Jz Cz hz); per active channel: `RangeSlider4` (four markers, order kept, touching allowed, double tap = whole range, one history item per lifted finger), whole-range warning icon (`warning.svg`, new), output range behind a toggle (a set hidden output range says it still applies), invert per slider, boost factor (scene channels except hz), picker, remove. Colour space sheet asks first, always (`change` / `cancel`); the change also removes elements of the old space (manual), as one undo step. Picker: the shared P3c tool with synthetic control ids below -1000 (small numbers handed out per element slider: the real engine's form ids are ~1.8e9) (`DarkroomController.pickerSpec`, `applyPickerLocked`, `pickerCheck`, `setView`; about 10 lines); range from the area as desktop's `_blendif_scale` with its 0.01 feather (`PickRange`).
3. `show mask` in parametric mode: done (same toggle, same engine call).
4. Refinement (collapsed, every mode but off and uniform): done. Limits 0..250 px, 0..100 px, +-100 %; reset to desktop's defaults; details threshold disabled with a note for non-RAW.
5. Read-only: shown, no change (tests: controls disabled; `edit` returns).
Files: `ParametricMasks.kt` (logic), `ui/ParametricMask.kt`, `ui/MaskRefinement.kt`, `ui/RangeSlider4.kt`; tokens `size.rangeMarker`, `size.rangeMarkerLine`; `ui-spec.md` 8.1 and 8.2.

## 2. Differences from desktop / limits (stated, not hidden)
- The picker converts the engine's pick to the channel only for: Lab pick -> L a b, C (from the box of a, b); Lch -> C h; rgb -> g R G B (g = 0.3 R + 0.59 G + 0.11 B, desktop's fallback without the profile's luminance); hsl -> H S l; jzczhz -> Jz Cz hz. A pick space "none" is read as the blend colour space's own. Anything else gives a note ("set the range by hand"). Only direct members of the module's group are listed (elements inside nested groups show in 2a's tree only). Not built: the reversed hue range across the wrap, the profile's luminance for g.
- Boost slider 0..18 EV (6.9); the picker adds the channel's offset (Jz, Cz: -6.64) to the element's `boost`.
- Invert is the element's (one per channel row, not per slider).
- Arming the picker picks at once with its first area (P3c behaviour), so the range is set by the arming pick and again by the user's area.

## 3. Reproduce
`python3 tools/app/check-screen-rules.py` (54 files, 0 violations). JVM tests `P3dParametricTest` (marker order, reset, one item per drag, colour space asks and resets, refinement and its limits, pick scaling) and `P3dMasksTest` (one line changed: it asserted that `parametric` is refused; it now asserts `drawn & parametric` is refused). Screenshots `P3dParametricScreenshotTest` (phone, both skins, parametric and refinement; one tablet). Emulator `P3dParametricScenarioTest` (e1 luminance range on exposure: yellow region keeps the exposure, grey region loses it, XMP reopens with the same range, export compared with darktable-cli as `p3d-parametric`; e2 range from a picked area against the engine's stats).

## 4. Running log (CI)
- Infra: the Maven mirror answered 429 for `nativeruntime-dist-compat` in `darkroom:testDebugUnitTest` on several runs (p2a-app and the p2b apk job race for the same jar); a failed apk job skips emulator and compare.
- 6b2f054 (classic model, before the merge): JVM and screenshots green, emulator e1/e2 failed in test code (comparison the wrong way round; e2 read the arming pick). Fixed.
- 45077344585 (merged with main, classic model): emulator e1 timed out: the real engine answers `flexi` with inactive channels: the model above.
- 45080379162 (elements model, 85dafce8): JVM test failed (undo of a remove is two steps without a history group) and the section hid in mode "drawn & parametric" (fake flags): fixed in d76f5a5.
- 45081458881 (d76f5a5): p2a-app green; emulator 84 of 85: e1 passed (luminance range on exposure: where the mask is on the picture keeps the exposure, mean change against the unmasked picture 4.5; where off it loses it, 60.7; XMP reopens with the same range, picture difference 0.0), compare `p3d mask parametric` psnr 43.28 against darktable-cli (circle 45.06, brush 45.05, tree 45.26; compare job green); e2 failed: picker ids overflowed with the real engine's large form ids. Fixed in 509593f.
- 509593f: see the agent's reply for the final run.

## 5. Open questions
- Colour space change asks always, also with no active channel (brief: "asks first"). Say if it should ask only when channels would be lost.
- Refinement through `blend_set` (feathering, blur, brightness, contrast, details) changes the module's whole-mask settings; whether it is what the tree's own refinement should write is not verified against the picture on the emulator (only the JVM stand-in checks the values).
- The `parametric` mode button is not highlighted when the engine reads the mask as `flexi` (the tab then reads it as `drawn`).

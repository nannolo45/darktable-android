# P6e: colour dialog for framing and watermark, the rest of watermark

Brief: docs/briefs/P6e-colour-dialog-framing-watermark.md. Branch `p6e-colour-dialog`.

## 1. What the brief asked and what was delivered

### 1. Shared colour dialog
- Colour row (swatch with the current colour, hex beside it) opening a colour dialog as a bottom sheet: saturation/value
  field, hue strip, red/green/blue sliders 0–255, hex field (ASCII keyboard in capitals; "#" optional, 3 or 6 digits),
  recent colours (per app, `files/settings/recent-colours.txt`, newest first, at most 8), Cancel / OK, a before/after
  swatch pair that follows every drag: **done** (`app/darkroom/.../ui/ColorDialog.kt`: `ColorRow`, `ColorSwatch`,
  `ColorDialogSheet`; arithmetic in `ColorMath.kt`, `Rgb`, `Hsv`, `RecentColors`).
- One history item on OK, none on Cancel, undo works: **done** (JVM `P6eFramingWatermarkTest`, emulator `P6eTapTest.t1`,
  bridge C46). Differs from desktop in one point: OK with the colour unchanged writes nothing (desktop's colour
  button emits "color-set" and adds an item even then).
- Written in the module's parameter space as `_colorpick_color_set` does (GdkRGBA red/green/blue → the three floats,
  one `params_set`); no drift: the dialog keeps the parameter's exact value until the user changes the colour, a slider
  changes only its own channel, and the HSV field/strip snap to 8-bit values: **done** (JVM `P6eColorMathTest`: all 256
  byte values through float and back, 2000 random off-grid floats, hex round trip on a 18³ grid, HSV round trip on a
  52³ grid within 1e-9 and byte-exact; bridge C46 writes 51/255, 102/255, 204/255 and reads the same floats, and a
  value off the 8-bit grid written back reads identically).
- In a shared place, tokens and icons only: **done**. `ColorDialog.kt` takes a title, the colour, the recent list and
  two callbacks, so later packages can use it with any float triple. New tokens `size.colorField`, `size.colorHue`,
  `size.colorCursor`; `check-screen-rules.py`: 0 violations.
- Retouch's `fill_color`: **not done, on purpose**: it already has red/green/blue sliders under its swatch and a picker
  (P3m), so it is not "picker only"; attaching the dialog there means rewriting its per-shape setting path, not small.

### 2. Framing (`borders`)
- Border colour and frame line colour rows with the dialog and desktop's picker buttons (P3c machinery; new bridge
  action `borders pick {which: "border"|"frame"}` = `color_picker_apply`, including desktop's guard that writes nothing
  when the pick equals either colour): **done**. The frame line row is greyed out by the schema's sensitivity rule as
  desktop does while the frame line size is 0. The picker stays armed after a pick as every other picker of the app
  (desktop keeps it on too); the dialog's OK turns it off, as `_colorpick_color_set` calls
  `dt_iop_color_picker_reset`.
- "Remove the not-available notes from `borders.json`": there were none for these rows; the overrides now carry the
  two `picker` entries and class f notes on the colour buttons (13, 17).

### 3. Watermark
1. Marker list: the `.svg`/`.png` files of darktable's `watermarks` folder in the APK, then the configuration folder's
   (`_refresh_watermarks`, bridge `watermark list_files`), shown as desktop's "name (ext)"; a choice writes `filename`:
   **done**. The configuration folder is the engine's private one, so in practice only darktable's seven files show;
   there is no way yet to add files on the phone (see open questions). Desktop's refresh button is replaced by reading
   the lists each time the panel or the list opens.
2. Text field (written on the keyboard's done key or when the field is left: one history item), font chooser
   (families and faces from the engine), text colour row with the dialog, picker from the image
   (`watermark pick`): **done**. Text, font and colour are greyed out for `.png` markers
   (`_text_color_font_set_sensitive`).
3. Alignment: desktop's 3×3 toggles, the selected cell highlighted (new icon `alignMark.svg`): **done**.
4. Fonts on Android: **done, with a finding that differs from what the brief expected**. Fontconfig's built-in
   configuration in the APK already finds the phone's fonts: on the emulator (API 33) it lists 520 fonts (its
   compiled-in default directories include `/system/fonts`), so text would have rendered, but with no DejaVu Sans
   (desktop's default watermark font "DejaVu Sans 10", which every XMP from the PCs names unless changed), no
   `sans-serif`/`Sans` alias and a cache directory outside the app. The bridge now writes
   `<cachedir>/fontconfig/fonts.conf` before `dt_init` on Android (`engine/src/p6e_actions.c` `dtb__fonts_setup`):
   the APK's `share/fonts`, the phone's `/system/fonts` and `/product/fonts`, the cache in the app's cache folder,
   `sans-serif` and `Sans` preferring DejaVu Sans. The APK carries DejaVu Sans 2.37 (Bitstream Vera licence + public
   domain, licence file `share/fonts/LICENSE-DejaVu.txt`), 757,076 bytes unpacked, fetched with a pinned SHA-256 by
   `tools/build-engine-android.sh`; with it the default watermark renders with the same font on the phone and the PCs
   (section 3: text pixels within 0.02 %, overlap 0.998). Since fonts did render without it, bundling is a choice, not
   a necessity: the reviewer may drop it (open questions). The font list offers the families fontconfig can draw
   English text with (pango's families and faces, as GTK's font chooser lists them, minus those whose fonts do not
   cover the "en" language); on the emulator 25 families, 136 faces, among them CJK families, which do contain Latin
   letters.
5. `watermark.json`'s "not available yet" note: **done** (replaced by class f notes;
   `docs/experiments/P2j-results/unbound-controls-modules-all.txt` regenerated: one class b control fewer).

### 4. Bridge
- New module actions `borders pick`, `watermark pick`, `watermark list_files`, `watermark list_fonts`; `filename`,
  `text`, `font` were already settable through `params_set` (char arrays). Documented in `dtbridge.h` and
  `docs/design/engine-api.md` 5.4. `engine/CMakeLists.txt` links fontconfig and pangocairo (darktable dependencies
  already). Test C46 (`engine/tests/dtb_test_p6e.c`, row in TEST-IDS.md, g0 cache key updated).

### 5. Tests
- Bridge C46 + C14 (g0 `tests="C14 C46"`): **see section 3**.
- JVM: `P6eColorMathTest` (conversions, hex parsing, recent colours), `P6eFramingWatermarkTest` (OK/Cancel/unchanged OK,
  undo, recent colours read back from the file, both framing pickers and watermark's picker, the dialog turning the
  picker off, marker list, text, font, alignment, one item each): **done**.
- Screenshots (p2a), three skins: framing with both rows, the dialog, the dialog after typing "#f80", watermark with
  marker, text, font and colour, the marker list, the font list (`PhoneP6eScreenshots`, 6 per skin): **done**.
- Emulator (p2b) `P6eTapTest`: t1 framing border colour typed in hex, OK → one item, the floats, the preview changes;
  t2 watermark marker and the bottom-right alignment by tap, one item each; t3 the fonts the engine sees and a text
  watermark exported with and without the module (pixels counted on the device), whose XMPs the compare job renders
  with darktable-cli (`tools/app/p6e-watermark-compare.py`). Two p2b runs on the final commit: **see section 3**.

## 2. Reproduce
- Bridge: `gh workflow run g0-app.yml --ref p6e-colour-dialog -f tests="C14 C46"`.
- App tests and screenshots: `gh workflow run p2a-app.yml --ref p6e-colour-dialog`.
- Emulator and darktable-cli comparison: `gh workflow run p2b-real-engine.yml --ref p6e-colour-dialog`.
- Logs: branch `ci-logs`, `runs/<id>-a1/...` (C46: `g0-linux/<image>/C46.log`; p2b: `p2b-emulator/p2b-test/p6e-results.txt`,
  `p2b-compare/compare.txt`).

## 3. Results
- **g0 46074470023** (head 808d572, `tests="C14 C46"`): C14 and C46 PASS on cr2, raf, arw; darktable-cli parity of
  the C46 XMP max 0, PSNR inf on all three. Framing's corner pixel 51 102 204 for a border set to 51 102 204;
  border pick e.g. 0.5078 0.3962 0.3364 (cr2); 7 watermark files; 8 font families on the Linux runner; the text
  watermark changes 0.653 % / 0.634 % / 0.622 % of the pixels (cr2 / raf / arw, max difference 255 / 236 / 255); XMP
  round trip render identical. (First g0 46069279851 failed C46 on `borders pick`, fixed in 55ffa63; second g0
  46072200685 failed before testing: the runner's apt could not reach packages.microsoft.com, 403.)
- **p2a 46070351896** (ecd3230): green, `P6eColorMathTest`, `P6eFramingWatermarkTest` and the 18 `PhoneP6eScreenshots`
  included. The p2b runs below rebuild and rerun the same JVM and screenshot suites on 808d572 (apk job green).
- **p2b 46074481784** (808d572): green, 138 instrumented tests, 0 failed. `P6eTapTest`: t1 history 11 → 12, colour
  [0.2, 0.4, 0.8], preview 1024x681 → 1024x716 (the frame); t2 marker "simple-text.svg" and alignment 8, history
  11 → 13; t3 fontconfig file in the app's cache, 520 fonts under the built-in configuration, 25 families / 136 faces
  offered, the text watermark changes 66,316 of 10,168,616 pixels (0.652 %). Compare job: app vs darktable-cli for the
  same watermark XMP: text pixels 66,316 vs 66,305 (ratio 1.000), overlap 0.998, centres (1163.1, 42.0) vs (1163.3,
  42.0), PSNR of the two exports 44.04 dB; G0 export 75.73 dB.
- **Second p2b 46078912928** (953b07f, which adds only this report to 808d572): green, 138 tests, 0 failed; the same
  P6e numbers (t1 11 → 12, t2 11 → 13, 66,316 text pixels; compare ratio 1.000, overlap 0.998, 44.04 dB).
  The commit after 953b07f adds only this run's id to the report.
- Measurements here are x86_64 (CI runners and the emulator), not phone numbers.

## 4. Upstream and CI surprises
- `actions2.c` answers `NOT_FOUND` for any `borders` action it does not know, so the new actions are dispatched first
  (first g0 run 46069279851: C46 failed on `borders pick` with "borders has no action 'pick'").
- Fontconfig 2.18.4 as built for the APK (`deps/recipes/fontconfig.sh`) has compiled-in default font folders that
  include the phone's `/system/fonts` (520 fonts on the emulator), so "no fonts at all" was not the case.
- `src/iop/watermark.c` (`_watermark_get_svgdoc`): with an empty text the marker `$(WATERMARK_TEXT)` is not
  substituted there but by `dt_variables_expand`; the font size in the font string is ignored (only family, style
  and weight reach the SVG).
- `src/iop/borders.c` `color_picker_apply` refuses a pick equal to *either* colour (its loop guard), so the frame line
  picker does nothing on the colour the border already has; kept as desktop.

## 5. Open questions
- The watermark configuration folder is the engine's private folder; should LightPhotoLibrary carry the PCs'
  `~/.config/darktable/watermarks` like the LUT folder (P6b)? Not done here.
- OK with an unchanged colour writes no history item (desktop adds one). Keep?
- Keep DejaVu Sans in the APK (757 KB unpacked)? The phone's own fonts render without it; it is there so that
  desktop's default watermark font is the same on both sides.
- Fonts from `/system/fonts` differ between phones; an XMP with "Roboto" renders with another font on the PCs
  (and DejaVu Sans on a phone without Roboto). Only DejaVu Sans is guaranteed on both sides.

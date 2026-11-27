# Brief P6e: a colour dialog, used by framing and watermark; the rest of watermark

Branch `p6e-colour-dialog` from `main` (this brief is its first commit). Read CLAUDE.md, PLAN.md 6.1 item 2, the audit rows "framing (`borders`)" and "watermark (`watermark`)" and the files table row "watermark SVGs" in `docs/experiments/P5a-module-audit.md`, `schema/overrides/borders.json` and `schema/overrides/watermark.json`, and the manual pages for framing and watermark (https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/framing/ and .../watermark/). Desktop reference (pinned submodule, check it out first: `git submodule update --init --depth 1 upstream`): `src/iop/borders.c` (`gui_init`, `_colorpick_color_set`, `color_picker_apply`, the frame line colour), `src/iop/watermark.c` (`gui_init`, `refresh_watermarks`, `_combo_box_changed`, `_alignment_callback`, `_text_entry_changed`, `_font_callback`, `_colorpick_color_set`, `color_picker_apply`, `_load_svg`/the `$(WATERMARK_*)` text substitution), and `src/gui/color_picker_proxy.c` for how a picker writes a colour. Medium package.

## 1. A shared colour dialog (one component, reused)
1. A colour row as desktop's colour button: a swatch with the current colour; tapping opens a **colour dialog** (bottom sheet on the phone) with: hue/saturation/value controls (a square or ring plus a hue slider is fine), **R, G, B sliders 0–255** and a **hex field** (number/hex keyboard), a short row of recent colours (kept per app, not per photo), Cancel / OK. Live preview of the swatch while dragging; **one history item on OK**, none on Cancel; undo works.
2. Colours are written in the module's own parameter space as desktop's `_colorpick_color_set` does (sRGB display values → the float params); round trip param → dialog → param must not drift (test it).
3. Put the component in a shared module (e.g. `ui-common`/darkroom widgets) so later packages can reuse it; tokens and icons only (CLAUDE.md rule 5). If retouch's `fill_color` currently has no way to set a colour except the picker, attach the same row there too (small; skip and say so if it is not small).

## 2. Framing (`borders`)
- **Border colour** and **frame line colour** rows with the dialog, plus desktop's **picker buttons** next to them (pick the colour from the image) reusing the P3c picker machinery, behaving as `color_picker_apply` (the picked colour is written; the picker toggles off as desktop does).
- Remove the matching "not available" notes from `schema/overrides/borders.json`.

## 3. Watermark
1. **Marker list**: the SVG files in darktable's watermarks folder that the APK carries (and the user's `watermarks` config folder if the app has one; say what you chose), as desktop's `refresh_watermarks` combo; selecting writes `filename`.
2. **Text** field (for the `$(WATERMARK_TEXT)` marker), **font** chooser, **text colour** row (dialog) and the **colour picker from image**.
3. **Alignment**: desktop's 3×3 grid of toggle buttons.
4. **Fonts on Android**: find out what the engine's fontconfig/pango actually sees in the APK (bundled fonts? `/system/fonts`?). Offer in the font chooser exactly the families that render; if none render, bundle one free font (OFL, e.g. DejaVu or Noto Sans — licence file included, say its APK size) and configure fontconfig for it. Prove a text watermark renders on the device build (emulator) and matches darktable-cli for the same XMP on Linux in a simple statistic.
5. Remove the note in `schema/overrides/watermark.json`.

## 4. Bridge
Only if needed (e.g. `watermark list_files`, `fonts list`, setting the string params `filename`/`text`/`font` if param_set does not already handle char arrays): `dtbridge.h` + `docs/design/engine-api.md` 5.4, new bridge tests with the next free C ids registered in `engine/tests/TEST-IDS.md`. Never touch `upstream/`.

## 5. Tests
- Bridge (g0 with `tests=` limited to the new ids plus C14): set border/frame colours and the watermark file/text/font/colour/alignment, XMP round trip, render differs from default (simple statistic); the font list is non-empty and a text watermark renders non-empty pixels.
- JVM: colour conversions (param ↔ RGB 0–255 ↔ hex ↔ HSV) round trip without drift, hex parsing (with/without `#`, 3 and 6 digits, invalid input rejected), recent-colours list.
- Screenshots (p2a): the dialog; framing with both rows; watermark with list, text, font, colour, alignment — in the three skins.
- Emulator (p2b): open framing, set the border colour by hex in the dialog, OK → one history item and the preview changes; open watermark, pick another marker and an alignment. Wait on real conditions, not sleeps. Run p2b at least twice on the final commit; both green.

## 6. Out of scope
Colour checker, other modules' missing pickers (P6a leftovers), graph editors.

## Economy and report
One agent, no sub-agents; save tokens (no long log dumps into context, grep the artefacts). Report in `docs/experiments/P6e-colour-dialog-REPORT.md` as CLAUDE.md says, with CI run ids, and open the pull request. Do not merge it.

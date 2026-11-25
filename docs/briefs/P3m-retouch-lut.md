# Brief P3m: retouch and color look up table (PLAN.md 6, Phase 3 item 6)

Branch `p3m-retouch-lut` from `main`. Read CLAUDE.md, PLAN.md 3.x (module editors, 3.6 theme tokens), the masks work (P1b2, P3d reports and their code: retouch shapes are darktable mask forms, so reuse the bridge's mask calls and the on-photo shape tools wherever they fit), the curve/band editors (P3e) for how hand-built editors plug in, and the L1 layout (PUI3a). **Read the manual pages first** (rule 8): retouch https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/retouch/ and color look up table https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/color-look-up-table/ , and upstream `src/iop/retouch.c`, `src/iop/colorchecker.c` at the pinned commit for exact behaviour and defaults.

The owner approved mockups on 24 Nov 2026 (described here; corrected against the manual): in every skin (darktable, modern, lightcraft), the controls live inside the module as in desktop.

## Retouch
1. Panel: shapes count (all scales, as desktop); shape buttons circle, ellipse, path, brush; algorithm buttons clone / heal / fill / blur — the choice applies to new shapes and, as desktop's ctrl+click, a long-press (or a clear "apply to selected shape") changes the selected shape's algorithm; "show and edit shapes", "display masks" (yellow overlay of target shapes), "temporarily switch off shapes". Selected shape section as desktop: shape selected (name/type), fill mode erase/color, fill color (with the colour picker), brightness, blur type gaussian/bilateral, blur radius, mask opacity — each shown only when it applies.
2. On the photo (owner: "tap and drag are enough"): with a shape tool active, tap places the target and its source at desktop's default offset (the "+" position); dragging the source or target moves it; for circle and ellipse, press on the spot and drag to where the source should be places both (desktop's click-and-drag). Resize and the ellipse/feather handles as the masks tools already do; two fingers pan/zoom the photo, never edit the shape. No "pick source first" button. Brush shapes are drawn with the finger as for masks, without pressure (approved). Rotation of a shape: as the masks tools do it today, if they do; otherwise leave out and say so.
3. Wavelet decompose: **not in this package** (owner: later). An XMP with scales > 0 must keep working: the engine renders it; the panel shows "wavelet decompose: n scales · edit on desktop", shows and edits only the shapes of the current scale, and never changes the wavelet params. Never rewrite an XMP the app does not understand (rule 4).
4. While retouch is focused the uncropped image is shown with the crop frame as a guide, as desktop does, if the existing pipeline already supports showing the uncropped image for crop; otherwise propose it in the report.

## Color look up table
1. Patch grid as desktop: the default 24 color checker patches; above 24, a 7x7 grid; an outline on patches whose target differs from the source; the selected patch marked; a patch combo below the grid. Tap selects. The colour picker selects the nearest patch.
2. Sliders for the selected patch: lightness, green-red, blue-yellow, saturation (target offsets, as desktop).
3. Touch equivalents of desktop's mouse actions, in a long-press menu on a patch: "replace source with the picked colour" (shift+click a patch), "reset" (double-click), "delete" (right-click); and "add a patch from the picked colour" (shift+click empty space) as a button next to the grid.
4. Presets come from the existing presets menu (palettes); nothing new.
5. Not in desktop's UI, so not here: the mockup's "target color" and "number of patches" rows are dropped.

## Tests
Bridge tests for any new bridge call; XMP round trip for retouch (each algorithm, each shape, source moved) and color look up table (patch changed, added, deleted, reset) read back by `darktable-cli` in CI as the other editors do; controller/JVM tests; screenshot tests in all three skins; an emulator scenario that places a heal circle and changes a patch and checks the render changes. Wavelet: a test that an XMP with scales > 0 survives open/edit-of-a-shape/save with its wavelet params untouched.

## Report
`docs/experiments/P3m-retouch-lut-REPORT.md` as CLAUDE.md says, with CI run ids, and every place the app differs from desktop with the reason.

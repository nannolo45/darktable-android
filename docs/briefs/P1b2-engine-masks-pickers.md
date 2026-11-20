# P1b, part 2: blending, masks, pickers and coordinates in the engine

What colour pickers (P3c), masks (P3d) and the crop tool's clean-up wait for. Engine and bridge side with the JNI and
Kotlin interface, `FakeEngine` and the contract suite for each call; **no screens**, except item 2's switch inside the
crop tool. Branch `p1b2-engine-masks-pickers` from `main`. Report
`docs/experiments/P1b2-engine-masks-pickers-REPORT.md`, running log `docs/experiments/P1b2-engine-masks-pickers.md`
(pushed at least every 30 minutes). Another agent builds the styles and presets screens (`app/darkroom/ui`, the app
module): keep out of them.

Read `CLAUDE.md`, `docs/design/engine-api.md` (section 3 is the outline; section 5 shows the form part 1 gave its
calls), the reports of P1b part 1 and P3a (its limits 1 to 3 and open questions), and how part 1 built and tested
(`engine/tests`, the g0-app workflow, the valgrind and comparison scripts).

## Order of work
**First, within the first hour: exact signatures and JSON** of every call below in `docs/design/engine-api.md` on the
branch, pushed; the reviewer reads them while you build. Then in this order; stop at a clean point if it proves too
large and say exactly what is built and tested.

## Deliverables
1. **A render that bypasses one instance** (`render_preview` and `render_region` with an option naming an instance
   to leave out for that render only): no history item, no undo state, the session's edit untouched, the next
   ordinary render as before. Engine test: history, undo and redo queues and the written XMP are byte-equal before
   and after; the picture equals the one with the instance switched off.
2. **The crop tool uses it** (`app/darkroom` `CropSession` and the controller's bracket): the switch-off, render,
   undo bracket and `RedoGuard` go; redo steps survive opening the tool; opening and closing the tool without a
   change leaves the XMP byte-equal (P3a report, limits 1 and 2); the sharp region when zoomed in works while the
   tool is open (limit 3) if the option makes that straightforward. P3a's tests keep passing; the ones that pinned
   the limits are changed to pin the new behaviour, and the report says which.
3. **`undo()` and `redo()` keep the XMP's history hashes consistent**: today `darktable:history_auto_hash` is
   dropped after any undo through the bridge (upstream `dt_history_snapshot_restore`, P3a report). After an undo
   back to a state, the XMP written equals the XMP written at that state, apart from the change timestamp; say
   whether the timestamp should move (what desktop does) and do the same.
4. **Colour picker and histogram**: point and area pickers on the current render in the spaces the modules with
   pickers need (as desktop's `dt_iop_color_picker` gives them: the module's input or output, mean, min, max), and
   the module actions that use a picked value as desktop's buttons do (white balance from an area, color
   calibration's illuminant from an area and its area mapping, exposure's spot, rgb levels, the curve modules'
   pick): list which are built, each compared with the value desktop's own code computes for the same area in a
   Linux test. Histogram of the current render as desktop's (bins, channels, scale).
5. **`coords_transform`** between original-image and processed-image coordinates through the modules that move
   pixels (lens correction, rotate and perspective, crop, orientation, framing), both directions, for lists of
   points; tested against `dt_dev_distort_transform` and its inverse.
6. **Blending**: `blend_get` / `blend_set` for an instance (mask mode flags as named values, opacity, blend mode
   and its colour space, parametric sliders per channel, mask refinement: feathering, blur, contrast, brightness,
   invert, combine), validated as desktop's interface constrains them; one history item per change, grouped like
   `param_set` until sealed.
7. **Drawn masks**: list all forms (id, type, name, geometry in normalised image coordinates, which instances use
   them), create and update circle, ellipse, gradient, path and brush (a brush stroke as a list of points with
   pressure-free default hardness and size, as desktop builds one from mouse input), delete, rename; groups;
   attach to and detach from an instance with combine mode, inversion and opacity; one form used by several
   instances. XMP written by the bridge with masks opens in darktable-cli with the same render (engine test), and
   an XMP with masks made by desktop (public test files) lists and renders the same through the bridge.
8. **Mask display**: a render variant that shows one instance's mask as desktop's "show mask" overlay does, for
   preview and region.
9. `FakeEngine` and the contract suite follow every call (the fake may keep masks as data without rendering them:
   say what it does). Engine tests on Linux, both ABIs build, the real-engine workflow green with the contract suite
   on the emulator; `engine-api.md` complete for everything built.

## Not in this package
Screens for pickers and masks; raster masks; retouch's and liquify's own shapes; photo duplicates (planned with
LightPhotoLibrary); a demosaic shortcut for the fit view.

## Limits and rules
No changes under `upstream/`; no patches without the reviewer's agreement (describe the need precisely). List every
`dtbridge.h` change in the report. The LPL contract is not touched. `CLAUDE.md`. Nothing personal in the repository.
SPDX line on new files. Do not weaken a test. Never push to `main`, no pull requests; no throwaway branches left on
the remote (run probes on your own branch or say which branch is to be deleted). Report shortfalls first, plainly.
Maven Central is flaky in CI: rerun before suspecting the change.

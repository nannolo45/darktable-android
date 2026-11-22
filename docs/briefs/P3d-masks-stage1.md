# Brief P3d (stage 1): masks and blending screens

Branch `p3d-masks` from `main` (`4c71dfb`). The engine side of masks exists already (package P1b part 2,
engine-api.md 6.5 to 6.8); this package builds the app's screens on it. Mockup approved by the owner, with the
changes below: `docs/design/mockups/P3d-Masks.html`.

Budget rule set by the owner: token use is a priority (read with grep and line ranges, no exploratory tours).

## Rules
- `CLAUDE.md` is binding. Public repository: nothing personal. Read the darktable manual pages for masks (drawn
  masks, mask manager, blending) before building; where the app differs, say so in the report.
- Never push to `main`, no pull requests; push after every deliverable.
- Do not edit `upstream/`. `engine/` and `engine-jni` only if a screen cannot work without a small addition (then
  minimal, tested in the bridge tests, listed first in the report). Do not touch `app/lpl`.
- Screen code: tokens and icons only. Two skins exist (`tokens.json` = darktable, `modern-tokens.json` on top =
  modern): every new colour or size is a token in `tokens.json` with an override in `modern-tokens.json` where the
  mockup's value differs; new icons go into `ui-icons`. The mask overlay colour is the existing `color.maskOverlay`.
- App builds, screenshot and emulator tests run only in CI. Add the branch to the triggers of `p2a-app.yml` and
  `p2b-real-engine.yml` (and `engine.yml` only if `engine/` changes).

## What the owner decided (differs from the mockup)
1. The mask controls do not sit inside the module. A module only shows that it has a mask: an indicator in its
   header (a small mask icon, visible whenever the module's mask mode is not `off`, also when the module is closed
   and in the `active` group and quick access panel). Tapping the indicator, or a row `mask…` at the end of an open
   module's controls, opens the masks tab with that module selected.
2. The masks tab (third bottom tab on the phone: `modules`, `masks`, `history · n`; on the tablet a third tab in
   the same place the history sits) holds everything: the module whose mask is edited (a row that opens a sheet of
   the photo's module instances, those with a mask marked); the five modes `off`, `uniform`, `drawn`, `parametric`,
   `drawn & parametric`, then `opacity` and `blend mode`, and for drawn: the line saying what the module uses, the add
   buttons `brush`, `circle`, `ellipse`, `gradient`, `path`, `use an existing shape…`, the module's shapes (combine
   mode `union` / `intersection` / `difference` / `exclusion`, invert, remove), `show mask`, `invert mask`; below it
   the manager: every group and shape of the photo, `shapes not in a group`, per shape a `more` sheet with `rename`,
   `use in another module…`, `duplicate`, `remove from this group`, `delete shape` (asks once), and `used by n
   modules`. Tapping a shape shows it on the photo.
3. Drawing mode as in the mockup: the panel is replaced by the tool panel; brush = finger strokes with `size`,
   `hardness`, `opacity`, `undo stroke`, `show mask`; circle and ellipse placed by a tap and moved by dragging, with
   `size`, `feather` (ellipse also `rotation`, `flatness`); gradient with `rotation`, `compression`; path by taps with
   `remove last point`, `close path`; `cancel` / `done`. Two fingers pan and zoom the photo while drawing (reuse the
   picker tool's separation of one and two fingers). Shape positions go to the engine in image coordinates through
   `coords_transform`; a finger stroke is passed as plain points, as the engine API describes.

## Stage 1 scope
Modes `off`, `uniform`, `drawn`; opacity and blend mode; all five drawn shapes; combine, invert, remove; `show mask`
(the engine's mask display); the indicator; the masks tab with the manager; undo/redo and history behaving as for
any other change (one history item per finished shape or setting, not per touch move). Not in stage 1: `parametric`
and `drawn & parametric` (both shown disabled with a line `parametric masks: coming next`), `mask refinement`,
editing an existing shape's geometry after `done` (allowed if cheap; otherwise say so), raster masks. If the engine
API lacks something stage 1 needs (for example moving members in a group), build the screen without it and report
it; do not grow the engine for it.

## Safety
Read-only photos (XMP with unknown or newer modules) show masks but allow no change. A photo's XMP written with masks
must reopen with the same masks (round trip), and must render the same through `darktable-cli` as the bridge renders
it (add one case to the existing compare step if that is a small change; otherwise say so).

## Tests
JVM tests of the masks state logic against the stand-in engine (extend the stand-in minimally); screenshot tests of
the masks tab, drawing mode and the header indicator in both skins, phone, and one tablet shot; on the emulator with
the real engine: add a circle to exposure, `show mask` renders, the picture differs from the unmasked one, the XMP
reopens with the mask; a brush stroke likewise. Do not weaken existing tests.

## Docs
This brief; a section on the masks tab and the indicator in `docs/design/ui-spec.md`;
`docs/experiments/P3d-masks-stage1-REPORT.md` in `CLAUDE.md`'s form and a short running log.

## CI
Stop when both app workflows are green on the last code commit including the emulator job, or after three failed fix
attempts on one problem: then report plainly what fails.

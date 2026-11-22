# PUI3a: third interface skin "L1", a second darkroom layout

Branch: `pui3a-l1-layout` from `main`. Agent: Opus (structural). One pull request (or REPORT file, see CLAUDE.md).

## Goal
A third value of the app setting `interface`: `l1`. Same engine, view models, controller, schema-generated controls, history, presets, styles, masks and LPL hand-off as today. **Only the darkroom's phone layout changes** (and its sideways phone layout). Everything a user can do in the `darktable` and `modern` skins must stay reachable in `l1`; nothing is renamed or removed. Labels stay lowercase as in darktable.

Visual finish: `modern`'s tokens (M1) plus a new `l1-tokens.json` layer for anything L1 adds. Rule 5 of CLAUDE.md holds: no literal colours or sizes in screen code.

## Layout, upright phone
1. **Photo** full width on black, top of the screen, as large as the panel leaves room for; fitted, never cropped.
2. **Top bar floats over the photo** (no fill, readable on light photos by a soft shade token): back · series pill "n / N" that opens and closes the existing filmstrip · undo · **compare** (the existing before/after) · export · more (the existing menu). While a slider is dragged it hides.
3. **Bottom: two rows**, from the bottom up:
   - **tools row**: `styles` · `modules` · `masks` · `more`. The chosen tool is filled with the accent.
   - **sections row** (only while `modules` is chosen): the groups of the active module layout, in their order, plus `quick` first (the quick access panel) and `active` last (enabled modules). A short mark under a group that holds an enabled, non-default module. Groups that do not fit: the row shrinks labels to icons + short label; **no sideways scrolling anywhere in L1.**
4. **Panel** between the photo and the rows, 95 % opaque panel colour, its height capped (token), scrolling vertically inside:
   - `quick`: the existing quick access panel, controls as a flat list (module name small and dim before each label).
   - a group: a **stacked list of module rows** (power, name, instance number, mask indicator when the instance has a mask, chevron). **One module open at a time**: tapping a row opens it under its row (picker / reset / presets / ⋯ icons, then its generated controls, then graph rows, then the existing `mask…` row). Tapping the open row closes it. Power works on closed rows. Crop and retouch are ordinary modules in whatever group the layout puts them; their on-photo tools open from the module as today.
   - `active`: the enabled modules, last applied on top, each row opens that module in its group.
   - `styles` tool: the existing styles screen content (StylesSheets) inside the panel, unchanged behaviour.
   - `masks` tool: the existing masks tab content inside the panel, **unchanged**. Do not edit `MasksPanel.kt` or `MaskTree.kt` (other branches work there); only host the composable.
   - `more` tool: snapshot (if available, else leave out), copy, paste, duplicate (if available), then the existing history list. Only actions the app already has; no new engine calls.
5. **Dragging a slider**: the panel, rows and top bar fade to transparent (about 250 ms out, 100 ms back; tokens), only the dragged slider stays visible, and a pill at the top shows `module · control · value`.
6. **Graph-type editors and on-photo tools** that today open full screen or as their own mode (crop frame, pickers, drawing a mask): keep their current behaviour; in L1 the two rows are hidden while they are open and come back on `done`. Graph editors themselves are a later package (P3e); do not build them.
7. ⋯ on a module: the existing instance menu (new, duplicate, move up/down, rename, delete, masks), styled as a bottom sheet.

## Layout, sideways phone
Photo on the left; then the panel (fixed width token); then the sections column; then the tools column at the right edge. Same rules otherwise. Tablet: **unchanged** (L1 uses the existing tablet layout in `modern` tokens) — say so in the report.

## Structure
- `Skin.L1("l1", "l1-tokens.json")` layered on top of `modern-tokens.json` (extend the loader if it only stacks one layer). Setting value `l1`, shown in the About screen's interface choice as "lightcraft-style" or similar neutral words; switching needs no restart.
- New files for the layout (for example `ui/l1/L1Darkroom.kt`, `ui/l1/L1Rows.kt`, `ui/l1/L1Panel.kt`); `DarkroomScreen` picks the layout by skin. Controls, module rows' content, history, styles and masks content are **reused**, not copied. Where a shared composable needs a small parameter to fit (for example "hide header"), add it with a default that keeps today's behaviour.
- No change to `DarkroomController`'s public behaviour, the engine, the bridge, `engine-api`, LPL or the schema.

## Tests and evidence
- JVM tests: skin key round trip; sections built from a layout (incl. `quick`, `active`, marks); one-open-at-a-time; power on a closed row calls the controller; active list order; rows hidden while an on-photo tool is open; drag state hides the chrome and shows the pill.
- Screenshot tests in `l1` for: rest, a group with one open module, quick, active, styles, masks, more, dragging, sideways. Existing screenshot tests for `darktable` and `modern` must still pass unchanged.
- Emulator run of the existing instrumented suite with `interface = l1` if the workflow allows a parameter; otherwise say so.
- Report in `docs/experiments/PUI3a-l1-layout-REPORT.md`, structured as CLAUDE.md asks, with the screenshots' paths.

## Not in this package
Graph editors (P3e), new engine calls, snapshot or duplicate if the app does not have them yet, tablet layout, any change to masks behaviour, styles amount.

## Privacy
Public repository: no personal names, device models, private links or personal module-layout names in code, tests, briefs or reports.

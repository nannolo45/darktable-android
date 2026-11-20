# Interface specification (darkroom)

What the owner agreed to in three rounds of interactive mockups. The mockup sources are in `docs/design/mockups/` (HTML with inline styles and a small logic class each; read them for exact sizes, colours and behaviour, they are not app code). Where this text and a mockup differ, this text wins.

Principle: **darktable's own look, adapted to touch.** Flat, grey, lowercase labels, no accent colour, no shadows or rounded cards. Every colour, size and icon must be changeable later without touching screen code (section 5).

## 1. Layouts

**Phone, portrait** (`B2-Phone`), top to bottom:
1. Top bar: back (to the working set), a title button showing file name and "series name · n of m" that shows or hides the filmstrip, "before" toggle, "done".
2. Photo on the mid-grey surround, inset 8 dp.
3. One info line: exposure time, aperture, focal length, ISO, camera.
4. Filmstrip of the series in the order given by the caller; hidden by default on phones.
5. Module group tabs: active (power icon), quick, then the configured groups.
6. Scrolling module list.
7. Bottom tabs: modules, history (with the current step count).

**Tablet, landscape** (`B2-Tablet`): top bar; left panel with history (always visible, with "compress history stack") and image information; centre with photo and filmstrip (shown by default); right panel with group tabs and module list. Header icons are always visible on tablets.

Layout switches on window size class, not on device type. Both share all logic.

## 2. Module list

- A module row is a header bar: power icon (on/off), name, instance label (for example "1") in a lighter tone. **Modules whose `can_switch` is false show no power icon** (demosaic, input and output colour profile, raw black/white point and others), as on desktop.
- Tapping the name expands the module; one module is expanded at a time.
- The expanded header shows three icon buttons on the right: instances, reset, presets. On phones they appear only on the expanded module.
- Instances menu (bottom sheet on phones, inline menu on tablets): new instance, duplicate instance, move up, move down, delete (disabled when it is the only instance), rename.
- The body is generated from the control schema (`schema/ui-schema.json`, see `docs/experiments/S5-control-schema.md`): sliders, lists, switches, sections, tabs, with the schema's visibility rules evaluated against current parameters. A control the schema cannot place is shown rather than hidden. Modules with no generated controls show a one-line note that their editor is not available yet; they still render and can be switched.
- Group "active" lists modules that are on at the current history position. Group "quick" lists selected single controls from several modules (configurable; the default comes from darktable's own scene-referred layout, see PLAN.md 3.9; it carries no white balance control, because the manual has colour temperature set in color calibration), labelled "module · control".
- Module groups are configurable data, not code. They follow darktable's layout presets (PLAN.md 3.9): groups, modules per group and quick access controls belong to a layout; layouts can be edited in the app and brought in from the desktop.

## 3. Controls

- **Slider** (darktable's "bauhaus" look): label left, value with unit right, a 6 dp track with a lighter fill from the left and a small triangle marker under the value position. Whole row is the touch target (48 dp high). Drag changes the value within the soft range; a long-press opens exact numeric entry within the hard range; double-tap on the label or on the bar (the track with its marker) resets to default, as a double click does on desktop (owner, 20 Nov 2026); double-tap on the rest of the row (the value) opens exact entry. While dragging, changes are sent continuously and the history item is sealed when the finger lifts.
- **List**: label left, current entry right; opens a sheet with the entries.
- **Switch**: label and a check-style toggle as in darktable.
- Units, digits, factor and offset come from the schema.

## 4. Behaviour

- Every change renders the preview first, then the visible region; a newer change cancels the render in flight.
- "before" shows the image at history position 0 while held or toggled.
- History: newest on top, "original" at the bottom, tap to go to a step, steps above the current one dimmed; editing from an earlier step discards the steps above it. Undo and redo are available from the top bar on tablets and by two-finger tap on the photo on phones (plus buttons in the history view).
- Photo view: pinch to zoom, drag to pan, double-tap toggles fit and 100 %.
- Read-only images (version guard failed) open with a banner explaining why; all editing controls are disabled.
- Later screens, already agreed in mockups and specified when their work packages start: masks and blending (`B3-Masks`), crop with draggable corners and movable frame (`B3-Crop`, built in P3a: section 6), tone equalizer graph (`B3-ToneEq`), snapshots with a split view (`B3-Snapshots`), export (`B3-Export`).

## 5. Theme tokens and icons

Tokens live in one data file and are the only source of colours and sizes for screen code. Initial values (darktable's default theme, as mocked):

| Token | Value | Used for |
|---|---|---|
| `color.bg` | `#262626` | window background |
| `color.panel` | `#303030` | expanded module body, sheets |
| `color.header` | `#3b3b3b` | module header bars, buttons |
| `color.border` | `#1b1b1b` | separators |
| `color.surround` | `#777777` | around the photo (must stay mid-grey) |
| `color.filmstrip` | `#5e5e5e` | filmstrip background |
| `color.selected` | `#5e5e5e` | selected tab, selected history step |
| `color.text` | `#b9b9b9` | normal text |
| `color.textStrong` | `#d4d4d4` | module names, titles |
| `color.textBright` | `#f1f1f1` | selected items |
| `color.textMuted` | `#9e9e9e` | info line, secondary labels |
| `color.textDisabled` | `#848484` | switched-off modules' icons, disabled items |
| `color.sliderTrack` | `#242424` | slider background |
| `color.sliderFill` | `#8a8a8a` | slider fill |
| `color.maskOverlay` | `#ffe000` | "show mask" overlay |
| `size.touch` | 44 dp | minimum touch target |
| `size.moduleHeader` | 44 dp | |
| `size.sliderRow` | 48 dp | |
| `size.sliderTrack` | 6 dp | |
| `radius.control` | 4 dp | buttons; everything else is square |
| `type.body` / `label` / `small` / `tiny` | 14 / 13 / 12 / 11 sp | system font (Roboto) |

Text on its background must keep a contrast of at least 4.5:1; check new palettes against that.

Icons are requested by meaning. Initial set: `power`, `instances`, `reset`, `presets`, `back`, `chevronDown`, `close`, `undo`, `redo`, `splitHandle`. One SVG per icon in one folder, stroke style, 24 dp grid.

Debug builds also read an optional token override file from the app's own folder, so a palette can be tried without rebuilding.

## 6. Crop on the photo (brief P3a)

- The tool opens when crop's panel is expanded on an editable photo, as desktop shows it when crop gets the focus; it closes (and the frame is applied) when the panel is closed, another module is opened, "done" or "back" is tapped, another photo is opened, or any other edit, history step, undo or redo is made.
- While it is open the photo shows the whole uncropped picture; outside the frame it is dimmed (`color.cropDim`); the frame (`color.cropFrame`, `size.cropFrameLine`) has a rule-of-thirds grid (`color.cropGrid`, `size.cropGridLine`) and handles on the four corners and the four sides (`color.cropHandle` on `color.cropHandleFill`, `size.cropHandle`, `size.cropHandleLine`). The info line shows the size of the cropped picture.
- A drag that starts within a touch target (`size.touch`) of a corner or a side, inside the frame or up to half a target outside it, resizes from there; a drag that starts elsewhere inside the frame moves it. Two fingers, or a drag that starts away from the frame, pinch and pan the photo. The ratio chosen in the aspect list is kept as desktop keeps it; the frame never leaves the picture.
- The margins' sliders and the aspect list show the frame live and move it. Read-only photos: no tool.

## 7. Colour pickers (brief P3c)

- A control that has a colour picker on desktop (the schema marks it) shows desktop's picker button: at the right of
  a slider or list, or as its own row ("from image area", "black", "gray", "white", "show color", ...). A picker the
  app has not built yet is greyed out. The button is shown on (`color.selected` behind it) while its picker is armed.
- Tapping it arms the picker: as desktop, the module is switched on and the picker picks at once with its last
  selection, or desktop's first one (a point in the middle; for pickers that take areas only, the area 2 %..98 %).
  Only one picker is armed; tapping its button again, opening another module, closing the module's panel or opening
  another photo disarms it.
- On the photo (`color.pickerFrame` over `color.pickerShadow`, `size.pickerLine`, `size.pickerHandle`,
  `size.pickerPoint`): a tap places a point (a picker that takes areas only gets a small area of one touch target
  there); a one-finger drag that starts away from the selection draws a new area; a drag that starts on the area
  moves it (inside) or resizes it (sides and corners, within a touch target); one on the point moves it. Two fingers
  pinch and pan. Each lifted finger picks again. The selection is kept in original-image coordinates, so it stays on
  the same part of the picture when the edit's geometry changes.
- The line under the photo shows the picked values as desktop's global colour picker does: RGB (0..255) and Lab, the
  mean, and for an area the min and max.
- The picker's settings that desktop keeps outside the edit (exposure's area mode and target lightness, color
  calibration's area mapping) are the app's, for the app's run; changing a target in "correction" mode picks again.

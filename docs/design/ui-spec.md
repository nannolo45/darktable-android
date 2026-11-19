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
- Group "active" lists modules that are on at the current history position. Group "quick" lists selected single controls from several modules (configurable; default exposure, the tone mapper's contrast, white balance temperature, vibrance), labelled "module · control".
- Module groups are configurable data, not code.

## 3. Controls

- **Slider** (darktable's "bauhaus" look): label left, value with unit right, a 6 dp track with a lighter fill from the left and a small triangle marker under the value position. Whole row is the touch target (48 dp high). Drag changes the value within the soft range; a long-press or double-tap opens exact numeric entry within the hard range; double-tap on the label resets to default. While dragging, changes are sent continuously and the history item is sealed when the finger lifts.
- **List**: label left, current entry right; opens a sheet with the entries.
- **Switch**: label and a check-style toggle as in darktable.
- Units, digits, factor and offset come from the schema.

## 4. Behaviour

- Every change renders the preview first, then the visible region; a newer change cancels the render in flight.
- "before" shows the image at history position 0 while held or toggled.
- History: newest on top, "original" at the bottom, tap to go to a step, steps above the current one dimmed; editing from an earlier step discards the steps above it. Undo and redo are available from the top bar on tablets and by two-finger tap on the photo on phones (plus buttons in the history view).
- Photo view: pinch to zoom, drag to pan, double-tap toggles fit and 100 %.
- Read-only images (version guard failed) open with a banner explaining why; all editing controls are disabled.
- Later screens, already agreed in mockups and specified when their work packages start: masks and blending (`B3-Masks`), crop with draggable corners and movable frame (`B3-Crop`), tone equalizer graph (`B3-ToneEq`), snapshots with a split view (`B3-Snapshots`), export (`B3-Export`).

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

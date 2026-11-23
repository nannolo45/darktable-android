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

## 8. Masks and blending (brief P3d, stage 1)

Mockup: `docs/design/mockups/P3d-Masks.html`, with the owner's changes: the mask controls are not inside the module.

- **Indicator.** A module whose mask mode is not `off` shows a small mask icon (`DtIcon.Mask`, `size.maskIndicator`)
  in its header, also when closed, in the `active` group and in the quick access panel. Tapping it opens the masks
  tab with that module chosen. An open module that has blending ends with a row `mask…` showing its mode; it opens
  the masks tab the same way.
- **Masks tab.** Phone: the third bottom tab (`modules`, `masks`, `history · n`). Tablet: a second tab beside
  `history` in the left panel. From top to bottom:
  - `module`: the instance whose mask is edited; it opens a sheet with the photo's instances that have blending
    (those with a mask are marked `· mask`).
  - the five modes `off`, `uniform`, `drawn`, `parametric`, `drawn & parametric`; the parametric ones are disabled
    with the line `parametric masks: coming next` (stage 2). Then `opacity` (one history item when the finger lifts)
    and `blend mode` (a sheet with desktop's modes for the module's blend colour space).
  - for `drawn`: the line naming the module's group and its number of shapes; the add buttons `brush`, `circle`,
    `ellipse`, `gradient`, `path` (`size.shapeButton`); `use an existing shape…`; then (stage 2a) the module's
    mask as a tree, as the manual's groups and operators: `group operator` (a sheet `combine with`:
    union (maximum), intersection (minimum), difference, exclusion, screen (smooth union), sum (additive union),
    product (smooth intersection): plain names first, desktop's names beside them), `invert group`, `group opacity`;
    then one row per member, top first (the bottom member of a group is its first, as desktop lists rows bottom-up):
    shape icon or the group's open/close chevron, the name, for a group its operator (tap: the operator sheet) and
    number of members; `move up`, `move down` (disabled at the ends; order matters for difference), `invert` (a
    group's own invert for a group), `more`. A nested group's members follow its row one step in (`space.l` per
    level) and show only while it is open; nested groups are closed until opened (a group just made opens). The
    selected row shows its `opacity` slider (a group: its own opacity). The `more` sheet of a tree row adds `wrap in
    group`, `group together…` (a sheet to choose the other members of the same group) and `remove from this group`
    for any group. This replaces stage 1's `combine shapes` row and flat shape rows. `show mask` (the engine's mask display: picture in grey, mask in
    yellow, until switched off, the tab is left or the mask is no longer drawn); `invert mask`.
  - the manager: every group of the photo (titled after the module whose mask it is), foldable, with its shapes;
    then `shapes not in a group`. A tap on a shape shows its outline on the photo (`color.maskLine` over
    `color.maskShadow`, `size.maskLine`). Its `more` sheet: `rename`, `use in another module…`, `duplicate`,
    `remove from this group` (rows under a module's group), `delete shape` (asks once), and how many modules use it.
- **Drawing mode.** An add button replaces the bottom panel (phone) or the left panel (tablet) with the tool panel:
  title `<module> · <shape>`, a hint, the shape's sliders, `cancel` and `done`.
  - `done` is one history item and one undo step for the shape, or for all strokes of a brush session (stage 2a:
    `dtb_mask_add` inside a history group); `cancel` leaves nothing.
  - brush: one finger paints strokes (each stroke one shape, as desktop's brush tool); `size`, `hardness`,
    `opacity`, `undo stroke`, `show mask` (strokes filled in `color.maskOverlay` or drawn as thin lines);
  - circle and ellipse: a tap places it, a drag moves it; `size`, `feather`, ellipse also `rotation`, `flatness`;
  - gradient: a tap places the line, a drag moves it; `rotation`, `compression`; the tinted side is below the line;
  - path: each lifted finger adds a point (a drag before lifting places it); `remove last point`, `close path`;
    `done` needs three points.
  - Two fingers pan and zoom the photo while drawing; a second finger cancels what the first one began. Double tap
    and the two-finger-tap undo are off while drawing. Android's back cancels the drawing.
  - Sizes are fractions of the picture's shorter side. On `done` the shape goes to the engine in original-image
    coordinates (coords_transform), joins the module's mask, and the tab shows it selected.
- A read-only photo shows its masks; every control that would change them is disabled.

### 8.1 Parametric masks (brief P3d stage 2b)
At the pinned engine a module's mask is a flexi tree (engine-api.md 6.9), so a channel button adds a parametric element to the module's group (`mask_add` type `parametric`); ranges, boost and the output view are the element's geometry (`mask_update`), its invert the member's (`mask_member_update`). The section shows for modes `parametric` (pressing it asks the engine for `masks: "parametric"`, which it answers as the flexi group) and `drawn` next to the shapes. The classic `blend_get` `parametric` channels are not used.
Mode `parametric` is enabled; `drawn & parametric` is enabled in stage 2c (8.3).
The section follows `show mask` and `invert mask` rules of the drawn mode: `show mask` works in both modes.
- `colour space` row (a sheet: module's default, Lab, display RGB, scene RGB). A choice is asked first (`change the colour space? the parametric elements are removed`: `change` / `cancel`), because the manual removes them.
- Channel buttons as the manual lists them: Lab `L a b C h`; display RGB `g R G B H S L`; scene RGB `g R G B Jz Cz hz`. A tap adds an element (the same channel may appear twice).
- Per channel row: name; the whole-range warning icon (`DtIcon.Warning`, shown while input and output ranges cover everything: the channel restricts nothing); `output range` toggle (the output slider is hidden by default; a set output range still applies hidden and says so); `invert` (the element's), remove (out of the group, form deleted). Input slider: `RangeSlider4` and a picker (`DtIcon.ColorPicker`, drag an area on the photo: the shared P3c picker tool, the range from the area's values as desktop's `_blendif_scale` and 0.01 feather). Channels with a boost factor (`boost_supported`) have `boost factor` (EV, 0..18).
- `RangeSlider4`: four markers, inner filled (full opacity), outer open (zero opacity), a diamond of `size.rangeMarker`, open ones outlined with `size.rangeMarkerLine`. Order fixed (a <= b <= c <= d), markers may touch, never cross; touching markers: the first movement's direction picks one. One history item when the finger lifts; a double tap gives the whole range.

### 8.2 Mask refinement (brief P3d stage 2b)
`refine the mask` (collapsed by default) for every mode but off and uniform: `details threshold` (-100..100 %, disabled with a note for non-RAW photos), `feathering guide` (output before blur, input before blur, output after blur, input after blur), `feathering radius` (0..250 px), `blurring radius` (0..100 px), `mask brightness` and `mask contrast` (-100..100 %), `reset refinement` (desktop's defaults: all 0, guide input after blur). Read-only photos show the values and change nothing.

### 8.3 Group choice, editing a shape, drawn & parametric (brief P3d stage 2c)
- **Where a new shape goes.** The tree has one chosen group (default: the module's own). Tapping a group row chooses it (its sub-line adds `new shapes go here`) and selects it for its opacity; tapping the chosen group again, or `use the module's group`, goes back. A line above the add buttons reads `new shapes go into: <group name>` (the module's name for its own group). The add buttons and `add parametric…` add into that group (`mask_add` with `group`), and the group opens so the new row shows. The choice is dropped when another module is chosen.
- **Edit after `done`.** Tapping a shape row (or `edit` in its `more` sheet) opens the drawing tool on it, `edit <shape>` in the title. Circle, ellipse, gradient: drag anywhere to move (the finger keeps its offset from the centre), the sliders are those of drawing; path: drag a point (a touch farther than 8 % of the picture from every point moves nothing); brush: drag to move all strokes, no sliders. `used by n modules` shows when more than one module uses the shape. `done` is one `mask_update` (one history item of the first module using it, one undo step); `cancel` writes nothing. Shapes keep their other settings (a path's border, a brush's density, a gradient's steepness): the update sends the whole geometry. Not editable by the tool: groups and parametric elements (a parametric row selects it and opens its controls).
- **Drawn & parametric.** The `drawn & parametric` button is enabled. The pinned engine has no mode of that name (engine-api.md 6.9), so it asks for `drawn` and the tree holds the parametric elements; the button shows as chosen while the tree has any. In the tree `add parametric…` opens the channel buttons of parametric mode and adds an element to the chosen group; the controls of 8.1 (`ChannelRow`, `RangeSlider4`) open under its row once chosen when it is in a nested group (`ParametricState.all` finds elements at any depth); elements of the module's own group keep their rows in the 8.1 section, as the engine reads a mask with parametric elements as `flexi` whatever mode was asked, so the tab cannot tell parametric mode from the tree. `show mask` works as in the other modes.

## 10. Graph editors: bands (brief P3e-b)

One editor (`ui/graph/BandEditor.kt`, model in `BandModel.kt`) serves contrast equalizer, color zones and color equalizer. It stands in for desktop's graph and for the module's notebook; nothing else of the schema's generated controls moves (the sliders, color zones' process mode and mix, color equalizer's "node placement" and options stay where the schema puts them).

- **Layout, top to bottom**: the tabs of the module's curves (contrast equalizer: luma, chroma, edges; color zones: lightness, chroma, hue; color equalizer: hue, saturation, brightness; the page is the controller's, as the colour pickers read it), the graph at full panel width and `size.bandHeight` high, the strip under it, the axis words, the brush radius slider, and for color zones three small rows (edit by area, select by, interpolation method).
- **The graph**: one value per node, y up. The grid, the neutral line, the curve (drawn through the nodes with the module's interpolation; the picture is the engine's), the nodes (radius `size.bandNode`, a finger takes the nearest node within `size.bandNodeHit`). The finger on a node takes it and the soft brush is centred on it; a finger elsewhere pulls the curve towards the finger (desktop's `get_params` of the contrast equalizer: every node's y is drawn towards the finger's y with a Gaussian of the brush radius round the touch's x, the pick offset keeps the curve from jumping). The brush radius is a fraction of the axis (slider "brush radius"; the circle shows while a finger is down).
- **Nodes and axes by module**: contrast equalizer: six nodes per curve, y 0..1 (0.5 = no change; the denoise splines of luma and chroma are the second curve of those tabs and share the x positions); the inner nodes' x positions are moved in the strip under the graph. Color zones: up to 20 free nodes; x is the axis chosen by "select by" (lightness and chroma: a grey ramp, hue: the hue colours), nodes are dragged in x and y (stay in order, 0.0025 apart; the hue axis wraps with the second splines version), a drag on an empty place adds a node, a long press on a node takes it away; "edit by area" on switches to the brush. Color equalizer: eight nodes at fixed hue positions (shifted by "node placement"), y spans the parameter's range with the neutral value in the middle; the strip under the graph takes its colours from the schema's saturation sliders (the colour each ends in).
- **Gestures**: a drag is one history item (the engine merges the writes, the history is sealed when the finger lifts); a double tap resets the shown tab's curves (one item); a tap alone changes nothing. A vertical drag that starts on the graph edits the curve and does not scroll the panel; the rest of the panel scrolls as before.
- **Rule 5 for the hue strip**: the strip's colours are data (the colour of an axis position), not theme colours, as the slider gradients are: `ui-theme/HueBand.kt` turns them into a brush, screen code never builds a colour. The lines, nodes, circle and marks are drawn by `ui-theme/BandDraw.kt` from the `color.band*` / `size.band*` / `alpha.band*` tokens (the `l1` skin has a lower graph, `size.bandHeight`).
- **Colour picker** (color zones): the picked colour is marked on the axis (a line, and the range of an area), from the module's input in LCh as the engine gives it (engine-api.md 6.3: `colorzones` is picked in `lch`), at the positions desktop uses (`colorzones.c _draw_color_picker`).

## 11. Graph editors: curves (brief P3e)

One component family (`ui/graph/CurveEditor.kt`; logic in `CurveModel.kt` and `ToneEqMath.kt`) draws the `graph` control of tone curve, rgb curve, rgb levels and the tone equalizer's `advanced` tab. It sits where desktop's graph sits in the generated controls, so the three skins and the l1 layout (which hosts module controls unchanged) show it without further work. Colours and sizes are tokens (`color.curve*`, `size.curve*`); the histogram is the engine's (6.4), drawn in desktop's logarithmic height.

- **Size.** The curve graphs are square, as wide as the panel, at most `size.curveMaxHeight`; in l1 also at most the panel's height cap minus two touch rows. Rgb levels is a strip (histogram and a handle row of touch height); the tone equalizer is 2.1 : 1 with a label row under it.
- **Curve graph (tone curve, rgb curve).** Nodes are diamonds with a 44 px touch target (`size.touch`). A drag on a node moves it, between its neighbours (`MIN_GAP` 0.001) and inside the graph. A tap on empty space adds a node there (at most 20; not within 0.025 in x of a neighbour, as desktop's `_add_node` guard) and the finger keeps moving it. A long press on a node, or dragging it off the graph, removes it; the two end nodes cannot be removed (desktop puts them back at 0 and 1). A double tap anywhere puts the channel's nodes and interpolation method back to darktable's defaults. Every finger that lifts is one history item (`curveDrag` steps merge, `curveEnd` seals). The curve drawn is the interpolation of the chosen method (cubic, centripetal, monotonic), approximating the engine's.
- **Channels.** The channel tabs the schema already shows choose the channel. Tone curve: a and b are edited only with independent channels (desktop); with linked channels the graph is dimmed with a note, and a double tap switches to independent channels (desktop does the same). Rgb curve: tabs exist only with independent channels; the graph shows the first channel otherwise.
- **Rgb levels.** Three handles in the strip under the histogram: black, grey, white. A press chooses the nearest handle; the drag is relative to where it began. Black and white keep the grey handle at its relative place and handles stay 0.05 apart (rgblevels.c `_rgblevels_move_handle`). Per channel with independent channels. Double tap resets the channel. The three pickers stay.
- **Tone equalizer.** Nine band points (-8 to 0 EV, +2 EV at the top, -2 at the bottom as B3-ToneEq) joined by the curve the module applies (the eight-gaussian fit with `smoothing`, toneequal.c). A vertical drag at a band's column raises or lowers the curve around that exposure exactly as desktop's drag and mouse wheel do (`set_new_params_interactive`, spread `smoothing^2 / 2`), so neighbouring bands follow; the nine values written are read back from the fitted curve. Double tap resets the nine values.
- **Drag on the photo.** With the tone equalizer's panel open (not zoomed in, no picker armed, no shape drawn, no crop tool), a vertical one-finger drag on the photo reads the exposure at the point where it began (the engine's picker on the module's input, `dtb_color_pick`, with the module's own luminance estimator) and moves the bands around that exposure; the photo's height stands for 4 EV, as the graph's. One history item per drag; the module is switched on by the first step.

## 12. Versions (brief P3f)
Design and mockup: `docs/design/P3f-versions.md`, `docs/design/mockups/P3f-Versions.html`. A photo has version 0 (`NAME.EXT.xmp`) and versions n (`NAME_nn.EXT.xmp`).
- **Chip.** Darktable and modern skins (and the tablet layout) show a `versions` chip (icon `versions` and `v2`) in the top bar; the l1 phone skin has none: its `more` menu has `duplicate…`, which opens the same sheet. Shown only where the app supports versions.
- **Sheet** "Versions of NAME": a row per version: thumbnail (the version's last picture seen in this run, else its number), `vN · name · cover`, `edited` or `no edits`, a sync dot (synced, waiting to sync, on this phone only) and a menu (rename…, set as cover when LPL knows versions, delete… never for 0). Buttons `Duplicate` (same history as the version in use) and `Virgin duplicate` (no history); both keep rating, labels, tags and open the new version. Tap a row: the version in use is saved, the other opens, the sheet closes. Hold a row: its last picture is shown over the photo while held.
- **Rules.** A version whose XMP fails the version guard is never duplicated or renamed. Delete asks first; LPL keeps its copy. Tokens: `size.versionThumbWidth/Height`, `size.versionDot`, `color.syncDone`, `color.syncWaiting`.

## 13. Export (brief P3g)

Mockup `B3-Export`; darktable's export module (manual: utility modules › shared › export) is the reference for the
options and their defaults. Opened from the darkroom's menu ("export…", every skin) and, in l1, the top bar's
export button. A screen over the darkroom: back, title "export", the photo's name and the series line.

- **storage options:** target storage (this device: `Pictures/darktable` through MediaStore; share to an app:
  the Android share sheet once the export is done), images (this image / whole series (N), only with a series), a
  note on the target. LightPhotoLibrary is not offered: the app has no R5 upload call yet (P3g report).
- **format options:** file format (JPEG, PNG, TIFF, WebP: what the engine takes; WebP only where the engine was
  built with it, otherwise the export fails with the engine's message), bit depth where the format has more than
  one (PNG 8/16, TIFF 8/16/32), quality for JPEG and WebP (brief P3i: a slider 5..100 as desktop's, default 95, one value per lifted finger, double tap on the bar resets to 95).
- **global options:** set size (original size = max size 0 × 0, long edge 2048 px, long edge 3840 px), high quality
  resampling, profile (image settings, sRGB, Adobe RGB, Display P3). Upscaling is always off.
- Defaults are darktable's: JPEG, quality 95, 8 bit, no size limit, high quality resampling no, profile image
  settings (`darktableconfig.xml.in`: `high_quality_processing` false, `icctype` −1).
- **While exporting** the options are greyed and the screen shows `BatchProgress` (P2k): one photo: "exporting
  <name>", the bar, the step in plain words with the percent ("preparing", "processing · module 7 of 23",
  "writing the file", "saving to this device" / "getting it ready to share"; "sending to LightPhotoLibrary" is
  reserved for R5) and "cancel". A series: "photo n of N" with the overall bar and "k finished · p %", and under
  it the current photo's own bar and step. The share of one photo: the engine's 0…95 % (its steps, engine-api.md
  section 7), the hand-off the last 5 %.
- **After:** "exported to Pictures/darktable on this device" (or the share sheet opens), "cancelled · nothing
  written" / "cancelled · k of N exported", or the failure with the engine's message; the button reads "export
  again". Closing the screen forgets an ended export.
- **Top bar indicator:** while an export runs, or after it ended until the export screen is opened again, the
  darkroom's top bar (phone, tablet, l1) shows a small text ("export 42 %", "export 5/12 · 38 %", "exported") over
  a thin bar; tapping it opens the export screen.
- **Brief P3i, LightPhotoLibrary:** the destination "LightPhotoLibrary" sends the file to the server the photo came from
  (R18, PLAN.md 4.3), with the photo's id and version. It is on offer only when the photo came from an LPL server whose
  last heartbeat answer has `server_caps` with `exports`; otherwise the entry is greyed and a one-line note under the
  target says why (not from LPL, server not answered yet, server too old). The step reads "sending to LightPhotoLibrary"
  with the bytes sent as the share of the bar. A server that does not answer keeps the export in a queue on the phone
  (survives a restart); it is sent with the next sync and the end line says "waiting for LightPhotoLibrary".
- **Brief P3i, series in the background:** a series no longer opens each photo in the darkroom. The darkroom's photo is
  saved and closed, every photo is opened, exported and closed on the engine (one session at a time: the bridge allows
  one), and the darkroom's photo is opened again at the end. Meanwhile the darkroom's own actions wait. A foreground
  service (type dataSync) keeps the process alive with the app in the background or the screen off; its notification
  reads "Exporting photo n of N" with a progress bar and a Cancel action; the end stays as a notification.
- The export runs in the darkroom's scope: it goes on when the export screen is closed and while another screen of
  the app is shown. A series opens each photo in the darkroom and comes back to the first one; when the darkroom is
  left during a series, it stops after the photo under way ("stopped: the darkroom was left"). In the background
  (app not visible) nothing stops it while Android keeps the process, but there is no foreground service yet
  (later package): Android may stop the process and with it the export.

## 14. Snapshots and the split view (brief P3h)

Mockup `B3-Snapshots`; darktable's snapshots module (manual: utility modules › darkroom › snapshots;
`src/libs/snapshots.c`) is the reference.

- **Where:** a "snapshots" section above the history, as desktop's left panel has it: the phone's history tab
  (darktable and modern skins), the tablet's left panel, and l1's `more` tool (which shows the same history view).
- **take snapshot:** keeps the current history end; the open history item is closed first, so that a further
  change of the same control becomes a new item and the snapshot keeps its picture. Named as on desktop by the
  history item at that end with its instance name ("original" at 0), after the position number ("4 · exposure").
  At most 10, as desktop's `MAX_SNAPSHOT` (the button is off when all are used). Memory only, for the open photo:
  another photo starts with none (desktop keeps them for the session, also across images: not done here).
  **Difference from desktop:** desktop stores a copy of the history with each snapshot
  (`dt_history_snapshot_create`), so a snapshot survives any later change; here a snapshot is a history position,
  rendered by the engine's `history_end` option, and it goes when the history below it changes (see "editing").
- **list:** one 44 px row per snapshot; a tap shows it in the split view, a second tap on the shown one ends the
  split (desktop); the close icon on the right deletes it (desktop has one reset for all).
- **split view:** the snapshot on one side of a divider, the live edit on the other, both at the same zoom and pan;
  the divider is a light line over a dark edge (`color.splitLine`, `color.splitShadow`, `size.splitLine`) with a
  round handle (`size.splitHandle`); a finger within half a touch target (`size.touch`, 44 px) of the line drags
  it (4 % to 96 % of the view). Labels in the corners: "snapshot · <name>" and "current". Under the list while a
  snapshot is shown: "divider: vertical / horizontal" (desktop's rotate icon on the line) and "switch sides"
  (desktop: the side-by-side mode's rotation; the overlay's sides are switched here).
- **editing:** changes go to the live side; the snapshot's picture is rendered once (preview, and the sharp region
  when zoomed in) as a history position (engine-api.md 8) and does not change. A change made after a tap on an
  older history item discards the items above it, and with them the snapshots above it; a compress removes all
  snapshots (their positions mean something else); undo and redo keep a snapshot while the items up to its position
  are the same.
- **l1:** the compare button opens the split with the newest snapshot (and closes it again); without snapshots it
  is "before" as until now.
- **before:** rendered with the same engine option at position 0; no history_goto.

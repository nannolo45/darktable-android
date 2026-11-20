# Control schema overrides

Small hand corrections to `../ui-schema.json` (PLAN.md 3.3), one JSON file per module, named after the
operation (`flip.json`). The app's `schema` module packs every `*.json` here into the app at build time
together with `ui-schema.json` and applies them when it loads the schema
(`app/schema/src/main/kotlin/app/dtandroid/schema/Overrides.kt`). Each file says why it exists in `reason`.

Every operation names the schema node it changes and states what it expects to find there
(`expect_action_paths`, `expect_label`). When a new darktable version changes the panel, loading fails
with a message instead of silently changing the wrong control; the app's unit tests load every file.

## Operations

`replace_with_buttons`: replace a node (usually a row of icon buttons whose desktop callbacks are code)
by a row of icon buttons. Each button sets one enum parameter by a table from the current constant to the
new one. `icon` is a meaning from the icon registry (`app/ui-icons`).

```json
{ "op": "flip", "reason": "...",
  "replace_with_buttons": [ { "node": 0, "expect_action_paths": ["rotate 90 degrees CCW", "..."],
    "label": "transform",
    "buttons": [ { "action_path": "rotate 90 degrees CCW", "icon": "rotateLeft", "label": "...",
                   "param": "orientation", "map": { "ORIENTATION_NONE": "ORIENTATION_ROTATE_CCW_90_DEG", "...": "..." } } ] } ] }
```

`bind_action`: an unbound slider is read through `module_action(get)` and written through
`module_action(set)` with `fields` as arguments (the other fields keep their current value).

```json
{ "op": "temperature", "reason": "...",
  "bind_action": [ { "node": 12, "expect_label": "temperature", "get": "get_wb", "set": "set_wb",
                     "field": "temperature", "fields": ["temperature", "tint"] } ] }
```

## Files

| File | What it does |
|---|---|
| `flip.json` | orientation's four buttons (rotate left, rotate right, flip horizontally, flip vertically), with the tables of `do_rotate`, `_flip_h` and `_flip_v` in `src/iop/flip.c`. `ORIENTATION_NULL` ("use the image's own orientation") is treated as no rotation, because the schema does not know the image's orientation; with a real engine the first tap on an image with a rotated Exif orientation can therefore start from the wrong orientation. |
| `temperature.json` | white balance temperature and tint through `get_wb` / `set_wb`, so the quick panel's default temperature control works. Not asked for by brief P2a (which expected only the orientation override); see its report. |

## Brief P2j: controls that desktop keeps outside the parameters

Each entry names `node` and `expect_label` (the schema's label, empty for an unlabelled control). The app repeats
desktop's callback on the parameters (`app/schema`: `Computed.kt`, `Formulas.kt`, `SpektrafilmStocks.kt`,
`P2jOverrides.kt`); `tools/schema-unbound-controls.py` lists what is still open.

| Operation | What it does |
|---|---|
| `computed` | a slider shown from parameters and written by a named formula (`formula`, `role`, `params`, `partners`): `illuminant_hue_chroma` (color calibration), `protect_center_width` (saturation curve), `sqrt2_log` (tone equalizer) |
| `value_list` | a fixed list; each entry's `set` is written when chosen; shown is the first entry whose `read` parameters match (`tolerance` for numbers), else `unmatched`; `section` entries are headings; `unwritable` entries are greyed out with the reason |
| `stock_list` | spektrafilm's film, paper and spectral upsampling lists from the data pack (`tools/spektrafilm-stock-table.py` writes and checks the table) |
| `toggle_writes` | a switch that writes `set` when switched on, shown on when `on_when` holds |
| `bind_param` | a control the extractor saw without a parameter but whose callback writes one parameter directly |
| `note` | one plain line under a control that waits for something (`class` b, c or d of the P2j report) |
| `hide` | a control that changes nothing on a phone (desktop's configuration only); `reason` says why |
| `visibility` | replaces the schema's visibility rule by desktop's own condition (`when_all` or `table`) |

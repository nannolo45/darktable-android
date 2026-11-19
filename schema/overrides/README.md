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

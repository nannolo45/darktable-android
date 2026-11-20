# Engine API v1

The contract between the app and the darktable engine. It exists twice with the same meaning:

- **C:** `engine/include/dtbridge.h` (`dtb_*` functions), implemented in `engine/`.
- **Kotlin:** `DarktableEngine` in `app/engine-api`, with two implementations: `FakeEngine` (pure Kotlin, for interface work and tests) and, later, the JNI one over the C bridge.

Structured data crosses the boundary as JSON with the shapes below. Pixels cross as raw RGBA8 buffers. This file is the source of truth: when C and Kotlin disagree with it, they are wrong. Changes are proposed to the reviewer, not made unilaterally. The API is frozen for Phase 2 at gate G1.

Findings this design rests on: `docs/experiments/S3-bridge-prototype.md`, `S1-android-build.md`, `S5-control-schema.md`, `G0-test-app.md`.

## 1. Model

- One process, one engine, at most one open image (a *session*).
- One engine thread owns the session. Every call except `render_cancel` runs on it; the Kotlin implementation enforces this with a single-thread dispatcher, and all Kotlin functions are `suspend`.
- A module instance is addressed by `op` (darktable's operation name, for example `exposure`) and `priority` (darktable's `multi_priority`, 0 for the first instance). Priorities can change after `instance_delete`; callers re-read `modules`.
- Errors: C functions return 0 or a negative `DTB_E_*`; `dtb_last_error()` has the text. Kotlin throws `EngineException(code, message)`.
- Coordinates for rendering are pixels of the processed image at a given `scale` (1.0 = full size after crop and distortion).

Error codes: `STATE -1`, `ARG -2`, `NOT_FOUND -3`, `TYPE -4`, `RANGE -5`, `IO -6`, `READ_ONLY -7`, `CANCELLED -8`, `ENGINE -9`.

## 2. Core (work package P1a; everything Phase 2 needs)

### 2.1 Lifecycle
| Function | Meaning |
|---|---|
| `init(paths, threads)` | Start the engine without its desktop interface. `paths`: configdir, cachedir, datadir, moduledir, tmpdir, library (`:memory:` in the app). `threads` 0 = engine default. The engine never writes an XMP on its own. |
| `shutdown()` | |
| `engine_info()` → JSON | `{ "darktable_version", "api_version": 1, "threads", "plugins": {"files","loaded","missing":[...]} }` |
| `set_threads(n)` / `set_thread_cpus(cpu_ids[])` | Limit the thread count (never above the count at `init`) and pin the engine's worker threads to the given CPUs. On phones the app passes the fast cores only: on the G0 phone 4 fast-core threads were up to six times faster than all 8 for interactive steps. The JNI layer finds the fast cores; the bridge only applies the list. |

### 2.2 Version guard
`xmp_check(xmp_path)` → JSON `{ "ok", "xmp_version", "entries", "problems": [ { "num", "operation", "modversion", "engine_version" | null, "reason": "unknown_operation" | "newer_module_version" | "newer_blend_version" | "newer_masks_version" } ] }`. Never loads the image.

### 2.3 Session
| Function | Meaning |
|---|---|
| `open(raw_path, xmp_path?)` | Import and load. The session starts from exactly the edit of `xmp_path`, or, without it, of the sidecar `<raw>.xmp` if one exists; without either, from the default edit (as a first import shows it), every time, whatever an earlier session in the same process did. The guard runs first; if it fails the image opens **read-only**: every mutating call and `xmp_write` return `READ_ONLY`. The RAW is only ever read. |
| `close()` | Does not write anything. |
| `image_info()` → JSON | `{ "width", "height" (processed, scale 1), "raw_width", "raw_height", "image_class": "bayer" | "xtrans" | "nonraw" | "monochrome", "read_only", "exif": { "maker", "model", "lens", "iso", "aperture", "exposure", "focal_length", "datetime" }, "rating", "color_labels": [..] }`. `image_class` selects the control schema (S5). |
| `metadata_reload(xmp_path)` | Re-read rating, colour labels and tags from an XMP (after LightPhotoLibrary merged them) without touching the history. |

### 2.4 Modules and parameters
`modules()` → JSON array in pipeline order:
```
{ "op", "name", "priority", "instance_name", "iop_order",
  "enabled", "can_switch",        // false for always-on modules (no switch on desktop)
  "default_enabled", "hidden", "deprecated", "one_instance", "supports_blending",
  "version", "in_history",
  "params": { ... } }
```
`params` is the whole parameter structure through darktable's introspection: scalars as number, bool, or enum constant name; nested structs as objects; arrays as arrays (large arrays included). Non-finite numbers are `null`.

| Function | Meaning |
|---|---|
| `param_set(op, priority, path, value)` | `path` is a field name with optional steps: `exposure`, `curve[2].x`, `equalizer_y[0][3]`. Value type must match (`TYPE`), and be inside the declared range (`RANGE`). Switches the module on, as moving a control on desktop does. |
| `params_set(op, priority, {path: value, ...})` | Several fields as one history step. |
| `set_enabled(op, priority, enabled)` | `ARG` for modules with `can_switch` false. |
| `module_reset(op, priority)` | Back to the module's defaults for this image. |
| `module_action(op, priority, action, args JSON)` → JSON | Escape hatch for what a module's desktop panel computes itself. P1a implements the mechanism and these actions: `temperature`: `get_wb` → `{temperature, tint}`, `set_wb {temperature, tint}`, `list_presets`, `apply_preset {name}`; `lens`: `list_cameras`, `list_lenses {camera}`, `set_lens {camera, lens}`; `lut3d`: `set_file {path}`; `denoiseprofile`: `list_profiles` → `[ {index, name, auto, iso} ]` (`index`: position in desktop's profile list, 0 = the automatic entry), `set_profile {index}` or `{name}` (a name selects the first entry of that name; names can repeat, indices do not). Unknown action → `NOT_FOUND`. More actions are added per module in later work packages without changing this signature. |

History grouping: consecutive `param_set` calls on the same instance merge into one history item until `history_seal()`; any other mutating call seals first. The app seals when the finger lifts.

### 2.5 Instances
`instance_new`, `instance_duplicate` (→ new priority), `instance_delete`, `instance_move(direction ±1)`, `instance_rename(name)`. Behaviour follows desktop darktable as documented in `S3-bridge-prototype.md` section 6. `one_instance` modules refuse `new` and `duplicate`. `instance_move(+1)` moves the instance one step later in the pipeline (up in the module list, which shows the pipeline last-first, as on desktop); `-1` earlier. `iop_order` in `modules()` is an integer.

### 2.6 History
| Function | Meaning |
|---|---|
| `history()` → JSON | `{ "end", "items": [ { "num", "op", "priority", "name", "instance_name", "enabled", "unknown" } ] }`, `0 <= end <=` number of items. `unknown` is true for an item the engine could not load (in a read-only session: an XMP entry whose operation the engine does not know); it keeps its place and its operation name (also as `name`) and has no effect on the picture. |
| `history_goto(end)` | Works for every `end` from 0 to the number of items, unknown items included. State after the first `end` items; a following change discards the items above, as on desktop. It is navigation: allowed in a read-only session, and not an undo step (undo and redo cover edits only; this differs from desktop on purpose). |
| `history_compress()` | |
| `history_seal()` | |
| `undo()` / `redo()` / `undo_state()` → `{can_undo, can_redo}` | Step through whole-history states recorded before each sealed change (at least 50 steps). After `undo`, `modules()`, `history()` and renders equal the earlier state exactly. |

### 2.7 Rendering
All renders return RGBA8, alpha 255, in sRGB for now.

| Function | Meaning |
|---|---|
| `render_size(scale)` → `{w, h}` | |
| `render_preview(max_size)` → buffer, w, h | Whole image, from the downscaled input, for immediate feedback. |
| `render_region(x, y, w, h, scale)` → buffer | Exactly the requested region. **The bridge renders with an internal margin and crops it away**, because a bare region differs from the full render near its edges (S3, test T5): at least 32 px, wider (up to 256 px) when a switched-on module reaches further, and for raw images a window on the same colour filter phase as a full render (P1c). The differences that remain are listed with bounds in `engine/tests/t5_expected.txt`. Callers never stitch a view from several regions. |
| `render_cancel()` | From any thread. The running render returns `CANCELLED`; cancel takes effect between modules. |

The pipelines keep their caches between calls. `cache_drop()` exists for measurements.

### 2.8 Output
| Function | Meaning |
|---|---|
| `xmp_write(path)` | Refused when the session is read-only or when an existing file at `path` fails the guard. Written atomically (temporary file, then rename). |
| `export(path, options JSON)` | `{ "format": "jpeg" | "png" | "tiff" | "webp", "quality", "bit_depth", "max_width", "max_height", "upscale": false, "high_quality": true, "profile": "srgb" | "adobergb" | "displayp3" | "image" }` through darktable's own export path. Formats whose library is not built report `ARG` with a clear message. |

## 3. Extended (work package P1b; needed for Phase 3)

Specified in outline; P1b turns it into exact signatures and JSON for review before implementing.

- **Blending:** `blend_get(op, priority)` → JSON of all blend parameters by name; `blend_set(op, priority, {path: value})`. Mask mode flags (off, uniform, drawn, parametric, raster, combinations) as named values.
- **Masks:** `masks()` → all forms with id, type (circle, ellipse, gradient, path, brush, group), name and geometry in normalised image coordinates; `mask_create(type, geometry)`, `mask_update(id, geometry)`, `mask_delete(id)`; `mask_attach(op, priority, form_id, {combine, inverse, opacity})`, `mask_detach`; reuse of one form by several instances.
- **Mask display:** `render_*` variants that show the mask of one instance as an overlay, as desktop's "show mask".
- **Coordinates:** `coords_transform(direction, points)` between original-image and processed-image coordinates (through lens correction, rotation, crop), for on-image tools.
- **Presets and styles:** list, apply, store per module; styles: list, apply to the open photo, import from `.dtstyle` files, delete; presets: import from `.dtpreset` files. Both live in darktable's own database in the app's configuration folder, as on desktop (PLAN.md 3.9). *Exact calls: section 5 (P1b part 1).*
- **Module layouts:** list the layout presets (darktable's built-in ones and the user's), read and write one as the string darktable stores, mark one as current (PLAN.md 3.9).
- **Analysis:** histogram of the current render; colour picker (point and area) in the pipeline's working space, as the modules with pickers need.

## 4. Tests every implementation must pass

For the C bridge, on Linux in CI, against `darktable-cli` of the same build (see `engine/tests/`): render parity, parameter change, instances, history, guard, cancel, drawn mask, exports, plus new ones for each function above (arrays through `param_set`, `params_set` as one step, `can_switch`, `module_reset`, each `module_action`, undo/redo equality, region equals the crop of the full render **now required to pass** thanks to the internal margin for demosaic; a remaining difference from another module is reported per module, not hidden), thread limits, read-only refusal for every mutating call.

For `FakeEngine`: the same Kotlin test suite that later runs against the JNI implementation on the emulator, minus pixel parity.

## Clarifications after P2a (19 Nov 2026)
- **"before" view.** There is no call that renders another history position without moving the history end. The app shows "before" by `history_goto(0)`, `render_preview`, `history_goto(end)` under one lock, and keeps that picture for the session, because the state at position 0 does not change while a photo is open. A call that renders a history position or a snapshot without touching the session belongs to the extended part (snapshots, section 3).
- **`color_labels`** in `image_info()` is a list of darktable's label numbers (0 red, 1 yellow, 2 green, 3 blue, 4 purple).
- **`modules()` size.** Array parameters make the full list large. If re-reading it after every change is too slow through JNI, a call returning one module instance may be added; measure first.

## Clarifications after P2b (19 Nov 2026): what the real engine does
The contract tests were run against the real engine; where they disagreed, the engine is the reference and the stand-in engine was corrected.
- `set_threads(0)` restores the thread count chosen at init; a larger count than the device has is clamped. `set_thread_cpus([])` removes pinning. Neither is an error.
- `history_goto` with a position outside the list fails with ARG.
- `history_compress` keeps modules that end up switched off (as upstream documents) and drops the items above the end.
- `in_history` of a module is true for any item in the list, also above the end.
- Exif date and time are given as `YYYY-MM-DDTHH:MM:SS`.
- **Known gaps in the bridge, to fix in the next engine package:** in a read-only session with an unknown module, `history().end` can be larger than the number of items and `history_goto(end)` is refused (the app clamps); within one process, `open(raw, NULL)` returns the history the private library still holds for that file, not the default edit. *Fixed by P1c: see 2.3 (`open`), 2.6 (`unknown` items) and the noise profile `index` in 2.4.*


## 5. P1b part 1: exact calls (20 Nov 2026)

Brief `docs/briefs/P1b-engine-presets-styles.md`. Every call below exists in C (`dtbridge.h`), in Kotlin
(`DarktableEngine`, the JNI implementation and `FakeEngine`) and in the contract suite. Calls marked [M] change the
edit (refused in a read-only session with `READ_ONLY`, and recorded for undo as one step). Calls that change only
darktable's database of presets and styles (`data.db` in `configdir`, as on desktop) are not [M]: they need an
initialised engine, not an open image, unless said otherwise. Names are matched exactly (case included), as desktop's
database does; `name` in JSON is the stored name (presets in a submenu keep desktop's `|` separator, for example
`film|portra`).

### 5.1 Open: undecodable files (E1)
`dtb_open(raw, xmp)` loads the picture before it answers. A file darktable cannot decode is refused with **`DTB_E_IO`**
and the loader's reason in `dtb_last_error()`, for example `cannot decode <path>: no decoder for this file (load status
3: unsupported format)`, or `...: file is truncated or corrupt (load status 2)`; no session is left open and nothing
is written. `DTB_E_IO` also for a picture of 0 x 0 pixels. After such a refusal every render, `image_info` and every
session call answer `STATE` ("no image open"), as with no `open`. Every render additionally refuses an input buffer of
0 x 0 (`ENGINE`), so no pipeline ever starts on an empty input.

### 5.2 Presets (per module)
JSON of one preset (`dtb_presets_list`):
```
{ "name": s, "description": s,
  "builtin": bool,            // darktable's own (write-protected): cannot be renamed, deleted or overwritten
  "op_version": n,            // module version the preset was stored for
  "usable": bool,             // op_version == the engine's module version (desktop greys the others out)
  "enabled": bool,            // applying it switches the instance on (true) or off
  "autoapply": bool,          // applied automatically to new edits whose image matches "filter"
  "filter": null | {          // null: the preset has no image filter ("only show this preset for matching images" off)
      "maker": s, "model": s, "lens": s,   // SQL LIKE patterns as stored, "%" = any
      "iso": [min, max], "exposure": [min, max], "aperture": [min, max], "focal_length": [min, max],
      "format": [ "raw" | "ldr" | "hdr" | "matrix" | "monochrome" | "color", ... ]  // [] = any format
    },
  "fits": bool | null,        // the open image passes the filter (always true without filter); null: no image open.
                              // Desktop's presets menu shows only presets that fit; the app does the same.
  "active": bool | null       // the instance's parameters and blending equal the preset's (desktop's check mark);
                              // null: no image open
}
```

| C | JSON / meaning |
|---|---|
| `int dtb_presets_list(const char *op, int priority, char **json)` | `[ preset, ... ]` in desktop's menu order: built-in first, then by name (case-insensitive). Every stored preset of the module, all versions; `fits`/`active` against instance (op, priority) of the open image. Without an open image `priority` is ignored. Unknown `op`: `NOT_FOUND`. |
| `int dtb_preset_apply(const char *op, int priority, const char *name)` [M] | As desktop's `dt_gui_presets_apply_preset`: parameters (the module's defaults if the preset stores none), on/off, blending and the instance label (preset's `multi_name` or name, unless the label was hand edited, `darkroom/ui/auto_module_name_update`). **One history item.** `NOT_FOUND` if there is no preset of that name at the module's version; `ARG` for a preset of another version. |
| `int dtb_preset_store(const char *op, int priority, const char *name, const char *description, int overwrite)` | Store the instance's current parameters, on/off state and blending as a user preset at the module's version, no filter, no auto-apply (as desktop's "store new preset"). Needs an open image. An existing user preset of that name: `ARG` ("exists") unless `overwrite` != 0, then replaced (its filter and auto-apply settings kept, as desktop's "update preset"). A built-in name: `ARG`. Empty name or a name with `/`: `ARG`. |
| `int dtb_preset_rename(const char *op, const char *name, const char *new_name)` | User presets only (`ARG` for built-in); `ARG` if `new_name` exists; `NOT_FOUND` if `name` does not. All versions of that name are renamed together. |
| `int dtb_preset_delete(const char *op, const char *name)` | User presets only (`ARG` for built-in); all versions of that name. |
| `int dtb_preset_import(const char *path, int overwrite, char **json)` | Import one `.dtpreset` file (desktop's `dt_presets_import_from_file`). → `{ "op": s, "name": s, "op_version": n, "usable": bool }`. Refused with `ARG` and a reason, nothing stored: unknown module (`unknown_operation`), module version newer than the engine's (`newer_module_version`), blend version newer (`newer_blend_version`), a built-in name, or an existing user preset of that name unless `overwrite`. `IO`: unreadable or not a darktable preset file. An older module version is stored as it is (desktop does the same) and shows `usable: false`. |
| `int dtb_preset_export(const char *op, const char *name, const char *dir, char **json)` | Write `<dir>/<op>_<name>.dtpreset` as desktop's `dt_presets_save_to_file` (characters not allowed in file names replaced as desktop does). → `{ "path": s }`. Built-in presets can be exported. `IO` if it cannot be written. |

### 5.3 Styles
JSON of one style (`dtb_styles_list`):
```
{ "name": s, "description": s,
  "has_module_order": bool,      // the style carries a module order
  "items": [ { "num": n,          // item number in the style
               "op": s, "priority": n, "instance_name": s, "enabled": bool,
               "module_version": n, "blend_version": n,
               "problem": null | "unknown_operation" | "newer_module_version" | "newer_blend_version" } ],
  "usable": bool }               // no item has a problem
```

| C | JSON / meaning |
|---|---|
| `int dtb_styles_list(char **json)` | `[ style, ... ]` by name (desktop's order). |
| `int dtb_style_apply(const char *name, const char *mode)` [M] | Apply to the open image. `mode`: `"append"` (`DT_STYLE_HISTORY_APPEND`: the style's items are added on top of the history) or `"overwrite"` (`DT_STYLE_HISTORY_OVERWRITE`: the history is replaced by the default edit plus the style, as desktop's `dt_styles_apply_to_image` with overwrite), or NULL = desktop's preference `plugins/lighttable/style/applymode`. As on desktop, the items above the history end are discarded first and every style item becomes one history item; it is **one undo step**. A style that is not `usable` is refused with `ARG` and the problems in the message (`style 'x' cannot be applied: item 2 'foo' unknown_operation; ...`), and nothing is changed. `NOT_FOUND`: no such style. The render afterwards equals darktable-cli `--style <name>` (append) / `--style-overwrite` within the usual bound. |
| `int dtb_styles_import(const char *paths_json, int overwrite, char **json)` | Import `.dtstyle` files: `paths_json` is a JSON array of paths (one or several). → `{ "imported": [ { "path", "name", "usable" } ], "refused": [ { "path", "reason" } ] }`. Each file is independent. Refused (in `refused`, not stored): not readable or not a darktable style (`reason` `"io"` / `"not_a_style"`), a style of that name exists and `overwrite` is 0 (`"exists"`). A style with an unknown or newer module **is imported** (it may become usable with a later engine, and desktop imports it too) and listed with `usable: false`; `dtb_style_apply` refuses it. The call returns `DTB_OK` when the array could be read, even if files were refused; `ARG` for a bad array. |
| `int dtb_style_delete(const char *name)` | `NOT_FOUND` if there is none. |
| `int dtb_style_create(const char *name, const char *description, const char *items_json, int overwrite)` | Create a style from the open image's history, as desktop's "create a style from the current history" (`dt_styles_create_from_image`, module order included). `items_json`: NULL = every history item up to the history end, or a JSON array of history `num`s (as `dtb_history()` lists them) to include. Desktop's dialog keeps one entry per module instance (the last one); the bridge does the same with the chosen items. `ARG`: empty name, a `num` outside 0..end-1, the name exists and `overwrite` is 0. Needs an open image; allowed in a read-only session only if no chosen item is `unknown`. Does not change the edit. |

### 5.4 Module actions added (P2j section 6)
`dtb_module_action` keeps its signature; new actions and fields:
- `colorin: list_profiles` → `[ { "kind": "input" | "work", "type": s, "filename": s, "name": s, "current": bool } ]`:
  the input profiles desktop's input profile list shows for this image (embedded ICC, embedded matrix, standard
  matrix, enhanced matrix, vendor matrix, alternate matrix, as `colorin.c` `update_profile_list` builds them, then
  darktable's profiles with an input position) and the working profiles (darktable's profiles with a working
  position). `type` is the enum constant name of `dt_colorspaces_color_profile_type_t` and, with `filename` (empty
  for built-in profiles), is what `param_set`/`params_set` take for `type` / `filename` (input) and `type_work` /
  `filename_work` (working). `name` is desktop's label.
- `denoiseprofile: list_profiles`: every entry gains `"current": bool`, true for the entry the instance's parameters
  hold (as `gui_update` selects it: the automatic entry when the `a`/`b` coefficients are "auto", else the profile
  whose coefficients equal them). One entry at most is current.
- `temperature: get_wb` gains `"tuning": n | null` (the fine tuning of the matched camera preset, null when the
  mode is not a camera preset) and `"preset_index": n | null` (the position of that preset in `list_presets`).
- `borders: set_aspect {"aspect": number}` [M]: writes framing's aspect including the two values desktop's list
  writes outside the field's limits, `0` ("image") and `-1` ("constant border"); any other value must be inside the
  field's limits (1..3), else `RANGE`. One history item.
- `lens: use_latest_md` [M]: what desktop's "use latest algorithm" button writes (`md_version` = 2,
  `scale_md_v1` = 0, below the field's limit 0.9). One history item.
- `lens: list_lenses {camera}`: every entry gains `"min_aperture": number | null` and `"max_aperture": number | null`
  (Lensfun's f-numbers of the lens; the aperture list of `_lens_set` runs from the largest opening to the smallest).
  *Why module actions and not an unchecked `param_set` (P2j question 1):* the out-of-range values are fixed values one
  desktop callback writes, not a range; an action writes exactly those values and nothing else, while a flag on
  `param_set` would open every field to any value and remove the range check that protects the XMP.

### 5.5 Threads and CPUs (P2g section 5)
No signature changes. Behaviour:
- `dtb_set_thread_cpus(ids, n)`: the CPUs are checked against what the process may use **at the time of the call**
  (`sched_getaffinity` of the process's main thread, which the bridge never pins), not the start-up set. The set
  applied is `requested ∩ allowed`; `ARG` only when that is empty. `engine_info.thread_cpus` lists what was applied.
- `dtb_set_thread_cpus(NULL, 0)` restores the process's allowed set at the time of the call.
- `engine_info` gains `"cpus_allowed": [ cpu, ... ]` (the process's allowed set now) and `"cpus_configured": n`.
- Thread count when the process starts with few CPUs allowed: `dtb_init(paths, 0)` sizes the engine for the CPUs the
  device has (`sysconf(_SC_NPROCESSORS_CONF)`), not for the start-up affinity mask, so `threads_max` is the device's
  count and `set_threads` can later use all of them. Measured and described in the P1b report.

### 5.6 Per-module times of the last render (E2)
`int dtb_render_times(char **json)` →
```
{ "pipe": "full" | "preview" | null,   // the pipe of the last render (null: none yet in this session)
  "total_ms": number,                  // wall time of that render call
  "modules": [ { "op": s, "priority": n, "instance_name": s, "ms": number, "cpu_ms": number, "tiles": n } ] }
```
`modules` in pipeline order, only the modules that ran in that render (modules whose output came from the cache are
not listed); `ms` is wall time of the module's processing (all tiles), `cpu_ms` the process CPU time over the same
span. Blending is not included. Needs an open image. Kotlin: `renderTimes(): RenderTimes`; the app's timings page
reads it instead of darktable's log.

### 5.7 Kotlin
`DarktableEngine` gains, with the same meaning and errors: `presets(op, priority): List<Preset>`,
`presetApply(op, priority, name)`, `presetStore(op, priority, name, description, overwrite = false)`,
`presetRename(op, name, newName)`, `presetDelete(op, name)`, `presetImport(path, overwrite = false): PresetRef`,
`presetExport(op, name, dir): String`, `styles(): List<Style>`, `styleApply(name, mode: StyleMode? = null)`
(`StyleMode.APPEND`, `OVERWRITE`), `stylesImport(paths, overwrite = false): StyleImport`, `styleDelete(name)`,
`styleCreate(name, description, items: List<Int>? = null, overwrite = false)`, `renderTimes(): RenderTimes`.

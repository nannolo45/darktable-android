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
- **"before" view.** There is no call that renders another history position without moving the history end. The app shows "before" by `history_goto(0)`, `render_preview`, `history_goto(end)` under one lock, and keeps that picture for the session, because the state at position 0 does not change while a photo is open. A call that renders a history position or a snapshot without touching the session belongs to the extended part (snapshots, section 3). *P3h: that call is the render option `history_end` (section 8); "before" uses it.*
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
`dtb_open(raw, xmp)` loads the picture before it answers. A file without a picture darktable can decode (junk, an
empty file, a truncated or corrupt RAW, an unsupported format) is refused with **`DTB_E_IO`** and a message that
**starts with `cannot decode `** (contract: this prefix tells the undecodable case), followed by the path and the
loader's reason: `cannot decode <path>: <reason> (load status n)` with darktable's `dt_imageio_retval_t` (for example
3, "no loader could decode it (unsupported format, or a truncated or corrupt file)", or 7, "read error"), or
`cannot decode <path>: darktable does not recognise it as a picture (import refused)`. The other `IO` answers of
`open` keep their own wording and are not this case: `cannot read <path>` (the RAW or the given XMP is missing or not
a file) and `cannot load XMP <path>`. Kotlin: `EngineException.isUndecodableFile` is true exactly for the first
case. No session is left open and nothing is written. `DTB_E_IO` also for a picture of 0 x 0 pixels. After such a refusal every render, `image_info` and every
session call answer `STATE` ("no image open"), as with no `open`. Every render additionally refuses an input buffer of
0 x 0 (`ENGINE`), so no pipeline ever starts on an empty input.

### 5.2 Presets (per module)
JSON of one preset (`dtb_presets_list`):
```
{ "name": s, "description": s,
  "builtin": bool,            // darktable's own (write-protected): cannot be renamed, deleted or overwritten.
                              // Built-in names carry darktable's "_builtin_" prefix (desktop shows them translated
                              // without it); submenus are written with "|" as on desktop
  "op_version": n,            // module version the preset was stored for
  "usable": bool,             // op_version == the engine's module version (desktop greys the others out)
  "enabled": bool,            // applying it switches the instance on (true) or off
  "autoapply": bool,          // applied automatically to new edits whose image meets "match"
  "filter": bool,             // "only show this preset for matching images" (desktop's menu hides it otherwise)
  "match": {                  // the image conditions; the filter and auto-apply both use them
      "maker": s, "model": s, "lens": s,   // SQL LIKE patterns as stored, "%" = any
      "iso": [min, max|null], "exposure": [min, max|null], "aperture": [min, max|null],
      "focal_length": [min, max|null],     // null = no upper limit (darktable stores FLT_MAX)
      "format": [ "raw" | "ldr" | "hdr" | "matrix" | "monochrome" | "color", ... ]  // [] = any format
    },
  "matches": bool | null,     // the open image meets "match"; null: no image open
  "fits": bool | null,        // shown in desktop's menu for the open image: !filter || matches; null: no image open
  "active": bool | null       // the instance's parameters, blending and switch equal the preset's (desktop's
                              // check mark, dt_get_active_preset_name); null: no image open
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
| `int dtb_preset_export(const char *op, const char *name, const char *dir, char **json)` | Write `<dir>/<name>.dtpreset` as desktop's `dt_presets_save_to_file` (`/<>:"\|*?[]` replaced by `_`, as desktop does; a preset of the same name of another module would get the same file name, as on desktop). The newest stored version of the name. → `{ "path": s }`. Built-in presets can be exported. `NOT_FOUND`, `IO` (folder missing or not writable). |

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
| `int dtb_styles_list(char **json)` | `[ style, ... ]` by name. Includes darktable's shipped styles (share/darktable/styles: camera styles and examples, 534 at the pinned commit): `dtb_init` imports them from the data folder as desktop and darktable-cli do at start (`dt_import_default_styles`; existing names are left alone; about 0.3 s the first time, 13 ms after, workspace). |
| `int dtb_style_apply(const char *name, const char *mode)` [M] | Apply to the open image. `mode`: `"append"` (`DT_STYLE_HISTORY_APPEND`: the style's items are added on top of the history) or `"overwrite"` (`DT_STYLE_HISTORY_OVERWRITE`: the history is replaced by the default edit plus the style, as desktop's `dt_styles_apply_to_image` with overwrite), or NULL = desktop's preference `plugins/lighttable/style/applymode`. As on desktop, the items above the history end are discarded first and every style item becomes one history item; it is **one undo step**. A style that is not `usable` is refused with `ARG` and the problems in the message (`style 'x' cannot be applied: item 2 'foo' unknown_operation; ...`), and nothing is changed. `NOT_FOUND`: no such style. The render afterwards equals darktable-cli of the XMP the bridge writes exactly, and darktable-cli `--style <name>` (its append) within 2 levels (P1b report: the two upstream paths place a new instance at different pipe positions). darktable-cli's `--style-overwrite` is not desktop's overwrite (it keeps no default edit) and is not compared. |
| `int dtb_styles_import(const char *paths_json, int overwrite, char **json)` | Import `.dtstyle` files: `paths_json` is a JSON array of paths (one or several). → `{ "imported": [ { "path", "name", "usable" } ], "refused": [ { "path", "reason" } ] }`. Each file is independent. Refused (in `refused`, not stored): not readable or not a darktable style (`reason` `"io"` / `"not_a_style"`), a style of that name exists and `overwrite` is 0 (`"exists"`). A style with an unknown or newer module **is imported** (it may become usable with a later engine, and desktop imports it too) and listed with `usable: false`; `dtb_style_apply` refuses it. The call returns `DTB_OK` when the array could be read, even if files were refused; `ARG` for a bad array. |
| `int dtb_style_delete(const char *name)` | `NOT_FOUND` if there is none. |
| `int dtb_style_create(const char *name, const char *description, const char *items_json, int overwrite)` | Create a style from the open image's history, as desktop's "create a style from the current history" (`dt_styles_create_from_image`, module order included). `items_json`: NULL = every history item up to the history end, or a JSON array of history `num`s (as `dtb_history()` lists them) to include. Desktop's dialog keeps one entry per module instance (the last one); the bridge does the same with the chosen items. `ARG`: empty name, a `num` outside 0..end-1, no module item chosen, the name exists and `overwrite` is 0. Needs an open image; `READ_ONLY` when the session's history holds items the engine could not load (`unknown` in `history()`). Does not change the edit. |

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
- The process's allowed set is its cpuset (`/proc/self/cpuset` with `/dev/cpuset/<group>/cpus` on Android, or the
  cgroup v2 `cpuset.cpus.effective`) intersected with the main thread's mask; the main thread's mask alone when the
  cgroup files cannot be read.
- Thread count when the process starts with few CPUs allowed: `dtb_init` sets `KMP_AFFINITY=disabled` (unless the
  caller set it) before darktable starts OpenMP, so that LLVM's libomp (Android) counts the online CPUs, not the
  start-up affinity mask; effective only if the OpenMP runtime has not started yet in the process. GCC's libgomp
  (Linux) does not read the variable: there `threads_max` is the mask the process had when the library loaded
  (measured: 1 under `taskset -c 0`). Not verified on a phone started in the background (P1b report).

### 5.6 Per-module times of the last render (E2)
`int dtb_render_times(char **json)` →
```
{ "pipe": "full" | "preview" | null,   // the pipe of the last render (null: none yet in this session)
  "total_ms": number,                  // wall time of that render call
  "modules": [ { "op": s, "priority": n, "instance_name": s, "ms": number, "cpu_ms": number, "tiles": n } ] }
```
Renders counted: `render_preview` ("preview") and `render_region` ("full"); `export` and `image_info` are not.
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


## 6. P1b part 2: exact calls (20 Nov 2026)

Brief `docs/briefs/P1b2-engine-masks-pickers.md`. Same conventions as section 5: [M] = changes the edit (refused with
`READ_ONLY` in a read-only session, one undo step unless said otherwise). Every call exists in C (`dtbridge.h`), in
Kotlin (`DarktableEngine`, the JNI implementation and `FakeEngine`) and in the contract suite, once built; the state of
each is in the running log and the report (`docs/experiments/P1b2-engine-masks-pickers*.md`).

**Coordinates.** Unless a call says otherwise, positions on the picture are **normalised processed-image
coordinates**: `[0,0]` is the top-left corner and `[1,1]` the bottom-right corner of the picture `render_preview`
shows (the image after crop and every distortion, at any scale). *Original-image* coordinates are normalised to the
pipeline's input (the full decoded image darktable's modules start from, the space darktable's drawn forms are
stored in). `coords_transform` converts between the two.

### 6.1 Render options (deliverables 1, 8)

```
int dtb_render_size_ex(float scale, const char *options_json, int *width, int *height);
int dtb_render_preview_ex(int max_size, const char *options_json, uint8_t **rgba8_out, int *width, int *height);
int dtb_render_region_ex(int x, int y, int w, int h, float scale, const char *options_json, uint8_t *rgba8_out);
```
`options_json` (NULL or `{}` = the plain call, which stays as it is):
```
{ "bypass": [ { "op": s, "priority": n }, ... ],   // instances left out of this render only
  "show_mask": { "op": s, "priority": n } | null } // desktop's "show mask" overlay for this instance (6.8)
```
- **bypass**: the instances are processed as if switched off, **for this call only**: no history item, no undo
  state, no change to `modules()`, `history()`, `undo_state()` or the XMP `xmp_write` produces; the next call without
  the option renders the edit as before. An instance that is already off is accepted (no effect). `NOT_FOUND`: no such
  instance; `ARG`: a module without an on/off switch (`can_switch` false: the pipe cannot run without it). Sizes and
  regions are those of the picture without the instance: `render_size_ex(1, {"bypass":[crop]})` is crop's input size,
  and `render_region_ex` regions are pixels of that picture. The picture equals the one rendered with the instance
  switched off (engine test).
- The pipelines' caches are shared: a bypass render after a plain one re-runs only the modules from the bypassed one
  on, and the plain render after it reuses its own cache lines.
- Kotlin: overloads `renderSize(scale, options: RenderOptions)`, `renderPreview(maxSize, options)`,
  `renderRegion(x, y, w, h, scale, options)` next to the plain ones (wrappers that override the plain calls keep
  working); `RenderOptions(bypass: List<InstanceRef> = emptyList(), showMask: InstanceRef? = null)`,
  `RenderOptions.bypass(op, priority)`, `RenderOptions.NONE`, `InstanceRef(op, priority)`.
- The crop tool's uncropped picture is `render_size_ex(1, bypass crop)` + `render_preview_ex(…, bypass crop)`, and its
  sharp region when zoomed in `render_region_ex(…, bypass crop)` (P3a limits 1 to 3).

### 6.2 Undo and redo keep the XMP's hashes (deliverable 3)

No signature change. `dtb_undo`/`dtb_redo` restore the whole-history state **and** the image's history hash row
(`basic_hash`, `auto_hash`, `current_hash`, `main.history_hash`) as it was at that state. The change timestamp is
**not moved** by undo or redo and not cleared: desktop's undo (`libs/history.c` `_pop_undo`) writes the history but
does not call `dt_image_cache_set_change_timestamp` (only adding a history item does, `develop.c`
`dt_dev_add_history_item`), so the XMP keeps the time of the last real change. Contract: after an undo back to a
state, the XMP written equals the XMP written at that state byte for byte, except `darktable:change_timestamp`, which
is the time of the latest change (equal too when nothing was changed in between, for example a redo after an undo).
The `darktable|changed` tag (written to the XMP's tag lists) is also restored as it was at that state: desktop keeps
it once set, the bridge does not, so that an undo back to the opened state gives the opened XMP again (apart from
the timestamp). Engine test C18.

### 6.3 Colour pickers (deliverable 4)

```
int dtb_color_pick(const char *op, int priority, const char *request_json, char **json);
```
As desktop's module picker (`dt_iop_color_picker`, statistics by `common/color_picker.c`
`dt_color_picker_helper` on the module's input and output buffers, before blending, `pixelpipe_hb.c`
`_pixelpipe_picker`), on the **preview** pipe as desktop (the whole picture, from the downscaled input), or the full
pipe on request. `op` NULL = the picture the pipe puts out (desktop's global colour picker, before the 8-bit
conversion).
```
request: { "area": [x0, y0, x1, y1] } | { "point": [x, y] }  // normalised processed-image coordinates
         + "denoise": bool          // default false; desktop's pickers with DT_COLOR_PICKER_DENOISE set it
         + "pipe": "preview" | "full"   // default "preview"
         + "bypass": [ {op, priority} ] // as 6.1
result:  { "pipe": s,
           "input":  { "colorspace": s, "mean": [4 numbers], "min": [4], "max": [4], "box": [x0, y0, x1, y1] } | null,
           "output": { ... } | null }   // null: the area is outside that buffer (desktop: no sample)
```
`colorspace` is the space of the numbers: the module's picker space as desktop sets it (`dt_color_picker_new` /
`_with_cst` in the module's `gui_init`: `"none"` = the buffer's own space, else `"raw"`, `"rgb"`, `"lab"`, `"lch"`,
`"hsl"`, `"jzczhz"`); the bridge keeps that table per module. `box` is the sampled rectangle in pixels of that buffer
(desktop's `dt_color_picker_box`). The fourth value of each vector is what desktop's helper returns there. `ARG`: bad
area (empty, outside 0..1), a module that is switched off (desktop's pickers only pick on modules that are on).
The module is forced to run once for the pick (its piece hash changed for that render, as for a bypass), on the
preview pipe at scale 1 (desktop's preview pipe renders the whole downscaled picture at scale 1); with `"pipe":
"full"` the full pipe renders the area's bounding box with a 64 px margin at scale 1. The plain renders before and
after are unaffected. The box mapping and the statistics are desktop's own functions:
`dt_color_picker_backtransform_box` (the area to a sample in the pipe's input), `dt_color_picker_box` (to the
module's input and output buffers) and `dt_color_picker_helper`; they read the global develop object's preview pipe,
which the bridge points at its own pipe for the call. Tiles of a tiled module are merged (mean weighted by pixels,
min of mins, max of maxes). Kotlin: `colorPick(op: String?, priority: Int, request: PickRequest): PickResult`
(`PickRequest.area(x0, y0, x1, y1)`, `PickRequest.point(x, y)`, `PickStats`).

**Module actions that use a picked area** (`dtb_module_action`, desktop's picker buttons; each [M]). Arguments take
`area` (or `point`) as in `dtb_color_pick`; the module's own picker runs on the preview pipe (the module must be on),
then the module's own `color_picker_apply` arithmetic is applied as desktop does it. Each returns
`{ "picked": { "input": ..., "output": ... }, ... }` plus the fields named below. `ARG` when the area gives no
sample in the module's input.
| op | action | what desktop's button does | built |
|---|---|---|---|
| `temperature` | `wb_from_area {area}` | coefficients from the input mean (green normalised, each 0..8), mode "from image area" (`temperature.c` `color_picker_apply`); → `coefficients: [4]` | yes |
| `exposure` | `spot {area, mode: "correction" (default) \| "measure", lightness?}` | `exposure.c` `_auto_set_exposure`: "measure" returns the area's lightness after exposure (and stores it as the target in `darkroom/modules/exposure/lightness`, as desktop); "correction" sets exposure so the area's lightness becomes `lightness` (default: that stored target, 50 %), black kept under white; manual mode only (`ARG` in deflicker mode); → `input_lightness`, `lightness`, `exposure` | yes |
| `rgblevels` | `pick {which: "black" \| "grey" \| "white", area, channel?: 0..2}` | `rgblevels.c` `color_picker_apply` on the input mean's first value; a history item only when a level moved; → `levels: [3]`, `changed` | yes |
| `rgbcurve` | `set_nodes_from_area {area, channel?: 0..2, offset?: "flat" \| "upper" \| "lower"}` | `rgbcurve.c` "set values" picker: the channel's curve reset, nodes at min, mean, max (luminance in linked mode, middle grey compensation), offset as desktop's ctrl / shift; → `nodes: [[x, y]]` | yes |
| `channelmixerrgb` | `illuminant_from_area {area, use_mixing?}` | the CAT tab's "from image area": `channelmixerrgb.c` `_auto_set_illuminant`, "correction" mode with a neutral target: illuminant custom, x, y so the area becomes grey, temperature from x, y (`_check_if_close_to_daylight`'s CCT); `use_mixing` default desktop's remembered toggle (on); → `x`, `y`, `temperature`, `illuminant` | yes |
| `channelmixerrgb` | `spot {area, mode: "correction", target?: {lightness, chroma, hue}, use_mixing?}` | area mapping, "correction" mode, target default desktop's remembered one (50, 0, 0); **"measure" mode not built** (`ARG`): it needs `_get_corrected_illuminant_xy`, which reads the white balance through the interface | correction only |
| `tonecurve`, `rgbcurve` | the read-only marks desktop draws on the curve | `color_picker_apply` (drawing only) | not built: `dtb_color_pick` gives the same numbers |

### 6.4 Histogram (deliverable 4)

```
int dtb_histogram(const char *request_json, char **json);
```
`request: { "max_size": n (default 1024), "bypass": [...] }`. Desktop's histogram module (`libs/histogram.c`,
waveform and vectorscope not included): computed on the preview pipe's output, RGB, in the histogram profile
(the export profile here, as every render of the bridge), 256 bins per channel, values clipped to [0, 1].
```
{ "bins": 256, "channels": ["red", "green", "blue"], "counts": [[...256], [...], [...]], "max": n,
  "pixels": n, "width": n, "height": n }
```
Desktop draws it in linear or logarithmic height (`plugins/darkroom/histogram/histogram`: "linear" draws
`count / max`, "logarithmic" draws `log(1 + count) / log(1 + max)`): the app scales; the bridge returns counts.
Kotlin: `histogram(maxSize = 1024, options = RenderOptions.NONE): Histogram`.

### 6.5 Coordinates (deliverable 5)

```
int dtb_coords_transform(const char *request_json, char **json);
```
```
request: { "direction": "to_processed" | "to_original",
           "points": [ [x, y], ... ],          // normalised, see the section head
           "bypass": [ {op, priority} ] }      // optional, as 6.1 (for example the crop tool's picture without crop)
result:  { "points": [ [x, y], ... ],          // same order; null for a point a module cannot map
           "original": { "width", "height" },  // the pipeline's input size (pixels of the full decoded image)
           "processed": { "width", "height" } } // render_size(1) with the same bypass
```
Through every switched-on module that moves pixels (lens correction, rotate and perspective, crop, orientation,
framing, liquify, ...), as `dt_dev_distort_transform_plus` / `dt_dev_distort_backtransform_plus` (develop.c) over the
bridge's full pipe with `DT_DEV_TRANSFORM_DIR_ALL`. Points outside the picture are mapped too (they can lie outside
0..1). Kotlin: `coordsTransform(direction: CoordsDirection, points: List<Pair<Double, Double>>, options = RenderOptions.NONE): CoordsResult`.

### 6.6 Blending (deliverable 6)

```
int dtb_blend_get(const char *op, int priority, char **json);
int dtb_blend_set(const char *op, int priority, const char *values_json);   // [M]
```
`blend_get` →
```
{ "supported": bool,                       // the module supports blending (desktop shows the blending section)
  "masks": "off" | "uniform" | "drawn" | "parametric" | "drawn_parametric" | "raster" | "flexi",
                                           // mask_mode as desktop's mode buttons set it: off = DISABLED,
                                           // uniform = ENABLED, drawn = ENABLED|MASK, parametric = ENABLED|CONDITIONAL,
                                           // drawn_parametric = ENABLED|MASK|CONDITIONAL, raster = ENABLED|RASTER,
                                           // flexi = ENABLED|FLEXI (desktop's "drawn & parametric" since masks v7)
  "mask_mode_flags": [ "enabled", "drawn", "parametric", "raster", "flexi" ],  // the raw flags, as named values
  "colorspace": "default" | "raw" | "lab" | "rgb_display" | "rgb_scene",       // blend_cst; "default" = the module's
  "mode": s,                               // blend mode: "normal", "average", "difference", "lighten", ... (enum names
                                           // without DEVELOP_BLEND_, lower case); obsolete modes as stored
  "reverse": bool,                         // DEVELOP_BLEND_REVERSE
  "parameter": number,                     // blend fulcrum (EV), scene-referred modes
  "opacity": number,                       // 0..100
  "combine": { "invert": bool, "mode": "exclusive" | "inclusive" },   // mask_combine
  "feathering_radius": number, "feathering_guide": "input_before_blur" | "output_before_blur"
                                           | "input_after_blur" | "output_after_blur",
  "blur_radius": number, "contrast": number, "brightness": number, "details": number,
  "parametric": { "<channel>": { "active": bool, "invert": bool, "range": [a, b, c, d], "boost": number }, ... },
                                           // channels of the blend colour space as desktop names them:
                                           // lab: L_in, a_in, b_in, C_in, h_in and _out; rgb: g_in, R_in, G_in, B_in,
                                           // Jz_in, Cz_in, hz_in (scene) or H_in, S_in, l_in (display), and _out
  "raster": { "op": s, "priority": n, "invert": bool } | null,
  "mask_id": n | null,                     // the group of drawn forms (6.7), null = none
  "mask_lock": bool }
```
`blend_set(op, priority, {path: value, ...})`: the same names as paths (`opacity`, `masks`, `combine.invert`,
`parametric.L_in.range`, ...); all values checked first, nothing changes if one is refused: `TYPE`, `RANGE` (desktop's
slider limits: opacity 0..100, feathering 0..250, blur 0..100, contrast and brightness -1..1, details -1..1, the
parametric range points 0..1 or the channel's own range and in order a <= b <= c <= d), `ARG` (a module without
blending, a blend mode or colour space desktop does not offer for the module, a raster source that writes no raster
mask). As on desktop, changing `colorspace` resets the parametric channels to the defaults of the new space
(`dt_develop_blend_init_blendif`). History: one item per call, merged with following `blend_set` / `param_set` calls on
the same instance until `history_seal` (as `param_set`), so a slider drag is one item.
Kotlin: `blendGet(op, priority): Blend`, `blendSet(op, priority, values: Map<String, JsonElement>)`.

### 6.7 Drawn masks (deliverable 7)

At the pinned commit a module's mask is a **flexi mask** (upstream `dev-doc/masks_data_model.md`): a tree of groups.
A group is a `DT_MASKS_GROUP` form whose first point is a *marker* holding the group's settings (operator, inversion,
bypass, opacity, name); its other points are *members* (a shape or a nested group) with their own opacity, inversion
and "disabled". A group folds its members in order with its operator: `maximum` (default), `screen`, `minimum`,
`product`, `sum`, `difference`, `exclusion`. Classic masks of older edits are converted when an edit is loaded (by
darktable). So the "combine mode" of the brief is the operator of the group a form is in, set with
`dtb_mask_group_set`; inversion and opacity belong to the member. Forms are darktable's `dt_masks_form_t` in the
session (`dev->forms`), written to the XMP with the history (`masks_history`).

JSON of one form (`dtb_masks`):
```
{ "id": n, "type": "circle" | "ellipse" | "gradient" | "path" | "brush" | "group" | "parametric" | "raster"
          | "object" | "clone" | "unknown",
  "name": s, "version": n,       // darktable's masks version of the form (7)
  "geometry": {...},             // normalised original-image coordinates (the pipe's input):
     circle:   { "center": [x, y], "radius": r, "border": b }
     ellipse:  { "center": [x, y], "radius": [a, b], "rotation": deg, "border": b, "proportional": bool }
     gradient: { "anchor": [x, y], "rotation": deg, "compression": c, "steepness": s, "curvature": k,
                 "state": "linear" | "sigmoidal" }
     path:     { "points": [ { "corner": [x, y], "ctrl1": [x, y], "ctrl2": [x, y], "border": [b1, b2] } ] }  (closed)
     brush:    { "points": [ { "corner", "ctrl1", "ctrl2", "border": [b1, b2], "density": d, "hardness": h } ] }
     group:    { "flexi": bool,
                 // flexi only: the marker's settings
                 "operator": s, "invert": bool, "bypass": bool, "opacity": o, "group_name": s,
                 "members": [ { "id": n, "inverse": bool, "opacity": o, "disabled": bool,
                                "combine": "union" | ... }  // "combine" only in a classic (non-flexi) group
                            ] }
     other types: {}
  "used_by": [ { "op": s, "priority": n, "root": bool } ] }   // instances whose mask holds it; root: it is the mask
```
An instance's mask group is `blend_get(...).mask_id`.

| C | meaning |
|---|---|
| `int dtb_masks(char **json)` | `[ form, ... ]`, every form of the session in darktable's order. |
| `int dtb_mask_create(const char *type, const char *geometry_json, const char *name, char **json)` [M] | A new form (`circle`, `ellipse`, `gradient`, `path`, `brush`) used by no instance; → `{ "id": n }`. `name` NULL or empty: darktable's automatic name (`dt_masks_assign_unique_name`). Geometry as above; missing sizes take desktop's remembered values (`plugins/darkroom/masks/<shape>/...`). A path needs 3 points, a brush 2; a point without `ctrl1`/`ctrl2` gets them as `path.c` / `brush.c` `_*_init_ctrl_points` computes them. A brush may instead be `{ "stroke": [ [x, y], ... ], "size"?, "hardness"?, "density"? }`: samples as from a finger, simplified and given control points exactly as desktop's brush tool does on button release (`brush.c`: Ramer-Douglas-Peucker with desktop's `brush_smoothing` epsilon, then `_brush_init_ctrl_points`; no pressure). Recorded as one history item of darktable's "mask manager", as desktop's mask manager does. |
| `int dtb_mask_update(int id, const char *geometry_json)` [M] | New geometry, same rules (same type). |
| `int dtb_mask_rename(int id, const char *name)` [M] | |
| `int dtb_mask_delete(int id)` [M] | Removes the form and its member points from every group. `ARG` for a form that is an instance's whole mask. |
| `int dtb_mask_attach(const char *op, int priority, int id, const char *options_json)` [M] | The form (or a group) becomes a member of the instance's mask, as desktop's panel adds a new shape (`dt_masks_group_insert_point`: the instance's flexi group is created with its marker when it has none) and the mask is switched on as flexi (`mask_mode` ENABLED \| FLEXI, `_blendop_mask_enable`). `options`: `{ "inverse": bool, "opacity": 0..1, "disabled": bool }` (defaults: not inverted, opaque, enabled). A form may be in several instances' masks. `ARG`: a module without drawn masks (no blending, or `IOP_FLAGS_NO_MASKS`), already a member, a group into itself. |
| `int dtb_mask_detach(const char *op, int priority, int id)` [M] | Removes the member from the instance's mask group (the form stays). |
| `int dtb_mask_member_set(const char *op, int priority, int id, const char *options_json)` [M] | The member's `inverse`, `opacity`, `disabled`. |
| `int dtb_mask_group_create(const char *member_ids_json, const char *name, char **json)` [M] | A flexi group of existing forms (`[id, ...]`, each an opaque member), → `{ "id" }`; attach it like a form. |
| `int dtb_mask_group_set(int id, const char *options_json)` [M] | A flexi group's marker: `{ "operator", "invert", "bypass", "opacity": 0..1, "name" }`. |
Errors: `NOT_FOUND` (form or instance), `ARG`, `TYPE`, `RANGE` (positions -1..2, sizes as desktop's limits). Each
call is one history item: a masks history item (`dt_dev_add_masks_history_item_ext`) of the instance it concerns, of
the first instance whose mask holds the form (update, rename, delete, group settings), or of darktable's mask manager
for forms no instance uses. Undo and redo cover them. The S3 prototype `dtb_mask_add_circle` stays (it equals
`mask_create("circle")` + `mask_attach`). Not built: retouch's and liquify's own shapes (not in this package), raster
and parametric members (`masks` lists them, nothing creates them), moving a member inside a group.
Kotlin: `masks(): List<MaskForm>`, `maskCreate(type, geometry: JsonObject, name: String? = null): Int`, `maskUpdate`,
`maskRename`, `maskDelete`, `maskAttach(op, priority, id, MaskMember())`, `maskDetach`, `maskMemberSet`,
`maskGroupCreate(ids, name): Int`, `maskGroupSet(id, settings: JsonObject)`; `MaskForm`, `MaskUser`, `MaskMember`.

### 6.8 Mask display (deliverable 8)

`render_preview_ex` / `render_region_ex` with `"show_mask": {op, priority}`: the picture as desktop's darkroom shows
it with that instance's "display mask" button on (`DT_DEV_PIXELPIPE_DISPLAY_MASK`): the picture in grey and the mask
in yellow (`iop/gamma.c` `_mask_display`, mix as desktop's `darkroom/ui/develop_mask_mix`). For that render the
bridge gives blend.c what it asks for (the instance has the focus, the pipe is the develop object's full pipe) and
runs the pipe with gamma; nothing of the edit changes, and the next plain render is the one from before. Two
modules that the bridge's export-type pipe runs and desktop's darkroom pipe does not run that way drop the mask
channel (colorout, and colorin's blue-mapping path): while a mask is shown the bridge carries the channel through
every module after the shown one whose input and output windows coincide, as desktop's modules do with
`dt_iop_alpha_copy`. `ARG` when the instance's mask mode is off or uniform (desktop shows no mask then), or the
instance is off. The stand-in engine (`FakeEngine`) answers with its picture in grey (it draws no masks).

### 6.9 Mask tree, history groups, parametric elements (P3d stage 2a, 22 Nov 2026)

What desktop does at the pinned commit: a module's mask is always a flexi tree. Desktop's mask panel has no mode
buttons any more, only on/off (`blend_gui.c` `_blendop_mask_enable` sets ENABLED | FLEXI); "drawn & parametric"
(`DEVELOP_MASK_MASK_CONDITIONAL`) survives only as a name of legacy edits, which `masks/migrate_legacy.c`
`_migrate_drawn_and_parametric` converts into a tree whose parametric channels are **parametric elements**
(`DT_MASKS_PARAMETRIC` forms, `dt_masks_point_parametric_t`, one channel each, `blend_gui.c`
`_add_parametric_channel`). So the bridge adds parametric elements to the tree; `blend_set`'s classic `parametric.*`
paths stay as they are (6.6).

```
int dtb_history_group_begin(void);                                   // [M]
int dtb_history_group_end(int keep);
int dtb_mask_add(const char *op, int priority, const char *request_json, char **json);   // [M]
int dtb_mask_member_move(int group, int id, int to_group, int position);                 // [M]
int dtb_mask_member_remove(int group, int id);                                           // [M]
int dtb_mask_member_update(int group, int id, const char *options_json);                 // [M]
int dtb_mask_group_wrap(int group, const char *member_ids_json, const char *op, char **json);  // [M]
```

**One history item per shape.** `mask_add(op, priority, {type, geometry, name?, group?, member?})` creates the form
and adds it to the instance's mask (its own group, created as `mask_attach` does, or `group`, a flexi group in the
instance's tree, on top of it), as one masks history item of the instance → `{ "id" }`. `type` is `circle`,
`ellipse`, `gradient`, `path`, `brush` (geometry as 6.7) or `parametric`. `member`: `{inverse, opacity, disabled}`.
The mask is switched on as `mask_attach` does. Errors as 6.7; `ARG` for a `group` not in the instance's tree.

**History groups.** Smallest sound design for "a brush session of n strokes is one item": the bridge's own undo
already records one state per change (`dtb__change_begin`); `group_begin` makes only the first change inside the
group record it and seal, later changes neither record nor seal, so darktable's `_dev_add_history_item_ext` merges
the masks items of the same instance into its open item (it starts a new item only for another module or after a
seal). `history_seal` inside a group does nothing (the group's item stays open). `group_end(1)`: one undo step
(none if nothing changed); `group_end(0)` (cancel): back to the state the group started from, no item, nothing to
redo. Undo, redo, `history_goto` and `history_compress` end an open group, keeping it. `STATE`: a group already open
(begin) or none open (end). Changes of different instances inside one group stay separate items but one undo step.

**Tree editing** (each one history item: of the first instance whose mask holds `group`, else the mask manager's).
`group` and `to_group` are flexi groups (`ARG` otherwise: not a group, a classic or clone group); a module's own
group is `blend_get(...).mask_id`. Member lists are bottom first, as desktop folds them (6.7).
- `member_move(group, id, to_group, position)`: position 0 = bottom, -1 = top, of `to_group` after the member left
  `group` (may be the same group: a reorder). The member's settings go with it. `ARG`: a group into itself or into
  its own tree; already a member of `to_group`; deeper than desktop allows (`_may_move_into`: the depth of
  `to_group` plus the height of the member at most `DT_MASKS_NESTING_MAX`, or no deeper than before). `RANGE`:
  position. The same place: nothing happens, no item.
- `member_remove(group, id)`: from any group; the form stays in the session.
- `member_update(group, id, {inverse, opacity, disabled})`: in any group. For a member that is a group only
  `disabled` (`ARG` otherwise): desktop keeps a group's invert and opacity on its marker (`mask_group_set`); the
  reference stays neutral (`dt_masks_group_insert_point`).
- `group_wrap(group, [id, ...], op)`: "wrap in group" (one id) and "group together" (several), as desktop's
  `dt_masks_model_group_points`: a new flexi group (operator `op`, NULL = maximum; named "group #n" as
  `_new_nested_group`) in the place of the topmost of the members, which move into it in their order with their
  settings; the reference to it is neutral. `NOT_FOUND`: an id that is not a member of `group`; `ARG`: empty or
  repeated ids, unknown operator, nesting (`depth(group) + 1 + max(1, height) > DT_MASKS_NESTING_MAX`).

**Parametric elements.** Geometry for `mask_add` / `mask_update` (same rules, a channel fixed after creation):
`{ "channel": s, "in": [a, b, c, d], "out": [a, b, c, d], "boost": EV 0..18, "show_output": bool }`. Channels of
the instance's blend colour space, named as `blend_get` names them without `_in`: lab `L a b C h`, display RGB
`g R G B H S l`, scene RGB `g R G B Jz Cz hz`. A new element starts from the module's default blendif settings
with the polarity bits clear (as `_add_parametric_channel`). A range other than the whole `[0, 0, 1, 1]` makes its
channel take part (the active bit), the whole range takes it out. `boost` rescales the ranges as desktop's slider
does (`_param_boost_apply`); only for channels with a boost factor (`ARG` otherwise for a value other than 0).
Invert and opacity are the member's (`member_update`), as on desktop's element row. `dtb_masks` reads it back:
```
parametric: { "colorspace": s, "channel": s, "in": [4], "out": [4], "in_active": bool, "out_active": bool,
              "polarity": [in, out], "boost_supported": bool, "boost": EV, "show_output": bool,
              "whole_range": bool }   // whole_range: dt_masks_parametric_is_noop, desktop's warning badge
```
Kotlin: `historyGroupBegin()`, `historyGroupEnd(keep)`, `maskAdd(op, priority, type, geometry, name, group, member)`,
`maskMemberMove`, `maskMemberRemove`, `maskMemberUpdate`, `maskGroupWrap(group, ids, operator)`; JNI entry
`NativeBridge.p3d`. The stand-in engine keeps them as data (it merges a group's items of one instance as the bridge
does). Bridge test C26 (`engine/tests/dtb_test_p3d.c`).

## 7. P3g: export progress and cancel (23 Nov 2026)

`export` (2.8) reports nothing while it runs and cannot be stopped. Two calls add both; `export` itself is
unchanged (it is `export_ex` without a callback, and its output is the same byte for byte: test C27).

| C | Kotlin (`DarktableEngine`) | Meaning |
|---|---|---|
| `int dtb_export_ex(path, options_json, progress, user)` | `suspend fun export(path, options, onProgress: (ExportProgress) -> Unit)` | The export of 2.8 with progress. `progress(step, module, modules, fraction, user)` is called on the engine thread inside the export, in this order: `PREPARING` once (fraction 0); `PROCESSING` once per module of the export pipe as it starts (`module` = 1..`modules`, `modules` = the enabled modules of that pipe); `WRITING` once; `DONE` once (fraction 1) on success only. `fraction` never goes back. The callback returns quickly and calls nothing of the bridge but `export_cancel`. The file is written under a temporary name (`.<name>.dtb-part-<pid>.<ext>`) in the target folder and renamed to `path` only on success: a cancelled or failed export leaves no file, and a file already at `path` stays as it was. |
| `void dtb_export_cancel(void)` | `fun exportCancel()` | From any thread (also from the callback). The export returns `CANCELLED`; the pipe stops after the module that is processing; nothing is written once the cancel arrived, also when it arrives after the last module. A cancel while no export runs has no effect: the next export starts uncancelled. |

`ExportProgress(step: ExportStep, module: Int, modules: Int, fraction: Double)`, `ExportStep` = `PREPARING`,
`PROCESSING`, `WRITING`, `DONE`.

**Where the progress comes from (no change to upstream).** darktable has no progress callback for one export:
desktop's export job reports only "image n of N" (`control/jobs/control_jobs.c`), and
`dt_imageio_export_with_flags` (`imageio/imageio.c`) keeps its pipe on its own stack. The bridge reaches into it at
two places that upstream already routes through replaceable function pointers:
- every module's pixel code runs through `module->process_plain` (`default_process`, `develop/imageop.c`), which
  each instance copies from its library's `so->process_plain` (`dt_iop_load_module_by_so`); the bridge has wrapped
  it since P1b for module times (`engine/src/times.c`). Before each module the wrapper now calls the export hook
  (`engine/src/output.c`), which counts the modules of the export pipe (`dt_pipe_is_export`) and, after a cancel,
  stops that pipe with `dt_dev_pixelpipe_set_shutdown(pipe, DT_DEV_PIXELPIPE_STOP_PIECE)` — the stopper a module
  itself uses (`dt_dev_piece_shutdown`), which `_module_pipe_stop` (`develop/pixelpipe_hb.c`) never ignores, unlike
  `STOP_NODES`/`STOP_DATA` late in the pipe;
- the format's `write_image`: the export is given a copy of the format module whose `write_image` is the bridge's;
  it reports `WRITING`, and refuses to write after a cancel.

So the processing part is real pipeline progress ("module n of N"), not an estimate. It counts modules, not time:
demosaic and the heavy modules take a larger share of the time than of the bar. Limits: a module processed by
OpenCL (`process_cl`) is not seen (the bridge runs on the CPU); an enabled module whose pixel code is not reached
through `process_plain` is not counted, and the bar then jumps from the last module seen to writing (C27 reports
modules seen against modules enabled). A cancel takes effect when the module processing at that moment ends.

Shares of the bar: preparing 0 to 5 %, modules 5 to 90 % (module n starting = 5 + 85·(n−1)/N), writing 90 %, done
100 %. The app adds a share per photo for a series and its own steps (sending, sharing) outside the engine.

Tests (C27, `engine/tests/dtb_test_p3g.c`): events in order and ending at 100 %; cancel while preparing, at the
first, middle and last module, while writing, and over an older file, from the callback; cancel from another
thread at 30 % and 60 % of the export's time; each leaves no file and no temporary file; an idle cancel does not
affect the next export; the export after the cancels equals the first byte for byte, `export` equals `export_ex`,
and the first export is compared with `darktable-cli` (run_tests.sh, as X1).

## 8. P3h: render a history position (snapshots, "before") (23 Nov 2026)

The clarification after P2a ("before" view) named a missing call: one that renders another history position
without touching the session. It is a render option, so preview, region and size all have it with the options they
already take (6.1: `bypass`, `show_mask`).

| C | Kotlin | Meaning |
|---|---|---|
| `"history_end": n` in `options_json` of `dtb_render_preview_ex`, `dtb_render_region_ex`, `dtb_render_size_ex` | `RenderOptions(historyEnd = n)`, `RenderOptions.at(n)` | The picture (and size) of the state after the first `n` history items, 0 <= n <= the number of items `history()` lists, as `history_goto(n)`, the same render, `history_goto(back)` give it. The session does not move: no XMP change, `history()` and its end, `undo_state()` and the open history item (and an open group) stay as they were; the next change still merges into the open item. `null` or absent: the session's own end. ARG: n outside 0..items; TYPE: not an integer. Cancel-safe as every render (`dtb_render_cancel`): a cancelled render returns CANCELLED and leaves the session as it was. |

How (history.c, `dtb__history_at_begin` / `_end`): the steps `history_goto` takes, there and back: the
library is written, the end moved, the develop object reloaded (the private library only, never the XMP). What
`history_goto` does besides is left out: an open history group stays open, the top item is not sealed, and the
module of the open item is found again after the reload, so the next change still merges into it. A first version
moved only the module state in memory (`dt_dev_pop_history_items_ext` there and back); C28 showed its pictures
differ from `history_goto`'s from some positions on, so it was replaced (P3h report).
Shapes kept across the reload (C28 fix, branch c28-live-render-fix): darktable reads back only the masks of the
items below the history end (`dt_masks_read_masks_history`: `num < history_end`), so a reload at a lower end left
the items above it without their shapes and the next library write dropped them; `history_goto` and back lost a
drawn mask this way. The bridge now keeps every item's shapes across that reload (history.c
`_reload_keeping_forms`).
In a read-only session `n` counts the rows `history()` lists (unknown items included), as `history_goto` does.

Pickers, histogram and coordinates do not take `history_end` (their options are 6.1's; the member is ignored there).

Tests: C28 (`engine/tests/dtb_test_p3h.c`): for positions 0, 1, n/2, n-2, n-1 and n of a history with a new
instance and a drawn mask, preview, size and a region equal `history_goto` + render + `history_goto` back pixel for
pixel; `history()`, `undo_state()` and the live render are unchanged, and the open item is still open; positions
out of range and a string are refused; a full-size render cancelled from another thread returns CANCELLED and
leaves everything as it was. Kotlin contract test `aRenderOfAHistoryPositionEqualsGotoAndBack` (stand-in engine on
the JVM, real engine on the emulator).

The app's "before" view uses it (`RenderOptions.at(0)`), no longer `history_goto(0)` + render + `history_goto(end)`.

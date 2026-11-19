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
- **Presets and styles:** list, apply, store per module; styles: list, apply to the open photo, import from `.dtstyle` files, delete; presets: import from `.dtpreset` files. Both live in darktable's own database in the app's configuration folder, as on desktop (PLAN.md 3.9).
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


# P1a: engine core (Engine API v1, sections 1 and 2)

Brief: `docs/briefs/P1a-engine-core.md`. Branch `p1a-engine-core`. Pull request text:
`P1a-engine-core-REPORT.md`.

## Running log (newest first)

- 2026-11-19 09:40: run 44496589328: engine for both ABIs and the G0 APKs built on the merge commit; Linux
  tests and emulator test running.
- 2026-11-19 09:3x: run 44496589328 started on the merge commit (6c84736). Local C1 on cr2 (2-core
  workspace): modules() 185 203 bytes, 7.3 ms per call. Report updated: contract points with evidence,
  modules() size, noise profile names that repeat upstream, what was not exercised.
- 2026-11-19 09:2x (work resumed after an interruption): run 44489957409 (024151b) ended: Linux 115 PASS,
  6 FAIL (the same T5 cases as 44483237019, same numbers); engine and APKs built; emulator test passed (test
  exit 0, export vs darktable-cli max 26, mean 0.00092, comparison ok). Merged main (P2c shell app, P2d
  brief, CLAUDE.md rule 8, signing step in p2a-app.yml; no conflicts, nothing in engine/). A run on the
  merge commit is started to show the G0 APK still builds with main's app/ settings.
- 2026-11-19: run 44489957409 (024151b): APKs built; Linux tests and emulator test running.
- 2026-11-19: run 44483237019: Linux 115 PASS, 6 FAIL (all T5, see 3.4); engine and APKs built; the
  emulator test failed only because tools/app/emulator-g0.sh still used the G0 app's old application id
  after the merge of main (P2a changed it). Fixed in 024151b; run on that commit pending.
- 2026-11-19 08:3x: waiting for run 44483237019 (engine for both ABIs and APKs built; Linux tests running, then
  the t5_bisect diagnosis). Report draft in P1a-engine-core-REPORT.md; numbers filled in from that run.
- 2026-11-19: aligned with the engine-api.md clarifications of cedca8b; merged main; run 44483237019 started
  (adds a t5_bisect diagnosis step). Run 44478936128: Linux 112 PASS, 9 FAIL (T5 raf corner, T5 arw x2,
  C7 webp not built on the runner: the test now expects ARG there); emulator test passed with the new bridge.
- 2026-11-19: CI run 44478936128 (commit 5a6275a) started: engine with the new bridge built for both ABIs
  (release `engine-04477d32-d0aab2b3cc28`, libdtbridge about 100 KB), G0 APKs built against the new header.
  Waiting for the Linux tests and the emulator test.
- 2026-11-19: local test run: cr2 passes everything; raf and arw cannot run on the workspace at the moment:
  the shell's memory cgroup has a 6 GB limit shared with another agent's Gradle daemons (about 3 GB), and a
  26 MP render needs more than the rest (killed by the OOM killer at 3.2 GB RSS, also with
  `resourcelevel=small`). CI (16 GB runners) is the reference for raf and arw.
- 2026-11-19 (after a server restart): test driver rewritten for API v1 (json-glib for reading JSON),
  new commands api, params, undo, actions, threads, readonly, output. history_goto now reloads through
  the library. Runner: C1 to C7 with darktable-cli parity, T5 strict.
- 2026-11-19: branch created from main 71964f9; new header and sources (commit 8e87811).

## 1. What changed

### Header (`engine/include/dtbridge.h`)
Function for function as `docs/design/engine-api.md` sections 1 and 2, C names `dtb_<name>`. Conventions:
every function returns 0 or `DTB_E_*` (except shutdown, render_cancel, last_error, free); JSON comes back
through a `char **` argument and is freed with `dtb_free`; JSON arguments are UTF-8 text. No darktable type
appears in the header. Calls that change the edit are marked [M] and refused in read-only sessions.

Renames and behaviour changes relative to the prototype (S3/G0):

| Prototype | Now | Notes |
|---|---|---|
| `dtb_init(paths)` with `paths.threads` | `dtb_init(paths, threads)` | `threads` left the struct |
| `dtb_engine_version`, `dtb_plugins_json`, `dtb_get_threads` | `dtb_engine_info(&json)` | `plugins` keeps the per-kind detail G0 used, plus `files`, `loaded`, `missing` |
| `dtb_set_threads(n)` returned the count | returns 0; the count is `engine_info.threads` | also clamps instead of refusing |
| — | `dtb_set_thread_cpus(ids, n)` | new |
| `dtb_xmp_check(path, &n)` returned JSON | `dtb_xmp_check(path, &json)` | reason `newer_mask_version` renamed `newer_masks_version` (spec) |
| `dtb_is_read_only()` | `image_info.read_only` | |
| — | `dtb_image_info`, `dtb_metadata_reload` | new; `color_labels` are darktable's numbers 0..4 (cedca8b) |
| `dtb_modules_json()` keys `operation`, `multi_priority`, scalar params only | `dtb_modules(&json)` keys `op`, `priority`, full parameter tree, flags `can_switch` … `in_history` | |
| `dtb_param_set_float/_int/_bool(op, prio, name, v)` | `dtb_param_set(op, prio, path, value_json)` | paths into structs and arrays; enums by constant name only |
| — | `dtb_params_set`, `dtb_module_reset`, `dtb_module_action` | new |
| `dtb_set_enabled` always added an item | no-op when the state does not change | |
| — | `dtb_instance_rename` | new |
| `dtb_history_json()` keys `history_end`, `operation`, `multi_priority` | `dtb_history(&json)` keys `end`, `op`, `priority`, plus `name` | |
| `dtb_history_goto` popped in memory | pops, writes the history, reloads the develop object; allowed read-only; not an undo step (cedca8b) | see 3.3 |
| `void dtb_history_seal()` | returns int | |
| — | `dtb_undo`, `dtb_redo`, `dtb_undo_state` | new |
| `dtb_render(x, y, w, h, scale, out)` | `dtb_render_region(...)` | internal margin, see 3.4 |
| `dtb_export_jpeg(path, q, w, h)` | `dtb_export(path, options_json)` | jpeg, png, tiff, webp, depth, profile, upscale, high_quality |
| `dtb_xmp_write` wrote in place | temporary file in the same folder, then rename | |
| history items without the "darktable|changed" tag | tag and change timestamp set per change, as desktop | see 4 |

`dtb_mask_add_circle` and `dtb_cache_drop` are kept (the first for T9 until P1b, the second is in the spec).

### Sources (`engine/src/`)
`core.c` (errors, lifecycle, engine_info, threads), `guard.c` (version guard, was `xmp_check.c`),
`session.c`, `params.c` (modules and parameters through introspection), `actions.c`, `instances.c`,
`history.c` (grouping, history, undo), `render.c`, `output.c`, and `dtb_internal.h`.
The bridge now links lcms2 and lensfun directly (both are darktable dependencies already, in `deps/`): the
white balance and lens actions call them as the modules do.

### G0 app (`app/`)
Only what the renames required: `dtjni.c` maps the unchanged Kotlin `NativeBridge` functions onto the new
calls; `Engine.kt` reads the plugin report under `engine_info.plugins`; `EngineOps.kt` reads `op` and
`priority`. No feature added.

### Tests (`engine/tests/`)
The driver uses `dtbridge.h` and json-glib. T1 to T9 and X1 are kept with the new names. New:

| Test | Covers (engine-api.md section 4) |
|---|---|
| C1 api | engine_info, image_info (class per image, size = render_size), modules JSON keys and pipeline order, struct/array/enum params, `can_switch` (demosaic refuses `set_enabled` with ARG), NOT_FOUND |
| C2 params | paths into arrays of structs (`rgbcurve curve_nodes[0][0].y`), int array elements, enums by name; TYPE, RANGE, NOT_FOUND, ARG; nothing changes on refusal; `params_set` as one item and all-or-nothing; merging of consecutive `param_set`, `history_seal`, other calls seal; `module_reset` equals the instance's defaults, idempotent, one item; darktable-cli renders both written XMPs like the bridge |
| C3 undo | 12 kinds of change (merged param_set, set_enabled, params_set, new, param on new, rename, move, duplicate, delete priority 0, reset, goto, compress): each undo and redo gives byte-identical `modules()` and `history()` JSON and identical pixels; redo cleared by a new change; depth after 60 sealed changes; cli parity of the result |
| C4 actions | every action in the spec, errors included; cli parity of the resulting XMP (the LUT root passed to cli) |
| C5 threads | set_threads 1, clamping, restore; set_thread_cpus pins the engine thread and every OpenMP worker (checked in `/proc/self/task/*/status`), unpinning, renders unchanged |
| C6 readonly | 20 mutating calls return READ_ONLY in a session opened on a newer-version XMP; reads, renders, get actions and export still work; the XMP file is byte-identical afterwards |
| C7 output | atomic xmp_write (no temp files left, also after a failed write), 11 export variants and 6 refused ones, metadata_reload (rating and labels from a modified XMP; modules and history JSON unchanged; written back); JPEG and PNG exports equal darktable-cli's |
| T5 region | now strict: region = crop of the full render and = crop of darktable-cli, tolerance 0 |

## 2. Upstream functions newly relied on (for `docs/UPDATE.md`)

| Area | File: functions |
|---|---|
| introspection | `common/introspection.h`: `dt_introspection_get_child`, `dt_introspection_access_array`, `dt_introspection_get_enum_name`, `dt_introspection_get_enum_value`; per module `get_introspection()` |
| history | `develop/develop.c`: `dt_dev_history_seal`, `dt_dev_equal_chroma`; `common/history_snapshot.c`: `dt_history_snapshot_create`, `_restore`, `_clear` |
| modules | `develop/imageop.c`: `dt_iop_reset_params`; fields `hide_enable_button`, `default_enabled`, `multi_name_hand_edited` |
| metadata | `common/ratings.c`: `dt_ratings_get`; `common/colorlabels.c`: `dt_colorlabels_get_labels`, `dt_colorlabels_to_string`; `common/tags.c`: `dt_tag_new`, `dt_tag_attach`, `dt_tag_get_attached`, `dt_tag_free_result`; `common/image_cache.c`: `dt_image_cache_set_change_timestamp`; `common/datetime.c`: `dt_datetime_img_to_exif` |
| white balance | `common/wb_presets.c`: `dt_wb_presets_count`, `dt_wb_preset`, `dt_wb_preset_interpolate`; `common/colorspaces.c`: `dt_colorspaces_conversion_matrices_xyz`, `D65xyY`; `external/cie_colorimetric_tables.c` (included); `dev->chroma` (`as_shot`, `wb.D65coeffs`) |
| noise | `common/noiseprofiles.c`: `dt_noiseprofile_get_matching`, `dt_noiseprofile_free`, `dt_noiseprofile_generic` |
| lens | `common/file_location.c`: `dt_loc_get_datadir`; Lensfun C API < 0.3.95 (`lf_db_*`, `lf_modifier_*`); lcms2 `cmsWhitePointFromTemp` |
| export | `imageio/imageio.c`: `dt_imageio_export` with an ICC type; format modules' `get_params` reading `plugins/imageio/format/<name>/*`; `control/conf.c`: `dt_conf_get_string`, `dt_conf_key_exists`, `dt_conf_set_*` |
| threads | darktable's `num_openmp_threads`; OpenMP's thread pool (`#pragma omp parallel`), `sched_setaffinity` |

## 3. Desktop logic re-implemented in the bridge (compare at every upstream bump)

Static functions upstream, so their logic is copied; each place in the source names its origin.

1. **White balance** (`iop/temperature.c`): `_spd_blackbody`, `_spd_daylight`, `_spectrum_to_XYZ`,
   `_temperature_to_XYZ`, `_XYZ_to_temperature`, `_temp2mul`, `_mul2xyz`/`_mul2temp`, `_prepare_matrices`,
   `_generate_preset_combo` (positions stored in params `preset`), `_params_mode`, `_preset_tune_callback`
   (camera presets with fine tuning and interpolation), `_temp_tint_callback` (set_wb), `gui_update` (get_wb).
2. **Lens** (`iop/lens.cc`): `init_global` database location (Lensfun < 0.3.95 path), `_camera_set`,
   `_lens_set` (prime lens fixes the focal length), `_lens_menu_select`, `_get_autoscale_lf`,
   `_get_modifier` (pre-0.3.95 branch), `_modflags_to_lensfun_mods` and `_lenstype_to_lensfun_lenstype`
   (by enum constant name, not by copied values). The bridge loads its own copy of the Lensfun database;
   the module's copy is in private global data.
3. **lut3d** (`iop/lut3d.c`): `_button_clicked` (file inside the LUT root folder, relative path),
   `_filepath_is_safe`, `filepath_set_unix_separator`, `_filepath_callback` without G'MIC.
4. **denoise (profiled)** (`iop/denoiseprofile.c`): `_get_iso_highlight_preservation_shift`, the label logic
   of `dt_iop_denoiseprofile_get_auto_profile`, the profile list of `reload_defaults`, `profile_callback`.
5. **History item recording** (`develop/develop.c`, `_dev_add_history_item`): the "darktable|changed" tag
   and change timestamp after `dt_dev_add_history_item_ext`.
6. **Instance rename** (`develop/imageop.c`, `dt_iop_update_multi_name` with force).
7. **Reset** follows `_gui_reset_clicked` (refused for always-off modules; switches the module on).
8. **Undo/redo**: desktop's darkroom undo (`develop.c` `dt_dev_undo_start_record`, `libs/history.c`
   `_pop_undo`) is tied to the interface; the bridge uses the lighttable's snapshot tables and reloads.
9. From S3, still in place: duplicate/new instance (`dt_iop_gui_duplicate`), delete (`_gui_delete_callback`
   and `dt_dev_module_remove`), move, compress, mask history.

## 3.x Decisions with numbers

### 3.1 History grouping
Consecutive `param_set`/`params_set` on the same instance merge into one item until `history_seal`; any
other mutating call seals before and after itself; a parameter change on another instance starts a new
item. C2 checks the counts (cr2: 9 → 10 → 11 → 12 → 14 → 15).

### 3.2 Undo and redo
Before each sealed change the bridge writes its history to the library and copies the image's rows
(history, masks history, module order; all items, also those above `end`) into darktable's
`memory.snapshot_*` tables with `dt_history_snapshot_create`. Undo snapshots the current state onto the
redo stack, restores with `dt_history_snapshot_restore`, sets `history_end`, and reloads the develop object.
Up to 100 states are kept (spec: at least 50; C3 measured 71 steps after 60 sealed changes, cr2, because
the earlier steps of the test are still there). `dt_history_snapshot_restore` empties the history with
`dt_history_delete_on_image_ext`, which also detaches "darktable|style|…" and "darktable|changed" tags; the
bridge re-attaches the style tags it saw and marks the image changed.

### 3.3 history_goto reloads
After an instance delete or move, a module instance keeps the defaults computed when it was created, but
several modules compute defaults from whether the instance is the first in the pipe
(`dt_iop_is_first_instance`; exposure's `compensate_hilite_pres`). After `goto`, instances whose items lie
above the new end show those stale defaults in memory, while a reload (and darktable-cli) computes fresh
ones. C3 found this (modules JSON differed in that one field after undoing a goto). The bridge's goto
therefore writes and reloads, so the session shows what a fresh session would.

### 3.4 Region margin
Upstream does not expose how far demosaic reaches: it keeps its tile border inside
`_tiling_requirements()` (`iop/demosaic.c`; up to 18 px for Markesteijn 3-pass, plus 6 with capture
sharpening) and reports no overlap through `tiling_callback()`. The margin is therefore fixed:
**32 px** on every side that lies inside the image, in output pixels at the render scale
(`DTB_REGION_MARGIN` in the environment overrides it, for `t5_bisect.sh` only).

Run 44483237019 (CI step "Region test diagnosis"): cr2 exact in all three regions; raf exact except the
top-left corner region (max 11, within 10 px of the image's top and left edge; lens off → 0, demosaic
passthrough → 0; margins 0/16/32/64/256 → 12/10/11/8/4); arw centre max 21 and bottom-right max 1,
independent of the margin (spektrafilm off → centre 0 but bottom-right 68; demosaic passthrough → all 0).
T5 keeps failing for these cases.

### 3.5 history_goto and undo (engine-api.md clarifications, cedca8b)
`history_goto` is navigation: allowed in read-only sessions, records no undo state. The next edit records
the state it starts from (with the moved end), so undoing that edit returns to the goto position, and
undoing further returns to the states before earlier edits, each with its own end. Redo returns to the
state an undo left (C3 checks both).

## 4. Points where the specification could not be followed as written
See the report, section "Open questions".

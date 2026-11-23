// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * dtbridge.h: the only interface between the app and the darktable engine.
 *
 * Engine API v1, sections 1 and 2 ("Core") of docs/design/engine-api.md,
 * which is the source of truth for meaning and JSON shapes; section 5
 * (presets, styles, P1b part 1) and section 6 (render options, pickers,
 * histogram, coordinates, blending, masks, P1b part 2) are at the end of
 * this file. dtb_mask_add_circle() below is the prototype from S3, kept for
 * its test.
 *
 * Nothing above this header may include darktable's own headers, and this
 * header exposes no darktable type.
 *
 * Model
 * - One process holds one engine (dtb_init .. dtb_shutdown) and at most one
 *   open image (dtb_open .. dtb_close), called a session.
 * - Threading: every function except dtb_render_cancel() and
 *   dtb_export_cancel() must be called from the same thread, the "engine
 *   thread". dtb_render_cancel() may be called from any thread while a render
 *   runs on the engine thread; dtb_export_cancel() from any thread (P3g).
 * - A module instance is addressed by its operation name `op` ("exposure")
 *   and its `priority` (darktable's multi_priority, 0 for the first
 *   instance). Priorities can change when an instance is deleted; callers
 *   re-read dtb_modules().
 * - Every function returns 0 (DTB_OK) or a negative DTB_E_* code, except
 *   dtb_shutdown, dtb_render_cancel, dtb_export_cancel, dtb_last_error and dtb_free.
 *   dtb_last_error() then holds a message.
 * - JSON results are returned through a `char **json` argument, are UTF-8,
 *   owned by the caller and freed with dtb_free(). On failure *json is NULL.
 * - JSON arguments are UTF-8 text.
 * - Read-only sessions (the opened XMP failed the version guard) refuse every
 *   call that would change the edit, with DTB_E_READ_ONLY: the "mutating"
 *   calls marked [M] below.
 * - Coordinates for rendering are pixels of the processed image at a given
 *   scale: 1.0 is the full processed image after crop and distortions.
 */
#ifndef DTBRIDGE_H
#define DTBRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DTB_API_VERSION 1

/* error codes */
#define DTB_OK              0
#define DTB_E_STATE        -1  /* wrong state: not initialised, no session, nothing to undo, ... */
#define DTB_E_ARG          -2  /* bad argument, or an operation the module does not allow */
#define DTB_E_NOT_FOUND    -3  /* module instance, parameter, action or preset not found */
#define DTB_E_TYPE         -4  /* value has another type than the parameter */
#define DTB_E_RANGE        -5  /* value outside the parameter's declared range */
#define DTB_E_IO           -6  /* file could not be read or written */
#define DTB_E_READ_ONLY    -7  /* refused: session or target XMP failed the version guard */
#define DTB_E_CANCELLED    -8  /* render cancelled by dtb_render_cancel() */
#define DTB_E_ENGINE       -9  /* darktable reported a failure */

/* ---------------------------------------------------------------------- */
/* helpers (C only)                                                        */

/* Message for the last failure on this thread's engine calls. */
const char *dtb_last_error(void);
/* Free a JSON string or a buffer returned by the bridge. */
void dtb_free(void *p);

/* ---------------------------------------------------------------------- */
/* 2.1 lifecycle                                                           */

typedef struct dtb_paths_t
{
  const char *configdir;  /* darktablerc lives here; created if missing */
  const char *cachedir;   /* kernels and other caches */
  const char *datadir;    /* darktable's share/darktable */
  const char *moduledir;  /* darktable's lib/darktable (holds plugins/) */
  const char *tmpdir;     /* may be NULL */
  const char *library;    /* private library database file, or ":memory:" */
} dtb_paths_t;

/* Start the engine without its desktop interface (as darktable-cli does).
 * threads: OpenMP threads, 0 = darktable's default (all cores). The engine
 * never writes an XMP on its own (write_sidecar_files=never); only
 * dtb_xmp_write() does. */
int dtb_init(const dtb_paths_t *paths, int threads);

/* Close the session if one is open, then stop the engine. */
void dtb_shutdown(void);

/* { "darktable_version": s, "api_version": 1, "threads": n,
 *   "threads_max": n,                 the count at dtb_init, the upper limit
 *   "thread_cpus": [ cpu, ... ],      the CPUs set by dtb_set_thread_cpus, [] = not pinned
 *   "cpus_allowed": [ cpu, ... ],     the CPUs the process may use now (P1b)
 *   "cpus_configured": n,             the CPUs the device has (P1b)
 *   "plugins": { "files": n, "loaded": n, "missing": [ "<kind>/<name>", ... ],
 *                "moduledir": s,
 *                "iop" | "format" | "storage": { "files", "loaded", "not_loaded": [ name ] },
 *                "gui_only": { "views": n, "lighttable": n } } }
 * Without the desktop interface darktable loads only processing modules
 * (iop), export formats and storages; view and lighttable plugins are only
 * counted. "files" and "loaded" sum iop, format and storage. */
int dtb_engine_info(char **json);

/* Limit the number of threads darktable's parallel loops use. n <= 0
 * restores the count of dtb_init; a larger n is clamped to it (some
 * per-thread buffers are sized at start-up). No render in flight. */
int dtb_set_threads(int n);

/* Pin the engine's threads to the given CPUs: the engine thread itself and
 * every OpenMP worker thread, each with sched_setaffinity() on its own
 * thread id, so that threads created later inherit the set. The CPUs are
 * checked against what the process may use at the time of the call (its
 * cpuset and its main thread's mask, P1b): the set applied is requested ∩
 * allowed, DTB_E_ARG only when that is empty; engine_info "thread_cpus"
 * lists what was applied. n == 0 removes the pinning: back to every CPU the
 * process may use now. The caller chooses the CPUs (on phones: the fast
 * cores); the bridge only applies the list. */
int dtb_set_thread_cpus(const int *cpu_ids, int n);

/* ---------------------------------------------------------------------- */
/* 2.2 version guard (PLAN.md 2.1)                                         */

/* Check an XMP sidecar without opening the image:
 * { "ok": bool, "parsed": bool, "xmp_version": n, "entries": n,
 *   "problems": [ { "num": n, "operation": s, "modversion": n,
 *                   "engine_version": n | null,
 *                   "reason": "unknown_operation" | "newer_module_version"
 *                             | "newer_blend_version" | "newer_masks_version" } ] }
 * "ok" is false if the file cannot be parsed. DTB_E_IO if it cannot be read. */
int dtb_xmp_check(const char *xmp_path, char **json);

/* ---------------------------------------------------------------------- */
/* 2.3 session                                                             */

/* Import raw_path into the private library, load its picture, and load
 * xmp_path (NULL: a
 * sidecar named <raw>.xmp is picked up if it exists). The session starts
 * from exactly that XMP's edit; without an XMP (xmp_path NULL and no
 * sidecar) from the default edit, as a first import shows it, whatever an
 * earlier session in this process did to the image. The guard runs first;
 * if it fails, the image still opens, for viewing, read-only: every [M] call
 * and dtb_xmp_write return DTB_E_READ_ONLY. Returns DTB_OK in both cases
 * (see image_info "read_only"). The RAW file is only ever read.
 * A file without a picture darktable can decode (junk, empty, truncated,
 * unsupported) is refused with DTB_E_IO and the loader's reason; no session
 * is left open (P1b, E1). */
int dtb_open(const char *raw_path, const char *xmp_path);

/* Close the session. Writes nothing. */
int dtb_close(void);

/* { "width", "height",             processed image at scale 1
 *   "raw_width", "raw_height",     sensor data as loaded
 *   "image_class": "bayer" | "xtrans" | "nonraw" | "monochrome",
 *   "read_only": bool,
 *   "exif": { "maker", "model", "lens", "iso", "aperture", "exposure",
 *             "focal_length", "datetime" },   datetime "YYYY-MM-DDTHH:MM:SS" or null
 *   "rating": -1 (rejected) .. 5,
 *   "color_labels": [ n, ... ] }   darktable's numbers: 0 red, 1 yellow, 2 green, 3 blue, 4 purple
 * Loads the full image if no render has done so yet. */
int dtb_image_info(char **json);

/* [M] Re-read rating, colour labels and tags from an XMP (for example after
 * LightPhotoLibrary merged them) without touching the session's history. */
int dtb_metadata_reload(const char *xmp_path);

/* ---------------------------------------------------------------------- */
/* 2.4 modules and parameters                                              */

/* Module instances in pipeline order:
 * [ { "op", "name", "priority", "instance_name", "iop_order",
 *     "enabled", "can_switch", "default_enabled", "hidden", "deprecated",
 *     "one_instance", "supports_blending", "version", "in_history",
 *     "params": { ... } } ]
 * params is the whole parameter structure read through darktable's
 * introspection: numbers, bools and enum constant names for scalars,
 * objects for structs, arrays for arrays, strings for char arrays.
 * Non-finite numbers are null. */
int dtb_modules(char **json);

/* [M] Set one parameter. path: a field name with optional steps, for
 * example "exposure", "curve[2].x", "equalizer_y[0][3]". value_json: a JSON
 * number (float and integer fields), true/false (bool), an enum constant
 * name as a JSON string (enum), or a JSON string (char array). Wrong type:
 * DTB_E_TYPE; outside the declared range: DTB_E_RANGE. Switches the module
 * on, as moving a control on desktop does (not for modules without an on/off
 * switch). History grouping: consecutive dtb_param_set / dtb_params_set
 * calls on the same instance merge into one history item until
 * dtb_history_seal(); any other mutating call seals first. */
int dtb_param_set(const char *op, int priority, const char *path, const char *value_json);

/* [M] Several fields as one history step: values_json is an object
 * { "<path>": value, ... }. All values are checked first; nothing changes
 * if one is refused. */
int dtb_params_set(const char *op, int priority, const char *values_json);

/* [M] Switch an instance on or off. DTB_E_ARG for modules without a switch
 * ("can_switch" false). Nothing happens if it is already in that state. */
int dtb_set_enabled(const char *op, int priority, int enabled);

/* [M] Back to the module's defaults for this image (dt_iop_reset_params),
 * as the reset button on desktop. */
int dtb_module_reset(const char *op, int priority);

/* What a module's desktop panel computes itself. args_json may be NULL
 * (= {}). *result_json receives a JSON value (may be NULL on failure).
 * Unknown action: DTB_E_NOT_FOUND. Actions that change parameters are [M].
 * docs/design/engine-api.md 2.4 and 5.4 have the JSON.
 *   temperature:    get_wb -> {temperature, tint, preset, coefficients, tuning, preset_index};
 *                   set_wb {temperature, tint} [M];
 *                   list_presets -> [ {name, kind, tuning_min, tuning_max, tuning_default} ];
 *                   apply_preset {name, tuning?} [M]
 *   lens:           list_cameras -> [ {maker, model, variant, mount, crop_factor} ];
 *                   list_lenses {camera} -> [ {maker, model, min_focal, max_focal, min_aperture, max_aperture} ];
 *                   use_latest_md [M] (desktop's "use latest algorithm": md_version 2, scale_md_v1 0);
 *                   set_lens {camera, lens} [M]
 *   lut3d:          set_file {path} [M]
 *   colorin:        list_profiles -> [ {kind: "input"|"work", type, filename, name, current} ];
 *   borders:        set_aspect {aspect} [M]: 0 (image), -1 (constant border) or 1..3;
 *   P1b part 2 (engine-api.md 6.3), each picks an "area" or "point" first:
 *   temperature:     wb_from_area {area} [M];
 *   exposure:        spot {area, mode: "correction"|"measure", lightness?} [M];
 *   rgblevels:       pick {which: "black"|"grey"|"white", area, channel?} [M];
 *   rgbcurve:        set_nodes_from_area {area, channel?, offset?} [M];
 *   channelmixerrgb: illuminant_from_area {area, use_mixing?} [M],
 *                    spot {area, mode: "correction", target?, use_mixing?} [M]
 *   denoiseprofile: list_profiles -> [ {index, name, auto, iso, current} ], index = position
 *                   in desktop's profile list, 0 = the automatic entry;
 *                   set_profile {index} or {name} [M] (a name selects the first
 *                   entry of that name; names can repeat, indices do not) */
int dtb_module_action(const char *op, int priority, const char *action, const char *args_json,
                      char **result_json);

/* ---------------------------------------------------------------------- */
/* 2.5 instances                                                           */

/* [M] New instance with default parameters, placed after the base instance.
 * *new_priority (may be NULL) receives its priority. DTB_E_ARG for
 * one_instance modules. */
int dtb_instance_new(const char *op, int priority, int *new_priority);
/* [M] Same, copying parameters, blending and (shared) masks from the base. */
int dtb_instance_duplicate(const char *op, int priority, int *new_priority);
/* [M] Delete an instance and every history item that refers to it, as
 * desktop does. The last instance of a module cannot be deleted (DTB_E_ARG).
 * If priority 0 is deleted, the next instance becomes priority 0. */
int dtb_instance_delete(const char *op, int priority);
/* [M] Move one step in the pipeline: direction +1 = later in the pipeline
 * (up in darktable's module list, which shows the pipeline last-first),
 * -1 = earlier. iop_order in dtb_modules() grows along the pipeline. Skips hidden modules; refuses moves the
 * pipeline rules forbid (DTB_E_ARG). As on desktop, moving switches the
 * module on. */
int dtb_instance_move(const char *op, int priority, int direction);
/* [M] Set the instance label. A non-empty name is kept as typed (hand
 * edited); an empty name returns the label to darktable's automatic one. */
int dtb_instance_rename(const char *op, int priority, const char *name);

/* ---------------------------------------------------------------------- */
/* 2.6 history                                                             */

/* { "end": n, "items": [ { "num", "op", "priority", "name",
 *                          "instance_name", "enabled", "unknown" } ] }
 * 0 <= end <= number of items. "unknown" is true for an item the engine
 * could not load: in a read-only session, an entry of the XMP whose
 * operation the engine does not know. Such an item keeps its place, its
 * operation name (also as "name") and its fields as the XMP gives them; it
 * has no effect on the picture. */
int dtb_history(char **json);
/* Show the state after the first `end` items (0 = original), for every end
 * from 0 to the number of items, unknown items included. A following
 * change discards the items above `end`, as on desktop. Navigation: allowed
 * in a read-only session, and not an undo step (undo and redo cover edits
 * only, unlike desktop). */
int dtb_history_goto(int end);
/* [M] Compress history (darktable's dt_history_compress_on_image). */
int dtb_history_compress(void);
/* Close the current history item: the next change starts a new one. */
int dtb_history_seal(void);
/* [M] Step back to the whole-history state recorded before the last sealed
 * change, or forward again. At least 50 steps are kept. DTB_E_STATE when
 * there is nothing to undo or redo. The image's history hashes and its
 * "darktable|changed" tag come back with the state; the change timestamp is
 * not moved, as on desktop (P1b part 2, engine-api.md 6.2). */
int dtb_undo(void);
int dtb_redo(void);
/* { "can_undo": bool, "can_redo": bool } */
int dtb_undo_state(char **json);

/* ---------------------------------------------------------------------- */
/* masks (prototype from S3, for its test only; replaced in P1b)           */

/* [M] Give an instance a drawn circle mask. cx, cy, radius and border are
 * in darktable's normalised image coordinates (0..1 of the full input). */
int dtb_mask_add_circle(const char *op, int priority,
                        float cx, float cy, float radius, float border);

/* ---------------------------------------------------------------------- */
/* 2.7 rendering                                                           */
/* All renders return RGBA8 with A = 255, in the output colour profile of the
 * history (sRGB by default), processed as an export: same pipeline type and
 * the same 8-bit conversion as darktable-cli. */

/* Size of the processed image at `scale`. */
int dtb_render_size(float scale, int *width, int *height);

/* Whole image fitting in max_size x max_size, from darktable's downscaled
 * input (as the darkroom's preview pipe), for immediate feedback.
 * *rgba8_out is allocated by the bridge and freed with dtb_free(). */
int dtb_render_preview(int max_size, uint8_t **rgba8_out, int *width, int *height);

/* Exactly the region (x, y, w, h) of the processed image at `scale` into
 * rgba8_out (w*h*4 bytes). The bridge renders a larger region and crops it
 * away, so that the region equals the same crop of a full render: an
 * internal margin of at least 32 px on every side that lies inside the
 * image, wider (up to 256 px) when a switched-on module reaches further
 * (its own tiling overlap), and, for raw images, a window moved so that
 * demosaic starts on the same colour filter phase as in a full render.
 * Known exceptions, with bounds: engine/tests/t5_expected.txt. Do not
 * stitch a view from several regions. */
int dtb_render_region(int x, int y, int w, int h, float scale, uint8_t *rgba8_out);

/* P1b part 2 (engine-api.md 6.1): the same three calls with options for
 * this call only. options_json NULL or "{}" = the plain call:
 * { "bypass": [ { "op": s, "priority": n }, ... ],
 *     the instances are processed as if switched off, for this call only: no
 *     history item, no undo state, no change to modules(), history() or the
 *     XMP; sizes and regions are those of the picture without them.
 *     DTB_E_NOT_FOUND: no such instance; DTB_E_ARG: a module without an on/off
 *     switch.
 *   "show_mask": { "op": s, "priority": n } | null }
 *     desktop's "show mask" overlay of that instance (6.8). */
int dtb_render_size_ex(float scale, const char *options_json, int *width, int *height);
int dtb_render_preview_ex(int max_size, const char *options_json, uint8_t **rgba8_out, int *width,
                          int *height);
int dtb_render_region_ex(int x, int y, int w, int h, float scale, const char *options_json,
                         uint8_t *rgba8_out);

/* Ask the render running on the engine thread to stop. Safe from any
 * thread. The running render returns DTB_E_CANCELLED; the pipeline stops
 * between modules. A cancel while no render runs has no effect. Must not be
 * called concurrently with dtb_close(). */
void dtb_render_cancel(void);

/* Drop the decoded images of the last opened image from the engine's
 * memory caches, so the next dtb_open() and render start from the RAW
 * again. Only while no image is open. For measurements. */
int dtb_cache_drop(void);

/* ---------------------------------------------------------------------- */
/* 2.8 output                                                              */

/* Write the session's history as a darktable XMP sidecar. Refused
 * (DTB_E_READ_ONLY) if the session is read-only or if an existing file at
 * xmp_path fails the version guard. Written atomically: to a temporary file
 * in the same folder, then renamed over xmp_path. */
int dtb_xmp_write(const char *xmp_path);

/* Export through darktable's own export path (dt_imageio_export).
 * options_json (may be NULL = all defaults):
 * { "format": "jpeg" (default) | "png" | "tiff" | "webp",
 *   "quality": 1..100 (jpeg, webp; default 95),
 *   "bit_depth": 8 (default) | 16 (png, tiff) | 32 (tiff),
 *   "max_width": n, "max_height": n (0 = no limit, default),
 *   "upscale": false, "high_quality": true,
 *   "profile": "image" (default: the history's output profile) | "srgb"
 *              | "adobergb" | "displayp3" }
 * A format whose library is not built: DTB_E_ARG with a message. */
int dtb_export(const char *path, const char *options_json);

/* P3g (engine-api.md 6.10): the same export with progress, and a cancel.
 * progress (may be NULL) is called on the engine thread, inside the export,
 * in this order: PREPARING once (fraction 0), PROCESSING once per module of
 * the export pipe as it starts (module = 1..modules, the count of enabled
 * modules of that pipe), WRITING once, DONE once (fraction 1) on success
 * only. fraction (0..1) never goes back. The callback must return quickly
 * and must not call the bridge, except dtb_export_cancel().
 * The file is written under a temporary name in path's folder and renamed
 * to path only on success: a cancelled or failed export leaves no file.
 * Returns DTB_E_CANCELLED after dtb_export_cancel(). dtb_export(p, o) is
 * dtb_export_ex(p, o, NULL, NULL). */
typedef enum dtb_export_step_t
{
  DTB_EXPORT_PREPARING = 0,
  DTB_EXPORT_PROCESSING = 1,
  DTB_EXPORT_WRITING = 2,
  DTB_EXPORT_DONE = 3
} dtb_export_step_t;
typedef void (*dtb_export_progress_f)(int step, int module, int modules, double fraction, void *user);
int dtb_export_ex(const char *path, const char *options_json, dtb_export_progress_f progress, void *user);

/* Ask the export running on the engine thread to stop. Safe from any thread,
 * also from the progress callback. The pipe stops after the module that is
 * processing; nothing is written once the cancel arrived. A cancel while no
 * export runs has no effect (the next export starts uncancelled). */
void dtb_export_cancel(void);


/* ---------------------------------------------------------------------- */
/* 5.2 presets (P1b; docs/design/engine-api.md section 5.2 has the JSON)   */
/* Presets live in darktable's database in configdir (data.db), as on
 * desktop. Calls that only change that database are not [M]. */

/* Every stored preset of module `op`, all versions, in desktop's menu
 * order: [ { "name", "description", "builtin", "op_version", "usable",
 * "enabled", "autoapply", "filter", "match": { "model", "maker", "lens",
 * "iso": [min, max|null], "exposure": [..], "aperture": [..],
 * "focal_length": [..], "format": [ "raw"|"ldr"|"hdr"|"matrix"|"monochrome"
 * |"color" ] }, "matches", "fits", "active" } ]. "matches", "fits" and
 * "active" refer to the open image and instance (op, priority); null when
 * no image is open. Unknown op: DTB_E_NOT_FOUND. */
int dtb_presets_list(const char *op, int priority, char **json);
/* [M] Apply a preset to an instance as desktop does: one history item. */
int dtb_preset_apply(const char *op, int priority, const char *name);
/* Store the instance's parameters, switch and blending as a user preset
 * (needs an open image). An existing user preset of that name: DTB_E_ARG
 * unless overwrite != 0. Built-in names: DTB_E_ARG. */
int dtb_preset_store(const char *op, int priority, const char *name, const char *description, int overwrite);
/* User presets only (DTB_E_ARG for built-in ones); every version of the name. */
int dtb_preset_rename(const char *op, const char *name, const char *new_name);
int dtb_preset_delete(const char *op, const char *name);
/* Import one .dtpreset file -> { "op", "name", "op_version", "usable" }.
 * Refused (DTB_E_ARG, nothing stored) for an unknown module, a newer module
 * or blend version, a built-in name, or an existing user preset of that
 * name unless overwrite != 0. DTB_E_IO: not a readable preset file. */
int dtb_preset_import(const char *path, int overwrite, char **json);
/* Write <dir>/<name>.dtpreset as desktop does -> { "path" }. */
int dtb_preset_export(const char *op, const char *name, const char *dir, char **json);

/* ---------------------------------------------------------------------- */
/* 5.3 styles (P1b)                                                        */

/* [ { "name", "description", "has_module_order", "usable",
 *     "items": [ { "num", "op", "priority", "instance_name", "enabled",
 *                  "module_version", "blend_version", "problem": null |
 *                  "unknown_operation" | "newer_module_version"
 *                  | "newer_blend_version" } ] } ] by name. */
int dtb_styles_list(char **json);
/* [M] Apply a style to the open image. mode "append", "overwrite", or NULL
 * = desktop's preference. One undo step. A style with an item the engine
 * cannot use is refused with DTB_E_ARG and nothing changes. */
int dtb_style_apply(const char *name, const char *mode);
/* Import .dtstyle files: paths_json is a JSON array of paths ->
 * { "imported": [ { "path", "name", "usable" } ],
 *   "refused": [ { "path", "reason": "io" | "not_a_style" | "exists" } ] }. */
int dtb_styles_import(const char *paths_json, int overwrite, char **json);
int dtb_style_delete(const char *name);
/* Create a style from the open image's history: items_json NULL = every
 * item below the history end, else a JSON array of history nums. Does not
 * change the edit. */
int dtb_style_create(const char *name, const char *description, const char *items_json, int overwrite);

/* ---------------------------------------------------------------------- */
/* 5.6 per-module times of the last render (P1b, E2)                       */

/* { "pipe": "full" | "preview" | null, "total_ms",
 *   "modules": [ { "op", "priority", "instance_name", "ms", "cpu_ms", "tiles" } ] }
 * The modules that ran in the last render, in pipeline order. */
int dtb_render_times(char **json);

/* ---------------------------------------------------------------------- */
/* 6.3, 6.4 colour pickers and histogram (P1b part 2; engine-api.md)       */

/* Desktop's module picker on instance (op, priority), or with op NULL the
 * picture the pipe puts out (desktop's global picker). request_json:
 * { "area": [x0, y0, x1, y1] } or { "point": [x, y] } in normalised
 * coordinates of the picture, plus "denoise": bool, "pipe": "preview"
 * (default) | "full", "bypass": [...] (as the render options) ->
 * { "pipe", "input": { "colorspace", "mean": [4], "min": [4], "max": [4],
 *   "box": [x0, y0, x1, y1] } | null, "output": { ... } | null }.
 * DTB_E_ARG: bad area, or the instance is switched off. */
int dtb_color_pick(const char *op, int priority, const char *request_json, char **json);

/* Histogram of the preview pipe's picture: request_json { "max_size": n
 * (default 1024), "bypass": [...] } -> { "bins": 256, "channels": ["red",
 * "green", "blue"], "counts": [[256], [256], [256]], "max", "pixels",
 * "width", "height" }. */
int dtb_histogram(const char *request_json, char **json);

/* ---------------------------------------------------------------------- */
/* 6.5 coordinates (P1b part 2)                                            */

/* Points between the original image (the pipe's input, the space drawn
 * forms are stored in) and the processed picture, both normalised:
 * request_json { "direction": "to_processed" | "to_original",
 * "points": [ [x, y], ... ], "bypass": [...] } -> { "points": [ [x, y] |
 * null, ... ], "original": { "width", "height" }, "processed": { "width",
 * "height" } }. Through every switched-on module that moves pixels
 * (dt_dev_distort_transform_plus / _backtransform_plus, all directions). */
int dtb_coords_transform(const char *request_json, char **json);

/* ---------------------------------------------------------------------- */
/* 6.6 blending (P1b part 2)                                               */

/* The instance's blend settings by name (engine-api.md 6.6): { "supported",
 * "masks", "mask_mode_flags", "colorspace", "effective_colorspace", "mode",
 * "reverse", "parameter", "opacity", "combine": { "invert", "mode" },
 * "feathering_radius", "feathering_guide", "blur_radius", "contrast",
 * "brightness", "details", "parametric": { "<channel>": { "active",
 * "invert", "range": [a, b, c, d], "boost" } }, "raster", "mask_id",
 * "mask_lock" }. */
int dtb_blend_get(const char *op, int priority, char **json);
/* [M] Set blend settings: { "<path>": value, ... } with the names of
 * blend_get ("combine.invert", "parametric.L_in.range", ...). Checked first,
 * nothing changes if one is refused (TYPE, RANGE, ARG, NOT_FOUND). Merged
 * into one history item with following blend_set / param_set calls on the
 * same instance until dtb_history_seal(), as param_set. */
int dtb_blend_set(const char *op, int priority, const char *values_json);

/* ---------------------------------------------------------------------- */
/* 6.7 drawn masks (P1b part 2; engine-api.md 6.7 has the JSON)            */

/* Every form of the session: [ { "id", "type", "name", "version",
 * "geometry": {...}, "used_by": [ { "op", "priority", "root" } ] } ].
 * Coordinates are normalised to the original image (the pipe's input). */
int dtb_masks(char **json);
/* [M] A new form (circle, ellipse, gradient, path, brush) used by no
 * instance yet; name NULL = darktable's automatic name -> { "id" }. A brush
 * may be given as a "stroke" of points, turned into brush points as
 * desktop's brush tool does. */
int dtb_mask_create(const char *type, const char *geometry_json, const char *name, char **json);
/* [M] New geometry for a form (same type). */
int dtb_mask_update(int id, const char *geometry_json);
/* [M] */
int dtb_mask_rename(int id, const char *name);
/* [M] Delete a form and remove it from every group. DTB_E_ARG for a form
 * that is an instance's whole mask. */
int dtb_mask_delete(int id);
/* [M] Add a form (or group) to an instance's mask, as desktop's panel adds
 * a shape: the instance's flexi group is created if needed and its mask
 * switched on. options_json: { "inverse": bool, "opacity": 0..1,
 * "disabled": bool } or NULL. A form may be in several instances' masks. */
int dtb_mask_attach(const char *op, int priority, int id, const char *options_json);
/* [M] Remove a form from an instance's mask (the form stays). */
int dtb_mask_detach(const char *op, int priority, int id);
/* [M] Change inverse, opacity, disabled of a form in an instance's mask. */
int dtb_mask_member_set(const char *op, int priority, int id, const char *options_json);
/* [M] A flexi group of existing forms ([id, ...]) -> { "id" }. */
int dtb_mask_group_create(const char *member_ids_json, const char *name, char **json);
/* [M] A flexi group's own settings: { "operator": "maximum" | "screen" |
 * "minimum" | "product" | "sum" | "difference" | "exclusion", "invert",
 * "bypass", "opacity": 0..1, "name" }. An instance's mask is such a group
 * (blend_get "mask_id"). */
int dtb_mask_group_set(int id, const char *options_json);

/* ---------------------------------------------------------------------- */
/* 6.9 mask tree editing, history groups, parametric elements (P3d stage   */
/* 2a; engine-api.md 6.9 has the JSON)                                     */

/* [M] Start a history group: every change until dtb_history_group_end is one
 * undo step, and changes of one instance merge into one history item (a
 * brush session of several strokes). DTB_E_STATE if one is open. */
int dtb_history_group_begin(void);
/* End the open group: keep != 0 keeps its changes (one undo step, none if
 * nothing changed); keep == 0 restores the state it started from (cancel:
 * no history item, nothing to redo). DTB_E_STATE when none is open. Undo,
 * redo, history_goto and history_compress end an open group, keeping it. */
int dtb_history_group_end(int keep);
/* [M] A new element in an instance's mask as one history item of that
 * instance (dtb_mask_create + dtb_mask_attach in one step). request:
 * { "type": "circle" | "ellipse" | "gradient" | "path" | "brush" | "parametric",
 *   "geometry": {...}, "name"?: s, "group"?: id (a flexi group in the
 *   instance's tree; default its own group, created if needed),
 *   "member"?: { "inverse", "opacity", "disabled" } } -> { "id": n }. */
int dtb_mask_add(const char *op, int priority, const char *request_json, char **json);
/* [M] Move member `id` of flexi group `group` to `position` (0 = bottom,
 * -1 = top) of flexi group `to_group` (may be `group`). Settings go with it.
 * DTB_E_ARG: a group into itself or its own tree, deeper than darktable's
 * nesting limit, already a member of to_group; DTB_E_RANGE: position. */
int dtb_mask_member_move(int group, int id, int to_group, int position);
/* [M] Remove member `id` from flexi group `group` (any group; the form stays). */
int dtb_mask_member_remove(int group, int id);
/* [M] A member's "inverse", "opacity", "disabled" in any flexi group. For a
 * member that is a group only "disabled" (its invert and opacity are its
 * marker's: dtb_mask_group_set), else DTB_E_ARG. */
int dtb_mask_member_update(int group, int id, const char *options_json);
/* [M] "wrap in group" (one id) and "group together" (several): the members
 * `[id, ...]` of flexi group `group` go, in their order, into a new flexi
 * group (operator `op`, NULL = maximum) in the place of the topmost of them.
 * -> { "id": the new group }. */
int dtb_mask_group_wrap(int group, const char *member_ids_json, const char *op, char **json);

#ifdef __cplusplus
}
#endif

#endif /* DTBRIDGE_H */

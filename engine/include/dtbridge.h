// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * dtbridge.h: the only interface between the app and the darktable engine.
 *
 * Engine API v1, sections 1 and 2 ("Core") of docs/design/engine-api.md,
 * which is the source of truth for meaning and JSON shapes. Section 3
 * ("Extended": blending, masks, coordinates, presets, analysis) is a later
 * work package; dtb_mask_add_circle() below is the prototype from S3, kept
 * for its test until then.
 *
 * Nothing above this header may include darktable's own headers, and this
 * header exposes no darktable type.
 *
 * Model
 * - One process holds one engine (dtb_init .. dtb_shutdown) and at most one
 *   open image (dtb_open .. dtb_close), called a session.
 * - Threading: every function except dtb_render_cancel() must be called from
 *   the same thread, the "engine thread". dtb_render_cancel() may be called
 *   from any thread while a render runs on the engine thread.
 * - A module instance is addressed by its operation name `op` ("exposure")
 *   and its `priority` (darktable's multi_priority, 0 for the first
 *   instance). Priorities can change when an instance is deleted; callers
 *   re-read dtb_modules().
 * - Every function returns 0 (DTB_OK) or a negative DTB_E_* code, except
 *   dtb_shutdown, dtb_render_cancel, dtb_last_error and dtb_free.
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
 * thread id, so that threads created later inherit the set. n == 0 removes
 * the pinning (back to the CPUs the process had at dtb_init). The caller
 * chooses the CPUs (on phones: the fast cores); the bridge only applies the
 * list. DTB_E_ARG for an id that is not an allowed CPU of the process. */
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

/* Import raw_path into the private library and load xmp_path (NULL: a
 * sidecar named <raw>.xmp is picked up if it exists). The guard runs first;
 * if it fails, the image still opens, for viewing, read-only: every [M] call
 * and dtb_xmp_write return DTB_E_READ_ONLY. Returns DTB_OK in both cases
 * (see image_info "read_only"). The RAW file is only ever read. */
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
 *   temperature:    get_wb -> {temperature, tint, preset}; set_wb {temperature, tint} [M];
 *                   list_presets -> [ {name, kind, tuning_min, tuning_max, tuning_default} ];
 *                   apply_preset {name, tuning?} [M]
 *   lens:           list_cameras -> [ {maker, model, variant, mount, crop_factor} ];
 *                   list_lenses {camera} -> [ {maker, model, min_focal, max_focal} ];
 *                   set_lens {camera, lens} [M]
 *   lut3d:          set_file {path} [M]
 *   denoiseprofile: list_profiles -> [ {name, auto, iso} ]; set_profile {name} [M] */
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
 *                          "instance_name", "enabled" } ] } */
int dtb_history(char **json);
/* Show the state after the first `end` items (0 = original). A following
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
 * there is nothing to undo or redo. */
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
 * rgba8_out (w*h*4 bytes). The bridge renders a larger region, with an
 * internal margin of at least 16 px on every side that lies inside the
 * image, and crops it away, so that the region equals the same crop of a
 * full render. Do not stitch a view from several regions. */
int dtb_render_region(int x, int y, int w, int h, float scale, uint8_t *rgba8_out);

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

#ifdef __cplusplus
}
#endif

#endif /* DTBRIDGE_H */

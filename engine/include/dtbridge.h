// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * dtbridge.h: the only interface between the app and the darktable engine.
 *
 * PROTOTYPE (brief S3, Phase 0). Names and signatures may still change before
 * gate G1; nothing above this header may include darktable's own headers.
 *
 * Model
 * - One process holds one engine (dtb_init .. dtb_shutdown) and at most one
 *   open image (dtb_open .. dtb_close), called a session.
 * - Threading: every function except dtb_render_cancel() must be called from
 *   the same thread, the "engine thread". dtb_render_cancel() may be called
 *   from any thread while a render runs on the engine thread.
 * - A module instance is addressed by its operation name ("exposure") and its
 *   multi_priority (0 for the first instance). Priorities are darktable's own
 *   and can change when an instance is deleted (see dtb_instance_delete).
 * - Every function that can fail returns 0 on success or a negative DTB_E_*
 *   code; dtb_last_error() then holds a message.
 * - Strings returned as char * are JSON, owned by the caller, freed with
 *   dtb_free().
 * - Coordinates for rendering are in the processed (output) image at the
 *   given scale, as darktable's pixelpipe uses them: scale 1.0 is the full
 *   processed image after crop and distortions, scale 0.5 half of it.
 */
#ifndef DTBRIDGE_H
#define DTBRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* error codes */
#define DTB_OK              0
#define DTB_E_STATE        -1  /* wrong state: not initialised, no session, ... */
#define DTB_E_ARG          -2  /* bad argument */
#define DTB_E_NOT_FOUND    -3  /* module instance or parameter not found */
#define DTB_E_TYPE         -4  /* parameter has another type */
#define DTB_E_RANGE        -5  /* value outside the parameter's declared range */
#define DTB_E_IO           -6  /* file could not be read or written */
#define DTB_E_READ_ONLY    -7  /* refused: session or target XMP failed the version guard */
#define DTB_E_CANCELLED    -8  /* render cancelled by dtb_render_cancel() */
#define DTB_E_ENGINE       -9  /* darktable reported a failure */

/* --- lifecycle --- */

typedef struct dtb_paths_t
{
  const char *configdir;  /* darktablerc lives here; created if missing */
  const char *cachedir;   /* mipmaps and kernels cache */
  const char *datadir;    /* darktable's share/darktable */
  const char *moduledir;  /* darktable's lib/darktable (holds plugins/) */
  const char *tmpdir;
  const char *library;    /* private library database file, or ":memory:" */
  int threads;            /* OpenMP threads, 0 = darktable's default */
} dtb_paths_t;

/* Start the engine without its desktop interface (dt_init with init_gui
 * FALSE). Forces write_sidecar_files=never: the engine never writes an XMP
 * on its own; only dtb_xmp_write() does. */
int dtb_init(const dtb_paths_t *paths);
void dtb_shutdown(void);

const char *dtb_last_error(void);
const char *dtb_engine_version(void);   /* darktable's version string */
void dtb_free(void *p);

/* --- engine information and tuning (brief G0) --- */

/* Module plugins found in the module directory and loaded by the engine:
 * { "moduledir": s,
 *   "iop":     { "files": n, "loaded": n, "not_loaded": [ "<name>", ... ] },
 *   "format":  { ... },          plugins/imageio/format
 *   "storage": { ... },          plugins/imageio/storage
 *   "not_loaded_total": n,       sum over iop, format and storage
 *   "gui_only": { "views": n, "lighttable": n } }
 * Without the desktop interface darktable loads only processing modules
 * (iop), export formats and storages; view and lighttable plugins are only
 * counted. A plugin that exists as a file but did not load is listed by its
 * file name without "lib" and ".so". */
char *dtb_plugins_json(void);

/* Set the number of threads darktable's parallel loops use (OpenMP), as
 * dt_init's --threads does, while the engine runs. n <= 0 restores the count
 * in effect after dtb_init. Never more than that count: darktable sizes some
 * per-thread buffers at start-up. Returns the count now in effect, or a
 * negative DTB_E_* code. Engine thread, no render in flight. */
int dtb_set_threads(int n);
/* The count of OpenMP threads in effect now. */
int dtb_get_threads(void);

/* Drop the decoded images of the last opened image from the engine's memory
 * caches (full, downscaled and thumbnail sizes), so the next dtb_open() and
 * render start from the RAW file again. Only while no image is open. For
 * benchmarks; never touches the RAW or the XMP. */
int dtb_cache_drop(void);

/* --- version guard (PLAN.md 2.1) --- */

/* Check an XMP sidecar without opening the image. Returns a JSON object:
 * { "ok": bool, "xmp_version": n, "entries": n,
 *   "problems": [ { "num": n, "operation": s, "modversion": n,
 *                   "engine_version": n or null,
 *                   "reason": "unknown_operation" | "newer_module_version"
 *                             | "newer_blend_version" } ] }
 * *n_problems (if not NULL) receives the number of problems, or -1 if the
 * file cannot be parsed. Returns NULL only if the file cannot be read. */
char *dtb_xmp_check(const char *xmp_path, int *n_problems);

/* --- session --- */

/* Import raw_path into the private library and load xmp_path (may be NULL:
 * then only a sidecar named <raw>.xmp is picked up by the import). The XMP
 * is checked first; if it fails the guard the image is still opened, for
 * viewing, but the session is read-only: dtb_xmp_write() refuses.
 * The RAW file is only ever read. */
int dtb_open(const char *raw_path, const char *xmp_path);
int dtb_close(void);
int dtb_is_read_only(void);   /* 1 if the opened XMP failed the guard */

/* Module instances in pipeline order:
 * [ { "operation", "instance_name", "multi_priority", "iop_order",
 *     "enabled", "hidden", "version",
 *     "params": { "<name>": number | bool | "<enum constant>", ... } } ]
 * params lists scalar fields read through darktable's introspection; fields
 * inside arrays are left out. */
char *dtb_modules_json(void);

/* Set one scalar parameter by its introspection name. The module is switched
 * on, as moving a control on desktop does. Consecutive changes to the same
 * instance merge into one history item until dtb_history_seal() is called;
 * a change after dtb_open, dtb_history_goto/compress or an instance operation
 * always starts a new item. */
int dtb_param_set_float(const char *op, int multi_priority, const char *param, float value);
int dtb_param_set_int(const char *op, int multi_priority, const char *param, int value);
int dtb_param_set_bool(const char *op, int multi_priority, const char *param, int value);
int dtb_set_enabled(const char *op, int multi_priority, int enabled);

/* Close the current history item: the next change starts a new one. */
void dtb_history_seal(void);

/* --- instances --- */

/* New instance with default parameters, placed after the base instance.
 * *new_priority receives its multi_priority. */
int dtb_instance_new(const char *op, int multi_priority, int *new_priority);
/* Same, copying parameters, blending and (shared) masks from the base. */
int dtb_instance_duplicate(const char *op, int multi_priority, int *new_priority);
/* Delete an instance and every history item that refers to it, as desktop
 * does. The last instance of a module cannot be deleted. If priority 0 is
 * deleted, the next instance becomes priority 0. */
int dtb_instance_delete(const char *op, int multi_priority);
/* Move one step in the pipeline: direction +1 = later (up in darktable's
 * panel), -1 = earlier. Skips hidden modules; refuses moves the pipeline
 * rules forbid. As on desktop, moving switches the module on. */
int dtb_instance_move(const char *op, int multi_priority, int direction);

/* --- history --- */

/* { "history_end": n, "items": [ { "num", "operation", "multi_priority",
 *   "instance_name", "enabled" } ] } */
char *dtb_history_json(void);
/* Show the state after the first `end` items (0 = original). A following
 * change discards the items above `end`, as on desktop. */
int dtb_history_goto(int end);
/* Compress history (darktable's dt_history_compress_on_image). */
int dtb_history_compress(void);

/* --- masks (prototype, for test T9 only) --- */

/* Give an instance a drawn circle mask. cx, cy, radius and border are in
 * darktable's normalised image coordinates (0..1 of the full input image). */
int dtb_mask_add_circle(const char *op, int multi_priority,
                        float cx, float cy, float radius, float border);

/* --- rendering --- */

/* Size of the processed image at `scale`. */
int dtb_render_size(float scale, int *width, int *height);

/* Render region (x, y, w, h) of the processed image at `scale` into rgba8_out
 * (w*h*4 bytes, RGBA, A = 255), in the output colour profile set in the
 * history (sRGB by default), processed like an export: same pipeline type
 * and same 8-bit conversion as darktable-cli. Uses a pipeline with a cache,
 * kept across calls, so a re-render after a change re-runs only the modules
 * from the first changed one on. */
int dtb_render(int x, int y, int w, int h, float scale, uint8_t *rgba8_out);

/* Fast preview of the whole image, fitting in max_size x max_size, rendered
 * by a second cached pipeline from darktable's downscaled input (the mipmap
 * the darkroom's preview uses). *out is allocated with malloc and freed with
 * dtb_free(). */
int dtb_render_preview(int max_size, uint8_t **out, int *width, int *height);

/* Ask the render running on the engine thread to stop. Safe from any thread.
 * A cancel that arrives at any point during dtb_render() or
 * dtb_render_preview() makes that call return DTB_E_CANCELLED (the output
 * buffer is then undefined); the pipeline stops between modules. A cancel
 * that arrives while no render runs has no effect: each render starts by
 * clearing it. Must not be called concurrently with dtb_close(). */
void dtb_render_cancel(void);

/* --- output --- */

/* Write the session's history as a darktable XMP sidecar to xmp_path.
 * Refused (DTB_E_READ_ONLY) if the session is read-only or if an existing
 * file at xmp_path fails the version guard. */
int dtb_xmp_write(const char *xmp_path);

/* Export the current edit as JPEG through darktable's own export path
 * (as darktable-cli does). max_width/max_height 0 = full size. */
int dtb_export_jpeg(const char *path, int quality, int max_width, int max_height);

#ifdef __cplusplus
}
#endif

#endif /* DTBRIDGE_H */

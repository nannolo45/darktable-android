// SPDX-License-Identifier: GPL-3.0-or-later
/* Shared between the bridge's source files; never included above the bridge.
 * This is the only place where the bridge's own state meets darktable's
 * types. */
#ifndef DTB_INTERNAL_H
#define DTB_INTERNAL_H

#include "dtbridge.h"

#include "common/darktable.h"
#include "common/introspection.h"
#include "common/mipmap_cache.h"
#include "develop/develop.h"
#include "develop/imageop.h"
#include "develop/pixelpipe_hb.h"

#include <glib.h>
#include <json-glib/json-glib.h>
#include <string.h>

/* one cached pixelpipe and the input buffer it holds a read lock on */
typedef struct dtb_pipe_t
{
  gboolean ready;
  dt_dev_pixelpipe_t pipe;
  dt_mipmap_buffer_t buf;
  dt_mipmap_size_t size;
  GHashTable *shape_hash;  // piece -> dtb_shape_hash_t, see render.c _shapes_into_hash
} dtb_pipe_t;

/* one whole-history state for undo and redo, kept in darktable's
 * memory.snapshot_* tables (common/history_snapshot.c) */
typedef struct dtb_undo_entry_t
{
  int snap_id;      // id in memory.snapshot_*
  int n_items;      // history items in the snapshot
  int history_end;  // history_end of that state
  // the image's history hash row at that state (main.history_hash: basic,
  // auto, current), restored with it (P1b part 2, engine-api.md 6.2)
  guint8 *hash[3];
  int hash_len[3];
  gboolean changed_tag;  // "darktable|changed" attached at that state
} dtb_undo_entry_t;

/* one entry of the history as the XMP gave it, for read-only sessions whose
 * XMP names items the engine could not load (P1c item 1) */
typedef struct dtb_row_t
{
  int dev_index;        // position in dev.history, -1: not loaded by the engine
  char op[64];          // operation name as written in the XMP
  int priority;
  char *instance_name;  // g_malloc
  gboolean enabled;
} dtb_row_t;

typedef struct dtb_session_t
{
  gboolean open;
  dt_imgid_t imgid;
  dt_develop_t dev;
  gboolean read_only;
  // history grouping: the module the last parameter change went to, NULL
  // after any other change or a seal (the next change starts a new item)
  dt_iop_module_t *merge_module;
  dtb_pipe_t full;     // regions and full renders, from the full-size input
  dtb_pipe_t preview;  // whole-image preview, from the downscaled input
  GQueue undo;         // of dtb_undo_entry_t *, newest at the tail
  GQueue redo;
  int next_snap_id;
  // read-only sessions with entries the engine could not load: the history
  // as the XMP lists it (GArray of dtb_row_t), and its end in those rows.
  // NULL otherwise: history() then reads dev.history.
  GArray *rows;
  int rows_end;
  // dtb_history_group_begin .. _end (engine-api.md 6.9): open, and whether a
  // change inside it recorded the undo state it started from
  gboolean group_open;
  gboolean group_changed;
} dtb_session_t;

/* speed.c (brief P4b) */
int dtb__speed_options(const char *json, gchar **argv, int *argc, int max_argc);
void dtb__speed_after_init(void);
void dtb__speed_pipe_made(dtb_pipe_t *p, gboolean full);
void dtb__speed_render_begin(void);
void dtb__speed_render_end(void);

/* core.c */
extern dtb_session_t dtb_S;
extern gboolean dtb_inited;
int dtb__err(int code, const char *fmt, ...) G_GNUC_PRINTF(2, 3);
/* finish a JsonBuilder into a string (g_malloc); unrefs the builder */
char *dtb__json_finish(JsonBuilder *b);
/* a copy of n bytes (g_free); g_memdup2 is newer than darktable's GLib floor */
static inline void *dtb__memdup(const void *p, size_t n)
{
  void *r = g_malloc(n);
  memcpy(r, p, n);
  return r;
}
/* parse JSON text; NULL and an error set on failure (caller frees with json_node_unref) */
JsonNode *dtb__json_parse(const char *text);

#define NEED_INIT()                                                          \
  do { if(!dtb_inited) return dtb__err(DTB_E_STATE, "engine not initialised"); } while(0)
#define NEED_SESSION()                                                       \
  do { NEED_INIT();                                                          \
       if(!dtb_S.open) return dtb__err(DTB_E_STATE, "no image open"); } while(0)
#define NEED_WRITABLE()                                                      \
  do { NEED_SESSION();                                                       \
       if(dtb_S.read_only)                                                   \
         return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard"); \
  } while(0)

/* Step timers for measurements (P1c item 5). With DTB_TIMING set in the
 * environment, dtb__tlog() prints "DTB_TIMING <what> <ms>" to stderr; without
 * it the timers cost a clock read and a branch. Not part of the API. */
double dtb__t(void);
void dtb__tlog(const char *what, double t0);

/* guard.c: needs an initialised engine (compares with the loaded module
 * libraries). Returns a JSON string (g_malloc) or NULL if the file cannot be
 * read; *n_problems = -1 if it cannot be parsed. */
char *dtb__xmp_check(const char *xmp_path, int *n_problems);

/* session.c */
dt_iop_module_t *dtb__find(const char *op, int priority);
/* tear the develop object down and load it again from the library, after
 * the library history was changed underneath it */
void dtb__reload_dev(void);

/* number of loaded items among the first `row_end` rows (read-only view) */
int dtb__rows_dev_end(int row_end);

/* history.c: every change to the edit goes through these */
typedef enum dtb_change_t
{
  DTB_CHANGE_PARAMS,  // may merge into the open item of the same instance
  DTB_CHANGE_OTHER,   // always its own step
} dtb_change_t;
/* call before changing the develop object: seals the open item unless a
 * parameter change continues on `m`, and records an undo state for a new
 * step */
void dtb__change_begin(dtb_change_t kind, dt_iop_module_t *m);
/* call after the change: for DTB_CHANGE_PARAMS keeps the item open for `m` */
void dtb__change_end(dtb_change_t kind, dt_iop_module_t *m);
/* record the module's state in history, as desktop's dt_dev_add_history_item
 * does without its interface work, and flag the pipes */
void dtb__history_add(dt_iop_module_t *m, gboolean enable);
/* the same for a change of the module's parameters only (param_set,
 * params_set): lets the pipes resynchronise just that module */
void dtb__history_add_params(dt_iop_module_t *m, gboolean enable);
void dtb__undo_clear(void);

/* render.c: render options (engine-api.md 6.1) */
typedef struct dtb_render_opts_t
{
  GPtrArray *bypass;            // dt_iop_module_t *, left out for one call; NULL = none
  dt_iop_module_t *show_mask;   // "show mask" overlay of this instance; NULL = none
  int history_end;              // P3h: render this history position; -1 = the session's
} dtb_render_opts_t;
/* P3h (engine-api.md 8): the develop object at another history position for
 * one render, by the steps history_goto takes (library write, reload) but
 * without closing an open group or the open item; _end goes back the same
 * way and gives the session its merge state back (history.c) */
typedef struct dtb_history_at_t
{
  gboolean active;
  int saved_end;         // the develop object's history_end before
  gboolean sealed;       // dev.history_sealed before
  char merge_op[64];     // the module the open item belongs to ("" = none)
  int merge_priority;
} dtb_history_at_t;
int dtb__history_at_begin(const dtb_render_opts_t *o, dtb_history_at_t *h);
void dtb__history_at_end(dtb_history_at_t *h);
#define DTB_BYPASS_MAX 16
typedef struct dtb_bypass_t
{
  dtb_pipe_t *pipe;
  int n;
  dt_dev_pixelpipe_iop_t *piece[DTB_BYPASS_MAX];
  dt_hash_t hash[DTB_BYPASS_MAX];
  int processed_width, processed_height;
} dtb_bypass_t;
/* parse {"bypass": [...], "show_mask": {...}}; NULL or "" = none */
int dtb__render_opts_parse(const char *json, dtb_render_opts_t *out);
/* the same members of an object other calls take (pickers, coordinates) */
int dtb__render_opts_from(JsonObject *o, dtb_render_opts_t *out);
void dtb__render_opts_clear(dtb_render_opts_t *o);
gboolean dtb__render_opts_empty(const dtb_render_opts_t *o);
/* on a synchronised pipe: leave the bypassed pieces out until _end */
void dtb__bypass_begin(dtb_pipe_t *p, const dtb_render_opts_t *o, dtb_bypass_t *saved);
void dtb__bypass_end(dtb_bypass_t *saved);
/* masks display (engine-api.md 6.8): set up the pipe for one render */
typedef struct dtb_mask_display_t
{
  gboolean active;
  dtb_pipe_t *pipe;
  dt_iop_module_t *module;
  void *saved[8];
  int saved_int[8];
  void *piece;     // dt_dev_pixelpipe_iop_t * of the module
  dt_hash_t hash;  // its hash before the render
} dtb_mask_display_t;
int dtb__mask_display_begin(dtb_pipe_t *p, const dtb_render_opts_t *o, dtb_mask_display_t *md);
void dtb__mask_display_end(dtb_mask_display_t *md);
/* TRUE while a mask display render runs: the pipe runs with gamma */
extern gboolean dtb__mask_display_active;
/* times.c calls it after every process_plain: the mask channel kept */
void dtb__mask_display_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const i, void *const o,
                            const dt_iop_roi_t *const roi_in, const dt_iop_roi_t *const roi_out);
/* run a ready pipe like an export (render.c _pipe_run): float output in
 * pipe.backbuf, 8-bit copy in out (w*h*4) */
int dtb__pipe_run(dtb_pipe_t *p, int x, int y, int w, int h, float scale, uint8_t *out);

/* pickers.c (6.3): the statistics of one pick; [0] input, [1] output */
typedef struct dtb_pick_result_t
{
  gboolean have[2];
  int cst[2];  // dt_iop_colorspace_type_t of the numbers
  float mean[2][4], min[2][4], max[2][4];
  int box[2][4];
  /* P4c: the pipe's working profile (dt_ioppr_get_pipe_work_profile_info) and,
   * for each side in RGB, what rgb curve's picker_scale makes of the numbers:
   * luminance (dt_ioppr_get_rgb_matrix_luminance), each channel and the
   * luminance through dt_ioppr_compensate_middle_grey; [s][0..2] = mean, min, max */
  gboolean work;
  int work_type;
  char work_file[256];
  gboolean work_nonlinear;
  float work_y[3];
  gboolean work_have[2];
  float work_lum[2][3], work_grey[2][3][3], work_grey_lum[2][3];
} dtb_pick_result_t;
/* pick on module m: area normalised in the picture (x0, y0, x1, y1) */
int dtb__pick(dt_iop_module_t *m, const float area[4], gboolean denoise, gboolean full,
              const dtb_render_opts_t *o, dtb_pick_result_t *res);
/* brief P6a: dtb__pick with the picker's colour space given (dt_iop_colorspace_type_t), for a module whose pickers
 * differ in it */
int dtb__pick_cst(dt_iop_module_t *m, const float area[4], gboolean denoise, int cst, dtb_pick_result_t *res);
/* brief P6a: one render in which m runs, fn called on its input buffer (pickers.c) */
typedef void (*dtb_input_fn)(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const float *in,
                             const dt_iop_roi_t *roi_in, void *user);
int dtb__module_input(dt_iop_module_t *m, dtb_input_fn fn, void *user);
/* brief P6a: an area of the picture mapped back to the pipe's input (normalised), as desktop's picker box */
void dtb__area_to_input(const float area[4], float box[4]);
/* brief P6a: module actions added by P6a (p6a_actions.c, p6a_agx.c); 1 = no such action here */
int dtb__module_action_p6a(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);
int dtb__module_action_agx(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);
/* "area": [x0, y0, x1, y1] or "point": [x, y] of a request or action arguments */
int dtb__read_area(JsonObject *o, float area[4]);
/* {"input": ..., "output": ...} */
void dtb__pick_json(JsonBuilder *b, const dtb_pick_result_t *res);
/* times.c calls it after every process_plain */
void dtb__pick_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const i, void *const o,
                    const dt_iop_roi_t *const roi_in, const dt_iop_roi_t *const roi_out);

/* make sure a pipe exists and is synchronised: full (DT_MIPMAP_FULL) or preview (DT_MIPMAP_F) */
int dtb__pipe_ready(gboolean full, dtb_pipe_t **out);

/* render.c */
void dtb__pipes_dirty(dt_dev_pixelpipe_change_t what);
void dtb__pipes_free(void);
/* after dtb__reload_dev: point the pipes at the new develop object */
void dtb__pipes_reattach(void);
void dtb__pipes_detach(void);
/* make sure the full pipe exists (loads the full image) */
int dtb__full_pipe(void);
extern dtb_pipe_t *dtb__full_pipe_ptr(void);

/* params.c: introspection paths */
typedef struct dtb_field_ref_t
{
  dt_introspection_field_t *f;
  uint8_t *p;  // address of the field inside the params blob
} dtb_field_ref_t;
/* resolve `path` inside params (a blob laid out as module m's params) */
int dtb__field_resolve(dt_iop_module_t *m, void *params, const char *path, dtb_field_ref_t *out);
/* check and store a JSON value into a resolved field */
int dtb__field_set(const char *what, const dtb_field_ref_t *r, JsonNode *value);
/* write a field value into a builder */
void dtb__field_emit(JsonBuilder *b, dt_introspection_field_t *f, const uint8_t *p);
/* convenience for actions: a float/bool/int/char-array field by path */
float *dtb__param_float(dt_iop_module_t *m, const char *path);
int *dtb__param_int(dt_iop_module_t *m, const char *path);
char *dtb__param_chars(dt_iop_module_t *m, const char *path, size_t *size);

/* presets.c */
dt_iop_module_so_t *dtb__iop_so(const char *op);
const char *dtb__item_problem(const char *op, int module_version, int blend_version);

/* times.c (E2) */
void dtb__times_install(void);
void dtb__times_begin(const char *pipe);
void dtb__times_end(void);
void dtb__times_reset(void);

/* output.c (P3g): times.c calls it before every process_plain; progress and
 * cancel of an export in flight, nothing otherwise */
void dtb__export_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece);

/* actions2.c: 1 = no such action there */
int dtb__module_action2(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);

/* masks.c, for retouch.c (P3m): a form's geometry JSON, points from geometry
 * JSON, a new form in the session (no history), control points, one masks
 * history item for m, an [x, y] member of o */
struct dt_masks_form_t;
void dtb__mask_geometry(JsonBuilder *b, const struct dt_masks_form_t *f);
GList *dtb__mask_points(int type, JsonObject *g, int *err);
struct dt_masks_form_t *dtb__mask_form_new(int type, GList *pts, const char *name);
void dtb__mask_init_ctrl(struct dt_masks_form_t *f);
void dtb__mask_record(dt_iop_module_t *m);
int dtb__mask_pair(JsonObject *o, const char *k, float *v);

/* masks.c: desktop's dt_masks_iop_use_same_as on the session's forms (no history) */
gboolean dtb__masks_use_same_as(dt_iop_module_t *m, dt_iop_module_t *src);

/* blend.c: for parametric mask elements (masks.c, engine-api.md 6.9) */
/* csp: a dt_develop_blend_colorspace_t (develop/blend.h is not included here) */
int dtb__blend_cst(dt_iop_module_t *m);
const char *dtb__blend_cst_name(int csp);
const char *dtb__blendif_name(int csp, int index);
float dtb__boost_min(int csp, int index);

/* history.c: the open history group (dtb_history_group_begin), if any, is
 * closed and kept; undo, redo, history_goto and compress call it first */
void dtb__group_commit(void);

/* P3j (engine-api.md 9): session.c's default edit for an image of the
 * library; output.c's atomic XMP write of any image of the library (no
 * guard: callers check); copypaste.c's clipboard, at init and shutdown */
void dtb__reset_to_default_edit(const dt_imgid_t imgid);
int dtb__xmp_write_image(const dt_imgid_t imgid, const char *xmp_path);
void dtb__clipboard_init(void);
dt_imgid_t dtb__image_id(const dt_filmid_t film, const char *filename);
void dtb__clipboard_drop(void);

/* actions3.c: module actions that pick (P1b part 2); 1 = no such action there */
int dtb__module_action3(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);

/* actions.c */
int dtb__module_action(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);

/* perspective.c (P3n): rotate and perspective's structure, guides and fit.
 * After a bridge pipe synchronised: the focused instance's piece shows the
 * whole picture (commit_params with the focus); after a module's process:
 * ashift's copy of its input on the preview pipe; at close: the interface
 * data dropped */
void dtb__ashift_after_sync(dtb_pipe_t *p);
void dtb__ashift_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const i,
                      const dt_iop_roi_t *const roi_in);
void dtb__ashift_drop(void);

/* retouch.c (P4c): retouch's focus. While a retouch instance has the focus
 * (dtb_retouch_focus "begin", not paused) every bridge render, size,
 * coordinate transform and picker leaves out the modules desktop skips then
 * (imageop.c dt_iop_module_is_skipped: after the focused module in the pipe,
 * operation_tags() & its operation_tags_filter(), here decoration and
 * cropping), as dtb__bypass_begin does for "bypass". At close: dropped. */
gboolean dtb__focus_active(void);
gboolean dtb__focus_skips(const dt_iop_module_t *m);
void dtb__focus_drop(void);

#endif

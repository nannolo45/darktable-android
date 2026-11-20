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
} dtb_session_t;

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
} dtb_render_opts_t;
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
} dtb_pick_result_t;
/* pick on module m: area normalised in the picture (x0, y0, x1, y1) */
int dtb__pick(dt_iop_module_t *m, const float area[4], gboolean denoise, gboolean full,
              const dtb_render_opts_t *o, dtb_pick_result_t *res);
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

/* actions2.c: 1 = no such action there */
int dtb__module_action2(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);

/* actions3.c: module actions that pick (P1b part 2); 1 = no such action there */
int dtb__module_action3(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);

/* actions.c */
int dtb__module_action(dt_iop_module_t *m, const char *action, JsonObject *args, char **result);

#endif

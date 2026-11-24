// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: copy and (selective) paste of the history between photos
 * (docs/design/engine-api.md section 9, brief P3j).
 *
 * Desktop's copy does not copy anything: it remembers the image
 * (darktable.view_manager->copy_paste) and the paste reads that image's
 * history from the library when it runs (common/history.c dt_history_copy,
 * dt_history_paste). The engine holds one photo at a time and reads every
 * photo's history from its XMP into the library when it opens it, so the
 * copied history is kept under an image of its own in the private library:
 * the clipboard image, a library duplicate of the copied photo
 * (dt_image_duplicate_with_version, version 32000; dtb__image_id never returns it)
 * and that holds a verbatim copy of the photo's history rows, masks, module
 * order and history end, made with upstream's overwrite paste of the whole
 * history (copy_full). It lives until the next copy or the engine's end,
 * and outlives the photo's session.
 *
 * Both the copy into the clipboard image and every paste go through
 * upstream's dt_history_copy_and_paste_on_image() itself, so selection,
 * append (merge) and overwrite, module order, masks and locked masks are
 * desktop's. Its interface parts do nothing without the interface (no view
 * manager: dt_view_get_current() is the lighttable, darktable.develop holds
 * no image, darktable.undo is NULL so dt_undo_record() returns), except one:
 * its undo record first takes two snapshots of the target's history in
 * memory.snapshot_* (dt_history_snapshot_undo_create), the tables the
 * bridge's own undo uses (history.c). They are never used and are cleared
 * right after the call, so they cannot collide with the bridge's snapshot
 * ids.
 */
#include "dtb_internal.h"

#include "common/database.h"
#include "common/debug.h"
#include "common/exif.h"
#include "common/film.h"
#include "common/history.h"
#include "common/history_snapshot.h"
#include "common/image.h"
#include "common/image_cache.h"
#include "common/iop_order.h"
#include "common/mipmap_cache.h"
#include "control/conf.h"
#include "develop/imageop.h"

#include <string.h>

/* the clipboard image: a version no photo of the library has */
#define CLIP_VERSION 32000

static dt_imgid_t g_clip = NO_IMGID;
// the photo it was copied from: desktop does not paste onto it (dt_history_copy_and_paste_on_image)
static dt_imgid_t g_src = NO_IMGID;
// desktop's dt_history_copy_item_t: selops (NULL = the whole history),
// full_copy, copy_iop_order
static GList *g_ops = NULL;
static gboolean g_full = FALSE;
static gboolean g_order = TRUE;

/* a photo of the library by film and file name, never the clipboard image (dt_image_get_id() takes whichever
 * row of that name comes first) */
dt_imgid_t dtb__image_id(const dt_filmid_t film, const char *filename)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT id FROM main.images WHERE film_id = ?1 AND filename = ?2 AND version != ?3"
                              " ORDER BY id LIMIT 1", -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, film);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, filename, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 3, CLIP_VERSION);
  const dt_imgid_t id = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : NO_IMGID;
  sqlite3_finalize(stmt);
  return id;
}

static void _remove_clipboard_images(void)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT id FROM main.images WHERE version = ?1", -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, CLIP_VERSION);
  GList *ids = NULL;
  while(sqlite3_step(stmt) == SQLITE_ROW) ids = g_list_prepend(ids, GINT_TO_POINTER(sqlite3_column_int(stmt, 0)));
  sqlite3_finalize(stmt);
  for(GList *l = ids; l; l = g_list_next(l)) dt_image_remove(GPOINTER_TO_INT(l->data));
  g_list_free(ids);
}

void dtb__clipboard_init(void)
{
  // a process that ended without dtb_shutdown left its clipboard image
  _remove_clipboard_images();
  g_clip = NO_IMGID;
}

void dtb__clipboard_drop(void)
{
  _remove_clipboard_images();
  g_clip = NO_IMGID;
  g_list_free(g_ops);
  g_ops = NULL;
}

/* a new clipboard image for the photo `src` */
static dt_imgid_t _clipboard_new(const dt_imgid_t src)
{
  _remove_clipboard_images();
  g_clip = NO_IMGID;
  const dt_imgid_t id = dt_image_duplicate_with_version(src, CLIP_VERSION);
  if(!dt_is_valid_imgid(id)) return NO_IMGID;
  // not renamed (a first version renamed it; C29 on arw then lost a multi-instance that desktop's path keeps,
  // and a clipboard image that is not renamed does not): photos are looked up without it (dtb__image_id)
  return id;
}

static int _snap_max(const dt_imgid_t imgid)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT MAX(id) FROM memory.snapshot_history WHERE imgid = ?1", -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, imgid);
  const int m = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : 0;
  sqlite3_finalize(stmt);
  return m;
}

/* upstream's paste, and the two undo snapshots it leaves cleared (see above) */
static gboolean _paste(const dt_imgid_t src, const dt_imgid_t dest, const gboolean merge, GList *ops,
                       const gboolean order, const gboolean full)
{
  const int before = _snap_max(dest);
  const gboolean ok = dt_history_copy_and_paste_on_image(src, dest, merge, ops, order, full, FALSE);
  // dt_history_snapshot_undo_create numbers them MAX(id) + 1: before + 1,
  // and before + 2 when the first one held history rows
  dt_history_snapshot_clear(dest, before + 1);
  dt_history_snapshot_clear(dest, before + 2);
  return ok;
}

/* ------------------------------------------------------------------ */
/* the items desktop's "selective copy" / "selective paste" dialog lists */

/* gui/hist_dialog.c dt_gui_hist_dialog_new: dt_history_get_items(imgid,
 * FALSE, TRUE, ...) without the hidden modules, in its order (one entry per
 * instance). Returns the list (dt_history_item_t) */
static GList *_dialog_items(const dt_imgid_t imgid)
{
  GList *items = dt_history_get_items(imgid, FALSE, TRUE, FALSE);
  GList *r = NULL;
  for(GList *l = items; l; l = g_list_next(l))
  {
    dt_history_item_t *it = l->data;
    if(dt_iop_get_module_flags(it->op) & IOP_FLAGS_HIDDEN)
      dt_history_item_free(it);
    else
      r = g_list_prepend(r, it);
  }
  g_list_free(items);
  return g_list_reverse(r);
}

static void _item_json(JsonBuilder *b, const dt_imgid_t imgid, const dt_history_item_t *it, const int selected)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT multi_priority, multi_name FROM main.history WHERE imgid = ?1 AND num = ?2",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, imgid);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 2, it->num);
  int prio = 0;
  gchar *iname = NULL;
  if(sqlite3_step(stmt) == SQLITE_ROW)
  {
    prio = sqlite3_column_int(stmt, 0);
    iname = g_utf8_make_valid(sqlite3_column_text(stmt, 1) ? (const char *)sqlite3_column_text(stmt, 1) : "", -1);
  }
  sqlite3_finalize(stmt);
  const int flags = dt_iop_get_module_flags(it->op);
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "num");
  json_builder_add_int_value(b, it->num);
  json_builder_set_member_name(b, "op");
  json_builder_add_string_value(b, it->op);
  json_builder_set_member_name(b, "priority");
  json_builder_add_int_value(b, prio);
  json_builder_set_member_name(b, "name");
  gchar *name = g_utf8_make_valid(it->name ? it->name : it->op, -1);
  json_builder_add_string_value(b, name);
  g_free(name);
  json_builder_set_member_name(b, "instance_name");
  json_builder_add_string_value(b, iname ? iname : "");
  g_free(iname);
  json_builder_set_member_name(b, "enabled");
  json_builder_add_boolean_value(b, it->enabled);
  json_builder_set_member_name(b, "mask");
  json_builder_add_boolean_value(b, it->mask_mode > 0);
  json_builder_set_member_name(b, "safe");
  json_builder_add_boolean_value(b, !dt_history_module_skip_copy(flags));
  if(selected >= 0)
  {
    json_builder_set_member_name(b, "selected");
    json_builder_add_boolean_value(b, selected);
  }
  json_builder_end_object(b);
}

static void _items_json(JsonBuilder *b, const dt_imgid_t imgid, const gboolean with_selection)
{
  GList *items = _dialog_items(imgid);
  json_builder_set_member_name(b, "items");
  json_builder_begin_array(b);
  for(GList *l = items; l; l = g_list_next(l))
  {
    const dt_history_item_t *it = l->data;
    int sel = -1;
    if(with_selection)
    {
      // hist_dialog.c _gui_is_set: every item when nothing was chosen
      sel = g_ops == NULL;
      for(GList *o = g_ops; o && !sel; o = g_list_next(o)) sel = abs(GPOINTER_TO_INT(o->data)) == (int)it->num;
    }
    _item_json(b, imgid, it, sel);
  }
  json_builder_end_array(b);
  g_list_free_full(items, dt_history_item_free);
  json_builder_set_member_name(b, "module_order");
  json_builder_add_string_value(b, dt_iop_order_string(dt_ioppr_get_iop_order_version(imgid)));
}

int dtb_history_copy_items(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  dt_dev_write_history(&dtb_S.dev);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  _items_json(b, dtb_S.imgid, FALSE);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

int dtb_history_clipboard(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  if(!dt_is_valid_imgid(g_clip))
  {
    *json = g_strdup("null");
    return DTB_OK;
  }
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "parts");
  json_builder_add_boolean_value(b, g_ops != NULL);
  json_builder_set_member_name(b, "copy_module_order");
  json_builder_add_boolean_value(b, g_order);
  _items_json(b, g_clip, TRUE);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* options                                                            */

/* the nums of a JSON array member, each one of the listed items; -1 = not
 * an array of such nums (error set) */
static int _nums(JsonObject *o, const char *member, GList *items, GHashTable *into)
{
  JsonNode *n = json_object_get_member(o, member);
  if(JSON_NODE_TYPE(n) != JSON_NODE_ARRAY)
    return dtb__err(DTB_E_ARG, "\"%s\" must be an array of history nums", member), -1;
  JsonArray *a = json_node_get_array(n);
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonNode *e = json_array_get_element(a, i);
    if(JSON_NODE_TYPE(e) != JSON_NODE_VALUE || json_node_get_value_type(e) != G_TYPE_INT64)
      return dtb__err(DTB_E_ARG, "\"%s\" must be an array of history nums", member), -1;
    const gint64 v = json_node_get_int(e);
    gboolean listed = FALSE;
    for(GList *l = items; l && !listed; l = g_list_next(l)) listed = ((dt_history_item_t *)l->data)->num == v;
    if(!listed)
      return dtb__err(DTB_E_ARG, "\"%s\": %" G_GINT64_FORMAT " is not one of the listed items", member, v), -1;
    g_hash_table_add(into, GINT_TO_POINTER((int)v));
  }
  return 0;
}

/* a selection ({"items": [...], "reset": [...]}) of the items of `imgid` as
 * desktop's dialog returns it (_gui_hist_get_active_items: in the list's
 * order, reset items as -num). *ops = NULL and 0 when `o` has no "items" */
static int _selection(JsonObject *o, const dt_imgid_t imgid, GList **ops)
{
  *ops = NULL;
  if(!o || !json_object_has_member(o, "items")) return 0;
  GList *items = _dialog_items(imgid);
  GHashTable *sel = g_hash_table_new(NULL, NULL), *reset = g_hash_table_new(NULL, NULL);
  int r = _nums(o, "items", items, sel);
  if(!r && json_object_has_member(o, "reset")) r = _nums(o, "reset", items, reset);
  if(!r && g_hash_table_size(sel) == 0) r = dtb__err(DTB_E_ARG, "no item chosen");
  if(!r)
  {
    GList *res = NULL;
    for(GList *l = items; l; l = g_list_next(l))
    {
      const int num = ((dt_history_item_t *)l->data)->num;
      if(g_hash_table_contains(sel, GINT_TO_POINTER(num)))
        res = g_list_prepend(res, GINT_TO_POINTER(g_hash_table_contains(reset, GINT_TO_POINTER(num)) ? -num : num));
    }
    *ops = g_list_reverse(res);
  }
  g_hash_table_destroy(sel);
  g_hash_table_destroy(reset);
  g_list_free_full(items, dt_history_item_free);
  return r ? DTB_E_ARG : 0;
}

static JsonNode *_options(const char *options_json, JsonObject **o)
{
  *o = NULL;
  if(!options_json) return NULL;
  JsonNode *n = dtb__json_parse(options_json);
  if(n && JSON_NODE_TYPE(n) == JSON_NODE_OBJECT) *o = json_node_get_object(n);
  else if(n && JSON_NODE_TYPE(n) == JSON_NODE_NULL) {}
  else
  {
    if(n) json_node_unref(n);
    dtb__err(DTB_E_ARG, "options must be a JSON object or null");
    return (JsonNode *)-1;
  }
  return n;
}

/* ------------------------------------------------------------------ */
/* copy                                                               */

int dtb_history_copy(const char *options_json)
{
  NEED_WRITABLE();
  JsonObject *o;
  JsonNode *n = _options(options_json, &o);
  if(n == (JsonNode *)-1) return DTB_E_ARG;
  gboolean order = TRUE;
  if(o && json_object_has_member(o, "module_order"))
  {
    JsonNode *m = json_object_get_member(o, "module_order");
    if(JSON_NODE_TYPE(m) != JSON_NODE_VALUE || json_node_get_value_type(m) != G_TYPE_BOOLEAN)
    {
      json_node_unref(n);
      return dtb__err(DTB_E_ARG, "\"module_order\" must be true or false");
    }
    order = json_node_get_boolean(m);
  }
  // the session's history (items above its end included) into the library,
  // as dt_history_copy() does for the darkroom's image
  dt_dev_write_history(&dtb_S.dev);
  GList *ops = NULL;
  if(_selection(o, dtb_S.imgid, &ops))
  {
    if(n) json_node_unref(n);
    return DTB_E_ARG;
  }
  const gboolean parts = o && json_object_has_member(o, "items");
  if(n) json_node_unref(n);
  const dt_imgid_t clip = _clipboard_new(dtb_S.imgid);
  if(!dt_is_valid_imgid(clip))
  {
    g_list_free(ops);
    return dtb__err(DTB_E_ENGINE, "cannot make the clipboard image");
  }
  // a verbatim copy of the photo's history: upstream's overwrite paste of
  // the whole stack with copy_full (nothing skipped) and the module order
  if(!_paste(dtb_S.imgid, clip, FALSE, NULL, TRUE, TRUE))
  {
    g_list_free(ops);
    dtb__clipboard_drop();
    return dtb__err(DTB_E_ENGINE, "copying the history to the clipboard failed");
  }
  g_clip = clip;
  g_src = dtb_S.imgid;
  g_list_free(g_ops);
  g_ops = ops;
  // dt_history_copy: full_copy FALSE, copy_iop_order TRUE;
  // dt_history_copy_parts: full_copy TRUE, the dialog's module order box
  g_full = parts;
  g_order = parts ? order : TRUE;
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* paste                                                              */

typedef struct _paste_t
{
  gboolean merge;
} _paste_t;

/* mode and, for a selective paste, the new selection (kept, as desktop's
 * dialog keeps it in copy_paste.selops) */
static int _paste_options(const char *options_json, _paste_t *p)
{
  if(!dt_is_valid_imgid(g_clip)) return dtb__err(DTB_E_STATE, "nothing copied");
  JsonObject *o;
  JsonNode *n = _options(options_json, &o);
  if(n == (JsonNode *)-1) return DTB_E_ARG;
  p->merge = dt_conf_get_int("plugins/lighttable/copy_history/pastemode") != DT_HISTORY_COPY_OVERWRITE;
  if(o && json_object_has_member(o, "mode") && !json_object_get_null_member(o, "mode"))
  {
    const char *mode = json_object_get_string_member(o, "mode");
    if(mode && !strcmp(mode, "append")) p->merge = TRUE;
    else if(mode && !strcmp(mode, "overwrite")) p->merge = FALSE;
    else
    {
      json_node_unref(n);
      return dtb__err(DTB_E_ARG, "mode must be \"append\", \"overwrite\" or null");
    }
  }
  GList *ops = NULL;
  if(_selection(o, g_clip, &ops))
  {
    if(n) json_node_unref(n);
    return DTB_E_ARG;
  }
  if(o && json_object_has_member(o, "items"))
  {
    g_list_free(g_ops);
    g_ops = ops;
  }
  if(n) json_node_unref(n);
  return DTB_OK;
}

int dtb_history_paste(const char *options_json)
{
  NEED_WRITABLE();
  _paste_t p;
  const int r = _paste_options(options_json, &p);
  if(r) return r;
  if(dtb_S.imgid == g_src) return dtb__err(DTB_E_ARG, "this is the photo the history was copied from");
  dtb__change_begin(DTB_CHANGE_OTHER, NULL);
  // as desktop in the darkroom: the image's history is written first
  dt_dev_write_history(&dtb_S.dev);
  const gboolean ok = _paste(g_clip, dtb_S.imgid, p.merge, g_ops, g_order, g_full);
  dtb__reload_dev();
  // the history end never above the items: a reopened XMP has it so (darktable clamps it when it reads the
  // file), and undo and redo keep what this state says (C29 on arw had 23 of 22 after a paste)
  const int n = g_list_length(dtb_S.dev.history);
  if(dtb_S.dev.history_end > n)
  {
    fprintf(stderr, "[dtb] history_paste: history end %d above its %d items after the paste; set to %d\n",
            dtb_S.dev.history_end, n, n);
    dtb_S.dev.history_end = n;
    dt_dev_write_history(&dtb_S.dev);
  }
  dtb__pipes_dirty(DT_DEV_PIPE_REMOVE | DT_DEV_PIPE_SYNCH);
  dtb__change_end(DTB_CHANGE_OTHER, NULL);
  return ok ? DTB_OK : dtb__err(DTB_E_ENGINE, "the paste failed");
}

/* one photo of dtb_history_paste_files */
static int _paste_file(const char *raw, const char *xmp, const _paste_t *p)
{
  if(!raw || !g_file_test(raw, G_FILE_TEST_IS_REGULAR)) return dtb__err(DTB_E_IO, "cannot read %s", raw ? raw : "(null)");
  if(!xmp || !*xmp) return dtb__err(DTB_E_ARG, "no XMP path for %s", raw);
  const gboolean have_xmp = g_file_test(xmp, G_FILE_TEST_IS_REGULAR);
  if(have_xmp)
  {
    // rule 4: an XMP with an unknown module or a newer version is not rewritten
    int bad = 0;
    gchar *c = dtb__xmp_check(xmp, &bad);
    if(!c) return dtb__err(DTB_E_IO, "cannot read %s", xmp);
    g_free(c);
    if(bad != 0)
      return dtb__err(DTB_E_READ_ONLY, "refusing to change %s: it failed the version guard (%d)", xmp, bad);
  }
  // the photo into the library, as dtb_open
  gchar *dir = g_path_get_dirname(raw);
  dt_film_t film;
  dt_film_init(&film);
  const dt_filmid_t filmid = dt_film_new(&film, dir);
  dt_film_cleanup(&film);
  g_free(dir);
  if(!dt_is_valid_filmid(filmid)) return dtb__err(DTB_E_ENGINE, "cannot create film roll");
  gchar *base = g_path_get_basename(raw);
  dt_imgid_t imgid = dtb__image_id(filmid, base);
  g_free(base);
  if(!dt_is_valid_imgid(imgid)) imgid = dt_image_import(filmid, raw, TRUE, FALSE);
  if(!dt_is_valid_imgid(imgid))
    return dtb__err(DTB_E_IO, "cannot decode %s: darktable does not recognise it as a picture (import refused)", raw);
  if(have_xmp)
  {
    dt_image_t *img = dt_image_cache_get(imgid, 'w');
    const gboolean failed = dt_exif_xmp_read(img, xmp, FALSE);
    dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
    if(failed) return dtb__err(DTB_E_IO, "cannot load XMP %s", xmp);
  }
  else
  {
    // no edit yet: the default edit (automatic presets included) as
    // dtb_open shows it, which needs the picture (white balance and other
    // defaults come from the raw); desktop's images have it from their
    // first thumbnail
    dtb__reset_to_default_edit(imgid);
    dt_develop_t dev;
    dt_dev_init(&dev, FALSE);
    dt_dev_load_image(&dev, imgid);
    dt_dev_write_history(&dev);
    dt_dev_cleanup(&dev);
    dt_mipmap_cache_evict_at_size(imgid, DT_MIPMAP_FULL);
  }
  if(imgid == g_src) return dtb__err(DTB_E_ARG, "%s is the photo the history was copied from", raw);
  if(!_paste(g_clip, imgid, p->merge, g_ops, g_order, g_full))
    return dtb__err(DTB_E_ENGINE, "the paste onto %s failed", raw);
  return dtb__xmp_write_image(imgid, xmp);
}

int dtb_history_paste_files(const char *targets_json, const char *options_json, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  if(dtb_S.open) return dtb__err(DTB_E_STATE, "close the open image first (save it to its XMP)");
  JsonNode *t = dtb__json_parse(targets_json);
  if(!t) return DTB_E_ARG;
  gboolean ok = JSON_NODE_TYPE(t) == JSON_NODE_ARRAY;
  JsonArray *a = ok ? json_node_get_array(t) : NULL;
  for(guint i = 0; ok && i < json_array_get_length(a); i++)
  {
    JsonNode *e = json_array_get_element(a, i);
    ok = JSON_NODE_TYPE(e) == JSON_NODE_OBJECT && json_object_has_member(json_node_get_object(e), "raw")
         && json_object_has_member(json_node_get_object(e), "xmp");
  }
  if(!ok)
  {
    json_node_unref(t);
    return dtb__err(DTB_E_ARG, "targets must be a JSON array of { \"raw\", \"xmp\" }");
  }
  _paste_t p;
  const int r = _paste_options(options_json, &p);
  if(r)
  {
    json_node_unref(t);
    return r;
  }
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *e = json_node_get_object(json_array_get_element(a, i));
    const char *raw = json_object_get_string_member(e, "raw");
    const char *xmp = json_object_get_string_member(e, "xmp");
    const int c = _paste_file(raw, xmp, &p);
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "raw");
    json_builder_add_string_value(b, raw ? raw : "");
    json_builder_set_member_name(b, "xmp");
    json_builder_add_string_value(b, xmp ? xmp : "");
    json_builder_set_member_name(b, "code");
    json_builder_add_int_value(b, c);
    json_builder_set_member_name(b, "error");
    if(c) json_builder_add_string_value(b, dtb_last_error());
    else json_builder_add_null_value(b);
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  json_node_unref(t);
  *json = dtb__json_finish(b);
  dtb__err(DTB_OK, "%s", "");
  return DTB_OK;
}

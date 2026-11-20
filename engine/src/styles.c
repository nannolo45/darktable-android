// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: styles (docs/design/engine-api.md section 5.3, brief P1b
 * deliverable 3).
 *
 * Styles live in darktable's own database (data.styles, data.style_items
 * in data.db, in the configuration folder), as on desktop. Listing, import,
 * delete and create use upstream's functions in common/styles.c. Applying
 * follows _styles_apply_to_image_ext() (common/styles.c, static) step for
 * step, without the parts that belong to desktop's interface: its undo
 * record (it allocates ids in the same memory.snapshot_* tables the
 * bridge's own undo uses, history.c), the sidecar write and the thumbnail
 * signals. The session's develop object is then reloaded from the library,
 * as dt_styles_apply_to_dev() does on desktop.
 */
#include "dtb_internal.h"

#include "common/database.h"
#include "common/debug.h"
#include "common/history.h"
#include "common/image_cache.h"
#include "common/iop_order.h"
#include "common/styles.h"
#include "common/tags.h"
#include "control/conf.h"
#include "develop/blend.h"

#include <libxml/parser.h>
#include <string.h>

static int _style_id(const char *name)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db), "SELECT id FROM data.styles WHERE name=?1", -1,
                              &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, name, -1, SQLITE_TRANSIENT);
  const int id = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : 0;
  sqlite3_finalize(stmt);
  return id;
}

static void _str(JsonBuilder *b, const char *name, const char *v)
{
  json_builder_set_member_name(b, name);
  gchar *valid = g_utf8_make_valid(v ? v : "", -1);
  json_builder_add_string_value(b, valid);
  g_free(valid);
}

/* the items of a style and their problems; returns the number of items
 * the engine cannot use and, in *problems (may be NULL), their text */
static int _style_items(JsonBuilder *b, const int id, GString *problems)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT num, operation, multi_priority, multi_name, enabled, module,"
                              "  blendop_version"
                              " FROM data.style_items WHERE styleid=?1 ORDER BY num",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, id);
  int bad = 0;
  if(b)
  {
    json_builder_set_member_name(b, "items");
    json_builder_begin_array(b);
  }
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    const int num = sqlite3_column_int(stmt, 0);
    const char *op = (const char *)sqlite3_column_text(stmt, 1);
    const int mv = sqlite3_column_int(stmt, 5);
    const int bv = sqlite3_column_int(stmt, 6);
    const char *problem = dtb__item_problem(op ? op : "", mv, bv);
    if(problem)
    {
      bad++;
      if(problems)
        g_string_append_printf(problems, "%sitem %d '%s' %s (version %d, blend version %d)",
                               problems->len ? "; " : "", num, op ? op : "", problem, mv, bv);
    }
    if(!b) continue;
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "num");
    json_builder_add_int_value(b, num);
    _str(b, "op", op);
    json_builder_set_member_name(b, "priority");
    json_builder_add_int_value(b, sqlite3_column_int(stmt, 2));
    _str(b, "instance_name", (const char *)sqlite3_column_text(stmt, 3));
    json_builder_set_member_name(b, "enabled");
    json_builder_add_boolean_value(b, sqlite3_column_int(stmt, 4) != 0);
    json_builder_set_member_name(b, "module_version");
    json_builder_add_int_value(b, mv);
    json_builder_set_member_name(b, "blend_version");
    json_builder_add_int_value(b, bv);
    json_builder_set_member_name(b, "problem");
    if(problem) json_builder_add_string_value(b, problem);
    else json_builder_add_null_value(b);
    json_builder_end_object(b);
  }
  sqlite3_finalize(stmt);
  if(b) json_builder_end_array(b);
  return bad;
}

int dtb_styles_list(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT id, name, description, iop_list FROM data.styles ORDER BY name", -1, &stmt,
                              NULL);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    json_builder_begin_object(b);
    _str(b, "name", (const char *)sqlite3_column_text(stmt, 1));
    _str(b, "description", (const char *)sqlite3_column_text(stmt, 2));
    json_builder_set_member_name(b, "has_module_order");
    json_builder_add_boolean_value(b, sqlite3_column_type(stmt, 3) != SQLITE_NULL
                                          && sqlite3_column_bytes(stmt, 3) > 0);
    const int bad = _style_items(b, sqlite3_column_int(stmt, 0), NULL);
    json_builder_set_member_name(b, "usable");
    json_builder_add_boolean_value(b, bad == 0);
    json_builder_end_object(b);
  }
  sqlite3_finalize(stmt);
  json_builder_end_array(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* apply                                                              */

/* _styles_apply_to_image_ext() (common/styles.c) for the session's image,
 * on the library's history, without duplicate, undo record, sidecar and
 * signals */
static void _apply_on_library(const char *name, const int style_id, const dt_imgid_t imgid)
{
  dt_develop_t dev_dest = { 0 };
  dt_dev_init(&dev_dest, FALSE);
  dev_dest.iop = dt_iop_load_modules_ext(&dev_dest, TRUE);
  dev_dest.image_storage.id = imgid;

  GList *iop_list = dt_styles_module_order_list(name);
  if(iop_list)
  {
    // the style has a module order: merge the image's multi-instances into it
    GList *img_iop_order_list = dt_ioppr_get_iop_order_list(imgid, FALSE);
    GList *mi = dt_ioppr_extract_multi_instances_list(img_iop_order_list);
    if(mi) iop_list = dt_ioppr_merge_multi_instance_iop_order_list(iop_list, mi);
    dt_ioppr_write_iop_order_list(iop_list, imgid);
    g_list_free_full(iop_list, g_free);
    g_list_free_full(img_iop_order_list, g_free);
    g_list_free_full(mi, g_free);
  }

  dt_dev_read_history_ext(&dev_dest, imgid, TRUE);
  dt_dev_pop_history_items_ext(&dev_dest, dev_dest.history_end);

  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT num, module, operation, op_params, enabled,"
                              "       blendop_params, blendop_version, multi_priority,"
                              "       multi_name, multi_name_hand_edited"
                              " FROM data.style_items WHERE styleid=?1 "
                              " ORDER BY operation, multi_priority",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, style_id);
  GList *si_list = NULL;
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    dt_style_item_t *si = calloc(1, sizeof(dt_style_item_t));
    si->num = sqlite3_column_int(stmt, 0);
    si->enabled = sqlite3_column_int(stmt, 4);
    si->multi_priority = sqlite3_column_int(stmt, 7);
    si->operation = g_strdup((char *)sqlite3_column_text(stmt, 2));
    si->multi_name_hand_edited = sqlite3_column_int(stmt, 9);
    // as upstream: see dt_iop_get_instance_name()
    si->multi_name = g_strdup((si->multi_priority > 0 || si->multi_name_hand_edited)
                                  ? (char *)sqlite3_column_text(stmt, 8)
                                  : "");
    si->module_version = sqlite3_column_int(stmt, 1);
    si->blendop_version = sqlite3_column_int(stmt, 6);
    si->params_size = sqlite3_column_bytes(stmt, 3);
    si->params = malloc(MAX(1, si->params_size));
    if(si->params_size) memcpy(si->params, sqlite3_column_blob(stmt, 3), si->params_size);
    si->blendop_params_size = sqlite3_column_bytes(stmt, 5);
    si->blendop_params = malloc(MAX(1, si->blendop_params_size));
    if(si->blendop_params_size) memcpy(si->blendop_params, sqlite3_column_blob(stmt, 5), si->blendop_params_size);
    si_list = g_list_prepend(si_list, si);
  }
  sqlite3_finalize(stmt);
  si_list = g_list_reverse(si_list);

  dt_ioppr_update_for_style_items(&dev_dest, si_list, FALSE);
  GList *modules_used = NULL;
  for(GList *l = si_list; l; l = g_list_next(l))
    dt_styles_apply_style_item(&dev_dest, l->data, &modules_used, FALSE);
  g_list_free_full(si_list, dt_style_item_free);
  g_list_free(modules_used);

  dt_dev_write_history_ext(&dev_dest, imgid);
  dt_dev_cleanup(&dev_dest);

  // the tags upstream attaches
  guint tagid = 0;
  gchar *local_name = dt_util_localize_segmented_name(name, FALSE);
  gchar *tag = g_strdup_printf("darktable|style|%s", local_name);
  g_free(local_name);
  if(dt_tag_new(tag, &tagid)) dt_tag_attach(tagid, imgid, FALSE, FALSE);
  g_free(tag);
  if(dt_tag_new("darktable|changed", &tagid)) dt_tag_attach(tagid, imgid, FALSE, FALSE);
  dt_image_cache_set_change_timestamp(imgid);
}

int dtb_style_apply(const char *name, const char *mode)
{
  NEED_WRITABLE();
  if(!name) return dtb__err(DTB_E_ARG, "style name required");
  gboolean overwrite;
  if(!mode)
    overwrite = dt_conf_get_int("plugins/lighttable/style/applymode") == DT_STYLE_HISTORY_OVERWRITE;
  else if(!strcmp(mode, "append"))
    overwrite = FALSE;
  else if(!strcmp(mode, "overwrite"))
    overwrite = TRUE;
  else
    return dtb__err(DTB_E_ARG, "mode must be \"append\", \"overwrite\" or null");
  const int id = _style_id(name);
  if(!id) return dtb__err(DTB_E_NOT_FOUND, "no style '%s'", name);
  GString *problems = g_string_new(NULL);
  const int bad = _style_items(NULL, id, problems);
  if(bad)
  {
    const int r = dtb__err(DTB_E_ARG, "style '%s' cannot be applied: %s", name, problems->str);
    g_string_free(problems, TRUE);
    return r;
  }
  g_string_free(problems, TRUE);

  dtb__change_begin(DTB_CHANGE_OTHER, NULL);
  // the session's history, items above its end included, into the library;
  // reading it back with that end and adding items discards those above, as
  // desktop's dt_styles_apply_to_dev() does
  dt_dev_write_history(&dtb_S.dev);
  if(overwrite)
  {
    // the lighttable's overwrite (control_jobs.c _control_apply_styles_job):
    // dt_history_delete_on_image_ext(imgid, FALSE, TRUE), which also clears
    // the "auto presets applied" flag so the next read starts from the
    // default edit. Its init_history branch writes that flag with
    // DT_IMAGE_CACHE_SAFE; the bridge clears it itself, relaxed, as
    // session.c does for the default edit
    dt_history_delete_on_image_ext(dtb_S.imgid, FALSE, FALSE);
    dt_image_t *img = dt_image_cache_get(dtb_S.imgid, 'w');
    if(img)
    {
      img->flags &= ~DT_IMAGE_AUTO_PRESETS_APPLIED;
      dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
    }
  }
  _apply_on_library(name, id, dtb_S.imgid);
  dtb__reload_dev();
  dtb__pipes_dirty(DT_DEV_PIPE_REMOVE | DT_DEV_PIPE_SYNCH);
  dtb__change_end(DTB_CHANGE_OTHER, NULL);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* import, delete, create                                             */

/* <darktable_style><info><name> of a .dtstyle file; NULL if the file is not
 * a darktable style */
static gchar *_dtstyle_name(const char *path)
{
  xmlDocPtr doc = xmlReadFile(path, NULL, XML_PARSE_NONET | XML_PARSE_NOERROR | XML_PARSE_NOWARNING);
  xmlNodePtr root = doc ? xmlDocGetRootElement(doc) : NULL;
  gchar *name = NULL;
  if(root && !xmlStrcmp(root->name, BAD_CAST "darktable_style"))
    for(xmlNodePtr i = root->children; i && !name; i = i->next)
      if(i->type == XML_ELEMENT_NODE && !xmlStrcmp(i->name, BAD_CAST "info"))
        for(xmlNodePtr c = i->children; c && !name; c = c->next)
          if(c->type == XML_ELEMENT_NODE && !xmlStrcmp(c->name, BAD_CAST "name"))
          {
            xmlChar *v = xmlNodeGetContent(c);
            if(v && *v) name = g_strdup((const char *)v);
            xmlFree(v);
          }
  if(doc) xmlFreeDoc(doc);
  return name;
}

static void _refused(JsonBuilder *b, const char *path, const char *reason)
{
  json_builder_begin_object(b);
  _str(b, "path", path);
  _str(b, "reason", reason);
  json_builder_end_object(b);
}

int dtb_styles_import(const char *paths_json, int overwrite, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonNode *n = dtb__json_parse(paths_json);
  if(!n) return DTB_E_ARG;
  if(JSON_NODE_TYPE(n) != JSON_NODE_ARRAY)
  {
    json_node_unref(n);
    return dtb__err(DTB_E_ARG, "paths must be a JSON array of strings");
  }
  JsonArray *a = json_node_get_array(n);
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonNode *e = json_array_get_element(a, i);
    if(JSON_NODE_TYPE(e) != JSON_NODE_VALUE || json_node_get_value_type(e) != G_TYPE_STRING)
    {
      json_node_unref(n);
      return dtb__err(DTB_E_ARG, "paths must be a JSON array of strings");
    }
  }
  JsonBuilder *ok = json_builder_new();
  JsonBuilder *no = json_builder_new();
  json_builder_begin_array(ok);
  json_builder_begin_array(no);
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    const char *path = json_array_get_string_element(a, i);
    if(!g_file_test(path, G_FILE_TEST_IS_REGULAR))
    {
      _refused(no, path, "io");
      continue;
    }
    gchar *name = _dtstyle_name(path);
    if(!name)
    {
      _refused(no, path, "not_a_style");
      continue;
    }
    if(_style_id(name))
    {
      if(!overwrite)
      {
        _refused(no, path, "exists");
        g_free(name);
        continue;
      }
      dt_styles_delete_by_name_adv(name, FALSE, FALSE);
    }
    // upstream's importer (GMarkup); it stores nothing if the file does not
    // parse to the end
    dt_styles_import_from_file(path);
    const int id = _style_id(name);
    if(!id)
    {
      _refused(no, path, "not_a_style");
      g_free(name);
      continue;
    }
    json_builder_begin_object(ok);
    _str(ok, "path", path);
    _str(ok, "name", name);
    json_builder_set_member_name(ok, "usable");
    json_builder_add_boolean_value(ok, _style_items(NULL, id, NULL) == 0);
    json_builder_end_object(ok);
    g_free(name);
  }
  json_node_unref(n);
  json_builder_end_array(ok);
  json_builder_end_array(no);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "imported");
  JsonNode *okn = json_builder_get_root(ok);
  json_builder_add_value(b, okn);
  json_builder_set_member_name(b, "refused");
  JsonNode *non = json_builder_get_root(no);
  json_builder_add_value(b, non);
  json_builder_end_object(b);
  g_object_unref(ok);
  g_object_unref(no);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

int dtb_style_delete(const char *name)
{
  NEED_INIT();
  if(!name) return dtb__err(DTB_E_ARG, "style name required");
  if(!_style_id(name)) return dtb__err(DTB_E_NOT_FOUND, "no style '%s'", name);
  dt_styles_delete_by_name_adv(name, FALSE, FALSE);
  return _style_id(name) ? dtb__err(DTB_E_ENGINE, "style '%s' was not deleted", name) : DTB_OK;
}

typedef struct _inst_t
{
  char op[64];
  int priority;
  int num;
} _inst_t;

int dtb_style_create(const char *name, const char *description, const char *items_json, int overwrite)
{
  NEED_SESSION();
  if(!name || !*name || !g_utf8_validate(name, -1, NULL)) return dtb__err(DTB_E_ARG, "a style name must be non-empty UTF-8");
  if(_style_id(name) && !overwrite) return dtb__err(DTB_E_ARG, "a style '%s' exists (exists)", name);
  if(dtb_S.rows) return dtb__err(DTB_E_READ_ONLY, "the history holds items the engine could not load");

  const int end = dtb_S.dev.history_end;
  // the history items below the end, by position
  GArray *chosen = g_array_new(FALSE, TRUE, sizeof(_inst_t));
  GList *h = dtb_S.dev.history;
  GHashTable *want = NULL;
  if(items_json)
  {
    JsonNode *n = dtb__json_parse(items_json);
    if(!n) return DTB_E_ARG;
    if(JSON_NODE_TYPE(n) != JSON_NODE_ARRAY)
    {
      json_node_unref(n);
      g_array_free(chosen, TRUE);
      return dtb__err(DTB_E_ARG, "items must be a JSON array of history nums");
    }
    want = g_hash_table_new(g_direct_hash, g_direct_equal);
    JsonArray *a = json_node_get_array(n);
    for(guint i = 0; i < json_array_get_length(a); i++)
    {
      JsonNode *e = json_array_get_element(a, i);
      const gint64 v = JSON_NODE_TYPE(e) == JSON_NODE_VALUE && json_node_get_value_type(e) == G_TYPE_INT64
                           ? json_node_get_int(e)
                           : -1;
      if(v < 0 || v >= end)
      {
        json_node_unref(n);
        g_hash_table_destroy(want);
        g_array_free(chosen, TRUE);
        return dtb__err(DTB_E_ARG, "history num %lld outside 0..%d", (long long)v, end - 1);
      }
      g_hash_table_add(want, GINT_TO_POINTER((int)v));
    }
    json_node_unref(n);
    if(g_hash_table_size(want) == 0)
    {
      g_hash_table_destroy(want);
      g_array_free(chosen, TRUE);
      return dtb__err(DTB_E_ARG, "no history item chosen");
    }
  }
  // one entry per module instance, its last chosen item (as desktop's
  // dialog, gui/styles_dialog.c with dt_history_get_items())
  int pos = 0;
  for(GList *l = h; l && pos < end; l = g_list_next(l), pos++)
  {
    const dt_dev_history_item_t *it = l->data;
    if(!strcmp(it->op_name, "mask_manager")) continue;
    if(want && !g_hash_table_contains(want, GINT_TO_POINTER(pos))) continue;
    guint k = 0;
    for(; k < chosen->len; k++)
    {
      _inst_t *c = &g_array_index(chosen, _inst_t, k);
      if(!strcmp(c->op, it->op_name) && c->priority == it->multi_priority) break;
    }
    if(k == chosen->len)
    {
      _inst_t c = { { 0 }, it->multi_priority, pos };
      g_strlcpy(c.op, it->op_name, sizeof(c.op));
      g_array_append_val(chosen, c);
    }
    else
      g_array_index(chosen, _inst_t, k).num = pos;
  }
  if(want) g_hash_table_destroy(want);
  if(chosen->len == 0)
  {
    g_array_free(chosen, TRUE);
    return dtb__err(DTB_E_ARG, "the chosen history holds no module item");
  }
  GList *filter = NULL;
  for(guint k = 0; k < chosen->len; k++)
    filter = g_list_append(filter, GINT_TO_POINTER(g_array_index(chosen, _inst_t, k).num));
  g_array_free(chosen, TRUE);

  // the library holds the history with nums = positions
  dt_dev_write_history(&dtb_S.dev);
  if(_style_id(name)) dt_styles_delete_by_name_adv(name, FALSE, FALSE);
  const gboolean ok = dt_styles_create_from_image(name, description ? description : "", dtb_S.imgid, filter, TRUE);
  g_list_free(filter);
  if(!ok || !_style_id(name)) return dtb__err(DTB_E_ENGINE, "style '%s' was not created", name);
  return DTB_OK;
}

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: module presets (docs/design/engine-api.md section 5.2,
 * brief P1b deliverable 2).
 *
 * Presets live in darktable's own database (data.presets in data.db, in the
 * configuration folder), as on desktop. The bridge uses upstream's preset
 * functions (common/presets.c) and, where desktop does the work in its
 * interface code (gui/presets.c), the interface-free part of it; each place
 * names the upstream function it follows.
 */
#include "dtb_internal.h"

#include "common/database.h"
#include "common/debug.h"
#include "common/exif.h"
#include "common/image_cache.h"
#include "common/presets.h"
#include "control/conf.h"
#include "develop/blend.h"
#include "develop/masks.h"
#include "gui/presets.h"

#include <float.h>
#include <libxml/parser.h>
#include <string.h>

/* the module library (not an instance) of an operation; NULL if the engine
 * does not have it */
dt_iop_module_so_t *dtb__iop_so(const char *op)
{
  if(!op) return NULL;
  for(GList *l = darktable.iop; l; l = g_list_next(l))
  {
    dt_iop_module_so_t *so = l->data;
    if(!strcmp(so->op, op)) return so;
  }
  return NULL;
}

/* the reason a stored item (preset, style item) cannot be used by this
 * engine, as the version guard words it; NULL if it can */
const char *dtb__item_problem(const char *op, const int module_version, const int blend_version)
{
  dt_iop_module_so_t *so = dtb__iop_so(op);
  if(!so) return "unknown_operation";
  if(module_version > so->version()) return "newer_module_version";
  if(blend_version > dt_develop_blend_version()) return "newer_blend_version";
  return NULL;
}

static gboolean _valid_name(const char *name)
{
  return name && *name && !strchr(name, '/') && g_utf8_validate(name, -1, NULL);
}

/* rows of data.presets for a module and name: count, and whether any is
 * write-protected (built-in) */
static int _name_rows(const char *op, const char *name, gboolean *builtin)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT writeprotect FROM data.presets WHERE operation=?1 AND name=?2",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, op, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, name, -1, SQLITE_TRANSIENT);
  int n = 0;
  if(builtin) *builtin = FALSE;
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    n++;
    if(builtin && sqlite3_column_int(stmt, 0)) *builtin = TRUE;
  }
  sqlite3_finalize(stmt);
  return n;
}

/* ------------------------------------------------------------------ */
/* list                                                               */

/* does the image match a preset's conditions? The WHERE clause of
 * dt_presets_menu_rows_iop() (common/presets.c) without its "filter=0 OR":
 * desktop's menu shows a preset with the filter switched on only when this
 * holds, and auto-apply (dt_presets_find_autoapply) uses the same
 * conditions */
static GHashTable *_matching_rowids(const char *op, const dt_image_t *image)
{
  GHashTable *set = g_hash_table_new(g_direct_hash, g_direct_equal);
  char *format_filter = dt_presets_get_filter(image);
  gchar *query = g_strdup_printf("SELECT rowid FROM data.presets"
                                 " WHERE operation=?1"
                                 "   AND ((?2 LIKE model AND ?3 LIKE maker) OR (?4 LIKE model AND ?5 LIKE maker))"
                                 "   AND ?6 LIKE lens"
                                 "   AND ?7 BETWEEN iso_min AND iso_max"
                                 "   AND ?8 BETWEEN exposure_min AND exposure_max"
                                 "   AND ?9 BETWEEN aperture_min AND aperture_max"
                                 "   AND ?10 BETWEEN focal_length_min AND focal_length_max"
                                 "   AND (%s)",
                                 format_filter);
  g_free(format_filter);
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db), query, -1, &stmt, NULL);
  g_free(query);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, op, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, image->exif_model, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 3, image->exif_maker, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 4, image->camera_alias, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 5, image->camera_maker, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 6, image->exif_lens, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_DOUBLE(stmt, 7, image->exif_iso);
  DT_DEBUG_SQLITE3_BIND_DOUBLE(stmt, 8, image->exif_exposure);
  DT_DEBUG_SQLITE3_BIND_DOUBLE(stmt, 9, image->exif_aperture);
  DT_DEBUG_SQLITE3_BIND_DOUBLE(stmt, 10, image->exif_focal_length);
  while(sqlite3_step(stmt) == SQLITE_ROW)
    g_hash_table_add(set, GINT_TO_POINTER(sqlite3_column_int(stmt, 0)));
  sqlite3_finalize(stmt);
  return set;
}

static void _range(JsonBuilder *b, const char *name, const double lo, const double hi)
{
  json_builder_set_member_name(b, name);
  json_builder_begin_array(b);
  json_builder_add_double_value(b, isfinite(lo) ? lo : 0.0);
  // FLT_MAX ("no upper limit") as null
  if(!isfinite(hi) || hi >= FLT_MAX * 0.99) json_builder_add_null_value(b);
  else json_builder_add_double_value(b, hi);
  json_builder_end_array(b);
}

static void _str(JsonBuilder *b, const char *name, const char *v)
{
  json_builder_set_member_name(b, name);
  gchar *valid = g_utf8_make_valid(v ? v : "", -1);
  json_builder_add_string_value(b, valid);
  g_free(valid);
}

/* the format flags of a preset (gui/presets.h FOR_*; the two "not" bits are
 * stored inverted, gui/presets.c _edit_preset_response) */
static void _format(JsonBuilder *b, const int format)
{
  json_builder_set_member_name(b, "format");
  json_builder_begin_array(b);
  if(format != 0)
  {
    if(format & FOR_RAW) json_builder_add_string_value(b, "raw");
    if(format & FOR_LDR) json_builder_add_string_value(b, "ldr");
    if(format & FOR_HDR) json_builder_add_string_value(b, "hdr");
    if(format & FOR_MATRIX) json_builder_add_string_value(b, "matrix");
    if(!(format & FOR_NOT_MONO)) json_builder_add_string_value(b, "monochrome");
    if(!(format & FOR_NOT_COLOR)) json_builder_add_string_value(b, "color");
  }
  json_builder_end_array(b);
}

/* is the instance's state the preset's? dt_get_active_preset_name()
 * (common/presets.c), for one row */
static gboolean _row_active(dt_iop_module_t *m, const void *op_params, const int op_size,
                            const void *bl, const int bl_size, const int enabled)
{
  return ((op_size == 0 && !memcmp(m->default_params, m->params, m->params_size))
          || (op_size > 0 && !memcmp(m->params, op_params, MIN(op_size, m->params_size))))
         && dt_presets_module_blend_match(m, bl, bl_size) && m->enabled == enabled;
}

int dtb_presets_list(const char *op, int priority, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!op || !json) return dtb__err(DTB_E_ARG, "op and result pointer are required");
  dt_iop_module_so_t *so = dtb__iop_so(op);
  if(!so) return dtb__err(DTB_E_NOT_FOUND, "the engine has no module '%s'", op);
  const int version = so->version();
  dt_iop_module_t *m = dtb_S.open ? dtb__find(op, priority) : NULL;
  if(dtb_S.open && !m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op, priority);

  GHashTable *match = NULL;
  if(dtb_S.open)
  {
    const dt_image_t *img = dt_image_cache_get(dtb_S.imgid, 'r');
    if(img)
    {
      match = _matching_rowids(op, img);
      dt_image_cache_read_release(img);
    }
  }

  // desktop's menu order: built-in first, then by name (dt_presets_menu_rows_iop)
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT rowid, name, description, writeprotect, op_version, enabled,"
                              "  autoapply, filter, model, maker, lens, iso_min, iso_max,"
                              "  exposure_min, exposure_max, aperture_min, aperture_max,"
                              "  focal_length_min, focal_length_max, format, op_params, blendop_params"
                              " FROM data.presets WHERE operation=?1"
                              " ORDER BY writeprotect DESC, LOWER(name), rowid",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, op, -1, SQLITE_TRANSIENT);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    const int rowid = sqlite3_column_int(stmt, 0);
    const int op_version = sqlite3_column_int(stmt, 4);
    const int enabled = sqlite3_column_int(stmt, 5);
    const int filter = sqlite3_column_int(stmt, 7);
    json_builder_begin_object(b);
    _str(b, "name", (const char *)sqlite3_column_text(stmt, 1));
    _str(b, "description", (const char *)sqlite3_column_text(stmt, 2));
    json_builder_set_member_name(b, "builtin");
    json_builder_add_boolean_value(b, sqlite3_column_int(stmt, 3) != 0);
    json_builder_set_member_name(b, "op_version");
    json_builder_add_int_value(b, op_version);
    json_builder_set_member_name(b, "usable");
    json_builder_add_boolean_value(b, op_version == version);
    json_builder_set_member_name(b, "enabled");
    json_builder_add_boolean_value(b, enabled != 0);
    json_builder_set_member_name(b, "autoapply");
    json_builder_add_boolean_value(b, sqlite3_column_int(stmt, 6) != 0);
    json_builder_set_member_name(b, "filter");
    json_builder_add_boolean_value(b, filter != 0);
    json_builder_set_member_name(b, "match");
    json_builder_begin_object(b);
    _str(b, "model", (const char *)sqlite3_column_text(stmt, 8));
    _str(b, "maker", (const char *)sqlite3_column_text(stmt, 9));
    _str(b, "lens", (const char *)sqlite3_column_text(stmt, 10));
    _range(b, "iso", sqlite3_column_double(stmt, 11), sqlite3_column_double(stmt, 12));
    _range(b, "exposure", sqlite3_column_double(stmt, 13), sqlite3_column_double(stmt, 14));
    _range(b, "aperture", sqlite3_column_double(stmt, 15), sqlite3_column_double(stmt, 16));
    _range(b, "focal_length", sqlite3_column_double(stmt, 17), sqlite3_column_double(stmt, 18));
    _format(b, sqlite3_column_int(stmt, 19));
    json_builder_end_object(b);
    json_builder_set_member_name(b, "matches");
    if(match) json_builder_add_boolean_value(b, g_hash_table_contains(match, GINT_TO_POINTER(rowid)));
    else json_builder_add_null_value(b);
    json_builder_set_member_name(b, "fits");
    if(match) json_builder_add_boolean_value(b, !filter || g_hash_table_contains(match, GINT_TO_POINTER(rowid)));
    else json_builder_add_null_value(b);
    json_builder_set_member_name(b, "active");
    if(m)
      json_builder_add_boolean_value(b, op_version == version
                                            && _row_active(m, sqlite3_column_blob(stmt, 20), sqlite3_column_bytes(stmt, 20),
                                                           sqlite3_column_blob(stmt, 21), sqlite3_column_bytes(stmt, 21),
                                                           enabled));
    else
      json_builder_add_null_value(b);
    json_builder_end_object(b);
  }
  sqlite3_finalize(stmt);
  json_builder_end_array(b);
  if(match) g_hash_table_destroy(match);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* apply                                                              */

int dtb_preset_apply(const char *op, int priority, const char *name)
{
  NEED_WRITABLE();
  if(!op || !name) return dtb__err(DTB_E_ARG, "op and name are required");
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op, priority);
  dt_presets_params_t p = { 0 };
  if(!dt_presets_get_params(m->op, m->version(), name, &p))
  {
    dt_presets_params_clear(&p);
    if(_name_rows(op, name, NULL) > 0)
      return dtb__err(DTB_E_ARG, "preset '%s' of %s was stored for another version of the module", name, op);
    return dtb__err(DTB_E_NOT_FOUND, "%s has no preset '%s'", op, name);
  }
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  // dt_gui_presets_apply_preset() (gui/presets.c) without its interface work
  if(p.op_params && p.op_params_size == m->params_size)
    memcpy(m->params, p.op_params, m->params_size);
  else
    memcpy(m->params, m->default_params, m->params_size);
  m->enabled = p.enabled;
  dt_iop_update_multi_name(m, (p.multi_name && *p.multi_name) ? p.multi_name : name,
                           p.multi_name_hand_edited, FALSE, FALSE);
  dt_gui_presets_apply_blend_params(m, p.blendop_params, p.blendop_version, p.blendop_params_size);
  dt_presets_params_clear(&p);
  // only the masks variant records the forms (as desktop)
  if(dt_masks_module_mask_in_forms(m, dtb_S.dev.forms))
  {
    dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
    dt_dev_add_masks_history_item_ext(&dtb_S.dev, m, FALSE, TRUE);
    dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
    dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
  }
  else
    dtb__history_add(m, FALSE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* store, rename, delete                                              */

int dtb_preset_store(const char *op, int priority, const char *name, const char *description, int overwrite)
{
  NEED_SESSION();
  if(!op || !_valid_name(name)) return dtb__err(DTB_E_ARG, "a preset name must be non-empty UTF-8 without '/'");
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op, priority);
  gboolean builtin = FALSE;
  const int existing = _name_rows(op, name, &builtin);
  if(builtin) return dtb__err(DTB_E_ARG, "'%s' is a built-in preset of %s and cannot be overwritten", name, op);
  if(existing && !overwrite) return dtb__err(DTB_E_ARG, "%s already has a preset '%s' (exists)", op, name);

  // the mask goes with the parameters (gui/presets.c _edit_preset_response)
  size_t blend_size = 0;
  void *blend = dt_gui_presets_module_blend_blob(m, &blend_size);
  if(existing)
  {
    // desktop's "update preset": parameters, switch and blending; filters,
    // auto-apply and description stay (dt_presets_update_params)
    dt_presets_update_params(m->op, m->version(), name, m->params, m->params_size, m->enabled, blend,
                             (int)blend_size, dt_develop_blend_version());
    if(description)
    {
      sqlite3_stmt *stmt;
      DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                                  "UPDATE data.presets SET description=?1 WHERE operation=?2 AND name=?3",
                                  -1, &stmt, NULL);
      DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, description, -1, SQLITE_TRANSIENT);
      DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, m->op, -1, SQLITE_TRANSIENT);
      DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 3, name, -1, SQLITE_TRANSIENT);
      sqlite3_step(stmt);
      sqlite3_finalize(stmt);
    }
  }
  else
  {
    // desktop's "store new preset" with the edit dialog's defaults
    // (gui/presets.c: every camera, ISO 0..FLT_MAX, the whole exposure,
    // aperture and focal length lists, no auto-apply, no filter, every format)
    dt_presets_write_t w = {
      .name = name,
      .description = description ? description : "",
      .model = "%",
      .maker = "%",
      .lens = "%",
      .iso_min = 0.0,
      .iso_max = FLT_MAX,
      .exposure_min = 0.0,
      .exposure_max = FLT_MAX,
      .aperture_min = 0.0,
      .aperture_max = FLT_MAX,
      .focal_length_min = 0.0,
      .focal_length_max = MAX_FOCAL_LEN,
      .autoapply = 0,
      .filter = 0,
      .format = (FOR_LDR | FOR_RAW | FOR_HDR | FOR_NOT_MONO | FOR_NOT_COLOR) ^ (FOR_NOT_MONO | FOR_NOT_COLOR),
      .iop = TRUE,
      .op_params = m->params,
      .op_params_size = m->params_size,
      .enabled = m->enabled,
      .multi_name = m->multi_name_hand_edited ? m->multi_name : name,
      .multi_name_hand_edited = m->multi_name_hand_edited,
      .blendop_params = blend,
      .blendop_params_size = (int)blend_size,
      .blendop_version = dt_develop_blend_version(),
      .operation = m->op,
      .op_version = m->version(),
    };
    dt_presets_write_to_rowid(-1, &w);
  }
  g_free(blend);
  if(_name_rows(op, name, NULL) == 0) return dtb__err(DTB_E_ENGINE, "the preset was not stored");
  return DTB_OK;
}

int dtb_preset_rename(const char *op, const char *name, const char *new_name)
{
  NEED_INIT();
  if(!op || !name || !_valid_name(new_name))
    return dtb__err(DTB_E_ARG, "op, name and a valid new name are required");
  gboolean builtin = FALSE;
  if(_name_rows(op, name, &builtin) == 0) return dtb__err(DTB_E_NOT_FOUND, "%s has no preset '%s'", op, name);
  if(builtin) return dtb__err(DTB_E_ARG, "'%s' is a built-in preset and cannot be renamed", name);
  if(!strcmp(name, new_name)) return DTB_OK;
  if(_name_rows(op, new_name, NULL) > 0) return dtb__err(DTB_E_ARG, "%s already has a preset '%s' (exists)", op, new_name);
  // dt_presets_rename() renames one version; every version of the name here
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "UPDATE data.presets SET name=?1 WHERE operation=?2 AND name=?3 AND writeprotect=0",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, new_name, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, op, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 3, name, -1, SQLITE_TRANSIENT);
  sqlite3_step(stmt);
  sqlite3_finalize(stmt);
  return DTB_OK;
}

int dtb_preset_delete(const char *op, const char *name)
{
  NEED_INIT();
  if(!op || !name) return dtb__err(DTB_E_ARG, "op and name are required");
  gboolean builtin = FALSE;
  if(_name_rows(op, name, &builtin) == 0) return dtb__err(DTB_E_NOT_FOUND, "%s has no preset '%s'", op, name);
  if(builtin) return dtb__err(DTB_E_ARG, "'%s' is a built-in preset and cannot be deleted", name);
  // dt_presets_delete_unprotected_from_rowid(), for every version of the name
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "DELETE FROM data.presets WHERE operation=?1 AND name=?2 AND writeprotect=0",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, op, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, name, -1, SQLITE_TRANSIENT);
  sqlite3_step(stmt);
  sqlite3_finalize(stmt);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* import and export                                                  */

/* one element of a .dtpreset file (<darktable_preset><preset><x>) */
static gchar *_xml_field(xmlDocPtr doc, const char *field)
{
  xmlNodePtr root = xmlDocGetRootElement(doc);
  for(xmlNodePtr p = root ? root->children : NULL; p; p = p->next)
  {
    if(p->type != XML_ELEMENT_NODE || xmlStrcmp(p->name, BAD_CAST "preset")) continue;
    for(xmlNodePtr c = p->children; c; c = c->next)
      if(c->type == XML_ELEMENT_NODE && !xmlStrcmp(c->name, BAD_CAST field))
      {
        xmlChar *v = xmlNodeGetContent(c);
        gchar *r = g_strdup(v ? (const char *)v : "");
        xmlFree(v);
        return r;
      }
  }
  return NULL;
}

int dtb_preset_import(const char *path, int overwrite, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!path || !json) return dtb__err(DTB_E_ARG, "path and result pointer are required");
  if(!g_file_test(path, G_FILE_TEST_IS_REGULAR)) return dtb__err(DTB_E_IO, "cannot read %s", path);
  // the checks dt_presets_import_from_file() (common/presets.c) does not
  // make, on the same elements it reads
  xmlDocPtr doc = xmlReadFile(path, NULL, XML_PARSE_NONET | XML_PARSE_NOERROR | XML_PARSE_NOWARNING);
  xmlNodePtr root = doc ? xmlDocGetRootElement(doc) : NULL;
  if(!root || xmlStrcmp(root->name, BAD_CAST "darktable_preset"))
  {
    if(doc) xmlFreeDoc(doc);
    return dtb__err(DTB_E_IO, "%s is not a darktable preset file", path);
  }
  gchar *name = _xml_field(doc, "name");
  gchar *op = _xml_field(doc, "operation");
  gchar *opv = _xml_field(doc, "op_version");
  gchar *blv = _xml_field(doc, "blendop_version");
  gchar *opp = _xml_field(doc, "op_params");
  xmlFreeDoc(doc);
  int r = DTB_OK;
  const int op_version = opv ? atoi(opv) : 0;
  const int blend_version = blv ? atoi(blv) : 0;
  if(!name || !*name || !op || !*op || !opv || !opp)
    r = dtb__err(DTB_E_IO, "%s is not a complete darktable preset file", path);
  const char *problem = r ? NULL : dtb__item_problem(op, op_version, blend_version);
  gboolean builtin = FALSE;
  if(!r && problem)
    r = dtb__err(DTB_E_ARG, "preset '%s' cannot be imported: %s (module %s version %d, blend version %d)", name,
                 problem, op, op_version, blend_version);
  if(!r && _name_rows(op, name, &builtin) > 0)
  {
    if(builtin)
      r = dtb__err(DTB_E_ARG, "preset '%s' cannot be imported: %s has a built-in preset of that name", name, op);
    else if(!overwrite)
      r = dtb__err(DTB_E_ARG, "preset '%s' cannot be imported: %s already has a preset of that name (exists)", name, op);
    else
    {
      // replace every version of the user's preset of that name
      sqlite3_stmt *stmt;
      DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                                  "DELETE FROM data.presets WHERE operation=?1 AND name=?2 AND writeprotect=0",
                                  -1, &stmt, NULL);
      DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, op, -1, SQLITE_TRANSIENT);
      DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, name, -1, SQLITE_TRANSIENT);
      sqlite3_step(stmt);
      sqlite3_finalize(stmt);
    }
  }
  if(!r && !dt_presets_import_from_file(path)) r = dtb__err(DTB_E_IO, "darktable could not import %s", path);
  if(!r && _name_rows(op, name, NULL) == 0) r = dtb__err(DTB_E_ENGINE, "preset '%s' was not stored", name);
  if(!r)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    _str(b, "op", op);
    _str(b, "name", name);
    json_builder_set_member_name(b, "op_version");
    json_builder_add_int_value(b, op_version);
    json_builder_set_member_name(b, "usable");
    json_builder_add_boolean_value(b, dtb__iop_so(op)->version() == op_version);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
  }
  g_free(name);
  g_free(op);
  g_free(opv);
  g_free(blv);
  g_free(opp);
  return r;
}

int dtb_preset_export(const char *op, const char *name, const char *dir, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!op || !name || !dir || !json) return dtb__err(DTB_E_ARG, "op, name, dir and result pointer are required");
  if(!g_file_test(dir, G_FILE_TEST_IS_DIR)) return dtb__err(DTB_E_IO, "%s is not a folder", dir);
  // the newest version of the name, as desktop exports the preset it shows
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT rowid FROM data.presets WHERE operation=?1 AND name=?2"
                              " ORDER BY op_version DESC LIMIT 1",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, op, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, name, -1, SQLITE_TRANSIENT);
  const int rowid = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : -1;
  sqlite3_finalize(stmt);
  if(rowid < 0) return dtb__err(DTB_E_NOT_FOUND, "%s has no preset '%s'", op, name);
  // the file name dt_presets_save_to_file() makes
  gchar *safe = g_strdup(name);
  g_strdelimit(safe, "/<>:\"\\|*?[]", '_');
  gchar *file = g_strdup_printf("%s/%s.dtpreset", dir, safe);
  g_free(safe);
  g_unlink(file);
  dt_presets_save_to_file(rowid, name, dir);
  if(!g_file_test(file, G_FILE_TEST_IS_REGULAR))
  {
    const int r = dtb__err(DTB_E_IO, "cannot write %s", file);
    g_free(file);
    return r;
  }
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  _str(b, "path", file);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  g_free(file);
  return DTB_OK;
}

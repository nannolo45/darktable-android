// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Shared styles and presets (brief P3l, request R11; PLAN.md 4.5): the app's
 * own data.db seen as the items LightPhotoLibrary passes between devices.
 *
 * - a body is JSON: {"kind": "preset", "row": {<every column of data.presets
 *   except writeprotect>}} or {"kind": "style", "style": {<every column of
 *   data.styles except id>}, "items": [<every column of data.style_items
 *   except styleid>, ... by num]}. Columns are read and written by name, so a
 *   darktable with a column more or less still understands a body: a body
 *   column the table does not have is ignored, a table column the body lacks
 *   is NULL.
 * - values by the column's declared type (SQLite's affinity rules): INTEGER a
 *   JSON number, REAL a string "%.17g", BLOB lowercase hex, TEXT as is, NULL
 *   null.
 * - the hash is the SHA-256 of the canonical JSON of the item's core object
 *   (PLAN.md 4.5; tools/r11/r11_hash.py is the reference): keys sorted,
 *   separators "," and ":", strings escaped as Python's json.dumps with
 *   ensure_ascii=False does it.
 *
 * Nothing here is cached by the engine: dtb_presets_list and dtb_styles_list
 * read data.db at every call, and the darkroom's menus are filled from them.
 * Upstream's GUI-side caches (style shortcuts in darktable.control's actions,
 * the presets menus of darktable's own GUI) do not exist in the bridge, so an
 * apply has nothing to refresh beyond the database itself.
 */
#include "dtb_internal.h"
#include "common/database.h"
#include "common/debug.h"
#include "develop/blend.h"

#include <stdlib.h>
#include <string.h>

/* PLAN.md 4.5: the columns that make an item's hash, per kind */
static const char *PRESET_CORE[] = { "name", "description", "operation", "op_version", "op_params", "enabled",
                                     "blendop_params", "blendop_version", "multi_priority", "multi_name",
                                     "multi_name_hand_edited", "model", "maker", "lens", "iso_min", "iso_max",
                                     "exposure_min", "exposure_max", "aperture_min", "aperture_max",
                                     "focal_length_min", "focal_length_max", "autoapply", "filter", "def", "format",
                                     NULL };
static const char *LAYOUT_CORE[] = { "name", "operation", "op_version", "op_params", NULL };
static const char *STYLE_CORE[] = { "name", "description", "iop_list", NULL };
static const char *ITEM_CORE[] = { "num", "module", "operation", "op_params", "enabled", "blendop_params",
                                   "blendop_version", "multi_priority", "multi_name", "multi_name_hand_edited",
                                   NULL };

/* ------------------------------------------------------------------ canonical JSON */

/* a string as Python's json.dumps(ensure_ascii=False) writes it; with
 * `ascii_astral` characters outside the BMP become \ud8xx\udcxx pairs (same
 * value, for the JNI layer that cannot pass 4-byte UTF-8) */
static void _cj_string(GString *o, const char *s, const gboolean ascii_astral)
{
  g_string_append_c(o, '"');
  for(const unsigned char *p = (const unsigned char *)s; *p; p++)
  {
    const unsigned char c = *p;
    switch(c)
    {
      case '"': g_string_append(o, "\\\""); continue;
      case '\\': g_string_append(o, "\\\\"); continue;
      case '\n': g_string_append(o, "\\n"); continue;
      case '\r': g_string_append(o, "\\r"); continue;
      case '\t': g_string_append(o, "\\t"); continue;
      case '\b': g_string_append(o, "\\b"); continue;
      case '\f': g_string_append(o, "\\f"); continue;
      default: break;
    }
    if(c < 0x20)
      g_string_append_printf(o, "\\u%04x", c);
    else if(ascii_astral && c >= 0xf0 && p[1] && p[2] && p[3])
    {
      const gunichar u = g_utf8_get_char_validated((const gchar *)p, 4);
      if(u != (gunichar)-1 && u != (gunichar)-2 && u >= 0x10000)
      {
        const gunichar v = u - 0x10000;
        g_string_append_printf(o, "\\u%04x\\u%04x", 0xd800 + (v >> 10), 0xdc00 + (v & 0x3ff));
        p += 3;
      }
      else
        g_string_append_c(o, (char)c);
    }
    else
      g_string_append_c(o, (char)c);
  }
  g_string_append_c(o, '"');
}

static int _cmp_str(const void *a, const void *b) { return strcmp(*(const char *const *)a, *(const char *const *)b); }

static void _cj_node(GString *o, JsonNode *n, const gboolean esc);

static void _cj_object(GString *o, JsonObject *obj, const gboolean esc)
{
  GList *names = json_object_get_members(obj);
  const guint len = g_list_length(names);
  const char **keys = g_new0(const char *, len + 1);
  guint i = 0;
  for(GList *l = names; l; l = g_list_next(l)) keys[i++] = l->data;
  // UTF-8 byte order is code point order, which is how Python sorts keys
  qsort(keys, len, sizeof(char *), _cmp_str);
  g_string_append_c(o, '{');
  for(i = 0; i < len; i++)
  {
    if(i) g_string_append_c(o, ',');
    _cj_string(o, keys[i], esc);
    g_string_append_c(o, ':');
    _cj_node(o, json_object_get_member(obj, keys[i]), esc);
  }
  g_string_append_c(o, '}');
  g_free(keys);
  g_list_free(names);
}

static void _cj_node(GString *o, JsonNode *n, const gboolean esc)
{
  if(!n || JSON_NODE_HOLDS_NULL(n))
  {
    g_string_append(o, "null");
    return;
  }
  if(JSON_NODE_HOLDS_OBJECT(n))
  {
    _cj_object(o, json_node_get_object(n), esc);
    return;
  }
  if(JSON_NODE_HOLDS_ARRAY(n))
  {
    JsonArray *a = json_node_get_array(n);
    g_string_append_c(o, '[');
    for(guint i = 0; i < json_array_get_length(a); i++)
    {
      if(i) g_string_append_c(o, ',');
      _cj_node(o, json_array_get_element(a, i), esc);
    }
    g_string_append_c(o, ']');
    return;
  }
  const GType t = json_node_get_value_type(n);
  if(t == G_TYPE_STRING)
    _cj_string(o, json_node_get_string(n), esc);
  else if(t == G_TYPE_BOOLEAN)
    g_string_append(o, json_node_get_boolean(n) ? "true" : "false");
  else if(t == G_TYPE_DOUBLE)
  {
    // not used by bodies (REAL travels as a string); written so that it reads back the same
    char buf[G_ASCII_DTOSTR_BUF_SIZE];
    g_string_append(o, g_ascii_formatd(buf, sizeof(buf), "%.17g", json_node_get_double(n)));
  }
  else
    g_string_append_printf(o, "%" G_GINT64_FORMAT, json_node_get_int(n));
}

/* canonical JSON of a node (g_free) */
static char *_cj(JsonNode *n, const gboolean esc)
{
  GString *o = g_string_new(NULL);
  _cj_node(o, n, esc);
  return g_string_free(o, FALSE);
}

/* ------------------------------------------------------------------ core object and hash */

/* {"c1": v1, ...} with exactly the columns `cols` of `src` (missing: null) */
static JsonNode *_pick(JsonObject *src, const char **cols)
{
  JsonObject *r = json_object_new();
  for(const char **c = cols; *c; c++)
  {
    JsonNode *v = src ? json_object_get_member(src, *c) : NULL;
    json_object_set_member(r, *c, v ? json_node_copy(v) : json_node_new(JSON_NODE_NULL));
  }
  JsonNode *n = json_node_new(JSON_NODE_OBJECT);
  json_node_take_object(n, r);
  return n;
}

static gint _by_num(gconstpointer a, gconstpointer b)
{
  JsonObject *x = json_node_get_object((JsonNode *)a), *y = json_node_get_object((JsonNode *)b);
  const gint64 nx = json_object_has_member(x, "num") ? json_object_get_int_member(x, "num") : 0;
  const gint64 ny = json_object_has_member(y, "num") ? json_object_get_int_member(y, "num") : 0;
  return nx < ny ? -1 : nx > ny;
}

static const char *_kind(JsonObject *body)
{
  JsonNode *k = body ? json_object_get_member(body, "kind") : NULL;
  return k && JSON_NODE_HOLDS_VALUE(k) && json_node_get_value_type(k) == G_TYPE_STRING ? json_node_get_string(k) : NULL;
}

/* the core object of a body (PLAN.md 4.5), NULL if the body has no known kind */
static JsonNode *_core(JsonObject *body)
{
  const char *kind = _kind(body);
  if(!kind) return NULL;
  JsonObject *r = json_object_new();
  json_object_set_string_member(r, "kind", kind);
  if(!strcmp(kind, "style"))
  {
    JsonNode *st = json_object_get_member(body, "style");
    json_object_set_member(r, "style", _pick(st && JSON_NODE_HOLDS_OBJECT(st) ? json_node_get_object(st) : NULL, STYLE_CORE));
    JsonNode *it = json_object_get_member(body, "items");
    GList *items = NULL;
    if(it && JSON_NODE_HOLDS_ARRAY(it))
    {
      JsonArray *a = json_node_get_array(it);
      for(guint i = 0; i < json_array_get_length(a); i++)
        if(JSON_NODE_HOLDS_OBJECT(json_array_get_element(a, i))) items = g_list_append(items, json_array_get_element(a, i));
    }
    items = g_list_sort(items, _by_num);  // stable, as Python's sorted
    JsonArray *out = json_array_new();
    for(GList *l = items; l; l = g_list_next(l))
      json_array_add_element(out, _pick(json_node_get_object(l->data), ITEM_CORE));
    g_list_free(items);
    json_object_set_array_member(r, "items", out);
  }
  else if(!strcmp(kind, "preset") || !strcmp(kind, "layout"))
  {
    JsonNode *row = json_object_get_member(body, "row");
    json_object_set_member(r, "row", _pick(row && JSON_NODE_HOLDS_OBJECT(row) ? json_node_get_object(row) : NULL,
                                           !strcmp(kind, "layout") ? LAYOUT_CORE : PRESET_CORE));
  }
  else
  {
    json_object_unref(r);
    return NULL;
  }
  JsonNode *n = json_node_new(JSON_NODE_OBJECT);
  json_node_take_object(n, r);
  return n;
}

/* lowercase hex SHA-256 of the canonical core (g_free); *canonical (may be
 * NULL) receives the canonical text */
static char *_hash(JsonObject *body, char **canonical)
{
  JsonNode *core = _core(body);
  if(!core) return NULL;
  char *c = _cj(core, FALSE);
  json_node_unref(core);
  char *h = g_compute_checksum_for_string(G_CHECKSUM_SHA256, c, -1);
  if(canonical)
    *canonical = c;
  else
    g_free(c);
  return h;
}

int dtb_shared_hash(const char *body_json, char **json)
{
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  *json = NULL;
  JsonNode *n = dtb__json_parse(body_json);
  if(!n) return DTB_E_ARG;
  if(!JSON_NODE_HOLDS_OBJECT(n))
  {
    json_node_unref(n);
    return dtb__err(DTB_E_ARG, "a body is a JSON object");
  }
  char *canon = NULL;
  char *h = _hash(json_node_get_object(n), &canon);
  json_node_unref(n);
  if(!h) return dtb__err(DTB_E_ARG, "the body has no kind preset, layout or style");
  JsonObject *r = json_object_new();
  json_object_set_string_member(r, "hash", h);
  json_object_set_string_member(r, "canonical", canon);
  JsonNode *rn = json_node_new(JSON_NODE_OBJECT);
  json_node_take_object(rn, r);
  *json = _cj(rn, TRUE);
  json_node_unref(rn);
  g_free(h);
  g_free(canon);
  return DTB_OK;
}

/* ------------------------------------------------------------------ columns by name */

typedef enum _aff_t { AFF_INTEGER, AFF_TEXT, AFF_BLOB, AFF_REAL, AFF_NUMERIC } _aff_t;

typedef struct _col_t
{
  char *name;
  _aff_t aff;
} _col_t;

/* SQLite's rules for a column's affinity from its declared type (datatype3.html 3.1) */
static _aff_t _affinity(const char *decl)
{
  gchar *d = g_ascii_strup(decl ? decl : "", -1);
  _aff_t a;
  if(strstr(d, "INT")) a = AFF_INTEGER;
  else if(strstr(d, "CHAR") || strstr(d, "CLOB") || strstr(d, "TEXT")) a = AFF_TEXT;
  else if(strstr(d, "BLOB") || !d[0]) a = AFF_BLOB;
  else if(strstr(d, "REAL") || strstr(d, "FLOA") || strstr(d, "DOUB")) a = AFF_REAL;
  else a = AFF_NUMERIC;
  g_free(d);
  return a;
}

/* the columns of data.<table> in table order, without `skip` (GArray of _col_t) */
static GArray *_columns(const char *table, const char *skip)
{
  GArray *cols = g_array_new(FALSE, TRUE, sizeof(_col_t));
  gchar *q = g_strdup_printf("PRAGMA data.table_info(%s)", table);
  sqlite3_stmt *stmt;
  if(sqlite3_prepare_v2(dt_database_get(darktable.db), q, -1, &stmt, NULL) == SQLITE_OK)
  {
    while(sqlite3_step(stmt) == SQLITE_ROW)
    {
      const char *name = (const char *)sqlite3_column_text(stmt, 1);
      if(!name || (skip && !strcmp(name, skip))) continue;
      _col_t c = { g_strdup(name), _affinity((const char *)sqlite3_column_text(stmt, 2)) };
      g_array_append_val(cols, c);
    }
    sqlite3_finalize(stmt);
  }
  g_free(q);
  return cols;
}

static void _columns_free(GArray *cols)
{
  for(guint i = 0; i < cols->len; i++) g_free(g_array_index(cols, _col_t, i).name);
  g_array_free(cols, TRUE);
}

static const _col_t *_column(GArray *cols, const char *name)
{
  for(guint i = 0; i < cols->len; i++)
    if(!strcmp(g_array_index(cols, _col_t, i).name, name)) return &g_array_index(cols, _col_t, i);
  return NULL;
}

/* "SELECT "c1","c2",... " for the columns */
static GString *_select_list(GArray *cols)
{
  GString *s = g_string_new("SELECT ");
  for(guint i = 0; i < cols->len; i++)
    g_string_append_printf(s, "%s\"%s\"", i ? "," : "", g_array_index(cols, _col_t, i).name);
  return s;
}

/* one column of the current row as a body value */
static JsonNode *_value(sqlite3_stmt *stmt, const int i, const _aff_t aff)
{
  JsonNode *n = json_node_new(JSON_NODE_VALUE);
  if(sqlite3_column_type(stmt, i) == SQLITE_NULL)
  {
    json_node_init_null(n);
    return n;
  }
  switch(aff)
  {
    case AFF_INTEGER:
      json_node_set_int(n, sqlite3_column_int64(stmt, i));
      break;
    case AFF_REAL:
    case AFF_NUMERIC:
      if(aff == AFF_NUMERIC && sqlite3_column_type(stmt, i) == SQLITE_INTEGER)
        json_node_set_int(n, sqlite3_column_int64(stmt, i));
      else
      {
        char buf[64];
        // "%.17g" in the C locale, as Python's format(x, ".17g")
        g_ascii_formatd(buf, sizeof(buf), "%.17g", sqlite3_column_double(stmt, i));
        json_node_set_string(n, buf);
      }
      break;
    case AFF_BLOB:
    {
      const unsigned char *b = sqlite3_column_blob(stmt, i);
      const int len = sqlite3_column_bytes(stmt, i);
      GString *h = g_string_sized_new(2 * len + 1);
      for(int k = 0; k < len; k++) g_string_append_printf(h, "%02x", b[k]);
      json_node_set_string(n, h->str);
      g_string_free(h, TRUE);
      break;
    }
    case AFF_TEXT:
    default:
    {
      const char *t = (const char *)sqlite3_column_text(stmt, i);
      json_node_set_string(n, t ? t : "");
      break;
    }
  }
  return n;
}

/* the current row as an object of every listed column */
static JsonObject *_row(sqlite3_stmt *stmt, GArray *cols)
{
  JsonObject *o = json_object_new();
  for(guint i = 0; i < cols->len; i++)
  {
    const _col_t *c = &g_array_index(cols, _col_t, i);
    json_object_set_member(o, c->name, _value(stmt, i, c->aff));
  }
  return o;
}

/* lowercase or uppercase hex -> bytes (g_free); FALSE if not hex */
static gboolean _unhex(const char *s, guint8 **out, int *len)
{
  const size_t n = strlen(s);
  if(n % 2) return FALSE;
  guint8 *b = g_malloc(n / 2 + 1);
  for(size_t i = 0; i < n / 2; i++)
  {
    const int hi = g_ascii_xdigit_value(s[2 * i]), lo = g_ascii_xdigit_value(s[2 * i + 1]);
    if(hi < 0 || lo < 0)
    {
      g_free(b);
      return FALSE;
    }
    b[i] = (guint8)(hi * 16 + lo);
  }
  *out = b;
  *len = (int)(n / 2);
  return TRUE;
}

/* bind body value `v` to parameter `k` as the receiving column's type wants
 * it; DTB_E_ARG (with a message) if the value cannot be read that way */
static int _bind(sqlite3_stmt *stmt, const int k, const _col_t *c, JsonNode *v)
{
  if(!v || JSON_NODE_HOLDS_NULL(v)) return sqlite3_bind_null(stmt, k) == SQLITE_OK ? DTB_OK : DTB_E_ENGINE;
  if(!JSON_NODE_HOLDS_VALUE(v)) return dtb__err(DTB_E_ARG, "column %s: not a plain value", c->name);
  const GType t = json_node_get_value_type(v);
  switch(c->aff)
  {
    case AFF_INTEGER:
      if(t == G_TYPE_INT64) sqlite3_bind_int64(stmt, k, json_node_get_int(v));
      else if(t == G_TYPE_BOOLEAN) sqlite3_bind_int(stmt, k, json_node_get_boolean(v));
      else if(t == G_TYPE_DOUBLE) sqlite3_bind_int64(stmt, k, (sqlite3_int64)json_node_get_double(v));
      else
      {
        const char *s = json_node_get_string(v);
        char *end = NULL;
        const gint64 x = g_ascii_strtoll(s, &end, 10);
        if(!s[0] || (end && *end)) return dtb__err(DTB_E_ARG, "column %s: '%s' is not an integer", c->name, s);
        sqlite3_bind_int64(stmt, k, x);
      }
      return DTB_OK;
    case AFF_REAL:
    case AFF_NUMERIC:
      if(t == G_TYPE_STRING)
      {
        const char *s = json_node_get_string(v);
        char *end = NULL;
        const double x = g_ascii_strtod(s, &end);
        if(!s[0] || (end && *end)) return dtb__err(DTB_E_ARG, "column %s: '%s' is not a number", c->name, s);
        sqlite3_bind_double(stmt, k, x);
      }
      else if(t == G_TYPE_INT64 && c->aff == AFF_NUMERIC)
        sqlite3_bind_int64(stmt, k, json_node_get_int(v));
      else if(t == G_TYPE_INT64 || t == G_TYPE_DOUBLE)
        sqlite3_bind_double(stmt, k, json_node_get_double(v));
      else
        return dtb__err(DTB_E_ARG, "column %s: not a number", c->name);
      return DTB_OK;
    case AFF_BLOB:
    {
      if(t != G_TYPE_STRING) return dtb__err(DTB_E_ARG, "column %s: a blob travels as hex text", c->name);
      guint8 *b = NULL;
      int len = 0;
      if(!_unhex(json_node_get_string(v), &b, &len)) return dtb__err(DTB_E_ARG, "column %s: not hexadecimal", c->name);
      sqlite3_bind_blob(stmt, k, b, len, g_free);
      return DTB_OK;
    }
    case AFF_TEXT:
    default:
      if(t == G_TYPE_STRING) sqlite3_bind_text(stmt, k, json_node_get_string(v), -1, SQLITE_TRANSIENT);
      else if(t == G_TYPE_INT64) sqlite3_bind_int64(stmt, k, json_node_get_int(v));
      else return dtb__err(DTB_E_ARG, "column %s: not text", c->name);
      return DTB_OK;
  }
}

/* INSERT [OR REPLACE] INTO data.<table> of the body columns the table has,
 * plus the fixed ones ("extra_col" = extra_val); returns a DTB code */
static int _insert(const char *table, GArray *cols, JsonObject *src, const gboolean replace, const char *extra_col,
                   const gint64 extra_val)
{
  GString *q = g_string_new(NULL);
  g_string_printf(q, "INSERT %sINTO data.%s (", replace ? "OR REPLACE " : "", table);
  GPtrArray *used = g_ptr_array_new();
  for(guint i = 0; i < cols->len; i++)
  {
    const _col_t *c = &g_array_index(cols, _col_t, i);
    if(extra_col && !strcmp(c->name, extra_col)) continue;
    if(!json_object_has_member(src, c->name)) continue;  // missing: NULL (the column's default)
    g_string_append_printf(q, "%s\"%s\"", used->len ? "," : "", c->name);
    g_ptr_array_add(used, (gpointer)c);
  }
  if(extra_col) g_string_append_printf(q, "%s\"%s\"", used->len ? "," : "", extra_col);
  g_string_append(q, ") VALUES (");
  const guint n = used->len + (extra_col ? 1 : 0);
  for(guint i = 0; i < n; i++) g_string_append_printf(q, "%s?%u", i ? "," : "", i + 1);
  g_string_append(q, ")");
  sqlite3_stmt *stmt;
  int r = DTB_OK;
  if(sqlite3_prepare_v2(dt_database_get(darktable.db), q->str, -1, &stmt, NULL) != SQLITE_OK)
    r = dtb__err(DTB_E_ENGINE, "cannot write data.%s: %s", table, sqlite3_errmsg(dt_database_get(darktable.db)));
  else
  {
    for(guint i = 0; i < used->len && r == DTB_OK; i++)
    {
      const _col_t *c = g_ptr_array_index(used, i);
      r = _bind(stmt, i + 1, c, json_object_get_member(src, c->name));
    }
    if(r == DTB_OK && extra_col) sqlite3_bind_int64(stmt, n, extra_val);
    if(r == DTB_OK && sqlite3_step(stmt) != SQLITE_DONE)
      r = dtb__err(DTB_E_ENGINE, "cannot write data.%s: %s", table, sqlite3_errmsg(dt_database_get(darktable.db)));
    sqlite3_finalize(stmt);
  }
  g_ptr_array_free(used, TRUE);
  g_string_free(q, TRUE);
  return r;
}

/* ------------------------------------------------------------------ snapshot */

static int _int_member(JsonObject *o, const char *name, gint64 *v)
{
  JsonNode *n = o ? json_object_get_member(o, name) : NULL;
  if(!n || !JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_INT64) return FALSE;
  *v = json_node_get_int(n);
  return TRUE;
}

static const char *_str_member(JsonObject *o, const char *name)
{
  JsonNode *n = o ? json_object_get_member(o, name) : NULL;
  if(!n || !JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_STRING) return NULL;
  return json_node_get_string(n);
}


static void _add_item(JsonArray *out, const char *kind, const char *op, const gint64 op_version, const char *name,
                      JsonObject *body)
{
  char *h = _hash(body, NULL);
  JsonObject *o = json_object_new();
  json_object_set_string_member(o, "kind", kind);
  json_object_set_string_member(o, "operation", op ? op : "");
  json_object_set_int_member(o, "op_version", op_version);
  json_object_set_string_member(o, "name", name ? name : "");
  json_object_set_string_member(o, "hash", h);
  json_object_set_object_member(o, "body", body);
  json_array_add_object_element(out, o);
  g_free(h);
}

int dtb_shared_snapshot(char **json)
{
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  *json = NULL;
  sqlite3 *db = dt_database_get(darktable.db);
  JsonArray *out = json_array_new();

  // presets of processing modules that are not darktable's own (writeprotect = 0)
  GArray *pc = _columns("presets", "writeprotect");
  if(!_column(pc, "name") || !_column(pc, "operation") || !_column(pc, "op_version"))
  {
    _columns_free(pc);
    json_array_unref(out);
    return dtb__err(DTB_E_ENGINE, "data.presets has no name, operation or op_version column");
  }
  GString *q = _select_list(pc);
  g_string_append(q, " FROM data.presets WHERE writeprotect = 0 ORDER BY operation, name, op_version");
  sqlite3_stmt *stmt;
  if(sqlite3_prepare_v2(db, q->str, -1, &stmt, NULL) == SQLITE_OK)
  {
    while(sqlite3_step(stmt) == SQLITE_ROW)
    {
      JsonObject *row = _row(stmt, pc);
      const char *op = _str_member(row, "operation");
      // presets of lib modules (export, modulegroups, ...) are not shared from here
      if(!op || !dtb__iop_so(op))
      {
        json_object_unref(row);
        continue;
      }
      JsonObject *body = json_object_new();
      json_object_set_string_member(body, "kind", "preset");
      json_object_set_object_member(body, "row", row);
      gint64 opv = 0;
      _int_member(row, "op_version", &opv);
      _add_item(out, "preset", op, opv, _str_member(row, "name"), body);
    }
    sqlite3_finalize(stmt);
  }
  g_string_free(q, TRUE);
  _columns_free(pc);

  // every style with its items
  GArray *sc = _columns("styles", NULL);
  GArray *ic = _columns("style_items", "styleid");
  const _col_t *idcol = _column(sc, "id");
  q = _select_list(sc);
  g_string_append(q, " FROM data.styles ORDER BY name, id");
  if(idcol && sqlite3_prepare_v2(db, q->str, -1, &stmt, NULL) == SQLITE_OK)
  {
    GString *qi = _select_list(ic);
    g_string_append(qi, " FROM data.style_items WHERE styleid = ?1 ORDER BY num");
    while(sqlite3_step(stmt) == SQLITE_ROW)
    {
      JsonObject *st = _row(stmt, sc);
      gint64 id = 0;
      _int_member(st, "id", &id);
      json_object_remove_member(st, "id");
      JsonArray *items = json_array_new();
      sqlite3_stmt *si;
      if(sqlite3_prepare_v2(db, qi->str, -1, &si, NULL) == SQLITE_OK)
      {
        sqlite3_bind_int64(si, 1, id);
        while(sqlite3_step(si) == SQLITE_ROW) json_array_add_object_element(items, _row(si, ic));
        sqlite3_finalize(si);
      }
      gchar *name = g_strdup(_str_member(st, "name"));
      JsonObject *body = json_object_new();
      json_object_set_string_member(body, "kind", "style");
      json_object_set_object_member(body, "style", st);
      json_object_set_array_member(body, "items", items);
      _add_item(out, "style", "", 0, name, body);
      g_free(name);
    }
    sqlite3_finalize(stmt);
    g_string_free(qi, TRUE);
  }
  g_string_free(q, TRUE);
  _columns_free(sc);
  _columns_free(ic);

  JsonNode *n = json_node_new(JSON_NODE_ARRAY);
  json_node_take_array(n, out);
  *json = _cj(n, TRUE);
  json_node_unref(n);
  return DTB_OK;
}

/* ------------------------------------------------------------------ apply and delete */

static int _apply_preset(JsonObject *row, gboolean *usable)
{
  const char *name = _str_member(row, "name");
  const char *op = _str_member(row, "operation");
  gint64 opv = 0, blv = 0;
  if(!name || !name[0] || !op || !_int_member(row, "op_version", &opv))
    return dtb__err(DTB_E_ARG, "a preset body needs name, operation and op_version");
  _int_member(row, "blendop_version", &blv);
  const char *problem = dtb__item_problem(op, (int)opv, (int)blv);
  if(problem)
  {
    *usable = FALSE;
    dtb__err(DTB_OK, "preset '%s' of %s not stored: %s (version %d, blend version %d)", name, op, problem, (int)opv,
             (int)blv);
    return DTB_OK;
  }
  GArray *pc = _columns("presets", NULL);
  dt_database_start_transaction(darktable.db);
  // insert or replace on darktable's unique index (name, operation, op_version), never a built-in one
  sqlite3_stmt *stmt;
  int builtin = 0;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT COUNT(*) FROM data.presets WHERE name = ?1 AND operation = ?2"
                              " AND op_version = ?3 AND writeprotect != 0",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, name, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, op, -1, SQLITE_TRANSIENT);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 3, (int)opv);
  if(sqlite3_step(stmt) == SQLITE_ROW) builtin = sqlite3_column_int(stmt, 0);
  sqlite3_finalize(stmt);
  int r = builtin ? dtb__err(DTB_E_ARG, "'%s' is one of darktable's own presets of %s", name, op)
                  : _insert("presets", pc, row, TRUE, "writeprotect", 0);
  if(r == DTB_OK)
    dt_database_release_transaction(darktable.db);
  else
    dt_database_rollback_transaction(darktable.db);
  _columns_free(pc);
  *usable = TRUE;
  return r;
}

static void _delete_style_rows(const char *name)
{
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "DELETE FROM data.style_items WHERE styleid IN (SELECT id FROM data.styles WHERE name = ?1)",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, name, -1, SQLITE_TRANSIENT);
  sqlite3_step(stmt);
  sqlite3_finalize(stmt);
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db), "DELETE FROM data.styles WHERE name = ?1", -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, name, -1, SQLITE_TRANSIENT);
  sqlite3_step(stmt);
  sqlite3_finalize(stmt);
}

static int _apply_style(JsonObject *body, gboolean *usable)
{
  JsonNode *stn = json_object_get_member(body, "style");
  JsonNode *itn = json_object_get_member(body, "items");
  if(!stn || !JSON_NODE_HOLDS_OBJECT(stn) || !itn || !JSON_NODE_HOLDS_ARRAY(itn))
    return dtb__err(DTB_E_ARG, "a style body needs \"style\" and \"items\"");
  JsonObject *st = json_node_get_object(stn);
  JsonArray *items = json_node_get_array(itn);
  const char *name = _str_member(st, "name");
  if(!name || !name[0]) return dtb__err(DTB_E_ARG, "a style body needs a name");
  for(guint i = 0; i < json_array_get_length(items); i++)
  {
    JsonNode *e = json_array_get_element(items, i);
    if(!JSON_NODE_HOLDS_OBJECT(e)) return dtb__err(DTB_E_ARG, "style item %u is not an object", i);
    JsonObject *it = json_node_get_object(e);
    const char *op = _str_member(it, "operation");
    gint64 mv = 0, bv = 0;
    _int_member(it, "module", &mv);
    _int_member(it, "blendop_version", &bv);
    const char *problem = dtb__item_problem(op ? op : "", (int)mv, (int)bv);
    if(problem)
    {
      *usable = FALSE;
      dtb__err(DTB_OK, "style '%s' not stored: item '%s' %s (version %d, blend version %d)", name, op ? op : "",
               problem, (int)mv, (int)bv);
      return DTB_OK;
    }
  }
  GArray *sc = _columns("styles", "id");
  GArray *ic = _columns("style_items", "styleid");
  dt_database_start_transaction(darktable.db);
  _delete_style_rows(name);
  sqlite3_stmt *stmt;
  gint64 id = 1;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db), "SELECT COALESCE(MAX(id), 0) + 1 FROM data.styles", -1,
                              &stmt, NULL);
  if(sqlite3_step(stmt) == SQLITE_ROW) id = sqlite3_column_int64(stmt, 0);
  sqlite3_finalize(stmt);
  int r = _insert("styles", sc, st, FALSE, "id", id);
  for(guint i = 0; i < json_array_get_length(items) && r == DTB_OK; i++)
    r = _insert("style_items", ic, json_array_get_object_element(items, i), FALSE, "styleid", id);
  if(r == DTB_OK)
    dt_database_release_transaction(darktable.db);
  else
    dt_database_rollback_transaction(darktable.db);
  _columns_free(sc);
  _columns_free(ic);
  *usable = TRUE;
  return r;
}

int dtb_shared_apply(const char *body_json, char **json)
{
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  *json = NULL;
  JsonNode *n = dtb__json_parse(body_json);
  if(!n) return DTB_E_ARG;
  JsonObject *body = JSON_NODE_HOLDS_OBJECT(n) ? json_node_get_object(n) : NULL;
  const char *kind = _kind(body);
  gboolean usable = FALSE;
  int r;
  if(!kind)
    r = dtb__err(DTB_E_ARG, "a body is a JSON object with a kind");
  else if(!strcmp(kind, "preset"))
  {
    JsonNode *row = json_object_get_member(body, "row");
    r = row && JSON_NODE_HOLDS_OBJECT(row) ? _apply_preset(json_node_get_object(row), &usable)
                                           : dtb__err(DTB_E_ARG, "a preset body needs a row");
  }
  else if(!strcmp(kind, "style"))
    r = _apply_style(body, &usable);
  else
    r = dtb__err(DTB_E_ARG, "kind '%s' is not applied by the engine (layouts are the app's)", kind);
  json_node_unref(n);
  if(r != DTB_OK) return r;
  *json = g_strdup(usable ? "{\"usable\":true}" : "{\"usable\":false}");
  return DTB_OK;
}

int dtb_shared_delete(const char *kind, const char *operation, int op_version, const char *name)
{
  NEED_INIT();
  if(!kind || !name) return dtb__err(DTB_E_ARG, "kind and name required");
  int changed = 0;
  if(!strcmp(kind, "preset"))
  {
    if(!operation) return dtb__err(DTB_E_ARG, "a preset needs its operation");
    sqlite3_stmt *stmt;
    DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                                "DELETE FROM data.presets WHERE name = ?1 AND operation = ?2 AND op_version = ?3"
                                " AND writeprotect = 0",
                                -1, &stmt, NULL);
    DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, name, -1, SQLITE_TRANSIENT);
    DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 2, operation, -1, SQLITE_TRANSIENT);
    DT_DEBUG_SQLITE3_BIND_INT(stmt, 3, op_version);
    sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    changed = sqlite3_changes(dt_database_get(darktable.db));
  }
  else if(!strcmp(kind, "style"))
  {
    sqlite3_stmt *stmt;
    DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db), "SELECT COUNT(*) FROM data.styles WHERE name = ?1",
                                -1, &stmt, NULL);
    DT_DEBUG_SQLITE3_BIND_TEXT(stmt, 1, name, -1, SQLITE_TRANSIENT);
    if(sqlite3_step(stmt) == SQLITE_ROW) changed = sqlite3_column_int(stmt, 0);
    sqlite3_finalize(stmt);
    if(changed)
    {
      dt_database_start_transaction(darktable.db);
      _delete_style_rows(name);
      dt_database_release_transaction(darktable.db);
    }
  }
  else
    return dtb__err(DTB_E_ARG, "kind '%s' is not kept by the engine", kind);
  return changed ? DTB_OK : dtb__err(DTB_E_NOT_FOUND, "no %s '%s'", kind, name);
}

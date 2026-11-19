// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: modules and parameters (Engine API v1, section 2.4).
 *
 * Parameters are read and written through darktable's introspection
 * (common/introspection.h): every module's params struct is described at
 * build time, with ranges from its $MIN/$MAX annotations and enum constant
 * names. Paths address fields inside it: "exposure", "curve[2].x",
 * "equalizer_y[0][3]".
 */
#include "dtb_internal.h"

#include <ctype.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* introspection: paths, values                                       */

int dtb__field_resolve(dt_iop_module_t *m, void *params, const char *path, dtb_field_ref_t *out)
{
  if(!m->so->have_introspection || !m->so->get_introspection)
    return dtb__err(DTB_E_NOT_FOUND, "%s has no parameter description", m->op);
  if(!path || !*path) return dtb__err(DTB_E_ARG, "empty parameter path");
  dt_introspection_t *in = m->so->get_introspection();
  dt_introspection_field_t *f = in ? in->field : NULL;
  uint8_t *p = params;
  if(!f) return dtb__err(DTB_E_NOT_FOUND, "%s has no parameter description", m->op);

  const char *s = path;
  for(;;)
  {
    // a field name
    const char *start = s;
    while(*s && (isalnum((unsigned char)*s) || *s == '_')) s++;
    if(s == start) return dtb__err(DTB_E_ARG, "bad parameter path '%s'", path);
    gchar *name = g_strndup(start, s - start);
    if(f->header.type != DT_INTROSPECTION_TYPE_STRUCT && f->header.type != DT_INTROSPECTION_TYPE_UNION)
    {
      dtb__err(DTB_E_NOT_FOUND, "%s: '%s' in '%s' is not inside a struct", m->op, name, path);
      g_free(name);
      return DTB_E_NOT_FOUND;
    }
    dt_introspection_field_t *child = NULL;
    uint8_t *cp = dt_introspection_get_child(f, p, name, &child);
    if(!cp || !child)
    {
      dtb__err(DTB_E_NOT_FOUND, "%s has no parameter '%s' (path '%s')", m->op, name, path);
      g_free(name);
      return DTB_E_NOT_FOUND;
    }
    g_free(name);
    f = child;
    p = cp;
    // array steps
    while(*s == '[')
    {
      char *end = NULL;
      const long idx = strtol(s + 1, &end, 10);
      if(!end || end == s + 1 || *end != ']') return dtb__err(DTB_E_ARG, "bad index in '%s'", path);
      if(f->header.type != DT_INTROSPECTION_TYPE_ARRAY)
        return dtb__err(DTB_E_NOT_FOUND, "%s: '%s' indexes something that is not an array", m->op, path);
      if(idx < 0 || (size_t)idx >= f->Array.count)
        return dtb__err(DTB_E_NOT_FOUND, "%s: index %ld outside 0..%zu in '%s'", m->op, idx,
                        f->Array.count - 1, path);
      dt_introspection_field_t *elem = NULL;
      p = dt_introspection_access_array(f, p, (unsigned)idx, &elem);
      f = elem;
      s = end + 1;
    }
    if(*s == '\0') break;
    if(*s != '.') return dtb__err(DTB_E_ARG, "bad parameter path '%s'", path);
    s++;
  }
  out->f = f;
  out->p = p;
  return DTB_OK;
}

static gboolean _json_number(JsonNode *v, double *d, gboolean *integral)
{
  if(!v || JSON_NODE_TYPE(v) != JSON_NODE_VALUE) return FALSE;
  const GType t = json_node_get_value_type(v);
  if(t == G_TYPE_INT64)
  {
    *d = (double)json_node_get_int(v);
    *integral = TRUE;
    return TRUE;
  }
  if(t == G_TYPE_DOUBLE)
  {
    *d = json_node_get_double(v);
    *integral = isfinite(*d) && *d == floor(*d);
    return TRUE;
  }
  return FALSE;
}

static const char *_type_name(const dt_introspection_type_t t)
{
  switch(t)
  {
    case DT_INTROSPECTION_TYPE_FLOAT: case DT_INTROSPECTION_TYPE_DOUBLE: return "a float";
    case DT_INTROSPECTION_TYPE_BOOL: return "a bool";
    case DT_INTROSPECTION_TYPE_ENUM: return "an enum (constant name as a string)";
    case DT_INTROSPECTION_TYPE_STRUCT: return "a struct (set its fields one by one)";
    case DT_INTROSPECTION_TYPE_UNION: return "a union (set its fields one by one)";
    case DT_INTROSPECTION_TYPE_ARRAY: return "an array (set its elements one by one)";
    default: return "an integer";
  }
}

int dtb__field_set(const char *what, const dtb_field_ref_t *r, JsonNode *v)
{
  dt_introspection_field_t *f = r->f;
  void *p = r->p;
  double d = 0.0;
  gboolean integral = FALSE;
  const dt_introspection_type_t t = f->header.type;

#define NEED_NUMBER()                                                                   \
  if(!_json_number(v, &d, &integral))                                                   \
    return dtb__err(DTB_E_TYPE, "%s is %s; give a JSON number", what, _type_name(t));
#define NEED_INTEGER(lo, hi)                                                            \
  NEED_NUMBER();                                                                        \
  if(!integral) return dtb__err(DTB_E_TYPE, "%s is an integer; %g is not", what, d);    \
  if(d < (double)(lo) || d > (double)(hi))                                              \
    return dtb__err(DTB_E_RANGE, "%s: %g outside [%g, %g]", what, d, (double)(lo), (double)(hi));

  switch(t)
  {
    case DT_INTROSPECTION_TYPE_FLOAT:
      NEED_NUMBER();
      if(!isfinite(d) || d < f->Float.Min || d > f->Float.Max)
        return dtb__err(DTB_E_RANGE, "%s: %g outside [%g, %g]", what, d, f->Float.Min, f->Float.Max);
      *(float *)p = (float)d;
      return DTB_OK;
    case DT_INTROSPECTION_TYPE_DOUBLE:
      NEED_NUMBER();
      if(!isfinite(d) || d < f->Double.Min || d > f->Double.Max)
        return dtb__err(DTB_E_RANGE, "%s: %g outside [%g, %g]", what, d, f->Double.Min, f->Double.Max);
      *(double *)p = d;
      return DTB_OK;
    case DT_INTROSPECTION_TYPE_CHAR:   { NEED_INTEGER(f->Char.Min, f->Char.Max);     *(char *)p = (char)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_INT8:   { NEED_INTEGER(f->Int8.Min, f->Int8.Max);     *(int8_t *)p = (int8_t)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_UINT8:  { NEED_INTEGER(f->UInt8.Min, f->UInt8.Max);   *(uint8_t *)p = (uint8_t)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_SHORT:  { NEED_INTEGER(f->Short.Min, f->Short.Max);   *(short *)p = (short)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_USHORT: { NEED_INTEGER(f->UShort.Min, f->UShort.Max); *(unsigned short *)p = (unsigned short)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_INT:    { NEED_INTEGER(f->Int.Min, f->Int.Max);       *(int *)p = (int)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_UINT:   { NEED_INTEGER(f->UInt.Min, f->UInt.Max);     *(unsigned int *)p = (unsigned int)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_LONG:   { NEED_INTEGER(f->Long.Min, f->Long.Max);     *(long *)p = (long)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_ULONG:  { NEED_INTEGER(f->ULong.Min, f->ULong.Max);   *(unsigned long *)p = (unsigned long)d; return DTB_OK; }
    case DT_INTROSPECTION_TYPE_BOOL:
      if(!v || JSON_NODE_TYPE(v) != JSON_NODE_VALUE || json_node_get_value_type(v) != G_TYPE_BOOLEAN)
        return dtb__err(DTB_E_TYPE, "%s is a bool; give true or false", what);
      *(gboolean *)p = json_node_get_boolean(v) ? TRUE : FALSE;
      return DTB_OK;
    case DT_INTROSPECTION_TYPE_ENUM:
    {
      if(!v || JSON_NODE_TYPE(v) != JSON_NODE_VALUE || json_node_get_value_type(v) != G_TYPE_STRING)
        return dtb__err(DTB_E_TYPE, "%s is an enum; give one of its constant names as a string", what);
      int value = 0;
      if(!dt_introspection_get_enum_value(f, json_node_get_string(v), &value))
        return dtb__err(DTB_E_RANGE, "%s: '%s' is not one of its constants", what, json_node_get_string(v));
      *(int *)p = value;
      return DTB_OK;
    }
    case DT_INTROSPECTION_TYPE_ARRAY:
      if(f->Array.type == DT_INTROSPECTION_TYPE_CHAR)
      {
        if(!v || JSON_NODE_TYPE(v) != JSON_NODE_VALUE || json_node_get_value_type(v) != G_TYPE_STRING)
          return dtb__err(DTB_E_TYPE, "%s is a text field; give a JSON string", what);
        const char *s = json_node_get_string(v);
        const size_t n = strlen(s);
        if(n >= f->Array.count)
          return dtb__err(DTB_E_RANGE, "%s: text of %zu bytes, at most %zu fit", what, n, f->Array.count - 1);
        memset(p, 0, f->Array.count);
        memcpy(p, s, n);
        return DTB_OK;
      }
      return dtb__err(DTB_E_TYPE, "%s is %s", what, _type_name(t));
    default:
      return dtb__err(DTB_E_TYPE, "%s is %s", what, _type_name(t));
  }
#undef NEED_NUMBER
#undef NEED_INTEGER
}

static void _add_double(JsonBuilder *b, const double v)
{
  // JSON has no NaN or infinity; some modules keep NaN in unused fields
  // (temperature's "various"), which json-glib would write as "nan.0"
  if(isfinite(v))
    json_builder_add_double_value(b, v);
  else
    json_builder_add_null_value(b);
}

void dtb__field_emit(JsonBuilder *b, dt_introspection_field_t *f, const uint8_t *p)
{
  switch(f->header.type)
  {
    case DT_INTROSPECTION_TYPE_FLOAT:  _add_double(b, *(const float *)p); break;
    case DT_INTROSPECTION_TYPE_DOUBLE: _add_double(b, *(const double *)p); break;
    case DT_INTROSPECTION_TYPE_FLOATCOMPLEX:
    {
      const float *c = (const float *)p;  // float _Complex: real, imaginary
      json_builder_begin_array(b);
      _add_double(b, c[0]);
      _add_double(b, c[1]);
      json_builder_end_array(b);
      break;
    }
    case DT_INTROSPECTION_TYPE_CHAR:   json_builder_add_int_value(b, *(const char *)p); break;
    case DT_INTROSPECTION_TYPE_INT8:   json_builder_add_int_value(b, *(const int8_t *)p); break;
    case DT_INTROSPECTION_TYPE_UINT8:  json_builder_add_int_value(b, *(const uint8_t *)p); break;
    case DT_INTROSPECTION_TYPE_SHORT:  json_builder_add_int_value(b, *(const short *)p); break;
    case DT_INTROSPECTION_TYPE_USHORT: json_builder_add_int_value(b, *(const unsigned short *)p); break;
    case DT_INTROSPECTION_TYPE_INT:    json_builder_add_int_value(b, *(const int *)p); break;
    case DT_INTROSPECTION_TYPE_UINT:   json_builder_add_int_value(b, *(const unsigned int *)p); break;
    case DT_INTROSPECTION_TYPE_LONG:   json_builder_add_int_value(b, *(const long *)p); break;
    case DT_INTROSPECTION_TYPE_ULONG:  json_builder_add_int_value(b, (gint64)*(const unsigned long *)p); break;
    case DT_INTROSPECTION_TYPE_BOOL:   json_builder_add_boolean_value(b, *(const gboolean *)p != 0); break;
    case DT_INTROSPECTION_TYPE_ENUM:
    {
      const int v = *(const int *)p;
      const char *name = dt_introspection_get_enum_name(f, v);
      if(name)
        json_builder_add_string_value(b, name);
      else
        json_builder_add_int_value(b, v);  // a value outside the enum: kept as a number
      break;
    }
    case DT_INTROSPECTION_TYPE_STRUCT:
    case DT_INTROSPECTION_TYPE_UNION:
    {
      json_builder_begin_object(b);
      dt_introspection_field_t **fields =
        f->header.type == DT_INTROSPECTION_TYPE_STRUCT ? f->Struct.fields : f->Union.fields;
      for(dt_introspection_field_t **c = fields; c && *c; c++)
      {
        json_builder_set_member_name(b, (*c)->header.field_name);
        // offsets are from the start of the params; relative to the parent
        // as dt_introspection_get_child() computes them
        dtb__field_emit(b, *c, p + ((*c)->header.offset - f->header.offset));
      }
      json_builder_end_object(b);
      break;
    }
    case DT_INTROSPECTION_TYPE_ARRAY:
    {
      if(f->Array.type == DT_INTROSPECTION_TYPE_CHAR)
      {
        // text fields (file names, camera and lens names)
        const size_t n = strnlen((const char *)p, f->Array.count);
        gchar *s = g_utf8_make_valid((const char *)p, n);
        json_builder_add_string_value(b, s);
        g_free(s);
        break;
      }
      json_builder_begin_array(b);
      const size_t step = f->Array.field->header.size;
      for(size_t i = 0; i < f->Array.count; i++) dtb__field_emit(b, f->Array.field, p + i * step);
      json_builder_end_array(b);
      break;
    }
    default: json_builder_add_null_value(b); break;
  }
}

/* typed access for the module actions */
static void *_param_ptr(dt_iop_module_t *m, const char *path, dt_introspection_type_t t)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, m->params, path, &r)) return NULL;
  if(t == DT_INTROSPECTION_TYPE_ARRAY)
    return r.f->header.type == t && r.f->Array.type == DT_INTROSPECTION_TYPE_CHAR ? r.p : NULL;
  if(t == DT_INTROSPECTION_TYPE_INT)
    return r.f->header.type == DT_INTROSPECTION_TYPE_INT || r.f->header.type == DT_INTROSPECTION_TYPE_ENUM
               || r.f->header.type == DT_INTROSPECTION_TYPE_BOOL
             ? r.p : NULL;
  return r.f->header.type == t ? r.p : NULL;
}

float *dtb__param_float(dt_iop_module_t *m, const char *path)
{
  return _param_ptr(m, path, DT_INTROSPECTION_TYPE_FLOAT);
}

int *dtb__param_int(dt_iop_module_t *m, const char *path)
{
  return _param_ptr(m, path, DT_INTROSPECTION_TYPE_INT);
}

char *dtb__param_chars(dt_iop_module_t *m, const char *path, size_t *size)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, m->params, path, &r)) return NULL;
  if(r.f->header.type != DT_INTROSPECTION_TYPE_ARRAY || r.f->Array.type != DT_INTROSPECTION_TYPE_CHAR)
    return NULL;
  if(size) *size = r.f->Array.count;
  return (char *)r.p;
}

/* ------------------------------------------------------------------ */
/* modules                                                            */

static gboolean _can_switch(const dt_iop_module_t *m)
{
  // desktop shows no on/off switch for these (always on, or always off)
  return !m->hide_enable_button;
}

static gboolean _in_history(const dt_iop_module_t *m)
{
  for(GList *h = dtb_S.dev.history; h; h = g_list_next(h))
    if(((dt_dev_history_item_t *)h->data)->module == m) return TRUE;
  return FALSE;
}

int dtb_modules(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  // dev.iop is kept sorted by iop_order (dt_sort_iop_by_order)
  for(GList *l = dtb_S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *m = l->data;
    const int flags = m->flags();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "op");
    json_builder_add_string_value(b, m->op);
    json_builder_set_member_name(b, "name");
    json_builder_add_string_value(b, m->name());
    json_builder_set_member_name(b, "priority");
    json_builder_add_int_value(b, m->multi_priority);
    json_builder_set_member_name(b, "instance_name");
    json_builder_add_string_value(b, m->multi_name);
    json_builder_set_member_name(b, "iop_order");
    json_builder_add_int_value(b, m->iop_order);
    json_builder_set_member_name(b, "enabled");
    json_builder_add_boolean_value(b, m->enabled);
    json_builder_set_member_name(b, "can_switch");
    json_builder_add_boolean_value(b, _can_switch(m));
    json_builder_set_member_name(b, "default_enabled");
    json_builder_add_boolean_value(b, m->default_enabled);
    json_builder_set_member_name(b, "hidden");
    json_builder_add_boolean_value(b, dt_iop_is_hidden(m));
    json_builder_set_member_name(b, "deprecated");
    json_builder_add_boolean_value(b, (flags & IOP_FLAGS_DEPRECATED) != 0);
    json_builder_set_member_name(b, "one_instance");
    json_builder_add_boolean_value(b, (flags & IOP_FLAGS_ONE_INSTANCE) != 0);
    json_builder_set_member_name(b, "supports_blending");
    json_builder_add_boolean_value(b, (flags & IOP_FLAGS_SUPPORTS_BLENDING) != 0);
    json_builder_set_member_name(b, "version");
    json_builder_add_int_value(b, m->version());
    json_builder_set_member_name(b, "in_history");
    json_builder_add_boolean_value(b, _in_history(m));
    json_builder_set_member_name(b, "params");
    dt_introspection_t *in = m->so->have_introspection && m->so->get_introspection
                               ? m->so->get_introspection() : NULL;
    if(in && in->field && m->params)
      dtb__field_emit(b, in->field, m->params);
    else
    {
      json_builder_begin_object(b);
      json_builder_end_object(b);
    }
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* changing parameters                                                */

static int _find_writable(const char *op, int prio, dt_iop_module_t **out)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = dtb__find(op, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "(null)", prio);
  *out = m;
  return DTB_OK;
}

/* store the new params as one change. Moving a control switches the module
 * on on desktop (dt_dev_add_history_item(..., TRUE) in the bauhaus
 * callbacks); modules without a switch keep their state */
static void _commit_params(dt_iop_module_t *m, const void *params)
{
  const double t0 = dtb__t();
  dtb__change_begin(DTB_CHANGE_PARAMS, m);
  dtb__tlog("param.change_begin", t0);
  const double t1 = dtb__t();
  memcpy(m->params, params, m->params_size);
  dtb__history_add_params(m, _can_switch(m));
  dtb__change_end(DTB_CHANGE_PARAMS, m);
  dtb__tlog("param.history_add", t1);
}

int dtb_param_set(const char *op, int prio, const char *path, const char *value_json)
{
  dt_iop_module_t *m = NULL;
  int r = _find_writable(op, prio, &m);
  if(r) return r;
  JsonNode *v = dtb__json_parse(value_json);
  if(!v) return DTB_E_ARG;
  // checked on a copy: nothing changes if the value is refused
  void *tmp = dtb__memdup(m->params, m->params_size);
  dtb_field_ref_t ref;
  r = dtb__field_resolve(m, tmp, path, &ref);
  if(!r)
  {
    gchar *what = g_strdup_printf("%s.%s", m->op, path);
    r = dtb__field_set(what, &ref, v);
    g_free(what);
  }
  if(!r) _commit_params(m, tmp);
  g_free(tmp);
  json_node_unref(v);
  return r;
}

int dtb_params_set(const char *op, int prio, const char *values_json)
{
  dt_iop_module_t *m = NULL;
  int r = _find_writable(op, prio, &m);
  if(r) return r;
  JsonNode *root = dtb__json_parse(values_json);
  if(!root) return DTB_E_ARG;
  if(JSON_NODE_TYPE(root) != JSON_NODE_OBJECT)
  {
    json_node_unref(root);
    return dtb__err(DTB_E_ARG, "params_set takes a JSON object {path: value}");
  }
  void *tmp = dtb__memdup(m->params, m->params_size);
  JsonObject *o = json_node_get_object(root);
  GList *members = json_object_get_members(o);
  for(GList *k = members; k && !r; k = g_list_next(k))
  {
    const char *path = k->data;
    dtb_field_ref_t ref;
    r = dtb__field_resolve(m, tmp, path, &ref);
    if(r) break;
    gchar *what = g_strdup_printf("%s.%s", m->op, path);
    r = dtb__field_set(what, &ref, json_object_get_member(o, path));
    g_free(what);
  }
  g_list_free(members);
  if(!r) _commit_params(m, tmp);
  g_free(tmp);
  json_node_unref(root);
  return r;
}

int dtb_set_enabled(const char *op, int prio, int enabled)
{
  dt_iop_module_t *m = NULL;
  const int r = _find_writable(op, prio, &m);
  if(r) return r;
  if(!_can_switch(m)) return dtb__err(DTB_E_ARG, "%s cannot be switched on or off", m->op);
  const gboolean on = enabled ? TRUE : FALSE;
  if(m->enabled == on) return DTB_OK;
  // as the module's power button (imageop.c, _gui_off_callback)
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  m->enabled = on;
  dtb__history_add(m, FALSE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  return DTB_OK;
}

/* follows the reset button (imageop.c, _gui_reset_clicked): params and
 * blend params back to the defaults computed for this image
 * (dt_iop_reset_params), then a history item that switches the module on.
 * Known difference: dt_iop_reset_params() removes a drawn mask through the
 * global develop object (darktable.develop), which is not the session's, so
 * the mask's shapes stay in the session's forms list, unused. */
int dtb_module_reset(const char *op, int prio)
{
  dt_iop_module_t *m = NULL;
  const int r = _find_writable(op, prio, &m);
  if(r) return r;
  if(!m->default_enabled && m->hide_enable_button)
    return dtb__err(DTB_E_ARG, "%s is always off for this image; nothing to reset", m->op);
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  dt_iop_reset_params(m);
  dtb__history_add(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  return DTB_OK;
}

int dtb_module_action(const char *op, int prio, const char *action, const char *args_json,
                      char **result_json)
{
  if(result_json) *result_json = NULL;
  NEED_SESSION();
  if(!action || !result_json) return dtb__err(DTB_E_ARG, "action and result pointer are required");
  dt_iop_module_t *m = dtb__find(op, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "(null)", prio);
  JsonNode *args = NULL;
  if(args_json && *args_json)
  {
    args = dtb__json_parse(args_json);
    if(!args) return DTB_E_ARG;
    if(JSON_NODE_TYPE(args) != JSON_NODE_OBJECT)
    {
      json_node_unref(args);
      return dtb__err(DTB_E_ARG, "action arguments must be a JSON object");
    }
  }
  JsonObject *o = args ? json_node_get_object(args) : NULL;
  JsonObject *empty = o ? NULL : json_object_new();
  const int r = dtb__module_action(m, action, o ? o : empty, result_json);
  if(args) json_node_unref(args);
  if(empty) json_object_unref(empty);
  return r;
}

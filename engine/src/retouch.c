// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: retouch (P3m, docs/design/engine-api.md 6.10).
 *
 * Retouch keeps its shapes in a group of its own, not in its mask: at the
 * pinned commit a module with IOP_FLAGS_NO_MASKS has two groups, its own
 * (blend_params->mask_id, type DT_MASKS_GROUP | DT_MASKS_CLONE) and its blend
 * mask (dt_develop_blend_mask_group_id). A shape is a circle, ellipse, path
 * or brush form with DT_MASKS_CLONE (clone, heal: form->source is where the
 * pixels come from) or DT_MASKS_NON_CLONE (blur, fill). The module's params
 * hold one rt_forms entry per shape (formid, scale, algorithm, blur and fill
 * settings); desktop keeps them in step with the group in gui_update
 * (retouch.c rt_resynch_params), which the bridge's session never calls. So
 * every change here runs the same resynchronisation (_resync, a copy of
 * rt_resynch_params) before the history item is recorded, which then holds
 * the forms and the params together (dt_dev_add_masks_history_item_ext).
 *
 * The params are reached through darktable's introspection, never through
 * retouch's private structure: the entry size and the field offsets are
 * read from it (_layout), entries are moved as whole blocks.
 *
 * Desktop behaviour followed (retouch.c at the pinned commit):
 *  - new shape: rt_add_shape (CLONE for clone and heal, NON_CLONE for blur and
 *    fill, by the module's current algorithm), dt_masks_gui_form_save_creation
 *    (unique name, dt_masks_group_insert_point: opacity from
 *    plugins/darkroom/masks/opacity), module switched on;
 *    rt_resynch_params gives it scale = curr_scale, the current algorithm and
 *    the current blur or fill settings, distort_mode 2;
 *  - ctrl+click on an algorithm (rt_select_algorithm_callback): only
 *    clone <-> heal and blur <-> fill, the module's algorithm follows;
 *  - blur and fill controls (gui_changed, colour callbacks): the module's
 *    value and the selected shape's;
 *  - selecting a shape (rt_shape_selection_changed): the module's algorithm,
 *    blur or fill settings take the shape's;
 *  - mask opacity (rt_masks_form_change_opacity): clamped to 0.05 .. 1;
 *  - removing a shape (dt_masks_form_remove, retouch shapes are always
 *    deleted for good): out of the group and the session, the group itself
 *    removed when it empties (mask_id back to none).
 * Wavelet settings (num_scales, curr_scale, merge_from_scale,
 * preview_levels) are never written here.
 */
#include "dtb_internal.h"

#include "develop/blend.h"
#include "develop/masks.h"

#include <math.h>
#include <string.h>

static const char *const _algos[] = { "unused", "clone", "heal", "blur", "fill" };
#define ALGO_CLONE 1
#define ALGO_HEAL 2
#define ALGO_BLUR 3
#define ALGO_FILL 4
static const char *const _blurs[] = { "gaussian", "bilateral" };
static const char *const _fills[] = { "erase", "color" };

/* where things are in retouch's params, from the introspection */
typedef struct rt_layout_t
{
  size_t first;   // offset of rt_forms[0]
  size_t stride;  // size of one entry
  int n;          // entries
  // offsets inside an entry
  size_t formid, scale, algorithm, blur_type, blur_radius, fill_mode, fill_color, fill_brightness, distort_mode;
  // offsets inside the params
  size_t p_algorithm, p_num_scales, p_curr_scale, p_merge_from_scale, p_blur_type, p_blur_radius, p_fill_mode,
      p_fill_color, p_fill_brightness;
} rt_layout_t;

static int _off(dt_iop_module_t *m, const char *path, size_t *out)
{
  dtb_field_ref_t r;
  const int e = dtb__field_resolve(m, m->params, path, &r);
  if(e) return e;
  *out = (size_t)(r.p - (uint8_t *)m->params);
  return DTB_OK;
}

static int _layout(dt_iop_module_t *m, rt_layout_t *l)
{
  memset(l, 0, sizeof(*l));
  dtb_field_ref_t arr;
  int r = dtb__field_resolve(m, m->params, "rt_forms", &arr);
  if(r || arr.f->header.type != DT_INTROSPECTION_TYPE_ARRAY || arr.f->Array.count < 2)
    return dtb__err(DTB_E_ENGINE, "retouch: no shape table in its parameter description");
  l->n = (int)arr.f->Array.count;
  size_t second = 0;
  r = _off(m, "rt_forms[0]", &l->first);
  if(!r) r = _off(m, "rt_forms[1]", &second);
  if(r) return r;
  l->stride = second - l->first;
  static const char *const ef[] = { "formid", "scale", "algorithm", "blur_type", "blur_radius", "fill_mode",
                                    "fill_color[0]", "fill_brightness", "distort_mode" };
  size_t *eo[] = { &l->formid, &l->scale, &l->algorithm, &l->blur_type, &l->blur_radius, &l->fill_mode,
                   &l->fill_color, &l->fill_brightness, &l->distort_mode };
  for(size_t k = 0; k < G_N_ELEMENTS(ef) && !r; k++)
  {
    gchar *path = g_strdup_printf("rt_forms[0].%s", ef[k]);
    r = _off(m, path, eo[k]);
    g_free(path);
    if(!r) *eo[k] -= l->first;
  }
  static const char *const pf[] = { "algorithm", "num_scales", "curr_scale", "merge_from_scale", "blur_type",
                                    "blur_radius", "fill_mode", "fill_color[0]", "fill_brightness" };
  size_t *po[] = { &l->p_algorithm, &l->p_num_scales, &l->p_curr_scale, &l->p_merge_from_scale, &l->p_blur_type,
                   &l->p_blur_radius, &l->p_fill_mode, &l->p_fill_color, &l->p_fill_brightness };
  for(size_t k = 0; k < G_N_ELEMENTS(pf) && !r; k++) r = _off(m, pf[k], po[k]);
  return r ? dtb__err(DTB_E_ENGINE, "retouch: its parameters are not as expected (%s)", dtb_last_error()) : DTB_OK;
}

#define PI_(base, off) ((int32_t *)((uint8_t *)(base) + (off)))
#define PF_(base, off) ((float *)((uint8_t *)(base) + (off)))
#define ENTRY(l, params, i) ((uint8_t *)(params) + (l)->first + (size_t)(i) * (l)->stride)

static dt_masks_form_t *_group(dt_iop_module_t *m)
{
  if(!m->blend_params) return NULL;
  dt_masks_form_t *g = dt_masks_get_from_id(&dtb_S.dev, m->blend_params->mask_id);
  return g && (g->type & DT_MASKS_GROUP) && (g->type & DT_MASKS_CLONE) ? g : NULL;
}

static int _index(const rt_layout_t *l, const void *params, const dt_mask_id_t id)
{
  if(!dt_is_valid_maskid(id)) return -1;
  for(int i = 0; i < l->n; i++)
    if(*PI_(ENTRY(l, params, i), l->formid) == id) return i;
  return -1;
}

/* retouch.c rt_resynch_params on m->params */
static void _resync(dt_iop_module_t *m, const rt_layout_t *l)
{
  void *p = m->params;
  uint8_t *nd = g_malloc0((size_t)l->n * l->stride);
  dt_masks_form_t *grp = _group(m);
  int k = 0;
  for(GList *pts = grp ? grp->points : NULL; pts && k < l->n; pts = g_list_next(pts))
  {
    const dt_masks_point_group_t *pt = pts->data;
    if(!pt) continue;
    const int i = _index(l, p, pt->formid);
    uint8_t *e = nd + (size_t)k * l->stride;
    if(i >= 0)
    {
      memcpy(e, ENTRY(l, p, i), l->stride);
      k++;
    }
    else if(dt_masks_get_from_id(&dtb_S.dev, pt->formid))
    {
      *PI_(e, l->formid) = pt->formid;
      *PI_(e, l->scale) = *PI_(p, l->p_curr_scale);
      const int algo = *PI_(p, l->p_algorithm);
      *PI_(e, l->algorithm) = algo;
      *PI_(e, l->distort_mode) = 2;
      if(algo == ALGO_BLUR)
      {
        *PI_(e, l->blur_type) = *PI_(p, l->p_blur_type);
        *PF_(e, l->blur_radius) = *PF_(p, l->p_blur_radius);
      }
      else if(algo == ALGO_FILL)
      {
        *PI_(e, l->fill_mode) = *PI_(p, l->p_fill_mode);
        memcpy(PF_(e, l->fill_color), PF_(p, l->p_fill_color), 3 * sizeof(float));
        *PF_(e, l->fill_brightness) = *PF_(p, l->p_fill_brightness);
      }
      k++;
    }
  }
  memcpy(ENTRY(l, p, 0), nd, (size_t)l->n * l->stride);
  g_free(nd);
}

static int _module(const char *op, const int priority, dt_iop_module_t **out, rt_layout_t *l)
{
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "(null)", priority);
  if(strcmp(m->op, "retouch")) return dtb__err(DTB_E_ARG, "%s is not retouch", m->op);
  if(!m->blend_params) return dtb__err(DTB_E_ENGINE, "retouch %d has no blend parameters", priority);
  *out = m;
  return _layout(m, l);
}

static int _name_of(const char *s, const char *const *names, const int n, const int from, const char *what)
{
  for(int k = from; s && k < n; k++)
    if(!strcmp(s, names[k])) return k;
  return dtb__err(DTB_E_ARG, "%s: unknown name '%s'", what, s ? s : "(none)");
}

static const char *_name(const int v, const char *const *names, const int n)
{
  return v >= 0 && v < n ? names[v] : "unknown";
}

static void _f(JsonBuilder *b, const char *k, const double v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_double_value(b, v);
}

static void _i(JsonBuilder *b, const char *k, const gint64 v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_int_value(b, v);
}

static void _s(JsonBuilder *b, const char *k, const char *v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_string_value(b, v);
}

static const char *_shape_type(const dt_masks_type_t t)
{
  return (t & DT_MASKS_CIRCLE) ? "circle" : (t & DT_MASKS_ELLIPSE) ? "ellipse" : (t & DT_MASKS_PATH) ? "path"
         : (t & DT_MASKS_BRUSH) ? "brush" : "unknown";
}

static dt_masks_point_group_t *_point(dt_masks_form_t *grp, const dt_mask_id_t id)
{
  for(GList *l = grp ? grp->points : NULL; l; l = g_list_next(l))
    if(((dt_masks_point_group_t *)l->data)->formid == id) return l->data;
  return NULL;
}

int dtb_retouch(const char *op, int priority, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  dt_iop_module_t *m = NULL;
  rt_layout_t l;
  int r = _module(op, priority, &m, &l);
  if(r) return r;
  const void *p = m->params;
  dt_masks_form_t *grp = _group(m);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "group");
  if(grp) json_builder_add_int_value(b, grp->formid);
  else json_builder_add_null_value(b);
  // desktop's "shapes" count: the group's forms, every scale
  _i(b, "count", grp ? g_list_length(grp->points) : 0);
  _i(b, "max", l.n);
  _s(b, "algorithm", _name(*PI_(p, l.p_algorithm), _algos, G_N_ELEMENTS(_algos)));
  _i(b, "num_scales", *PI_(p, l.p_num_scales));
  _i(b, "curr_scale", *PI_(p, l.p_curr_scale));
  _i(b, "merge_from_scale", *PI_(p, l.p_merge_from_scale));
  _s(b, "blur_type", _name(*PI_(p, l.p_blur_type), _blurs, G_N_ELEMENTS(_blurs)));
  _f(b, "blur_radius", *PF_(p, l.p_blur_radius));
  _s(b, "fill_mode", _name(*PI_(p, l.p_fill_mode), _fills, G_N_ELEMENTS(_fills)));
  json_builder_set_member_name(b, "fill_color");
  json_builder_begin_array(b);
  for(int c = 0; c < 3; c++) json_builder_add_double_value(b, PF_(p, l.p_fill_color)[c]);
  json_builder_end_array(b);
  _f(b, "fill_brightness", *PF_(p, l.p_fill_brightness));
  json_builder_set_member_name(b, "shapes");
  json_builder_begin_array(b);
  for(int i = 0; i < l.n; i++)
  {
    const uint8_t *e = ENTRY(&l, p, i);
    const dt_mask_id_t id = *PI_(e, l.formid);
    if(!dt_is_valid_maskid(id)) continue;
    const dt_masks_form_t *f = dt_masks_get_from_id(&dtb_S.dev, id);
    if(!f) continue;
    const dt_masks_point_group_t *pt = _point(grp, id);
    json_builder_begin_object(b);
    _i(b, "id", id);
    _i(b, "index", i);
    _s(b, "type", _shape_type(f->type));
    gchar *nm = g_utf8_make_valid(f->name, -1);
    _s(b, "name", nm);
    g_free(nm);
    json_builder_set_member_name(b, "clone");
    json_builder_add_boolean_value(b, (f->type & DT_MASKS_CLONE) != 0);
    json_builder_set_member_name(b, "geometry");
    dtb__mask_geometry(b, f);
    _f(b, "opacity", pt ? pt->opacity : 1.0);
    _i(b, "scale", *PI_(e, l.scale));
    _s(b, "algorithm", _name(*PI_(e, l.algorithm), _algos, G_N_ELEMENTS(_algos)));
    _s(b, "blur_type", _name(*PI_(e, l.blur_type), _blurs, G_N_ELEMENTS(_blurs)));
    _f(b, "blur_radius", *PF_(e, l.blur_radius));
    _s(b, "fill_mode", _name(*PI_(e, l.fill_mode), _fills, G_N_ELEMENTS(_fills)));
    json_builder_set_member_name(b, "fill_color");
    json_builder_begin_array(b);
    for(int c = 0; c < 3; c++) json_builder_add_double_value(b, PF_(e, l.fill_color)[c]);
    json_builder_end_array(b);
    _f(b, "fill_brightness", *PF_(e, l.fill_brightness));
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

static JsonObject *_request(const char *json, JsonNode **keep, int *err)
{
  *keep = NULL;
  *err = DTB_OK;
  if(!json || !*json)
  {
    *err = dtb__err(DTB_E_ARG, "no request");
    return NULL;
  }
  JsonNode *n = dtb__json_parse(json);
  if(!n)
  {
    *err = DTB_E_ARG;
    return NULL;
  }
  if(!JSON_NODE_HOLDS_OBJECT(n))
  {
    json_node_unref(n);
    *err = dtb__err(DTB_E_ARG, "the request is a JSON object");
    return NULL;
  }
  *keep = n;
  return json_node_get_object(n);
}

static int _str_member(JsonObject *q, const char *k, const char **out)
{
  JsonNode *n = json_object_get_member(q, k);
  if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_STRING)
    return dtb__err(DTB_E_TYPE, "%s: a name", k);
  *out = json_node_get_string(n);
  return DTB_OK;
}

static int _num_member(JsonObject *q, const char *k, const double lo, const double hi, float *out)
{
  JsonNode *n = json_object_get_member(q, k);
  if(!JSON_NODE_HOLDS_VALUE(n) || (json_node_get_value_type(n) != G_TYPE_DOUBLE && json_node_get_value_type(n) != G_TYPE_INT64))
    return dtb__err(DTB_E_TYPE, "%s: a number", k);
  const double d = json_node_get_double(n);
  if(!isfinite(d) || d < lo || d > hi) return dtb__err(DTB_E_RANGE, "%s %g outside %g..%g", k, d, lo, hi);
  *out = (float)d;
  return DTB_OK;
}

static int _color_member(JsonObject *q, const char *k, float *out)
{
  JsonNode *n = json_object_get_member(q, k);
  JsonArray *a = JSON_NODE_HOLDS_ARRAY(n) ? json_node_get_array(n) : NULL;
  if(!a || json_array_get_length(a) != 3) return dtb__err(DTB_E_TYPE, "%s: [r, g, b]", k);
  for(int c = 0; c < 3; c++)
  {
    JsonNode *e = json_array_get_element(a, c);
    if(!JSON_NODE_HOLDS_VALUE(e) || (json_node_get_value_type(e) != G_TYPE_DOUBLE && json_node_get_value_type(e) != G_TYPE_INT64))
      return dtb__err(DTB_E_TYPE, "%s: numbers", k);
    const double d = json_node_get_double(e);
    if(!isfinite(d) || d < 0.0 || d > 1.0) return dtb__err(DTB_E_RANGE, "%s: %g outside 0..1", k, d);
    out[c] = (float)d;
  }
  return DTB_OK;
}

int dtb_retouch_add(const char *op, int priority, const char *request_json, char **json)
{
  if(json) *json = NULL;
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  rt_layout_t l;
  int r = _module(op, priority, &m, &l);
  if(r) return r;
  JsonNode *keep = NULL;
  JsonObject *q = _request(request_json, &keep, &r);
  if(r) return r;
  const char *type = NULL, *name = NULL, *algo_name = NULL;
  JsonObject *g = NULL;
  float source[2] = { 0.0f, 0.0f };
  gboolean has_source = FALSE;
  GList *keys = json_object_get_members(q);
  for(GList *k = keys; k && !r; k = g_list_next(k))
  {
    const char *key = k->data;
    JsonNode *n = json_object_get_member(q, key);
    if(!strcmp(key, "type")) r = _str_member(q, key, &type);
    else if(!strcmp(key, "algorithm")) r = _str_member(q, key, &algo_name);
    else if(!strcmp(key, "name"))
    {
      if(!JSON_NODE_HOLDS_NULL(n)) r = _str_member(q, key, &name);
    }
    else if(!strcmp(key, "geometry"))
      g = JSON_NODE_HOLDS_OBJECT(n) ? json_node_get_object(n) : NULL, r = g ? DTB_OK : dtb__err(DTB_E_TYPE, "geometry: an object");
    else if(!strcmp(key, "source"))
      r = dtb__mask_pair(q, key, source), has_source = TRUE;
    else
      r = dtb__err(DTB_E_NOT_FOUND, "no request field '%s' (type, geometry, source, algorithm, name)", key);
  }
  g_list_free(keys);
  dt_masks_type_t t = 0;
  if(!r)
  {
    if(!type) r = dtb__err(DTB_E_ARG, "type missing");
    else if(!strcmp(type, "circle")) t = DT_MASKS_CIRCLE;
    else if(!strcmp(type, "ellipse")) t = DT_MASKS_ELLIPSE;
    else if(!strcmp(type, "path")) t = DT_MASKS_PATH;
    else if(!strcmp(type, "brush")) t = DT_MASKS_BRUSH;
    else r = dtb__err(DTB_E_ARG, "type is circle, ellipse, path or brush");
  }
  if(!r && !g) r = dtb__err(DTB_E_ARG, "geometry missing");
  int algo = *PI_(m->params, l.p_algorithm);
  if(!r && algo_name)
  {
    const int a = _name_of(algo_name, _algos, G_N_ELEMENTS(_algos), 1, "algorithm");
    if(a < 0) r = a;
    else algo = a;
  }
  const gboolean clone = algo == ALGO_CLONE || algo == ALGO_HEAL;
  if(!r && algo < ALGO_CLONE) r = dtb__err(DTB_E_ARG, "the module's algorithm is unused");
  if(!r && clone && !has_source) r = dtb__err(DTB_E_ARG, "clone and heal need a source");
  // rt_allow_create_form: the last entry must be free
  if(!r && dt_is_valid_maskid(*PI_(ENTRY(&l, m->params, l.n - 1), l.formid)))
    r = dtb__err(DTB_E_RANGE, "retouch holds %d shapes already", l.n);
  if(!r && name && strlen(name) >= sizeof(((dt_masks_form_t *)0)->name)) r = dtb__err(DTB_E_RANGE, "name too long");
  GList *pts = NULL;
  if(!r) pts = dtb__mask_points(t, g, &r);
  if(r)
  {
    if(keep) json_node_unref(keep);
    return r;
  }
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  *PI_(m->params, l.p_algorithm) = algo;
  dt_masks_form_t *form = dtb__mask_form_new(t | (clone ? DT_MASKS_CLONE : DT_MASKS_NON_CLONE), pts, name);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  form->source[0] = clone ? source[0] : 0.0f;
  form->source[1] = clone ? source[1] : 0.0f;
  dt_masks_group_insert_point(&dtb_S.dev, m, form);
  _resync(m, &l);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dtb__mask_record(m);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  if(keep) json_node_unref(keep);
  if(json)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    _i(b, "id", form->formid);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
  }
  return DTB_OK;
}

int dtb_retouch_shape_set(const char *op, int priority, int id, const char *request_json)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  rt_layout_t l;
  int r = _module(op, priority, &m, &l);
  if(r) return r;
  dt_masks_form_t *grp = _group(m);
  dt_masks_point_group_t *pt = _point(grp, id);
  dt_masks_form_t *f = dt_masks_get_from_id(&dtb_S.dev, id);
  const int i = _index(&l, m->params, id);
  if(!pt || !f || i < 0) return dtb__err(DTB_E_NOT_FOUND, "form %d is not a shape of retouch %d", id, priority);
  JsonNode *keep = NULL;
  JsonObject *q = _request(request_json, &keep, &r);
  if(r) return r;
  // checked on copies first: nothing changes when a value is refused
  void *np = dtb__memdup(m->params, m->params_size);
  uint8_t *e = ENTRY(&l, np, i);
  GList *pts = NULL;
  float source[2] = { f->source[0], f->source[1] };
  float opacity = pt->opacity;
  gboolean geo = FALSE, src = FALSE, opa = FALSE;
  const int old_algo = *PI_(e, l.algorithm);
  int algo = old_algo;
  if(json_object_has_member(q, "algorithm"))
  {
    const char *s = NULL;
    r = _str_member(q, "algorithm", &s);
    if(!r)
    {
      const int a = _name_of(s, _algos, G_N_ELEMENTS(_algos), 1, "algorithm");
      if(a < 0) r = a;
      else algo = a;
    }
    // rt_select_algorithm_callback: ctrl+click only swaps clone <-> heal and blur <-> fill
    const gboolean a_clone = algo == ALGO_CLONE || algo == ALGO_HEAL;
    const gboolean o_clone = old_algo == ALGO_CLONE || old_algo == ALGO_HEAL;
    if(!r && a_clone != o_clone)
      r = dtb__err(DTB_E_ARG, "a %s shape cannot become %s (only clone <-> heal, blur <-> fill)",
                   _name(old_algo, _algos, G_N_ELEMENTS(_algos)), _algos[algo]);
    if(!r)
    {
      *PI_(e, l.algorithm) = algo;
      *PI_(np, l.p_algorithm) = algo;
    }
  }
  GList *keys = json_object_get_members(q);
  for(GList *k = keys; k && !r; k = g_list_next(k))
  {
    const char *key = k->data;
    JsonNode *n = json_object_get_member(q, key);
    const gboolean blur_key = !strcmp(key, "blur_type") || !strcmp(key, "blur_radius");
    const gboolean fill_key = !strcmp(key, "fill_mode") || !strcmp(key, "fill_color") || !strcmp(key, "fill_brightness");
    if(blur_key && algo != ALGO_BLUR)
      r = dtb__err(DTB_E_ARG, "%s: shape %d is not a blur shape", key, id);
    else if(fill_key && algo != ALGO_FILL)
      r = dtb__err(DTB_E_ARG, "%s: shape %d is not a fill shape", key, id);
    else if(!strcmp(key, "algorithm"))
      ;
    else if(!strcmp(key, "geometry"))
    {
      if(!JSON_NODE_HOLDS_OBJECT(n)) r = dtb__err(DTB_E_TYPE, "geometry: an object");
      else pts = dtb__mask_points(f->type, json_node_get_object(n), &r), geo = TRUE;
    }
    else if(!strcmp(key, "source"))
    {
      if(!(f->type & DT_MASKS_CLONE)) r = dtb__err(DTB_E_ARG, "source: shape %d has none (blur, fill)", id);
      else r = dtb__mask_pair(q, key, source), src = TRUE;
    }
    else if(!strcmp(key, "opacity"))
    {
      r = _num_member(q, key, 0.0, 1.0, &opacity);
      opacity = CLAMP(opacity, 0.05f, 1.0f);
      opa = TRUE;
    }
    else if(!strcmp(key, "blur_type"))
    {
      const char *s = NULL;
      r = _str_member(q, key, &s);
      const int v = r ? r : _name_of(s, _blurs, G_N_ELEMENTS(_blurs), 0, key);
      if(!r && v < 0) r = v;
      if(!r) *PI_(e, l.blur_type) = *PI_(np, l.p_blur_type) = v;
    }
    else if(!strcmp(key, "blur_radius"))
    {
      float v = 0;
      r = _num_member(q, key, 0.1, 200.0, &v);
      if(!r) *PF_(e, l.blur_radius) = *PF_(np, l.p_blur_radius) = v;
    }
    else if(!strcmp(key, "fill_mode"))
    {
      const char *s = NULL;
      r = _str_member(q, key, &s);
      const int v = r ? r : _name_of(s, _fills, G_N_ELEMENTS(_fills), 0, key);
      if(!r && v < 0) r = v;
      if(!r) *PI_(e, l.fill_mode) = *PI_(np, l.p_fill_mode) = v;
    }
    else if(!strcmp(key, "fill_color"))
    {
      float c[3];
      r = _color_member(q, key, c);
      if(!r)
      {
        memcpy(PF_(e, l.fill_color), c, sizeof(c));
        memcpy(PF_(np, l.p_fill_color), c, sizeof(c));
      }
    }
    else if(!strcmp(key, "fill_brightness"))
    {
      float v = 0;
      r = _num_member(q, key, -1.0, 1.0, &v);
      if(!r) *PF_(e, l.fill_brightness) = *PF_(np, l.p_fill_brightness) = v;
    }
    else
      r = dtb__err(DTB_E_NOT_FOUND, "no field '%s' (geometry, source, opacity, algorithm, blur_type, blur_radius, "
                                    "fill_mode, fill_color, fill_brightness)", key);
  }
  g_list_free(keys);
  if(keep) json_node_unref(keep);
  if(r)
  {
    g_list_free_full(pts, free);
    g_free(np);
    return r;
  }
  if(geo || src || opa)
  {
    // the shape itself changes: one masks history item (it also holds the params)
    dtb__change_begin(DTB_CHANGE_OTHER, m);
    dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
    memcpy(m->params, np, m->params_size);
    if(geo)
    {
      g_list_free_full(f->points, free);
      f->points = pts;
      dtb__mask_init_ctrl(f);
    }
    if(src)
    {
      f->source[0] = source[0];
      f->source[1] = source[1];
    }
    pt->opacity = opacity;
    dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
    dtb__mask_record(m);
    dtb__change_end(DTB_CHANGE_OTHER, m);
  }
  else if(memcmp(m->params, np, m->params_size))
  {
    // settings only: as a slider, merged with the following changes of the instance until sealed
    dtb__change_begin(DTB_CHANGE_PARAMS, m);
    memcpy(m->params, np, m->params_size);
    dtb__history_add_params(m, TRUE);
    dtb__change_end(DTB_CHANGE_PARAMS, m);
  }
  g_free(np);
  return DTB_OK;
}

int dtb_retouch_shape_remove(const char *op, int priority, int id)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  rt_layout_t l;
  int r = _module(op, priority, &m, &l);
  if(r) return r;
  dt_masks_form_t *grp = _group(m);
  dt_masks_point_group_t *pt = _point(grp, id);
  dt_masks_form_t *f = dt_masks_get_from_id(&dtb_S.dev, id);
  if(!pt || !f) return dtb__err(DTB_E_NOT_FOUND, "form %d is not a shape of retouch %d", id, priority);
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  // dt_masks_form_remove: a retouch shape leaves every group and the session
  for(GList *l2 = dtb_S.dev.forms; l2; l2 = g_list_next(l2))
  {
    dt_masks_form_t *g = l2->data;
    if(!(g->type & DT_MASKS_GROUP)) continue;
    GList *p = g->points;
    while(p)
    {
      GList *next = g_list_next(p);
      dt_masks_point_group_t *x = p->data;
      if(x->formid == id)
      {
        free(x);
        g->points = g_list_delete_link(g->points, p);
      }
      p = next;
    }
  }
  dtb_S.dev.forms = g_list_remove(dtb_S.dev.forms, f);
  dt_masks_free_form(f);
  // an emptied shape group goes too (dt_masks_form_remove on the group)
  if(grp && !grp->points)
  {
    dtb_S.dev.forms = g_list_remove(dtb_S.dev.forms, grp);
    dt_masks_free_form(grp);
    m->blend_params->mask_id = NO_MASKID;
  }
  _resync(m, &l);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dtb__mask_record(m);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  return DTB_OK;
}

int dtb_retouch_select(const char *op, int priority, int id)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  rt_layout_t l;
  int r = _module(op, priority, &m, &l);
  if(r) return r;
  const int i = _index(&l, m->params, id);
  if(i < 0 || !_point(_group(m), id)) return dtb__err(DTB_E_NOT_FOUND, "form %d is not a shape of retouch %d", id, priority);
  // rt_shape_selection_changed: the module's settings take the shape's
  void *np = dtb__memdup(m->params, m->params_size);
  const uint8_t *e = ENTRY(&l, np, i);
  const int algo = *PI_(e, l.algorithm);
  if(algo == ALGO_BLUR)
  {
    *PI_(np, l.p_blur_type) = *PI_(e, l.blur_type);
    *PF_(np, l.p_blur_radius) = *PF_(e, l.blur_radius);
  }
  else if(algo == ALGO_FILL)
  {
    *PI_(np, l.p_fill_mode) = *PI_(e, l.fill_mode);
    *PF_(np, l.p_fill_brightness) = *PF_(e, l.fill_brightness);
    memcpy(PF_(np, l.p_fill_color), PF_(e, l.fill_color), 3 * sizeof(float));
  }
  *PI_(np, l.p_algorithm) = algo;
  if(memcmp(m->params, np, m->params_size))
  {
    dtb__change_begin(DTB_CHANGE_PARAMS, m);
    memcpy(m->params, np, m->params_size);
    dtb__history_add_params(m, TRUE);
    dtb__change_end(DTB_CHANGE_PARAMS, m);
  }
  g_free(np);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* P4c: retouch's focus, the uncropped picture                         */
/*
 * Desktop: while retouch is the focused module (dev->gui_module), the pipe
 * skips every module after it whose operation_tags() meet retouch's
 * operation_tags_filter() (IOP_TAG_DECORATION | IOP_TAG_CROPPING): crop,
 * borders, ... (imageop.c dt_iop_module_is_skipped, pixelpipe_hb.c
 * _skip_piece_on_tags, also in the distort transforms and the cache hash),
 * so the whole picture is shown, and crop draws its frame over it as a guide
 * (darkroom.c, "expose cropper"). The bridge's pipes are export pipes, for
 * which darktable never skips, and the bridge keeps no gui_module; so the
 * focus is kept here and dtb__bypass_begin leaves those modules out of every
 * render, size, coordinate transform and picker, as "bypass" does. Nothing
 * in the edit, the history or the XMP changes; exports run darktable's own
 * pipe and never see it. "pause" / "resume" give the edit as it is for a
 * render made for someone else (LightPhotoLibrary), as rotate and
 * perspective's (perspective.c).
 */
static char rf_op[20];
static int rf_prio = -1;
static gboolean rf_focus = FALSE, rf_paused = FALSE, rf_suspend = FALSE;

static dt_iop_module_t *_focused(void)
{
  if(!rf_focus || !dtb_S.open) return NULL;
  dt_iop_module_t *m = dtb__find(rf_op, rf_prio);
  if(m && !strcmp(m->op, "retouch")) return m;
  // the instance went (deleted): the focus goes with it, as desktop's
  dtb__focus_drop();
  return NULL;
}

gboolean dtb__focus_active(void)
{
  return !rf_paused && !rf_suspend && _focused() != NULL;
}

gboolean dtb__focus_skips(const dt_iop_module_t *m)
{
  const dt_iop_module_t *g = dtb__focus_active() ? _focused() : NULL;
  // imageop.c dt_iop_module_is_skipped with dev->gui_module = g
  return g && m && g != m && (g->operation_tags_filter() & m->operation_tags()) && g->iop_order < m->iop_order;
}

void dtb__focus_drop(void)
{
  rf_focus = rf_paused = rf_suspend = FALSE;
  rf_prio = -1;
  rf_op[0] = '\0';
}

/* the edit's picture (focus left aside) in the whole picture: its corners
 * taken back to the original and forward with the focus' modules left out,
 * fractions of the whole picture. Desktop's crop draws its own box mapped
 * forward from its input (crop.c gui_post_expose); with crop the only left-out
 * module that moves pixels both give the same frame. FALSE when no module is
 * left out or nothing moves. */
static gboolean _frame(float xy[8])
{
  dtb_pipe_t *p = NULL;
  if(dtb__pipe_ready(TRUE, &p)) return FALSE;
  rf_suspend = TRUE;
  dtb_bypass_t plain;
  dtb__bypass_begin(p, NULL, &plain);
  const float pw = p->pipe.processed_width, ph = p->pipe.processed_height;
  const float c[8] = { 0.0f, 0.0f, pw, 0.0f, pw, ph, 0.0f, ph };
  memcpy(xy, c, sizeof(c));
  gboolean ok = dt_dev_distort_backtransform_plus(&dtb_S.dev, &p->pipe, 0, DT_DEV_TRANSFORM_DIR_ALL, xy, 4);
  dtb__bypass_end(&plain);
  rf_suspend = FALSE;
  dtb_bypass_t saved;
  dtb__bypass_begin(p, NULL, &saved);
  const float fw = p->pipe.processed_width, fh = p->pipe.processed_height;
  const int n = saved.n;
  if(ok && n) ok = dt_dev_distort_transform_plus(&dtb_S.dev, &p->pipe, 0, DT_DEV_TRANSFORM_DIR_ALL, xy, 4);
  dtb__bypass_end(&saved);
  if(!ok || !n || fw <= 0 || fh <= 0) return FALSE;
  for(int k = 0; k < 4; k++)
  {
    xy[2 * k] /= fw;
    xy[2 * k + 1] /= fh;
    if(!isfinite(xy[2 * k]) || !isfinite(xy[2 * k + 1])) return FALSE;
  }
  return TRUE;
}

int dtb_retouch_focus(const char *op, int priority, const char *action, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!action) return dtb__err(DTB_E_ARG, "action: begin, end, pause, resume or state");
  dt_iop_module_t *m = NULL;
  rt_layout_t l;
  int r = _module(op, priority, &m, &l);
  if(r) return r;
  const gboolean ours = _focused() == m;
  if(!strcmp(action, "begin"))
  {
    g_strlcpy(rf_op, m->op, sizeof(rf_op));
    rf_prio = m->multi_priority;
    rf_focus = TRUE;
    rf_paused = FALSE;
  }
  else if(!strcmp(action, "end"))
  {
    if(ours) dtb__focus_drop();
  }
  else if(!strcmp(action, "pause") || !strcmp(action, "resume"))
  {
    if(ours) rf_paused = !strcmp(action, "pause");
  }
  else if(strcmp(action, "state"))
    return dtb__err(DTB_E_ARG, "action: begin, end, pause, resume or state, not '%s'", action);
  if(!json) return DTB_OK;

  const gboolean focused = _focused() == m;
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "focused");
  json_builder_add_boolean_value(b, focused);
  json_builder_set_member_name(b, "paused");
  json_builder_add_boolean_value(b, focused && rf_paused);
  json_builder_set_member_name(b, "left_out");
  json_builder_begin_array(b);
  if(focused && !rf_paused)
    for(GList *n = dtb_S.dev.iop; n; n = g_list_next(n))
    {
      const dt_iop_module_t *x = n->data;
      if(!x->enabled || !dtb__focus_skips(x)) continue;
      json_builder_begin_object(b);
      _s(b, "op", x->op);
      _i(b, "priority", x->multi_priority);
      json_builder_end_object(b);
    }
  json_builder_end_array(b);
  float xy[8];
  json_builder_set_member_name(b, "frame");
  if(focused && !rf_paused && _frame(xy))
  {
    json_builder_begin_array(b);
    for(int k = 0; k < 4; k++)
    {
      json_builder_begin_array(b);
      json_builder_add_double_value(b, xy[2 * k]);
      json_builder_add_double_value(b, xy[2 * k + 1]);
      json_builder_end_array(b);
    }
    json_builder_end_array(b);
  }
  else
    json_builder_add_null_value(b);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

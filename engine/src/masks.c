// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: drawn masks (P1b part 2, docs/design/engine-api.md 6.7).
 *
 * The forms are darktable's dt_masks_form_t in the session's develop object
 * (dev->forms), written to the XMP with the history (masks_history). At the
 * pinned commit a module's mask is a "flexi" mask (dev-doc/
 * masks_data_model.md): a tree of groups whose first point is a marker that
 * holds the group's settings (operator, inversion, opacity, name); the other
 * points are members (a shape or a nested group) with their own opacity and
 * inversion. The bridge builds the same structures as desktop's mask panel:
 * dt_masks_group_insert_point (the module's group, created with its marker by
 * dt_masks_module_group_create when it has none) and the module's mask mode
 * switched to ENABLED | FLEXI, as _blendop_mask_enable does. A brush stroke
 * is turned into brush points as desktop's brush tool does on button release
 * (masks/brush.c: Ramer-Douglas-Peucker simplification, then
 * _brush_init_ctrl_points; both copied here, they are static upstream); a
 * path without control points gets them as path.c _path_init_ctrl_points
 * computes them. Every change records one masks history item
 * (dt_dev_add_masks_history_item_ext): for the instance it concerns, or, for
 * forms no instance uses, for darktable's "mask manager" module, as
 * desktop's mask manager does.
 */
#include "dtb_internal.h"

#include "control/conf.h"
#include "develop/blend.h"
#include "develop/masks.h"

#include <math.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* names                                                              */

static const struct { const char *name; dt_masks_type_t type; } _types[] = {
  { "circle", DT_MASKS_CIRCLE }, { "ellipse", DT_MASKS_ELLIPSE }, { "gradient", DT_MASKS_GRADIENT },
  { "path", DT_MASKS_PATH }, { "brush", DT_MASKS_BRUSH }, { "group", DT_MASKS_GROUP },
  { "parametric", DT_MASKS_PARAMETRIC }, { "raster", DT_MASKS_RASTER }, { "object", DT_MASKS_OBJECT },
};

static const char *_type_name(const dt_masks_type_t t)
{
  if(t & DT_MASKS_CLONE) return "clone";
  for(size_t k = 0; k < G_N_ELEMENTS(_types); k++)
    if(t & _types[k].type) return _types[k].name;
  return "unknown";
}

static const struct { const char *name; int bit; } _operators[] = {
  { "maximum", 0 },
  { "screen", DT_MASKS_STATE_FLEXI_SCREEN },
  { "minimum", DT_MASKS_STATE_FLEXI_MINIMUM },
  { "product", DT_MASKS_STATE_FLEXI_PRODUCT },
  { "sum", DT_MASKS_STATE_FLEXI_SUM },
  { "difference", DT_MASKS_STATE_FLEXI_DIFFERENCE },
  { "exclusion", DT_MASKS_STATE_FLEXI_EXCLUSION },
};

/* ------------------------------------------------------------------ */
/* helpers                                                            */

static dt_masks_form_t *_form(const dt_mask_id_t id)
{
  return dt_masks_get_from_id(&dtb_S.dev, id);
}

static gboolean _id_used(const dt_mask_id_t id)
{
  for(GList *l = dtb_S.dev.forms; l; l = g_list_next(l))
  {
    const dt_masks_form_t *f = l->data;
    if(f->formid == id) return TRUE;
    if(f->type & DT_MASKS_GROUP)
      for(GList *p = f->points; p; p = g_list_next(p))
        if(((dt_masks_point_group_t *)p->data)->formid == id) return TRUE;  // markers have ids of their own
  }
  return FALSE;
}

/* the instance's mask group (its root), NULL when it has none */
static dt_masks_form_t *_root(dt_iop_module_t *m)
{
  if(!m->blend_params) return NULL;
  const dt_mask_id_t id = dt_develop_blend_mask_group_id(m, m->blend_params);
  return dt_is_valid_maskid(id) ? _form(id) : NULL;
}

/* does the tree of group g contain form id (at any depth, up to darktable's nesting limit) */
static gboolean _tree_has(const dt_masks_form_t *g, const dt_mask_id_t id, const int depth)
{
  if(!g || depth > DT_MASKS_NESTING_MAX) return FALSE;
  if(g->formid == id) return TRUE;
  if(!(g->type & DT_MASKS_GROUP)) return FALSE;
  for(GList *p = g->points; p; p = g_list_next(p))
  {
    const dt_masks_point_group_t *pt = p->data;
    if(dt_masks_point_is_marker(pt)) continue;
    if(pt->formid == id) return TRUE;
    const dt_masks_form_t *c = _form(pt->formid);
    if(c && (c->type & DT_MASKS_GROUP) && _tree_has(c, id, depth + 1)) return TRUE;
  }
  return FALSE;
}

static dt_iop_module_t *_mask_manager(void)
{
  return dtb__find("mask_manager", 0);
}

/* one masks history item for m (or the mask manager), pipes flagged */
static void _record(dt_iop_module_t *m, const gboolean enable)
{
  dt_iop_module_t *who = m ? m : _mask_manager();
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  if(who) dt_dev_add_masks_history_item_ext(&dtb_S.dev, who, m ? enable : FALSE, TRUE);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
}

/* the first instance whose mask holds form id, else NULL */
static dt_iop_module_t *_first_user(const dt_mask_id_t id)
{
  for(GList *l = dtb_S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *m = l->data;
    if(_tree_has(_root(m), id, 0)) return m;
  }
  return NULL;
}

/* ------------------------------------------------------------------ */
/* JSON out                                                           */

static void _pt(JsonBuilder *b, const char *k, const float *p)
{
  json_builder_set_member_name(b, k);
  json_builder_begin_array(b);
  json_builder_add_double_value(b, p[0]);
  json_builder_add_double_value(b, p[1]);
  json_builder_end_array(b);
}

static void _num(JsonBuilder *b, const char *k, const double v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_double_value(b, v);
}

static void _geometry(JsonBuilder *b, const dt_masks_form_t *f)
{
  json_builder_begin_object(b);
  if((f->type & DT_MASKS_CIRCLE) && f->points)
  {
    const dt_masks_point_circle_t *c = f->points->data;
    _pt(b, "center", c->center);
    _num(b, "radius", c->radius);
    _num(b, "border", c->border);
  }
  else if((f->type & DT_MASKS_ELLIPSE) && f->points)
  {
    const dt_masks_point_ellipse_t *e = f->points->data;
    _pt(b, "center", e->center);
    _pt(b, "radius", e->radius);
    _num(b, "rotation", e->rotation);
    _num(b, "border", e->border);
    json_builder_set_member_name(b, "proportional");
    json_builder_add_boolean_value(b, (e->flags & DT_MASKS_ELLIPSE_PROPORTIONAL) != 0);
  }
  else if((f->type & DT_MASKS_GRADIENT) && f->points)
  {
    const dt_masks_point_gradient_t *g = f->points->data;
    _pt(b, "anchor", g->anchor);
    _num(b, "rotation", g->rotation);
    _num(b, "compression", g->compression);
    _num(b, "steepness", g->steepness);
    _num(b, "curvature", g->curvature);
    json_builder_set_member_name(b, "state");
    json_builder_add_string_value(b, g->state == DT_MASKS_GRADIENT_STATE_SIGMOIDAL ? "sigmoidal" : "linear");
  }
  else if(f->type & (DT_MASKS_PATH | DT_MASKS_BRUSH))
  {
    const gboolean brush = f->type & DT_MASKS_BRUSH;
    json_builder_set_member_name(b, "points");
    json_builder_begin_array(b);
    for(GList *l = f->points; l; l = g_list_next(l))
    {
      json_builder_begin_object(b);
      if(brush)
      {
        const dt_masks_point_brush_t *p = l->data;
        _pt(b, "corner", p->corner);
        _pt(b, "ctrl1", p->ctrl1);
        _pt(b, "ctrl2", p->ctrl2);
        _pt(b, "border", p->border);
        _num(b, "density", p->density);
        _num(b, "hardness", p->hardness);
      }
      else
      {
        const dt_masks_point_path_t *p = l->data;
        _pt(b, "corner", p->corner);
        _pt(b, "ctrl1", p->ctrl1);
        _pt(b, "ctrl2", p->ctrl2);
        _pt(b, "border", p->border);
      }
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
  }
  else if(f->type & DT_MASKS_GROUP)
  {
    const dt_masks_point_group_t *mk = dt_masks_group_marker(f);
    json_builder_set_member_name(b, "flexi");
    json_builder_add_boolean_value(b, mk != NULL);
    if(mk)
    {
      const char *op = "maximum";
      for(size_t k = 1; k < G_N_ELEMENTS(_operators); k++)
        if(mk->state & _operators[k].bit) op = _operators[k].name;
      json_builder_set_member_name(b, "operator");
      json_builder_add_string_value(b, op);
      json_builder_set_member_name(b, "invert");
      json_builder_add_boolean_value(b, (mk->state & DT_MASKS_STATE_OP_INVERT) != 0);
      json_builder_set_member_name(b, "bypass");
      json_builder_add_boolean_value(b, (mk->state & DT_MASKS_STATE_OP_BYPASS) != 0);
      _num(b, "opacity", mk->group_opacity);
      json_builder_set_member_name(b, "group_name");
      json_builder_add_string_value(b, mk->name);
    }
    json_builder_set_member_name(b, "members");
    json_builder_begin_array(b);
    for(GList *l = f->points; l; l = g_list_next(l))
    {
      const dt_masks_point_group_t *pt = l->data;
      if(dt_masks_point_is_marker(pt)) continue;
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "id");
      json_builder_add_int_value(b, pt->formid);
      json_builder_set_member_name(b, "inverse");
      json_builder_add_boolean_value(b, (pt->state & DT_MASKS_STATE_INVERSE) != 0);
      _num(b, "opacity", pt->opacity);
      json_builder_set_member_name(b, "disabled");
      json_builder_add_boolean_value(b, (pt->state & DT_MASKS_STATE_DISABLE) != 0);
      if(!mk)
      {
        // a classic group: each member's own operator
        const char *c = (pt->state & DT_MASKS_STATE_UNION) ? "union"
                        : (pt->state & DT_MASKS_STATE_INTERSECTION) ? "intersection"
                        : (pt->state & DT_MASKS_STATE_DIFFERENCE) ? "difference"
                        : (pt->state & DT_MASKS_STATE_EXCLUSION) ? "exclusion"
                        : (pt->state & DT_MASKS_STATE_SUM) ? "sum" : "none";
        json_builder_set_member_name(b, "combine");
        json_builder_add_string_value(b, c);
      }
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
  }
  json_builder_end_object(b);
}

int dtb_masks(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  for(GList *l = dtb_S.dev.forms; l; l = g_list_next(l))
  {
    const dt_masks_form_t *f = l->data;
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "id");
    json_builder_add_int_value(b, f->formid);
    json_builder_set_member_name(b, "type");
    json_builder_add_string_value(b, _type_name(f->type));
    json_builder_set_member_name(b, "name");
    gchar *nm = g_utf8_make_valid(f->name, -1);
    json_builder_add_string_value(b, nm);
    g_free(nm);
    json_builder_set_member_name(b, "version");
    json_builder_add_int_value(b, f->version);
    json_builder_set_member_name(b, "geometry");
    _geometry(b, f);
    json_builder_set_member_name(b, "used_by");
    json_builder_begin_array(b);
    for(GList *i = dtb_S.dev.iop; i; i = g_list_next(i))
    {
      dt_iop_module_t *m = i->data;
      if(!_tree_has(_root(m), f->formid, 0)) continue;
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "op");
      json_builder_add_string_value(b, m->op);
      json_builder_set_member_name(b, "priority");
      json_builder_add_int_value(b, m->multi_priority);
      json_builder_set_member_name(b, "root");
      json_builder_add_boolean_value(b, _root(m) == f);
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* JSON in                                                            */

static int _g_num(JsonObject *g, const char *k, float *v, const gboolean need, const double lo, const double hi)
{
  if(!json_object_has_member(g, k))
    return need ? dtb__err(DTB_E_ARG, "geometry: \"%s\" missing", k) : DTB_OK;
  JsonNode *n = json_object_get_member(g, k);
  if(!JSON_NODE_HOLDS_VALUE(n) || (json_node_get_value_type(n) != G_TYPE_DOUBLE && json_node_get_value_type(n) != G_TYPE_INT64))
    return dtb__err(DTB_E_TYPE, "geometry: \"%s\" takes a number", k);
  const double d = json_node_get_double(n);
  if(!isfinite(d) || d < lo || d > hi) return dtb__err(DTB_E_RANGE, "geometry: \"%s\" %g outside %g..%g", k, d, lo, hi);
  *v = (float)d;
  return DTB_OK;
}

static int _g_pair(JsonObject *g, const char *k, float *v, const gboolean need, const double lo, const double hi)
{
  if(!json_object_has_member(g, k))
    return need ? dtb__err(DTB_E_ARG, "geometry: \"%s\" missing", k) : DTB_OK;
  JsonNode *n = json_object_get_member(g, k);
  JsonArray *a = JSON_NODE_HOLDS_ARRAY(n) ? json_node_get_array(n) : NULL;
  if(!a || json_array_get_length(a) != 2) return dtb__err(DTB_E_TYPE, "geometry: \"%s\" takes [x, y]", k);
  for(int i = 0; i < 2; i++)
  {
    JsonNode *e = json_array_get_element(a, i);
    if(!JSON_NODE_HOLDS_VALUE(e) || (json_node_get_value_type(e) != G_TYPE_DOUBLE && json_node_get_value_type(e) != G_TYPE_INT64))
      return dtb__err(DTB_E_TYPE, "geometry: \"%s\" takes numbers", k);
    const double d = json_node_get_double(e);
    if(!isfinite(d) || d < lo || d > hi) return dtb__err(DTB_E_RANGE, "geometry: \"%s\" %g outside %g..%g", k, d, lo, hi);
    v[i] = (float)d;
  }
  return DTB_OK;
}

/* positions may lie a little outside the picture, as desktop allows shapes
 * to be dragged partly off it */
#define POS_LO -1.0
#define POS_HI 2.0

/* masks/brush.c: _brush_point_line_distance2, _brush_ramer_douglas_peucker,
 * _brush_catmull_to_bezier, _brush_init_ctrl_points (static upstream) */
static float _sqf(const float x) { return x * x; }

static float _brush_point_line_distance2(const int index, const int pointscount, const float *points,
                                         const float *payload)
{
  const float x = points[2 * index], y = points[2 * index + 1];
  const float b = payload[4 * index], h = payload[4 * index + 1], d = payload[4 * index + 2];
  const float xstart = points[0], ystart = points[1];
  const float bstart = payload[0], hstart = payload[1], dstart = payload[2];
  const float xend = points[2 * (pointscount - 1)], yend = points[2 * (pointscount - 1) + 1];
  const float bend = payload[4 * (pointscount - 1)], hend = payload[4 * (pointscount - 1) + 1],
              dend = payload[4 * (pointscount - 1) + 2];
  const float bweight = 1.0f, hweight = 0.01f, dweight = 0.01f;
  const float r1 = x - xstart, r2 = y - ystart, r3 = xend - xstart, r4 = yend - ystart;
  const float r5 = bend - bstart, r6 = hend - hstart, r7 = dend - dstart;
  const float r = r1 * r3 + r2 * r4;
  const float l = _sqf(r3) + _sqf(r4);
  const float p = r / l;
  float dx, dy, db, dh, dd;
  if(l == 0.0f || p < 0.0f)
  {
    dx = x - xstart; dy = y - ystart; db = b - bstart; dh = h - hstart; dd = d - dstart;
  }
  else if(p > 1.0f)
  {
    dx = x - xend; dy = y - yend; db = b - bend; dh = h - hend; dd = d - dend;
  }
  else
  {
    dx = x - (xstart + p * r3); dy = y - (ystart + p * r4); db = b - (bstart + p * r5);
    dh = h - (hstart + p * r6); dd = d - (dstart + p * r7);
  }
  return _sqf(dx) + _sqf(dy) + bweight * _sqf(db) + hweight * dh * dh + dweight * _sqf(dd);
}

static GList *_brush_rdp(const float *points, const int points_count, const float *payload, const float epsilon2)
{
  GList *res = NULL;
  float dmax2 = 0.0f;
  int index = 0;
  for(int i = 1; i < points_count - 1; i++)
  {
    const float d2 = _brush_point_line_distance2(i, points_count, points, payload);
    if(d2 > dmax2)
    {
      index = i;
      dmax2 = d2;
    }
  }
  if(dmax2 >= epsilon2)
  {
    GList *r1 = _brush_rdp(points, index + 1, payload, epsilon2);
    GList *r2 = _brush_rdp(points + index * 2, points_count - index, payload + index * 4, epsilon2);
    GList *end1 = g_list_last(r1);
    free(end1->data);
    r1 = g_list_delete_link(r1, end1);
    res = g_list_concat(r1, r2);
  }
  else
  {
    for(int e = 0; e < 2; e++)
    {
      const int i = e ? points_count - 1 : 0;
      dt_masks_point_brush_t *pt = malloc(sizeof(dt_masks_point_brush_t));
      pt->corner[0] = points[i * 2];
      pt->corner[1] = points[i * 2 + 1];
      pt->ctrl1[0] = pt->ctrl1[1] = pt->ctrl2[0] = pt->ctrl2[1] = -1.0f;
      pt->border[0] = pt->border[1] = payload[i * 4];
      pt->hardness = payload[i * 4 + 1];
      pt->density = payload[i * 4 + 2];
      pt->state = DT_MASKS_POINT_STATE_NORMAL;
      res = g_list_append(res, pt);
    }
  }
  return res;
}

static void _catmull_to_bezier(const float x1, const float y1, const float x2, const float y2, const float x3,
                               const float y3, const float x4, const float y4, float *bx1, float *by1, float *bx2,
                               float *by2)
{
  *bx1 = (-x1 + 6 * x2 + x3) / 6;
  *by1 = (-y1 + 6 * y2 + y3) / 6;
  *bx2 = (x2 + 6 * x3 - x4) / 6;
  *by2 = (y2 + 6 * y3 - y4) / 6;
}

static void _brush_init_ctrl_points(dt_masks_form_t *form)
{
  if(g_list_shorter_than(form->points, 2)) return;
  dt_masks_point_brush_t start_point[2], end_point[2];
  for(GList *fp = form->points; fp; fp = g_list_next(fp))
  {
    dt_masks_point_brush_t *point3 = fp->data;
    if(!(point3->state & DT_MASKS_POINT_STATE_NORMAL)) continue;
    GList *const prev = g_list_previous(fp);
    GList *const prevprev = prev ? g_list_previous(prev) : NULL;
    GList *const next = g_list_next(fp);
    GList *const nextnext = next ? g_list_next(next) : NULL;
    dt_masks_point_brush_t *point1 = prevprev ? prevprev->data : NULL;
    dt_masks_point_brush_t *point2 = prev ? prev->data : NULL;
    dt_masks_point_brush_t *point4 = next ? next->data : NULL;
    dt_masks_point_brush_t *point5 = nextnext ? nextnext->data : NULL;
    if(point1 == NULL && point2 == NULL)
    {
      start_point[0].corner[0] = start_point[1].corner[0] = 2 * point3->corner[0] - point4->corner[0];
      start_point[0].corner[1] = start_point[1].corner[1] = 2 * point3->corner[1] - point4->corner[1];
      point1 = &(start_point[0]);
      point2 = &(start_point[1]);
    }
    else if(point1 == NULL)
    {
      start_point[0].corner[0] = 2 * point2->corner[0] - point3->corner[0];
      start_point[0].corner[1] = 2 * point2->corner[1] - point3->corner[1];
      point1 = &(start_point[0]);
    }
    if(point4 == NULL && point5 == NULL)
    {
      end_point[0].corner[0] = end_point[1].corner[0] = 2 * point3->corner[0] - point2->corner[0];
      end_point[0].corner[1] = end_point[1].corner[1] = 2 * point3->corner[1] - point2->corner[1];
      point4 = &(end_point[0]);
      point5 = &(end_point[1]);
    }
    else if(point5 == NULL)
    {
      end_point[0].corner[0] = 2 * point4->corner[0] - point3->corner[0];
      end_point[0].corner[1] = 2 * point4->corner[1] - point3->corner[1];
      point5 = &(end_point[0]);
    }
    float bx1 = 0.0f, by1 = 0.0f, bx2 = 0.0f, by2 = 0.0f;
    _catmull_to_bezier(point1->corner[0], point1->corner[1], point2->corner[0], point2->corner[1], point3->corner[0],
                       point3->corner[1], point4->corner[0], point4->corner[1], &bx1, &by1, &bx2, &by2);
    if(point2->ctrl2[0] == -1.0) point2->ctrl2[0] = bx1;
    if(point2->ctrl2[1] == -1.0) point2->ctrl2[1] = by1;
    point3->ctrl1[0] = bx2;
    point3->ctrl1[1] = by2;
    _catmull_to_bezier(point2->corner[0], point2->corner[1], point3->corner[0], point3->corner[1], point4->corner[0],
                       point4->corner[1], point5->corner[0], point5->corner[1], &bx1, &by1, &bx2, &by2);
    if(point4->ctrl1[0] == -1.0) point4->ctrl1[0] = bx2;
    if(point4->ctrl1[1] == -1.0) point4->ctrl1[1] = by2;
    point3->ctrl2[0] = bx1;
    point3->ctrl2[1] = by1;
  }
}

/* masks/path.c _path_init_ctrl_points: a closed curve */
static void _path_init_ctrl_points(dt_masks_form_t *form)
{
  const guint nb = g_list_length(form->points);
  if(nb < 2) return;
  const GList *fp = form->points;
  for(guint k = 0; k < nb; k++)
  {
    dt_masks_point_path_t *point3 = fp->data;
    if(point3->state & DT_MASKS_POINT_STATE_NORMAL)
    {
      const GList *pt2 = g_list_prev_wraparound(fp);
      const GList *pt1 = g_list_prev_wraparound(pt2);
      const GList *pt4 = g_list_next_wraparound(fp, form->points);
      const GList *pt5 = g_list_next_wraparound(pt4, form->points);
      dt_masks_point_path_t *point1 = pt1->data, *point2 = pt2->data, *point4 = pt4->data, *point5 = pt5->data;
      float bx1 = 0.0f, by1 = 0.0f, bx2 = 0.0f, by2 = 0.0f;
      _catmull_to_bezier(point1->corner[0], point1->corner[1], point2->corner[0], point2->corner[1],
                         point3->corner[0], point3->corner[1], point4->corner[0], point4->corner[1], &bx1, &by1, &bx2,
                         &by2);
      if(point2->ctrl2[0] == -1.0) point2->ctrl2[0] = bx1;
      if(point2->ctrl2[1] == -1.0) point2->ctrl2[1] = by1;
      point3->ctrl1[0] = bx2;
      point3->ctrl1[1] = by2;
      _catmull_to_bezier(point2->corner[0], point2->corner[1], point3->corner[0], point3->corner[1],
                         point4->corner[0], point4->corner[1], point5->corner[0], point5->corner[1], &bx1, &by1, &bx2,
                         &by2);
      if(point4->ctrl1[0] == -1.0) point4->ctrl1[0] = bx2;
      if(point4->ctrl1[1] == -1.0) point4->ctrl1[1] = by2;
      point3->ctrl2[0] = bx1;
      point3->ctrl2[1] = by1;
    }
    fp = g_list_next(fp);
  }
}

/* the new point list of a form of type t from geometry g; NULL + error */
static GList *_points_from(const dt_masks_type_t t, JsonObject *g, int *err)
{
  *err = DTB_OK;
  GList *pts = NULL;
  int r = DTB_OK;
  if(t & DT_MASKS_CIRCLE)
  {
    dt_masks_point_circle_t *c = calloc(1, sizeof(*c));
    c->radius = dt_conf_get_float("plugins/darkroom/masks/circle/size");
    c->border = dt_conf_get_float("plugins/darkroom/masks/circle/border");
    if(!(r = _g_pair(g, "center", c->center, TRUE, POS_LO, POS_HI)) && !(r = _g_num(g, "radius", &c->radius, FALSE, 0.0005, 1.0)))
      r = _g_num(g, "border", &c->border, FALSE, 0.0005, 1.0);
    pts = g_list_append(NULL, c);
  }
  else if(t & DT_MASKS_ELLIPSE)
  {
    dt_masks_point_ellipse_t *e = calloc(1, sizeof(*e));
    e->radius[0] = dt_conf_get_float("plugins/darkroom/masks/ellipse/radius_a");
    e->radius[1] = dt_conf_get_float("plugins/darkroom/masks/ellipse/radius_b");
    e->rotation = dt_conf_get_float("plugins/darkroom/masks/ellipse/rotation");
    e->border = dt_conf_get_float("plugins/darkroom/masks/ellipse/border");
    e->flags = dt_conf_get_int("plugins/darkroom/masks/ellipse/flags");
    if(!(r = _g_pair(g, "center", e->center, TRUE, POS_LO, POS_HI)) && !(r = _g_pair(g, "radius", e->radius, FALSE, 0.001, 1.0))
       && !(r = _g_num(g, "rotation", &e->rotation, FALSE, -360.0, 360.0)))
      r = _g_num(g, "border", &e->border, FALSE, 0.001, 1.0);
    if(!r && json_object_has_member(g, "proportional"))
    {
      JsonNode *n = json_object_get_member(g, "proportional");
      if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_BOOLEAN)
        r = dtb__err(DTB_E_TYPE, "geometry: \"proportional\" takes true or false");
      else
        e->flags = json_node_get_boolean(n) ? DT_MASKS_ELLIPSE_PROPORTIONAL : DT_MASKS_ELLIPSE_EQUIDISTANT;
    }
    pts = g_list_append(NULL, e);
  }
  else if(t & DT_MASKS_GRADIENT)
  {
    dt_masks_point_gradient_t *gr = calloc(1, sizeof(*gr));
    gr->rotation = dt_conf_get_float("plugins/darkroom/masks/gradient/rotation");
    gr->compression = dt_conf_get_float("plugins/darkroom/masks/gradient/compression");
    gr->steepness = dt_conf_get_float("plugins/darkroom/masks/gradient/steepness");
    gr->curvature = 0.0f;
    gr->state = DT_MASKS_GRADIENT_STATE_SIGMOIDAL;
    if(!(r = _g_pair(g, "anchor", gr->anchor, TRUE, POS_LO, POS_HI)) && !(r = _g_num(g, "rotation", &gr->rotation, FALSE, -360.0, 360.0))
       && !(r = _g_num(g, "compression", &gr->compression, FALSE, 0.001, 1.0))
       && !(r = _g_num(g, "steepness", &gr->steepness, FALSE, 0.0, 1.0)))
      r = _g_num(g, "curvature", &gr->curvature, FALSE, -2.0, 2.0);
    if(!r && json_object_has_member(g, "state"))
    {
      const char *st = json_object_get_string_member(g, "state");
      if(st && !strcmp(st, "linear")) gr->state = DT_MASKS_GRADIENT_STATE_LINEAR;
      else if(st && !strcmp(st, "sigmoidal")) gr->state = DT_MASKS_GRADIENT_STATE_SIGMOIDAL;
      else r = dtb__err(DTB_E_ARG, "geometry: state is \"linear\" or \"sigmoidal\"");
    }
    pts = g_list_append(NULL, gr);
  }
  else if((t & DT_MASKS_BRUSH) && json_object_has_member(g, "stroke"))
  {
    // a stroke as from a finger: desktop's brush tool on button release
    JsonNode *sn = json_object_get_member(g, "stroke");
    JsonArray *s = JSON_NODE_HOLDS_ARRAY(sn) ? json_node_get_array(sn) : NULL;
    float border = dt_conf_get_float("plugins/darkroom/masks/brush/border");
    float hardness = dt_conf_get_float("plugins/darkroom/masks/brush/hardness");
    float density = dt_conf_get_float("plugins/darkroom/masks/brush/density");
    if(!s || json_array_get_length(s) < 1) r = dtb__err(DTB_E_ARG, "geometry: \"stroke\" takes [[x, y], ...]");
    if(!r && !(r = _g_num(g, "size", &border, FALSE, 0.00005, 0.5)) && !(r = _g_num(g, "hardness", &hardness, FALSE, 0.0005, 1.0)))
      r = _g_num(g, "density", &density, FALSE, 0.0, 1.0);
    if(!r)
    {
      int n = json_array_get_length(s);
      float *xy = g_new0(float, 2 * (n + 1)), *pl = g_new0(float, 4 * (n + 1));
      for(int i = 0; i < n && !r; i++)
      {
        JsonNode *e = json_array_get_element(s, i);
        JsonArray *a = JSON_NODE_HOLDS_ARRAY(e) ? json_node_get_array(e) : NULL;
        if(!a || json_array_get_length(a) != 2) r = dtb__err(DTB_E_TYPE, "geometry: stroke point %d is not [x, y]", i);
        else
        {
          xy[2 * i] = json_array_get_double_element(a, 0);
          xy[2 * i + 1] = json_array_get_double_element(a, 1);
          pl[4 * i] = border;
          pl[4 * i + 1] = hardness;
          pl[4 * i + 2] = density;
          pl[4 * i + 3] = 1.0f;
        }
      }
      if(!r)
      {
        if(n == 1)
        {
          // "add a helper node very close to the single spot" (brush.c)
          xy[2] = xy[0] + 0.01f;
          xy[3] = xy[1] - 0.01f;
          memcpy(pl + 4, pl, 4 * sizeof(float));
          n = 2;
        }
        // desktop's smoothing "medium" (brush_smoothing) unless configured
        float factor = 0.01f;
        const char *smoothing = dt_conf_get_string_const("brush_smoothing");
        if(!strcmp(smoothing, "low")) factor = 0.0025f;
        else if(!strcmp(smoothing, "high")) factor = 0.04f;
        const float epsilon2 = factor * MAX(0.00005f, border) * MAX(0.00005f, border);
        pts = _brush_rdp(xy, n, pl, epsilon2);
      }
      g_free(xy);
      g_free(pl);
    }
  }
  else if(t & (DT_MASKS_PATH | DT_MASKS_BRUSH))
  {
    const gboolean brush = t & DT_MASKS_BRUSH;
    JsonNode *pn = json_object_get_member(g, "points");
    JsonArray *a = pn && JSON_NODE_HOLDS_ARRAY(pn) ? json_node_get_array(pn) : NULL;
    const guint need = brush ? 2 : 3;
    if(!a || json_array_get_length(a) < need)
      r = dtb__err(DTB_E_ARG, "geometry: \"points\" takes at least %u points%s", need, brush ? " (or a \"stroke\")" : "");
    const float dborder = dt_conf_get_float(brush ? "plugins/darkroom/masks/brush/border" : "plugins/darkroom/masks/path/border");
    for(guint i = 0; !r && i < json_array_get_length(a); i++)
    {
      JsonNode *e = json_array_get_element(a, i);
      JsonObject *po = JSON_NODE_HOLDS_OBJECT(e) ? json_node_get_object(e) : NULL;
      if(!po)
      {
        r = dtb__err(DTB_E_TYPE, "geometry: point %u is not an object", i);
        break;
      }
      float corner[2], c1[2] = { -1.0f, -1.0f }, c2[2] = { -1.0f, -1.0f }, bd[2] = { dborder, dborder };
      float density = 1.0f, hardness = 1.0f;
      if(brush)
      {
        density = dt_conf_get_float("plugins/darkroom/masks/brush/density");
        hardness = dt_conf_get_float("plugins/darkroom/masks/brush/hardness");
      }
      const gboolean user = json_object_has_member(po, "ctrl1") || json_object_has_member(po, "ctrl2");
      if(!(r = _g_pair(po, "corner", corner, TRUE, POS_LO, POS_HI)) && !(r = _g_pair(po, "ctrl1", c1, FALSE, -3.0, 4.0))
         && !(r = _g_pair(po, "ctrl2", c2, FALSE, -3.0, 4.0)) && !(r = _g_pair(po, "border", bd, FALSE, 0.0005, 1.0)) && brush
         && !(r = _g_num(po, "density", &density, FALSE, 0.0, 1.0)))
        r = _g_num(po, "hardness", &hardness, FALSE, 0.0005, 1.0);
      if(r) break;
      if(user && (c1[0] == -1.0f || c2[0] == -1.0f))
      {
        r = dtb__err(DTB_E_ARG, "geometry: point %u: give both ctrl1 and ctrl2, or neither", i);
        break;
      }
      if(brush)
      {
        dt_masks_point_brush_t *p = calloc(1, sizeof(*p));
        memcpy(p->corner, corner, sizeof(corner));
        memcpy(p->ctrl1, c1, sizeof(c1));
        memcpy(p->ctrl2, c2, sizeof(c2));
        memcpy(p->border, bd, sizeof(bd));
        p->density = density;
        p->hardness = hardness;
        p->state = user ? DT_MASKS_POINT_STATE_USER : DT_MASKS_POINT_STATE_NORMAL;
        pts = g_list_append(pts, p);
      }
      else
      {
        dt_masks_point_path_t *p = calloc(1, sizeof(*p));
        memcpy(p->corner, corner, sizeof(corner));
        memcpy(p->ctrl1, c1, sizeof(c1));
        memcpy(p->ctrl2, c2, sizeof(c2));
        memcpy(p->border, bd, sizeof(bd));
        p->state = user ? DT_MASKS_POINT_STATE_USER : DT_MASKS_POINT_STATE_NORMAL;
        pts = g_list_append(pts, p);
      }
    }
  }
  else
    r = dtb__err(DTB_E_ARG, "forms of type %s are not made through the bridge", _type_name(t));
  if(r)
  {
    g_list_free_full(pts, free);
    *err = r;
    return NULL;
  }
  return pts;
}

static void _init_ctrl(dt_masks_form_t *f)
{
  if(f->type & DT_MASKS_BRUSH) _brush_init_ctrl_points(f);
  else if(f->type & DT_MASKS_PATH) _path_init_ctrl_points(f);
}

/* ------------------------------------------------------------------ */
/* calls                                                              */

int dtb_mask_create(const char *type, const char *geometry_json, const char *name, char **json)
{
  if(json) *json = NULL;
  NEED_WRITABLE();
  dt_masks_type_t t = 0;
  for(size_t k = 0; type && k < G_N_ELEMENTS(_types); k++)
    if(!strcmp(type, _types[k].name)) t = _types[k].type;
  if(!(t & (DT_MASKS_CIRCLE | DT_MASKS_ELLIPSE | DT_MASKS_GRADIENT | DT_MASKS_PATH | DT_MASKS_BRUSH)))
    return dtb__err(DTB_E_ARG, "type is circle, ellipse, gradient, path or brush (groups: dtb_mask_group_create)");
  if(name && strlen(name) >= sizeof(((dt_masks_form_t *)0)->name)) return dtb__err(DTB_E_RANGE, "name too long");
  JsonNode *root = dtb__json_parse(geometry_json ? geometry_json : "");
  if(!root) return DTB_E_ARG;
  if(!JSON_NODE_HOLDS_OBJECT(root))
  {
    json_node_unref(root);
    return dtb__err(DTB_E_ARG, "geometry must be an object");
  }
  int r = DTB_OK;
  GList *pts = _points_from(t, json_node_get_object(root), &r);
  json_node_unref(root);
  if(r) return r;
  dtb__change_begin(DTB_CHANGE_OTHER, NULL);
  dt_masks_form_t *form = dt_masks_create(t);
  while(_id_used(form->formid)) form->formid++;
  form->points = pts;
  _init_ctrl(form);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  if(name && *name)
    g_strlcpy(form->name, name, sizeof(form->name));
  else
    dt_masks_assign_unique_name(&dtb_S.dev, form);
  dtb_S.dev.forms = g_list_append(dtb_S.dev.forms, form);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  _record(NULL, FALSE);
  dtb__change_end(DTB_CHANGE_OTHER, NULL);
  if(json)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "id");
    json_builder_add_int_value(b, form->formid);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
  }
  return DTB_OK;
}

int dtb_mask_update(int id, const char *geometry_json)
{
  NEED_WRITABLE();
  dt_masks_form_t *f = _form(id);
  if(!f) return dtb__err(DTB_E_NOT_FOUND, "no form %d", id);
  if(f->type & DT_MASKS_GROUP) return dtb__err(DTB_E_ARG, "form %d is a group: use dtb_mask_group_set", id);
  JsonNode *root = dtb__json_parse(geometry_json ? geometry_json : "");
  if(!root) return DTB_E_ARG;
  if(!JSON_NODE_HOLDS_OBJECT(root))
  {
    json_node_unref(root);
    return dtb__err(DTB_E_ARG, "geometry must be an object");
  }
  int r = DTB_OK;
  GList *pts = _points_from(f->type, json_node_get_object(root), &r);
  json_node_unref(root);
  if(r) return r;
  dt_iop_module_t *user = _first_user(id);
  dtb__change_begin(DTB_CHANGE_OTHER, user);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  g_list_free_full(f->points, free);
  f->points = pts;
  _init_ctrl(f);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  _record(user, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, user);
  return DTB_OK;
}

int dtb_mask_rename(int id, const char *name)
{
  NEED_WRITABLE();
  dt_masks_form_t *f = _form(id);
  if(!f) return dtb__err(DTB_E_NOT_FOUND, "no form %d", id);
  if(!name || !*name) return dtb__err(DTB_E_ARG, "empty name");
  if(strlen(name) >= sizeof(f->name)) return dtb__err(DTB_E_RANGE, "name too long");
  dt_iop_module_t *user = _first_user(id);
  dtb__change_begin(DTB_CHANGE_OTHER, user);
  g_strlcpy(f->name, name, sizeof(f->name));
  _record(user, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, user);
  return DTB_OK;
}

int dtb_mask_delete(int id)
{
  NEED_WRITABLE();
  dt_masks_form_t *f = _form(id);
  if(!f) return dtb__err(DTB_E_NOT_FOUND, "no form %d", id);
  for(GList *l = dtb_S.dev.iop; l; l = g_list_next(l))
    if(_root(l->data) == f)
      return dtb__err(DTB_E_ARG, "form %d is the mask of %s %d: switch its mask off first", id,
                      ((dt_iop_module_t *)l->data)->op, ((dt_iop_module_t *)l->data)->multi_priority);
  dt_iop_module_t *user = _first_user(id);
  dtb__change_begin(DTB_CHANGE_OTHER, user);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  // out of every group that lists it, then out of the session
  for(GList *l = dtb_S.dev.forms; l; l = g_list_next(l))
  {
    dt_masks_form_t *g = l->data;
    if(!(g->type & DT_MASKS_GROUP)) continue;
    GList *p = g->points;
    while(p)
    {
      GList *next = g_list_next(p);
      dt_masks_point_group_t *pt = p->data;
      if(!dt_masks_point_is_marker(pt) && pt->formid == id)
      {
        free(pt);
        g->points = g_list_delete_link(g->points, p);
      }
      p = next;
    }
  }
  dtb_S.dev.forms = g_list_remove(dtb_S.dev.forms, f);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dt_masks_free_form(f);
  _record(user, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, user);
  return DTB_OK;
}

static int _instance_for_masks(const char *op, const int priority, dt_iop_module_t **out)
{
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s %d", op ? op : "(null)", priority);
  if(!(m->flags() & IOP_FLAGS_SUPPORTS_BLENDING) || !m->blend_params || (m->flags() & IOP_FLAGS_NO_MASKS))
    return dtb__err(DTB_E_ARG, "%s has no drawn masks", m->op);
  *out = m;
  return DTB_OK;
}

/* the member point of form id in the instance's root group */
static dt_masks_point_group_t *_member(dt_iop_module_t *m, const dt_mask_id_t id)
{
  dt_masks_form_t *g = _root(m);
  if(!g) return NULL;
  for(GList *p = g->points; p; p = g_list_next(p))
  {
    dt_masks_point_group_t *pt = p->data;
    if(!dt_masks_point_is_marker(pt) && pt->formid == id) return pt;
  }
  return NULL;
}

/* {"inverse": bool, "opacity": 0..1, "disabled": bool} onto a member point */
static int _member_opts(JsonObject *o, dt_masks_point_group_t *pt, const gboolean apply)
{
  if(!o) return DTB_OK;
  GList *members = json_object_get_members(o);
  int r = DTB_OK;
  for(GList *l = members; l && !r; l = g_list_next(l))
  {
    const char *k = l->data;
    JsonNode *n = json_object_get_member(o, k);
    if(!strcmp(k, "inverse") || !strcmp(k, "disabled"))
    {
      if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_BOOLEAN)
        r = dtb__err(DTB_E_TYPE, "%s takes true or false", k);
      else if(apply)
      {
        const int bit = !strcmp(k, "inverse") ? DT_MASKS_STATE_INVERSE : DT_MASKS_STATE_DISABLE;
        pt->state = json_node_get_boolean(n) ? (pt->state | bit) : (pt->state & ~bit);
      }
    }
    else if(!strcmp(k, "opacity"))
    {
      float v = 1.0f;
      r = _g_num(o, k, &v, TRUE, 0.0, 1.0);
      if(!r && apply) pt->opacity = v;
    }
    else
      r = dtb__err(DTB_E_NOT_FOUND, "no member setting '%s' (inverse, opacity, disabled)", k);
  }
  g_list_free(members);
  return r;
}

static JsonObject *_opts(const char *json, JsonNode **keep, int *err)
{
  *err = DTB_OK;
  *keep = NULL;
  if(!json || !*json) return NULL;
  *keep = dtb__json_parse(json);
  if(!*keep)
  {
    *err = DTB_E_ARG;
    return NULL;
  }
  if(!JSON_NODE_HOLDS_OBJECT(*keep))
  {
    *err = dtb__err(DTB_E_ARG, "options must be an object");
    return NULL;
  }
  return json_node_get_object(*keep);
}

int dtb_mask_attach(const char *op, int priority, int id, const char *options_json)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  int r = _instance_for_masks(op, priority, &m);
  if(r) return r;
  dt_masks_form_t *f = _form(id);
  if(!f) return dtb__err(DTB_E_NOT_FOUND, "no form %d", id);
  if(f->type & (DT_MASKS_CLONE | DT_MASKS_NON_CLONE)) return dtb__err(DTB_E_ARG, "form %d belongs to retouch", id);
  if(_root(m) == f || _member(m, id)) return dtb__err(DTB_E_ARG, "form %d is already in %s's mask", id, m->op);
  if((f->type & DT_MASKS_GROUP) && _tree_has(f, dt_develop_blend_mask_group_id(m, m->blend_params), 0))
    return dtb__err(DTB_E_ARG, "a group cannot hold itself");
  JsonNode *keep = NULL;
  JsonObject *o = _opts(options_json, &keep, &r);
  dt_masks_point_group_t probe = { 0 };
  if(!r) r = _member_opts(o, &probe, FALSE);
  if(r)
  {
    if(keep) json_node_unref(keep);
    return r;
  }
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  // as a new shape of desktop's panel: into the module's group (created
  // with its marker if it has none), then the mask switched on as flexi
  // (blend_gui.c _blendop_mask_enable)
  dt_masks_point_group_t *pt = dt_masks_group_insert_point(&dtb_S.dev, m, f);
  // a new member starts opaque here (desktop: the remembered shape opacity)
  pt->opacity = 1.0f;
  _member_opts(o, pt, TRUE);
  m->blend_params->mask_mode |= DEVELOP_MASK_ENABLED | DEVELOP_MASK_FLEXI;
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  _record(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  if(keep) json_node_unref(keep);
  return DTB_OK;
}

int dtb_mask_detach(const char *op, int priority, int id)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  int r = _instance_for_masks(op, priority, &m);
  if(r) return r;
  dt_masks_form_t *g = _root(m);
  dt_masks_point_group_t *pt = _member(m, id);
  if(!g || !pt) return dtb__err(DTB_E_NOT_FOUND, "form %d is not a member of %s %d's mask", id, m->op, priority);
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  g->points = g_list_remove(g->points, pt);
  free(pt);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  _record(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  return DTB_OK;
}

int dtb_mask_member_set(const char *op, int priority, int id, const char *options_json)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = NULL;
  int r = _instance_for_masks(op, priority, &m);
  if(r) return r;
  dt_masks_point_group_t *pt = _member(m, id);
  if(!pt) return dtb__err(DTB_E_NOT_FOUND, "form %d is not a member of %s %d's mask", id, m->op, priority);
  JsonNode *keep = NULL;
  JsonObject *o = _opts(options_json, &keep, &r);
  if(!r && !o) r = dtb__err(DTB_E_ARG, "no settings");
  if(!r) r = _member_opts(o, pt, FALSE);
  if(!r)
  {
    dtb__change_begin(DTB_CHANGE_OTHER, m);
    _member_opts(o, pt, TRUE);
    _record(m, TRUE);
    dtb__change_end(DTB_CHANGE_OTHER, m);
  }
  if(keep) json_node_unref(keep);
  return r;
}

int dtb_mask_group_create(const char *member_ids_json, const char *name, char **json)
{
  if(json) *json = NULL;
  NEED_WRITABLE();
  JsonNode *root = dtb__json_parse(member_ids_json ? member_ids_json : "");
  if(!root) return DTB_E_ARG;
  JsonArray *a = JSON_NODE_HOLDS_ARRAY(root) ? json_node_get_array(root) : NULL;
  int r = a ? DTB_OK : dtb__err(DTB_E_ARG, "members: an array of form ids");
  for(guint i = 0; !r && i < json_array_get_length(a); i++)
  {
    JsonNode *e = json_array_get_element(a, i);
    if(!JSON_NODE_HOLDS_VALUE(e) || json_node_get_value_type(e) != G_TYPE_INT64)
      r = dtb__err(DTB_E_TYPE, "members: integers");
    else if(!_form((int)json_node_get_int(e)))
      r = dtb__err(DTB_E_NOT_FOUND, "no form %ld", (long)json_node_get_int(e));
  }
  if(r)
  {
    json_node_unref(root);
    return r;
  }
  dtb__change_begin(DTB_CHANGE_OTHER, NULL);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  // a flexi group: its marker first (dt_masks_marker_new, operator maximum),
  // then one member per form, opaque, as dt_masks_group_insert_point makes them
  dt_masks_form_t *grp = dt_masks_create(DT_MASKS_GROUP);
  while(_id_used(grp->formid)) grp->formid++;
  if(name && *name) g_strlcpy(grp->name, name, sizeof(grp->name));
  else dt_masks_assign_unique_name(&dtb_S.dev, grp);
  dtb_S.dev.forms = g_list_append(dtb_S.dev.forms, grp);
  grp->points = g_list_append(NULL, dt_masks_marker_new(dtb_S.dev.forms, grp, 0));
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    dt_masks_point_group_t *pt = calloc(1, sizeof(*pt));
    pt->formid = (int)json_array_get_int_element(a, i);
    pt->parentid = grp->formid;
    pt->state = DT_MASKS_STATE_SHOW | DT_MASKS_STATE_USE;
    pt->opacity = 1.0f;
    pt->group_opacity = 1.0f;
    grp->points = g_list_append(grp->points, pt);
  }
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  _record(NULL, FALSE);
  dtb__change_end(DTB_CHANGE_OTHER, NULL);
  json_node_unref(root);
  if(json)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "id");
    json_builder_add_int_value(b, grp->formid);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
  }
  return DTB_OK;
}

int dtb_mask_group_set(int id, const char *options_json)
{
  NEED_WRITABLE();
  dt_masks_form_t *g = _form(id);
  if(!g) return dtb__err(DTB_E_NOT_FOUND, "no form %d", id);
  dt_masks_point_group_t *mk = (g->type & DT_MASKS_GROUP) ? dt_masks_group_marker(g) : NULL;
  if(!mk) return dtb__err(DTB_E_ARG, "form %d is not a flexi group", id);
  int r;
  JsonNode *keep = NULL;
  JsonObject *o = _opts(options_json, &keep, &r);
  if(!r && !o) r = dtb__err(DTB_E_ARG, "no settings");
  dt_masks_point_group_t nm = *mk;
  GList *members = o ? json_object_get_members(o) : NULL;
  for(GList *l = members; l && !r; l = g_list_next(l))
  {
    const char *k = l->data;
    JsonNode *n = json_object_get_member(o, k);
    if(!strcmp(k, "operator"))
    {
      const char *s = JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_STRING ? json_node_get_string(n) : NULL;
      int bit = -1;
      for(size_t q = 0; s && q < G_N_ELEMENTS(_operators); q++)
        if(!strcmp(s, _operators[q].name)) bit = _operators[q].bit;
      if(bit < 0) r = dtb__err(DTB_E_ARG, "operator: maximum, screen, minimum, product, sum, difference or exclusion");
      else nm.state = (nm.state & ~DT_MASKS_STATE_FLEXI_OP) | bit;
    }
    else if(!strcmp(k, "invert") || !strcmp(k, "bypass"))
    {
      if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_BOOLEAN)
        r = dtb__err(DTB_E_TYPE, "%s takes true or false", k);
      else
      {
        const int bit = !strcmp(k, "invert") ? DT_MASKS_STATE_OP_INVERT : DT_MASKS_STATE_OP_BYPASS;
        nm.state = json_node_get_boolean(n) ? (nm.state | bit) : (nm.state & ~bit);
      }
    }
    else if(!strcmp(k, "opacity"))
      r = _g_num(o, k, &nm.group_opacity, TRUE, 0.0, 1.0);
    else if(!strcmp(k, "name"))
    {
      const char *s = JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_STRING ? json_node_get_string(n) : NULL;
      if(!s || strlen(s) >= sizeof(nm.name)) r = dtb__err(DTB_E_ARG, "name: a string shorter than %zu bytes", sizeof(nm.name));
      else g_strlcpy(nm.name, s, sizeof(nm.name));
    }
    else
      r = dtb__err(DTB_E_NOT_FOUND, "no group setting '%s' (operator, invert, bypass, opacity, name)", k);
  }
  g_list_free(members);
  if(keep) json_node_unref(keep);
  if(r) return r;
  dt_iop_module_t *user = _first_user(id);
  dtb__change_begin(DTB_CHANGE_OTHER, user);
  *mk = nm;
  _record(user, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, user);
  return DTB_OK;
}

/* desktop's dt_masks_iop_use_same_as() (masks/masks.c) for the session: it
 * looks the groups up in, and copies forms into, the global develop object
 * (darktable.develop), which is not the session's, so a duplicated instance
 * lost its mask (P1a's instance_duplicate called it; found by P1b part 2).
 * The same steps on the session's forms: the new instance's group, then
 * dt_masks_group_add_members_of with the global object's form list pointed
 * at the session's for the call. No history item (the caller records one). */
gboolean dtb__masks_use_same_as(dt_iop_module_t *m, dt_iop_module_t *src)
{
  dt_masks_form_t *src_grp = _root(src);
  if(!src_grp || src_grp->type != DT_MASKS_GROUP) return FALSE;
  dt_masks_form_t *grp = _root(m);
  if(!grp)
  {
    grp = dt_masks_create(DT_MASKS_GROUP);
    while(_id_used(grp->formid)) grp->formid++;
    gchar *label = dt_history_item_get_name(m);
    snprintf(grp->name, sizeof(grp->name), _("group `%s'"), label);
    g_free(label);
    dtb_S.dev.forms = g_list_append(dtb_S.dev.forms, grp);
    dt_develop_blend_set_mask_group_id(m, m->blend_params, grp->formid);
  }
  GList *global = darktable.develop->forms;
  darktable.develop->forms = dtb_S.dev.forms;
  dt_masks_group_add_members_of(grp, src_grp);
  dtb_S.dev.forms = darktable.develop->forms;
  darktable.develop->forms = global;
  return TRUE;
}

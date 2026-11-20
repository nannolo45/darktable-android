// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: coordinates between the original image and the processed
 * picture (P1b part 2, docs/design/engine-api.md 6.5).
 *
 * darktable's own transforms: dt_dev_distort_transform_plus and
 * dt_dev_distort_backtransform_plus (develop/develop.c) with
 * DT_DEV_TRANSFORM_DIR_ALL over the bridge's full pipe, which calls every
 * switched-on module's distort_transform / distort_backtransform in pipe
 * order (lens correction, rotate and perspective, crop, orientation,
 * framing, liquify, ...). Points are pixels of the pipe's input (the full
 * decoded image) on one side and of the processed picture at scale 1 on the
 * other; the call takes and gives them normalised.
 */
#include "dtb_internal.h"

#include <math.h>
#include <string.h>

int dtb_coords_transform(const char *request_json, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonNode *root = dtb__json_parse(request_json ? request_json : "");
  if(!root) return DTB_E_ARG;
  JsonObject *o = JSON_NODE_HOLDS_OBJECT(root) ? json_node_get_object(root) : NULL;
  int r = DTB_OK;
  const char *dir = NULL;
  JsonArray *pts = NULL;
  if(!o) r = dtb__err(DTB_E_ARG, "the request must be an object");
  if(!r)
  {
    JsonNode *d = json_object_get_member(o, "direction");
    dir = d && JSON_NODE_HOLDS_VALUE(d) && json_node_get_value_type(d) == G_TYPE_STRING ? json_node_get_string(d) : NULL;
    if(!dir || (strcmp(dir, "to_processed") && strcmp(dir, "to_original")))
      r = dtb__err(DTB_E_ARG, "direction is \"to_processed\" or \"to_original\"");
  }
  if(!r)
  {
    JsonNode *p = json_object_get_member(o, "points");
    pts = p && JSON_NODE_HOLDS_ARRAY(p) ? json_node_get_array(p) : NULL;
    if(!pts) r = dtb__err(DTB_E_ARG, "points must be an array of [x, y]");
  }
  const guint n = pts ? json_array_get_length(pts) : 0;
  float *xy = r ? NULL : g_new0(float, 2 * MAX(1u, n));
  for(guint i = 0; !r && i < n; i++)
  {
    JsonNode *e = json_array_get_element(pts, i);
    JsonArray *a = JSON_NODE_HOLDS_ARRAY(e) ? json_node_get_array(e) : NULL;
    if(!a || json_array_get_length(a) != 2)
      r = dtb__err(DTB_E_ARG, "point %u is not [x, y]", i);
    for(int k = 0; !r && k < 2; k++)
    {
      JsonNode *v = json_array_get_element(a, k);
      if(!JSON_NODE_HOLDS_VALUE(v)
         || (json_node_get_value_type(v) != G_TYPE_DOUBLE && json_node_get_value_type(v) != G_TYPE_INT64))
        r = dtb__err(DTB_E_TYPE, "point %u: numbers", i);
      else
        xy[2 * i + k] = (float)json_node_get_double(v);
    }
  }
  dtb_render_opts_t ro = { 0 };
  if(!r) r = dtb__render_opts_from(o, &ro);
  dtb_pipe_t *p = NULL;
  if(!r) r = dtb__pipe_ready(TRUE, &p);
  if(!r)
  {
    dtb_bypass_t saved;
    dtb__bypass_begin(p, &ro, &saved);
    const float iw = p->pipe.iwidth, ih = p->pipe.iheight;
    const float pw = p->pipe.processed_width, ph = p->pipe.processed_height;
    const gboolean fwd = !strcmp(dir, "to_processed");
    for(guint i = 0; i < n; i++)
    {
      xy[2 * i] *= fwd ? iw : pw;
      xy[2 * i + 1] *= fwd ? ih : ph;
    }
    gboolean ok = TRUE;
    if(n)
      ok = fwd ? dt_dev_distort_transform_plus(&dtb_S.dev, &p->pipe, 0, DT_DEV_TRANSFORM_DIR_ALL, xy, n)
               : dt_dev_distort_backtransform_plus(&dtb_S.dev, &p->pipe, 0, DT_DEV_TRANSFORM_DIR_ALL, xy, n);
    if(!ok) r = dtb__err(DTB_E_ENGINE, "darktable could not transform the points");
    if(!r)
    {
      JsonBuilder *b = json_builder_new();
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "points");
      json_builder_begin_array(b);
      for(guint i = 0; i < n; i++)
      {
        const float x = xy[2 * i] / (fwd ? pw : iw), y = xy[2 * i + 1] / (fwd ? ph : ih);
        if(!isfinite(x) || !isfinite(y))
          json_builder_add_null_value(b);
        else
        {
          json_builder_begin_array(b);
          json_builder_add_double_value(b, x);
          json_builder_add_double_value(b, y);
          json_builder_end_array(b);
        }
      }
      json_builder_end_array(b);
      json_builder_set_member_name(b, "original");
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "width");
      json_builder_add_int_value(b, (int)iw);
      json_builder_set_member_name(b, "height");
      json_builder_add_int_value(b, (int)ih);
      json_builder_end_object(b);
      json_builder_set_member_name(b, "processed");
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "width");
      json_builder_add_int_value(b, (int)pw);
      json_builder_set_member_name(b, "height");
      json_builder_add_int_value(b, (int)ph);
      json_builder_end_object(b);
      json_builder_end_object(b);
      *json = dtb__json_finish(b);
    }
    dtb__bypass_end(&saved);
  }
  dtb__render_opts_clear(&ro);
  g_free(xy);
  json_node_unref(root);
  return r;
}

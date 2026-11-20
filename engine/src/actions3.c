// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: module actions that use a picked area (P1b part 2,
 * docs/design/engine-api.md 6.3). Each follows the module's own
 * color_picker_apply() (iop/<module>.c) after the module's picker has run
 * (pickers.c, dtb__pick, on the preview pipe as desktop). temperature's
 * wb_from_area is in actions.c with the other white balance actions.
 */
#include "dtb_internal.h"

#include "common/colorspaces_inline_conversions.h"
#include "common/iop_profile.h"
#include "common/math.h"
#include "control/conf.h"

#include <float.h>
#include <math.h>
#include <string.h>

/* the address of a field of a params blob laid out as m's, any type */
static void *_field(dt_iop_module_t *m, void *blob, const char *path)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, blob, path, &r)) return NULL;
  return r.p;
}

static void _commit(dt_iop_module_t *m, const void *params)
{
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  memcpy(m->params, params, m->params_size);
  dtb__history_add(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
}

static const char *_arg_s(JsonObject *a, const char *k, const char *def)
{
  if(!a || !json_object_has_member(a, k)) return def;
  JsonNode *n = json_object_get_member(a, k);
  return JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_STRING ? json_node_get_string(n) : NULL;
}

/* 1 found, 0 missing, -1 not a number */
static int _arg_d(JsonObject *a, const char *k, double *v)
{
  if(!a || !json_object_has_member(a, k)) return 0;
  JsonNode *n = json_object_get_member(a, k);
  if(!JSON_NODE_HOLDS_VALUE(n)) return -1;
  const GType t = json_node_get_value_type(n);
  if(t != G_TYPE_DOUBLE && t != G_TYPE_INT64) return -1;
  *v = json_node_get_double(n);
  return 1;
}

static int _pick_for(dt_iop_module_t *m, JsonObject *a, const gboolean denoise, dtb_pick_result_t *pk)
{
  if(dtb_S.read_only) return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  float area[4];
  int r = dtb__read_area(a, area);
  if(!r) r = dtb__pick(m, area, denoise, FALSE, NULL, pk);
  // desktop: "if(self->picked_color_max[0] < self->picked_color_min[0]) return;"
  if(!r && (!pk->have[0] || pk->max[0][0] < pk->min[0][0]))
    r = dtb__err(DTB_E_ARG, "the area gives no sample in %s's input", m->op);
  return r;
}

/* ------------------------------------------------------------------ */
/* exposure: spot exposure mapping (iop/exposure.c _auto_set_exposure)  */

static float _exif_bias(const dt_image_t *img)
{
  // exposure.c _get_exposure_bias
  float bias = img->exif_exposure_bias;
  return bias != DT_EXIF_TAG_UNINITIALIZED ? CLAMP(bias, -5.0f, 5.0f) : 0.0f;
}

static float _hilite_bias(const dt_image_t *img)
{
  // exposure.c _get_highlight_bias
  float bias = img->exif_highlight_preservation > 0.0f ? img->exif_highlight_preservation : 0.0f;
  return bias != DT_EXIF_TAG_UNINITIALIZED ? CLAMP(bias, -1.0f, 4.0f) : 0.0f;
}

static int _act_exposure_spot(dt_iop_module_t *m, JsonObject *a, char **res)
{
  const char *mode = _arg_s(a, "mode", "correction");
  if(!mode || (strcmp(mode, "correction") && strcmp(mode, "measure")))
    return dtb__err(DTB_E_ARG, "mode is \"correction\" or \"measure\"");
  double target = dt_conf_get_float("darkroom/modules/exposure/lightness");
  const int has_l = _arg_d(a, "lightness", &target);
  if(has_l < 0) return dtb__err(DTB_E_TYPE, "lightness must be a number");
  if(has_l && (target < 0.0 || target > 100.0)) return dtb__err(DTB_E_RANGE, "lightness 0..100");
  void *p = m->params;
  const int *pmode = _field(m, p, "mode");
  if(pmode && *pmode != 0)
    return dtb__err(DTB_E_ARG, "exposure's spot mapping works in manual mode only (desktop hides it in deflicker mode)");
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, FALSE, &pk);
  if(r) return r;
  dtb_pipe_t *pp = NULL;
  r = dtb__pipe_ready(FALSE, &pp);
  if(r) return r;
  const dt_iop_order_iccprofile_info_t *const input_profile = dt_ioppr_get_pipe_input_profile_info(&pp->pipe);
  if(!input_profile) return dtb__err(DTB_E_ENGINE, "no input profile");

  // as _auto_set_exposure: the picked mean (camera RGB before colorin) to
  // XYZ with the input profile's matrix, made grey in Lab
  dt_aligned_pixel_t RGB = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2], pk.mean[0][3] };
  dt_aligned_pixel_t XYZ, Lab;
  dot_product(RGB, input_profile->matrix_in, XYZ);
  dt_XYZ_to_Lab(XYZ, Lab);
  Lab[1] = Lab[2] = Lab[3] = 0.f;
  dt_Lab_to_XYZ(Lab, XYZ);
  dt_aligned_pixel_t Lch;
  dt_Lab_2_LCH(Lab, Lch);

  const dt_image_t *img = &dtb_S.dev.image_storage;
  const float exposure = *(float *)_field(m, p, "exposure");
  const float black = *(float *)_field(m, p, "black");
  const gboolean cb = *(int *)_field(m, p, "compensate_exposure_bias");
  const gboolean ch = *(int *)_field(m, p, "compensate_hilite_pres");
  // _exposure_compensation_ev
  float comp = 0.0f;
  if(cb) comp -= _exif_bias(img);
  if(ch) comp += _hilite_bias(img);

  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "picked");
  dtb__pick_json(b, &pk);
  json_builder_set_member_name(b, "input_lightness");
  json_builder_add_double_value(b, Lch[0]);
  if(!strcmp(mode, "measure"))
  {
    // the lightness the area has after exposure: becomes the target (desktop
    // keeps it in darkroom/modules/exposure/lightness)
    const float gain = exp2f(exposure + comp);
    dt_aligned_pixel_t XYZ_out = { 0.0f }, Lab_out;
    for(int c = 0; c < 3; c++) XYZ_out[c] = XYZ[c] * gain;
    dt_XYZ_to_Lab(XYZ_out, Lab_out);
    dt_conf_set_float("darkroom/modules/exposure/lightness", Lab_out[0]);
    json_builder_set_member_name(b, "lightness");
    json_builder_add_double_value(b, Lab_out[0]);
  }
  else
  {
    dt_aligned_pixel_t Lch_target = { (float)target, 0.f, 0.f, 0.f }, Lab_target = { 0.f }, XYZ_target = { 0.f };
    dt_LCH_2_Lab(Lch_target, Lab_target);
    dt_Lab_to_XYZ(Lab_target, XYZ_target);
    json_builder_set_member_name(b, "lightness");
    json_builder_add_double_value(b, target);
    // "a near-black target cannot be matched, and a near-black sample explodes the ratio"
    if(XYZ[1] > 1e-5f && XYZ_target[1] > 1e-5f)
    {
      // _exposure_set_white(self, XYZ[1] / XYZ_target[1])
      const float white = XYZ[1] / XYZ_target[1];
      const float ev = -dt_log2f(fmaxf(1e-20f, white)) - comp;  // white2exposure, _required_exposure_slider_ev
      if(ev != exposure)
      {
        void *tmp = dtb__memdup(m->params, m->params_size);
        *(float *)_field(m, tmp, "exposure") = ev;
        // "if(p->black >= white) _exposure_set_black(self, white - 0.01);"
        if(black >= white) *(float *)_field(m, tmp, "black") = white - 0.01f;
        _commit(m, tmp);
        g_free(tmp);
      }
      json_builder_set_member_name(b, "exposure");
      json_builder_add_double_value(b, ev);
    }
  }
  json_builder_end_object(b);
  *res = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* rgblevels: the black, grey and white pickers (iop/rgblevels.c)      */

static int _act_rgblevels_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  const char *which = _arg_s(a, "which", NULL);
  if(!which || (strcmp(which, "black") && strcmp(which, "grey") && strcmp(which, "white")))
    return dtb__err(DTB_E_ARG, "which is \"black\", \"grey\" or \"white\"");
  double chd = 0;
  const int hc = _arg_d(a, "channel", &chd);
  const int channel = (int)chd;
  if(hc < 0 || channel < 0 || channel > 2) return dtb__err(DTB_E_ARG, "channel 0 (R, or all linked), 1 (G), 2 (B)");
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, FALSE, &pk);
  if(r) return r;
  // color_picker_apply: "const float mean_picked_color = *self->picked_color;"
  const float v = pk.mean[0][0];
  void *tmp = dtb__memdup(m->params, m->params_size);
  gchar *path[3];
  float *lv[3];
  for(int k = 0; k < 3; k++)
  {
    path[k] = g_strdup_printf("levels[%d][%d]", channel, k);
    lv[k] = _field(m, tmp, path[k]);
    g_free(path[k]);
  }
  const float prev[3] = { *lv[0], *lv[1], *lv[2] };
  if(!strcmp(which, "black"))
    *lv[0] = v > *lv[1] ? *lv[1] - FLT_EPSILON : v;
  else if(!strcmp(which, "grey"))
  {
    if(!(v < *lv[0] || v > *lv[2])) *lv[1] = v;
  }
  else
    *lv[2] = v < *lv[1] ? *lv[1] + FLT_EPSILON : v;
  const gboolean changed = prev[0] != *lv[0] || prev[1] != *lv[1] || prev[2] != *lv[2];
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "picked");
  dtb__pick_json(b, &pk);
  json_builder_set_member_name(b, "levels");
  json_builder_begin_array(b);
  for(int k = 0; k < 3; k++) json_builder_add_double_value(b, *lv[k]);
  json_builder_end_array(b);
  json_builder_set_member_name(b, "changed");
  json_builder_add_boolean_value(b, changed);
  json_builder_end_object(b);
  // desktop adds a history item only when a level moved
  if(changed) _commit(m, tmp);
  g_free(tmp);
  *res = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* rgbcurve: nodes from an area (iop/rgbcurve.c color_picker_apply,    */
/* the "set values" picker)                                            */

#define RGBCURVE_MAXNODES 20  // DT_IOP_RGBCURVE_MAXNODES = MAX_ANCHORS (common/curve_tools.h)

typedef struct _node_t { float x, y; } _node_t;

/* rgbcurve.c _add_node */
static int _add_node(_node_t *curve_nodes, int *nodes, const float x, const float y)
{
  int selected = -1;
  if(curve_nodes[0].x > x)
    selected = 0;
  else
    for(int k = 1; k < *nodes; k++)
      if(curve_nodes[k].x > x)
      {
        selected = k;
        break;
      }
  if(selected == -1) selected = *nodes;
  for(int i = *nodes; i > selected; i--) curve_nodes[i] = curve_nodes[i - 1];
  curve_nodes[selected].x = x;
  curve_nodes[selected].y = y;
  (*nodes)++;
  return selected;
}

/* rgbcurve.c _add_node_from_picker */
static int _add_node_from_picker(_node_t *nodes, int *n, const int autoscale_rgb, const int compensate,
                                 const float *const in, const float increment, const int ch,
                                 const dt_iop_order_iccprofile_info_t *const work_profile)
{
  float val;
  if(autoscale_rgb)
    val = work_profile ? dt_ioppr_get_rgb_matrix_luminance(in, work_profile->matrix_in, work_profile->lut_in,
                                                           work_profile->unbounded_coeffs_in, work_profile->lutsize,
                                                           work_profile->nonlinearlut)
                       : dt_camera_rgb_luminance(in);
  else
    val = in[ch];
  float x, y;
  if(compensate && work_profile)
    y = x = dt_ioppr_compensate_middle_grey(val, work_profile);
  else
    y = x = val;
  x = CLIP(x - increment);
  y = CLIP(y + increment);
  return _add_node(nodes, n, x, y);
}

static int _act_rgbcurve_nodes(dt_iop_module_t *m, JsonObject *a, char **res)
{
  double chd = 0;
  const int hc = _arg_d(a, "channel", &chd);
  const int ch = (int)chd;
  if(hc < 0 || ch < 0 || ch > 2) return dtb__err(DTB_E_ARG, "channel 0 (R, or the linked curve), 1 (G), 2 (B)");
  const char *offset = _arg_s(a, "offset", "flat");
  int upper_lower;  // desktop: ctrl = upper (1), shift = lower (-1), none = flat (0)
  if(offset && !strcmp(offset, "flat")) upper_lower = 0;
  else if(offset && !strcmp(offset, "upper")) upper_lower = 1;
  else if(offset && !strcmp(offset, "lower")) upper_lower = -1;
  else return dtb__err(DTB_E_ARG, "offset is \"flat\", \"upper\" or \"lower\"");
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, FALSE, &pk);
  if(r) return r;
  dtb_pipe_t *pp = NULL;
  r = dtb__pipe_ready(FALSE, &pp);
  if(r) return r;
  const dt_iop_order_iccprofile_info_t *const work_profile = dt_ioppr_get_pipe_work_profile_info(&pp->pipe);

  void *tmp = dtb__memdup(m->params, m->params_size);
  _node_t nodes[RGBCURVE_MAXNODES + 4];
  gchar *s;
  // reset the curve of this channel to the defaults
  s = g_strdup_printf("curve_num_nodes[%d]", ch);
  int *num = _field(m, tmp, s);
  const int *dnum = _field(m, m->default_params, s);
  g_free(s);
  s = g_strdup_printf("curve_type[%d]", ch);
  *(int *)_field(m, tmp, s) = *(int *)_field(m, m->default_params, s);
  g_free(s);
  *num = *dnum;
  for(int k = 0; k < RGBCURVE_MAXNODES; k++)
  {
    gchar *px = g_strdup_printf("curve_nodes[%d][%d].x", ch, k), *py = g_strdup_printf("curve_nodes[%d][%d].y", ch, k);
    nodes[k].x = *(float *)_field(m, m->default_params, px);
    nodes[k].y = *(float *)_field(m, m->default_params, py);
    g_free(px);
    g_free(py);
  }
  const int autoscale_rgb = *(int *)_field(m, tmp, "curve_autoscale") == 0;  // DT_S_SCALE_AUTOMATIC_RGB
  const int compensate = *(int *)_field(m, tmp, "compensate_middle_grey");
  const float increment = 0.05f * upper_lower;
  // "now add 4 nodes: min, avg, center, max" (the picker's input statistics)
  _add_node_from_picker(nodes, num, autoscale_rgb, compensate, pk.min[0], 0.f, ch, work_profile);
  _add_node_from_picker(nodes, num, autoscale_rgb, compensate, pk.mean[0], increment, ch, work_profile);
  _add_node_from_picker(nodes, num, autoscale_rgb, compensate, pk.max[0], 0.f, ch, work_profile);
  if(*num == 5)
    _add_node(nodes, num, nodes[1].x - increment + (nodes[3].x - nodes[1].x) / 2.f,
              nodes[1].y + increment + (nodes[3].y - nodes[1].y) / 2.f);
  if(*num > RGBCURVE_MAXNODES)
  {
    g_free(tmp);
    return dtb__err(DTB_E_ENGINE, "too many nodes");
  }
  for(int k = 0; k < RGBCURVE_MAXNODES; k++)
  {
    gchar *px = g_strdup_printf("curve_nodes[%d][%d].x", ch, k), *py = g_strdup_printf("curve_nodes[%d][%d].y", ch, k);
    *(float *)_field(m, tmp, px) = nodes[k].x;
    *(float *)_field(m, tmp, py) = nodes[k].y;
    g_free(px);
    g_free(py);
  }
  _commit(m, tmp);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "picked");
  dtb__pick_json(b, &pk);
  json_builder_set_member_name(b, "nodes");
  json_builder_begin_array(b);
  for(int k = 0; k < *num; k++)
  {
    json_builder_begin_array(b);
    json_builder_add_double_value(b, nodes[k].x);
    json_builder_add_double_value(b, nodes[k].y);
    json_builder_end_array(b);
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
  g_free(tmp);
  *res = dtb__json_finish(b);
  return DTB_OK;
}

/* 1 = no such action here */
int dtb__module_action3(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(dt_iop_module_is(m, "exposure") && !strcmp(action, "spot")) return _act_exposure_spot(m, args, result);
  if(dt_iop_module_is(m, "rgblevels") && !strcmp(action, "pick")) return _act_rgblevels_pick(m, args, result);
  if(dt_iop_module_is(m, "rgbcurve") && !strcmp(action, "set_nodes_from_area"))
    return _act_rgbcurve_nodes(m, args, result);
  return 1;
}

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge, brief P6a: the pickers and "auto" buttons of the audit's list
 * (docs/briefs/P6a-small-module-gaps.md item 3) that compute from a picked
 * area or from the module's input, each as the module's own callback does
 * (named per action). AgX is in p6a_agx.c.
 *
 * Code marked "copied verbatim" is the text of the pinned darktable source
 * (src/iop/<module>.c), unchanged; engine test C43 (dtb_pickref p6a) runs
 * desktop's own callbacks, compiled from the source, against these actions.
 */
#include "dtb_internal.h"

#include "common/colorspaces.h"
#include "common/colorspaces_inline_conversions.h"
#include "common/iop_profile.h"
#include "common/math.h"
#include "common/rgb_norms.h"

#include <float.h>
#include <math.h>
#include <string.h>

static float *_ffield(dt_iop_module_t *m, void *blob, const char *path)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, blob, path, &r) || r.f->header.type != DT_INTROSPECTION_TYPE_FLOAT) return NULL;
  return (float *)r.p;
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

static int _pick_for(dt_iop_module_t *m, JsonObject *a, dtb_pick_result_t *pk)
{
  if(dtb_S.read_only) return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  float area[4];
  int r = dtb__read_area(a, area);
  if(!r) r = dtb__pick(m, area, FALSE, FALSE, NULL, pk);
  // desktop: "if(self->picked_color_max[0] < self->picked_color_min[0]) return;"
  if(!r && (!pk->have[0] || pk->max[0][0] < pk->min[0][0]))
    r = dtb__err(DTB_E_ARG, "the area gives no sample in %s's input", m->op);
  return r;
}

/* the answer: the picked statistics and the parameters written (field paths) */
static char *_answer(dt_iop_module_t *m, const dtb_pick_result_t *pk, const void *params, const char *const *paths,
                     const int n, const gboolean changed)
{
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  if(pk)
  {
    json_builder_set_member_name(b, "picked");
    dtb__pick_json(b, pk);
  }
  json_builder_set_member_name(b, "values");
  json_builder_begin_object(b);
  for(int i = 0; i < n; i++)
  {
    const float *v = _ffield(m, (void *)params, paths[i]);
    json_builder_set_member_name(b, paths[i]);
    if(v && isfinite(*v)) json_builder_add_double_value(b, *v);
    else json_builder_add_null_value(b);
  }
  json_builder_end_object(b);
  json_builder_set_member_name(b, "changed");
  json_builder_add_boolean_value(b, changed);
  json_builder_end_object(b);
  return dtb__json_finish(b);
}

static gboolean _differs(const void *a, const void *b, const size_t n)
{
  return memcmp(a, b, n) != 0;
}

/* ================================================================== */
/* rgb levels: "auto" and "auto region" (iop/rgblevels.c               */
/* _auto_levels_callback, the region picker, process(): _auto_levels   */
/* on the preview pipe's input of the module)                          */

/* ---- copied verbatim from src/iop/rgblevels.c ---- */
typedef enum dt_iop_rgblevels_channel_t
{
  DT_IOP_RGBLEVELS_R = 0,
  DT_IOP_RGBLEVELS_G = 1,
  DT_IOP_RGBLEVELS_B = 2,
  DT_IOP_RGBLEVELS_MAX_CHANNELS = 3
} dt_iop_rgblevels_channel_t;

typedef enum dt_iop_rgblevels_autoscale_t
{
  DT_IOP_RGBLEVELS_LINKED_CHANNELS = 0,     // $DESCRIPTION: "RGB, linked channels"
  DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS = 1 // $DESCRIPTION: "RGB, independent channels"
} dt_iop_rgblevels_autoscale_t;

typedef struct dt_iop_rgblevels_params_t
{
  dt_iop_rgblevels_autoscale_t autoscale; // $DEFAULT: DT_IOP_RGBLEVELS_LINKED_CHANNELS $DESCRIPTION: "mode" (DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS, DT_IOP_RGBLEVELS_LINKED_CHANNELS)
  dt_iop_rgb_norms_t preserve_colors;     // $DEFAULT: DT_RGB_NORM_LUMINANCE $DESCRIPTION: "preserve colors"
  float levels[DT_IOP_RGBLEVELS_MAX_CHANNELS][3];
} dt_iop_rgblevels_params_t;

static void _auto_levels(const float *const img,
                         const int width,
                         const int height,
                         int *box_area,
                         dt_iop_rgblevels_params_t *p,
                         const int _channel,
                         const dt_iop_order_iccprofile_info_t *const work_profile)
{
  int y_from, y_to, x_from, x_to;
  const int ch = 4;
  const int channel =
    (p->autoscale == DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS) ? _channel : 0;
  if(box_area[2] > box_area[0] && box_area[3] > box_area[1])
  {
    y_from = box_area[1];
    y_to = box_area[3];
    x_from = box_area[0];
    x_to = box_area[2];
  }
  else
  {
    y_from = 0;
    y_to = height - 1;
    x_from = 0;
    x_to = width - 1;
  }

  float max = -FLT_MAX;
  float min = FLT_MAX;

  for(int y = y_from; y <= y_to; y++)
  {
    const float *const in = img + ch * width * y;
    for(int x = x_from; x <= x_to; x++)
    {
      const float *const pixel = in + x * ch;

      if(p->autoscale == DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS
         || p->preserve_colors == DT_RGB_NORM_NONE)
      {
        if(p->autoscale == DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS)
        {
          if(pixel[channel] >= 0.f)
          {
            max = fmaxf(max, pixel[channel]);
            min = fminf(min, pixel[channel]);
          }
        }
        else
        {
          for(int c = 0; c < 3; c++)
          {
            if(pixel[c] >= 0.f)
            {
              max = fmaxf(max, pixel[c]);
              min = fminf(min, pixel[c]);
            }
          }
        }
      }
      else
      {
        const float lum = dt_rgb_norm(pixel, p->preserve_colors, work_profile);
        if(lum >= 0.f)
        {
          max = fmaxf(max, lum);
          min = fminf(min, lum);
        }
      }
    }
  }

  p->levels[channel][0] = CLAMP(min, 0.f, 1.f);
  p->levels[channel][2] = CLAMP(max, 0.f, 1.f);
  p->levels[channel][1] = (p->levels[channel][2] + p->levels[channel][0]) / 2.f;
}

/* ---- end of copy ---- */

typedef struct _rgblevels_auto_t
{
  dt_iop_rgblevels_params_t params;
  float box_cood[4];  // desktop's g->box_cood: 0 = the whole picture
  int channel;
  gboolean done;
} _rgblevels_auto_t;

/* rgblevels.c _get_selected_area, with desktop's g->box_cood passed in */
static void _rgblevels_selected_area(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const float *box_in,
                                     const dt_iop_roi_t *const roi_in, int *box_out)
{
  box_out[0] = box_out[1] = box_out[2] = box_out[3] = 0;
  const int width = roi_in->width;
  const int height = roi_in->height;
  dt_boundingbox_t box_cood = { box_in[0], box_in[1], box_in[2], box_in[3] };
  box_cood[0] *= piece->pipe->iwidth;
  box_cood[1] *= piece->pipe->iheight;
  box_cood[2] *= piece->pipe->iwidth;
  box_cood[3] *= piece->pipe->iheight;
  dt_dev_distort_transform_plus(self->dev, piece->pipe, self->iop_order, DT_DEV_TRANSFORM_DIR_BACK_INCL, box_cood, 2);
  box_cood[0] *= roi_in->scale;
  box_cood[1] *= roi_in->scale;
  box_cood[2] *= roi_in->scale;
  box_cood[3] *= roi_in->scale;
  box_cood[0] -= roi_in->x;
  box_cood[1] -= roi_in->y;
  box_cood[2] -= roi_in->x;
  box_cood[3] -= roi_in->y;
  int box[4];
  box[0] = fminf(box_cood[0], box_cood[2]);
  box[1] = fminf(box_cood[1], box_cood[3]);
  box[2] = fmaxf(box_cood[0], box_cood[2]);
  box[3] = fmaxf(box_cood[1], box_cood[3]);
  if(!(box[0] >= width || box[1] >= height || box[2] < 0 || box[3] < 0))
  {
    for(int k = 0; k < 4; k += 2) box[k] = MIN(width - 1, MAX(0, box[k]));
    for(int k = 1; k < 4; k += 2) box[k] = MIN(height - 1, MAX(0, box[k]));
    if(!(box[2] - box[0] < 1 || box[3] - box[1] < 1))
    {
      box_out[0] = box[0];
      box_out[1] = box[1];
      box_out[2] = box[2];
      box_out[3] = box[3];
    }
  }
}

static void _rgblevels_auto_fn(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const float *in,
                               const dt_iop_roi_t *roi_in, void *user)
{
  _rgblevels_auto_t *a = user;
  int box[4] = { 0 };
  if(a->box_cood[0] != 0.f || a->box_cood[1] != 0.f || a->box_cood[2] != 0.f || a->box_cood[3] != 0.f)
    _rgblevels_selected_area(self, piece, a->box_cood, roi_in, box);
  const dt_iop_order_iccprofile_info_t *const work_profile = dt_ioppr_get_pipe_work_profile_info(piece->pipe);
  _auto_levels(in, roi_in->width, roi_in->height, box, &a->params, a->channel, work_profile);
  a->done = TRUE;
}

/* auto_levels {channel?, area?}: "auto" (no area: the whole picture) or the
 * "auto region" picker (area in the picture). channel: the tab shown (desktop's
 * g->channel; only used with independent channels) */
static int _act_rgblevels_auto(dt_iop_module_t *m, JsonObject *a, char **res)
{
  if(dtb_S.read_only) return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  if(m->params_size != sizeof(dt_iop_rgblevels_params_t))
    return dtb__err(DTB_E_ENGINE, "rgblevels' parameters are %d bytes, the copy's %d (upstream changed?)", m->params_size,
                    (int)sizeof(dt_iop_rgblevels_params_t));
  double chd = 0;
  const int hc = _arg_d(a, "channel", &chd);
  if(hc < 0 || chd < 0 || chd > 2) return dtb__err(DTB_E_ARG, "channel 0 (R), 1 (G), 2 (B)");
  _rgblevels_auto_t st = { 0 };
  memcpy(&st.params, m->params, sizeof(st.params));
  st.channel = (int)chd;
  if(a && (json_object_has_member(a, "area") || json_object_has_member(a, "point")))
  {
    float area[4];
    int r = dtb__read_area(a, area);
    if(r) return r;
    // desktop takes a region only when it is more than one preview pixel wide and high
    dtb__area_to_input(area, st.box_cood);
  }
  // desktop switches the module on first ("auto": gtk_toggle_button_set_active(self->off))
  if(!m->enabled)
  {
    dtb__change_begin(DTB_CHANGE_OTHER, m);
    dtb__history_add(m, TRUE);
    dtb__change_end(DTB_CHANGE_OTHER, m);
  }
  int r = dtb__module_input(m, _rgblevels_auto_fn, &st);
  if(r) return r;
  if(!st.done) return dtb__err(DTB_E_ENGINE, "rgblevels did not run");
  static const char *const paths[9] = { "levels[0][0]", "levels[0][1]", "levels[0][2]", "levels[1][0]", "levels[1][1]",
                                        "levels[1][2]", "levels[2][0]", "levels[2][1]", "levels[2][2]" };
  const gboolean changed = _differs(&st.params, m->params, sizeof(st.params));
  // desktop adds the history item in any case (_develop_ui_pipe_finished_callback)
  _commit(m, &st.params);
  *res = _answer(m, NULL, m->params, paths, 9, changed);
  return DTB_OK;
}

/* ================================================================== */
/* pickers that set sliders from the picked area                       */

/* levels (legacy): black, grey, white (iop/levels.c color_picker_apply: the
 * picked mean lightness / 100; a level only moves where it keeps the order;
 * a history item only when a level moved) */
static int _act_levels_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  const char *which = _arg_s(a, "which", NULL);
  if(!which || (strcmp(which, "black") && strcmp(which, "grey") && strcmp(which, "white")))
    return dtb__err(DTB_E_ARG, "which is \"black\", \"grey\" or \"white\"");
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  const float v = pk.mean[0][0] / 100.0;
  void *tmp = dtb__memdup(m->params, m->params_size);
  static const char *const paths[3] = { "levels[0]", "levels[1]", "levels[2]" };
  float *lv[3];
  for(int k = 0; k < 3; k++) lv[k] = _ffield(m, tmp, paths[k]);
  if(!lv[0] || !lv[1] || !lv[2]) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "levels has no levels[3] (upstream changed?)"); }
  if(!strcmp(which, "black"))
    *lv[0] = v > *lv[1] ? *lv[1] - FLT_EPSILON : v;
  else if(!strcmp(which, "grey"))
  {
    if(!(v < *lv[0] || v > *lv[2])) *lv[1] = v;
  }
  else
    *lv[2] = v < *lv[1] ? *lv[1] + FLT_EPSILON : v;
  const gboolean changed = _differs(tmp, m->params, m->params_size);
  if(changed) _commit(m, tmp);
  *res = _answer(m, &pk, tmp, paths, 3, changed);
  g_free(tmp);
  return DTB_OK;
}

/* invert: the film colour from an area (iop/invert.c color_picker_apply: the
 * picked mean of the raw input, four channels) */
static int _act_invert_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  void *tmp = dtb__memdup(m->params, m->params_size);
  static const char *const paths[4] = { "color[0]", "color[1]", "color[2]", "color[3]" };
  for(int k = 0; k < 4; k++)
  {
    float *c = _ffield(m, tmp, paths[k]);
    if(!c) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "invert has no color[4] (upstream changed?)"); }
    *c = pk.mean[0][k];
  }
  const gboolean changed = _differs(tmp, m->params, m->params_size);
  _commit(m, tmp);
  *res = _answer(m, &pk, tmp, paths, 4, changed);
  g_free(tmp);
  return DTB_OK;
}

/* split-toning: the hue and saturation of shadows or highlights
 * (iop/splittoning.c color_picker_apply: rgb2hsl of the picked mean; nothing
 * when both are within 1e-4 of the current ones) */
static int _act_splittoning_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  const char *which = _arg_s(a, "which", NULL);
  if(!which || (strcmp(which, "shadows") && strcmp(which, "highlights")))
    return dtb__err(DTB_E_ARG, "which is \"shadows\" or \"highlights\"");
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  float H = .0f, S = .0f, L = .0f;
  const dt_aligned_pixel_t mean = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2], pk.mean[0][3] };
  rgb2hsl(mean, &H, &S, &L);
  const gboolean hi = !strcmp(which, "highlights");
  const char *paths[2] = { hi ? "highlight_hue" : "shadow_hue", hi ? "highlight_saturation" : "shadow_saturation" };
  void *tmp = dtb__memdup(m->params, m->params_size);
  float *ph = _ffield(m, tmp, paths[0]), *ps = _ffield(m, tmp, paths[1]);
  if(!ph || !ps) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "splittoning has no %s (upstream changed?)", paths[0]); }
  gboolean changed = FALSE;
  if(!(fabsf(*ph - H) < 0.0001f && fabsf(*ps - S) < 0.0001f))
  {
    *ph = H;
    *ps = S;
    changed = TRUE;
    _commit(m, tmp);
  }
  *res = _answer(m, &pk, tmp, paths, 2, changed);
  g_free(tmp);
  return DTB_OK;
}

/* colorize: hue and saturation (iop/colorize.c color_picker_apply: the
 * picked Lab mean to XYZ, to sRGB, rgb2hsl; nothing within 1e-4) */
static int _act_colorize_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  float H = .0f, S = .0f, L = .0f;
  dt_aligned_pixel_t XYZ, rgb, Lab = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2], pk.mean[0][3] };
  dt_Lab_to_XYZ(Lab, XYZ);
  dt_XYZ_to_sRGB(XYZ, rgb);
  rgb2hsl(rgb, &H, &S, &L);
  static const char *const paths[2] = { "hue", "saturation" };
  void *tmp = dtb__memdup(m->params, m->params_size);
  float *ph = _ffield(m, tmp, paths[0]), *ps = _ffield(m, tmp, paths[1]);
  if(!ph || !ps) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "colorize has no hue (upstream changed?)"); }
  gboolean changed = FALSE;
  if(!(fabsf(*ph - H) < 0.0001f && fabsf(*ps - S) < 0.0001f))
  {
    *ph = H;
    *ps = S;
    changed = TRUE;
    _commit(m, tmp);
  }
  *res = _answer(m, &pk, tmp, paths, 2, changed);
  g_free(tmp);
  return DTB_OK;
}

/* unbreak input profile: middle grey, black relative exposure, dynamic range,
 * auto tune levels (iop/profile_gamma.c apply_auto_grey, apply_auto_black,
 * apply_auto_dynamic_range, apply_autotune) */
static int _act_profile_gamma_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  const char *which = _arg_s(a, "which", NULL);
  if(!which || (strcmp(which, "grey") && strcmp(which, "black") && strcmp(which, "dynamic_range") && strcmp(which, "auto")))
    return dtb__err(DTB_E_ARG, "which is \"grey\", \"black\", \"dynamic_range\" or \"auto\"");
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  static const char *const paths[3] = { "grey_point", "shadows_range", "dynamic_range" };
  void *tmp = dtb__memdup(m->params, m->params_size);
  float *grey = _ffield(m, tmp, paths[0]), *shadows = _ffield(m, tmp, paths[1]), *dr = _ffield(m, tmp, paths[2]);
  const float *security = _ffield(m, tmp, "security_factor");
  if(!grey || !shadows || !dr || !security) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "profile_gamma's fields (upstream changed?)"); }
  const float noise = powf(2.0f, -16.0f);
  const gboolean all = !strcmp(which, "auto");
  if(all || !strcmp(which, "grey")) *grey = 100.f * max3f(pk.mean[0]);
  if(all)
  {
    float EVmin = Log2Thres(max3f(pk.min[0]) / (*grey / 100.0f), noise);
    EVmin *= (1.0f + *security / 100.0f);
    float EVmax = Log2Thres(max3f(pk.max[0]) / (*grey / 100.0f), noise);
    EVmax *= (1.0f + *security / 100.0f);
    *shadows = EVmin;
    *dr = EVmax - EVmin;
  }
  else if(!strcmp(which, "black"))
  {
    float EVmin = Log2Thres(max3f(pk.min[0]) / (*grey / 100.0f), noise);
    EVmin *= (1.0f + *security / 100.0f);
    *shadows = EVmin;
  }
  else if(!strcmp(which, "dynamic_range"))
  {
    const float EVmin = *shadows;
    float EVmax = Log2Thres(max3f(pk.max[0]) / (*grey / 100.0f), noise);
    EVmax *= (1.0f + *security / 100.0f);
    *dr = EVmax - EVmin;
  }
  const gboolean changed = _differs(tmp, m->params, m->params_size);
  _commit(m, tmp);
  *res = _answer(m, &pk, tmp, paths, 3, changed);
  g_free(tmp);
  return DTB_OK;
}

/* basic adjustments: middle grey from an area (iop/basicadj.c
 * color_picker_apply: the picked mean's luminance in the module's current
 * profile x 100, or dt_camera_rgb_luminance without a profile) */
static int _act_basicadj_grey(dt_iop_module_t *m, JsonObject *a, char **res)
{
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  dtb_pipe_t *pp = NULL;
  r = dtb__pipe_ready(FALSE, &pp);
  if(r) return r;
  const dt_iop_order_iccprofile_info_t *const work_profile = dt_ioppr_get_pipe_current_profile_info(m, &pp->pipe);
  static const char *const paths[1] = { "middle_grey" };
  void *tmp = dtb__memdup(m->params, m->params_size);
  float *mg = _ffield(m, tmp, paths[0]);
  if(!mg) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "basicadj has no middle_grey (upstream changed?)"); }
  dt_aligned_pixel_t c = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2], pk.mean[0][3] };
  *mg = work_profile ? dt_ioppr_get_rgb_matrix_luminance(c, work_profile->matrix_in, work_profile->lut_in,
                                                        work_profile->unbounded_coeffs_in, work_profile->lutsize,
                                                        work_profile->nonlinearlut) * 100.f
                     : dt_camera_rgb_luminance(c);
  const gboolean changed = _differs(tmp, m->params, m->params_size);
  _commit(m, tmp);
  *res = _answer(m, &pk, tmp, paths, 1, changed);
  g_free(tmp);
  return DTB_OK;
}

/* ---- copied verbatim from src/iop/colorequal.c ---- */
static void _pipe_RGB_to_Ych(dt_iop_module_t *self,
                             dt_dev_pixelpipe_t *pipe,
                             const dt_aligned_pixel_t RGB,
                             dt_aligned_pixel_t Ych)
{
  const struct dt_iop_order_iccprofile_info_t *const work_profile =
    dt_ioppr_get_pipe_current_profile_info(self, pipe);
  if(work_profile == NULL) return; // no point

  dt_aligned_pixel_t XYZ_D50 = { 0.0f, 0.0f, 0.0f, 0.0f };
  dt_aligned_pixel_t XYZ_D65 = { 0.0f, 0.0f, 0.0f, 0.0f };

  dt_ioppr_rgb_matrix_to_xyz(RGB, XYZ_D50, work_profile->matrix_in_transposed,
                             work_profile->lut_in,
                             work_profile->unbounded_coeffs_in,
                             work_profile->lutsize,
                             work_profile->nonlinearlut);
  XYZ_D50_to_D65(XYZ_D50, XYZ_D65);
  XYZ_to_Ych(XYZ_D65, Ych);

  if(Ych[2] < 0.f)
    Ych[2] = DT_2PI_F + Ych[2];
}

/* ---- end of copy ---- */

/* color equalizer: white level (iop/colorequal.c color_picker_apply: log2 of
 * the Y of the picked maximum, in the module's current profile) */
static int _act_colorequal_white(dt_iop_module_t *m, JsonObject *a, char **res)
{
  dtb_pick_result_t pk;
  int r = _pick_for(m, a, &pk);
  if(r) return r;
  dtb_pipe_t *pp = NULL;
  r = dtb__pipe_ready(FALSE, &pp);
  if(r) return r;
  static const char *const paths[1] = { "white_level" };
  void *tmp = dtb__memdup(m->params, m->params_size);
  float *wl = _ffield(m, tmp, paths[0]);
  if(!wl) { g_free(tmp); return dtb__err(DTB_E_ENGINE, "colorequal has no white_level (upstream changed?)"); }
  dt_aligned_pixel_t max_Ych = { 0.0f, 0.0f, 0.0f, 0.0f };
  dt_aligned_pixel_t mx = { pk.max[0][0], pk.max[0][1], pk.max[0][2], pk.max[0][3] };
  _pipe_RGB_to_Ych(m, &pp->pipe, mx, max_Ych);
  *wl = log2f(max_Ych[0]);
  const gboolean changed = _differs(tmp, m->params, m->params_size);
  _commit(m, tmp);
  *res = _answer(m, &pk, tmp, paths, 1, changed);
  g_free(tmp);
  return DTB_OK;
}

/* 1 = no such action here */
int dtb__module_action_p6a(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(dt_iop_module_is(m, "rgblevels") && !strcmp(action, "auto_levels")) return _act_rgblevels_auto(m, args, result);
  if(dt_iop_module_is(m, "levels") && !strcmp(action, "pick")) return _act_levels_pick(m, args, result);
  if(dt_iop_module_is(m, "invert") && !strcmp(action, "pick")) return _act_invert_pick(m, args, result);
  if(dt_iop_module_is(m, "splittoning") && !strcmp(action, "pick")) return _act_splittoning_pick(m, args, result);
  if(dt_iop_module_is(m, "colorize") && !strcmp(action, "pick")) return _act_colorize_pick(m, args, result);
  if(dt_iop_module_is(m, "profile_gamma") && !strcmp(action, "pick")) return _act_profile_gamma_pick(m, args, result);
  if(dt_iop_module_is(m, "basicadj") && !strcmp(action, "pick_grey")) return _act_basicadj_grey(m, args, result);
  if(dt_iop_module_is(m, "colorequal") && !strcmp(action, "white_level")) return _act_colorequal_white(m, args, result);
  return dtb__module_action_agx(m, action, args, result);
}

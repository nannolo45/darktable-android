// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Brief P6f: saturation curve's colour picker (satcurvergb.c color_picker_apply).
 *
 *   satcurvergb pick {area | point}
 *
 * Desktop's picker writes nothing: it marks the picked saturation on the graph
 * (the mean as a line, min..max as a band; satcurvergb.c _draw_sat_picker).
 * The action answers those three numbers: the picked mean, min and max of the
 * module's input (working RGB) through pixel_s_in_norm with the module's
 * formula, the pipe's gamut LUT and the working profile, as
 * color_picker_apply computes them.
 */

#include "dtb_internal.h"

#include "common/chromatic_adaptation.h"
#include "common/colorspaces_inline_conversions.h"
#include "common/darktable_ucs_22_helpers.h"
#include "common/iop_profile.h"
#include "common/math.h"
#include "common/matrices.h"
#include "develop/openmp_maths.h"
#include "gui/draw.h"

#include <float.h>
#include <math.h>
#include <string.h>

/* ---- copied verbatim from src/iop/satcurvergb.c ---- */
#define DT_IOP_SATCURVE_CHANNELS 2

typedef enum dt_iop_satcurve_formula_t
{
  DT_IOP_SATCURVE_JZAZBZ = 0,
  DT_IOP_SATCURVE_DTUCS = 1
} dt_iop_satcurve_formula_t;

typedef struct dt_iop_satcurve_channel_data_t
{
  dt_draw_curve_t *curve;
  int curve_num_nodes;
  int curve_type;
  float *lut;
} dt_iop_satcurve_channel_data_t;

typedef struct dt_iop_satcurve_data_t
{
  dt_iop_satcurve_formula_t formula;
  dt_iop_satcurve_channel_data_t channel[DT_IOP_SATCURVE_CHANNELS];
  float *gamut_lut;
  gboolean lut_inited;
  const struct dt_iop_order_iccprofile_info_t *work_profile;

  gboolean use_guided_filter;
  float gf_radius;
  float gf_feathering;
  float gf_protect_from;
  float gf_protect_to;
  float noise_protection;
} dt_iop_satcurve_data_t;

static inline float satcurve_lookup_gamut(const float *const gamut_lut, const float h)
{
  const float position = (h + M_PI_F) * (float)LUT_ELEM / DT_2PI_F;
  const int bin0 = ((int)floorf(position)) % LUT_ELEM;
  const int bin1 = (bin0 + 1) % LUT_ELEM;
  const float f = position - floorf(position);
  return gamut_lut[bin0] * (1.f - f) + gamut_lut[bin1] * f;
}

static inline float pixel_s_in_norm_jzazbz(const float *const restrict rgb_in,
                                           const dt_colormatrix_t inputmatrix_trans,
                                           const float *const restrict gamut_lut,
                                           float *const restrict h_out)
{
  dt_aligned_pixel_t rgb, xyz, jab;
  copy_pixel(rgb, rgb_in);
  dt_vector_clipneg(rgb);
  dt_apply_transposed_color_matrix(rgb, inputmatrix_trans, xyz);
  dt_XYZ_2_JzAzBz(xyz, jab);

  const float Jz = MAX(jab[0], 0.f);
  const float Cz = dt_fast_hypotf(jab[1], jab[2]);
  const float h = atan2f(jab[2], jab[1]);
  const float gamut = MAX(satcurve_lookup_gamut(gamut_lut, h), FLT_MIN);
  const float s_in = Jz > 0.f ? Cz / Jz : 0.f;

  if(h_out)
    *h_out = h;
  return s_in / gamut;
}

// maximum HSB saturation reachable at the gamut boundary for a given J, h in dt UCS;
// fit constants (15.93.../0.652.../0.600...) come from the gamut-boundary polynomial
// model used throughout darktable_ucs_22_helpers. Single source of truth for this
// computation -- do not re-derive it inline at call sites.
static inline float satcurve_ucs_gamut_saturation(const float J, const float h,
                                                  const float L_white,
                                                  const float *const restrict gamut_lut)
{
  const float max_colorfulness = MAX(satcurve_lookup_gamut(gamut_lut, h), FLT_MIN);
  const float max_chroma = 15.932993652962535f * powf(J / L_white, 0.6523997524738018f) * powf(max_colorfulness, 0.6007557017508491f) / L_white;

  const dt_aligned_pixel_t JCH_gamut_boundary = {J, max_chroma, h, 0.f};
  dt_aligned_pixel_t HSB_gamut_boundary;
  dt_UCS_JCH_to_HSB(JCH_gamut_boundary, HSB_gamut_boundary);

  return MAX(HSB_gamut_boundary[1], FLT_MIN);
}

static inline float pixel_s_in_norm_ucs(const float *const restrict rgb_in,
                                        const dt_colormatrix_t inputmatrix_trans,
                                        const float *const restrict gamut_lut,
                                        const float L_white,
                                        float *const restrict h_out)
{
  dt_aligned_pixel_t rgb, xyz, xyY, JCH, HCB;

  copy_pixel(rgb, rgb_in);
  dt_vector_clipneg(rgb);
  dt_apply_transposed_color_matrix(rgb, inputmatrix_trans, xyz);

  dt_D65_XYZ_to_xyY(xyz, xyY);
  xyY_to_dt_UCS_JCH(xyY, L_white, JCH);
  dt_UCS_JCH_to_HCB(JCH, HCB);

  const float gamut_s = satcurve_ucs_gamut_saturation(JCH[0], JCH[2], L_white, gamut_lut);

  if(h_out)
    *h_out = JCH[2];

  const float saturation = HCB[2] > 0.f ? HCB[1] / HCB[2] : 0.f;
  return saturation / gamut_s;
}

static inline float pixel_s_in_norm(const dt_iop_satcurve_formula_t formula,
                                    const float *const restrict rgb_in,
                                    const dt_colormatrix_t inputmatrix_trans,
                                    const float *const restrict gamut_lut,
                                    const float L_white,
                                    float *const restrict h_out)
{
  if(formula == DT_IOP_SATCURVE_DTUCS)
    return pixel_s_in_norm_ucs(rgb_in, inputmatrix_trans, gamut_lut, L_white, h_out);

  return pixel_s_in_norm_jzazbz(rgb_in, inputmatrix_trans, gamut_lut, h_out);
}
/* ---- end of copy ---- */

/* satcurvergb.c _get_gamut_lut_for_picker, with the bridge's pipe in place of
 * darktable.develop->full.pipe: the module's gamut LUT where its piece has one,
 * else a LUT of ones */
static const float *_gamut_lut(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe)
{
  static float unity_gamut_lut[LUT_ELEM];
  static gboolean unity_inited = FALSE;
  if(!unity_inited)
  {
    for(int i = 0; i < LUT_ELEM; i++)
      unity_gamut_lut[i] = 1.f;
    unity_inited = TRUE;
  }
  for(GList *nodes = pipe ? pipe->nodes : NULL; nodes; nodes = g_list_next(nodes))
  {
    dt_dev_pixelpipe_iop_t *piece = nodes->data;
    if(piece->module == self && piece->data)
    {
      const dt_iop_satcurve_data_t *d = piece->data;
      if(d->lut_inited && d->gamut_lut)
        return d->gamut_lut;
    }
  }
  return unity_gamut_lut;
}

static int _act_satcurve_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  float area[4];
  int r = dtb__read_area(a, area);
  if(r) return r;
  dtb_pick_result_t pk;
  r = dtb__pick(m, area, FALSE, FALSE, NULL, &pk);
  if(r) return r;
  if(!pk.have[0]) return dtb__err(DTB_E_ARG, "the area gives no sample in %s's input", m->op);
  dtb_pipe_t *pp = NULL;
  r = dtb__pipe_ready(FALSE, &pp);
  if(r) return r;

  // color_picker_apply, as satcurvergb.c computes the three numbers
  const dt_iop_order_iccprofile_info_t *work_profile = dt_ioppr_get_iop_work_profile_info(m, m->dev->iop);
  if(!work_profile) return dtb__err(DTB_E_ENGINE, "no working profile");
  dt_colormatrix_t inputmatrix = {{0.0f}};
  dt_colormatrix_t inputmatrix_trans;
  dt_colormatrix_mul(inputmatrix, XYZ_D50_to_D65_CAT16, work_profile->matrix_in);
  dt_colormatrix_transpose(inputmatrix_trans, inputmatrix);
  const float *const gamut_lut = _gamut_lut(m, &pp->pipe);
  const float L_white = Y_to_dt_UCS_L_star(1.f);
  const dt_aligned_pixel_t mean_rgb = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2], 0.f };
  const dt_aligned_pixel_t min_rgb = { pk.min[0][0], pk.min[0][1], pk.min[0][2], 0.f };
  const dt_aligned_pixel_t max_rgb = { pk.max[0][0], pk.max[0][1], pk.max[0][2], 0.f };
  dtb_field_ref_t fr;
  r = dtb__field_resolve(m, m->params, "formula", &fr);
  if(r) return r;
  const dt_iop_satcurve_formula_t formula = *(const dt_iop_satcurve_formula_t *)fr.p;
  const float s = pixel_s_in_norm(formula, mean_rgb, inputmatrix_trans, gamut_lut, L_white, NULL);
  const float s_min = pixel_s_in_norm(formula, min_rgb, inputmatrix_trans, gamut_lut, L_white, NULL);
  const float s_max = pixel_s_in_norm(formula, max_rgb, inputmatrix_trans, gamut_lut, L_white, NULL);

  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "picked");
  dtb__pick_json(b, &pk);
  json_builder_set_member_name(b, "saturation");
  json_builder_add_double_value(b, s);
  json_builder_set_member_name(b, "saturation_min");
  json_builder_add_double_value(b, s_min);
  json_builder_set_member_name(b, "saturation_max");
  json_builder_add_double_value(b, s_max);
  json_builder_set_member_name(b, "gamut_lut");
  json_builder_add_boolean_value(b, gamut_lut[0] != 1.f || gamut_lut[LUT_ELEM / 2] != 1.f);
  json_builder_end_object(b);
  *res = dtb__json_finish(b);
  return DTB_OK;
}

int dtb__module_action_p6f(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(dt_iop_module_is(m, "satcurvergb") && !strcmp(action, "pick")) return _act_satcurve_pick(m, args, result);
  return 1;
}

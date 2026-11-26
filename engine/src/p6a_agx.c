// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge, brief P6a: AgX's five pickers and three buttons
 * (iop/agx.c color_picker_apply with _apply_auto_black_exposure,
 * _apply_auto_white_exposure, _apply_auto_tune_exposure, _apply_auto_pivot_x,
 * _apply_auto_pivot_xy and _update_pivot_x; _read_exposure_params_callback;
 * _apply_primaries_from_menu_callback; _set_post_curve_primaries_from_pre_callback).
 * The curve arithmetic is copied verbatim from the pinned source; the
 * callbacks' own steps are repeated here without their slider updates.
 * Engine test C43 (dtb_pickref p6a) runs desktop's callbacks against these.
 */
#include "dtb_internal.h"

#include "common/iop_profile.h"
#include "common/math.h"
#include "develop/blend.h"

#include <float.h>
#include <math.h>
#include <string.h>

/* ---- copied verbatim from src/iop/agx.c ---- */
typedef enum dt_iop_agx_base_primaries_t
{
  DT_AGX_EXPORT_PROFILE = 0, // $DESCRIPTION: "export profile"
  DT_AGX_WORK_PROFILE = 1,   // $DESCRIPTION: "working profile"
  DT_AGX_REC2020 = 2,        // $DESCRIPTION: "Rec2020"
  DT_AGX_DISPLAY_P3 = 3,     // $DESCRIPTION: "Display P3"
  DT_AGX_ADOBE_RGB = 4,      // $DESCRIPTION: "Adobe RGB (compatible)"
  DT_AGX_SRGB = 5,           // $DESCRIPTION: "sRGB"
} dt_iop_agx_base_primaries_t;

typedef struct dt_iop_agx_params_t
{
  float look_lift;                   // $MIN: -1.f $MAX: 1.f $DEFAULT: 0.f $DESCRIPTION: "lift"
  float look_slope;                  // $MIN: 0.f $MAX: 10.f $DEFAULT: 1.f $DESCRIPTION: "slope"
  float look_brightness;             // $MIN: 0.f $MAX: 100.f $DEFAULT: 1.f $DESCRIPTION: "brightness"
  float look_saturation;             // $MIN: 0.f $MAX: 10.f $DEFAULT: 1.f $DESCRIPTION: "saturation"
  float look_original_hue_mix_ratio; // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.6f $DESCRIPTION: "preserve hue"

  // log mapping
  float range_black_relative_ev;  // $MIN: -20.f $MAX: -0.1f  $DEFAULT: -10.f $DESCRIPTION: "black relative exposure"
  float range_white_relative_ev;  // $MIN: 0.1f $MAX: 20.f $DEFAULT: 6.5f $DESCRIPTION: "white relative exposure"
  float dynamic_range_scaling;    // $MIN: -0.5f $MAX: 2.f $DEFAULT: 0.1f $DESCRIPTION: "dynamic range scaling"

  // curve params - comments indicate the original variables from https://www.desmos.com/calculator/yrysofmx8h
  // Corresponds to p_x; is displayed as EV using slider offset and scale.
  // 0.606060606061f = 10/16.5, mid-gray's position if black relative exposure is -10 EV, white is +6.5 EV
  float curve_pivot_x;                    // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.606060606061f $DESCRIPTION: "pivot relative exposure"
  // Corresponds to p_y, but not directly -- needs application of gamma
  float curve_pivot_y_linear_output;      // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.18f $DESCRIPTION: "pivot target output"
  // P_slope
  float curve_contrast_around_pivot;      // $MIN: 0.1f $MAX: 10.f $DEFAULT: 3.0f $DESCRIPTION: "contrast"
  // related to P_tlength; the number expresses the portion of the y range below the pivot
  float curve_linear_ratio_below_pivot;   // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.f $DESCRIPTION: "toe start"
  // related to P_slength; the number expresses the portion of the y range below the pivot
  float curve_linear_ratio_above_pivot;   // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.f $DESCRIPTION: "shoulder start"
  // t_p
  float curve_toe_power;                  // $MIN: 0.f $MAX: 10.f $DEFAULT: 1.5f $DESCRIPTION: "toe power"
  // s_p
  float curve_shoulder_power;             // $MIN: 0.f $MAX: 10.f $DEFAULT: 3.3f $DESCRIPTION: "shoulder power"
  float curve_gamma;                      // $MIN: 0.01f $MAX: 100.f $DEFAULT: 2.2f $DESCRIPTION: "curve y gamma"
  gboolean auto_gamma;                    // $DEFAULT: FALSE $DESCRIPTION: "keep the pivot on the diagonal"
  // t_ly
  float curve_target_display_black_ratio; // $MIN: 0.f $MAX: 0.15f $DEFAULT: 0.f $DESCRIPTION: "target black"
  // s_ly
  float curve_target_display_white_ratio; // $MIN: 0.2f $MAX: 1.f $DEFAULT: 1.f $DESCRIPTION: "target white"

  // custom primaries; rotation limits below: +/- 0.5236 radian => +/- 30 degrees
  dt_iop_agx_base_primaries_t base_primaries; // $DEFAULT: DT_AGX_REC2020 $DESCRIPTION: "base primaries"
  gboolean disable_primaries_adjustments; // $DEFAULT: FALSE $DESCRIPTION: "disable adjustments"
  float red_inset;        // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "red attenuation"
  float red_rotation;     // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "red rotation"
  float green_inset;      // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "green attenuation"
  float green_rotation;   // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "green rotation"
  float blue_inset;       // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "blue attenuation"
  float blue_rotation;    // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "blue rotation"

  float master_outset_ratio;     // $MIN:  0.f  $MAX: 2.f $DEFAULT: 1.f $DESCRIPTION: "master purity boost"
  float master_unrotation_ratio; // $MIN:  0.f  $MAX: 2.f $DEFAULT: 1.f $DESCRIPTION: "master rotation reversal"
  float red_outset;              // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "red purity boost"
  float red_unrotation;          // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "red reverse rotation"
  float green_outset;            // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "green purity boost"
  float green_unrotation;        // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "green reverse rotation"
  float blue_outset;             // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "blue purity boost"
  float blue_unrotation;         // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "blue reverse rotation"

  // v5
  gboolean completely_reverse_primaries; // $DEFAULT: FALSE $DESCRIPTION: "reverse all"

  // v8
  gboolean target_white_from_output; // $DEFAULT: TRUE $DESCRIPTION: "follow output peak"
  float hdr_purity;                  // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.5f $DESCRIPTION: "HDR purity"
} dt_iop_agx_params_t;

typedef struct hdr_params_t
{
  gboolean enabled;
  float headroom;
  float purity;
  float log_min;
  float pivot_x;
  float pivot_y;
  float toe_scale;
  float shoulder_scale;
} hdr_params_t;

typedef struct tone_mapping_params_t
{
  float black_relative_ev;
  float white_relative_ev;
  float range_in_ev;
  float curve_gamma;

  // the toe runs from (t_lx = 0, target black) to (toe_transition_x,
  // toe_transition_y)
  float pivot_x;
  float pivot_y;
  float target_black;     // t_ly
  float toe_power;        // t_p
  float toe_transition_x; // t_tx
  float toe_transition_y; // t_ty
  float toe_scale;        // t_s
  gboolean need_convex_toe;
  float toe_fallback_coefficient;
  float toe_fallback_power;

  // the linear section lies on y = mx + b, running from
  // (toe_transition_x, toe_transition_y) to (shoulder_transition_x,
  // shoulder_transition_y) it can have length 0, in which case it
  // only contains the pivot (pivot_x, pivot_y) the pivot may coincide
  // with toe_transition or shoulder_start or both
  float slope;     // m - for the linear section
  float intercept; // b parameter of the straight segment (y = mx + b,
                   // intersection with the y-axis at (0, b))

  // the shoulder runs from (shoulder_transition_x,
  // shoulder_transition_y) to (s_lx = 1, target_white)
  float target_white;          // s_ly
  float shoulder_power;        // s_p
  float shoulder_transition_x; // s_tx
  float shoulder_transition_y; // s_ty
  float shoulder_scale;        // s_s
  gboolean need_concave_shoulder;
  float shoulder_fallback_coefficient;
  float shoulder_fallback_power;

  // look
  float look_lift;
  float look_slope;
  float look_power;
  float look_saturation;
  float look_original_hue_mix_ratio;
  gboolean look_tuned;
  gboolean restore_hue;

  hdr_params_t hdr;
} tone_mapping_params_t;

static const float _epsilon = 1E-6f;

static const float _default_gamma = 2.2f;

static inline float _line(const float x,
                          const float slope,
                          const float intercept)
{
  return slope * x + intercept;
}

 static inline float _scale(const float limit_x,
                            const float limit_y,
                            const float transition_x,
                            const float transition_y,
                            const float slope,
                            const float power)
{
  // the hypothetical 'rise' if the linear section were extended to the limit.
  const float projected_rise = slope * fmaxf(_epsilon, limit_x - transition_x);

  // the actual 'rise' the curve needs to cover.
  const float actual_rise = fmaxf(_epsilon, limit_y - transition_y);

  // in double: when the pivot reaches target white, the rise is clamped to
  // _epsilon and powf() overflows to inf for powers above about 6.4, which
  // gives a zero scale and NaN in _scaled_sigmoid()
  const double transformed_projected_rise = pow(projected_rise, -power);
  const double transformed_actual_rise = pow(actual_rise, -power);

  const double base = fmax(_epsilon, transformed_actual_rise - transformed_projected_rise);

  const float scale_value = pow(base, -1.0 / power);

  // avoid 'explosions'
  return fminf(1e9f, scale_value);
}

static inline float _calculate_slope_matching_power(const float slope,
                                                    const float dx_transition_to_limit,
                                                    const float dy_transition_to_limit)
{
  return slope * dx_transition_to_limit / dy_transition_to_limit;
}

static inline float _calculate_fallback_curve_coefficient(const float dx_transition_to_limit,
                                                          const float dy_transition_to_limit,
                                                          const float exponent)
{
  return dy_transition_to_limit / powf(dx_transition_to_limit, exponent);
}

static inline float _sigmoid(const float x,
                             const float power)
{
  return x / powf(1.f + powf(x, power), 1.f / power);
}

static inline float _scaled_sigmoid(const float x,
                                    const float scale,
                                    const float slope,
                                    const float power,
                                    const float transition_x,
                                    const float transition_y)
{
  return scale * _sigmoid(slope * (x - transition_x) / scale, power) + transition_y;
}

static inline float _fallback_toe(const float x,
                                  const tone_mapping_params_t *params)
{
  return x < 0.f
           ? params->target_black
           : params->target_black
             + fmaxf(0.f, params->toe_fallback_coefficient * powf(x, params->toe_fallback_power));
}

static inline float _fallback_shoulder(const float x,
                                       const tone_mapping_params_t *params)
{
  return x >= 1.f
           ? params->target_white
           : params->target_white
             - fmaxf(0.f, params->shoulder_fallback_coefficient
                          * powf(1.f - x, params->shoulder_fallback_power));
}

static inline float _apply_curve(const float x,
                                 const tone_mapping_params_t *params)
{
  float result = 0.f;

  if(x < params->toe_transition_x)
  {
    result = params->need_convex_toe
               ? _fallback_toe(x, params)
               : _scaled_sigmoid(x, params->toe_scale, params->slope, params->toe_power,
                                 params->toe_transition_x, params->toe_transition_y);
  }
  else if(x <= params->shoulder_transition_x)
  {
    result = _line(x, params->slope, params->intercept);
  }
  else
  {
    result = params->need_concave_shoulder
               ? _fallback_shoulder(x, params)
               : _scaled_sigmoid(x, params->shoulder_scale, params->slope, params->shoulder_power,
                                 params->shoulder_transition_x, params->shoulder_transition_y);
  }
  return CLAMPF(result, params->target_black, params->target_white);
}

static inline float _calculate_pivot_y_at_gamma(const dt_iop_agx_params_t * p,
                                                const float gamma)
{
  return powf(CLAMPF(p->curve_pivot_y_linear_output,
                     p->curve_target_display_black_ratio,
                     p->curve_target_display_white_ratio),
              1.f / gamma);
}

static float _calculate_auto_gamma(const dt_iop_agx_params_t *p, const float pivot_x)
{
  if(!p->auto_gamma)
    return p->curve_gamma;

  const float gamma =
    pivot_x > 0.f && p->curve_pivot_y_linear_output > 0.f
    ? log2f(p->curve_pivot_y_linear_output) / log2f(pivot_x)
    : p->curve_gamma;

  return CLAMPF(gamma, 0.01f, 100.f);
}

static void _adjust_pivot(const dt_iop_agx_params_t *p,
                          tone_mapping_params_t *tone_mapping_params)
{
  // don't allow pivot_x to touch the endpoints
  tone_mapping_params->pivot_x = CLAMPF(p->curve_pivot_x, _epsilon, 1.f - _epsilon);

  tone_mapping_params->curve_gamma = _calculate_auto_gamma(p, tone_mapping_params->pivot_x);

  tone_mapping_params->pivot_y = _calculate_pivot_y_at_gamma(p, tone_mapping_params->curve_gamma);
}

static void _set_log_mapping_params(const dt_iop_agx_params_t *p,
                                    tone_mapping_params_t *curve_and_look_params)
{
  curve_and_look_params->white_relative_ev = p->range_white_relative_ev;
  curve_and_look_params->black_relative_ev = p->range_black_relative_ev;
  curve_and_look_params->range_in_ev = curve_and_look_params->white_relative_ev - curve_and_look_params->black_relative_ev;
}

static inline float _calculate_slope_gamma_compensation(const float gamma,
                                                        const float pivot_y,
                                                        const dt_iop_agx_params_t *p)
{
  // compensate contrast relative to gamma 2.2 to keep contrast around the pivot constant

  const float pivot_y_at_default_gamma = _calculate_pivot_y_at_gamma(p, _default_gamma);

  // We want to maintain the contrast after linearisation, so we need to apply
  // the chain rule (f(g(x)' = f'(g(x)) * g'(x))
  // to find the derivative of linearisation(curve(x)) = curve(x)^gamma.
  // By definition, the derivative of the curve g'(pivot_x)) = the slope;
  // also, curve(pivot_x) = pivot_y, so we need the derivative of the
  // power function at that point: f'(pivot_y).
  // We want to find gamma_compensated_slope to keep the overall derivative constant:
  // gamma_compensated_slope * [gamma * pivot_y_at_current_gamma^(current_gamma-1)] =
  // range_adjusted_slope * [_default_gamma * pivot_y_at_default_gamma^(_default_gamma-1)],
  // and thus gamma_compensated_slope = range_adjusted_slope *
  //              [_default_gamma * pivot_y^(_default_gamma-1)] / [gamma * pivot_y^(current_gamma-1)]

  const float derivative_at_current_gamma = gamma * powf(fmaxf(_epsilon, pivot_y), gamma - 1.0f);
  const float derivative_at_default_gamma =
    _default_gamma * powf(fmaxf(_epsilon, pivot_y_at_default_gamma), _default_gamma - 1.0f);
  return derivative_at_current_gamma / derivative_at_default_gamma;
}

static tone_mapping_params_t _calculate_tone_mapping_params(const dt_iop_agx_params_t *p)
{
  tone_mapping_params_t tone_mapping_params = { 0 };
  tone_mapping_params.hdr.headroom = 1.f;

  // look
  tone_mapping_params.look_lift = p->look_lift;
  tone_mapping_params.look_slope = p->look_slope;
  tone_mapping_params.look_saturation = p->look_saturation;
  const float brightness = p->look_brightness;
  tone_mapping_params.look_power = brightness < 1 ? 1.f / sqrtf(fmaxf(brightness, _epsilon)) : 1.f / brightness;
  tone_mapping_params.look_original_hue_mix_ratio = p->look_original_hue_mix_ratio;
  tone_mapping_params.look_tuned = p -> look_slope != 1.f
                                   || p->look_brightness != 1.f
                                   || p -> look_lift != 0.f
                                   || p -> look_saturation != 1.f;
  tone_mapping_params.restore_hue = p->look_original_hue_mix_ratio != 0.f;

  // log mapping
  _set_log_mapping_params(p, &tone_mapping_params);

  _adjust_pivot(p, &tone_mapping_params);

  // avoid range altering slope - 16.5 EV is the default AgX range; keep the meaning of slope
  const float range_adjusted_slope =
    p->curve_contrast_around_pivot * (tone_mapping_params.range_in_ev / 16.5f);

  const float compensation_factor = _calculate_slope_gamma_compensation(tone_mapping_params.curve_gamma, tone_mapping_params.pivot_y, p);

  tone_mapping_params.slope = range_adjusted_slope / compensation_factor;

  // toe
  tone_mapping_params.target_black =
    powf(p->curve_target_display_black_ratio, 1.f / tone_mapping_params.curve_gamma);
  tone_mapping_params.toe_power = fmaxf(0.01f, p->curve_toe_power);

  const float remaining_y_below_pivot = tone_mapping_params.pivot_y - tone_mapping_params.target_black;
  const float toe_length_y = remaining_y_below_pivot * p->curve_linear_ratio_below_pivot;
  float dx_linear_below_pivot = toe_length_y / tone_mapping_params.slope;
  // ...and subtract it from pivot_x to get the x coordinate where the linear section joins the toe
  // ... but keep the transition point above x = 0
  tone_mapping_params.toe_transition_x = fmaxf(_epsilon, tone_mapping_params.pivot_x - dx_linear_below_pivot);
  // fix up in case the limitation kicked in
  dx_linear_below_pivot = tone_mapping_params.pivot_x - tone_mapping_params.toe_transition_x;

  // from the 'run' pivot_x->toe_transition_x, we calculate the 'rise'
  const float toe_dy_below_pivot = tone_mapping_params.slope * dx_linear_below_pivot;
  tone_mapping_params.toe_transition_y = tone_mapping_params.pivot_y - toe_dy_below_pivot;

  // we use the same calculation as for the shoulder, so we flip the toe left <-> right, up <-> down
  const float inverse_toe_limit_x = 1.f; // 1 - toe_limix_x (toe_limix_x = 0, so inverse = 1)
  const float inverse_toe_limit_y = 1.f - tone_mapping_params.target_black; // Inverse limit y

  const float inverse_toe_transition_x = 1.f - tone_mapping_params.toe_transition_x;
  const float inverse_toe_transition_y = 1.f - tone_mapping_params.toe_transition_y;

  // and then flip the scale
  tone_mapping_params.toe_scale = -_scale(inverse_toe_limit_x, inverse_toe_limit_y,
                                          inverse_toe_transition_x, inverse_toe_transition_y,
                                          tone_mapping_params.slope, tone_mapping_params.toe_power);

  // limit_x is 0, so toe length =  -> toe_transition_x - limit_x is just toe_transition_x
  // the value is already limited to be >= epsilon, so safe to use in division
  const float toe_length_x = tone_mapping_params.toe_transition_x;
  const float toe_dy_transition_to_limit =
      fmaxf(_epsilon, tone_mapping_params.toe_transition_y - tone_mapping_params.target_black);
  const float toe_slope_transition_to_limit = toe_dy_transition_to_limit / toe_length_x;
  tone_mapping_params.need_convex_toe = toe_slope_transition_to_limit > tone_mapping_params.slope;

  // toe fallback curve params
  tone_mapping_params.toe_fallback_power = _calculate_slope_matching_power
    (tone_mapping_params.slope, toe_length_x, toe_dy_transition_to_limit);
  tone_mapping_params.toe_fallback_coefficient = _calculate_fallback_curve_coefficient
    (toe_length_x, toe_dy_transition_to_limit, tone_mapping_params.toe_fallback_power);

  // if x went from toe_transition_x to 0, at the given slope,
  // starting from toe_transition_y, where would we intersect the
  // y-axis?
  tone_mapping_params.intercept =
    tone_mapping_params.toe_transition_y
    - (tone_mapping_params.slope * tone_mapping_params.toe_transition_x);

  // shoulder
  tone_mapping_params.target_white =
    powf(p->curve_target_display_white_ratio, 1.f / tone_mapping_params.curve_gamma);
  const float remaining_y_above_pivot = tone_mapping_params.target_white - tone_mapping_params.pivot_y;
  const float shoulder_length_y = remaining_y_above_pivot * p->curve_linear_ratio_above_pivot;
  float dx_linear_above_pivot = shoulder_length_y / tone_mapping_params.slope;

  // don't allow shoulder_transition_x to reach 1
  tone_mapping_params.shoulder_transition_x =
    fminf(1.f - _epsilon, tone_mapping_params.pivot_x + dx_linear_above_pivot);
  dx_linear_above_pivot = tone_mapping_params.shoulder_transition_x - tone_mapping_params.pivot_x;

  const float shoulder_dy_above_pivot = tone_mapping_params.slope * dx_linear_above_pivot;
  tone_mapping_params.shoulder_transition_y = tone_mapping_params.pivot_y + shoulder_dy_above_pivot;
  tone_mapping_params.shoulder_power = fmaxf(0.01f, p->curve_shoulder_power);

  const float shoulder_limit_x = 1.f;
  tone_mapping_params.shoulder_scale = _scale(shoulder_limit_x,
                                              tone_mapping_params.target_white,
                                              tone_mapping_params.shoulder_transition_x,
                                              tone_mapping_params.shoulder_transition_y,
                                              tone_mapping_params.slope,
                                              tone_mapping_params.shoulder_power);

  // shoulder_transition_x < 1, guaranteed above
  const float shoulder_length_x = 1.f - tone_mapping_params.shoulder_transition_x;
  const float shoulder_dy_transition_to_limit =
    fmaxf(_epsilon, tone_mapping_params.target_white - tone_mapping_params.shoulder_transition_y);
  const float shoulder_slope_transition_to_limit =
    shoulder_dy_transition_to_limit / shoulder_length_x;
  tone_mapping_params.need_concave_shoulder =
    shoulder_slope_transition_to_limit > tone_mapping_params.slope;

  // shoulder fallback curve params
  tone_mapping_params.shoulder_fallback_power = _calculate_slope_matching_power
    (tone_mapping_params.slope, shoulder_length_x, shoulder_dy_transition_to_limit);
  tone_mapping_params.shoulder_fallback_coefficient = _calculate_fallback_curve_coefficient
    (shoulder_length_x, shoulder_dy_transition_to_limit, tone_mapping_params.shoulder_fallback_power);

  return tone_mapping_params;
}

static void _set_blenderlike_primaries(dt_iop_agx_params_t *p)
{
  p->disable_primaries_adjustments = FALSE;
  p->completely_reverse_primaries = FALSE;
  p->base_primaries = DT_AGX_REC2020;

  // AgX primaries settings that produce the same matrices under D50
  // as those used in the Blender OCIO config.
  // https://discuss.pixls.us/t/blender-agx-in-darktable-proof-of-concept/48697/1018
  // https://github.com/EaryChow/AgX_LUT_Gen/blob/main/AgXBaseRec2020.py
  p->red_inset = 0.29462451f;
  p->green_inset = 0.25861925f;
  p->blue_inset = 0.14641371f;
  p->red_rotation = 0.03540329f;
  p->green_rotation = -0.02108586f;
  p->blue_rotation = -0.06305724f;

  p->master_outset_ratio = 1.f;
  // Blender doesn't reverse rotations; we set up an exact unrotation below,
  // but let the user turn it on gradually
  p->master_unrotation_ratio = 0.f;

  p->red_outset = 0.290776401758f;
  p->green_outset = 0.263155400753f;
  p->blue_outset = 0.045810721815f;
  p->red_unrotation = p->red_rotation;
  p->green_unrotation = p->green_rotation;
  p->blue_unrotation = p->blue_rotation;
}

static void _set_unmodified_primaries(dt_iop_agx_params_t *p)
{
  p->disable_primaries_adjustments = FALSE;
  p->completely_reverse_primaries = FALSE;
  p->base_primaries = DT_AGX_REC2020;

  p->red_inset = 0.f;
  p->red_rotation = 0.f;
  p->green_inset = 0.f;
  p->green_rotation = 0.f;
  p->blue_inset = 0.f;
  p->blue_rotation = 0.f;

  p->master_outset_ratio = 1.f;
  p->master_unrotation_ratio = 1.f;

  p->red_outset = 0.f;
  p->red_unrotation = 0.f;
  p->green_outset = 0.f;
  p->green_unrotation = 0.f;
  p->blue_outset = 0.f;
  p->blue_unrotation = 0.f;
}

static void _set_smooth_primaries(dt_iop_agx_params_t *p)
{
  p->disable_primaries_adjustments = FALSE;
  p->completely_reverse_primaries = FALSE;

  // Sigmoid 'smooth' primaries settings
  p->base_primaries = DT_AGX_WORK_PROFILE;

  p->red_inset = 0.1f;
  p->green_inset = 0.1f;
  p->blue_inset = 0.15f;
  p->red_rotation = deg2radf(2.f);
  p->green_rotation = deg2radf(-1.f);
  p->blue_rotation = deg2radf(-3.f);

  // sigmoid: "Don't restore purity - try to avoid posterization."
  p->master_outset_ratio = 0.f;
  // but allow the user to do so simply by dragging the master control
  p->red_outset = p->red_inset;
  p->green_outset = p->green_inset;
  p->blue_outset = p->blue_inset;

  // sigmoid always reverses rotations
  p->master_unrotation_ratio = 1.f;
  p->red_unrotation = p->red_rotation;
  p->green_unrotation = p->green_rotation;
  p->blue_unrotation = p->blue_rotation;
}

/* ---- end of copy ---- */

static void _commit(dt_iop_module_t *m, const void *params)
{
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  memcpy(m->params, params, m->params_size);
  dtb__history_add(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
}

/* agx.c _update_pivot_x without its slider update: keep the pivot's exposure when the range changed */
static void _update_pivot_x_params(const float old_black_ev, const float old_white_ev, dt_iop_agx_params_t *const p)
{
  const float new_black_ev = p->range_black_relative_ev;
  const float new_white_ev = p->range_white_relative_ev;
  const float new_range = new_white_ev - new_black_ev;
  const float old_pivot_x = p->curve_pivot_x;
  const float old_range = old_white_ev - old_black_ev;
  const float pivot_ev = old_black_ev + (old_pivot_x * old_range);
  const float clamped_pivot_ev = CLAMPF(pivot_ev, new_black_ev, new_white_ev);
  p->curve_pivot_x = (clamped_pivot_ev - new_black_ev) / new_range;
}

static float _black_ev(const dt_iop_agx_params_t *p, const float *min)
{
  // _apply_auto_black_exposure
  const float black_norm = min3f(min);
  return CLAMPF(log2f(fmaxf(_epsilon, black_norm) / 0.18f) * (1.f + p->dynamic_range_scaling), -20.f, -0.1f);
}

static float _white_ev(const dt_iop_agx_params_t *p, const float *max)
{
  // _apply_auto_white_exposure
  const float white_norm = max3f(max);
  return CLAMPF(log2f(fmaxf(_epsilon, white_norm) / 0.18f) * (1.f + p->dynamic_range_scaling), 0.1f, 20.f);
}

static char *_agx_answer(const dtb_pick_result_t *pk, const dt_iop_agx_params_t *p)
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
  const struct { const char *n; float v; } v[] = {
    { "range_black_relative_ev", p->range_black_relative_ev }, { "range_white_relative_ev", p->range_white_relative_ev },
    { "curve_pivot_x", p->curve_pivot_x }, { "curve_pivot_y_linear_output", p->curve_pivot_y_linear_output },
    { "curve_gamma", p->curve_gamma } };
  for(size_t i = 0; i < G_N_ELEMENTS(v); i++)
  {
    json_builder_set_member_name(b, v[i].n);
    json_builder_add_double_value(b, v[i].v);
  }
  json_builder_end_object(b);
  json_builder_end_object(b);
  return dtb__json_finish(b);
}

/* pick {which: "black" | "white" | "range" | "pivot_x" | "pivot_xy", area}:
 * black and white relative exposure, auto tune levels, pivot relative
 * exposure, pivot target output (color_picker_apply; the pivot pickers use
 * the pipe's working profile as desktop's dt_ioppr_get_pipe_work_profile_info) */
static int _act_agx_pick(dt_iop_module_t *m, JsonObject *a, char **res)
{
  if(dtb_S.read_only) return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  const char *which = NULL;
  if(a && json_object_has_member(a, "which") && JSON_NODE_HOLDS_VALUE(json_object_get_member(a, "which")))
    which = json_object_get_string_member(a, "which");
  if(!which || (strcmp(which, "black") && strcmp(which, "white") && strcmp(which, "range") && strcmp(which, "pivot_x")
                && strcmp(which, "pivot_xy")))
    return dtb__err(DTB_E_ARG, "which is \"black\", \"white\", \"range\", \"pivot_x\" or \"pivot_xy\"");
  float area[4];
  dtb_pick_result_t pk;
  int r = dtb__read_area(a, area);
  // desktop's AgX pickers are DT_COLOR_PICKER_AREA | DT_COLOR_PICKER_DENOISE (gui_init)
  if(!r) r = dtb__pick(m, area, TRUE, FALSE, NULL, &pk);
  if(r) return r;
  if(!pk.have[0]) return dtb__err(DTB_E_ARG, "the area gives no sample in agx's input");
  dtb_pipe_t *pp = NULL;
  r = dtb__pipe_ready(FALSE, &pp);
  if(r) return r;
  dt_iop_agx_params_t p;
  memcpy(&p, m->params, sizeof(p));
  const float old_black_ev = p.range_black_relative_ev;
  const float old_white_ev = p.range_white_relative_ev;
  if(!strcmp(which, "black")) p.range_black_relative_ev = _black_ev(&p, pk.min[0]);
  else if(!strcmp(which, "white")) p.range_white_relative_ev = _white_ev(&p, pk.max[0]);
  else if(!strcmp(which, "range"))
  {
    p.range_black_relative_ev = _black_ev(&p, pk.min[0]);
    p.range_white_relative_ev = _white_ev(&p, pk.max[0]);
  }
  else
  {
    const dt_iop_order_iccprofile_info_t *profile = dt_ioppr_get_pipe_work_profile_info(&pp->pipe);
    if(!profile) return dtb__err(DTB_E_ENGINE, "no working profile");
    dt_aligned_pixel_t mean = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2], pk.mean[0][3] };
    // _apply_auto_pivot_x and _apply_auto_pivot_xy: _luminance_from_profile
    const float lum = dt_ioppr_get_rgb_matrix_luminance(mean, profile->matrix_in, profile->lut_in,
                                                        profile->unbounded_coeffs_in, profile->lutsize,
                                                        profile->nonlinearlut);
    const float picked_ev = CLAMPF(log2f(fmaxf(_epsilon, lum) / 0.18f), p.range_black_relative_ev, p.range_white_relative_ev);
    const float range = p.range_white_relative_ev - p.range_black_relative_ev;
    const float picked_pivot_x = (picked_ev - p.range_black_relative_ev) / range;
    if(!strcmp(which, "pivot_xy"))
    {
      const tone_mapping_params_t tone_mapping_params = _calculate_tone_mapping_params(&p);
      const float target_y = _apply_curve(picked_pivot_x, &tone_mapping_params);
      p.curve_pivot_y_linear_output = powf(target_y, p.curve_gamma);
    }
    p.curve_pivot_x = picked_pivot_x;
  }
  // color_picker_apply: then _update_pivot_x for every picker (the pivot's
  // gamma is only shown on the slider, not written: DT_ENTER_GUI_UPDATE)
  _update_pivot_x_params(old_black_ev, old_white_ev, &p);
  _commit(m, &p);
  *res = _agx_answer(&pk, &p);
  return DTB_OK;
}

/* the exposure module's effective exposure as desktop's
 * dt_dev_exposure_get_effective_exposure finds it: the enabled instance,
 * unmasked ones first, then the first; its _exposure_proxy_get_effective_exposure
 * (exposure + the compensations it applies). Deflicker: 0 (desktop takes the
 * value its panel computed, which needs the panel) */
static float _effective_exposure(void)
{
  // develop/imageop.c _find_preferred_instance(FILTER_REQUIRE, prefer_unmasked, prefer_first)
  dt_iop_module_t *best = NULL;
  int best_score = -1;
  for(GList *l = g_list_last(dtb_S.dev.iop); l; l = g_list_previous(l))
  {
    dt_iop_module_t *e = l->data;
    if(!dt_iop_module_is(e, "exposure") || e->iop_order == INT_MAX || !e->enabled) continue;
    const gboolean no_mask = e->blend_params->mask_mode == DEVELOP_MASK_DISABLED
                             || e->blend_params->mask_mode == DEVELOP_MASK_ENABLED;
    const int score = no_mask ? 2 : 0;
    if(score + 1 > best_score)
    {
      best_score = score;
      best = e;
    }
  }
  if(!best) return 0.0f;
  dtb_field_ref_t r;
  float ev = 0.0f;
  if(!dtb__field_resolve(best, best->params, "mode", &r) && *(int *)r.p != 0) return 0.0f;
  if(!dtb__field_resolve(best, best->params, "exposure", &r)) ev = *(float *)r.p;
  const dt_image_t *img = &dtb_S.dev.image_storage;
  // exposure.c _exposure_compensation_ev with _get_exposure_bias and _get_highlight_bias
  if(!dtb__field_resolve(best, best->params, "compensate_exposure_bias", &r) && *(gboolean *)r.p)
  {
    const float bias = img->exif_exposure_bias;
    ev -= bias != DT_EXIF_TAG_UNINITIALIZED ? CLAMP(bias, -5.0f, 5.0f) : 0.0f;
  }
  if(!dtb__field_resolve(best, best->params, "compensate_hilite_pres", &r) && *(gboolean *)r.p)
  {
    const float bias = img->exif_highlight_preservation > 0.0f ? img->exif_highlight_preservation : 0.0f;
    ev += bias != DT_EXIF_TAG_UNINITIALIZED ? CLAMP(bias, -1.0f, 4.0f) : 0.0f;
  }
  return ev;
}

/* 1 = no such action here */
int dtb__module_action_agx(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(!dt_iop_module_is(m, "agx")) return 1;
  const gboolean known = !strcmp(action, "pick") || !strcmp(action, "read_exposure")
                         || !strcmp(action, "reset_primaries") || !strcmp(action, "primaries_from_above");
  if(!known) return 1;
  if(m->params_size != sizeof(dt_iop_agx_params_t))
    return dtb__err(DTB_E_ENGINE, "agx's parameters are %d bytes, the copy's %d (upstream changed?)", m->params_size,
                    (int)sizeof(dt_iop_agx_params_t));
  if(!strcmp(action, "pick")) return _act_agx_pick(m, args, result);
  if(dtb_S.read_only) return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  dt_iop_agx_params_t p;
  memcpy(&p, m->params, sizeof(p));
  if(!strcmp(action, "read_exposure"))
  {
    // _read_exposure_params_callback: _adjust_relative_exposure_from_exposure_params, then the gamma
    const float old_black_ev = p.range_black_relative_ev;
    const float old_white_ev = p.range_white_relative_ev;
    const float exposure = _effective_exposure();
    p.range_black_relative_ev = CLAMPF((-8.f + 0.5f * exposure) * (1.f + p.dynamic_range_scaling), -20.f, -0.1f);
    p.range_white_relative_ev = CLAMPF((4.f + 0.8 * exposure) * (1.f + p.dynamic_range_scaling), 0.1f, 20.f);
    _update_pivot_x_params(old_black_ev, old_white_ev, &p);
    const float pivot_x = CLAMPF(p.curve_pivot_x, _epsilon, 1.f - _epsilon);
    p.curve_gamma = _calculate_auto_gamma(&p, pivot_x);
  }
  else if(!strcmp(action, "reset_primaries"))
  {
    // _apply_primaries_from_menu_callback: "blender", "smooth", "unmodified"
    const char *preset = args && json_object_has_member(args, "preset") ? json_object_get_string_member(args, "preset") : NULL;
    if(preset && !strcmp(preset, "blender")) _set_blenderlike_primaries(&p);
    else if(preset && !strcmp(preset, "smooth")) _set_smooth_primaries(&p);
    else if(preset && !strcmp(preset, "unmodified")) _set_unmodified_primaries(&p);
    else return dtb__err(DTB_E_ARG, "preset is \"blender\", \"smooth\" or \"unmodified\"");
  }
  else
  {
    // _set_post_curve_primaries_from_pre_callback
    p.master_outset_ratio = 1.0f;
    p.master_unrotation_ratio = 1.0f;
    p.red_outset = p.red_inset;
    p.green_outset = p.green_inset;
    p.blue_outset = p.blue_inset;
    p.red_unrotation = p.red_rotation;
    p.green_unrotation = p.green_rotation;
    p.blue_unrotation = p.blue_rotation;
  }
  _commit(m, &p);
  *result = _agx_answer(NULL, &p);
  return DTB_OK;
}

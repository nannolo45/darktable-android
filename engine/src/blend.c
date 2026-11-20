// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: blending of one instance (P1b part 2, docs/design/
 * engine-api.md 6.6). Reads and writes the instance's
 * dt_develop_blend_params_t (develop/blend.h) by name, validated as
 * desktop's blending panel (develop/blend_gui.c) constrains it: the mask
 * modes of dt_develop_mask_mode_names, the blend modes the panel lists for
 * the blend colour space (blend_gui.c, the "initialization of blending
 * modes" in dt_iop_gui_update_blending), the colour spaces it offers for the
 * module (Lab / RGB display / RGB scene for modules whose default is one of
 * those; the raw modules keep raw), the sliders' ranges, and a colour space
 * change resets the parametric channels as _blendif_change_blend_colorspace
 * does (dt_develop_blend_init_blendif_parameters). History: as param_set.
 */
#include "dtb_internal.h"

#include "develop/blend.h"

#include <math.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* names                                                              */

typedef struct _name_t
{
  const char *name;
  uint32_t value;
} _name_t;

static const _name_t _mask_modes[] = {
  { "off", DEVELOP_MASK_DISABLED },
  { "uniform", DEVELOP_MASK_ENABLED },
  { "drawn", DEVELOP_MASK_MASK | DEVELOP_MASK_ENABLED },
  { "parametric", DEVELOP_MASK_CONDITIONAL | DEVELOP_MASK_ENABLED },
  { "raster", DEVELOP_MASK_RASTER | DEVELOP_MASK_ENABLED },
  { "drawn_parametric", DEVELOP_MASK_MASK_CONDITIONAL | DEVELOP_MASK_ENABLED },
  { "flexi", DEVELOP_MASK_FLEXI | DEVELOP_MASK_ENABLED },
  { NULL, 0 }
};

static const _name_t _colorspaces[] = {
  { "default", DEVELOP_BLEND_CS_NONE },
  { "raw", DEVELOP_BLEND_CS_RAW },
  { "lab", DEVELOP_BLEND_CS_LAB },
  { "rgb_display", DEVELOP_BLEND_CS_RGB_DISPLAY },
  { "rgb_scene", DEVELOP_BLEND_CS_RGB_SCENE },
  { NULL, 0 }
};

/* dt_develop_blend_mode_names (blend_gui.c), in its order */
static const _name_t _blend_modes[] = {
  { "normal", DEVELOP_BLEND_NORMAL2 },
  { "average", DEVELOP_BLEND_AVERAGE },
  { "difference", DEVELOP_BLEND_DIFFERENCE2 },
  { "normal_bounded", DEVELOP_BLEND_BOUNDED },
  { "lighten", DEVELOP_BLEND_LIGHTEN },
  { "darken", DEVELOP_BLEND_DARKEN },
  { "screen", DEVELOP_BLEND_SCREEN },
  { "multiply", DEVELOP_BLEND_MULTIPLY },
  { "divide", DEVELOP_BLEND_DIVIDE },
  { "addition", DEVELOP_BLEND_ADD },
  { "subtract", DEVELOP_BLEND_SUBTRACT },
  { "geometric_mean", DEVELOP_BLEND_GEOMETRIC_MEAN },
  { "harmonic_mean", DEVELOP_BLEND_HARMONIC_MEAN },
  { "overlay", DEVELOP_BLEND_OVERLAY },
  { "softlight", DEVELOP_BLEND_SOFTLIGHT },
  { "hardlight", DEVELOP_BLEND_HARDLIGHT },
  { "vividlight", DEVELOP_BLEND_VIVIDLIGHT },
  { "linearlight", DEVELOP_BLEND_LINEARLIGHT },
  { "pinlight", DEVELOP_BLEND_PINLIGHT },
  { "lightness", DEVELOP_BLEND_LIGHTNESS },
  { "chromaticity", DEVELOP_BLEND_CHROMATICITY },
  { "lab_lightness", DEVELOP_BLEND_LAB_LIGHTNESS },
  { "lab_a", DEVELOP_BLEND_LAB_A },
  { "lab_b", DEVELOP_BLEND_LAB_B },
  { "lab_color", DEVELOP_BLEND_LAB_COLOR },
  { "rgb_red", DEVELOP_BLEND_RGB_R },
  { "rgb_green", DEVELOP_BLEND_RGB_G },
  { "rgb_blue", DEVELOP_BLEND_RGB_B },
  { "hsv_value", DEVELOP_BLEND_HSV_VALUE },
  { "hsv_color", DEVELOP_BLEND_HSV_COLOR },
  { "hue", DEVELOP_BLEND_HUE },
  { "color", DEVELOP_BLEND_COLOR },
  { "coloradjustment", DEVELOP_BLEND_COLORADJUST },
  // deprecated: shown only when a history holds them
  { "difference_deprecated", DEVELOP_BLEND_DIFFERENCE },
  { "subtract_inverse_deprecated", DEVELOP_BLEND_SUBTRACT_INVERSE },
  { "divide_inverse_deprecated", DEVELOP_BLEND_DIVIDE_INVERSE },
  { "lab_l_deprecated", DEVELOP_BLEND_LAB_L },
  { NULL, 0 }
};

/* the modes desktop's list offers for a colour space (blend_gui.c): ranges
 * of _blend_modes by name */
static gboolean _mode_offered(const dt_develop_blend_colorspace_t csp, const uint32_t mode)
{
  static const char *display[] = { "normal", "average", "difference", "normal_bounded", "lighten", "addition",
                                   "screen", "darken", "subtract", "multiply", "overlay", "softlight", "hardlight",
                                   "vividlight", "linearlight", "pinlight", NULL };
  static const char *lab_extra[] = { "lab_lightness", "lab_a", "lab_b", "lab_color", "hue", "color",
                                     "coloradjustment", "lightness", "chromaticity", NULL };
  static const char *rgbd_extra[] = { "rgb_red", "rgb_green", "rgb_blue", "hsv_value", "hsv_color", "hue", "color",
                                      "coloradjustment", "lightness", "chromaticity", NULL };
  static const char *scene[] = { "normal", "average", "difference", "multiply", "divide", "addition", "subtract",
                                 "geometric_mean", "harmonic_mean", "rgb_red", "rgb_green", "rgb_blue",
                                 "lightness", "chromaticity", NULL };
  const char *name = NULL;
  for(int k = 0; _blend_modes[k].name; k++)
    if(_blend_modes[k].value == mode) name = _blend_modes[k].name;
  if(!name) return FALSE;
  const char **lists[2] = { NULL, NULL };
  switch(csp)
  {
    case DEVELOP_BLEND_CS_RAW: lists[0] = display; break;
    case DEVELOP_BLEND_CS_LAB: lists[0] = display; lists[1] = lab_extra; break;
    case DEVELOP_BLEND_CS_RGB_DISPLAY: lists[0] = display; lists[1] = rgbd_extra; break;
    case DEVELOP_BLEND_CS_RGB_SCENE: lists[0] = scene; break;
    default: return FALSE;
  }
  for(int l = 0; l < 2; l++)
    for(int k = 0; lists[l] && lists[l][k]; k++)
      if(!strcmp(lists[l][k], name)) return TRUE;
  return FALSE;
}

static const char *_name_of(const _name_t *t, const uint32_t v)
{
  for(int k = 0; t[k].name; k++)
    if(t[k].value == v) return t[k].name;
  return NULL;
}

static int _value_of(const _name_t *t, const char *name, uint32_t *v)
{
  for(int k = 0; t[k].name; k++)
    if(!strcmp(t[k].name, name))
    {
      *v = t[k].value;
      return TRUE;
    }
  return FALSE;
}

/* parametric channels as desktop names them (blend_gui.c Lab_channels,
 * rgb_channels, rgbj_channels), and their index in blendif */
typedef struct _chan_t
{
  const char *name;
  int index;
} _chan_t;

static const _chan_t _lab_ch[] = { { "L_in", DEVELOP_BLENDIF_L_in }, { "a_in", DEVELOP_BLENDIF_A_in },
                                   { "b_in", DEVELOP_BLENDIF_B_in }, { "C_in", DEVELOP_BLENDIF_C_in },
                                   { "h_in", DEVELOP_BLENDIF_h_in }, { "L_out", DEVELOP_BLENDIF_L_out },
                                   { "a_out", DEVELOP_BLENDIF_A_out }, { "b_out", DEVELOP_BLENDIF_B_out },
                                   { "C_out", DEVELOP_BLENDIF_C_out }, { "h_out", DEVELOP_BLENDIF_h_out },
                                   { NULL, 0 } };
static const _chan_t _rgbd_ch[] = { { "g_in", DEVELOP_BLENDIF_GRAY_in }, { "R_in", DEVELOP_BLENDIF_RED_in },
                                    { "G_in", DEVELOP_BLENDIF_GREEN_in }, { "B_in", DEVELOP_BLENDIF_BLUE_in },
                                    { "H_in", DEVELOP_BLENDIF_H_in }, { "S_in", DEVELOP_BLENDIF_S_in },
                                    { "l_in", DEVELOP_BLENDIF_l_in }, { "g_out", DEVELOP_BLENDIF_GRAY_out },
                                    { "R_out", DEVELOP_BLENDIF_RED_out }, { "G_out", DEVELOP_BLENDIF_GREEN_out },
                                    { "B_out", DEVELOP_BLENDIF_BLUE_out }, { "H_out", DEVELOP_BLENDIF_H_out },
                                    { "S_out", DEVELOP_BLENDIF_S_out }, { "l_out", DEVELOP_BLENDIF_l_out },
                                    { NULL, 0 } };
static const _chan_t _rgbs_ch[] = { { "g_in", DEVELOP_BLENDIF_GRAY_in }, { "R_in", DEVELOP_BLENDIF_RED_in },
                                    { "G_in", DEVELOP_BLENDIF_GREEN_in }, { "B_in", DEVELOP_BLENDIF_BLUE_in },
                                    { "Jz_in", DEVELOP_BLENDIF_Jz_in }, { "Cz_in", DEVELOP_BLENDIF_Cz_in },
                                    { "hz_in", DEVELOP_BLENDIF_hz_in }, { "g_out", DEVELOP_BLENDIF_GRAY_out },
                                    { "R_out", DEVELOP_BLENDIF_RED_out }, { "G_out", DEVELOP_BLENDIF_GREEN_out },
                                    { "B_out", DEVELOP_BLENDIF_BLUE_out }, { "Jz_out", DEVELOP_BLENDIF_Jz_out },
                                    { "Cz_out", DEVELOP_BLENDIF_Cz_out }, { "hz_out", DEVELOP_BLENDIF_hz_out },
                                    { NULL, 0 } };

static dt_develop_blend_colorspace_t _effective_cst(dt_iop_module_t *m, const dt_develop_blend_params_t *bp)
{
  return bp->blend_cst == DEVELOP_BLEND_CS_NONE ? dt_develop_blend_default_module_blend_colorspace(m)
                                                 : (dt_develop_blend_colorspace_t)bp->blend_cst;
}

static const _chan_t *_channels(const dt_develop_blend_colorspace_t csp)
{
  switch(csp)
  {
    case DEVELOP_BLEND_CS_LAB: return _lab_ch;
    case DEVELOP_BLEND_CS_RGB_DISPLAY: return _rgbd_ch;
    case DEVELOP_BLEND_CS_RGB_SCENE: return _rgbs_ch;
    default: return NULL;
  }
}

/* the stored boost's lowest value: Jz and Cz carry an offset (blend.c
 * _blend_init_blendif_boost_parameters) */
static float _boost_min(const dt_develop_blend_colorspace_t csp, const int index)
{
  if(csp == DEVELOP_BLEND_CS_RGB_SCENE
     && (index == DEVELOP_BLENDIF_Jz_in || index == DEVELOP_BLENDIF_Cz_in || index == DEVELOP_BLENDIF_Jz_out
         || index == DEVELOP_BLENDIF_Cz_out))
    return -6.64385619f;
  return 0.0f;
}

static gboolean _supported(const dt_iop_module_t *m)
{
  return (m->flags() & IOP_FLAGS_SUPPORTS_BLENDING) && m->blend_params;
}

/* ------------------------------------------------------------------ */
/* get                                                                */

static void _num(JsonBuilder *b, const char *k, const double v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_double_value(b, v);
}

int dtb_blend_get(const char *op, int priority, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s %d", op ? op : "(null)", priority);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "supported");
  json_builder_add_boolean_value(b, _supported(m));
  if(_supported(m))
  {
    const dt_develop_blend_params_t *bp = m->blend_params;
    const char *mm = _name_of(_mask_modes, bp->mask_mode);
    json_builder_set_member_name(b, "masks");
    json_builder_add_string_value(b, mm ? mm : "other");
    json_builder_set_member_name(b, "mask_mode_flags");
    json_builder_begin_array(b);
    if(bp->mask_mode & DEVELOP_MASK_ENABLED) json_builder_add_string_value(b, "enabled");
    if(bp->mask_mode & DEVELOP_MASK_MASK) json_builder_add_string_value(b, "drawn");
    if(bp->mask_mode & DEVELOP_MASK_CONDITIONAL) json_builder_add_string_value(b, "parametric");
    if(bp->mask_mode & DEVELOP_MASK_RASTER) json_builder_add_string_value(b, "raster");
    if(bp->mask_mode & DEVELOP_MASK_FLEXI) json_builder_add_string_value(b, "flexi");
    json_builder_end_array(b);
    const char *cs = _name_of(_colorspaces, bp->blend_cst);
    json_builder_set_member_name(b, "colorspace");
    json_builder_add_string_value(b, cs ? cs : "default");
    json_builder_set_member_name(b, "effective_colorspace");
    const char *ecs = _name_of(_colorspaces, _effective_cst(m, bp));
    json_builder_add_string_value(b, ecs ? ecs : "default");
    const uint32_t mode = bp->blend_mode & DEVELOP_BLEND_MODE_MASK;
    const char *mn = _name_of(_blend_modes, mode);
    json_builder_set_member_name(b, "mode");
    if(mn)
      json_builder_add_string_value(b, mn);
    else
    {
      gchar *u = g_strdup_printf("unknown_%u", mode);
      json_builder_add_string_value(b, u);
      g_free(u);
    }
    json_builder_set_member_name(b, "reverse");
    json_builder_add_boolean_value(b, (bp->blend_mode & DEVELOP_BLEND_REVERSE) != 0);
    _num(b, "parameter", bp->blend_parameter);
    _num(b, "opacity", bp->opacity);
    json_builder_set_member_name(b, "combine");
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "invert");
    json_builder_add_boolean_value(b, (bp->mask_combine & DEVELOP_COMBINE_INV) != 0);
    json_builder_set_member_name(b, "mode");
    json_builder_add_string_value(b, (bp->mask_combine & DEVELOP_COMBINE_INCL) ? "inclusive" : "exclusive");
    json_builder_end_object(b);
    _num(b, "feathering_radius", bp->feathering_radius);
    const char *guide = bp->feathering_guide == DEVELOP_MASK_GUIDE_IN_BEFORE_BLUR ? "input_before_blur"
                        : bp->feathering_guide == DEVELOP_MASK_GUIDE_OUT_BEFORE_BLUR ? "output_before_blur"
                        : bp->feathering_guide == DEVELOP_MASK_GUIDE_IN_AFTER_BLUR ? "input_after_blur"
                        : bp->feathering_guide == DEVELOP_MASK_GUIDE_OUT_AFTER_BLUR ? "output_after_blur"
                        : "other";
    json_builder_set_member_name(b, "feathering_guide");
    json_builder_add_string_value(b, guide);
    _num(b, "blur_radius", bp->blur_radius);
    _num(b, "contrast", bp->contrast);
    _num(b, "brightness", bp->brightness);
    _num(b, "details", bp->details);
    json_builder_set_member_name(b, "parametric");
    json_builder_begin_object(b);
    const dt_develop_blend_colorspace_t csp = _effective_cst(m, bp);
    const _chan_t *ch = _channels(csp);
    for(int k = 0; ch && ch[k].name; k++)
    {
      const int i = ch[k].index;
      json_builder_set_member_name(b, ch[k].name);
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "active");
      json_builder_add_boolean_value(b, (bp->blendif & (1u << i)) != 0);
      json_builder_set_member_name(b, "invert");
      json_builder_add_boolean_value(b, (bp->blendif & (1u << (i + 16))) != 0);
      json_builder_set_member_name(b, "range");
      json_builder_begin_array(b);
      for(int j = 0; j < 4; j++) json_builder_add_double_value(b, bp->blendif_parameters[4 * i + j]);
      json_builder_end_array(b);
      _num(b, "boost", bp->blendif_boost_factors[i]);
      json_builder_end_object(b);
    }
    json_builder_end_object(b);
    json_builder_set_member_name(b, "raster");
    if(bp->raster_mask_source[0])
    {
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "op");
      json_builder_add_string_value(b, bp->raster_mask_source);
      json_builder_set_member_name(b, "priority");
      json_builder_add_int_value(b, bp->raster_mask_instance);
      json_builder_set_member_name(b, "invert");
      json_builder_add_boolean_value(b, bp->raster_mask_invert);
      json_builder_end_object(b);
    }
    else
      json_builder_add_null_value(b);
    json_builder_set_member_name(b, "mask_id");
    if(dt_is_valid_maskid(bp->mask_id))
      json_builder_add_int_value(b, bp->mask_id);
    else
      json_builder_add_null_value(b);
    json_builder_set_member_name(b, "mask_lock");
    json_builder_add_boolean_value(b, bp->mask_lock != 0);
  }
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* set                                                                */

static int _want_num(JsonNode *n, const char *path, double *v)
{
  if(!JSON_NODE_HOLDS_VALUE(n)
     || (json_node_get_value_type(n) != G_TYPE_DOUBLE && json_node_get_value_type(n) != G_TYPE_INT64))
    return dtb__err(DTB_E_TYPE, "%s takes a number", path);
  *v = json_node_get_double(n);
  if(!isfinite(*v)) return dtb__err(DTB_E_RANGE, "%s: not finite", path);
  return DTB_OK;
}

static int _want_bool(JsonNode *n, const char *path, gboolean *v)
{
  if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_BOOLEAN)
    return dtb__err(DTB_E_TYPE, "%s takes true or false", path);
  *v = json_node_get_boolean(n);
  return DTB_OK;
}

static int _want_str(JsonNode *n, const char *path, const char **v)
{
  if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_STRING)
    return dtb__err(DTB_E_TYPE, "%s takes a name", path);
  *v = json_node_get_string(n);
  return DTB_OK;
}

static int _range(const char *path, const double v, const double lo, const double hi)
{
  if(v < lo || v > hi) return dtb__err(DTB_E_RANGE, "%s: %g outside %g..%g", path, v, lo, hi);
  return DTB_OK;
}

/* one path into bp (a copy); the colour space first, see dtb_blend_set */
static int _set_one(dt_iop_module_t *m, dt_develop_blend_params_t *bp, const char *path, JsonNode *n)
{
  double d = 0.0;
  gboolean t = FALSE;
  const char *s = NULL;
  int r;
  if(!strcmp(path, "masks"))
  {
    uint32_t v;
    if((r = _want_str(n, path, &s))) return r;
    if(!_value_of(_mask_modes, s, &v)) return dtb__err(DTB_E_ARG, "masks: unknown mode '%s'", s);
    if((v & DEVELOP_MASK_RASTER) && !bp->raster_mask_source[0])
      return dtb__err(DTB_E_ARG, "masks: raster needs a raster mask source (not settable through the bridge yet)");
    if((v & (DEVELOP_MASK_MASK | DEVELOP_MASK_FLEXI)) && (m->flags() & IOP_FLAGS_NO_MASKS))
      return dtb__err(DTB_E_ARG, "%s has no drawn masks", m->op);
    bp->mask_mode = v;
    return DTB_OK;
  }
  if(!strcmp(path, "colorspace"))
  {
    uint32_t v;
    if((r = _want_str(n, path, &s))) return r;
    if(!_value_of(_colorspaces, s, &v)) return dtb__err(DTB_E_ARG, "colorspace: unknown '%s'", s);
    const dt_develop_blend_colorspace_t def = dt_develop_blend_default_module_blend_colorspace(m);
    // desktop offers default, Lab, RGB display and RGB scene for modules whose
    // default is one of those; a raw module keeps raw (blend_gui.c)
    const gboolean offered = v == DEVELOP_BLEND_CS_NONE || v == (uint32_t)def
                             || ((def == DEVELOP_BLEND_CS_LAB || def == DEVELOP_BLEND_CS_RGB_DISPLAY
                                  || def == DEVELOP_BLEND_CS_RGB_SCENE)
                                 && (v == DEVELOP_BLEND_CS_LAB || v == DEVELOP_BLEND_CS_RGB_DISPLAY
                                     || v == DEVELOP_BLEND_CS_RGB_SCENE));
    if(!offered) return dtb__err(DTB_E_ARG, "colorspace '%s' is not offered for %s", s, m->op);
    // _blendif_change_blend_colorspace: "default" means the module's own
    const dt_develop_blend_colorspace_t cst = v == DEVELOP_BLEND_CS_NONE ? def : (dt_develop_blend_colorspace_t)v;
    if(cst != (dt_develop_blend_colorspace_t)bp->blend_cst) dt_develop_blend_init_blendif_parameters(bp, cst);
    return DTB_OK;
  }
  if(!strcmp(path, "mode"))
  {
    uint32_t v;
    if((r = _want_str(n, path, &s))) return r;
    if(!_value_of(_blend_modes, s, &v)) return dtb__err(DTB_E_ARG, "mode: unknown '%s'", s);
    if(!_mode_offered(_effective_cst(m, bp), v))
      return dtb__err(DTB_E_ARG, "mode '%s' is not offered in this blend colour space", s);
    bp->blend_mode = (bp->blend_mode & DEVELOP_BLEND_REVERSE) | v;
    return DTB_OK;
  }
  if(!strcmp(path, "reverse"))
  {
    if((r = _want_bool(n, path, &t))) return r;
    bp->blend_mode = (bp->blend_mode & ~DEVELOP_BLEND_REVERSE) | (t ? DEVELOP_BLEND_REVERSE : 0);
    return DTB_OK;
  }
  if(!strcmp(path, "combine.invert") || !strcmp(path, "combine.mode"))
  {
    uint32_t c = bp->mask_combine;
    if(!strcmp(path, "combine.invert"))
    {
      if((r = _want_bool(n, path, &t))) return r;
      c = (c & ~DEVELOP_COMBINE_INV) | (t ? DEVELOP_COMBINE_INV : 0);
    }
    else
    {
      if((r = _want_str(n, path, &s))) return r;
      if(strcmp(s, "inclusive") && strcmp(s, "exclusive")) return dtb__err(DTB_E_ARG, "combine.mode: inclusive or exclusive");
      c = (c & ~DEVELOP_COMBINE_INCL) | (!strcmp(s, "inclusive") ? DEVELOP_COMBINE_INCL : 0);
    }
    bp->mask_combine = c;
    // _blendop_masks_combine_callback: the parametric channels that are not
    // used take the polarity of the combine mode
    const uint32_t mask = _effective_cst(m, bp) == DEVELOP_BLEND_CS_LAB ? DEVELOP_BLENDIF_Lab_MASK : DEVELOP_BLENDIF_RGB_MASK;
    const uint32_t unused = mask & ~bp->blendif;
    bp->blendif &= ~(unused << 16);
    if(bp->mask_combine & DEVELOP_COMBINE_INCL) bp->blendif |= unused << 16;
    return DTB_OK;
  }
  if(!strcmp(path, "feathering_guide"))
  {
    if((r = _want_str(n, path, &s))) return r;
    if(!strcmp(s, "input_before_blur")) bp->feathering_guide = DEVELOP_MASK_GUIDE_IN_BEFORE_BLUR;
    else if(!strcmp(s, "output_before_blur")) bp->feathering_guide = DEVELOP_MASK_GUIDE_OUT_BEFORE_BLUR;
    else if(!strcmp(s, "input_after_blur")) bp->feathering_guide = DEVELOP_MASK_GUIDE_IN_AFTER_BLUR;
    else if(!strcmp(s, "output_after_blur")) bp->feathering_guide = DEVELOP_MASK_GUIDE_OUT_AFTER_BLUR;
    else return dtb__err(DTB_E_ARG, "feathering_guide: unknown '%s'", s);
    return DTB_OK;
  }
  // the sliders and their ranges (blend_gui.c, dt_iop_gui_init_blending)
  static const struct { const char *path; double lo, hi; size_t off; } sliders[] = {
    { "opacity", 0.0, 100.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, opacity) },
    { "parameter", -18.0, 18.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, blend_parameter) },
    { "feathering_radius", 0.0, 250.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, feathering_radius) },
    { "blur_radius", 0.0, 100.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, blur_radius) },
    { "contrast", -1.0, 1.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, contrast) },
    { "brightness", -1.0, 1.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, brightness) },
    { "details", -1.0, 1.0, G_STRUCT_OFFSET(dt_develop_blend_params_t, details) },
  };
  for(size_t k = 0; k < G_N_ELEMENTS(sliders); k++)
    if(!strcmp(path, sliders[k].path))
    {
      if((r = _want_num(n, path, &d)) || (r = _range(path, d, sliders[k].lo, sliders[k].hi))) return r;
      *(float *)((uint8_t *)bp + sliders[k].off) = (float)d;
      return DTB_OK;
    }
  if(g_str_has_prefix(path, "parametric."))
  {
    const dt_develop_blend_colorspace_t csp = _effective_cst(m, bp);
    const _chan_t *ch = _channels(csp);
    if(!ch) return dtb__err(DTB_E_ARG, "no parametric channels in this blend colour space");
    gchar **parts = g_strsplit(path + strlen("parametric."), ".", 2);
    int idx = -1;
    for(int k = 0; parts[0] && ch[k].name; k++)
      if(!strcmp(ch[k].name, parts[0])) idx = ch[k].index;
    const char *field = parts[0] ? parts[1] : NULL;
    r = DTB_OK;
    if(idx < 0 || !field) r = dtb__err(DTB_E_NOT_FOUND, "no parametric channel in '%s'", path);
    else if(!strcmp(field, "active"))
    {
      if(!(r = _want_bool(n, path, &t))) bp->blendif = t ? bp->blendif | (1u << idx) : bp->blendif & ~(1u << idx);
    }
    else if(!strcmp(field, "invert"))
    {
      if(!(r = _want_bool(n, path, &t)))
        bp->blendif = t ? bp->blendif | (1u << (idx + 16)) : bp->blendif & ~(1u << (idx + 16));
    }
    else if(!strcmp(field, "boost"))
    {
      const float lo = _boost_min(csp, idx);
      if(!(r = _want_num(n, path, &d)) && !(r = _range(path, d, lo, lo + 18.0)))
        bp->blendif_boost_factors[idx] = (float)d;
    }
    else if(!strcmp(field, "range"))
    {
      JsonArray *a = JSON_NODE_HOLDS_ARRAY(n) ? json_node_get_array(n) : NULL;
      if(!a || json_array_get_length(a) != 4) r = dtb__err(DTB_E_TYPE, "%s takes [a, b, c, d]", path);
      float v[4];
      for(int j = 0; !r && j < 4; j++)
      {
        if(!(r = _want_num(json_array_get_element(a, j), path, &d)) && !(r = _range(path, d, 0.0, 1.0))) v[j] = (float)d;
      }
      if(!r && !(v[0] <= v[1] && v[1] <= v[2] && v[2] <= v[3]))
        r = dtb__err(DTB_E_RANGE, "%s: the points must be in order (a <= b <= c <= d)", path);
      if(!r)
      {
        for(int j = 0; j < 4; j++) bp->blendif_parameters[4 * idx + j] = v[j];
        // a range that is not the whole 0..1 makes the channel take part
        // (desktop's markers set the channel's bit when moved)
        const gboolean full = v[0] == 0.0f && v[1] == 0.0f && v[2] == 1.0f && v[3] == 1.0f;
        if(!full) bp->blendif |= 1u << idx;
      }
    }
    else
      r = dtb__err(DTB_E_NOT_FOUND, "no field '%s' of a parametric channel", field);
    g_strfreev(parts);
    return r;
  }
  if(!strcmp(path, "raster.invert"))
  {
    if((r = _want_bool(n, path, &t))) return r;
    bp->raster_mask_invert = t;
    return DTB_OK;
  }
  if(!strcmp(path, "mask_lock"))
  {
    if((r = _want_bool(n, path, &t))) return r;
    bp->mask_lock = t ? 1 : 0;
    return DTB_OK;
  }
  return dtb__err(DTB_E_NOT_FOUND, "no blend setting '%s'", path);
}

int dtb_blend_set(const char *op, int priority, const char *values_json)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s %d", op ? op : "(null)", priority);
  if(!_supported(m)) return dtb__err(DTB_E_ARG, "%s has no blending", m->op);
  JsonNode *root = dtb__json_parse(values_json ? values_json : "");
  if(!root) return DTB_E_ARG;
  if(!JSON_NODE_HOLDS_OBJECT(root) || json_object_get_size(json_node_get_object(root)) == 0)
  {
    json_node_unref(root);
    return dtb__err(DTB_E_ARG, "blend_set takes an object {path: value, ...}");
  }
  JsonObject *o = json_node_get_object(root);
  dt_develop_blend_params_t bp = *m->blend_params;
  int r = DTB_OK;
  // the colour space first: it decides the modes and channels the others name
  if(json_object_has_member(o, "colorspace")) r = _set_one(m, &bp, "colorspace", json_object_get_member(o, "colorspace"));
  GList *members = json_object_get_members(o);
  for(GList *l = members; l && !r; l = g_list_next(l))
    if(strcmp(l->data, "colorspace")) r = _set_one(m, &bp, l->data, json_object_get_member(o, l->data));
  g_list_free(members);
  json_node_unref(root);
  if(r) return r;
  // as param_set: merged with the following parameter or blend changes of
  // this instance until history_seal, and the module switched on
  dtb__change_begin(DTB_CHANGE_PARAMS, m);
  *m->blend_params = bp;
  dtb__history_add_params(m, TRUE);
  dtb__change_end(DTB_CHANGE_PARAMS, m);
  return DTB_OK;
}

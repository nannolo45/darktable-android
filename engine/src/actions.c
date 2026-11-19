// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: module actions (Engine API v1, section 2.4,
 * module_action). What a module's desktop panel computes itself, done
 * without the panel. Every action names the desktop function it follows;
 * those are static functions of the module files, so their logic is
 * re-implemented here and must be compared with upstream at every version
 * bump (listed in docs/experiments/P1a-engine-core.md).
 *
 * Parameters are reached through introspection by name, never through a
 * copy of a module's private params struct.
 */
#include "dtb_internal.h"

#include "common/colorspaces.h"
#include "common/file_location.h"
#include "common/image.h"
#include "common/image_cache.h"
#include "common/noiseprofiles.h"
#include "common/wb_presets.h"
#include "control/conf.h"

#include <lcms2.h>
#include <lensfun.h>
#include <math.h>
#include <string.h>

// spectral tables of the white balance module (read, not changed)
#include "external/cie_colorimetric_tables.c"

/* ------------------------------------------------------------------ */
/* helpers                                                            */

static int _need_writable(void)
{
  if(dtb_S.read_only)
    return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  return DTB_OK;
}

static gboolean _arg_str(JsonObject *a, const char *k, const char **out)
{
  if(!json_object_has_member(a, k)) return FALSE;
  JsonNode *n = json_object_get_member(a, k);
  if(JSON_NODE_TYPE(n) != JSON_NODE_VALUE || json_node_get_value_type(n) != G_TYPE_STRING) return FALSE;
  *out = json_node_get_string(n);
  return TRUE;
}

/* a number argument: 1 found, 0 missing, -1 not a number */
static int _arg_num(JsonObject *a, const char *k, double *out)
{
  if(!json_object_has_member(a, k)) return 0;
  JsonNode *n = json_object_get_member(a, k);
  if(JSON_NODE_TYPE(n) != JSON_NODE_VALUE) return -1;
  const GType t = json_node_get_value_type(n);
  if(t == G_TYPE_INT64) *out = (double)json_node_get_int(n);
  else if(t == G_TYPE_DOUBLE) *out = json_node_get_double(n);
  else return -1;
  return 1;
}

/* the image as loaded in full (sensor layout, colour matrices, sizes): a
 * copy, as develop.c keeps one in dev->image_storage, so that no cache lock
 * is held while the action records history (which takes the write lock) */
static dt_image_t g_img;
static const dt_image_t *_image_get(void)
{
  if(dtb__full_pipe()) return NULL;
  const dt_image_t *c = dt_image_cache_get(dtb_S.imgid, 'r');
  if(!c) return NULL;
  g_img = *c;
  dt_image_cache_read_release(c);
  return &g_img;
}

static void _commit(dt_iop_module_t *m, const void *params)
{
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  memcpy(m->params, params, m->params_size);
  dtb__history_add(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
}

/* ================================================================== */
/* temperature (white balance), iop/temperature.c                     */

// constants of iop/temperature.c
#define WB_INITIALBLACKBODYTEMPERATURE 4000
#define WB_LOWEST_TEMPERATURE 1901
#define WB_HIGHEST_TEMPERATURE 25000
#define WB_LOWEST_TINT 0.135
#define WB_HIGHEST_TINT 2.326
#define WB_NUM_STD_PRESETS 4
#define WB_UNKNOWN -1
#define WB_AS_SHOT 0
#define WB_SPOT 1
#define WB_USER 2
#define WB_D65 3

typedef double (*_spd_f)(unsigned long int wavelength, double TempK);

/* _spd_blackbody() */
static double _spd_blackbody(unsigned long int wavelength, double TempK)
{
  const long double lambda = (double)wavelength * 1e-9;
#define c1 3.7417715246641281639549488324352159753e-16L
#define c2 0.014387769599838156481252937624049081933L
  return (double)(c1 / (powl(lambda, 5) * (expl(c2 / (lambda * TempK)) - 1.0L)));
#undef c2
#undef c1
}

/* _spd_daylight() */
static double _spd_daylight(unsigned long int wavelength, double TempK)
{
  cmsCIExyY WhitePoint = { D65xyY.x, D65xyY.y, 1.0 };
  cmsWhitePointFromTemp(&WhitePoint, TempK);
  const double M = (0.0241 + 0.2562 * WhitePoint.x - 0.7341 * WhitePoint.y),
               m1 = (-1.3515 - 1.7703 * WhitePoint.x + 5.9114 * WhitePoint.y) / M,
               m2 = (0.0300 - 31.4424 * WhitePoint.x + 30.0717 * WhitePoint.y) / M;
  const unsigned long int j
      = ((wavelength - cie_daylight_components[0].wavelength)
         / (cie_daylight_components[1].wavelength - cie_daylight_components[0].wavelength));
  return (cie_daylight_components[j].S[0] + m1 * cie_daylight_components[j].S[1]
          + m2 * cie_daylight_components[j].S[2]);
}

/* _spectrum_to_XYZ() */
static cmsCIEXYZ _spectrum_to_XYZ(double TempK, _spd_f I)
{
  cmsCIEXYZ Source = { .X = 0.0, .Y = 0.0, .Z = 0.0 };
  for(size_t i = 0; i < cie_1931_std_colorimetric_observer_count; i++)
  {
    const unsigned long int lambda =
      cie_1931_std_colorimetric_observer[0].wavelength
      + (cie_1931_std_colorimetric_observer[1].wavelength
         - cie_1931_std_colorimetric_observer[0].wavelength) * i;
    const double P = I(lambda, TempK);
    Source.X += P * cie_1931_std_colorimetric_observer[i].xyz.X;
    Source.Y += P * cie_1931_std_colorimetric_observer[i].xyz.Y;
    Source.Z += P * cie_1931_std_colorimetric_observer[i].xyz.Z;
  }
  const double _max = fmax(fmax(Source.X, Source.Y), Source.Z);
  Source.X /= _max;
  Source.Y /= _max;
  Source.Z /= _max;
  return Source;
}

/* _temperature_to_XYZ() */
static cmsCIEXYZ _temperature_to_XYZ(double TempK)
{
  if(TempK < WB_LOWEST_TEMPERATURE) TempK = WB_LOWEST_TEMPERATURE;
  if(TempK > WB_HIGHEST_TEMPERATURE) TempK = WB_HIGHEST_TEMPERATURE;
  if(TempK < WB_INITIALBLACKBODYTEMPERATURE)
    return _spectrum_to_XYZ(TempK, _spd_blackbody);
  else
    return _spectrum_to_XYZ(TempK, _spd_daylight);
}

/* _XYZ_to_temperature() */
static void _XYZ_to_temperature(cmsCIEXYZ XYZ, float *TempK, float *tint)
{
  double maxtemp = WB_HIGHEST_TEMPERATURE, mintemp = WB_LOWEST_TEMPERATURE;
  cmsCIEXYZ _xyz = { 0 };
  for(*TempK = (maxtemp + mintemp) / 2.0; (maxtemp - mintemp) > 1.0; *TempK = (maxtemp + mintemp) / 2.0)
  {
    _xyz = _temperature_to_XYZ(*TempK);
    if(_xyz.Z / _xyz.X > XYZ.Z / XYZ.X)
      maxtemp = *TempK;
    else
      mintemp = *TempK;
  }
  *tint = (_xyz.Y / _xyz.X) / (XYZ.Y / XYZ.X);
  if(*TempK < WB_LOWEST_TEMPERATURE) *TempK = WB_LOWEST_TEMPERATURE;
  if(*TempK > WB_HIGHEST_TEMPERATURE) *TempK = WB_HIGHEST_TEMPERATURE;
  if(*tint < WB_LOWEST_TINT) *tint = WB_LOWEST_TINT;
  if(*tint > WB_HIGHEST_TINT) *tint = WB_HIGHEST_TINT;
}

typedef struct _wb_matrices_t
{
  double XYZ_to_CAM[4][3], CAM_to_XYZ[3][4];
} _wb_matrices_t;

/* _prepare_matrices(): the module keeps these in its GUI data */
static int _wb_prepare_matrices(const dt_image_t *img, _wb_matrices_t *mx)
{
  // sRGB D65
  const double RGB_to_XYZ[3][4] = { { 0.4124564, 0.3575761, 0.1804375, 0 },
                                    { 0.2126729, 0.7151522, 0.0721750, 0 },
                                    { 0.0193339, 0.1191920, 0.9503041, 0 } };
  const double XYZ_to_RGB[4][3] = { { 3.2404542, -1.5371385, -0.4985314 },
                                    { -0.9692660, 1.8760108, 0.0415560 },
                                    { 0.0556434, -0.2040259, 1.0572252 },
                                    { 0, 0, 0 } };
  if(!dt_image_is_raw(img))
  {
    // "let's just assume for now(TM) that if it is not raw, it is sRGB"
    memcpy(mx->XYZ_to_CAM, XYZ_to_RGB, sizeof(mx->XYZ_to_CAM));
    memcpy(mx->CAM_to_XYZ, RGB_to_XYZ, sizeof(mx->CAM_to_XYZ));
    return DTB_OK;
  }
  if(!dt_colorspaces_conversion_matrices_xyz(img->adobe_XYZ_to_CAM, (float *)img->d65_color_matrix,
                                             mx->XYZ_to_CAM, mx->CAM_to_XYZ))
    return dtb__err(DTB_E_ENGINE, "colour matrix not found for %s", img->camera_makermodel);
  return DTB_OK;
}

/* _temp2mul() */
static void _temp2mul(const _wb_matrices_t *mx, double TempK, double tint, double mul[4])
{
  cmsCIEXYZ xyz = _temperature_to_XYZ(TempK);
  xyz.Y /= tint;
  const double XYZ[3] = { xyz.X, xyz.Y, xyz.Z };
  for(int k = 0; k < 4; k++)
  {
    double cam = 0.0;
    for(int i = 0; i < 3; i++) cam += mx->XYZ_to_CAM[k][i] * XYZ[i];
    mul[k] = 1.0 / cam;
  }
}

/* _mul2temp() via _mul2xyz() */
static void _mul2temp(const _wb_matrices_t *mx, const double coeffs[4], float *TempK, float *tint)
{
  double CAM[4];
  for(int k = 0; k < 4; k++) CAM[k] = coeffs[k] > 0.0f ? 1.0 / coeffs[k] : 0.0f;
  double XYZ[3];
  for(int k = 0; k < 3; k++)
  {
    XYZ[k] = 0.0;
    for(int i = 0; i < 4; i++) XYZ[k] += mx->CAM_to_XYZ[k][i] * CAM[i];
  }
  _XYZ_to_temperature((cmsCIEXYZ){ XYZ[0], XYZ[1], XYZ[2] }, TempK, tint);
}

/* the temperature params, by name */
typedef struct _wb_params_t
{
  float *c[4];  // red, green, blue, various
  int *preset;
  int *late_correction;
} _wb_params_t;

static int _wb_params(dt_iop_module_t *m, _wb_params_t *w)
{
  static const char *names[4] = { "red", "green", "blue", "various" };
  for(int k = 0; k < 4; k++)
    if(!(w->c[k] = dtb__param_float(m, names[k])))
      return dtb__err(DTB_E_ENGINE, "temperature has no parameter '%s' (upstream changed?)", names[k]);
  w->preset = dtb__param_int(m, "preset");
  w->late_correction = dtb__param_int(m, "late_correction");
  if(!w->preset || !w->late_correction)
    return dtb__err(DTB_E_ENGINE, "temperature has no 'preset' or 'late_correction' (upstream changed?)");
  return DTB_OK;
}

/* one entry of the white balance module's preset list for this camera,
 * at the position _generate_preset_combo() gives it in the combobox */
typedef struct _wb_cam_preset_t
{
  const char *name;
  int pos;  // the value stored in params "preset"
  int min_ft_pos, max_ft_pos, no_ft_pos;
} _wb_cam_preset_t;

/* _generate_preset_combo(): positions 0..3 are the standard modes, 4 is the
 * camera's section header, camera presets follow */
static GArray *_wb_camera_presets(const dt_image_t *img)
{
  GArray *a = g_array_new(FALSE, TRUE, sizeof(_wb_cam_preset_t));
  if(dt_image_is_ldr(img)) return a;
  int pos = WB_NUM_STD_PRESETS, found = 0;
  const char *wb_name = NULL;
  for(int i = 0; i < dt_wb_presets_count(); i++)
  {
    if(found >= 50) break;
    const dt_wb_data *wbp = dt_wb_preset(i);
    if(strcmp(wbp->make, img->camera_maker) || strcmp(wbp->model, img->camera_model)) continue;
    if(!wb_name) pos++;  // the section header
    if(!wb_name || strcmp(wb_name, wbp->name))
    {
      _wb_cam_preset_t p = { .name = wbp->name, .pos = pos, .min_ft_pos = i, .max_ft_pos = i, .no_ft_pos = i };
      wb_name = wbp->name;
      if(wbp->tuning != 0)
      {
        int ft_pos = i;
        int last_ft = wbp->tuning;
        p.min_ft_pos = ft_pos++;
        while(ft_pos < dt_wb_presets_count() && strcmp(wb_name, dt_wb_preset(ft_pos)->name) == 0)
        {
          if(dt_wb_preset(ft_pos)->tuning == 0) p.no_ft_pos = ft_pos;
          if(dt_wb_preset(ft_pos)->tuning > last_ft)
          {
            p.max_ft_pos = ft_pos;
            last_ft = dt_wb_preset(ft_pos)->tuning;
          }
          ft_pos++;
        }
      }
      g_array_append_val(a, p);
      pos++;
      found++;
    }
  }
  return a;
}

/* the multipliers of camera preset p at `tune`, exact or interpolated
 * between two listed tunings (_preset_tune_callback, default branch) */
static gboolean _wb_preset_coeffs(const dt_image_t *img, const _wb_cam_preset_t *p, const int tune,
                                  double out[4])
{
  const char *name = dt_wb_preset(p->no_ft_pos)->name;
  for(int i = p->min_ft_pos; i < p->max_ft_pos + 1
      && !strcmp(dt_wb_preset(i)->make, img->camera_maker)
      && !strcmp(dt_wb_preset(i)->model, img->camera_model)
      && !strcmp(dt_wb_preset(i)->name, name); i++)
    if(dt_wb_preset(i)->tuning == tune)
    {
      memcpy(out, dt_wb_preset(i)->channels, sizeof(double) * 4);
      return TRUE;
    }
  for(int i = p->min_ft_pos + 1; i < p->max_ft_pos + 1
      && !strcmp(dt_wb_preset(i)->make, img->camera_maker)
      && !strcmp(dt_wb_preset(i)->model, img->camera_model)
      && !strcmp(dt_wb_preset(i)->name, name); i++)
    if(dt_wb_preset(i - 1)->tuning < tune && dt_wb_preset(i)->tuning > tune)
    {
      dt_wb_data interpolated = { .tuning = tune };
      dt_wb_preset_interpolate(dt_wb_preset(i - 1), dt_wb_preset(i), &interpolated);
      memcpy(out, interpolated.channels, sizeof(double) * 4);
      return TRUE;
    }
  return FALSE;
}

/* _params_mode() */
static int _wb_mode(const float coeffs[4], const int preset, const dt_dev_chroma_t *chr)
{
  if(preset != WB_UNKNOWN) return preset;
  if(dt_dev_equal_chroma(coeffs, chr->as_shot)) return WB_AS_SHOT;
  if(dt_dev_equal_chroma(coeffs, chr->wb.D65coeffs)) return WB_D65;
  return WB_UNKNOWN;
}

/* _update_preset(): the late correction flag when leaving camera reference.
 * Desktop sets it to dt_dev_cat_is_active(), which asks color calibration's
 * desktop panel (dev->proxy.cat_source, set only by its gui_init) and is
 * therefore always FALSE without the interface. The bridge cannot know it
 * and leaves the flag as it was; reported in P1a-engine-core.md. */
static void _wb_update_preset(_wb_params_t *w, const int mode)
{
  *w->preset = mode;
}

static const char *_wb_mode_name(const int mode)
{
  switch(mode)
  {
    case WB_AS_SHOT: return "as shot";
    case WB_SPOT: return "from image area";
    case WB_USER: return "user modified";
    case WB_D65: return "camera reference";
    default: return NULL;
  }
}

static int _wb_check(dt_iop_module_t *m, const dt_image_t *img)
{
  if(m->hide_enable_button)
    return dtb__err(DTB_E_ARG, "white balance is not used for this image (monochrome)");
  (void)img;
  return DTB_OK;
}

static int _act_temperature(dt_iop_module_t *m, const char *action, JsonObject *a, char **res)
{
  const gboolean mutating = !strcmp(action, "set_wb") || !strcmp(action, "apply_preset");
  if(strcmp(action, "get_wb") && strcmp(action, "list_presets") && !mutating)
    return dtb__err(DTB_E_NOT_FOUND, "temperature has no action '%s'", action);
  if(mutating && _need_writable()) return DTB_E_READ_ONLY;
  const dt_image_t *img = _image_get();
  if(!img) return dtb__err(DTB_E_ENGINE, "image could not be loaded");
  int r = _wb_check(m, img);
  _wb_params_t w;
  if(!r) r = _wb_params(m, &w);
  _wb_matrices_t mx;
  if(!r) r = _wb_prepare_matrices(img, &mx);
  GArray *cams = r ? NULL : _wb_camera_presets(img);
  const dt_dev_chroma_t *chr = &dtb_S.dev.chroma;

  if(!r && !strcmp(action, "get_wb"))
  {
    // as gui_update(): the mode the params apply, "as shot" and "camera
    // reference" with the image's own multipliers
    float coeffs[4];
    for(int k = 0; k < 4; k++) coeffs[k] = *w.c[k];
    const int mode = _wb_mode(coeffs, *w.preset, chr);
    const double *own = mode == WB_AS_SHOT ? chr->as_shot : mode == WB_D65 ? chr->wb.D65coeffs : NULL;
    double dc[4];
    for(int k = 0; k < 4; k++) dc[k] = own ? own[k] : coeffs[k];
    float T = 0.0f, tint = 0.0f;
    _mul2temp(&mx, dc, &T, &tint);
    const char *name = _wb_mode_name(mode);
    if(!name)
      for(guint i = 0; i < cams->len && !name; i++)
        if(g_array_index(cams, _wb_cam_preset_t, i).pos == mode)
          name = g_array_index(cams, _wb_cam_preset_t, i).name;
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "temperature");
    json_builder_add_double_value(b, T);
    json_builder_set_member_name(b, "tint");
    json_builder_add_double_value(b, tint);
    json_builder_set_member_name(b, "preset");
    if(name) json_builder_add_string_value(b, name);
    else json_builder_add_null_value(b);
    json_builder_set_member_name(b, "coefficients");
    json_builder_begin_array(b);
    for(int k = 0; k < 4; k++)
      if(isfinite(dc[k])) json_builder_add_double_value(b, dc[k]);
      else json_builder_add_null_value(b);
    json_builder_end_array(b);
    json_builder_end_object(b);
    *res = dtb__json_finish(b);
  }
  else if(!r && !strcmp(action, "list_presets"))
  {
    // the combobox entries that can be chosen without the interface:
    // "from image area" needs the colour picker, "user modified" the
    // panel's memory of the last slider values
    JsonBuilder *b = json_builder_new();
    json_builder_begin_array(b);
    const int modes[2] = { WB_AS_SHOT, WB_D65 };
    for(int i = 0; i < 2; i++)
    {
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "name");
      json_builder_add_string_value(b, _wb_mode_name(modes[i]));
      json_builder_set_member_name(b, "kind");
      json_builder_add_string_value(b, "mode");
      json_builder_end_object(b);
    }
    for(guint i = 0; i < cams->len; i++)
    {
      const _wb_cam_preset_t *p = &g_array_index(cams, _wb_cam_preset_t, i);
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "name");
      json_builder_add_string_value(b, p->name);
      json_builder_set_member_name(b, "kind");
      json_builder_add_string_value(b, "camera");
      json_builder_set_member_name(b, "tuning_min");
      json_builder_add_int_value(b, dt_wb_preset(p->min_ft_pos)->tuning);
      json_builder_set_member_name(b, "tuning_max");
      json_builder_add_int_value(b, dt_wb_preset(p->max_ft_pos)->tuning);
      json_builder_set_member_name(b, "tuning_default");
      json_builder_add_int_value(b, dt_wb_preset(p->no_ft_pos)->tuning);
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
    *res = dtb__json_finish(b);
  }
  else if(!r && !strcmp(action, "set_wb"))
  {
    // _temp_tint_callback(): multipliers from temperature and tint,
    // normalised to green, mode "user modified"
    double T = 0.0, tint = 0.0;
    if(_arg_num(a, "temperature", &T) != 1 || _arg_num(a, "tint", &tint) != 1)
      r = dtb__err(DTB_E_TYPE, "set_wb takes numbers {temperature, tint}");
    else if(T < WB_LOWEST_TEMPERATURE || T > WB_HIGHEST_TEMPERATURE || tint < WB_LOWEST_TINT
            || tint > WB_HIGHEST_TINT)
      r = dtb__err(DTB_E_RANGE, "temperature %g outside [%d, %d] or tint %g outside [%g, %g]", T,
                   WB_LOWEST_TEMPERATURE, WB_HIGHEST_TEMPERATURE, tint, WB_LOWEST_TINT, WB_HIGHEST_TINT);
    else
    {
      double mul[4];
      _temp2mul(&mx, T, tint, mul);
      mul[0] /= mul[1];
      mul[2] /= mul[1];
      mul[3] /= mul[1];
      mul[1] = 1.0;
      void *tmp = dtb__memdup(m->params, m->params_size);
      void *orig = m->params;
      m->params = tmp;  // fill the copy through the same field pointers
      _wb_params_t wt;
      _wb_params(m, &wt);
      _wb_update_preset(&wt, WB_USER);
      for(int k = 0; k < 4; k++) *wt.c[k] = (float)mul[k];
      m->params = orig;
      _commit(m, tmp);
      g_free(tmp);
      *res = g_strdup("{}");
    }
  }
  else if(!r && !strcmp(action, "apply_preset"))
  {
    const char *name = NULL;
    double tune_d = 0.0;
    const int has_tune = _arg_num(a, "tuning", &tune_d);
    if(!_arg_str(a, "name", &name)) r = dtb__err(DTB_E_TYPE, "apply_preset takes {name, tuning?}");
    else if(has_tune < 0 || (has_tune && tune_d != floor(tune_d)))
      r = dtb__err(DTB_E_TYPE, "tuning must be an integer");
    double coeffs[4] = { 0 };
    int mode = WB_UNKNOWN;
    if(!r && (!strcmp(name, "as shot") || !strcmp(name, "camera reference")))
    {
      // _preset_tune_callback(), cases AS_SHOT and D65
      if(has_tune) r = dtb__err(DTB_E_ARG, "'%s' has no fine tuning", name);
      mode = !strcmp(name, "as shot") ? WB_AS_SHOT : WB_D65;
      memcpy(coeffs, mode == WB_AS_SHOT ? chr->as_shot : chr->wb.D65coeffs, sizeof(coeffs));
    }
    else if(!r)
    {
      const _wb_cam_preset_t *p = NULL;
      for(guint i = 0; i < cams->len && !p; i++)
        if(!strcmp(g_array_index(cams, _wb_cam_preset_t, i).name, name))
          p = &g_array_index(cams, _wb_cam_preset_t, i);
      if(!p)
        r = dtb__err(DTB_E_NOT_FOUND, "no white balance preset '%s' for %s %s", name,
                     img->camera_maker, img->camera_model);
      else
      {
        const int tune = has_tune ? (int)tune_d : dt_wb_preset(p->no_ft_pos)->tuning;
        if(tune < dt_wb_preset(p->min_ft_pos)->tuning || tune > dt_wb_preset(p->max_ft_pos)->tuning)
          r = dtb__err(DTB_E_RANGE, "tuning %d outside [%d, %d]", tune,
                       dt_wb_preset(p->min_ft_pos)->tuning, dt_wb_preset(p->max_ft_pos)->tuning);
        else if(!_wb_preset_coeffs(img, p, tune, coeffs))
          r = dtb__err(DTB_E_RANGE, "no multipliers for tuning %d", tune);
        mode = p->pos;
      }
    }
    if(!r)
    {
      void *tmp = dtb__memdup(m->params, m->params_size);
      void *orig = m->params;
      m->params = tmp;
      _wb_params_t wt;
      _wb_params(m, &wt);
      _wb_update_preset(&wt, mode);
      for(int k = 0; k < 4; k++) *wt.c[k] = (float)coeffs[k];
      m->params = orig;
      _commit(m, tmp);
      g_free(tmp);
      *res = g_strdup("{}");
    }
  }
  if(cams) g_array_free(cams, TRUE);
  return r;
}

/* ================================================================== */
/* lens, iop/lens.cc (Lensfun method)                                 */

#if defined(LF_VERSION) && LF_VERSION >= ((0 << 24) | (3 << 16) | (95 << 8) | 0)
#error "lens actions are written for Lensfun < 0.3.95 (the API darktable uses without LF_0395)"
#endif

static lfDatabase *g_lfdb = NULL;

/* init_global() of iop/lens.cc, Lensfun before 0.3.95: the default
 * locations, else <datadir>/../lensfun/version_N, else <datadir>/../lensfun.
 * The module's own database is in its private global data, so the bridge
 * loads the same files into one of its own. */
static lfDatabase *_lf_db(void)
{
  if(g_lfdb) return g_lfdb;
  lfDatabase *db = lf_db_new();
  if(!db) return NULL;
  if(lf_db_load(db) != LF_NO_ERROR)
  {
    char datadir[PATH_MAX] = { 0 };
    dt_loc_get_datadir(datadir, sizeof(datadir));
    gchar *path = g_path_get_dirname(datadir);
    gboolean ok = FALSE;
#ifdef LF_MAX_DATABASE_VERSION
    gchar *sysdbpath = g_strdup_printf("%s/lensfun/version_%d", path, LF_MAX_DATABASE_VERSION);
    g_free(db->HomeDataDir);
    db->HomeDataDir = sysdbpath;
    ok = lf_db_load(db) == LF_NO_ERROR;
#endif
    if(!ok)
    {
      g_free(db->HomeDataDir);
      db->HomeDataDir = g_build_filename(path, "lensfun", NULL);
      lf_db_load(db);
    }
    g_free(path);
  }
  g_lfdb = db;
  return db;
}

static const char *_ml(const lfMLstr s) { return s ? lf_mlstr_get(s) : NULL; }

static void _add_str_or_null(JsonBuilder *b, const char *k, const char *v)
{
  json_builder_set_member_name(b, k);
  if(v)
  {
    gchar *s = g_utf8_make_valid(v, -1);
    json_builder_add_string_value(b, s);
    g_free(s);
  }
  else
    json_builder_add_null_value(b);
}

/* a camera as the module stores it: params "camera" is the Model string,
 * found again with FindCamerasExt(NULL, model) */
static const lfCamera *_lf_camera(lfDatabase *db, const char *model)
{
  if(!model || !*model) return NULL;
  const lfCamera **cams = lf_db_find_cameras_ext(db, NULL, model, 0);
  if(!cams) return NULL;
  const lfCamera *c = NULL;
  for(int i = 0; cams[i] && !c; i++)
    if(!strcmp(cams[i]->Model, model)) c = cams[i];
  if(!c) c = cams[0];
  lf_free(cams);
  return c;
}

/* the value of an enum constant of the module, by name */
static int _enum_value(dt_iop_module_t *m, const char *path, const char *constant, int *v)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, m->params, path, &r) || r.f->header.type != DT_INTROSPECTION_TYPE_ENUM
     || !dt_introspection_get_enum_value(r.f, constant, v))
    return dtb__err(DTB_E_ENGINE, "lens: no %s constant %s (upstream changed?)", path, constant);
  return DTB_OK;
}

/* _lenstype_to_lensfun_lenstype(), by constant name */
static lfLensType _lf_lenstype(dt_iop_module_t *m, const int target_geom)
{
  static const struct { const char *name; lfLensType t; } map[] = {
    { "DT_IOP_LENS_LENSTYPE_RECTILINEAR", LF_RECTILINEAR },
    { "DT_IOP_LENS_LENSTYPE_FISHEYE", LF_FISHEYE },
    { "DT_IOP_LENS_LENSTYPE_PANORAMIC", LF_PANORAMIC },
    { "DT_IOP_LENS_LENSTYPE_EQUIRECTANGULAR", LF_EQUIRECTANGULAR },
    { "DT_IOP_LENS_LENSTYPE_FISHEYE_ORTHOGRAPHIC", LF_FISHEYE_ORTHOGRAPHIC },
    { "DT_IOP_LENS_LENSTYPE_FISHEYE_STEREOGRAPHIC", LF_FISHEYE_STEREOGRAPHIC },
    { "DT_IOP_LENS_LENSTYPE_FISHEYE_EQUISOLID", LF_FISHEYE_EQUISOLID },
    { "DT_IOP_LENS_LENSTYPE_FISHEYE_THOBY", LF_FISHEYE_THOBY },
  };
  for(size_t i = 0; i < G_N_ELEMENTS(map); i++)
  {
    int v = 0;
    if(!_enum_value(m, "target_geom", map[i].name, &v) && v == target_geom) return map[i].t;
  }
  return LF_UNKNOWN;
}

/* _get_autoscale_lf() with _get_modifier() (Lensfun before 0.3.95), on the
 * params `p` of module m (a copy, reached through m->params) */
static float _lf_autoscale(dt_iop_module_t *m, lfDatabase *db, const lfCamera *camera, const dt_image_t *img)
{
  float scale = 1.0f;
  const char *lens = dtb__param_chars(m, "lens", NULL);
  if(!lens || !lens[0]) return scale;
  const lfLens **lenslist = lf_db_find_lenses_hd(db, camera, NULL, lens, 0);
  if(!lenslist) return scale;
  int flags = 0, dist = 0, tca = 0, vign = 0;
  const int *mf = dtb__param_int(m, "modify_flags");
  const int *inverse = dtb__param_int(m, "inverse");
  const int *target = dtb__param_int(m, "target_geom");
  const float *crop = dtb__param_float(m, "crop");
  const float *focal = dtb__param_float(m, "focal");
  const float *aperture = dtb__param_float(m, "aperture");
  const float *distance = dtb__param_float(m, "distance");
  if(mf && inverse && target && crop && focal && aperture && distance
     && !_enum_value(m, "modify_flags", "DT_IOP_LENS_MODFLAG_DIST", &dist)
     && !_enum_value(m, "modify_flags", "DT_IOP_LENS_MODFLAG_TCA", &tca)
     && !_enum_value(m, "modify_flags", "DT_IOP_LENS_MODFLAG_VIGN", &vign))
  {
    // _modflags_to_lensfun_mods()
    flags = LF_MODIFY_GEOMETRY | LF_MODIFY_SCALE;
    flags |= (*mf & dist) ? LF_MODIFY_DISTORTION : 0;
    flags |= (*mf & vign) ? LF_MODIFY_VIGNETTING : 0;
    flags |= (*mf & tca) ? LF_MODIFY_TCA : 0;
    flags &= LF_MODIFY_ALL;
    lfModifier *mod = lf_modifier_new(lenslist[0], *crop, img->p_width, img->p_height);
    lf_modifier_initialize(mod, lenslist[0], LF_PF_F32, *focal, *aperture, *distance, 1.0f,
                           _lf_lenstype(m, *target), flags, *inverse);
    scale = lf_modifier_get_auto_scale(mod, *inverse);
    lf_modifier_destroy(mod);
  }
  lf_free(lenslist);
  return scale;
}

static int _act_lens(dt_iop_module_t *m, const char *action, JsonObject *a, char **res)
{
  const gboolean mutating = !strcmp(action, "set_lens");
  if(strcmp(action, "list_cameras") && strcmp(action, "list_lenses") && !mutating)
    return dtb__err(DTB_E_NOT_FOUND, "lens has no action '%s'", action);
  if(mutating && _need_writable()) return DTB_E_READ_ONLY;
  lfDatabase *db = _lf_db();
  if(!db) return dtb__err(DTB_E_ENGINE, "Lensfun database not available");
  int r = DTB_OK;
  // the full image first: loading it runs the pipe, whose lens module takes
  // the same lock
  const dt_image_t *img = mutating ? _image_get() : NULL;
  if(mutating && !img) return dtb__err(DTB_E_ENGINE, "image could not be loaded");
  dt_pthread_mutex_lock(&darktable.plugin_threadsafe);  // as the module guards Lensfun

  if(!strcmp(action, "list_cameras"))
  {
    // _camera_menusearch_clicked(): GetCameras()
    const lfCamera *const *cams = lf_db_get_cameras(db);
    JsonBuilder *b = json_builder_new();
    json_builder_begin_array(b);
    for(int i = 0; cams && cams[i]; i++)
    {
      json_builder_begin_object(b);
      _add_str_or_null(b, "maker", _ml(cams[i]->Maker));
      _add_str_or_null(b, "model", cams[i]->Model);  // the value set_lens and params "camera" take
      _add_str_or_null(b, "variant", _ml(cams[i]->Variant));
      _add_str_or_null(b, "mount", cams[i]->Mount);
      json_builder_set_member_name(b, "crop_factor");
      json_builder_add_double_value(b, cams[i]->CropFactor);
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
    *res = dtb__json_finish(b);
  }
  else
  {
    const char *camera = NULL, *lens = NULL;
    if(!_arg_str(a, "camera", &camera)) r = dtb__err(DTB_E_TYPE, "%s takes {camera%s}", action, mutating ? ", lens" : "");
    else if(mutating && !_arg_str(a, "lens", &lens)) r = dtb__err(DTB_E_TYPE, "set_lens takes {camera, lens}");
    const lfCamera *cam = r ? NULL : _lf_camera(db, camera);
    if(!r && !cam) r = dtb__err(DTB_E_NOT_FOUND, "camera '%s' is not in the Lensfun database", camera);
    if(!r && !mutating)
    {
      // _lens_menusearch_clicked(): FindLenses(camera, NULL, NULL, SORT_AND_UNIQUIFY)
      const lfLens **lenses = lf_db_find_lenses_hd(db, cam, NULL, NULL, LF_SEARCH_SORT_AND_UNIQUIFY);
      JsonBuilder *b = json_builder_new();
      json_builder_begin_array(b);
      for(int i = 0; lenses && lenses[i]; i++)
      {
        json_builder_begin_object(b);
        _add_str_or_null(b, "maker", _ml(lenses[i]->Maker));
        _add_str_or_null(b, "model", lenses[i]->Model);  // the value set_lens takes
        json_builder_set_member_name(b, "min_focal");
        json_builder_add_double_value(b, lenses[i]->MinFocal);
        json_builder_set_member_name(b, "max_focal");
        json_builder_add_double_value(b, lenses[i]->MaxFocal);
        json_builder_end_object(b);
      }
      json_builder_end_array(b);
      if(lenses) lf_free(lenses);
      *res = dtb__json_finish(b);
    }
    else if(!r)
    {
      const lfLens **lenses = lf_db_find_lenses_hd(db, cam, NULL, lens, 0);
      const lfLens *l = NULL;
      for(int i = 0; lenses && lenses[i] && !l; i++)
        if(!strcmp(lenses[i]->Model, lens)) l = lenses[i];
      if(!l && lenses) l = lenses[0];
      if(!l)
        r = dtb__err(DTB_E_NOT_FOUND, "lens '%s' not found for camera '%s'", lens, camera);
      else
      {
        void *tmp = dtb__memdup(m->params, m->params_size);
        void *orig = m->params;
        m->params = tmp;
        size_t ncam = 0, nlens = 0;
        char *pcam = dtb__param_chars(m, "camera", &ncam);
        char *plens = dtb__param_chars(m, "lens", &nlens);
        float *crop = dtb__param_float(m, "crop");
        float *focal = dtb__param_float(m, "focal");
        float *scale = dtb__param_float(m, "scale");
        int *has_been_set = dtb__param_int(m, "has_been_set");
        if(!pcam || !plens || !crop || !focal || !scale || !has_been_set)
          r = dtb__err(DTB_E_ENGINE, "lens parameters not found (upstream changed?)");
        else
        {
          // _camera_set(): model and crop factor
          memset(pcam, 0, ncam);
          g_strlcpy(pcam, cam->Model, ncam);
          *crop = cam->CropFactor;
          // _lens_set(): model; a prime lens fixes the focal length
          memset(plens, 0, nlens);
          g_strlcpy(plens, l->Model, nlens);
          if(l->MinFocal >= l->MaxFocal) *focal = l->MinFocal;
          // _lens_menu_select(): has_been_set and the automatic scale
          *has_been_set = TRUE;
          *scale = _lf_autoscale(m, db, cam, img);
        }
        m->params = orig;
        if(!r)
        {
          dt_pthread_mutex_unlock(&darktable.plugin_threadsafe);
          _commit(m, tmp);
          dt_pthread_mutex_lock(&darktable.plugin_threadsafe);
          *res = g_strdup("{}");
        }
        g_free(tmp);
      }
      if(lenses) lf_free(lenses);
    }
  }
  dt_pthread_mutex_unlock(&darktable.plugin_threadsafe);
  (void)img;
  return r;
}

/* ================================================================== */
/* lut3d, iop/lut3d.c                                                 */

/* _button_clicked() and _filepath_callback() without G'MIC: the file must
 * lie inside the LUT root folder (configuration key
 * plugins/darkroom/lut3d/def_path); params "filepath" holds the path
 * relative to it, with '/' separators */
static int _act_lut3d(dt_iop_module_t *m, const char *action, JsonObject *a, char **res)
{
  if(strcmp(action, "set_file")) return dtb__err(DTB_E_NOT_FOUND, "lut3d has no action '%s'", action);
  if(_need_writable()) return DTB_E_READ_ONLY;
  const char *path = NULL;
  if(!_arg_str(a, "path", &path)) return dtb__err(DTB_E_TYPE, "set_file takes {path}");
  gchar *root = dt_conf_get_string("plugins/darkroom/lut3d/def_path");
  int r = DTB_OK;
  gchar *rel = NULL;
  if(!root || !root[0])
    r = dtb__err(DTB_E_ARG, "LUT root folder not defined (configuration plugins/darkroom/lut3d/def_path)");
  else if(g_path_is_absolute(path))
  {
    gchar *prefix = g_str_has_suffix(root, "/") ? g_strdup(root) : g_strconcat(root, "/", NULL);
    if(!g_str_has_prefix(path, prefix))
      r = dtb__err(DTB_E_ARG, "%s is outside the LUT root folder %s", path, root);
    else
      rel = g_strdup(path + strlen(prefix));
    g_free(prefix);
  }
  else
    rel = g_strdup(path);
  if(!r)
  {
    g_strdelimit(rel, "\\", '/');  // filepath_set_unix_separator()
    // _filepath_is_safe(): no ".." step out of the root
    gchar **parts = g_strsplit(rel, "/", -1);
    for(int i = 0; parts[i] && !r; i++)
      if(!strcmp(parts[i], "..")) r = dtb__err(DTB_E_ARG, "path must stay inside the LUT root folder");
    g_strfreev(parts);
  }
  if(!r)
  {
    gchar *lower = g_ascii_strdown(rel, -1);
    if(!g_str_has_suffix(lower, ".png") && !g_str_has_suffix(lower, ".cube") && !g_str_has_suffix(lower, ".3dl"))
      r = dtb__err(DTB_E_ARG, "not a hald CLUT (png) or 3D LUT (cube, 3dl): %s", rel);
    g_free(lower);
  }
  if(!r)
  {
    gchar *full = g_build_filename(root, rel, NULL);
    if(!g_file_test(full, G_FILE_TEST_IS_REGULAR)) r = dtb__err(DTB_E_IO, "cannot read %s", full);
    g_free(full);
  }
  if(!r)
  {
    void *tmp = dtb__memdup(m->params, m->params_size);
    void *orig = m->params;
    m->params = tmp;
    size_t n = 0;
    char *fp = dtb__param_chars(m, "filepath", &n);
    m->params = orig;
    if(!fp)
      r = dtb__err(DTB_E_ENGINE, "lut3d has no 'filepath' (upstream changed?)");
    else if(strlen(rel) >= n)
      r = dtb__err(DTB_E_RANGE, "path longer than %zu bytes", n - 1);
    else
    {
      memset(fp, 0, n);
      memcpy(fp, rel, strlen(rel));
      _commit(m, tmp);
      *res = g_strdup("{}");
    }
    g_free(tmp);
  }
  g_free(rel);
  g_free(root);
  return r;
}

/* ================================================================== */
/* denoiseprofile, iop/denoiseprofile.c                               */

/* _get_iso_highlight_preservation_shift() */
static int _dn_iso_shift(const dt_image_t *img)
{
  const int shift = floorf(img->exif_highlight_preservation);
  return shift <= 0 ? 0 : shift;
}

/* the name of the automatic entry, as dt_iop_denoiseprofile_get_auto_profile()
 * makes it (in English: the bridge does not translate) */
static gchar *_dn_auto_name(const dt_image_t *img, GList *profiles, const gboolean compensate)
{
  const int exif_iso = img->exif_iso;
  int iso = exif_iso, shift = 0;
  if(compensate)
  {
    shift = _dn_iso_shift(img);
    iso >>= shift;
  }
  const dt_noiseprofile_t *last = NULL;
  for(GList *it = profiles; it; it = g_list_next(it))
  {
    const dt_noiseprofile_t *cur = it->data;
    if(cur->iso == iso)
      return iso != exif_iso ? g_strdup_printf("found ISO %d (ISO %d %+d EV)", iso, exif_iso, -shift)
                             : g_strdup_printf("found ISO %d", iso);
    if(last && last->iso < iso && cur->iso > iso)
      return iso != exif_iso ? g_strdup_printf("interpolated ISO %d (ISO %d %+d EV)", iso, exif_iso, -shift)
                             : g_strdup_printf("interpolated ISO %d", iso);
    last = cur;
  }
  if(img->has_dng_noise_profile) return g_strdup("DNG noise profile");
  return g_strdup(dt_noiseprofile_generic.name);
}

/* the combobox list of reload_defaults(): the automatic entry, the
 * profiles for this camera, and the generic one when a DNG profile is the
 * automatic entry */
static GList *_dn_profiles(const dt_image_t *img)
{
  GList *profiles = dt_noiseprofile_get_matching(img);
  if(img->has_dng_noise_profile)
  {
    dt_noiseprofile_t *generic = malloc(sizeof(dt_noiseprofile_t));
    *generic = dt_noiseprofile_generic;
    generic->name = g_strdup(dt_noiseprofile_generic.name);
    generic->maker = g_strdup(dt_noiseprofile_generic.maker);
    generic->model = g_strdup(dt_noiseprofile_generic.model);
    profiles = g_list_append(profiles, generic);
  }
  return profiles;
}

static int _act_denoiseprofile(dt_iop_module_t *m, const char *action, JsonObject *a, char **res)
{
  const gboolean mutating = !strcmp(action, "set_profile");
  if(strcmp(action, "list_profiles") && !mutating)
    return dtb__err(DTB_E_NOT_FOUND, "denoiseprofile has no action '%s'", action);
  if(mutating && _need_writable()) return DTB_E_READ_ONLY;
  const dt_image_t *img = _image_get();
  if(!img) return dtb__err(DTB_E_ENGINE, "image could not be loaded");
  const int *compensate = dtb__param_int(m, "compensate_hilite_pres");
  int r = DTB_OK;
  if(!compensate)
  {
    return dtb__err(DTB_E_ENGINE, "denoiseprofile has no 'compensate_hilite_pres' (upstream changed?)");
  }
  GList *profiles = _dn_profiles(img);
  // the automatic entry's label follows the current switch (gui_changed)
  gchar *auto_name = _dn_auto_name(img, profiles, *compensate);

  if(!mutating)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_array(b);
    // "index": the entry's position in desktop's profile combobox (0 = the
    // automatic entry); upstream's list can hold two profiles of one name
    // (data/noiseprofiles.json), which only the position tells apart
    int index = 0;
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "index");
    json_builder_add_int_value(b, index++);
    json_builder_set_member_name(b, "name");
    json_builder_add_string_value(b, auto_name);
    json_builder_set_member_name(b, "auto");
    json_builder_add_boolean_value(b, TRUE);
    json_builder_set_member_name(b, "iso");
    json_builder_add_int_value(b, (int)img->exif_iso);
    json_builder_end_object(b);
    for(GList *it = profiles; it; it = g_list_next(it))
    {
      const dt_noiseprofile_t *p = it->data;
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "index");
      json_builder_add_int_value(b, index++);
      json_builder_set_member_name(b, "name");
      json_builder_add_string_value(b, p->name);
      json_builder_set_member_name(b, "auto");
      json_builder_add_boolean_value(b, FALSE);
      json_builder_set_member_name(b, "iso");
      json_builder_add_int_value(b, p->iso);
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
    *res = dtb__json_finish(b);
  }
  else
  {
    // profile_callback(): entry 0 takes the automatic profile that
    // reload_defaults() computed (its a[0] = -1 tells commit_params to
    // detect again), the others their own a and b
    const char *name = NULL;
    const float *pa = NULL, *pb = NULL;
    double idx_d = -1.0;
    const int has_index = _arg_num(a, "index", &idx_d);
    const int n_entries = 1 + g_list_length(profiles);
    if(has_index < 0 || (has_index == 1 && (idx_d != floor(idx_d))))
      r = dtb__err(DTB_E_TYPE, "set_profile: index must be an integer");
    else if(has_index == 1 && json_object_has_member(a, "name"))
      r = dtb__err(DTB_E_ARG, "set_profile takes {index} or {name}, not both");
    else if(has_index == 1 && (idx_d < 0 || idx_d >= n_entries))
      r = dtb__err(DTB_E_RANGE, "set_profile: index %.0f outside 0..%d", idx_d, n_entries - 1);
    else if(has_index == 1 && idx_d > 0)
    {
      const dt_noiseprofile_t *p = g_list_nth_data(profiles, (guint)idx_d - 1);
      pa = p->a;
      pb = p->b;
    }
    else if(has_index != 1 && !_arg_str(a, "name", &name))
      r = dtb__err(DTB_E_TYPE, "set_profile takes {index} or {name}");
    if(r || pa)
      ;
    else if(has_index == 1 || !strcmp(name, auto_name) || !strcmp(name, "auto"))
    {
      dtb_field_ref_t ra, rb;
      if(dtb__field_resolve(m, m->default_params, "a", &ra) || dtb__field_resolve(m, m->default_params, "b", &rb))
        r = dtb__err(DTB_E_ENGINE, "denoiseprofile has no 'a' or 'b' (upstream changed?)");
      else
      {
        pa = (const float *)ra.p;
        pb = (const float *)rb.p;
      }
    }
    else
    {
      // by name: the first entry of that name (use the index for the others)
      for(GList *it = profiles; it && !pa; it = g_list_next(it))
      {
        const dt_noiseprofile_t *p = it->data;
        if(!strcmp(p->name, name))
        {
          pa = p->a;
          pb = p->b;
        }
      }
      if(!pa) r = dtb__err(DTB_E_NOT_FOUND, "no noise profile '%s' for this image", name);
    }
    if(!r)
    {
      void *tmp = dtb__memdup(m->params, m->params_size);
      dtb_field_ref_t ra, rb;
      if(dtb__field_resolve(m, tmp, "a", &ra) || dtb__field_resolve(m, tmp, "b", &rb)
         || ra.f->header.type != DT_INTROSPECTION_TYPE_ARRAY || ra.f->Array.count != 3)
        r = dtb__err(DTB_E_ENGINE, "denoiseprofile 'a' or 'b' changed shape (upstream changed?)");
      else
      {
        for(int k = 0; k < 3; k++)
        {
          ((float *)ra.p)[k] = pa[k];
          ((float *)rb.p)[k] = pb[k];
        }
        _commit(m, tmp);
        *res = g_strdup("{}");
      }
      g_free(tmp);
    }
  }
  g_free(auto_name);
  g_list_free_full(profiles, dt_noiseprofile_free);
  return r;
}

/* ================================================================== */

int dtb__module_action(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(dt_iop_module_is(m, "temperature")) return _act_temperature(m, action, args, result);
  if(dt_iop_module_is(m, "lens")) return _act_lens(m, action, args, result);
  if(dt_iop_module_is(m, "lut3d")) return _act_lut3d(m, action, args, result);
  if(dt_iop_module_is(m, "denoiseprofile")) return _act_denoiseprofile(m, action, args, result);
  return dtb__err(DTB_E_NOT_FOUND, "%s has no actions", m->op);
}

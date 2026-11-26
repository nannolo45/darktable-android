// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: colour pickers and histogram (P1b part 2,
 * docs/design/engine-api.md 6.3 and 6.4).
 *
 * Desktop's module picker (pixelpipe_hb.c, _pixelpipe_picker) runs on the
 * darkroom's preview pipe, for the module that has the focus, and computes
 * the statistics of the area with dt_color_picker_helper()
 * (common/color_picker.c) on the module's input buffer (in the module's input
 * colour space, before process()) and on its output (after process(), before
 * blending). The area is given in the final picture; dt_color_picker_box()
 * maps it to the module's buffer through the distortions of the modules
 * before it. All of that is tied to the desktop's colour picker proxy
 * (darktable.lib), which does not exist without the interface. The bridge
 * does the same steps itself: the area's corners are mapped back to the
 * pipe's input (as desktop's dt_color_picker_backtransform_box stores a
 * sample), then forward to the module as dt_color_picker_box does, and the
 * statistics are taken with dt_color_picker_helper inside the module's
 * process_plain (the hook in times.c), for one render of the pipe at scale 1
 * in which the module is forced to run (its piece hash is changed for that
 * render, so no cache line is reused for it and the modules after it).
 */
#include "dtb_internal.h"

#include "common/color_picker.h"
#include "common/iop_profile.h"

#include <float.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* the module's picker colour space (its gui_init)                    */

/* Desktop's picker colour space: module->default_colorspace(module, NULL,
 * NULL) (gui/color_picker_proxy.c, _init_picker), unless the module's
 * gui_init fixes another with dt_color_picker_new_with_cst. */
/* brief P6a: a picker of a module whose pickers differ in colour space (colour equalizer's white level is
 * dt_color_picker_new, its hue shift dt_color_picker_new_with_cst): set for one dtb__pick_cst call */
static int g_forced_cst = -1;

static dt_iop_colorspace_type_t _picker_cst(dt_iop_module_t *m)
{
  if(g_forced_cst >= 0) return (dt_iop_colorspace_type_t)g_forced_cst;
  static const struct { const char *op; dt_iop_colorspace_type_t cst; } fixed[] = {
    { "temperature", IOP_CS_NONE },   // temperature.c gui_init
    { "colorzones", IOP_CS_LCH },     // colorzones.c gui_init
    { "colorequal", IOP_CS_JZCZHZ },  // colorequal.c gui_init (hue shift picker)
    { "satcurvergb", IOP_CS_RGB },    // satcurvergb.c gui_init
  };
  for(size_t k = 0; k < G_N_ELEMENTS(fixed); k++)
    if(dt_iop_module_is(m, fixed[k].op)) return fixed[k].cst;
  return m->default_colorspace(m, NULL, NULL);
}

static const char *_cst_name(const dt_iop_colorspace_type_t c)
{
  switch(c)
  {
    case IOP_CS_RAW: return "raw";
    case IOP_CS_LAB: return "lab";
    case IOP_CS_RGB: return "rgb";
    case IOP_CS_LCH: return "lch";
    case IOP_CS_HSL: return "hsl";
    case IOP_CS_JZCZHZ: return "jzczhz";
    default: return "none";
  }
}

/* the space dt_color_picker_helper() returns its numbers in */
static dt_iop_colorspace_type_t _result_cst(const dt_iop_buffer_dsc_t *dsc, const dt_iop_colorspace_type_t image_cst,
                                            const dt_iop_colorspace_type_t picker_cst)
{
  if(dsc->channels != 4u) return IOP_CS_RAW;
  const dt_iop_colorspace_type_t eff = image_cst == IOP_CS_RAW ? IOP_CS_RGB : image_cst;
  if(eff == IOP_CS_LAB && picker_cst == IOP_CS_LCH) return IOP_CS_LCH;
  if(eff == IOP_CS_RGB && (picker_cst == IOP_CS_HSL || picker_cst == IOP_CS_JZCZHZ)) return picker_cst;
  return image_cst;
}

/* ------------------------------------------------------------------ */
/* the pick during one render                                         */

typedef struct _side_t
{
  gboolean have;
  double sum[4];
  double n;
  float min[4], max[4];
  int box[4];   // in the module's buffer at the render's scale, whole (not per tile)
  dt_iop_colorspace_type_t cst;
} _side_t;

typedef struct _pick_t
{
  dt_iop_module_t *module;
  dt_dev_pixelpipe_t *pipe;
  dt_colorpicker_sample_t sample;  // as desktop's primary sample: box or point in the pipe's input, normalised
  _side_t side[2];                 // input, output
  dtb_input_fn fn;                 // brief P6a: called with the module's input instead of sampling
  void *user;
} _pick_t;

static _pick_t *g_pick = NULL;

/* the picture's area as pixels of an output buffer at scale 1 (op NULL):
 * sorted corners as dt_color_picker_box() leaves them */
static gboolean _box_in_roi(const float *fbox, const dt_iop_roi_t *roi, int *box)
{
  box[0] = 0.5f * (fbox[0] + fbox[2]) - roi->x;
  box[1] = 0.5f * (fbox[1] + fbox[3]) - roi->y;
  box[2] = 0.5f * (fbox[4] + fbox[6]) - roi->x;
  box[3] = 0.5f * (fbox[5] + fbox[7]) - roi->y;
  box[2] = MAX(box[2], box[0] + 1);
  box[3] = MAX(box[3], box[1] + 1);
  if(box[0] >= roi->width || box[1] >= roi->height || box[2] < 0 || box[3] < 0) return FALSE;
  box[0] = CLAMP(box[0], 0, roi->width - 1);
  box[1] = CLAMP(box[1], 0, roi->height - 1);
  box[2] = CLAMP(box[2], 1, roi->width);
  box[3] = CLAMP(box[3], 1, roi->height);
  return !((box[2] - box[0] < 1) || (box[3] - box[1] < 1));
}

/* Desktop's dt_color_picker_box() and dt_color_picker_backtransform_box()
 * read the global develop object's preview pipe (darktable.develop, which
 * dt_init creates also without the interface, but which has no pipe here):
 * for the duration of one call it is pointed at the bridge's pipe. */
static dt_dev_pixelpipe_t *_swap_preview_pipe(dt_dev_pixelpipe_t *pipe)
{
  dt_dev_pixelpipe_t *old = darktable.develop->preview_pipe;
  darktable.develop->preview_pipe = pipe;
  return old;
}

static void _sample(_side_t *s, const int *box, const dt_iop_buffer_dsc_t *dsc, const float *pixel,
                    const dt_iop_roi_t *roi, const dt_iop_colorspace_type_t image_cst,
                    const dt_iop_colorspace_type_t picker_cst, const dt_iop_order_iccprofile_info_t *profile,
                    const gboolean denoise)
{
  lib_colorpicker_stats pick;
  dt_color_picker_helper(dsc, pixel, roi, box, denoise, pick, image_cst, picker_cst, profile);
  const double n = (double)(box[2] - box[0]) * (box[3] - box[1]);
  if(!s->have)
  {
    for(int k = 0; k < 4; k++)
    {
      s->min[k] = FLT_MAX;
      s->max[k] = -FLT_MAX;
    }
    s->box[0] = box[0] + roi->x;
    s->box[1] = box[1] + roi->y;
    s->box[2] = box[2] + roi->x;
    s->box[3] = box[3] + roi->y;
  }
  else
  {
    // another tile of the same buffer
    s->box[0] = MIN(s->box[0], box[0] + roi->x);
    s->box[1] = MIN(s->box[1], box[1] + roi->y);
    s->box[2] = MAX(s->box[2], box[2] + roi->x);
    s->box[3] = MAX(s->box[3], box[3] + roi->y);
  }
  for(int k = 0; k < 4; k++)
  {
    s->sum[k] += (double)pick[DT_PICK_MEAN][k] * n;
    s->min[k] = MIN(s->min[k], pick[DT_PICK_MIN][k]);
    s->max[k] = MAX(s->max[k], pick[DT_PICK_MAX][k]);
  }
  s->n += n;
  s->have = TRUE;
  s->cst = _result_cst(dsc, image_cst, picker_cst);
}

/* called by times.c after each module's process_plain (every tile) */
void dtb__pick_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const i, void *const o,
                    const dt_iop_roi_t *const roi_in, const dt_iop_roi_t *const roi_out)
{
  _pick_t *p = g_pick;
  if(!p || self != p->module || piece->pipe != p->pipe) return;
  if(p->fn)
  {
    // brief P6a: the module's input as its process() gets it (a module's own computation on it, as desktop's
    // process() does on the preview pipe: rgb levels' auto levels)
    p->fn(self, piece, (const float *)i, roi_in, p->user);
    return;
  }
  dt_dev_pixelpipe_t *pipe = piece->pipe;
  const dt_iop_colorspace_type_t picker_cst = _picker_cst(self);
  const dt_iop_order_iccprofile_info_t *profile = dt_ioppr_get_pipe_current_profile_info(self, pipe);
  // input: the buffer as process() gets it, in the module's input colour
  // space (pixelpipe_hb.c passes piece->dsc_in and cst_tmp)
  const dt_iop_colorspace_type_t cst_in = self->input_colorspace(self, pipe, piece);
  const dt_iop_colorspace_type_t cst_out = self->output_colorspace(self, pipe, piece);
  dt_iop_buffer_dsc_t dsc_out = piece->dsc_out;
  dsc_out.cst = cst_out;
  int box_in[4], box_out[4];
  dt_dev_pixelpipe_t *old = _swap_preview_pipe(pipe);
  // TRUE = no sample there (desktop's convention)
  const gboolean no_in = dt_color_picker_box(self, roi_in, &p->sample, PIXELPIPE_PICKER_INPUT, box_in);
  const gboolean no_out = dt_color_picker_box(self, roi_out, &p->sample, PIXELPIPE_PICKER_OUTPUT, box_out);
  _swap_preview_pipe(old);
  // input: the buffer as process() gets it, in the module's input colour
  // space (pixelpipe_hb.c passes piece->dsc_in and cst_tmp)
  if(!no_in) _sample(&p->side[0], box_in, &piece->dsc_in, i, roi_in, cst_in, picker_cst, profile, p->sample.denoise);
  // output: after process(), before blending (pipe->dsc then has the
  // output colour space; piece->dsc_out is that description)
  if(!no_out) _sample(&p->side[1], box_out, &dsc_out, o, roi_out, cst_out, picker_cst, profile, p->sample.denoise);
}

/* ------------------------------------------------------------------ */
/* requests                                                           */

int dtb__read_area(JsonObject *o, float area[4])
{
  JsonArray *a = NULL;
  gboolean point = FALSE;
  if(o && json_object_has_member(o, "area") && JSON_NODE_HOLDS_ARRAY(json_object_get_member(o, "area")))
    a = json_object_get_array_member(o, "area");
  else if(o && json_object_has_member(o, "point") && JSON_NODE_HOLDS_ARRAY(json_object_get_member(o, "point")))
  {
    a = json_object_get_array_member(o, "point");
    point = TRUE;
  }
  if(!a || json_array_get_length(a) != (point ? 2u : 4u))
    return dtb__err(DTB_E_ARG, "expected \"area\": [x0, y0, x1, y1] or \"point\": [x, y]");
  for(guint k = 0; k < json_array_get_length(a); k++)
  {
    JsonNode *n = json_array_get_element(a, k);
    if(!JSON_NODE_HOLDS_VALUE(n) || (json_node_get_value_type(n) != G_TYPE_DOUBLE && json_node_get_value_type(n) != G_TYPE_INT64))
      return dtb__err(DTB_E_TYPE, "area and point take numbers");
    area[k] = (float)json_node_get_double(n);
    if(!isfinite(area[k]) || area[k] < 0.0f || area[k] > 1.0f)
      return dtb__err(DTB_E_ARG, "area and point are normalised coordinates of the picture (0..1)");
  }
  if(point)
  {
    area[2] = area[0];
    area[3] = area[1];
  }
  else if(area[2] < area[0] || area[3] < area[1])
    return dtb__err(DTB_E_ARG, "area: x0 <= x1 and y0 <= y1");
  return DTB_OK;
}

static gboolean _opt_bool(JsonObject *o, const char *k, const gboolean def)
{
  if(!o || !json_object_has_member(o, k)) return def;
  JsonNode *n = json_object_get_member(o, k);
  return JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_BOOLEAN ? json_node_get_boolean(n) : def;
}

static void _side_json(JsonBuilder *b, const char *name, const _side_t *s)
{
  json_builder_set_member_name(b, name);
  if(!s->have)
  {
    json_builder_add_null_value(b);
    return;
  }
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "colorspace");
  json_builder_add_string_value(b, _cst_name(s->cst));
  const char *names[3] = { "mean", "min", "max" };
  for(int w = 0; w < 3; w++)
  {
    json_builder_set_member_name(b, names[w]);
    json_builder_begin_array(b);
    for(int k = 0; k < 4; k++)
    {
      const double v = w == 0 ? s->sum[k] / MAX(1.0, s->n) : w == 1 ? s->min[k] : s->max[k];
      if(isfinite(v)) json_builder_add_double_value(b, v);
      else json_builder_add_null_value(b);
    }
    json_builder_end_array(b);
  }
  json_builder_set_member_name(b, "box");
  json_builder_begin_array(b);
  for(int k = 0; k < 4; k++) json_builder_add_int_value(b, s->box[k]);
  json_builder_end_array(b);
  json_builder_end_object(b);
}

int dtb__pick(dt_iop_module_t *m, const float area[4], const gboolean denoise, const gboolean full,
              const dtb_render_opts_t *o, dtb_pick_result_t *res)
{
  memset(res, 0, sizeof(*res));
  dtb_pipe_t *p = NULL;
  int r = dtb__pipe_ready(full, &p);
  if(r) return r;
  dtb_bypass_t saved;
  dtb__bypass_begin(p, o, &saved);
  dt_dev_pixelpipe_iop_t *piece = NULL;
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
    if(((dt_dev_pixelpipe_iop_t *)n->data)->module == m) piece = n->data;
  if(!piece || !piece->enabled)
  {
    dtb__bypass_end(&saved);
    return dtb__err(DTB_E_ARG, "%s %d is switched off (or left out): desktop's pickers only pick on modules that are on",
                    m->op, m->multi_priority);
  }
  const int pw = p->pipe.processed_width, ph = p->pipe.processed_height;
  _pick_t pick = { 0 };
  pick.module = m;
  pick.pipe = &p->pipe;
  // as desktop stores the primary sample of an iop picker
  // (gui/color_picker_proxy.c): the area in the picture, mapped back to the
  // pipe's input by dt_color_picker_backtransform_box (normalised, clipped)
  const gboolean point = area[0] == area[2] && area[1] == area[3];
  pick.sample.size = point ? DT_LIB_COLORPICKER_SIZE_POINT : DT_LIB_COLORPICKER_SIZE_BOX;
  pick.sample.denoise = denoise;
  pick.sample.pick_output = TRUE;
  {
    dt_dev_pixelpipe_t *old = _swap_preview_pipe(&p->pipe);
    if(point)
      dt_color_picker_backtransform_box(darktable.develop, 1, area, pick.sample.point);
    else
      dt_color_picker_backtransform_box(darktable.develop, 2, area, pick.sample.box);
    _swap_preview_pipe(old);
  }
  const float bx[4] = { area[0] * pw, area[1] * ph, area[2] * pw, area[3] * ph };

  // one render at scale 1 (the darkroom's preview pipe renders the whole
  // downscaled picture at scale 1) in which the module runs: its hash
  // changed for this render only, as dtb__bypass_begin does for left-out
  // modules, with a new value each time
  static guint64 serial = 0;
  serial++;
  const dt_hash_t h0 = piece->hash;
  piece->hash = dt_hash(piece->hash, &serial, sizeof(serial));
  g_pick = &pick;
  int x = 0, y = 0, w = pw, h = ph;
  if(full)
  {
    // the full pipe: only the area's bounding box and a margin
    x = MAX(0, (int)floorf(bx[0]) - 64);
    y = MAX(0, (int)floorf(bx[1]) - 64);
    w = MIN(pw, (int)ceilf(bx[2]) + 65) - x;
    h = MIN(ph, (int)ceilf(bx[3]) + 65) - y;
  }
  uint8_t *tmp = g_try_malloc((size_t)4 * MAX(1, w) * MAX(1, h));
  r = tmp ? dtb__pipe_run(p, x, y, w, h, 1.0f, tmp) : dtb__err(DTB_E_ENGINE, "out of memory");
  g_free(tmp);
  g_pick = NULL;
  piece->hash = h0;
  dtb__bypass_end(&saved);
  if(r) return r;
  for(int s = 0; s < 2; s++)
  {
    res->have[s] = pick.side[s].have;
    res->cst[s] = pick.side[s].cst;
    for(int k = 0; k < 4; k++)
    {
      res->mean[s][k] = pick.side[s].sum[k] / MAX(1.0, pick.side[s].n);
      res->min[s][k] = pick.side[s].min[k];
      res->max[s][k] = pick.side[s].max[k];
      res->box[s][k] = pick.side[s].box[k];
    }
  }
  // P4c: rgb curve's picker_scale on these numbers, with the pipe's working profile, by darktable's own functions
  const dt_iop_order_iccprofile_info_t *wp = dt_ioppr_get_pipe_work_profile_info(&p->pipe);
  if(wp)
  {
    res->work = TRUE;
    res->work_type = (int)wp->type;
    g_strlcpy(res->work_file, wp->filename, sizeof(res->work_file));
    res->work_nonlinear = wp->nonlinearlut != 0;
    for(int c = 0; c < 3; c++) res->work_y[c] = wp->matrix_in[1][c];
    for(int s = 0; s < 2; s++)
    {
      res->work_have[s] = res->have[s] && res->cst[s] == IOP_CS_RGB;
      if(!res->work_have[s]) continue;
      const float *v[3] = { res->mean[s], res->min[s], res->max[s] };
      for(int k = 0; k < 3; k++)
      {
        dt_aligned_pixel_t rgb = { v[k][0], v[k][1], v[k][2], 1.0f };
        const float y = dt_ioppr_get_rgb_matrix_luminance(rgb, wp->matrix_in, (float **)wp->lut_in,
                                                          wp->unbounded_coeffs_in, wp->lutsize, wp->nonlinearlut);
        res->work_lum[s][k] = y;
        res->work_grey_lum[s][k] = dt_ioppr_compensate_middle_grey(y, wp);
        for(int c = 0; c < 3; c++) res->work_grey[s][k][c] = dt_ioppr_compensate_middle_grey(v[k][c], wp);
      }
    }
  }
  return DTB_OK;
}

/* brief P6a: one render of the bridge's preview pipe at scale 1 in which module m runs (as dtb__pick), with fn
 * called on its input buffer for every tile process() gets (the preview pipe gets one) */
int dtb__module_input(dt_iop_module_t *m, dtb_input_fn fn, void *user)
{
  dtb_pipe_t *p = NULL;
  int r = dtb__pipe_ready(FALSE, &p);
  if(r) return r;
  dtb_bypass_t saved;
  dtb__bypass_begin(p, NULL, &saved);
  dt_dev_pixelpipe_iop_t *piece = NULL;
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
    if(((dt_dev_pixelpipe_iop_t *)n->data)->module == m) piece = n->data;
  if(!piece || !piece->enabled)
  {
    dtb__bypass_end(&saved);
    return dtb__err(DTB_E_ARG, "%s %d is switched off (or left out)", m->op, m->multi_priority);
  }
  const int pw = p->pipe.processed_width, ph = p->pipe.processed_height;
  _pick_t pick = { 0 };
  pick.module = m;
  pick.pipe = &p->pipe;
  pick.fn = fn;
  pick.user = user;
  static guint64 serial = 0;
  serial++;
  const dt_hash_t h0 = piece->hash;
  piece->hash = dt_hash(piece->hash, &serial, sizeof(serial));
  g_pick = &pick;
  uint8_t *tmp = g_try_malloc((size_t)4 * MAX(1, pw) * MAX(1, ph));
  r = tmp ? dtb__pipe_run(p, 0, 0, pw, ph, 1.0f, tmp) : dtb__err(DTB_E_ENGINE, "out of memory");
  g_free(tmp);
  g_pick = NULL;
  piece->hash = h0;
  dtb__bypass_end(&saved);
  return r;
}

/* brief P6a: the area's two corners (normalised in the picture) mapped back to the pipe's input and normalised
 * by its size, as rgb levels' and basic adjustments' "auto region" keep their box_cood (button_released: the
 * corners in preview pixels, dt_dev_distort_backtransform, divided by the preview pipe's iwidth, iheight) */
void dtb__area_to_input(const float area[4], float box[4])
{
  dtb_pipe_t *p = NULL;
  if(dtb__pipe_ready(FALSE, &p)) { memcpy(box, area, sizeof(float) * 4); return; }
  dt_dev_pixelpipe_t *old = _swap_preview_pipe(&p->pipe);
  box[0] = area[0] * p->pipe.processed_width;
  box[1] = area[1] * p->pipe.processed_height;
  box[2] = area[2] * p->pipe.processed_width;
  box[3] = area[3] * p->pipe.processed_height;
  dt_dev_distort_backtransform(darktable.develop, box, 2);
  box[0] /= p->pipe.iwidth;
  box[1] /= p->pipe.iheight;
  box[2] /= p->pipe.iwidth;
  box[3] /= p->pipe.iheight;
  _swap_preview_pipe(old);
}

int dtb__pick_cst(dt_iop_module_t *m, const float area[4], const gboolean denoise, const int cst, dtb_pick_result_t *res)
{
  g_forced_cst = cst;
  const int r = dtb__pick(m, area, denoise, FALSE, NULL, res);
  g_forced_cst = -1;
  return r;
}

static void _result_json(JsonBuilder *b, const dtb_pick_result_t *res)
{
  const char *names[2] = { "input", "output" };
  for(int s = 0; s < 2; s++)
  {
    _side_t side = { 0 };
    side.have = res->have[s];
    side.cst = res->cst[s];
    side.n = 1.0;
    for(int k = 0; k < 4; k++)
    {
      side.sum[k] = res->mean[s][k];
      side.min[k] = res->min[s][k];
      side.max[k] = res->max[s][k];
      side.box[k] = res->box[s][k];
    }
    _side_json(b, names[s], &side);
  }
  // P4c: the working profile and picker_scale's values (engine-api.md 6.3)
  json_builder_set_member_name(b, "work");
  if(!res->work)
  {
    json_builder_add_null_value(b);
    return;
  }
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "type");
  json_builder_add_int_value(b, res->work_type);
  json_builder_set_member_name(b, "filename");
  json_builder_add_string_value(b, res->work_file);
  json_builder_set_member_name(b, "nonlinear");
  json_builder_add_boolean_value(b, res->work_nonlinear);
  json_builder_set_member_name(b, "luminance_coeffs");
  json_builder_begin_array(b);
  for(int c = 0; c < 3; c++) json_builder_add_double_value(b, res->work_y[c]);
  json_builder_end_array(b);
  for(int s = 0; s < 2; s++)
  {
    json_builder_set_member_name(b, names[s]);
    if(!res->work_have[s])
    {
      json_builder_add_null_value(b);
      continue;
    }
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "luminance");
    json_builder_begin_array(b);
    for(int k = 0; k < 3; k++) json_builder_add_double_value(b, res->work_lum[s][k]);
    json_builder_end_array(b);
    json_builder_set_member_name(b, "grey_luminance");
    json_builder_begin_array(b);
    for(int k = 0; k < 3; k++) json_builder_add_double_value(b, res->work_grey_lum[s][k]);
    json_builder_end_array(b);
    json_builder_set_member_name(b, "grey");
    json_builder_begin_array(b);
    for(int k = 0; k < 3; k++)
    {
      json_builder_begin_array(b);
      for(int c = 0; c < 3; c++) json_builder_add_double_value(b, res->work_grey[s][k][c]);
      json_builder_end_array(b);
    }
    json_builder_end_array(b);
    json_builder_end_object(b);
  }
  json_builder_end_object(b);
}

void dtb__pick_json(JsonBuilder *b, const dtb_pick_result_t *res)
{
  json_builder_begin_object(b);
  _result_json(b, res);
  json_builder_end_object(b);
}

/* the picture the pipe puts out (desktop's global picker), on the last
 * render's output buffer of a render at scale 1 */
static int _pick_output(const float area[4], const gboolean denoise, const gboolean full, const dtb_render_opts_t *o,
                        dtb_pick_result_t *res)
{
  memset(res, 0, sizeof(*res));
  dtb_pipe_t *p = NULL;
  int r = dtb__pipe_ready(full, &p);
  if(r) return r;
  dtb_bypass_t saved;
  dtb__bypass_begin(p, o, &saved);
  const int pw = p->pipe.processed_width, ph = p->pipe.processed_height;
  int x = 0, y = 0, w = pw, h = ph;
  if(full)
  {
    x = MAX(0, (int)floorf(area[0] * pw) - 64);
    y = MAX(0, (int)floorf(area[1] * ph) - 64);
    w = MIN(pw, (int)ceilf(area[2] * pw) + 65) - x;
    h = MIN(ph, (int)ceilf(area[3] * ph) + 65) - y;
  }
  uint8_t *tmp = g_try_malloc((size_t)4 * MAX(1, w) * MAX(1, h));
  r = tmp ? dtb__pipe_run(p, x, y, w, h, 1.0f, tmp) : dtb__err(DTB_E_ENGINE, "out of memory");
  g_free(tmp);
  if(!r)
  {
    const dt_iop_roi_t roi = { x, y, w, h, 1.0f };
    // sorted as dt_color_picker_box leaves its corners: two lowest, two highest
    const float fbox[8] = { area[0] * pw, area[1] * ph, area[0] * pw, area[1] * ph,
                            area[2] * pw, area[3] * ph, area[2] * pw, area[3] * ph };
    _side_t side = { 0 };
    dt_iop_buffer_dsc_t dsc = p->pipe.dsc;
    dsc.channels = 4;
    int box[4];
    if(_box_in_roi(fbox, &roi, box))
      _sample(&side, box, &dsc, (const float *)p->pipe.backbuf, &roi, IOP_CS_RGB, IOP_CS_RGB, NULL, denoise);
    res->have[1] = side.have;
    res->cst[1] = IOP_CS_RGB;
    for(int k = 0; k < 4; k++)
    {
      res->mean[1][k] = side.sum[k] / MAX(1.0, side.n);
      res->min[1][k] = side.min[k];
      res->max[1][k] = side.max[k];
      res->box[1][k] = side.box[k];
    }
  }
  dtb__bypass_end(&saved);
  return r;
}

int dtb_color_pick(const char *op, int priority, const char *request_json, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  dt_iop_module_t *m = NULL;
  if(op)
  {
    m = dtb__find(op, priority);
    if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s %d", op, priority);
  }
  JsonNode *root = dtb__json_parse(request_json ? request_json : "{}");
  if(!root) return DTB_E_ARG;
  JsonObject *o = JSON_NODE_HOLDS_OBJECT(root) ? json_node_get_object(root) : NULL;
  float area[4];
  int r = o ? dtb__read_area(o, area) : dtb__err(DTB_E_ARG, "the request must be an object");
  const char *pipe = o && json_object_has_member(o, "pipe") ? json_object_get_string_member(o, "pipe") : "preview";
  if(!r && (!pipe || (strcmp(pipe, "preview") && strcmp(pipe, "full"))))
    r = dtb__err(DTB_E_ARG, "pipe is \"preview\" or \"full\"");
  dtb_render_opts_t ro = { 0 };
  if(!r) r = dtb__render_opts_from(o, &ro);
  dtb_pick_result_t res;
  const gboolean full = !r && !strcmp(pipe, "full");
  const gboolean denoise = _opt_bool(o, "denoise", FALSE);
  if(!r) r = m ? dtb__pick(m, area, denoise, full, &ro, &res) : _pick_output(area, denoise, full, &ro, &res);
  dtb__render_opts_clear(&ro);
  if(!r)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "pipe");
    json_builder_add_string_value(b, full ? "full" : "preview");
    _result_json(b, &res);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
  }
  json_node_unref(root);
  return r;
}

/* ------------------------------------------------------------------ */
/* histogram (6.4)                                                    */

int dtb_histogram(const char *request_json, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonNode *root = dtb__json_parse(request_json ? request_json : "{}");
  if(!root) return DTB_E_ARG;
  JsonObject *o = JSON_NODE_HOLDS_OBJECT(root) ? json_node_get_object(root) : NULL;
  int max_size = 1024;
  if(o && json_object_has_member(o, "max_size")) max_size = (int)json_object_get_int_member(o, "max_size");
  dtb_render_opts_t ro = { 0 };
  int r = max_size >= 16 ? dtb__render_opts_from(o, &ro) : dtb__err(DTB_E_ARG, "max_size must be >= 16");
  json_node_unref(root);
  if(r) return r;
  dtb_pipe_t *p = NULL;
  r = dtb__pipe_ready(FALSE, &p);
  if(r)
  {
    dtb__render_opts_clear(&ro);
    return r;
  }
  dtb_bypass_t saved;
  dtb__bypass_begin(p, &ro, &saved);
  const int pw = p->pipe.processed_width, ph = p->pipe.processed_height;
  const float scale = fminf(1.0f, (float)max_size / (float)MAX(pw, ph));
  const int w = MAX(1, (int)floor(scale * pw)), h = MAX(1, (int)floor(scale * ph));
  uint8_t *tmp = g_try_malloc((size_t)4 * w * h);
  r = tmp ? dtb__pipe_run(p, 0, 0, w, h, scale, tmp) : dtb__err(DTB_E_ENGINE, "out of memory");
  g_free(tmp);
  if(!r)
  {
    // common/histogram.c _bin_rgb / _clamp_bin, as libs/histogram.c uses it
    // for the RGB histogram: bin = (size_t)CLAMP(255 * value, 0, 255)
    guint32 *counts = g_new0(guint32, 3 * 256);
    const float *px = (const float *)p->pipe.backbuf;
    for(size_t i = 0; i < (size_t)w * h; i++)
      for(int c = 0; c < 3; c++)
      {
        const float v = 255.0f * px[4 * i + c];
        const size_t bin = (size_t)CLAMP(isfinite(v) ? v : 0.0f, 0.0f, 255.0f);
        counts[c * 256 + bin]++;
      }
    guint32 max = 0;
    for(int k = 0; k < 3 * 256; k++) max = MAX(max, counts[k]);
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "bins");
    json_builder_add_int_value(b, 256);
    json_builder_set_member_name(b, "channels");
    json_builder_begin_array(b);
    json_builder_add_string_value(b, "red");
    json_builder_add_string_value(b, "green");
    json_builder_add_string_value(b, "blue");
    json_builder_end_array(b);
    json_builder_set_member_name(b, "counts");
    json_builder_begin_array(b);
    for(int c = 0; c < 3; c++)
    {
      json_builder_begin_array(b);
      for(int k = 0; k < 256; k++) json_builder_add_int_value(b, counts[c * 256 + k]);
      json_builder_end_array(b);
    }
    json_builder_end_array(b);
    json_builder_set_member_name(b, "max");
    json_builder_add_int_value(b, max);
    json_builder_set_member_name(b, "pixels");
    json_builder_add_int_value(b, (gint64)w * h);
    json_builder_set_member_name(b, "width");
    json_builder_add_int_value(b, w);
    json_builder_set_member_name(b, "height");
    json_builder_add_int_value(b, h);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
    g_free(counts);
  }
  dtb__bypass_end(&saved);
  dtb__render_opts_clear(&ro);
  return r;
}

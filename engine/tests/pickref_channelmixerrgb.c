// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the CAT tab's picker callback in "correction" mode (color_picker_apply, _auto_set_illuminant, _check_if_close_to_daylight), copied verbatim from src/iop/channelmixerrgb.c by pickref_extract.py,
 * run on a copy of the module (dtb_pickref.c) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_channelmixerrgb_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: they update the panel only (illuminant lists, CCT label, colour patches), or serve "measure" mode,
 * which the test does not run */
static void _update_illuminants(const dt_iop_module_t *self) { (void)self; }
static void _update_approx_cct(const dt_iop_module_t *self) { (void)self; }
static void _update_illuminant_color(const dt_iop_module_t *self) { (void)self; }
static void _paint_hue(const dt_iop_module_t *self) { (void)self; }
static void _get_corrected_illuminant_xy(const dt_iop_module_t *self, const dt_dev_wb_t *wb,
                                         const dt_iop_channelmixer_rgb_params_t *p, float *x, float *y)
{
  (void)self; (void)wb; (void)p; (void)x; (void)y;
}

#define color_picker_apply up_channelmixerrgb_color_picker_apply
#include "pickref_up_channelmixerrgb_funcs.inc"
#undef color_picker_apply

int pickref_channelmixerrgb(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  if(w->mode != DT_SPOT_MODE_CORRECT) return 1;
  dt_iop_channelmixer_rgb_gui_data_t g;
  memset(&g, 0, sizeof(g));
  float temperature = 0.f, illum_x = 0.f, illum_y = 0.f;
  int illuminant = 0, adaptation = 0, picker = 0;
  g.spot_mode = (GtkWidget *)&w->mode;
  g.use_mixing = (GtkWidget *)&w->use_mixing;
  g.lightness_spot = (GtkWidget *)&w->lightness;
  g.hue_spot = (GtkWidget *)&w->hue;
  g.chroma_spot = (GtkWidget *)&w->chroma;
  g.temperature = (GtkWidget *)&temperature;
  g.illuminant = (GtkWidget *)&illuminant;
  g.adaptation = (GtkWidget *)&adaptation;
  g.illum_x = (GtkWidget *)&illum_x;
  g.illum_y = (GtkWidget *)&illum_y;
  g.color_picker = (GtkWidget *)&picker;
  self->gui_data = &g;
  up_channelmixerrgb_color_picker_apply(self, g.color_picker, pipe);
  self->gui_data = NULL;
  return 0;
}

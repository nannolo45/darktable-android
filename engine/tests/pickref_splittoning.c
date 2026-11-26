// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the two hue pickers' callback (color_picker_apply), copied verbatim from src/iop/splittoning.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_splittoning_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: they colour the panel's sliders only */
static void update_balance_slider_colors(GtkWidget *s, float a, float b) { (void)s; (void)a; (void)b; }
static void update_colorpicker_color(GtkWidget *c, float h, float s) { (void)c; (void)h; (void)s; }
static void update_saturation_slider_end_color(GtkWidget *s, float h) { (void)s; (void)h; }

#define color_picker_apply up_splittoning_color_picker_apply
#include "pickref_up_splittoning_funcs.inc"
#undef color_picker_apply

int pickref_splittoning(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_splittoning_gui_data_t g;
  memset(&g, 0, sizeof(g));
  float sl[6] = { 0 };
  int pk[2] = { 0 };
  g.highlight_hue_gslider = (GtkWidget *)&sl[0];
  g.highlight_sat_gslider = (GtkWidget *)&sl[1];
  g.shadow_hue_gslider = (GtkWidget *)&sl[2];
  g.shadow_sat_gslider = (GtkWidget *)&sl[3];
  g.balance_scale = (GtkWidget *)&sl[4];
  g.highlight_colorpick = (GtkWidget *)&pk[0];
  g.shadow_colorpick = (GtkWidget *)&pk[1];
  self->gui_data = &g;
  up_splittoning_color_picker_apply(self, w->which ? g.highlight_hue_gslider : g.shadow_hue_gslider, pipe);
  self->gui_data = NULL;
  return 0;
}

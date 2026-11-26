// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the hue picker's callback (color_picker_apply), copied verbatim from src/iop/colorize.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_colorize_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: it colours the panel's slider only */
static void update_saturation_slider_end_color(GtkWidget *s, float h) { (void)s; (void)h; }

#define color_picker_apply up_colorize_color_picker_apply
#include "pickref_up_colorize_funcs.inc"
#undef color_picker_apply

int pickref_colorize(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  (void)w;
  dt_iop_colorize_gui_data_t g;
  memset(&g, 0, sizeof(g));
  float sl[2] = { 0 };
  g.hue = (GtkWidget *)&sl[0];
  g.saturation = (GtkWidget *)&sl[1];
  self->gui_data = &g;
  up_colorize_color_picker_apply(self, g.hue, pipe);
  self->gui_data = NULL;
  return 0;
}

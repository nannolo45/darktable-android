// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the "create curve" picker callback (color_picker_apply, _add_node_from_picker, _add_node), copied verbatim from src/iop/rgbcurve.c by pickref_extract.py,
 * run on a copy of the module (dtb_pickref.c) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_rgbcurve_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

#define color_picker_apply up_rgbcurve_color_picker_apply
#include "pickref_up_rgbcurve_funcs.inc"
#undef color_picker_apply

int pickref_rgbcurve(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_rgbcurve_gui_data_t g;
  memset(&g, 0, sizeof(g));
  int set_values = 0, show = 0;
  g.colorpicker_set_values = (GtkWidget *)&set_values;
  g.colorpicker = (GtkWidget *)&show;
  g.channel = (rgbcurve_channel_t)w->channel;
  self->gui_data = &g;
  up_rgbcurve_color_picker_apply(self, g.colorpicker_set_values, pipe);
  self->gui_data = NULL;
  return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the "create a curve from an area" picker's callback (color_picker_apply), copied
 * verbatim from src/iop/colorzones.c by pickref_extract.py, run on a copy of the module (dtb_pickref.c, P6a cases)
 * with the widgets of pickref_stubs.h.
 */
#include "pickref_up_colorzones_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

#define color_picker_apply up_colorzones_color_picker_apply
#include "pickref_up_colorzones_funcs.inc"
#undef color_picker_apply

int pickref_colorzones(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_colorzones_gui_data_t *g = g_malloc0(sizeof(dt_iop_colorzones_gui_data_t));
  int pk = 0;
  g->colorpicker_set_values = (GtkWidget *)&pk;
  g->channel = (dt_iop_colorzones_channel_t)w->channel;
  self->gui_data = g;
  up_colorzones_color_picker_apply(self, g->colorpicker_set_values, pipe);
  self->gui_data = NULL;
  g_free(g);
  return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the black, grey and white pickers' callback (color_picker_apply), copied verbatim from src/iop/levels.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_levels_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

#define color_picker_apply up_levels_color_picker_apply
#include "pickref_up_levels_funcs.inc"
#undef color_picker_apply

int pickref_levels(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_levels_gui_data_t g;
  memset(&g, 0, sizeof(g));
  int pick[3] = { 0, 0, 0 };
  g.blackpick = (GtkWidget *)&pick[0];
  g.greypick = (GtkWidget *)&pick[1];
  g.whitepick = (GtkWidget *)&pick[2];
  g.last_picked_color = NAN;
  GtkWidget *picker = w->which == 0 ? g.blackpick : w->which == 1 ? g.greypick : g.whitepick;
  self->gui_data = &g;
  up_levels_color_picker_apply(self, picker, pipe);
  self->gui_data = NULL;
  return 0;
}

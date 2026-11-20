// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the black, gray and white pickers' callback (color_picker_apply), copied verbatim from src/iop/rgblevels.c by pickref_extract.py,
 * run on a copy of the module (dtb_pickref.c) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_rgblevels_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

#define color_picker_apply up_rgblevels_color_picker_apply
#include "pickref_up_rgblevels_funcs.inc"
#undef color_picker_apply

int pickref_rgblevels(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_rgblevels_gui_data_t g;
  memset(&g, 0, sizeof(g));
  int pick[3] = { 0, 0, 0 };
  g.blackpick = (GtkWidget *)&pick[0];
  g.greypick = (GtkWidget *)&pick[1];
  g.whitepick = (GtkWidget *)&pick[2];
  g.channel = (dt_iop_rgblevels_channel_t)w->channel;
  // a value no pick gives, so this pick is applied (desktop skips a pick equal to the last one, against flicker)
  g.last_picked_color = NAN;
  GtkWidget *picker = w->which == 0 ? g.blackpick : w->which == 1 ? g.greypick : g.whitepick;
  self->gui_data = &g;
  up_rgblevels_color_picker_apply(self, picker, pipe);
  self->gui_data = NULL;
  return 0;
}

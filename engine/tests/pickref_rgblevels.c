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

/* brief P6a (C43): "auto" and "auto region": desktop's _get_selected_area (with its box_cood) and _auto_levels on
 * the module's input buffer, as process() runs them on the preview pipe */
void pickref_rgblevels_auto(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const float *in,
                            const dt_iop_roi_t *roi_in, const float box_cood[4], const int channel, void *params)
{
  dt_iop_rgblevels_gui_data_t g;
  memset(&g, 0, sizeof(g));
  for(int k = 0; k < 4; k++) g.box_cood[k] = box_cood[k];
  int box[4] = { 0 };
  _get_selected_area(self, piece, &g, roi_in, box);
  _auto_levels(in, roi_in->width, roi_in->height, box, (dt_iop_rgblevels_params_t *)params, channel,
               dt_ioppr_get_pipe_work_profile_info(piece->pipe));
}

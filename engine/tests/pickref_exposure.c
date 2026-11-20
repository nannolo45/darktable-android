// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the exposure slider's picker callback (color_picker_apply, _auto_set_exposure and the helpers they call), copied verbatim from src/iop/exposure.c by pickref_extract.py,
 * run on a copy of the module (dtb_pickref.c) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_exposure_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: it paints the target colour patch only */
static void _paint_hue(dt_iop_module_t *self) { (void)self; }

#define color_picker_apply up_exposure_color_picker_apply
#include "pickref_up_exposure_funcs.inc"
#undef color_picker_apply

int pickref_exposure(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_exposure_gui_data_t g;
  memset(&g, 0, sizeof(g));
  float exposure = 0.f, black = 0.f;
  g.exposure = (GtkWidget *)&exposure;
  g.black = (GtkWidget *)&black;
  g.spot_mode = (GtkWidget *)&w->mode;
  g.lightness_spot = (GtkWidget *)&w->lightness;
  self->gui_data = &g;
  up_exposure_color_picker_apply(self, g.exposure, pipe);
  self->gui_data = NULL;
  return 0;
}

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the middle grey picker's callback (color_picker_apply), copied verbatim from src/iop/basicadj.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_basicadj_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

#define color_picker_apply up_basicadj_color_picker_apply
#include "pickref_up_basicadj_funcs.inc"
#undef color_picker_apply

int pickref_basicadj(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  (void)w;
  dt_iop_basicadj_gui_data_t g;
  memset(&g, 0, sizeof(g));
  float sl = 0.f;
  g.sl_middle_grey = (GtkWidget *)&sl;
  self->gui_data = &g;
  up_basicadj_color_picker_apply(self, g.sl_middle_grey, pipe);
  self->gui_data = NULL;
  return 0;
}

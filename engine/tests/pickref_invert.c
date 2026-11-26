// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the film colour picker's callback (color_picker_apply), copied verbatim from src/iop/invert.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_invert_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: it updates the panel's colour button only */
static void gui_update_from_coeffs(dt_iop_module_t *self) { (void)self; }

#define color_picker_apply up_invert_color_picker_apply
#include "pickref_up_invert_funcs.inc"
#undef color_picker_apply

int pickref_invert(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  (void)w;
  dt_iop_invert_gui_data_t g;
  memset(&g, 0, sizeof(g));
  self->gui_data = &g;
  up_invert_color_picker_apply(self, NULL, pipe);
  self->gui_data = NULL;
  return 0;
}

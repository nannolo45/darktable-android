// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the white level picker's callback (color_picker_apply, _pipe_RGB_to_Ych), copied verbatim from src/iop/colorequal.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_colorequal_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* the module's gui_changed: for the white level it redraws only */
static void gui_changed(dt_iop_module_t *self, GtkWidget *w, void *previous) { (void)self; (void)w; (void)previous; }

#define color_picker_apply up_colorequal_color_picker_apply
#include "pickref_up_colorequal_funcs.inc"
#undef color_picker_apply

int pickref_colorequal(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  (void)w;
  dt_iop_colorequal_gui_data_t *g = g_malloc0(sizeof(dt_iop_colorequal_gui_data_t));
  float sl = 0.f;
  g->white_level = (GtkWidget *)&sl;
  self->gui_data = g;
  up_colorequal_color_picker_apply(self, g->white_level, pipe);
  self->gui_data = NULL;
  g_free(g);
  return 0;
}

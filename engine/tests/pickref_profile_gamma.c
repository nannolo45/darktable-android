// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the four pickers' callbacks (color_picker_apply, apply_auto_grey, apply_auto_black, apply_auto_dynamic_range, apply_autotune), copied verbatim from src/iop/profile_gamma.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_profile_gamma_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

#define color_picker_apply up_profile_gamma_color_picker_apply
#include "pickref_up_profile_gamma_funcs.inc"
#undef color_picker_apply

int pickref_profile_gamma(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_profilegamma_gui_data_t g;
  memset(&g, 0, sizeof(g));
  float sl[3] = { 0 };
  int ab = 0;
  g.grey_point = (GtkWidget *)&sl[0];
  g.shadows_range = (GtkWidget *)&sl[1];
  g.dynamic_range = (GtkWidget *)&sl[2];
  g.auto_button = (GtkWidget *)&ab;
  GtkWidget *pick[4] = { g.grey_point, g.shadows_range, g.dynamic_range, g.auto_button };
  self->gui_data = &g;
  up_profile_gamma_color_picker_apply(self, pick[w->which], pipe);
  self->gui_data = NULL;
  return 0;
}

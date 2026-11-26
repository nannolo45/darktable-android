// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the hue pickers' callback (color_picker_apply, _picked_color_to_hue), copied verbatim
 * from src/iop/colorharmonizer.c by pickref_extract.py, run on a copy of the module (dtb_pickref.c, P6a cases) with
 * the widgets of pickref_stubs.h.
 */
#include "pickref_up_colorharmonizer_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: the RYB hue the panel's slider shows (the parameter is the UCS hue) */
static inline float _ucs_to_ryb_fast(const float ucs) { return ucs; }
/* the module's gui_changed: for a hue it redraws only */
static void gui_changed(dt_iop_module_t *self, GtkWidget *w, void *previous) { (void)self; (void)w; (void)previous; }

#define color_picker_apply up_colorharmonizer_color_picker_apply
#include "pickref_up_colorharmonizer_funcs.inc"
#undef color_picker_apply

int pickref_colorharmonizer(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_colorharmonizer_gui_data_t *g = g_malloc0(sizeof(dt_iop_colorharmonizer_gui_data_t));
  float sl[5] = { 0 };
  g->anchor_hue = (GtkWidget *)&sl[0];
  for(int i = 0; i < COLORHARMONIZER_MAX_NODES; i++) g->custom_hue_slider[i] = (GtkWidget *)&sl[1 + i];
  self->gui_data = g;
  // which: 0 the anchor, 1..4 custom node 0..3
  up_colorharmonizer_color_picker_apply(self, w->which ? g->custom_hue_slider[w->which - 1] : g->anchor_hue, pipe);
  self->gui_data = NULL;
  g_free(g);
  return 0;
}

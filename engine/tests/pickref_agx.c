// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C43 (brief P6a): the five pickers' and three buttons' callbacks (color_picker_apply, _read_exposure_params_callback, _apply_primaries_from_menu_callback, _set_post_curve_primaries_from_pre_callback) with the curve arithmetic, copied verbatim from src/iop/agx.c by pickref_extract.py, run on a copy of the
 * module (dtb_pickref.c, P6a cases) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_agx_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: they update the panel (the pivot slider's soft range, the curve's warnings) */
static void _update_pivot_slider_settings(GtkWidget *const slider, const dt_iop_agx_params_t *const p) { (void)slider; (void)p; }
static void _update_curve_warnings(dt_iop_module_t *self) { (void)self; }
/* the panel's refresh after a button */
#undef dt_iop_gui_update
#define dt_iop_gui_update(m) do {} while(0)
/* desktop asks the darkroom's exposure module (only in the darkroom view): the test gives the value the bridge used */
static float pickref_agx_exposure = 0.f;
#define dt_dev_exposure_get_effective_exposure(dev) ((void)(dev), pickref_agx_exposure)

#define color_picker_apply up_agx_color_picker_apply
#include "pickref_up_agx_funcs.inc"
#undef color_picker_apply

int pickref_agx(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  dt_iop_agx_gui_data_t *g = g_malloc0(sizeof(dt_iop_agx_gui_data_t));
  float sl[8] = { 0 };
  int pk = 0;
  g->black_exposure_picker = (GtkWidget *)&sl[0];
  g->white_exposure_picker = (GtkWidget *)&sl[1];
  g->range_exposure_picker = (GtkWidget *)&pk;
  g->basic_curve_controls.curve_pivot_x = (GtkWidget *)&sl[2];
  g->basic_curve_controls.curve_pivot_y_linear = (GtkWidget *)&sl[3];
  g->curve_gamma = (GtkWidget *)&sl[4];
  self->gui_data = g;
  // which: 0 black, 1 white, 2 auto tune levels, 3 pivot x, 4 pivot x and y; 10 read exposure,
  // 11..13 primaries blender / smooth / unmodified, 14 set post-curve primaries from above
  GtkWidget *pick[5] = { g->black_exposure_picker, g->white_exposure_picker, g->range_exposure_picker,
                         g->basic_curve_controls.curve_pivot_x, g->basic_curve_controls.curve_pivot_y_linear };
  if(w->which < 5) up_agx_color_picker_apply(self, pick[w->which], pipe);
  else if(w->which == 10)
  {
    pickref_agx_exposure = w->lightness;
    _read_exposure_params_callback(NULL, self);
  }
  else if(w->which >= 11 && w->which <= 13)
  {
    static const char *ids[3] = { "blender", "smooth", "unmodified" };
    GVariant *v = g_variant_ref_sink(g_variant_new_string(ids[w->which - 11]));
    _apply_primaries_from_menu_callback(NULL, v, self);
    g_variant_unref(v);
  }
  else if(w->which == 14) _set_post_curve_primaries_from_pre_callback(NULL, self);
  self->gui_data = NULL;
  g_free(g);
  return 0;
}

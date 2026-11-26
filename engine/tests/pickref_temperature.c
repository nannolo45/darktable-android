// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the "from image area" picker callback (color_picker_apply), copied verbatim from src/iop/temperature.c by pickref_extract.py,
 * run on a copy of the module (dtb_pickref.c) with the widgets of pickref_stubs.h.
 */
#include "pickref_up_temperature_types.inc"
#include "pickref_stubs.h"
#include "pickref.h"

/* not copied: it updates the panel (sliders, finetune) only */
static void _update_preset(dt_iop_module_t *self, int mode) { (void)self; (void)mode; }

#define color_picker_apply up_temperature_color_picker_apply
#include "pickref_up_temperature_funcs.inc"
#undef color_picker_apply

int pickref_temperature(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w)
{
  (void)w;
  dt_iop_temperature_gui_data_t g;
  memset(&g, 0, sizeof(g));
  int presets = -1, picker = 0;
  g.presets = (GtkWidget *)&presets;
  g.colorpicker = (GtkWidget *)&picker;
  self->gui_data = &g;
  up_temperature_color_picker_apply(self, g.colorpicker, pipe);
  self->gui_data = NULL;
  return presets == DT_IOP_TEMP_SPOT ? 0 : 1;
}

/* brief P6a (C43): the temperature and tint desktop's panel shows for the module's coefficients (_prepare_matrices,
 * _mul2temp: the camera's matrices, the spectra of a black body and of daylight, the CIE 1931 observer) */
void pickref_temperature_mul2temp(dt_iop_module_t *self, float *temp, float *tint)
{
  dt_iop_temperature_gui_data_t g;
  memset(&g, 0, sizeof(g));
  self->gui_data = &g;
  _prepare_matrices(self);
  _mul2temp(self, self->params, temp, tint);
  self->gui_data = NULL;
}

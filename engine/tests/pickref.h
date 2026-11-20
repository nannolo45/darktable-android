// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): darktable's own picker callbacks, copied verbatim
 * (pickref_extract.py), run on a copy of the module with the values the bridge's action picked.
 */
#pragma once

/* the values of the module's interface the callback reads, and what it writes back to them */
typedef struct pickref_widgets_t
{
  int mode;            /* exposure, color calibration: area mode, 0 correction, 1 measure */
  float lightness;     /* the area mapping's target lightness (and exposure's measured one, written back) */
  float hue, chroma;   /* color calibration's target */
  int use_mixing;      /* color calibration: take channel mixing into account */
  int channel;         /* rgblevels, rgbcurve: the channel tab */
  int which;           /* rgblevels: 0 black, 1 grey, 2 white */
} pickref_widgets_t;

/* run the module's callback on [self] (a copy of the session's module whose params point to the parameters to
 * change) with its picked_color, picked_color_min and picked_color_max set, on [pipe]; 0 = done */
struct dt_iop_module_t;
struct dt_dev_pixelpipe_t;
int pickref_temperature(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w);
int pickref_exposure(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w);
int pickref_rgblevels(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w);
int pickref_rgbcurve(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w);
int pickref_channelmixerrgb(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_t *pipe, pickref_widgets_t *w);

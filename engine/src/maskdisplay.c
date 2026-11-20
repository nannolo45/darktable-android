// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: desktop's "show mask" overlay for one render (P1b part 2,
 * engine-api.md 6.8).
 *
 * Desktop shows an instance's mask when its "display mask" button is on:
 * the module's blend (develop/blend.c dt_develop_blend_process) writes the
 * mask into the pipe and sets pipe->mask_display, and gamma (iop/gamma.c
 * _mask_display) draws the picture in grey with the mask in yellow. blend.c
 * honours the request only for the module with the focus on the darkroom's
 * full pipe ("dt_iop_has_focus(self) && piece->pipe == self->dev->full.pipe",
 * and dt_iop_has_focus wants dev->gui_attached and dev->gui_module). For the
 * one render the bridge sets exactly those on its develop object and the
 * module (request_mask_display = DT_DEV_PIXELPIPE_DISPLAY_MASK), runs the
 * pipe with gamma (render.c), and puts them back. The module must run for
 * its blend to write the mask, so its piece hash is changed for the render
 * (a cache line of the plain render would otherwise be reused), as for the
 * pickers; the lines after it are not kept by the pipe while a mask is shown
 * (pixelpipe_hb.c invalidates them).
 *
 * The mask travels to gamma in the pixels' fourth channel; desktop's modules
 * copy it when a mask is shown (dt_iop_alpha_copy). Two modules the bridge's
 * pipe runs and the darkroom's does not, or not that way, set it to 0:
 * colorout (the darkroom's presented pipe leaves colorout out, its
 * commit_params) and colorin's blue-mapping path (colorin.c
 * _process_cmatrix_bm, "cam[3] = 0.0f"). So while a mask is shown, after
 * every module behind the shown one whose input and output windows are the
 * same, the bridge copies the fourth channel from input to output, as
 * dt_iop_alpha_copy does (dtb__mask_display_hook, called from times.c).
 */
#include "dtb_internal.h"

#include "develop/blend.h"

gboolean dtb__mask_display_active = FALSE;

int dtb__mask_display_begin(dtb_pipe_t *p, const dtb_render_opts_t *o, dtb_mask_display_t *md)
{
  memset(md, 0, sizeof(*md));
  if(!o || !o->show_mask) return DTB_OK;
  dt_iop_module_t *m = o->show_mask;
  if(!(m->flags() & IOP_FLAGS_SUPPORTS_BLENDING) || !m->blend_params)
    return dtb__err(DTB_E_ARG, "%s has no blending, so no mask to show", m->op);
  const dt_develop_blend_params_t *bp = m->blend_params;
  // desktop shows a mask for drawn, parametric, raster and flexi masks, and
  // for the details threshold (blend.c: mode_parametric || mode_drawn ||
  // details != 0, and raster)
  const gboolean has_mask = (bp->mask_mode & DEVELOP_MASK_ENABLED)
                            && ((bp->mask_mode & ~DEVELOP_MASK_ENABLED) || bp->details != 0.0f);
  if(!has_mask) return dtb__err(DTB_E_ARG, "%s %d has no mask to show (mask mode off or uniform)", m->op, m->multi_priority);
  dt_dev_pixelpipe_iop_t *piece = NULL;
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
    if(((dt_dev_pixelpipe_iop_t *)n->data)->module == m) piece = n->data;
  if(!piece || !piece->enabled) return dtb__err(DTB_E_ARG, "%s %d is switched off", m->op, m->multi_priority);
  static guint64 serial = 0;
  serial++;
  md->piece = piece;
  md->hash = piece->hash;
  piece->hash = dt_hash(piece->hash, &serial, sizeof(serial));
  md->active = TRUE;
  md->pipe = p;
  md->module = m;
  md->saved[0] = dtb_S.dev.gui_module;
  md->saved[1] = dtb_S.dev.full.pipe;
  md->saved_int[0] = dtb_S.dev.gui_attached;
  md->saved_int[1] = m->request_mask_display;
  dtb_S.dev.gui_module = m;
  dtb_S.dev.full.pipe = &p->pipe;
  dtb_S.dev.gui_attached = TRUE;
  m->request_mask_display = DT_DEV_PIXELPIPE_DISPLAY_MASK;
  dtb__mask_display_active = TRUE;
  return DTB_OK;
}

void dtb__mask_display_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const i,
                            void *const o, const dt_iop_roi_t *const roi_in, const dt_iop_roi_t *const roi_out)
{
  if(!dtb__mask_display_active || !(piece->pipe->mask_display & DT_DEV_PIXELPIPE_DISPLAY_MASK)) return;
  if(piece->colors != 4 || dt_iop_module_is_gamma(self)) return;
  if(roi_in->width != roi_out->width || roi_in->height != roi_out->height || roi_in->x != roi_out->x
     || roi_in->y != roi_out->y)
    return;
  const float *const in = i;
  float *const out = o;
  const size_t n = (size_t)roi_out->width * roi_out->height;
  for(size_t k = 0; k < n; k++) out[4 * k + 3] = in[4 * k + 3];
}

void dtb__mask_display_end(dtb_mask_display_t *md)
{
  if(!md->active) return;
  dtb_S.dev.gui_module = md->saved[0];
  dtb_S.dev.full.pipe = md->saved[1];
  dtb_S.dev.gui_attached = md->saved_int[0];
  md->module->request_mask_display = md->saved_int[1];
  dtb__mask_display_active = FALSE;
  md->pipe->pipe.mask_display = DT_DEV_PIXELPIPE_DISPLAY_NONE;
  ((dt_dev_pixelpipe_iop_t *)md->piece)->hash = md->hash;
  md->active = FALSE;
}

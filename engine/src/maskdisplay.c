// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: desktop's "show mask" overlay for one render (P1b part 2,
 * engine-api.md 6.8).
 */
#include "dtb_internal.h"

int dtb__mask_display_begin(dtb_pipe_t *p, const dtb_render_opts_t *o, dtb_mask_display_t *md)
{
  memset(md, 0, sizeof(*md));
  if(!o || !o->show_mask) return DTB_OK;
  return dtb__err(DTB_E_ARG, "show_mask is not built yet");
}

void dtb__mask_display_end(dtb_mask_display_t *md)
{
  md->active = FALSE;
}

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: rendering (Engine API v1, section 2.7).
 *
 * Renders use the export pipeline's behaviour with a darkroom-sized cache:
 * the pipe type is DT_DEV_PIXELPIPE_EXPORT and the input is the full-size
 * image, as dt_imageio_export_with_flags() (imageio/imageio.c) sets them up,
 * and the 8-bit conversion is dt_display_to_8bit_pixels(), so a full render
 * equals darktable-cli's export. The preview runs a second pipe on the
 * downscaled input the darkroom's preview pipe uses.
 */
#include "dtb_internal.h"

#include "common/colorspaces.h"
#include "common/iop_order.h"
#include "develop/tiling.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

/* The region margin. darktable's darkroom renders its visible region with
 * no border: demosaic's modify_roi_in() (iop/demosaic.c) asks for exactly
 * the output region, so photosites at the region's edge are interpolated
 * without their outside neighbours (S3, test T5). Upstream does not expose
 * how far each module reaches: demosaic keeps its border inside
 * _tiling_requirements() (up to 18 px for Markesteijn 3-pass, plus 6 with
 * capture sharpening) and reports no overlap through tiling_callback(). The
 * bridge therefore uses a fixed margin that covers the largest of those
 * borders, in output pixels at the render scale. DTB_REGION_MARGIN in the
 * environment overrides it, for measurements only. */
#define DTB_REGION_MARGIN_DEFAULT 32

static gint g_cancel = 0;
static gpointer g_active_pipe = NULL;  // dt_dev_pixelpipe_t * of the render in flight

/* The largest margin a module's reach can ask for: a module whose tiling
 * overlap is larger is rendered with this margin and reported in the
 * write-up's list of expected differences instead. */
#define DTB_REGION_MARGIN_MAX 256

/* -1: no override; else DTB_REGION_MARGIN from the environment
 * (t5_bisect.sh, measurements only) */
static int _margin_override(void)
{
  static int m = -2;
  if(m == -2)
  {
    const char *e = g_getenv("DTB_REGION_MARGIN");
    m = e ? MAX(0, atoi(e)) : -1;
  }
  return m;
}

static void _pipe_free(dtb_pipe_t *p)
{
  if(!p->ready) return;
  dt_dev_pixelpipe_cleanup(&p->pipe);
  dt_mipmap_cache_release(&p->buf);
  p->ready = FALSE;
}

void dtb__pipes_free(void)
{
  _pipe_free(&dtb_S.full);
  _pipe_free(&dtb_S.preview);
}

void dtb__pipes_dirty(const dt_dev_pixelpipe_change_t what)
{
  if(dtb_S.full.ready) dtb_S.full.pipe.changed |= what;
  if(dtb_S.preview.ready) dtb_S.preview.pipe.changed |= what;
}

/* before the develop object is torn down: the nodes point to its modules */
void dtb__pipes_detach(void)
{
  if(dtb_S.full.ready) dt_dev_pixelpipe_cleanup_nodes(&dtb_S.full.pipe);
  if(dtb_S.preview.ready) dt_dev_pixelpipe_cleanup_nodes(&dtb_S.preview.pipe);
}

/* after it was loaded again: same input, nodes rebuilt on the next render */
void dtb__pipes_reattach(void)
{
  dtb_pipe_t *ps[2] = { &dtb_S.full, &dtb_S.preview };
  for(int i = 0; i < 2; i++)
    if(ps[i]->ready)
    {
      dt_dev_pixelpipe_set_input(&ps[i]->pipe, &dtb_S.dev, (float *)ps[i]->buf.buf,
                                 ps[i]->buf.width, ps[i]->buf.height, ps[i]->buf.iscale);
      ps[i]->pipe.changed |= DT_DEV_PIPE_REMOVE;
    }
}

/* Set up a cached pipeline the way dt_imageio_export_with_flags() does for
 * an export: same pipe type, so every module takes its export code path,
 * and the same input. Unlike the export pipe, it gets the darkroom's cache
 * size (dt_dev_pixelpipe_init: 64 lines), so a re-render re-runs only the
 * modules after the first changed one. */
static int _pipe_ensure(dtb_pipe_t *p, const dt_mipmap_size_t size)
{
  if(!p->ready)
  {
    p->size = size;
    dt_mipmap_cache_get(&p->buf, dtb_S.imgid, size, DT_MIPMAP_BLOCKING, 'r');
    if(!p->buf.buf || !p->buf.width || !p->buf.height)
    {
      dt_mipmap_cache_release(&p->buf);
      return dtb__err(DTB_E_ENGINE, "image could not be loaded (mipmap %d)", size);
    }
    if(!dt_dev_pixelpipe_init(&p->pipe))
    {
      dt_mipmap_cache_release(&p->buf);
      return dtb__err(DTB_E_ENGINE, "pixelpipe init failed");
    }
    // the darkroom pipe type would select display output and the screen
    // code paths; the export type is what darktable-cli runs
    p->pipe.type = DT_DEV_PIXELPIPE_EXPORT;
    dt_dev_pixelpipe_set_icc(&p->pipe, DT_COLORSPACE_NONE, NULL, DT_INTENT_LAST);
    dt_dev_pixelpipe_set_input(&p->pipe, &dtb_S.dev, (float *)p->buf.buf,
                               p->buf.width, p->buf.height, p->buf.iscale);
    dt_ioppr_resync_modules_order(&dtb_S.dev);
    dt_dev_pixelpipe_create_nodes(&p->pipe, &dtb_S.dev);
    dt_dev_pixelpipe_synch_all(&p->pipe, &dtb_S.dev);
    dt_dev_pixelpipe_get_dimensions(&p->pipe, &dtb_S.dev, p->pipe.iwidth, p->pipe.iheight,
                                    &p->pipe.processed_width, &p->pipe.processed_height);
    p->pipe.changed = DT_DEV_PIPE_UNCHANGED;
    p->ready = TRUE;
  }
  else if(p->pipe.changed != DT_DEV_PIPE_UNCHANGED)
  {
    const double t0 = dtb__t();
    const dt_dev_pixelpipe_change_t what = p->pipe.changed;
    if(p->pipe.changed & DT_DEV_PIPE_REMOVE) dt_ioppr_resync_modules_order(&dtb_S.dev);
    // takes dev->history_mutex, rebuilds nodes on REMOVE, resynchs on SYNCH
    dt_dev_pixelpipe_change(&p->pipe, &dtb_S.dev);
    dtb__tlog((what & DT_DEV_PIPE_REMOVE) ? "render.pipe_change_remove"
              : (what & DT_DEV_PIPE_SYNCH) ? "render.pipe_change_synch_all"
              : (what & DT_DEV_PIPE_TOP_CHANGED) ? "render.pipe_change_synch_top" : "render.pipe_change_other", t0);
  }
  return DTB_OK;
}

int dtb__full_pipe(void)
{
  return _pipe_ensure(&dtb_S.full, DT_MIPMAP_FULL);
}

dtb_pipe_t *dtb__full_pipe_ptr(void) { return &dtb_S.full; }

/* run the pipe like dt_imageio_export_with_flags(): gamma off, float output
 * converted with dt_display_to_8bit_pixels(). finalscale stays on for
 * scale >= 1 (darktable-cli's default "high quality") and is switched off
 * below 1 so downscaling happens early, as a non-hq export does. */
static int _pipe_run(dtb_pipe_t *p, int x, int y, int w, int h, float scale, uint8_t *out)
{
  dt_dev_pixelpipe_iop_t *finalscale = NULL;
  if(scale < 1.0f)
    for(GList *n = g_list_last(p->pipe.nodes); n; n = g_list_previous(n))
    {
      dt_dev_pixelpipe_iop_t *node = n->data;
      if(dt_iop_module_is_finalscale(node->module))
      {
        finalscale = node;
        break;
      }
    }

  g_atomic_pointer_set(&g_active_pipe, &p->pipe);
  // a cancel that came while the pipe was being synchronised
  if(g_atomic_int_get(&g_cancel))
  {
    g_atomic_pointer_set(&g_active_pipe, NULL);
    g_atomic_int_set(&g_cancel, 0);
    return dtb__err(DTB_E_CANCELLED, "render cancelled");
  }

  if(finalscale) finalscale->enabled = FALSE;
  const double t0 = dtb__t();
  const gboolean early = dt_dev_pixelpipe_process_no_gamma(&p->pipe, &dtb_S.dev, x, y, w, h, scale);
  dtb__tlog("render.process", t0);
  if(finalscale) finalscale->enabled = TRUE;

  g_atomic_pointer_set(&g_active_pipe, NULL);
  // as dt_dev_process_image_job() (develop.c) does after each run
  const int stopper = dt_atomic_exch_int(&p->pipe.shutdown, DT_DEV_PIXELPIPE_STOP_NO);

  // a cancel that came after the pipe's last module (the CAS in
  // dt_dev_pixelpipe_set_shutdown is then ignored) still cancels: the caller
  // asked for this render to be dropped
  if(g_atomic_int_get(&g_cancel))
  {
    g_atomic_int_set(&g_cancel, 0);
    return dtb__err(DTB_E_CANCELLED, "render cancelled");
  }
  if(early || stopper > DT_DEV_PIXELPIPE_PROCESSING)
    return dtb__err(DTB_E_ENGINE, "pixelpipe stopped early (%s)",
                    dt_dev_pixelpipe_shutdown_to_str(stopper));
  if(!p->pipe.backbuf || p->pipe.backbuf_width != w || p->pipe.backbuf_height != h)
    return dtb__err(DTB_E_ENGINE, "no valid output buffer (%dx%d, asked %dx%d)",
                    p->pipe.backbuf_width, p->pipe.backbuf_height, w, h);

  const float *in = (const float *)p->pipe.backbuf;
  const double t1 = dtb__t();
  DT_OMP_FOR()
  for(int row = 0; row < h; row++)
    dt_display_to_8bit_pixels(in + (size_t)4 * w * row, out + (size_t)4 * w * row, w, FALSE);
  dtb__tlog("render.to_8bit", t1);
  if(g_atomic_int_get(&g_cancel))
  {
    g_atomic_int_set(&g_cancel, 0);
    return dtb__err(DTB_E_CANCELLED, "render cancelled");
  }
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* where demosaic's input window lies (P1c item 3)                     */

/* The raw window demosaic is given for an output window: the pipe's
 * modify_roi_in() chain walked back from the end, as
 * _dev_pixelpipe_process_rec() (develop/pixelpipe_hb.c) does before it
 * processes, skipping the pieces it skips (_skip_piece_on_tags: switched
 * off or without an iop order; the export pipe is not "basic"). finalscale
 * is skipped below scale 1, as _pipe_run() switches it off there. FALSE when
 * the pipe has no demosaic (not a raw). *period: the CFA's repeat, 2 for
 * Bayer, 6 for X-Trans. */
static gboolean _demosaic_window(dtb_pipe_t *p, const int x, const int y, const int w, const int h,
                                 const float scale, dt_iop_roi_t *din, int *period)
{
  dt_iop_roi_t roi = { x, y, w, h, scale };
  for(GList *n = g_list_last(p->pipe.nodes); n; n = g_list_previous(n))
  {
    dt_dev_pixelpipe_iop_t *piece = n->data;
    dt_iop_module_t *m = piece->module;
    if(!piece->enabled || m->iop_order == INT_MAX) continue;
    if(scale < 1.0f && dt_iop_module_is_finalscale(m)) continue;
    dt_iop_roi_t in = roi;
    m->modify_roi_in(m, piece, &roi, &in);
    if(dt_iop_module_is(m, "demosaic"))
    {
      *din = in;
      const uint32_t f = p->pipe.dsc.filters;
      *period = f == 9u ? 6 : (f ? 2 : 1);
      return TRUE;
    }
    roi = in;
  }
  return FALSE;
}

static int _mod(const int a, const int n) { return ((a % n) + n) % n; }

/* Modules whose result near a window edge depends on pixels further away
 * than the fixed margin, that do not widen their own input window
 * (no modify_roi_in()), and for which a wider margin was measured to bring
 * a region to (or close to) the full render (P1c write-up, item 3, test T5
 * module sweep). Their reach is the overlap their own tiling_callback()
 * reports: the border darktable's tiler gives a tile so that a tiled result
 * equals an untiled one (develop/tiling.c). The callbacks of these modules
 * only compute from piece->data and the roi:
 *   shadhi.c, lowpass.c (4 sigma of the blur), soften.c, highpass.c (3 sigma
 *   of the box blur), bloom.c (5 x radius), denoiseprofile.c (patch +
 *   scattered neighbourhood), monochrome.c and bilat.c (4 sigma_s of the
 *   bilateral grid), bilateral.cc (the filter radius).
 * Not listed: modules that report an overlap without needing it here
 * (filmicrgb.c: 2^scales also with reconstruction off; atrous.c: exact with
 * the fixed margin), and modules whose result depends on the whole window
 * (defringe.c, globaltonemap.c), which no margin fixes. spektrafilm widens
 * its own input in modify_roi_in() (spektrafilm.c, by
 * SF_HALO_SIGMAS * _max_halo_sigma()). */
static const char *const _reach_modules[]
    = { "shadhi", "lowpass", "soften", "highpass", "bloom", "denoiseprofile", "monochrome", "bilat", "bilateral" };

/* The margin a region needs: the fixed 32 px that covers demosaic (its
 * border is private, see above), or more when a switched-on module of the
 * list above reaches further, capped at DTB_REGION_MARGIN_MAX. */
static int _region_margin(dtb_pipe_t *p, const float scale, const int pw, const int ph, const char **who)
{
  *who = NULL;
  const int o = _margin_override();
  if(o >= 0) return o;
  int reach = DTB_REGION_MARGIN_DEFAULT;
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
  {
    dt_dev_pixelpipe_iop_t *piece = n->data;
    dt_iop_module_t *m = piece->module;
    if(!piece->enabled || m->iop_order == INT_MAX || !m->tiling_callback || !piece->data) continue;
    gboolean listed = FALSE;
    for(size_t k = 0; k < G_N_ELEMENTS(_reach_modules) && !listed; k++)
      listed = dt_iop_module_is(m, _reach_modules[k]);
    if(!listed || dt_iop_module_modifies_roi_in(m)) continue;
    dt_develop_tiling_t t = { 0 };
    const dt_iop_roi_t roi = { 0, 0, pw, ph, scale };
    m->tiling_callback(m, piece, &roi, &roi, &t);
    if((int)t.overlap > reach)
    {
      reach = t.overlap;
      *who = m->op;
    }
  }
  return MIN(reach, DTB_REGION_MARGIN_MAX);
}

/* The window the full pipe renders for the region (x, y, w, h): the region
 * with the margin on every side that lies inside the image, then, for raw
 * images, moved so that demosaic's raw window starts on the same CFA phase
 * as in a full render. RCD demosaic (iop/demosaicing/rcd.c, rcd_demosaic)
 * gives different values when its window starts on the other phase (T5 on
 * the arw image: max 21 in one region, max 68 in another, depending on the
 * parity of the window's origin; PPG and AMaZE do not; P1c write-up). */
static void _region_window(dtb_pipe_t *p, const int x, const int y, const int w, const int h,
                           const float scale, const int pw, const int ph,
                           int *x0, int *y0, int *x1, int *y1)
{
  const char *who = NULL;
  const int m = _region_margin(p, scale, pw, ph, &who);
  *x0 = MAX(0, x - m);
  *y0 = MAX(0, y - m);
  *x1 = MIN(pw, x + w + m);
  *y1 = MIN(ph, y + h + m);
  if(g_getenv("DTB_DEBUG_ROI"))
    fprintf(stderr, "DTB_ROI margin %d (%s)\n", m, who ? who : "default");
  dt_iop_roi_t full, reg;
  int period = 1;
  if(!_demosaic_window(p, 0, 0, pw, ph, scale, &full, &period) || period <= 1) return;
  if(!_demosaic_window(p, *x0, *y0, *x1 - *x0, *y1 - *y0, scale, &reg, &period)) return;
  const int x0_in = *x0, y0_in = *y0;
  // the origin moves left / up by at most one period, one axis after the
  // other (each probe is one walk of modify_roi_in)
  for(int k = 1; k < 2 * period && _mod(reg.x - full.x, period) && *x0 > 0; k++)
  {
    (*x0)--;
    _demosaic_window(p, *x0, *y0, *x1 - *x0, *y1 - *y0, scale, &reg, &period);
  }
  for(int k = 1; k < 2 * period && _mod(reg.y - full.y, period) && *y0 > 0; k++)
  {
    (*y0)--;
    _demosaic_window(p, *x0, *y0, *x1 - *x0, *y1 - *y0, scale, &reg, &period);
  }
  if(g_getenv("DTB_DEBUG_ROI"))
    fprintf(stderr, "DTB_ROI region %d,%d %dx%d scale %.3f window %d,%d..%d,%d (moved %d,%d) demosaic in %d,%d %dx%d"
            " full %d,%d %dx%d period %d phase %s\n", x, y, w, h, scale, *x0, *y0, *x1, *y1, x0_in - *x0,
            y0_in - *y0, reg.x, reg.y, reg.width, reg.height, full.x, full.y, full.width, full.height, period,
            (_mod(reg.x - full.x, period) || _mod(reg.y - full.y, period)) ? "DIFFERENT" : "same");
}

int dtb_render_size(float scale, int *width, int *height)
{
  NEED_SESSION();
  if(!(scale > 0.0f)) return dtb__err(DTB_E_ARG, "scale must be > 0");
  const int r = _pipe_ensure(&dtb_S.full, DT_MIPMAP_FULL);
  if(r) return r;
  // as dt_imageio_export_with_flags: floor(scale * processed size)
  if(width) *width = (int)floor(scale * dtb_S.full.pipe.processed_width);
  if(height) *height = (int)floor(scale * dtb_S.full.pipe.processed_height);
  return DTB_OK;
}

int dtb_render_region(int x, int y, int w, int h, float scale, uint8_t *rgba8_out)
{
  NEED_SESSION();
  if(!rgba8_out || w <= 0 || h <= 0 || x < 0 || y < 0 || !(scale > 0.0f))
    return dtb__err(DTB_E_ARG, "bad region");
  // a cancel only concerns a render in flight: clear what is left over
  g_atomic_int_set(&g_cancel, 0);
  int r = _pipe_ensure(&dtb_S.full, DT_MIPMAP_FULL);
  if(r) return r;
  const int pw = (int)floor(scale * dtb_S.full.pipe.processed_width);
  const int ph = (int)floor(scale * dtb_S.full.pipe.processed_height);
  if(x + w > pw || y + h > ph)
    return dtb__err(DTB_E_ARG, "region %d,%d %dx%d outside %dx%d", x, y, w, h, pw, ph);

  // the region with the margin, clipped to the image, on the full render's
  // CFA phase
  int x0, y0, x1, y1;
  _region_window(&dtb_S.full, x, y, w, h, scale, pw, ph, &x0, &y0, &x1, &y1);
  if(x0 == x && y0 == y && x1 == x + w && y1 == y + h)
    return _pipe_run(&dtb_S.full, x, y, w, h, scale, rgba8_out);

  const int ow = x1 - x0, oh = y1 - y0;
  uint8_t *tmp = g_try_malloc((size_t)4 * ow * oh);
  if(!tmp) return dtb__err(DTB_E_ENGINE, "out of memory");
  r = _pipe_run(&dtb_S.full, x0, y0, ow, oh, scale, tmp);
  if(!r)
    for(int row = 0; row < h; row++)
      memcpy(rgba8_out + (size_t)4 * w * row,
             tmp + 4 * ((size_t)(y - y0 + row) * ow + (x - x0)), (size_t)4 * w);
  g_free(tmp);
  return r;
}

int dtb_render_preview(int max_size, uint8_t **out, int *width, int *height)
{
  if(out) *out = NULL;
  NEED_SESSION();
  if(!out || max_size <= 0) return dtb__err(DTB_E_ARG, "bad arguments");
  g_atomic_int_set(&g_cancel, 0);
  const double t0 = dtb__t();
  // DT_MIPMAP_F is the downscaled float input the darkroom's preview pipe
  // runs on (dt_dev_process_image_job, develop.c)
  int r = _pipe_ensure(&dtb_S.preview, DT_MIPMAP_F);
  if(r) return r;
  const int pw = dtb_S.preview.pipe.processed_width, ph = dtb_S.preview.pipe.processed_height;
  const float scale = fminf(1.0f, (float)max_size / (float)MAX(pw, ph));
  const int w = (int)floor(scale * pw), h = (int)floor(scale * ph);
  uint8_t *buf = g_try_malloc((size_t)4 * w * h);
  if(!buf) return dtb__err(DTB_E_ENGINE, "out of memory");
  r = _pipe_run(&dtb_S.preview, 0, 0, w, h, scale, buf);
  if(r)
  {
    g_free(buf);
    return r;
  }
  *out = buf;
  if(width) *width = w;
  if(height) *height = h;
  dtb__tlog("render.preview_total", t0);
  return DTB_OK;
}

void dtb_render_cancel(void)
{
  g_atomic_int_set(&g_cancel, 1);
  dt_dev_pixelpipe_t *p = g_atomic_pointer_get(&g_active_pipe);
  // CAS inside: ignored unless that pipe is processing
  if(p) dt_dev_pixelpipe_set_shutdown(p, DT_DEV_PIXELPIPE_STOP_DATA);
}

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
#include "common/image_cache.h"
#include "common/iop_order.h"
#include "develop/blend.h"
#include "develop/masks.h"
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
  if(p->shape_hash) g_hash_table_destroy(p->shape_hash);
  p->shape_hash = NULL;
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

/* P1b part 2: a piece's hash includes its mask's shapes, but
 * dt_iop_commit_params() (develop/imageop.c) looks the mask group up in the
 * global develop object (dt_masks_get_from_id(darktable.develop, ...) and
 * dt_masks_group_hash, which walks darktable.develop->forms), not in the
 * pipe's own. In the bridge that object is not the session's and has no
 * forms, so a change of a shape's geometry left the hash, and the cached
 * output of the modules using it, as they were (engine test C22 found it: a
 * shape moved after it was attached rendered at its old place until the
 * edit was opened again). While a pipe synchronises its pieces, the global
 * develop object's form list is the session's; nothing else runs then. */
static GList *_forms_swap_in(void)
{
  GList *saved = darktable.develop ? darktable.develop->forms : NULL;
  if(darktable.develop) darktable.develop->forms = dtb_S.dev.forms;
  return saved;
}

static void _forms_swap_out(GList *saved)
{
  if(darktable.develop) darktable.develop->forms = saved;
}

/* P3m: dt_iop_commit_params() (develop/imageop.c) puts a piece's mask group
 * into its hash only while the module "is blending": its blend mask_mode is
 * not off, or it is the focused module (dt_dev_gui_module()). Retouch
 * (IOP_FLAGS_NO_MASKS) keeps mask_mode off and holds its shapes in the group
 * named by its blend mask_id; its params list only the shapes' ids and
 * settings. On desktop the module is focused whenever a shape is edited, so
 * the shapes are in the hash; the bridge has no focused module, so moving a
 * shape or its source left the hash, and the cached output, as they were
 * (engine test C32: "moving the source changes the picture"). After each
 * synchronisation the group's shapes are added to such a piece's hash here.
 * The hash a commit gave is kept, so a piece that was not committed again
 * gets the same addition again, never a second one. */
typedef struct dtb_shape_hash_t
{
  dt_hash_t base, with_shapes;
} dtb_shape_hash_t;

static void _shapes_into_hash(dtb_pipe_t *p)
{
  GHashTable *next = g_hash_table_new_full(NULL, NULL, NULL, g_free);
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
  {
    dt_dev_pixelpipe_iop_t *piece = n->data;
    const dt_develop_blend_params_t *bp = piece->blendop_data;
    if(!piece->enabled || !bp || !(piece->module->flags() & IOP_FLAGS_SUPPORTS_BLENDING)
       || bp->mask_mode != DEVELOP_MASK_DISABLED)
      continue;  // off, or the commit already hashed the group (is_blending)
    dt_masks_form_t *grp = dt_masks_get_from_id_ext(dtb_S.dev.forms, bp->mask_id);
    dt_masks_form_t *bgrp = dt_masks_get_from_id_ext(dtb_S.dev.forms, bp->blend_mask_id);
    if(!grp && !bgrp) continue;
    const dtb_shape_hash_t *old = p->shape_hash ? g_hash_table_lookup(p->shape_hash, piece) : NULL;
    dtb_shape_hash_t *h = g_new(dtb_shape_hash_t, 1);
    h->base = (old && old->with_shapes == piece->hash) ? old->base : piece->hash;
    dt_hash_t v = h->base;
    if(grp) v = dt_masks_group_hash_ext(v, grp, dtb_S.dev.forms);
    if(bgrp) v = dt_masks_group_hash_ext(v, bgrp, dtb_S.dev.forms);
    h->with_shapes = piece->hash = v;
    g_hash_table_insert(next, piece, h);
  }
  if(p->shape_hash) g_hash_table_destroy(p->shape_hash);
  p->shape_hash = next;
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
    // E1 (P1b): never start a pipe on an empty input, nor on the static
    // placeholder picture darktable hands out as the downscaled buffer of an
    // image whose loader failed (common/mipmap_cache.c, _dead_image_f and
    // its siblings): dtb_open refuses such files, this is the second line
    const dt_image_t *img = dt_image_cache_get(dtb_S.imgid, 'r');
    const dt_imageio_retval_t status = img ? img->load_status : DT_IMAGEIO_FILE_NOT_FOUND;
    if(img) dt_image_cache_read_release(img);
    if(!p->buf.buf || !p->buf.width || !p->buf.height || status != DT_IMAGEIO_OK)
    {
      dt_mipmap_cache_release(&p->buf);
      return dtb__err(DTB_E_ENGINE, "image could not be loaded (mipmap %d, %d x %d, load status %d)", size,
                      p->buf.width, p->buf.height, (int)status);
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
    GList *global_forms = _forms_swap_in();
    dt_dev_pixelpipe_synch_all(&p->pipe, &dtb_S.dev);
    _forms_swap_out(global_forms);
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
    GList *global_forms = _forms_swap_in();
    dt_dev_pixelpipe_change(&p->pipe, &dtb_S.dev);
    _forms_swap_out(global_forms);
    dtb__tlog((what & DT_DEV_PIPE_REMOVE) ? "render.pipe_change_remove"
              : (what & DT_DEV_PIPE_SYNCH) ? "render.pipe_change_synch_all"
              : (what & DT_DEV_PIPE_TOP_CHANGED) ? "render.pipe_change_synch_top" : "render.pipe_change_other", t0);
  }
  // also when nothing was synchronised: a shape can change with no history step the pipe sees
  _shapes_into_hash(p);
  // P3n: rotate and perspective while it has the focus (perspective.c)
  dtb__ashift_after_sync(p);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* render options (P1b part 2, engine-api.md 6.1)                      */

void dtb__render_opts_clear(dtb_render_opts_t *o)
{
  if(o->bypass) g_ptr_array_free(o->bypass, TRUE);
  o->bypass = NULL;
  o->show_mask = NULL;
  o->history_end = -1;
}

static int _instance_ref(JsonNode *n, const char *what, dt_iop_module_t **out)
{
  JsonObject *r = n && JSON_NODE_HOLDS_OBJECT(n) ? json_node_get_object(n) : NULL;
  if(!r || !json_object_has_member(r, "op") || !json_object_has_member(r, "priority"))
    return dtb__err(DTB_E_ARG, "%s: expected {\"op\": s, \"priority\": n}", what);
  JsonNode *op = json_object_get_member(r, "op"), *pr = json_object_get_member(r, "priority");
  if(!JSON_NODE_HOLDS_VALUE(op) || json_node_get_value_type(op) != G_TYPE_STRING || !JSON_NODE_HOLDS_VALUE(pr)
     || json_node_get_value_type(pr) != G_TYPE_INT64)
    return dtb__err(DTB_E_TYPE, "%s: op must be a string and priority an integer", what);
  const char *ops = json_node_get_string(op);
  const int prio = (int)json_node_get_int(pr);
  dt_iop_module_t *m = dtb__find(ops, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "%s: no instance %s %d", what, ops, prio);
  *out = m;
  return DTB_OK;
}

int dtb__render_opts_from(JsonObject *o, dtb_render_opts_t *out)
{
  memset(out, 0, sizeof(*out));
  out->history_end = -1;
  if(!o) return DTB_OK;
  if(json_object_has_member(o, "bypass"))
  {
    JsonNode *n = json_object_get_member(o, "bypass");
    if(!JSON_NODE_HOLDS_NULL(n))
    {
      if(!JSON_NODE_HOLDS_ARRAY(n)) return dtb__err(DTB_E_TYPE, "bypass must be an array");
      JsonArray *a = json_node_get_array(n);
      out->bypass = g_ptr_array_new();
      for(guint i = 0; i < json_array_get_length(a); i++)
      {
        dt_iop_module_t *m = NULL;
        const int r = _instance_ref(json_array_get_element(a, i), "bypass", &m);
        if(r)
        {
          dtb__render_opts_clear(out);
          return r;
        }
        // the pipe cannot run without the always-on modules (no switch on desktop)
        if(m->hide_enable_button)
        {
          dtb__render_opts_clear(out);
          return dtb__err(DTB_E_ARG, "%s cannot be left out: it has no on/off switch", m->op);
        }
        g_ptr_array_add(out->bypass, m);
      }
    }
  }
  if(json_object_has_member(o, "show_mask"))
  {
    JsonNode *n = json_object_get_member(o, "show_mask");
    if(!JSON_NODE_HOLDS_NULL(n))
    {
      const int r = _instance_ref(n, "show_mask", &out->show_mask);
      if(r)
      {
        dtb__render_opts_clear(out);
        return r;
      }
    }
  }
  return DTB_OK;
}

int dtb__render_opts_parse(const char *json, dtb_render_opts_t *out)
{
  memset(out, 0, sizeof(*out));
  out->history_end = -1;  // P3h: none (0 is the original)
  if(!json || !*json) return DTB_OK;
  JsonNode *root = dtb__json_parse(json);
  if(!root) return DTB_E_ARG;
  if(!JSON_NODE_HOLDS_OBJECT(root))
  {
    json_node_unref(root);
    return dtb__err(DTB_E_ARG, "render options must be a JSON object");
  }
  JsonObject *obj = json_node_get_object(root);
  int r = dtb__render_opts_from(obj, out);
  // P3h: only the render calls take a history position (engine-api.md 8)
  if(!r && json_object_has_member(obj, "history_end"))
  {
    JsonNode *n = json_object_get_member(obj, "history_end");
    if(!JSON_NODE_HOLDS_NULL(n))
    {
      if(!JSON_NODE_HOLDS_VALUE(n) || json_node_get_value_type(n) != G_TYPE_INT64)
        r = dtb__err(DTB_E_TYPE, "history_end must be an integer");
      else
      {
        const gint64 e = json_node_get_int(n);
        const int rows = dtb_S.rows ? (int)dtb_S.rows->len : (int)g_list_length(dtb_S.dev.history);
        if(e < 0 || e > rows)
          r = dtb__err(DTB_E_ARG, "history_end %d outside 0..%d", (int)e, rows);
        else
          out->history_end = (int)e;
      }
      if(r) dtb__render_opts_clear(out);
    }
  }
  json_node_unref(root);
  return r;
}

gboolean dtb__render_opts_empty(const dtb_render_opts_t *o)
{
  return !o || ((!o->bypass || o->bypass->len == 0) && !o->show_mask && o->history_end < 0);
}


/* Leave the bypassed instances out of the (synchronised) pipe for one
 * render: their pieces are switched off, as dt_dev_pixelpipe_synch() does for
 * a module that is off, and their hash is changed. The pipe cache's hash
 * (pixelpipe_cache.c, _dev_pixelpipe_cache_basichash) includes a piece's hash
 * whenever its module is on ("module->enabled || piece->enabled"), so without
 * the changed hash the modules after it would find the cache lines of the
 * render with the instance in. Nothing outside the pipe changes: the develop
 * object, its history and the modules keep their state. dtb__bypass_end()
 * puts the pieces and the processed size back. */
void dtb__bypass_begin(dtb_pipe_t *p, const dtb_render_opts_t *o, dtb_bypass_t *saved)
{
  memset(saved, 0, sizeof(*saved));
  saved->pipe = p;
  saved->processed_width = p->pipe.processed_width;
  saved->processed_height = p->pipe.processed_height;
  if(!o || !o->bypass || o->bypass->len == 0) return;
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
  {
    dt_dev_pixelpipe_iop_t *piece = n->data;
    gboolean hit = FALSE;
    for(guint i = 0; i < o->bypass->len && !hit; i++) hit = piece->module == g_ptr_array_index(o->bypass, i);
    if(!hit || !piece->enabled || saved->n >= DTB_BYPASS_MAX) continue;
    saved->piece[saved->n] = piece;
    saved->hash[saved->n] = piece->hash;
    saved->n++;
    piece->enabled = FALSE;
    static const char tag[] = "dtbridge bypass";
    piece->hash = dt_hash(piece->hash, tag, sizeof(tag));
  }
  if(saved->n)
    dt_dev_pixelpipe_get_dimensions(&p->pipe, &dtb_S.dev, p->pipe.iwidth, p->pipe.iheight,
                                    &p->pipe.processed_width, &p->pipe.processed_height);
}

void dtb__bypass_end(dtb_bypass_t *saved)
{
  if(!saved->pipe) return;
  for(int i = 0; i < saved->n; i++)
  {
    saved->piece[i]->enabled = TRUE;
    saved->piece[i]->hash = saved->hash[i];
  }
  // again over every piece: dt_dev_pixelpipe_get_dimensions() also sets
  // each piece's buf_in / buf_out (the distort transforms read them), and
  // some modules keep values of their modify_roi_out() in piece->data
  if(saved->n)
    dt_dev_pixelpipe_get_dimensions(&saved->pipe->pipe, &dtb_S.dev, saved->pipe->pipe.iwidth,
                                    saved->pipe->pipe.iheight, &saved->pipe->pipe.processed_width,
                                    &saved->pipe->pipe.processed_height);
  saved->pipe = NULL;
  saved->n = 0;
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
  // desktop's mask overlay is drawn by gamma (maskdisplay.c)
  const gboolean early = dtb__mask_display_active
                           ? dt_dev_pixelpipe_process(&p->pipe, &dtb_S.dev, x, y, w, h, scale, DT_DEVICE_NONE)
                           : dt_dev_pixelpipe_process_no_gamma(&p->pipe, &dtb_S.dev, x, y, w, h, scale);
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

int dtb__pipe_run(dtb_pipe_t *p, int x, int y, int w, int h, float scale, uint8_t *out)
{
  g_atomic_int_set(&g_cancel, 0);
  return _pipe_run(p, x, y, w, h, scale, out);
}

int dtb__pipe_ready(const gboolean full, dtb_pipe_t **out)
{
  dtb_pipe_t *p = full ? &dtb_S.full : &dtb_S.preview;
  const int r = _pipe_ensure(p, full ? DT_MIPMAP_FULL : DT_MIPMAP_F);
  if(!r && out) *out = p;
  return r;
}

int dtb_render_size_ex(float scale, const char *options_json, int *width, int *height)
{
  NEED_SESSION();
  if(!(scale > 0.0f)) return dtb__err(DTB_E_ARG, "scale must be > 0");
  dtb_render_opts_t o;
  int r = dtb__render_opts_parse(options_json, &o);
  if(r) return r;
  dtb_history_at_t at;
  dtb__history_at_begin(&o, &at);
  r = _pipe_ensure(&dtb_S.full, DT_MIPMAP_FULL);
  if(r)
  {
    dtb__history_at_end(&at);
    dtb__render_opts_clear(&o);
    return r;
  }
  dtb_bypass_t saved;
  dtb__bypass_begin(&dtb_S.full, &o, &saved);
  // as dt_imageio_export_with_flags: floor(scale * processed size)
  if(width) *width = (int)floor(scale * dtb_S.full.pipe.processed_width);
  if(height) *height = (int)floor(scale * dtb_S.full.pipe.processed_height);
  dtb__bypass_end(&saved);
  dtb__history_at_end(&at);
  dtb__render_opts_clear(&o);
  return DTB_OK;
}

int dtb_render_size(float scale, int *width, int *height)
{
  return dtb_render_size_ex(scale, NULL, width, height);
}

/* the region on a pipe with the options applied */
static int _region(int x, int y, int w, int h, float scale, uint8_t *rgba8_out)
{
  const int pw = (int)floor(scale * dtb_S.full.pipe.processed_width);
  const int ph = (int)floor(scale * dtb_S.full.pipe.processed_height);
  if(x + w > pw || y + h > ph)
    return dtb__err(DTB_E_ARG, "region %d,%d %dx%d outside %dx%d", x, y, w, h, pw, ph);

  // the region with the margin, clipped to the image, on the full render's
  // CFA phase
  int x0, y0, x1, y1;
  _region_window(&dtb_S.full, x, y, w, h, scale, pw, ph, &x0, &y0, &x1, &y1);
  dtb__times_begin("full");
  int r;
  if(x0 == x && y0 == y && x1 == x + w && y1 == y + h)
  {
    r = _pipe_run(&dtb_S.full, x, y, w, h, scale, rgba8_out);
    dtb__times_end();
    return r;
  }

  const int ow = x1 - x0, oh = y1 - y0;
  uint8_t *tmp = g_try_malloc((size_t)4 * ow * oh);
  if(!tmp)
  {
    dtb__times_end();
    return dtb__err(DTB_E_ENGINE, "out of memory");
  }
  r = _pipe_run(&dtb_S.full, x0, y0, ow, oh, scale, tmp);
  if(!r)
    for(int row = 0; row < h; row++)
      memcpy(rgba8_out + (size_t)4 * w * row,
             tmp + 4 * ((size_t)(y - y0 + row) * ow + (x - x0)), (size_t)4 * w);
  g_free(tmp);
  dtb__times_end();
  return r;
}

int dtb_render_region_ex(int x, int y, int w, int h, float scale, const char *options_json, uint8_t *rgba8_out)
{
  NEED_SESSION();
  if(!rgba8_out || w <= 0 || h <= 0 || x < 0 || y < 0 || !(scale > 0.0f))
    return dtb__err(DTB_E_ARG, "bad region");
  dtb_render_opts_t o;
  int r = dtb__render_opts_parse(options_json, &o);
  if(r) return r;
  // a cancel only concerns a render in flight: clear what is left over
  g_atomic_int_set(&g_cancel, 0);
  dtb_history_at_t at;
  dtb__history_at_begin(&o, &at);
  r = _pipe_ensure(&dtb_S.full, DT_MIPMAP_FULL);
  if(!r)
  {
    dtb_bypass_t saved;
    dtb_mask_display_t md;
    r = dtb__mask_display_begin(&dtb_S.full, &o, &md);
    if(!r)
    {
      dtb__bypass_begin(&dtb_S.full, &o, &saved);
      r = _region(x, y, w, h, scale, rgba8_out);
      dtb__bypass_end(&saved);
      dtb__mask_display_end(&md);
    }
  }
  dtb__history_at_end(&at);
  dtb__render_opts_clear(&o);
  return r;
}

int dtb_render_region(int x, int y, int w, int h, float scale, uint8_t *rgba8_out)
{
  return dtb_render_region_ex(x, y, w, h, scale, NULL, rgba8_out);
}

int dtb_render_preview_ex(int max_size, const char *options_json, uint8_t **out, int *width, int *height)
{
  if(out) *out = NULL;
  NEED_SESSION();
  if(!out || max_size <= 0) return dtb__err(DTB_E_ARG, "bad arguments");
  dtb_render_opts_t o;
  int r = dtb__render_opts_parse(options_json, &o);
  if(r) return r;
  g_atomic_int_set(&g_cancel, 0);
  const double t0 = dtb__t();
  // DT_MIPMAP_F is the downscaled float input the darkroom's preview pipe
  // runs on (dt_dev_process_image_job, develop.c)
  dtb_history_at_t at;
  dtb__history_at_begin(&o, &at);
  r = _pipe_ensure(&dtb_S.preview, DT_MIPMAP_F);
  if(r)
  {
    dtb__history_at_end(&at);
    dtb__render_opts_clear(&o);
    return r;
  }
  dtb_mask_display_t md;
  r = dtb__mask_display_begin(&dtb_S.preview, &o, &md);
  if(r)
  {
    dtb__history_at_end(&at);
    dtb__render_opts_clear(&o);
    return r;
  }
  dtb_bypass_t saved;
  dtb__bypass_begin(&dtb_S.preview, &o, &saved);
  const int pw = dtb_S.preview.pipe.processed_width, ph = dtb_S.preview.pipe.processed_height;
  const float scale = fminf(1.0f, (float)max_size / (float)MAX(pw, ph));
  const int w = (int)floor(scale * pw), h = (int)floor(scale * ph);
  uint8_t *buf = g_try_malloc((size_t)4 * MAX(1, w) * MAX(1, h));
  if(!buf)
    r = dtb__err(DTB_E_ENGINE, "out of memory");
  else
  {
    dtb__times_begin("preview");
    r = _pipe_run(&dtb_S.preview, 0, 0, w, h, scale, buf);
    dtb__times_end();
  }
  dtb__bypass_end(&saved);
  dtb__mask_display_end(&md);
  dtb__history_at_end(&at);
  dtb__render_opts_clear(&o);
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

int dtb_render_preview(int max_size, uint8_t **out, int *width, int *height)
{
  return dtb_render_preview_ex(max_size, NULL, out, width, height);
}

void dtb_render_cancel(void)
{
  g_atomic_int_set(&g_cancel, 1);
  dt_dev_pixelpipe_t *p = g_atomic_pointer_get(&g_active_pipe);
  // CAS inside: ignored unless that pipe is processing
  if(p) dt_dev_pixelpipe_set_shutdown(p, DT_DEV_PIXELPIPE_STOP_DATA);
}

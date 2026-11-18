// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge prototype (brief S3): drives upstream darktable, started
 * without its desktop interface, through the API in dtbridge.h.
 *
 * Where upstream code assumes the desktop interface, the bridge does the
 * non-interface part of that code itself; each place says which upstream
 * function it follows. Nothing in upstream is changed.
 */
#include "dtbridge.h"
#include "dtb_internal.h"

#include "common/darktable.h"
#include "common/debug.h"
#include "common/exif.h"
#include "common/film.h"
#include "common/history.h"
#include "common/image.h"
#include "common/image_cache.h"
#include "common/introspection.h"
#include "common/iop_order.h"
#include "common/mipmap_cache.h"
#include "control/conf.h"
#include "develop/blend.h"
#include "develop/develop.h"
#include "develop/imageop.h"
#include "develop/masks.h"
#include "develop/pixelpipe_hb.h"
#include "imageio/imageio_common.h"
#include "imageio/imageio_module.h"

#include <json-glib/json-glib.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* state                                                              */

typedef struct dtb_pipe_t
{
  gboolean ready;
  dt_dev_pixelpipe_t pipe;
  dt_mipmap_buffer_t buf;
  dt_mipmap_size_t size;
} dtb_pipe_t;

typedef struct dtb_session_t
{
  gboolean open;
  dt_imgid_t imgid;
  dt_develop_t dev;
  gboolean read_only;
  gboolean seal_next;  // next history change starts a new item
  dtb_pipe_t full;     // regions and full renders, from the full-size input
  dtb_pipe_t preview;  // whole-image preview, from the downscaled input
} dtb_session_t;

static gboolean g_inited = FALSE;
static dtb_session_t S;
static char g_err[2048];
static gint g_cancel = 0;
static gpointer g_active_pipe = NULL;  // dt_dev_pixelpipe_t * of the render in flight
static gchar *g_argv_store[32];

int dtb__err(int code, const char *fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(g_err, sizeof(g_err), fmt, ap);
  va_end(ap);
  return code;
}

const char *dtb_last_error(void) { return g_err; }
const char *dtb_engine_version(void) { return darktable_package_version; }
void dtb_free(void *p) { g_free(p); }

#define NEED_INIT()                                                     \
  do { if(!g_inited) return dtb__err(DTB_E_STATE, "engine not initialised"); } while(0)
#define NEED_SESSION()                                                  \
  do { NEED_INIT();                                                     \
       if(!S.open) return dtb__err(DTB_E_STATE, "no image open"); } while(0)

/* ------------------------------------------------------------------ */
/* lifecycle                                                          */

int dtb_init(const dtb_paths_t *p)
{
  if(g_inited) return dtb__err(DTB_E_STATE, "already initialised");
  if(!p || !p->datadir || !p->moduledir || !p->configdir || !p->cachedir || !p->library)
    return dtb__err(DTB_E_ARG, "configdir, cachedir, datadir, moduledir and library are required");

  int argc = 0;
  gchar **a = g_argv_store;
  a[argc++] = g_strdup("dtbridge");
  a[argc++] = g_strdup("--library");    a[argc++] = g_strdup(p->library);
  a[argc++] = g_strdup("--configdir");  a[argc++] = g_strdup(p->configdir);
  a[argc++] = g_strdup("--cachedir");   a[argc++] = g_strdup(p->cachedir);
  a[argc++] = g_strdup("--datadir");    a[argc++] = g_strdup(p->datadir);
  a[argc++] = g_strdup("--moduledir");  a[argc++] = g_strdup(p->moduledir);
  if(p->tmpdir) { a[argc++] = g_strdup("--tmpdir"); a[argc++] = g_strdup(p->tmpdir); }
  if(p->threads > 0)
  {
    a[argc++] = g_strdup("--threads");
    a[argc++] = g_strdup_printf("%d", p->threads);
  }
  // as darktable-cli (cli/main.c): the engine itself never writes a sidecar;
  // only dtb_xmp_write() does, after the version guard
  a[argc++] = g_strdup("--conf");
  a[argc++] = g_strdup("write_sidecar_files=never");
  a[argc] = NULL;

  g_mkdir_with_parents(p->configdir, 0700);
  g_mkdir_with_parents(p->cachedir, 0700);
  if(p->tmpdir) g_mkdir_with_parents(p->tmpdir, 0700);

  // init_gui FALSE as darktable-cli; load_data TRUE = darktable-cli's
  // default (custom_presets), so presets and styles come from data.db
  if(dt_init(argc, a, FALSE, TRUE, NULL))
    return dtb__err(DTB_E_ENGINE, "dt_init failed");

  memset(&S, 0, sizeof(S));
  g_inited = TRUE;
  return DTB_OK;
}

void dtb_shutdown(void)
{
  if(!g_inited) return;
  if(S.open) dtb_close();
  dt_cleanup();
  g_inited = FALSE;
  for(int i = 0; g_argv_store[i]; i++)
  {
    g_free(g_argv_store[i]);
    g_argv_store[i] = NULL;
  }
}

/* ------------------------------------------------------------------ */
/* version guard                                                      */

char *dtb_xmp_check(const char *xmp_path, int *n_problems)
{
  if(n_problems) *n_problems = -1;
  if(!g_inited)
  {
    dtb__err(DTB_E_STATE, "engine not initialised");
    return NULL;
  }
  char *r = dtb__xmp_check(xmp_path, n_problems);
  if(!r) dtb__err(DTB_E_IO, "cannot read %s", xmp_path ? xmp_path : "(null)");
  return r;
}

/* ------------------------------------------------------------------ */
/* pipelines                                                          */

static void _pipe_free(dtb_pipe_t *p)
{
  if(!p->ready) return;
  dt_dev_pixelpipe_cleanup(&p->pipe);
  dt_mipmap_cache_release(&p->buf);
  p->ready = FALSE;
}

static void _pipes_dirty(const dt_dev_pixelpipe_change_t what)
{
  if(S.full.ready) S.full.pipe.changed |= what;
  if(S.preview.ready) S.preview.pipe.changed |= what;
}

/* Set up a cached pipeline the way dt_imageio_export_with_flags()
 * (imageio/imageio.c) does for an export: same pipe type, so every module
 * takes its export code path, and the same input. Unlike the export pipe,
 * it gets the darkroom's cache size (dt_dev_pixelpipe_init: 64 lines), so
 * a re-render re-runs only the modules after the first changed one. */
static int _pipe_ensure(dtb_pipe_t *p, const dt_mipmap_size_t size)
{
  if(!p->ready)
  {
    p->size = size;
    dt_mipmap_cache_get(&p->buf, S.imgid, size, DT_MIPMAP_BLOCKING, 'r');
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
    dt_dev_pixelpipe_set_input(&p->pipe, &S.dev, (float *)p->buf.buf,
                               p->buf.width, p->buf.height, p->buf.iscale);
    dt_ioppr_resync_modules_order(&S.dev);
    dt_dev_pixelpipe_create_nodes(&p->pipe, &S.dev);
    dt_dev_pixelpipe_synch_all(&p->pipe, &S.dev);
    dt_dev_pixelpipe_get_dimensions(&p->pipe, &S.dev, p->pipe.iwidth, p->pipe.iheight,
                                    &p->pipe.processed_width, &p->pipe.processed_height);
    p->pipe.changed = DT_DEV_PIPE_UNCHANGED;
    p->ready = TRUE;
  }
  else if(p->pipe.changed != DT_DEV_PIPE_UNCHANGED)
  {
    if(p->pipe.changed & DT_DEV_PIPE_REMOVE) dt_ioppr_resync_modules_order(&S.dev);
    // takes dev->history_mutex, rebuilds nodes on REMOVE, resynchs on SYNCH
    dt_dev_pixelpipe_change(&p->pipe, &S.dev);
  }
  return DTB_OK;
}

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

  // a cancel only concerns a render in flight: clear what is left over
  g_atomic_int_set(&g_cancel, 0);
  g_atomic_pointer_set(&g_active_pipe, &p->pipe);

  if(finalscale) finalscale->enabled = FALSE;
  const gboolean early = dt_dev_pixelpipe_process_no_gamma(&p->pipe, &S.dev, x, y, w, h, scale);
  if(finalscale) finalscale->enabled = TRUE;

  g_atomic_pointer_set(&g_active_pipe, NULL);
  // as dt_dev_process_image_job() (develop.c) does after each run
  const int stopper = dt_atomic_exch_int(&p->pipe.shutdown, DT_DEV_PIXELPIPE_STOP_NO);
  const gboolean cancelled = g_atomic_int_get(&g_cancel);
  g_atomic_int_set(&g_cancel, 0);

  if(early || stopper > DT_DEV_PIXELPIPE_PROCESSING)
  {
    if(cancelled) return dtb__err(DTB_E_CANCELLED, "render cancelled");
    return dtb__err(DTB_E_ENGINE, "pixelpipe stopped early (%s)",
                    dt_dev_pixelpipe_shutdown_to_str(stopper));
  }
  if(!p->pipe.backbuf || p->pipe.backbuf_width != w || p->pipe.backbuf_height != h)
    return dtb__err(DTB_E_ENGINE, "no valid output buffer (%dx%d, asked %dx%d)",
                    p->pipe.backbuf_width, p->pipe.backbuf_height, w, h);

  const float *in = (const float *)p->pipe.backbuf;
  DT_OMP_FOR()
  for(int row = 0; row < h; row++)
    dt_display_to_8bit_pixels(in + (size_t)4 * w * row, out + (size_t)4 * w * row, w, FALSE);
  return DTB_OK;
}

int dtb_render_size(float scale, int *width, int *height)
{
  NEED_SESSION();
  if(scale <= 0.0f) return dtb__err(DTB_E_ARG, "scale must be > 0");
  const int r = _pipe_ensure(&S.full, DT_MIPMAP_FULL);
  if(r) return r;
  // as dt_imageio_export_with_flags: floor(scale * processed size)
  if(width) *width = (int)floor(scale * S.full.pipe.processed_width);
  if(height) *height = (int)floor(scale * S.full.pipe.processed_height);
  return DTB_OK;
}

int dtb_render(int x, int y, int w, int h, float scale, uint8_t *rgba8_out)
{
  NEED_SESSION();
  if(!rgba8_out || w <= 0 || h <= 0 || x < 0 || y < 0 || scale <= 0.0f)
    return dtb__err(DTB_E_ARG, "bad region");
  int r = _pipe_ensure(&S.full, DT_MIPMAP_FULL);
  if(r) return r;
  const int pw = (int)floor(scale * S.full.pipe.processed_width);
  const int ph = (int)floor(scale * S.full.pipe.processed_height);
  if(x + w > pw || y + h > ph)
    return dtb__err(DTB_E_ARG, "region %d,%d %dx%d outside %dx%d", x, y, w, h, pw, ph);
  return _pipe_run(&S.full, x, y, w, h, scale, rgba8_out);
}

int dtb_render_preview(int max_size, uint8_t **out, int *width, int *height)
{
  NEED_SESSION();
  if(!out || max_size <= 0) return dtb__err(DTB_E_ARG, "bad arguments");
  *out = NULL;
  // DT_MIPMAP_F is the downscaled float input the darkroom's preview pipe
  // runs on (dt_dev_process_image_job, develop.c)
  int r = _pipe_ensure(&S.preview, DT_MIPMAP_F);
  if(r) return r;
  const int pw = S.preview.pipe.processed_width, ph = S.preview.pipe.processed_height;
  const float scale = fminf(1.0f, (float)max_size / (float)MAX(pw, ph));
  const int w = (int)floor(scale * pw), h = (int)floor(scale * ph);
  uint8_t *buf = g_try_malloc((size_t)4 * w * h);
  if(!buf) return dtb__err(DTB_E_ENGINE, "out of memory");
  r = _pipe_run(&S.preview, 0, 0, w, h, scale, buf);
  if(r)
  {
    g_free(buf);
    return r;
  }
  *out = buf;
  if(width) *width = w;
  if(height) *height = h;
  return DTB_OK;
}

void dtb_render_cancel(void)
{
  g_atomic_int_set(&g_cancel, 1);
  dt_dev_pixelpipe_t *p = g_atomic_pointer_get(&g_active_pipe);
  // CAS inside: ignored unless that pipe is processing
  if(p) dt_dev_pixelpipe_set_shutdown(p, DT_DEV_PIXELPIPE_STOP_DATA);
}

/* ------------------------------------------------------------------ */
/* session                                                            */

int dtb_open(const char *raw_path, const char *xmp_path)
{
  NEED_INIT();
  if(S.open) return dtb__err(DTB_E_STATE, "an image is already open");
  if(!raw_path || !g_file_test(raw_path, G_FILE_TEST_IS_REGULAR))
    return dtb__err(DTB_E_IO, "cannot read %s", raw_path ? raw_path : "(null)");

  // the guard first, on the XMP that will be loaded
  gchar *sidecar = xmp_path ? g_strdup(xmp_path) : g_strconcat(raw_path, ".xmp", NULL);
  gboolean read_only = FALSE;
  if(g_file_test(sidecar, G_FILE_TEST_IS_REGULAR))
  {
    int n = 0;
    g_free(dtb__xmp_check(sidecar, &n));
    read_only = (n != 0);
  }
  else if(xmp_path)
  {
    g_free(sidecar);
    return dtb__err(DTB_E_IO, "cannot read %s", xmp_path);
  }
  g_free(sidecar);

  gchar *dir = g_path_get_dirname(raw_path);
  dt_film_t film;
  dt_film_init(&film);
  const dt_filmid_t filmid = dt_film_new(&film, dir);
  dt_film_cleanup(&film);
  g_free(dir);
  if(!dt_is_valid_filmid(filmid)) return dtb__err(DTB_E_ENGINE, "cannot create film roll");

  gchar *base = g_path_get_basename(raw_path);
  dt_imgid_t imgid = dt_image_get_id(filmid, base);
  g_free(base);
  if(!dt_is_valid_imgid(imgid))
    imgid = dt_image_import(filmid, raw_path, TRUE, FALSE);
  if(!dt_is_valid_imgid(imgid)) return dtb__err(DTB_E_ENGINE, "cannot import %s", raw_path);

  // as darktable-cli: read the XMP into the library, replacing the history
  if(xmp_path)
  {
    dt_image_t *img = dt_image_cache_get(imgid, 'w');
    const gboolean failed = dt_exif_xmp_read(img, xmp_path, FALSE);
    dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
    if(failed) return dtb__err(DTB_E_IO, "cannot load XMP %s", xmp_path);
  }

  memset(&S, 0, sizeof(S));
  S.imgid = imgid;
  S.read_only = read_only;
  dt_dev_init(&S.dev, FALSE);
  dt_dev_load_image(&S.dev, imgid);
  // dt_dev_load_image reads the history but does not apply it to the
  // modules (the darkroom does that later); as darktable-mcp does
  dt_dev_pop_history_items_ext(&S.dev, S.dev.history_end);
  S.seal_next = TRUE;
  S.open = TRUE;
  return read_only ? dtb__err(DTB_OK, "opened read-only: XMP failed the version guard") : DTB_OK;
}

int dtb_close(void)
{
  NEED_SESSION();
  _pipe_free(&S.full);
  _pipe_free(&S.preview);
  dt_dev_cleanup(&S.dev);
  S.open = FALSE;
  return DTB_OK;
}

int dtb_is_read_only(void) { return S.open && S.read_only; }

/* tear the develop object down and load it again from the library, after
 * the library history was changed underneath it (compress) */
static void _reload_dev(void)
{
  if(S.full.ready) dt_dev_pixelpipe_cleanup_nodes(&S.full.pipe);
  if(S.preview.ready) dt_dev_pixelpipe_cleanup_nodes(&S.preview.pipe);
  dt_dev_cleanup(&S.dev);
  dt_dev_init(&S.dev, FALSE);
  dt_dev_load_image(&S.dev, S.imgid);
  dt_dev_pop_history_items_ext(&S.dev, S.dev.history_end);
  dtb_pipe_t *ps[2] = { &S.full, &S.preview };
  for(int i = 0; i < 2; i++)
    if(ps[i]->ready)
    {
      dt_dev_pixelpipe_set_input(&ps[i]->pipe, &S.dev, (float *)ps[i]->buf.buf,
                                 ps[i]->buf.width, ps[i]->buf.height, ps[i]->buf.iscale);
      ps[i]->pipe.changed |= DT_DEV_PIPE_REMOVE;
    }
}

/* ------------------------------------------------------------------ */
/* modules and parameters                                             */

static dt_iop_module_t *_find(const char *op, int prio)
{
  if(!op) return NULL;
  for(GList *l = S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *m = l->data;
    if(dt_iop_module_is(m, op) && m->multi_priority == prio) return m;
  }
  return NULL;
}

static gboolean _is_scalar(const dt_introspection_type_t t)
{
  switch(t)
  {
    case DT_INTROSPECTION_TYPE_FLOAT: case DT_INTROSPECTION_TYPE_DOUBLE:
    case DT_INTROSPECTION_TYPE_INT: case DT_INTROSPECTION_TYPE_UINT:
    case DT_INTROSPECTION_TYPE_INT8: case DT_INTROSPECTION_TYPE_UINT8:
    case DT_INTROSPECTION_TYPE_SHORT: case DT_INTROSPECTION_TYPE_USHORT:
    case DT_INTROSPECTION_TYPE_BOOL: case DT_INTROSPECTION_TYPE_ENUM:
      return TRUE;
    default:
      return FALSE;
  }
}

static void _add_value(JsonBuilder *b, const dt_introspection_field_t *f, const void *p)
{
  switch(f->header.type)
  {
    // JSON has no NaN or infinity; some modules keep NaN in unused fields
    // (temperature's "various"), which json-glib would write as "nan.0"
    case DT_INTROSPECTION_TYPE_FLOAT:
    case DT_INTROSPECTION_TYPE_DOUBLE:
    {
      const double v = f->header.type == DT_INTROSPECTION_TYPE_FLOAT ? *(const float *)p
                                                                     : *(const double *)p;
      if(isfinite(v))
        json_builder_add_double_value(b, v);
      else
        json_builder_add_null_value(b);
      break;
    }
    case DT_INTROSPECTION_TYPE_INT:    json_builder_add_int_value(b, *(const int *)p); break;
    case DT_INTROSPECTION_TYPE_UINT:   json_builder_add_int_value(b, *(const unsigned int *)p); break;
    case DT_INTROSPECTION_TYPE_INT8:   json_builder_add_int_value(b, *(const int8_t *)p); break;
    case DT_INTROSPECTION_TYPE_UINT8:  json_builder_add_int_value(b, *(const uint8_t *)p); break;
    case DT_INTROSPECTION_TYPE_SHORT:  json_builder_add_int_value(b, *(const short *)p); break;
    case DT_INTROSPECTION_TYPE_USHORT: json_builder_add_int_value(b, *(const unsigned short *)p); break;
    case DT_INTROSPECTION_TYPE_BOOL:   json_builder_add_boolean_value(b, *(const gboolean *)p != 0); break;
    case DT_INTROSPECTION_TYPE_ENUM:
    {
      const int v = *(const int *)p;
      for(const dt_introspection_type_enum_tuple_t *e = f->Enum.values; e && e->name; e++)
        if(e->value == v)
        {
          json_builder_add_string_value(b, e->name);
          return;
        }
      json_builder_add_int_value(b, v);
      break;
    }
    default: json_builder_add_null_value(b); break;
  }
}

static char *_json_finish(JsonBuilder *b)
{
  JsonGenerator *gen = json_generator_new();
  JsonNode *root = json_builder_get_root(b);
  json_generator_set_root(gen, root);
  gchar *s = json_generator_to_data(gen, NULL);
  json_node_free(root);
  g_object_unref(gen);
  g_object_unref(b);
  return s;
}

char *dtb_modules_json(void)
{
  if(!g_inited || !S.open)
  {
    dtb__err(DTB_E_STATE, "no image open");
    return NULL;
  }
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  // dev.iop is kept sorted by iop_order (dt_sort_iop_by_order)
  for(GList *l = S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *m = l->data;
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "operation");
    json_builder_add_string_value(b, m->op);
    json_builder_set_member_name(b, "instance_name");
    json_builder_add_string_value(b, m->multi_name);
    json_builder_set_member_name(b, "multi_priority");
    json_builder_add_int_value(b, m->multi_priority);
    json_builder_set_member_name(b, "iop_order");
    json_builder_add_int_value(b, m->iop_order);
    json_builder_set_member_name(b, "enabled");
    json_builder_add_boolean_value(b, m->enabled);
    json_builder_set_member_name(b, "hidden");
    json_builder_add_boolean_value(b, dt_iop_is_hidden(m));
    json_builder_set_member_name(b, "version");
    json_builder_add_int_value(b, m->version());
    json_builder_set_member_name(b, "params");
    json_builder_begin_object(b);
    if(m->so->have_introspection && m->params)
    {
      for(dt_introspection_field_t *f = m->so->get_introspection_linear();
          f && f->header.type != DT_INTROSPECTION_TYPE_NONE; f++)
      {
        if(!_is_scalar(f->header.type) || !f->header.name || strchr(f->header.name, '['))
          continue;
        json_builder_set_member_name(b, f->header.name);
        _add_value(b, f, (const uint8_t *)m->params + f->header.offset);
      }
    }
    json_builder_end_object(b);
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  return _json_finish(b);
}

/* record a module's state in history, as the desktop's
 * dt_dev_add_history_item() does, minus the interface work. That function
 * returns early without the desktop interface (develop.c,
 * _dev_add_history_item: "if(!darktable.gui ...) return"), so the bridge calls
 * dt_dev_add_history_item_ext() with no_image TRUE: the develop object has no
 * pipes of its own; the bridge's pipes are flagged below. */
static void _history_add(dt_iop_module_t *m, const gboolean enable)
{
  if(S.seal_next)
  {
    dt_dev_history_seal(&S.dev, TRUE);
    S.seal_next = FALSE;
  }
  dt_pthread_mutex_lock(&S.dev.history_mutex);
  dt_dev_add_history_item_ext(&S.dev, m, enable, TRUE);
  dt_pthread_mutex_unlock(&S.dev.history_mutex);
  _pipes_dirty(DT_DEV_PIPE_SYNCH);
}

void dtb_history_seal(void)
{
  if(S.open) S.seal_next = TRUE;
}

static int _param_field(const char *op, int prio, const char *param,
                        dt_iop_module_t **mo, dt_introspection_field_t **fo)
{
  NEED_SESSION();
  dt_iop_module_t *m = _find(op, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "", prio);
  if(!m->so->have_introspection || !param)
    return dtb__err(DTB_E_NOT_FOUND, "%s has no introspection", m->op);
  dt_introspection_field_t *f = m->get_f(param);
  if(!f || !_is_scalar(f->header.type))
    return dtb__err(DTB_E_NOT_FOUND, "%s has no scalar parameter '%s'", m->op, param);
  *mo = m;
  *fo = f;
  return DTB_OK;
}

int dtb_param_set_float(const char *op, int prio, const char *param, float v)
{
  dt_iop_module_t *m = NULL;
  dt_introspection_field_t *f = NULL;
  const int r = _param_field(op, prio, param, &m, &f);
  if(r) return r;
  void *p = (uint8_t *)m->params + f->header.offset;
  if(f->header.type == DT_INTROSPECTION_TYPE_FLOAT)
  {
    if(v < f->Float.Min || v > f->Float.Max)
      return dtb__err(DTB_E_RANGE, "%s.%s: %g outside [%g, %g]", m->op, param, v,
                      f->Float.Min, f->Float.Max);
    *(float *)p = v;
  }
  else if(f->header.type == DT_INTROSPECTION_TYPE_DOUBLE)
  {
    if(v < f->Double.Min || v > f->Double.Max)
      return dtb__err(DTB_E_RANGE, "%s.%s out of range", m->op, param);
    *(double *)p = v;
  }
  else
    return dtb__err(DTB_E_TYPE, "%s.%s is not a float", m->op, param);
  _history_add(m, TRUE);
  return DTB_OK;
}

int dtb_param_set_int(const char *op, int prio, const char *param, int v)
{
  dt_iop_module_t *m = NULL;
  dt_introspection_field_t *f = NULL;
  const int r = _param_field(op, prio, param, &m, &f);
  if(r) return r;
  void *p = (uint8_t *)m->params + f->header.offset;
  double lo = 0, hi = 0;
  switch(f->header.type)
  {
    case DT_INTROSPECTION_TYPE_INT:    lo = f->Int.Min;    hi = f->Int.Max;    break;
    case DT_INTROSPECTION_TYPE_UINT:   lo = f->UInt.Min;   hi = f->UInt.Max;   break;
    case DT_INTROSPECTION_TYPE_INT8:   lo = f->Int8.Min;   hi = f->Int8.Max;   break;
    case DT_INTROSPECTION_TYPE_UINT8:  lo = f->UInt8.Min;  hi = f->UInt8.Max;  break;
    case DT_INTROSPECTION_TYPE_SHORT:  lo = f->Short.Min;  hi = f->Short.Max;  break;
    case DT_INTROSPECTION_TYPE_USHORT: lo = f->UShort.Min; hi = f->UShort.Max; break;
    case DT_INTROSPECTION_TYPE_ENUM:
    {
      gboolean known = FALSE;
      for(const dt_introspection_type_enum_tuple_t *e = f->Enum.values; e && e->name; e++)
        if(e->value == v) known = TRUE;
      if(!known) return dtb__err(DTB_E_RANGE, "%s.%s: %d is not an enum value", m->op, param, v);
      lo = hi = v;
      break;
    }
    default:
      return dtb__err(DTB_E_TYPE, "%s.%s is not an integer or enum", m->op, param);
  }
  if(v < lo || v > hi)
    return dtb__err(DTB_E_RANGE, "%s.%s: %d outside [%g, %g]", m->op, param, v, lo, hi);
  switch(f->header.type)
  {
    case DT_INTROSPECTION_TYPE_INT:    *(int *)p = v; break;
    case DT_INTROSPECTION_TYPE_UINT:   *(unsigned int *)p = v; break;
    case DT_INTROSPECTION_TYPE_INT8:   *(int8_t *)p = v; break;
    case DT_INTROSPECTION_TYPE_UINT8:  *(uint8_t *)p = v; break;
    case DT_INTROSPECTION_TYPE_SHORT:  *(short *)p = v; break;
    case DT_INTROSPECTION_TYPE_USHORT: *(unsigned short *)p = v; break;
    case DT_INTROSPECTION_TYPE_ENUM:   *(int *)p = v; break;
    default: break;
  }
  _history_add(m, TRUE);
  return DTB_OK;
}

int dtb_param_set_bool(const char *op, int prio, const char *param, int v)
{
  dt_iop_module_t *m = NULL;
  dt_introspection_field_t *f = NULL;
  const int r = _param_field(op, prio, param, &m, &f);
  if(r) return r;
  if(f->header.type != DT_INTROSPECTION_TYPE_BOOL)
    return dtb__err(DTB_E_TYPE, "%s.%s is not a bool", m->op, param);
  *(gboolean *)((uint8_t *)m->params + f->header.offset) = v ? TRUE : FALSE;
  _history_add(m, TRUE);
  return DTB_OK;
}

int dtb_set_enabled(const char *op, int prio, int enabled)
{
  NEED_SESSION();
  dt_iop_module_t *m = _find(op, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "", prio);
  // desktop shows no on/off switch for these (always-on modules)
  if(m->hide_enable_button)
    return dtb__err(DTB_E_ARG, "%s cannot be switched on or off", m->op);
  m->enabled = enabled ? TRUE : FALSE;
  _history_add(m, FALSE);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* instances                                                          */

/* follows dt_iop_gui_duplicate() (develop/imageop.c) without its widgets */
static int _instance_add(const char *op, int prio, const gboolean copy, int *new_prio)
{
  NEED_SESSION();
  dt_iop_module_t *base = _find(op, prio);
  if(!base) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "", prio);
  if(base->flags() & IOP_FLAGS_ONE_INSTANCE)
    return dtb__err(DTB_E_ARG, "%s allows one instance only", base->op);

  // "make sure the duplicated module appears in the history"
  _history_add(base, FALSE);

  dt_pthread_mutex_lock(&S.dev.history_mutex);
  dt_iop_module_t *m = dt_dev_module_duplicate_ext(&S.dev, base, TRUE);
  dt_pthread_mutex_unlock(&S.dev.history_mutex);
  if(!m) return dtb__err(DTB_E_ENGINE, "dt_dev_module_duplicate_ext failed");

  dt_iop_reload_defaults(m);
  if(copy)
  {
    memcpy(m->params, base->params, m->params_size);
    if(m->flags() & IOP_FLAGS_SUPPORTS_BLENDING)
    {
      dt_iop_commit_blend_params(m, base->blend_params, NULL);
      if(dt_is_valid_maskid(base->blend_params->mask_id)
         || dt_is_valid_maskid(base->blend_params->blend_mask_id))
      {
        m->blend_params->mask_id = NO_MASKID;
        m->blend_params->blend_mask_id = NO_MASKID;
        dt_masks_iop_use_same_as(m, base);
      }
    }
  }
  // "we save the new instance creation"
  _history_add(m, TRUE);
  _pipes_dirty(DT_DEV_PIPE_REMOVE);
  S.seal_next = TRUE;
  if(new_prio) *new_prio = m->multi_priority;
  return DTB_OK;
}

int dtb_instance_new(const char *op, int prio, int *new_prio)
{
  return _instance_add(op, prio, FALSE, new_prio);
}

int dtb_instance_duplicate(const char *op, int prio, int *new_prio)
{
  return _instance_add(op, prio, TRUE, new_prio);
}

/* follows _gui_delete_callback() (develop/imageop.c). Its history clean-up
 * lives in dt_dev_module_remove() but only runs with gui_attached, so the
 * bridge does it here. */
int dtb_instance_delete(const char *op, int prio)
{
  NEED_SESSION();
  dt_iop_module_t *module = _find(op, prio);
  if(!module) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "", prio);

  dt_iop_module_t *next = NULL;
  gboolean found = FALSE;
  for(GList *l = S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *mod = l->data;
    if(mod == module)
    {
      found = TRUE;
      if(next) break;
    }
    else if(mod->instance == module->instance)
    {
      next = mod;
      if(found) break;
    }
  }
  if(!next) return dtb__err(DTB_E_ARG, "cannot delete the last instance of %s", module->op);

  const gboolean is_zero = (module->multi_priority == 0);
  dt_pthread_mutex_lock(&S.dev.history_mutex);

  // remove the module's history items. history_end only moves for items
  // below it (dt_dev_module_remove decrements it for every item)
  int idx = 0;
  for(GList *l = S.dev.history; l;)
  {
    GList *nl = g_list_next(l);
    dt_dev_history_item_t *hist = l->data;
    if(hist->module == module)
    {
      dt_dev_free_history_item(hist);
      S.dev.history = g_list_delete_link(S.dev.history, l);
      if(idx < S.dev.history_end) S.dev.history_end--;
    }
    else
      idx++;
    l = nl;
  }
  dt_pthread_mutex_unlock(&S.dev.history_mutex);
  dt_dev_module_remove(&S.dev, module);  // takes the module out of dev.iop

  // drop its entry from the order list (as dt_dev_reload_history_items does
  // with dt_ioppr_resync_iop_list after a desktop delete)
  dt_ioppr_resync_iop_list(&S.dev);

  if(is_zero)
  {
    dt_iop_module_t *first = NULL;
    for(GList *h = S.dev.history; h; h = g_list_next(h))
    {
      dt_dev_history_item_t *hist = h->data;
      if(hist->module->instance == module->instance && hist->module != module)
      {
        first = hist->module;
        break;
      }
    }
    if(!first) first = next;
    // the order list must follow the renumbering, or the instance would
    // take the deleted one's place in the pipe when the XMP is read again
    GList *link = dt_ioppr_get_iop_order_link(S.dev.iop_order_list, first->op,
                                              first->multi_priority);
    if(link) ((dt_iop_order_entry_t *)link->data)->instance = 0;
    dt_iop_update_multi_priority(first, 0);
    for(GList *h = S.dev.history; h; h = g_list_next(h))
    {
      dt_dev_history_item_t *hist = h->data;
      if(hist->module == first) hist->multi_priority = 0;
    }
    dt_ioppr_resync_modules_order(&S.dev);
  }

  // as desktop: keep the module alive, a pipe node may still point to it
  S.dev.alliop = g_list_append(S.dev.alliop, module);
  _pipes_dirty(DT_DEV_PIPE_REMOVE);
  S.seal_next = TRUE;
  return DTB_OK;
}

/* follows _gui_moveup_callback / _gui_movedown_callback (imageop.c); the
 * neighbour is the next module that is not hidden, as the desktop panel
 * shows them when every module is visible */
int dtb_instance_move(const char *op, int prio, int direction)
{
  NEED_SESSION();
  if(direction != 1 && direction != -1) return dtb__err(DTB_E_ARG, "direction must be +1 or -1");
  dt_iop_module_t *module = _find(op, prio);
  if(!module) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "", prio);

  GList *self = g_list_find(S.dev.iop, module);
  dt_iop_module_t *other = NULL;
  for(GList *l = direction > 0 ? g_list_next(self) : g_list_previous(self); l;
      l = direction > 0 ? g_list_next(l) : g_list_previous(l))
  {
    dt_iop_module_t *mod = l->data;
    if(!dt_iop_is_hidden(mod))
    {
      other = mod;
      break;
    }
  }
  if(!other) return dtb__err(DTB_E_ARG, "nothing to move past");

  const gboolean allowed = direction > 0
    ? dt_ioppr_check_can_move_after_iop(S.dev.iop, module, other)
    : dt_ioppr_check_can_move_before_iop(S.dev.iop, module, other);
  if(!allowed)
    return dtb__err(DTB_E_ARG, "pipeline rules forbid moving %s past %s", module->op, other->op);

  dt_pthread_mutex_lock(&S.dev.history_mutex);
  const gboolean moved = direction > 0 ? dt_ioppr_move_iop_after(&S.dev, module, other)
                                       : dt_ioppr_move_iop_before(&S.dev, module, other);
  if(moved) S.dev.iop = g_list_sort(S.dev.iop, dt_sort_iop_by_order);
  dt_pthread_mutex_unlock(&S.dev.history_mutex);
  if(!moved) return dtb__err(DTB_E_ENGINE, "move refused by iop order");

  S.seal_next = TRUE;
  _history_add(module, TRUE);  // desktop passes enable TRUE here too
  _pipes_dirty(DT_DEV_PIPE_REMOVE);
  S.seal_next = TRUE;
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* history                                                            */

char *dtb_history_json(void)
{
  if(!g_inited || !S.open)
  {
    dtb__err(DTB_E_STATE, "no image open");
    return NULL;
  }
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "history_end");
  json_builder_add_int_value(b, S.dev.history_end);
  json_builder_set_member_name(b, "items");
  json_builder_begin_array(b);
  int num = 0;
  for(GList *l = S.dev.history; l; l = g_list_next(l), num++)
  {
    const dt_dev_history_item_t *h = l->data;
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "num");
    json_builder_add_int_value(b, num);
    json_builder_set_member_name(b, "operation");
    json_builder_add_string_value(b, h->op_name);
    json_builder_set_member_name(b, "multi_priority");
    json_builder_add_int_value(b, h->multi_priority);
    json_builder_set_member_name(b, "instance_name");
    json_builder_add_string_value(b, h->multi_name);
    json_builder_set_member_name(b, "enabled");
    json_builder_add_boolean_value(b, h->enabled);
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
  return _json_finish(b);
}

int dtb_history_goto(int end)
{
  NEED_SESSION();
  const int n = g_list_length(S.dev.history);
  if(end < 0 || end > n) return dtb__err(DTB_E_ARG, "history_end %d outside 0..%d", end, n);
  dt_pthread_mutex_lock(&S.dev.history_mutex);
  dt_dev_pop_history_items_ext(&S.dev, end);
  S.dev.iop = g_list_sort(S.dev.iop, dt_sort_iop_by_order);
  dt_pthread_mutex_unlock(&S.dev.history_mutex);
  _pipes_dirty(DT_DEV_PIPE_REMOVE);
  S.seal_next = TRUE;
  return DTB_OK;
}

/* dt_dev_history_truncate() (develop.c) is the desktop path; it reloads
 * through dt_dev_reload_history_items(), which touches the darkroom's pipes
 * and widgets (dev->full.pipe is NULL here). The bridge keeps its library
 * steps and reloads the develop object instead. */
int dtb_history_compress(void)
{
  NEED_SESSION();
  dt_dev_write_history(&S.dev);
  dt_history_compress_on_image(S.imgid);
  _reload_dev();
  // dt_history_compress_on_image() leaves images.history_end as it was, so
  // it can point past the shorter history; dt_dev_history_truncate() then
  // sets it to the new number of items, and so does the bridge
  const int n = g_list_length(S.dev.history);
  if(S.dev.history_end != n)
  {
    dt_image_set_history_end(S.imgid, n);
    dt_dev_pop_history_items_ext(&S.dev, n);
  }
  S.seal_next = TRUE;
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* masks                                                              */

int dtb_mask_add_circle(const char *op, int prio, float cx, float cy, float radius, float border)
{
  NEED_SESSION();
  dt_iop_module_t *m = _find(op, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "", prio);
  if(!(m->flags() & IOP_FLAGS_SUPPORTS_BLENDING) || (m->flags() & IOP_FLAGS_NO_MASKS))
    return dtb__err(DTB_E_ARG, "%s has no drawn masks", m->op);
  if(radius <= 0.0f || border < 0.0f) return dtb__err(DTB_E_ARG, "bad circle");

  dt_masks_form_t *form = dt_masks_create(DT_MASKS_CIRCLE);
  dt_masks_point_circle_t *c = calloc(1, sizeof(dt_masks_point_circle_t));
  c->center[0] = cx;
  c->center[1] = cy;
  c->radius = radius;
  c->border = border;
  form->points = g_list_append(NULL, c);

  dt_pthread_mutex_lock(&S.dev.history_mutex);
  // as dt_masks_gui_form_save_creation() and dt_masks_group_insert_member()
  // (develop/masks/masks.c), whose history calls return without the GUI
  dt_masks_assign_unique_name(&S.dev, form);
  S.dev.forms = g_list_append(S.dev.forms, form);
  dt_masks_group_insert_point(&S.dev, m, form);
  // what the mask on/off switch sets (blend_gui.c, _blendop_mask_enable)
  m->blend_params->mask_mode |= DEVELOP_MASK_ENABLED | DEVELOP_MASK_FLEXI;
  dt_pthread_mutex_unlock(&S.dev.history_mutex);

  if(S.seal_next)
  {
    dt_dev_history_seal(&S.dev, TRUE);
    S.seal_next = FALSE;
  }
  dt_pthread_mutex_lock(&S.dev.history_mutex);
  dt_dev_add_masks_history_item_ext(&S.dev, m, TRUE, TRUE);
  dt_pthread_mutex_unlock(&S.dev.history_mutex);
  _pipes_dirty(DT_DEV_PIPE_SYNCH);
  S.seal_next = TRUE;
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* output                                                             */

int dtb_xmp_write(const char *xmp_path)
{
  NEED_SESSION();
  if(!xmp_path) return dtb__err(DTB_E_ARG, "no path");
  if(S.read_only)
    return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  if(g_file_test(xmp_path, G_FILE_TEST_EXISTS))
  {
    int n = 0;
    g_free(dtb__xmp_check(xmp_path, &n));
    if(n != 0)
      return dtb__err(DTB_E_READ_ONLY, "refusing to overwrite %s: it failed the version guard (%d)",
                      xmp_path, n);
  }
  dt_dev_write_history(&S.dev);
  // force_write: no merge with whatever the file holds
  if(dt_exif_xmp_write(S.imgid, xmp_path, TRUE))
    return dtb__err(DTB_E_IO, "cannot write %s", xmp_path);
  return DTB_OK;
}

int dtb_export_jpeg(const char *path, int quality, int max_width, int max_height)
{
  NEED_SESSION();
  if(!path || quality < 1 || quality > 100 || max_width < 0 || max_height < 0)
    return dtb__err(DTB_E_ARG, "bad arguments");
  // the export reads the history from the library, as darktable-cli's does
  dt_dev_write_history(&S.dev);

  dt_imageio_module_format_t *format = dt_imageio_get_format_by_name("jpeg");
  if(!format) return dtb__err(DTB_E_ENGINE, "no jpeg format module");
  dt_conf_set_int("plugins/imageio/format/jpeg/quality", quality);
  dt_imageio_module_data_t *fdata = format->get_params(format);
  if(!fdata) return dtb__err(DTB_E_ENGINE, "jpeg get_params failed");
  fdata->max_width = max_width;
  fdata->max_height = max_height;
  fdata->style[0] = '\0';
  fdata->style_append = 1;

  dt_export_metadata_t metadata = { .flags = dt_lib_export_metadata_default_flags(),
                                    .list = NULL };
  // high_quality TRUE and upscale FALSE are darktable-cli's defaults
  const gboolean failed = dt_imageio_export(S.imgid, path, format, fdata,
                                            TRUE, FALSE, FALSE, 1.0, TRUE, FALSE,
                                            DT_COLORSPACE_NONE, NULL, DT_INTENT_LAST,
                                            NULL, NULL, 1, 1, &metadata);
  format->free_params(format, fdata);
  if(failed) return dtb__err(DTB_E_IO, "export to %s failed", path);
  return DTB_OK;
}

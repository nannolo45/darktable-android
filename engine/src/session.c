// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: session (Engine API v1, section 2.3): open, close, image
 * information, metadata reload.
 */
#include "dtb_internal.h"

#include "common/colorlabels.h"
#include "common/database.h"
#include "common/debug.h"
#include "common/history.h"
#include "common/datetime.h"
#include "common/exif.h"
#include "common/film.h"
#include "common/image.h"
#include "common/image_cache.h"
#include "common/ratings.h"
#include "views/view.h"

#include <string.h>

static dt_imgid_t g_last_imgid = NO_IMGID;

dt_iop_module_t *dtb__find(const char *op, int prio)
{
  if(!op) return NULL;
  for(GList *l = dtb_S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *m = l->data;
    if(dt_iop_module_is(m, op) && m->multi_priority == prio) return m;
  }
  return NULL;
}

/* ------------------------------------------------------------------ */
/* the edit a session starts from (P1c item 2)                        */

/* The default edit: what a first import shows. The private library keeps an
 * image's history between sessions of one process (main.history and its
 * companions, written by every change and by dt_dev_read_history_ext()
 * itself), and dt_dev_load_image() starts from that. Clearing it as the
 * lighttable's "discard history" does (dt_history_delete_on_image_ext,
 * common/history.c) and clearing the "auto presets applied" flag makes the
 * next load apply the defaults and automatic presets again, exactly as on
 * import. init_history is FALSE because its TRUE branch writes the flag
 * with DT_IMAGE_CACHE_SAFE (a sidecar write path) and reloads
 * darktable.develop; the bridge clears the flag itself, relaxed. */
static void _reset_to_default_edit(const dt_imgid_t imgid)
{
  dt_history_delete_on_image_ext(imgid, FALSE, FALSE);
  dt_image_t *img = dt_image_cache_get(imgid, 'w');
  if(img)
  {
    img->flags &= ~DT_IMAGE_AUTO_PRESETS_APPLIED;
    dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
  }
}

/* the library's history rows of the image, before the develop object reads
 * (and rewrites) them */
static GArray *_rows_read(const dt_imgid_t imgid, int *end)
{
  GArray *rows = g_array_new(FALSE, TRUE, sizeof(dtb_row_t));
  sqlite3_stmt *stmt;
  DT_DEBUG_SQLITE3_PREPARE_V2(dt_database_get(darktable.db),
                              "SELECT operation, multi_priority, multi_name, enabled"
                              " FROM main.history WHERE imgid = ?1 ORDER BY num",
                              -1, &stmt, NULL);
  DT_DEBUG_SQLITE3_BIND_INT(stmt, 1, imgid);
  while(sqlite3_step(stmt) == SQLITE_ROW)
  {
    dtb_row_t r = { 0 };
    r.dev_index = -1;
    const char *op = (const char *)sqlite3_column_text(stmt, 0);
    g_strlcpy(r.op, op ? op : "", sizeof(r.op));
    r.priority = sqlite3_column_int(stmt, 1);
    const char *name = (const char *)sqlite3_column_text(stmt, 2);
    r.instance_name = g_strdup(name ? name : "");
    r.enabled = sqlite3_column_int(stmt, 3) != 0;
    g_array_append_val(rows, r);
  }
  sqlite3_finalize(stmt);
  *end = 0;
  dt_image_get_history_end(imgid, end);
  return rows;
}

static void _rows_free(GArray *rows)
{
  if(!rows) return;
  for(guint i = 0; i < rows->len; i++) g_free(g_array_index(rows, dtb_row_t, i).instance_name);
  g_array_free(rows, TRUE);
}

static gboolean _row_matches(const dtb_row_t *r, const dt_dev_history_item_t *h)
{
  if(strcmp(r->op, h->op_name)) return FALSE;
  // dt_dev_read_history_ext() sets priority 0 for one-instance modules
  return r->priority == h->multi_priority || (h->module && (h->module->flags() & IOP_FLAGS_ONE_INSTANCE));
}

int dtb__rows_dev_end(const int row_end)
{
  int n = 0;
  for(int i = 0; i < row_end && i < (int)dtb_S.rows->len; i++)
    if(g_array_index(dtb_S.rows, dtb_row_t, i).dev_index >= 0) n++;
  return n;
}

/* dt_dev_read_history_ext() (develop/develop.c) skips history rows whose
 * module is not installed (and a few others: no iop order, failed legacy
 * conversion), rewrites the library from what it kept (_dev_write_history),
 * but takes dev->history_end from the image's stored history_end, which
 * still counts the skipped rows: end can then be larger than the list, and
 * an item below the end shifts every position above it. Here the rows as
 * the XMP gave them are matched with the items the engine loaded, in order;
 * the develop object's end is set to the number of loaded items below the
 * XMP's end. A read-only session keeps the matched rows, so history() lists
 * every row (unloaded ones flagged "unknown") and history_goto takes
 * positions in that list. A writable session cannot hold an unknown module
 * (the guard); a row skipped for another reason is left out, as desktop
 * does when it writes the history. */
static void _rows_match(GArray *pre, const int pre_end)
{
  const int npre = pre->len;
  const int ndev = g_list_length(dtb_S.dev.history);
  GArray *rows = g_array_new(FALSE, TRUE, sizeof(dtb_row_t));
  int i = 0, j = 0, unloaded = 0;
  while(i < npre || j < ndev)
  {
    const dt_dev_history_item_t *h = j < ndev ? g_list_nth_data(dtb_S.dev.history, j) : NULL;
    dtb_row_t *p = i < npre ? &g_array_index(pre, dtb_row_t, i) : NULL;
    dtb_row_t r = { 0 };
    if(p && h && _row_matches(p, h))
    {
      r = *p;
      r.instance_name = g_strdup(p->instance_name);
      r.dev_index = j++;
      i++;
    }
    else if(p)
    {
      // is the row loaded further on (an item the engine inserted before it)?
      gboolean later = FALSE;
      for(int k = j + 1; k < ndev && !later; k++)
        later = _row_matches(p, g_list_nth_data(dtb_S.dev.history, k));
      if(later && h)
      {
        g_strlcpy(r.op, h->op_name, sizeof(r.op));
        r.priority = h->multi_priority;
        r.instance_name = g_strdup(h->multi_name);
        r.enabled = h->enabled;
        r.dev_index = j++;
      }
      else
      {
        r = *p;
        r.instance_name = g_strdup(p->instance_name);
        r.dev_index = -1;
        unloaded++;
        i++;
      }
    }
    else
    {
      g_strlcpy(r.op, h->op_name, sizeof(r.op));
      r.priority = h->multi_priority;
      r.instance_name = g_strdup(h->multi_name);
      r.enabled = h->enabled;
      r.dev_index = j++;
    }
    g_array_append_val(rows, r);
  }
  // upstream's end counts positions in the list the library held, skipped
  // rows included, and is shifted by items the load added itself
  // (automatic presets, dt_image_shift_history_end): a position in the
  // merged list. It can exceed the list (the reported gap).
  (void)pre_end;
  const int rows_end = MIN(MAX(dtb_S.dev.history_end, 0), (int)rows->len);

  dtb_S.rows = rows;
  dtb_S.rows_end = rows_end;
  const int dev_end = dtb__rows_dev_end(rows_end);
  if(dtb_S.dev.history_end != dev_end)
  {
    dt_dev_pop_history_items_ext(&dtb_S.dev, dev_end);
    dt_image_set_history_end(dtb_S.imgid, dev_end);
  }
  if(!(dtb_S.read_only && unloaded > 0))
  {
    _rows_free(dtb_S.rows);
    dtb_S.rows = NULL;
    dtb_S.rows_end = 0;
  }
}

/* E1 (P1b): a file without a decodable picture is refused at open. The
 * full-size mipmap is what every render and image_info load; asking for it
 * here runs darktable's loaders (dt_imageio_open) once. When they fail,
 * dt_mipmap_cache_get leaves a NULL or empty full buffer and records the
 * loader's status in the image; the downscaled (DT_MIPMAP_F) buffer of such
 * an image is one of darktable's small static placeholder pictures
 * (_dead_image_f, _unsupp_image_f, _error_image_f in common/mipmap_cache.c),
 * which the preview pipe would then process as if it were the sensor data:
 * the P2f hang. */
static const char *_load_reason(const dt_imageio_retval_t r)
{
  switch(r)
  {
    case DT_IMAGEIO_FILE_NOT_FOUND: return "file not found";
    case DT_IMAGEIO_LOAD_FAILED: return "the loader failed (corrupt or unsupported file)";
    case DT_IMAGEIO_UNSUPPORTED_FORMAT: return "no loader could decode it (unsupported format, or a truncated or corrupt file)";
    case DT_IMAGEIO_UNSUPPORTED_CAMERA: return "unsupported camera";
    case DT_IMAGEIO_UNSUPPORTED_FEATURE: return "unsupported feature (for example the compression)";
    case DT_IMAGEIO_FILE_CORRUPTED: return "file is corrupt or truncated";
    case DT_IMAGEIO_IOERROR: return "read error";
    case DT_IMAGEIO_CACHE_FULL: return "out of memory for the image";
    case DT_IMAGEIO_UNRECOGNIZED: return "no decoder recognises this file";
    default: return "unknown loader status";
  }
}

static int _check_decodable(const dt_imgid_t imgid, const char *raw_path)
{
  dt_mipmap_buffer_t buf;
  dt_mipmap_cache_get(&buf, imgid, DT_MIPMAP_FULL, DT_MIPMAP_BLOCKING, 'r');
  const gboolean ok = buf.buf && buf.width > 0 && buf.height > 0;
  const int w = buf.width, h = buf.height;
  dt_imageio_retval_t status = buf.loader_status;
  dt_mipmap_cache_release(&buf);
  if(ok) return DTB_OK;
  const dt_image_t *img = dt_image_cache_get(imgid, 'r');
  if(img)
  {
    if(img->load_status != DT_IMAGEIO_OK) status = img->load_status;
    dt_image_cache_read_release(img);
  }
  // nothing of this file may stay in the memory caches: a later open of a
  // repaired file of the same name must load it again
  dt_mipmap_cache_remove(imgid);
  if(status == DT_IMAGEIO_OK)
    return dtb__err(DTB_E_IO, "cannot decode %s: the loader returned a picture of %d x %d pixels", raw_path, w, h);
  return dtb__err(DTB_E_IO, "cannot decode %s: %s (load status %d)", raw_path, _load_reason(status), (int)status);
}

int dtb_open(const char *raw_path, const char *xmp_path)
{
  NEED_INIT();
  if(dtb_S.open) return dtb__err(DTB_E_STATE, "an image is already open");
  if(!raw_path || !g_file_test(raw_path, G_FILE_TEST_IS_REGULAR))
    return dtb__err(DTB_E_IO, "cannot read %s", raw_path ? raw_path : "(null)");

  // the edit comes from the given XMP, else from the sidecar next to the
  // RAW, else it is the default edit; the guard first, on that XMP
  gchar *sidecar = xmp_path ? g_strdup(xmp_path) : g_strconcat(raw_path, ".xmp", NULL);
  gboolean read_only = FALSE;
  const gboolean have_xmp = g_file_test(sidecar, G_FILE_TEST_IS_REGULAR);
  if(have_xmp)
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

  gchar *dir = g_path_get_dirname(raw_path);
  dt_film_t film;
  dt_film_init(&film);
  const dt_filmid_t filmid = dt_film_new(&film, dir);
  dt_film_cleanup(&film);
  g_free(dir);
  if(!dt_is_valid_filmid(filmid))
  {
    g_free(sidecar);
    return dtb__err(DTB_E_ENGINE, "cannot create film roll");
  }

  gchar *base = g_path_get_basename(raw_path);
  dt_imgid_t imgid = dt_image_get_id(filmid, base);
  g_free(base);
  if(!dt_is_valid_imgid(imgid))
    imgid = dt_image_import(filmid, raw_path, TRUE, FALSE);
  if(!dt_is_valid_imgid(imgid))
  {
    // dt_image_import refuses files it cannot identify as a picture (an
    // empty file, for example): a file problem, not an engine one (E1)
    g_free(sidecar);
    return dtb__err(DTB_E_IO, "cannot import %s: darktable does not recognise it as a picture", raw_path);
  }
  {
    const int r = _check_decodable(imgid, raw_path);
    if(r)
    {
      g_free(sidecar);
      return r;
    }
  }

  // as darktable-cli: read the XMP into the library, replacing the history
  // (dt_exif_xmp_read deletes the image's history, masks and module order
  // first). Without an XMP: back to the default edit, whatever an earlier
  // session of this process left in the library.
  if(have_xmp)
  {
    dt_image_t *img = dt_image_cache_get(imgid, 'w');
    const gboolean failed = dt_exif_xmp_read(img, sidecar, FALSE);
    dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
    if(failed)
    {
      dtb__err(DTB_E_IO, "cannot load XMP %s", sidecar);
      g_free(sidecar);
      return DTB_E_IO;
    }
  }
  else
    _reset_to_default_edit(imgid);
  g_free(sidecar);

  int pre_end = 0;
  GArray *pre = _rows_read(imgid, &pre_end);

  memset(&dtb_S, 0, sizeof(dtb_S));
  g_queue_init(&dtb_S.undo);
  g_queue_init(&dtb_S.redo);
  dtb_S.next_snap_id = 1;
  dtb_S.imgid = imgid;
  g_last_imgid = imgid;
  dtb_S.read_only = read_only;
  dt_dev_init(&dtb_S.dev, FALSE);
  dt_dev_load_image(&dtb_S.dev, imgid);
  // dt_dev_load_image reads the history but does not apply it to the
  // modules (the darkroom does that later); as darktable-mcp does
  dt_dev_pop_history_items_ext(&dtb_S.dev, dtb_S.dev.history_end);
  _rows_match(pre, pre_end);
  _rows_free(pre);
  dtb_S.merge_module = NULL;
  dtb__times_reset();
  dtb_S.open = TRUE;
  if(read_only) dtb__err(DTB_OK, "opened read-only: XMP failed the version guard");
  return DTB_OK;
}

int dtb_close(void)
{
  NEED_SESSION();
  dtb__pipes_free();
  dtb__undo_clear();
  dt_dev_cleanup(&dtb_S.dev);
  _rows_free(dtb_S.rows);
  dtb_S.rows = NULL;
  dtb_S.open = FALSE;
  return DTB_OK;
}

int dtb_cache_drop(void)
{
  NEED_INIT();
  if(dtb_S.open) return dtb__err(DTB_E_STATE, "close the image first");
  if(!dt_is_valid_imgid(g_last_imgid)) return DTB_OK;
  // the float sizes the bridge renders from, then the 8-bit thumbnails
  dt_mipmap_cache_evict_at_size(g_last_imgid, DT_MIPMAP_FULL);
  dt_mipmap_cache_evict_at_size(g_last_imgid, DT_MIPMAP_F);
  dt_mipmap_cache_remove(g_last_imgid);
  return DTB_OK;
}

void dtb__reload_dev(void)
{
  dtb__pipes_detach();
  dt_dev_cleanup(&dtb_S.dev);
  dt_dev_init(&dtb_S.dev, FALSE);
  dt_dev_load_image(&dtb_S.dev, dtb_S.imgid);
  dt_dev_pop_history_items_ext(&dtb_S.dev, dtb_S.dev.history_end);
  dtb__pipes_reattach();
  dtb_S.merge_module = NULL;
}

/* ------------------------------------------------------------------ */
/* image information                                                  */

static void _str_member(JsonBuilder *b, const char *name, const char *v)
{
  json_builder_set_member_name(b, name);
  if(v && *v)
  {
    gchar *valid = g_utf8_make_valid(v, -1);
    json_builder_add_string_value(b, valid);
    g_free(valid);
  }
  else
    json_builder_add_null_value(b);
}

static void _num_member(JsonBuilder *b, const char *name, const double v)
{
  json_builder_set_member_name(b, name);
  if(isfinite(v) && v > 0.0)
    json_builder_add_double_value(b, v);
  else
    json_builder_add_null_value(b);
}

int dtb_image_info(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  // the processed size and the sensor layout are known once the full image
  // is loaded, which the full pipe does
  int r = dtb__full_pipe();
  if(r) return r;
  int w = 0, h = 0;
  r = dtb_render_size(1.0f, &w, &h);
  if(r) return r;

  const dt_image_t *img = dt_image_cache_get(dtb_S.imgid, 'r');
  if(!img) return dtb__err(DTB_E_ENGINE, "image not in cache");
  const char *klass = "nonraw";
  // the class selects the control schema (S5): X-Trans, Bayer, true
  // monochrome raw, everything else
  if(img->buf_dsc.filters == 9u)
    klass = "xtrans";
  else if(img->buf_dsc.filters != 0u)
    klass = "bayer";
  else if(img->flags & DT_IMAGE_MONOCHROME)
    klass = "monochrome";

  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "width");
  json_builder_add_int_value(b, w);
  json_builder_set_member_name(b, "height");
  json_builder_add_int_value(b, h);
  json_builder_set_member_name(b, "raw_width");
  json_builder_add_int_value(b, img->width);
  json_builder_set_member_name(b, "raw_height");
  json_builder_add_int_value(b, img->height);
  json_builder_set_member_name(b, "image_class");
  json_builder_add_string_value(b, klass);
  json_builder_set_member_name(b, "read_only");
  json_builder_add_boolean_value(b, dtb_S.read_only);

  json_builder_set_member_name(b, "exif");
  json_builder_begin_object(b);
  // the names darktable shows (camera_maker/model are its normalised names)
  _str_member(b, "maker", img->camera_maker[0] ? img->camera_maker : img->exif_maker);
  _str_member(b, "model", img->camera_model[0] ? img->camera_model : img->exif_model);
  _str_member(b, "lens", img->exif_lens);
  _num_member(b, "iso", img->exif_iso);
  _num_member(b, "aperture", img->exif_aperture);
  _num_member(b, "exposure", img->exif_exposure);
  _num_member(b, "focal_length", img->exif_focal_length);
  char dt[DT_DATETIME_LENGTH] = { 0 };
  json_builder_set_member_name(b, "datetime");
  if(img->exif_datetime_taken && dt_datetime_img_to_exif(dt, sizeof(dt), img) && strlen(dt) >= 19)
  {
    // "YYYY:MM:DD HH:MM:SS[.mmm]" -> "YYYY-MM-DDTHH:MM:SS"
    dt[4] = '-';
    dt[7] = '-';
    dt[10] = 'T';
    dt[19] = '\0';
    json_builder_add_string_value(b, dt);
  }
  else
    json_builder_add_null_value(b);
  json_builder_end_object(b);
  const dt_imgid_t imgid = img->id;
  dt_image_cache_read_release(img);

  // as darktable stores them: common/ratings.c, common/colorlabels.c (0 red .. 4 purple)
  const int rating = dt_ratings_get(imgid);
  json_builder_set_member_name(b, "rating");
  json_builder_add_int_value(b, rating == DT_VIEW_REJECT ? -1 : rating);
  json_builder_set_member_name(b, "color_labels");
  json_builder_begin_array(b);
  const int labels = dt_colorlabels_get_labels(imgid);
  for(int k = 0; k < DT_COLORLABELS_LAST; k++)
    if(labels & (1 << k)) json_builder_add_int_value(b, k);
  json_builder_end_array(b);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

int dtb_metadata_reload(const char *xmp_path)
{
  NEED_WRITABLE();
  if(!xmp_path || !g_file_test(xmp_path, G_FILE_TEST_IS_REGULAR))
    return dtb__err(DTB_E_IO, "cannot read %s", xmp_path ? xmp_path : "(null)");
  int n = 0;
  g_free(dtb__xmp_check(xmp_path, &n));
  if(n < 0) return dtb__err(DTB_E_IO, "%s is not a readable XMP", xmp_path);

  // dt_exif_xmp_read() is the only reader of rating, labels and tags from a
  // sidecar, and it also replaces the library's history with the file's.
  // The session's history lives in the develop object; it is written to the
  // library before and again after, so the library ends up with the
  // session's history and the file's metadata.
  dt_dev_write_history(&dtb_S.dev);
  dt_image_t *img = dt_image_cache_get(dtb_S.imgid, 'w');
  const gboolean failed = dt_exif_xmp_read(img, xmp_path, FALSE);
  dt_image_cache_write_release(img, DT_IMAGE_CACHE_RELAXED);
  dt_dev_write_history(&dtb_S.dev);
  if(failed) return dtb__err(DTB_E_IO, "cannot read metadata from %s", xmp_path);
  return DTB_OK;
}

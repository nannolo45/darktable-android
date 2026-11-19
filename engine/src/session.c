// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: session (Engine API v1, section 2.3): open, close, image
 * information, metadata reload.
 */
#include "dtb_internal.h"

#include "common/colorlabels.h"
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

int dtb_open(const char *raw_path, const char *xmp_path)
{
  NEED_INIT();
  if(dtb_S.open) return dtb__err(DTB_E_STATE, "an image is already open");
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
  dtb_S.merge_module = NULL;
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

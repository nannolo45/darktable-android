// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: output (Engine API v1, section 2.8): XMP sidecar and export.
 */
#include "dtb_internal.h"

#include "common/colorspaces.h"
#include "common/exif.h"
#include "control/conf.h"
#include "imageio/imageio_common.h"
#include "imageio/imageio_module.h"

#include <glib/gstdio.h>
#include <string.h>
#include <unistd.h>

int dtb_xmp_write(const char *xmp_path)
{
  NEED_SESSION();
  if(!xmp_path || !*xmp_path) return dtb__err(DTB_E_ARG, "no path");
  if(dtb_S.read_only)
    return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  if(g_file_test(xmp_path, G_FILE_TEST_EXISTS))
  {
    int n = 0;
    g_free(dtb__xmp_check(xmp_path, &n));
    if(n != 0)
      return dtb__err(DTB_E_READ_ONLY, "refusing to overwrite %s: it failed the version guard (%d)",
                      xmp_path, n);
  }
  dt_dev_write_history(&dtb_S.dev);

  // atomically: a temporary file in the same folder (same file system),
  // then a rename over the target
  gchar *dir = g_path_get_dirname(xmp_path);
  gchar *base = g_path_get_basename(xmp_path);
  gchar *tmpl = g_strdup_printf("%s/.%s.XXXXXX", dir, base);
  g_free(dir);
  g_free(base);
  const int fd = g_mkstemp(tmpl);
  if(fd < 0)
  {
    const int r = dtb__err(DTB_E_IO, "cannot create a temporary file next to %s", xmp_path);
    g_free(tmpl);
    return r;
  }
  close(fd);
  // force_write: no merge with whatever the file holds
  if(dt_exif_xmp_write(dtb_S.imgid, tmpl, TRUE))
  {
    g_unlink(tmpl);
    g_free(tmpl);
    return dtb__err(DTB_E_IO, "cannot write %s", xmp_path);
  }
  if(g_rename(tmpl, xmp_path) != 0)
  {
    g_unlink(tmpl);
    g_free(tmpl);
    return dtb__err(DTB_E_IO, "cannot rename the written XMP to %s", xmp_path);
  }
  g_free(tmpl);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* export                                                             */

/* darktable's format modules read their options from the configuration
 * (get_params() in imageio/format/<name>.c); the bridge sets the keys for one
 * export and puts the user's values back afterwards */
typedef struct _conf_saved_t
{
  gchar *key;
  gchar *value;  // NULL: the key did not exist
} _conf_saved_t;

static void _conf_set_int(GList **saved, const char *key, const int v)
{
  _conf_saved_t *s = g_new0(_conf_saved_t, 1);
  s->key = g_strdup(key);
  s->value = dt_conf_key_exists(key) ? dt_conf_get_string(key) : NULL;
  *saved = g_list_prepend(*saved, s);
  dt_conf_set_int(key, v);
}

static void _conf_restore(GList *saved)
{
  for(GList *l = saved; l; l = g_list_next(l))
  {
    _conf_saved_t *s = l->data;
    if(s->value)
      dt_conf_set_string(s->key, s->value);
    // a key that did not exist keeps the value just used: darktable's
    // configuration has no way to remove a key again
    g_free(s->key);
    g_free(s->value);
    g_free(s);
  }
  g_list_free(saved);
}

static gboolean _opt_bool(JsonObject *o, const char *k, const gboolean def, int *err)
{
  if(!o || !json_object_has_member(o, k)) return def;
  JsonNode *n = json_object_get_member(o, k);
  if(JSON_NODE_TYPE(n) != JSON_NODE_VALUE || json_node_get_value_type(n) != G_TYPE_BOOLEAN)
  {
    *err = dtb__err(DTB_E_TYPE, "export option %s must be true or false", k);
    return def;
  }
  return json_node_get_boolean(n);
}

static int _opt_int(JsonObject *o, const char *k, const int def, int *err)
{
  if(!o || !json_object_has_member(o, k)) return def;
  JsonNode *n = json_object_get_member(o, k);
  if(JSON_NODE_TYPE(n) != JSON_NODE_VALUE || json_node_get_value_type(n) != G_TYPE_INT64)
  {
    *err = dtb__err(DTB_E_TYPE, "export option %s must be an integer", k);
    return def;
  }
  return (int)json_node_get_int(n);
}

static const char *_opt_str(JsonObject *o, const char *k, const char *def, int *err)
{
  if(!o || !json_object_has_member(o, k)) return def;
  JsonNode *n = json_object_get_member(o, k);
  if(JSON_NODE_TYPE(n) != JSON_NODE_VALUE || json_node_get_value_type(n) != G_TYPE_STRING)
  {
    *err = dtb__err(DTB_E_TYPE, "export option %s must be a string", k);
    return def;
  }
  return json_node_get_string(n);
}

int dtb_export(const char *path, const char *options_json)
{
  NEED_SESSION();
  if(!path || !*path) return dtb__err(DTB_E_ARG, "no path");
  JsonNode *root = NULL;
  JsonObject *o = NULL;
  if(options_json && *options_json)
  {
    root = dtb__json_parse(options_json);
    if(!root) return DTB_E_ARG;
    if(JSON_NODE_TYPE(root) != JSON_NODE_OBJECT)
    {
      json_node_unref(root);
      return dtb__err(DTB_E_ARG, "export options must be a JSON object");
    }
    o = json_node_get_object(root);
    static const char *known[] = { "format", "quality", "bit_depth", "max_width", "max_height",
                                   "upscale", "high_quality", "profile", NULL };
    GList *members = json_object_get_members(o);
    for(GList *k = members; k; k = g_list_next(k))
    {
      gboolean ok = FALSE;
      for(int i = 0; known[i] && !ok; i++) ok = !strcmp(known[i], k->data);
      if(!ok)
      {
        const int r = dtb__err(DTB_E_ARG, "unknown export option '%s'", (char *)k->data);
        g_list_free(members);
        json_node_unref(root);
        return r;
      }
    }
    g_list_free(members);
  }

  int err = DTB_OK;
  const char *fmt = _opt_str(o, "format", "jpeg", &err);
  const int quality = _opt_int(o, "quality", 95, &err);
  const int bpp = _opt_int(o, "bit_depth", 8, &err);
  const int max_w = _opt_int(o, "max_width", 0, &err);
  const int max_h = _opt_int(o, "max_height", 0, &err);
  const gboolean upscale = _opt_bool(o, "upscale", FALSE, &err);
  const gboolean hq = _opt_bool(o, "high_quality", TRUE, &err);
  const char *profile = _opt_str(o, "profile", "image", &err);
  if(!err && (max_w < 0 || max_h < 0)) err = dtb__err(DTB_E_RANGE, "max_width and max_height must be >= 0");

  // the format module and what it accepts (imageio/format/<name>.c)
  const char *fmt_name = NULL;
  int bpp_ok = 0;
  gboolean has_quality = FALSE;
  if(!err)
  {
    if(!strcmp(fmt, "jpeg")) { fmt_name = "jpeg"; bpp_ok = bpp == 8; has_quality = TRUE; }
    else if(!strcmp(fmt, "png")) { fmt_name = "png"; bpp_ok = bpp == 8 || bpp == 16; }
    else if(!strcmp(fmt, "tiff")) { fmt_name = "tiff"; bpp_ok = bpp == 8 || bpp == 16 || bpp == 32; }
    else if(!strcmp(fmt, "webp")) { fmt_name = "webp"; bpp_ok = bpp == 8; has_quality = TRUE; }
    else err = dtb__err(DTB_E_ARG, "unknown export format '%s'", fmt);
  }
  if(!err && !bpp_ok) err = dtb__err(DTB_E_ARG, "%s cannot be written with %d bits", fmt, bpp);
  if(!err && has_quality && (quality < 1 || quality > 100))
    err = dtb__err(DTB_E_RANGE, "quality %d outside 1..100", quality);

  dt_colorspaces_color_profile_type_t icc = DT_COLORSPACE_NONE;
  if(!err)
  {
    if(!strcmp(profile, "image")) icc = DT_COLORSPACE_NONE;  // the history's output profile
    else if(!strcmp(profile, "srgb")) icc = DT_COLORSPACE_SRGB;
    else if(!strcmp(profile, "adobergb")) icc = DT_COLORSPACE_ADOBERGB;
    else if(!strcmp(profile, "displayp3")) icc = DT_COLORSPACE_DISPLAY_P3;
    else err = dtb__err(DTB_E_ARG, "unknown profile '%s'", profile);
  }
  dt_imageio_module_format_t *format = err ? NULL : dt_imageio_get_format_by_name(fmt_name);
  if(!err && !format)
    err = dtb__err(DTB_E_ARG, "the %s format is not built into this engine", fmt_name);
  if(err)
  {
    if(root) json_node_unref(root);
    return err;
  }

  GList *saved = NULL;
  if(!strcmp(fmt_name, "jpeg"))
    _conf_set_int(&saved, "plugins/imageio/format/jpeg/quality", quality);
  else if(!strcmp(fmt_name, "webp"))
  {
    _conf_set_int(&saved, "plugins/imageio/format/webp/quality", quality);
    _conf_set_int(&saved, "plugins/imageio/format/webp/comp_type", 0);  // lossy, as quality implies
  }
  else if(!strcmp(fmt_name, "png"))
    _conf_set_int(&saved, "plugins/imageio/format/png/bpp", bpp);
  else if(!strcmp(fmt_name, "tiff"))
    _conf_set_int(&saved, "plugins/imageio/format/tiff/bpp", bpp);
  dt_imageio_module_data_t *fdata = format->get_params(format);
  _conf_restore(saved);
  if(root) json_node_unref(root);
  if(!fdata) return dtb__err(DTB_E_ENGINE, "%s get_params failed", fmt_name);
  fdata->max_width = max_w;
  fdata->max_height = max_h;
  fdata->style[0] = '\0';
  fdata->style_append = 1;

  // the export reads the history from the library, as darktable-cli's does
  dt_dev_write_history(&dtb_S.dev);
  dt_export_metadata_t metadata = { .flags = dt_lib_export_metadata_default_flags(),
                                    .list = NULL };
  const gboolean failed = dt_imageio_export(dtb_S.imgid, path, format, fdata,
                                            hq, upscale, FALSE, 1.0, TRUE, FALSE,
                                            icc, NULL, DT_INTENT_LAST,
                                            NULL, NULL, 1, 1, &metadata);
  format->free_params(format, fdata);
  if(failed) return dtb__err(DTB_E_IO, "export to %s failed", path);
  return DTB_OK;
}

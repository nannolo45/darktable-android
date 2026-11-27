// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Brief P6e: framing's and watermark's colours from the picture, watermark's
 * list of marker files and the fonts the engine can draw text with, and the
 * fontconfig set-up of the Android engine.
 *
 *   borders   pick {which: "border" | "frame", area | point}
 *   watermark pick {area | point}
 *   watermark list_files {}
 *   watermark list_fonts {}
 *
 * The colours are written as color_picker_apply in src/iop/borders.c and
 * src/iop/watermark.c does: the picked mean in the module's input, copied
 * into the float parameters, one history item. The colour button's own
 * callback (_colorpick_color_set) needs no action: it writes the three floats,
 * which dtb_params_set does.
 */
#include "dtb_internal.h"

#include "common/file_location.h"

#include <fontconfig/fontconfig.h>
#include <pango/pangocairo.h>

#include <math.h>
#include <string.h>

static float *_ffield(dt_iop_module_t *m, void *blob, const char *path)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, blob, path, &r) || r.f->header.type != DT_INTROSPECTION_TYPE_FLOAT) return NULL;
  return (float *)r.p;
}

static void _commit(dt_iop_module_t *m, const void *params)
{
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  memcpy(m->params, params, m->params_size);
  dtb__history_add(m, TRUE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
}

static const char *_arg_s(JsonObject *a, const char *k)
{
  if(!a || !json_object_has_member(a, k)) return NULL;
  JsonNode *n = json_object_get_member(a, k);
  return JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_STRING ? json_node_get_string(n) : NULL;
}

/* the colour picked from an area or point of the module's input, as desktop's picker gives self->picked_color */
static int _pick_color(dt_iop_module_t *m, JsonObject *a, dtb_pick_result_t *pk)
{
  if(dtb_S.read_only) return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  float area[4];
  int r = dtb__read_area(a, area);
  if(!r) r = dtb__pick(m, area, FALSE, FALSE, NULL, pk);
  if(!r && (!pk->have[0] || pk->max[0][0] < pk->min[0][0]))
    r = dtb__err(DTB_E_ARG, "the area gives no sample in %s's input", m->op);
  return r;
}

static gboolean _near(const float *a, const float *b)
{
  // desktop: "interrupt infinite loops"
  return fabsf(a[0] - b[0]) < 0.0001f && fabsf(a[1] - b[1]) < 0.0001f && fabsf(a[2] - b[2]) < 0.0001f;
}

/* borders: color_picker_apply; watermark: color_picker_apply. [guards] are the colours desktop compares the pick
 * with before writing (both of framing's, watermark's one); [target] the field written. */
static int _act_pick_color(dt_iop_module_t *m, JsonObject *a, const char *target, const char *const *guards, int nguards,
                           char **res)
{
  dtb_pick_result_t pk;
  int r = _pick_color(m, a, &pk);
  if(r) return r;
  const float picked[3] = { pk.mean[0][0], pk.mean[0][1], pk.mean[0][2] };
  void *params = dtb__memdup(m->params, m->params_size);
  float *dst = _ffield(m, params, target);
  gboolean skip = FALSE;
  for(int i = 0; i < nguards; i++)
  {
    const float *g = _ffield(m, params, guards[i]);
    if(!g || !dst)
    {
      g_free(params);
      return dtb__err(DTB_E_ENGINE, "%s has no %s (upstream changed?)", m->op, g ? target : guards[i]);
    }
    if(_near(g, picked)) skip = TRUE;
  }
  if(!skip) for(int k = 0; k < 3; k++) dst[k] = picked[k];
  const gboolean changed = memcmp(params, m->params, m->params_size) != 0;
  if(changed) _commit(m, params);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "picked");
  dtb__pick_json(b, &pk);
  json_builder_set_member_name(b, "color");
  json_builder_begin_array(b);
  for(int k = 0; k < 3; k++) json_builder_add_double_value(b, dst[k]);
  json_builder_end_array(b);
  json_builder_set_member_name(b, "changed");
  json_builder_add_boolean_value(b, changed);
  json_builder_end_object(b);
  *res = dtb__json_finish(b);
  g_free(params);
  return DTB_OK;
}

/* watermark.c _load_watermarks: the .svg and .png files of <basedir>/watermarks, sorted, as "name (ext)" */
static void _load_watermarks(JsonBuilder *b, const char *basedir, const char *where, const char *current, gboolean *found)
{
  GList *files = NULL;
  gchar *dirname = g_build_filename(basedir, "watermarks", NULL);
  GDir *dir = g_dir_open(dirname, 0, NULL);
  if(dir)
  {
    const gchar *d_name;
    while((d_name = g_dir_read_name(dir))) files = g_list_prepend(files, g_strdup(d_name));
    g_dir_close(dir);
  }
  files = g_list_sort(files, (GCompareFunc)g_strcmp0);
  for(GList *it = files; it; it = g_list_next(it))
  {
    const char *filename = it->data;
    const char *ext = strrchr(filename, '.');
    if(!ext || (g_ascii_strcasecmp(ext, ".svg") && g_ascii_strcasecmp(ext, ".png"))) continue;
    gchar *base = g_strndup(filename, ext - filename);
    gchar *label = g_strdup_printf("%s (%s)", base, ext + 1);
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "file");
    json_builder_add_string_value(b, filename);
    json_builder_set_member_name(b, "label");
    json_builder_add_string_value(b, label);
    // _text_color_font_set_sensitive: text, font and colour apply to .svg markers only
    json_builder_set_member_name(b, "svg");
    json_builder_add_boolean_value(b, !g_ascii_strcasecmp(ext, ".svg"));
    json_builder_set_member_name(b, "folder");
    json_builder_add_string_value(b, where);
    json_builder_end_object(b);
    if(current && !g_strcmp0(current, filename)) *found = TRUE;
    g_free(base);
    g_free(label);
  }
  g_list_free_full(files, g_free);
  g_free(dirname);
}

static int _act_watermark_files(dt_iop_module_t *m, char **res)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, m->params, "filename", &r) || r.f->header.type != DT_INTROSPECTION_TYPE_ARRAY)
    return dtb__err(DTB_E_ENGINE, "watermark has no filename (upstream changed?)");
  gchar *current = g_strndup((const char *)r.p, r.f->Array.count);
  char datadir[PATH_MAX] = { 0 }, configdir[PATH_MAX] = { 0 };
  dt_loc_get_datadir(datadir, sizeof(datadir));
  dt_loc_get_user_config_dir(configdir, sizeof(configdir));
  gboolean found = FALSE;
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "files");
  json_builder_begin_array(b);
  // _refresh_watermarks: darktable's folder first, then the user's configuration folder
  _load_watermarks(b, datadir, "data", current, &found);
  _load_watermarks(b, configdir, "config", current, &found);
  json_builder_end_array(b);
  json_builder_set_member_name(b, "current");
  json_builder_add_string_value(b, current);
  json_builder_set_member_name(b, "found");
  json_builder_add_boolean_value(b, found);
  json_builder_set_member_name(b, "config_folder");
  gchar *cw = g_build_filename(configdir, "watermarks", NULL);
  json_builder_add_string_value(b, cw);
  g_free(cw);
  json_builder_end_object(b);
  *res = dtb__json_finish(b);
  g_free(current);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* fonts                                                                */

static int g_default_config_fonts = -1;
static gchar *g_font_config = NULL;

void dtb__fonts_setup(const char *datadir, const char *cachedir)
{
#ifdef __ANDROID__
  if(g_getenv("FONTCONFIG_FILE")) return;
  // what fontconfig finds with its built-in configuration (the sysroot's paths, absent on a phone)
  FcConfig *c = FcInitLoadConfigAndFonts();
  FcFontSet *s = c ? FcConfigGetFonts(c, FcSetSystem) : NULL;
  g_default_config_fonts = s ? s->nfont : 0;
  if(c) FcConfigDestroy(c);
  // the font the APK carries (share/fonts, next to share/darktable: DejaVu Sans, desktop's default
  // watermark font) and the phone's own fonts
  gchar *share = g_path_get_dirname(datadir);
  gchar *fonts = g_build_filename(share, "fonts", NULL);
  gchar *dir = g_build_filename(cachedir, "fontconfig", NULL);
  g_mkdir_with_parents(dir, 0700);
  gchar *conf = g_build_filename(dir, "fonts.conf", NULL);
  gchar *xml = g_markup_printf_escaped(
      "<?xml version=\"1.0\"?>\n<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">\n<fontconfig>\n"
      "  <dir>%s</dir>\n  <dir>/system/fonts</dir>\n  <dir>/product/fonts</dir>\n"
      "  <cachedir>%s</cachedir>\n"
      "  <alias><family>sans-serif</family><prefer><family>DejaVu Sans</family></prefer></alias>\n"
      "  <alias><family>Sans</family><prefer><family>DejaVu Sans</family></prefer></alias>\n"
      "  <alias><family>serif</family><prefer><family>Noto Serif</family><family>DejaVu Sans</family></prefer></alias>\n"
      "  <alias><family>monospace</family><prefer><family>Droid Sans Mono</family><family>DejaVu Sans</family></prefer></alias>\n"
      "</fontconfig>\n",
      fonts, dir);
  if(g_file_set_contents(conf, xml, -1, NULL))
  {
    g_setenv("FONTCONFIG_FILE", conf, TRUE);
    g_free(g_font_config);
    g_font_config = g_strdup(conf);
  }
  g_free(xml);
  g_free(conf);
  g_free(dir);
  g_free(fonts);
  g_free(share);
#else
  (void)datadir;
  (void)cachedir;
#endif
}

/* a family fontconfig can draw English text with (its fonts cover the "en" language) */
static gboolean _family_latin(const char *family)
{
  FcPattern *pat = FcPatternCreate();
  FcPatternAddString(pat, FC_FAMILY, (const FcChar8 *)family);
  FcLangSet *ls = FcLangSetCreate();
  FcLangSetAdd(ls, (const FcChar8 *)"en");
  FcPatternAddLangSet(pat, FC_LANG, ls);
  FcObjectSet *os = FcObjectSetBuild(FC_FAMILY, NULL);
  FcFontSet *fs = FcFontList(NULL, pat, os);
  const gboolean ok = fs && fs->nfont > 0;
  if(fs) FcFontSetDestroy(fs);
  FcObjectSetDestroy(os);
  FcLangSetDestroy(ls);
  FcPatternDestroy(pat);
  return ok;
}

static int _cmp_family(const void *a, const void *b)
{
  return g_utf8_collate(pango_font_family_get_name(*(PangoFontFamily *const *)a),
                        pango_font_family_get_name(*(PangoFontFamily *const *)b));
}

/* GTK's font chooser lists pango's families and their faces; the font it gives is the face's description with
 * a size (gtk_font_chooser_get_font). Generic aliases (Sans, Serif, Monospace) and families without Latin
 * letters are left out. */
static int _act_watermark_fonts(char **res)
{
  PangoFontMap *map = pango_cairo_font_map_get_default();
  PangoFontFamily **fams = NULL;
  int n = 0;
  pango_font_map_list_families(map, &fams, &n);
  if(n > 1) qsort(fams, n, sizeof(*fams), _cmp_family);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "families");
  json_builder_begin_array(b);
  int listed = 0;
  for(int i = 0; i < n; i++)
  {
    const char *name = pango_font_family_get_name(fams[i]);
    if(!name || !_family_latin(name)) continue;
    PangoFontFace **faces = NULL;
    int nf = 0;
    pango_font_family_list_faces(fams[i], &faces, &nf);
    if(nf == 0)
    {
      g_free(faces);
      continue;
    }
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "family");
    json_builder_add_string_value(b, name);
    json_builder_set_member_name(b, "faces");
    json_builder_begin_array(b);
    for(int j = 0; j < nf; j++)
    {
      PangoFontDescription *d = pango_font_face_describe(faces[j]);
      pango_font_description_set_size(d, 10 * PANGO_SCALE);
      gchar *font = pango_font_description_to_string(d);
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "face");
      json_builder_add_string_value(b, pango_font_face_get_face_name(faces[j]));
      json_builder_set_member_name(b, "font");
      json_builder_add_string_value(b, font);
      json_builder_end_object(b);
      g_free(font);
      pango_font_description_free(d);
    }
    json_builder_end_array(b);
    json_builder_end_object(b);
    g_free(faces);
    listed++;
  }
  g_free(fams);
  json_builder_end_array(b);
  json_builder_set_member_name(b, "config");
  if(g_font_config) json_builder_add_string_value(b, g_font_config);
  else json_builder_add_null_value(b);
  json_builder_set_member_name(b, "default_config_fonts");
  if(g_default_config_fonts >= 0) json_builder_add_int_value(b, g_default_config_fonts);
  else json_builder_add_null_value(b);
  json_builder_end_object(b);
  *res = dtb__json_finish(b);
  (void)listed;
  return DTB_OK;
}

/* 1 = no such action here */
int dtb__module_action_p6e(dt_iop_module_t *m, const char *action, JsonObject *args, char **result)
{
  if(dt_iop_module_is(m, "borders") && !strcmp(action, "pick"))
  {
    const char *w = _arg_s(args, "which");
    static const char *const guards[2] = { "color[0]", "frame_color[0]" };
    if(w && !strcmp(w, "border")) return _act_pick_color(m, args, "color[0]", guards, 2, result);
    if(w && !strcmp(w, "frame")) return _act_pick_color(m, args, "frame_color[0]", guards, 2, result);
    return dtb__err(DTB_E_ARG, "which is \"border\" or \"frame\"");
  }
  if(dt_iop_module_is(m, "watermark"))
  {
    static const char *const guard[1] = { "color[0]" };
    if(!strcmp(action, "pick")) return _act_pick_color(m, args, "color[0]", guard, 1, result);
    if(!strcmp(action, "list_files")) return _act_watermark_files(m, result);
    if(!strcmp(action, "list_fonts")) return _act_watermark_fonts(result);
  }
  return 1;
}

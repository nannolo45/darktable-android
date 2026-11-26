// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the arithmetic of the bridge's picker actions (engine-api.md 6.3;
 * engine/src/actions.c wb_from_area, engine/src/actions3.c spot, pick, set_nodes_from_area, illuminant_from_area)
 * against darktable's own callbacks, compiled verbatim (pickref_extract.py, pickref_<op>.c).
 *
 *   dtb_pickref <workdir> <raw> <xmp> <outdir>       (DT_PREFIX: darktable's install prefix)
 *   dtb_pickref <workdir> <raw> <xmp> <outdir> p6a   brief P6a (test C43): rgb levels' auto levels, levels, invert,
 *                                                    split-toning, colorize, unbreak input profile, basic adjustments,
 *                                                    color equalizer, AgX (pickers and buttons), white balance presets'
 *                                                    temperature and tint
 *
 * Each case: the module's parameters before; the bridge's action on an area or a point; then darktable's callback
 * on a copy of the module whose parameters are those from before and whose picked_color, picked_color_min and
 * picked_color_max are what the action picked (the "picked" input of its answer: the same numbers the bridge used);
 * the parameters compared field by field. Prints RESULT lines, FAIL lines for a difference above the bound, and
 * PICK lines (the values picked on the default edit for fixed areas, as the emulator test p3 writes them: the
 * workflow compares the two). Unlike dtb_test, this driver is compiled with darktable's headers and the bridge's
 * internal header: it needs the module's own structure and the pipe the bridge picks on.
 */
#include "dtb_internal.h"
#include "pickref.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <glib/gstdio.h>

static int failures = 0;

#define MUST(x)                                                                          \
  do {                                                                                   \
    const int _r = (x);                                                                  \
    if(_r) { fprintf(stdout, "FAIL %s: %d %s\n", #x, _r, dtb_last_error()); exit(1); }  \
  } while(0)

static void engine_start(const char *workdir)
{
  const char *prefix = getenv("DT_PREFIX");
  if(!prefix) { fprintf(stdout, "FAIL DT_PREFIX not set\n"); exit(1); }
  g_mkdir(workdir, 0700);
  gchar *library = g_build_filename(workdir, "library.db", NULL);
  g_unlink(library);
  gchar *configdir = g_build_filename(workdir, "config", NULL);
  gchar *cachedir = g_build_filename(workdir, "cache", NULL);
  gchar *tmpdir = g_build_filename(workdir, "tmp", NULL);
  gchar *datadir = g_build_filename(prefix, "share/darktable", NULL);
  gchar *moduledir = g_build_filename(prefix, "lib/darktable", NULL);
  g_mkdir(configdir, 0700);
  const char *level = getenv("DTB_TEST_RESOURCELEVEL");
  if(level)
  {
    gchar *rc = g_build_filename(configdir, "darktablerc", NULL);
    gchar *text = g_strdup_printf("resourcelevel=%s\n", level);
    g_file_set_contents(rc, text, -1, NULL);
    g_free(text);
    g_free(rc);
  }
  dtb_paths_t p = { configdir, cachedir, datadir, moduledir, tmpdir, library };
  MUST(dtb_init(&p, 0));
}

static JsonObject *parse(char *s, JsonNode **keep)
{
  GError *e = NULL;
  *keep = json_from_string(s, &e);
  dtb_free(s);
  if(!*keep || !JSON_NODE_HOLDS_OBJECT(*keep)) { fprintf(stdout, "FAIL invalid JSON\n"); exit(1); }
  return json_node_get_object(*keep);
}

static int history_end(void)
{
  char *s = NULL;
  MUST(dtb_history(&s));
  JsonNode *n;
  const int end = (int)json_object_get_int_member(parse(s, &n), "end");
  json_node_unref(n);
  return end;
}

static double field(dt_iop_module_t *m, void *blob, const char *path)
{
  dtb_field_ref_t r;
  if(dtb__field_resolve(m, blob, path, &r)) return NAN;
  switch(r.f->header.type)
  {
    case DT_INTROSPECTION_TYPE_FLOAT: return *(float *)r.p;
    case DT_INTROSPECTION_TYPE_DOUBLE: return *(double *)r.p;
    case DT_INTROSPECTION_TYPE_UINT: return *(unsigned int *)r.p;
    case DT_INTROSPECTION_TYPE_USHORT: return *(unsigned short *)r.p;
    case DT_INTROSPECTION_TYPE_INT8: return *(int8_t *)r.p;
    default: return *(int *)r.p;  // int, enum, bool (gboolean)
  }
}

static void vec4(JsonObject *side, const char *what, float out[4])
{
  JsonArray *a = json_object_get_array_member(side, what);
  for(int k = 0; k < 4; k++)
  {
    JsonNode *n = json_array_get_element(a, k);
    out[k] = JSON_NODE_HOLDS_NULL(n) ? NAN : (float)json_node_get_double(n);
  }
}

typedef int (*ref_fn)(dt_iop_module_t *, dt_dev_pixelpipe_t *, pickref_widgets_t *);

/*
 * One case: [action] with [args] on [op] instance 0; darktable's callback [ref] with widgets [w]; the parameters
 * [paths] compared (absolute difference, relative for values above 1). [result_field]: a number of the answer
 * compared with the target lightness the callback wrote back to its slider (exposure's "measure"). Returns the
 * largest difference.
 */
static double run_case(const char *name, const char *op, const char *action, const char *args, ref_fn ref,
                       pickref_widgets_t w, const char *const *paths, int npaths, const char *result_field, double bound)
{
  dt_iop_module_t *m = dtb__find(op, 0);
  if(!m) { fprintf(stdout, "FAIL %s: no %s\n", name, op); failures++; return INFINITY; }
  void *before = dtb__memdup(m->params, m->params_size);
  char *s = NULL;
  const int rc = dtb_module_action(op, 0, action, args, &s);
  if(rc) { fprintf(stdout, "FAIL %s: %s %s: %d %s\n", name, op, action, rc, dtb_last_error()); failures++; g_free(before); return INFINITY; }
  JsonNode *keep;
  JsonObject *res = parse(s, &keep);
  JsonObject *picked = json_object_get_object_member(res, "picked");
  JsonNode *in = picked ? json_object_get_member(picked, "input") : NULL;
  if(!in || !JSON_NODE_HOLDS_OBJECT(in)) { fprintf(stdout, "FAIL %s: no picked input\n", name); failures++; json_node_unref(keep); g_free(before); return INFINITY; }
  JsonObject *io = json_node_get_object(in);

  m = dtb__find(op, 0);
  dt_iop_module_t copy = *m;
  copy.params = dtb__memdup(before, m->params_size);
  vec4(io, "mean", copy.picked_color);
  vec4(io, "min", copy.picked_color_min);
  vec4(io, "max", copy.picked_color_max);
  dtb_pipe_t *pp = NULL;
  MUST(dtb__pipe_ready(FALSE, &pp));
  const int rr = ref(&copy, &pp->pipe, &w);
  if(rr) { fprintf(stdout, "FAIL %s: darktable's callback did not apply (%d)\n", name, rr); failures++; }

  double worst = 0.0;
  GString *diff = g_string_new(NULL);
  for(int i = 0; i < npaths; i++)
  {
    const double a = field(m, m->params, paths[i]), b = field(m, copy.params, paths[i]), b0 = field(m, before, paths[i]);
    const double d = isnan(a) && isnan(b) ? 0.0 : fabs(a - b) / fmax(1.0, fabs(b));
    worst = fmax(worst, isnan(d) ? INFINITY : d);
    if(d > 0.0 || i < 4)
      g_string_append_printf(diff, " %s %.9g%s%.9g (was %.9g)", paths[i], a, d > 0.0 ? " vs darktable " : " = ", b, b0);
  }
  if(result_field)
  {
    const double a = json_object_get_double_member(res, result_field), b = w.lightness;
    const double d = fabs(a - b) / fmax(1.0, fabs(b));
    worst = fmax(worst, d);
    g_string_append_printf(diff, " answer %s %.9g%s%.9g", result_field, a, d > 0.0 ? " vs darktable " : " = ", b);
  }
  fprintf(stdout, "RESULT %s: %s %s, picked mean %.6f %.6f %.6f, max difference %.3g:%s\n", name, op, action,
          copy.picked_color[0], copy.picked_color[1], copy.picked_color[2], worst, diff->str);
  if(!(worst <= bound))
  {
    fprintf(stdout, "FAIL %s: the bridge's %s %s differs from darktable's callback by %.3g (bound %.1g)\n", name, op, action, worst, bound);
    failures++;
  }
  g_string_free(diff, TRUE);
  g_free(copy.params);
  g_free(before);
  json_node_unref(keep);
  return worst;
}

static void picks_on_default_edit(const char *raw)
{
  // the values the emulator test p3 picks on the same photo without a sidecar (P3cScenarioTest)
  MUST(dtb_open(raw, NULL));
  static const struct { const char *name, *req; } reqs[] = {
    { "area", "{\"area\":[0.40,0.35,0.62,0.55]}" },
    { "corner", "{\"area\":[0.05,0.05,0.25,0.30]}" },
    { "point", "{\"point\":[0.5,0.5]}" },
  };
  const char *ops[] = { "temperature", "exposure", NULL };
  for(int i = 0; i < 3; i++)
    for(int k = 0; k < 3; k++)
    {
      char *s = NULL;
      MUST(dtb_color_pick(ops[k], 0, reqs[i].req, &s));
      JsonNode *keep;
      JsonObject *o = parse(s, &keep);
      JsonObject *side = json_object_get_object_member(o, ops[k] ? "input" : "output");
      float mean[4], mn[4], mx[4];
      vec4(side, "mean", mean);
      vec4(side, "min", mn);
      vec4(side, "max", mx);
      fprintf(stdout, "PICK %s %s %s mean %.6f %.6f %.6f min %.6f %.6f %.6f max %.6f %.6f %.6f\n", reqs[i].name,
              ops[k] ? ops[k] : "global", json_object_get_string_member(side, "colorspace"), mean[0], mean[1], mean[2],
              mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
      json_node_unref(keep);
    }
  MUST(dtb_close());
}

/* ------------------------------------------------------------------ */
/* brief P6a (C43): the pickers and buttons of P6a against desktop's callbacks                         */

/* a module action without a pick (AgX's buttons): its parameters against darktable's callback on a copy */
static double run_button(const char *name, const char *op, const char *action, const char *args, ref_fn ref,
                         pickref_widgets_t w, const char *const *paths, int npaths, double bound)
{
  dt_iop_module_t *m = dtb__find(op, 0);
  if(!m) { fprintf(stdout, "FAIL %s: no %s\n", name, op); failures++; return INFINITY; }
  void *before = dtb__memdup(m->params, m->params_size);
  char *s = NULL;
  const int rc = dtb_module_action(op, 0, action, args, &s);
  dtb_free(s);
  if(rc) { fprintf(stdout, "FAIL %s: %s %s: %d %s\n", name, op, action, rc, dtb_last_error()); failures++; g_free(before); return INFINITY; }
  m = dtb__find(op, 0);
  dt_iop_module_t copy = *m;
  copy.params = dtb__memdup(before, m->params_size);
  dtb_pipe_t *pp = NULL;
  MUST(dtb__pipe_ready(FALSE, &pp));
  ref(&copy, &pp->pipe, &w);
  double worst = 0.0;
  GString *diff = g_string_new(NULL);
  for(int i = 0; i < npaths; i++)
  {
    const double a = field(m, m->params, paths[i]), b = field(m, copy.params, paths[i]), b0 = field(m, before, paths[i]);
    const double d = isnan(a) && isnan(b) ? 0.0 : fabs(a - b) / fmax(1.0, fabs(b));
    worst = fmax(worst, isnan(d) ? INFINITY : d);
    if(d > 0.0 || i < 4)
      g_string_append_printf(diff, " %s %.9g%s%.9g (was %.9g)", paths[i], a, d > 0.0 ? " vs darktable " : " = ", b, b0);
  }
  fprintf(stdout, "RESULT %s: %s %s, max difference %.3g:%s\n", name, op, action, worst, diff->str);
  if(!(worst <= bound))
  {
    fprintf(stdout, "FAIL %s: the bridge's %s %s differs from darktable's callback by %.3g (bound %.1g)\n", name, op, action, worst, bound);
    failures++;
  }
  g_string_free(diff, TRUE);
  g_free(copy.params);
  g_free(before);
  return worst;
}

typedef struct p6a_auto_t
{
  float box_cood[4];
  int channel;
  void *params;
} p6a_auto_t;

static void p6a_auto_ref(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const float *in, const dt_iop_roi_t *roi_in,
                         void *user)
{
  p6a_auto_t *a = user;
  pickref_rgblevels_auto(self, piece, in, roi_in, a->box_cood, a->channel, a->params);
}

/* rgb levels' auto levels: the bridge's action, then desktop's _get_selected_area and _auto_levels on the same
 * input buffer (a second render of the module's input) with the box desktop keeps for the area */
static double run_auto_levels(const char *name, const char *args, const float *area, const int channel, double bound)
{
  dt_iop_module_t *m = dtb__find("rgblevels", 0);
  void *before = dtb__memdup(m->params, m->params_size);
  char *s = NULL;
  const int rc = dtb_module_action("rgblevels", 0, "auto_levels", args, &s);
  dtb_free(s);
  if(rc) { fprintf(stdout, "FAIL %s: auto_levels: %d %s\n", name, rc, dtb_last_error()); failures++; g_free(before); return INFINITY; }
  m = dtb__find("rgblevels", 0);
  p6a_auto_t a = { { 0 }, channel, dtb__memdup(before, m->params_size) };
  if(area) dtb__area_to_input(area, a.box_cood);
  MUST(dtb__module_input(m, p6a_auto_ref, &a));
  static const char *const lv[9] = { "levels[0][0]", "levels[0][1]", "levels[0][2]", "levels[1][0]", "levels[1][1]",
                                     "levels[1][2]", "levels[2][0]", "levels[2][1]", "levels[2][2]" };
  double worst = 0.0;
  GString *diff = g_string_new(NULL);
  for(int i = 0; i < 9; i++)
  {
    const double x = field(m, m->params, lv[i]), y = field(m, a.params, lv[i]);
    const double d = fabs(x - y);
    worst = fmax(worst, d);
    if(d > 0.0 || i < 3) g_string_append_printf(diff, " %s %.9g%s%.9g", lv[i], x, d > 0.0 ? " vs darktable " : " = ", y);
  }
  fprintf(stdout, "RESULT %s: rgblevels auto_levels, max difference %.3g:%s\n", name, worst, diff->str);
  if(!(worst <= bound)) { fprintf(stdout, "FAIL %s: differs by %.3g\n", name, worst); failures++; }
  g_string_free(diff, TRUE);
  g_free(a.params);
  g_free(before);
  return worst;
}

static int run_p6a(const char *raw, const char *xmp)
{
  MUST(dtb_open(raw, xmp));
  static const char *const on[] = { "rgblevels", "levels", "invert", "splittoning", "colorize", "profile_gamma", "basicadj",
                                    "colorequal", "agx" };
  for(size_t i = 0; i < G_N_ELEMENTS(on); i++) MUST(dtb_set_enabled(on[i], 0, 1));
  MUST(dtb_history_seal());
  const int end0 = history_end();
  const double bound = 1e-6;
  double worst = 0.0;
  const char *A = "\"area\":[0.40,0.35,0.62,0.55]", *B = "\"area\":[0.05,0.05,0.25,0.30]", *P = "\"point\":[0.5,0.5]";
  char args[512];

#define CASE6(name, op, action, argfmt, argval, ref, w, paths)                                               \
  do {                                                                                                      \
    snprintf(args, sizeof(args), argfmt, argval);                                                           \
    worst = fmax(worst, run_case(name, op, action, args, ref, w, paths, G_N_ELEMENTS(paths), NULL, bound));  \
    MUST(dtb_history_goto(end0));                                                                           \
  } while(0)

  // rgb levels: "auto" (the whole picture) and "auto region", linked channels; then independent channels, G
  {
    const float area[4] = { 0.40f, 0.35f, 0.62f, 0.55f };
    worst = fmax(worst, run_auto_levels("rgblevels_auto", "{}", NULL, 0, bound));
    MUST(dtb_history_goto(end0));
    worst = fmax(worst, run_auto_levels("rgblevels_auto_region", "{\"area\":[0.40,0.35,0.62,0.55]}", area, 0, bound));
    MUST(dtb_history_goto(end0));
    MUST(dtb_params_set("rgblevels", 0, "{\"autoscale\":\"DT_IOP_RGBLEVELS_INDEPENDENT_CHANNELS\"}"));
    MUST(dtb_history_seal());
    worst = fmax(worst, run_auto_levels("rgblevels_auto_independent_G", "{\"channel\":1}", NULL, 1, bound));
    MUST(dtb_history_goto(end0));
    MUST(dtb_params_set("rgblevels", 0, "{\"preserve_colors\":\"DT_RGB_NORM_NONE\"}"));
    MUST(dtb_history_seal());
    worst = fmax(worst, run_auto_levels("rgblevels_auto_region_no_norm", "{\"area\":[0.05,0.05,0.25,0.30]}",
                                        (const float[4]){ 0.05f, 0.05f, 0.25f, 0.30f }, 0, bound));
    MUST(dtb_history_goto(end0));
  }

  // levels (legacy): black, grey, white on a point
  static const char *const lev[] = { "levels[0]", "levels[1]", "levels[2]" };
  const char *which3[3] = { "black", "grey", "white" };
  for(int k = 0; k < 3; k++)
  {
    pickref_widgets_t wl = { 0 };
    wl.which = k;
    gchar *n = g_strdup_printf("levels_%s", which3[k]);
    gchar *f = g_strdup_printf("{\"which\":\"%s\",%%s}", which3[k]);
    CASE6(n, "levels", "pick", f, P, pickref_levels, wl, lev);
    g_free(n); g_free(f);
  }

  // invert: the film colour
  static const char *const inv[] = { "color[0]", "color[1]", "color[2]", "color[3]" };
  pickref_widgets_t w0 = { 0 };
  CASE6("invert_color", "invert", "pick", "{%s}", A, pickref_invert, w0, inv);

  // split-toning: shadows and highlights hue and saturation
  static const char *const sts[] = { "shadow_hue", "shadow_saturation", "highlight_hue", "highlight_saturation" };
  pickref_widgets_t ws = { 0 };
  ws.which = 0;
  CASE6("splittoning_shadows", "splittoning", "pick", "{\"which\":\"shadows\",%s}", P, pickref_splittoning, ws, sts);
  ws.which = 1;
  CASE6("splittoning_highlights", "splittoning", "pick", "{\"which\":\"highlights\",%s}", B, pickref_splittoning, ws, sts);

  // colorize
  static const char *const cz[] = { "hue", "saturation" };
  CASE6("colorize_hue", "colorize", "pick", "{%s}", P, pickref_colorize, w0, cz);

  // unbreak input profile: the four pickers (log mode, its default)
  static const char *const pg[] = { "grey_point", "shadows_range", "dynamic_range" };
  const char *pgw[4] = { "grey", "black", "dynamic_range", "auto" };
  for(int k = 0; k < 4; k++)
  {
    pickref_widgets_t wp = { 0 };
    wp.which = k;
    gchar *n = g_strdup_printf("profile_gamma_%s", pgw[k]);
    gchar *f = g_strdup_printf("{\"which\":\"%s\",%%s}", pgw[k]);
    CASE6(n, "profile_gamma", "pick", f, k % 2 ? B : A, pickref_profile_gamma, wp, pg);
    g_free(n); g_free(f);
  }

  // basic adjustments: middle grey
  static const char *const ba[] = { "middle_grey" };
  CASE6("basicadj_middle_grey", "basicadj", "pick_grey", "{%s}", A, pickref_basicadj, w0, ba);

  // color equalizer: white level
  static const char *const ce[] = { "white_level" };
  CASE6("colorequal_white_level", "colorequal", "white_level", "{%s}", A, pickref_colorequal, w0, ce);

  // AgX: the five pickers, then with "keep the pivot on the diagonal" off; the three buttons
  static const char *const ag[] = { "range_black_relative_ev", "range_white_relative_ev", "curve_pivot_x",
                                    "curve_pivot_y_linear_output", "curve_gamma", "red_inset", "red_outset",
                                    "green_rotation", "green_unrotation", "blue_inset", "blue_outset",
                                    "master_outset_ratio", "master_unrotation_ratio", "base_primaries" };
  const char *agw[5] = { "black", "white", "range", "pivot_x", "pivot_xy" };
  for(int pass = 0; pass < 2; pass++)
  {
    if(pass)
    {
      MUST(dtb_params_set("agx", 0, "{\"auto_gamma\":false,\"curve_gamma\":1.8}"));
      MUST(dtb_history_seal());
    }
    const int endp = history_end();
    for(int k = 0; k < 5; k++)
    {
      pickref_widgets_t wa = { 0 };
      wa.which = k;
      gchar *n = g_strdup_printf("agx_%s%s", agw[k], pass ? "_manual_gamma" : "");
      gchar *f = g_strdup_printf("{\"which\":\"%s\",%%s}", agw[k]);
      snprintf(args, sizeof(args), f, k == 1 ? B : A);
      worst = fmax(worst, run_case(n, "agx", "pick", args, pickref_agx, wa, ag, G_N_ELEMENTS(ag), NULL, bound));
      MUST(dtb_history_goto(endp));
      g_free(n); g_free(f);
    }
  }
  MUST(dtb_history_goto(end0));
  {
    // read exposure: the exposure the bridge finds (exposure's own parameters and the image's biases) is given to
    // desktop's callback in place of dt_dev_exposure_get_effective_exposure (only the darkroom view has it)
    dt_iop_module_t *ex = dtb__find("exposure", 0);
    double ev = ex && ex->enabled ? field(ex, ex->params, "exposure") : 0.0;
    const dt_image_t *img = &dtb_S.dev.image_storage;
    if(ex && ex->enabled && field(ex, ex->params, "compensate_exposure_bias") != 0.0 && img->exif_exposure_bias != DT_EXIF_TAG_UNINITIALIZED)
      ev -= CLAMP(img->exif_exposure_bias, -5.0f, 5.0f);
    if(ex && ex->enabled && field(ex, ex->params, "compensate_hilite_pres") != 0.0 && img->exif_highlight_preservation > 0.0f)
      ev += CLAMP(img->exif_highlight_preservation, -1.0f, 4.0f);
    pickref_widgets_t wa = { 0 };
    wa.which = 10;
    wa.lightness = (float)ev;
    fprintf(stdout, "RESULT agx read exposure: effective exposure %.6f EV\n", ev);
    worst = fmax(worst, run_button("agx_read_exposure", "agx", "read_exposure", NULL, pickref_agx, wa, ag, G_N_ELEMENTS(ag), bound));
    MUST(dtb_history_goto(end0));
    const char *pr[3] = { "blender", "smooth", "unmodified" };
    for(int k = 0; k < 3; k++)
    {
      wa.which = 11 + k;
      gchar *n = g_strdup_printf("agx_primaries_%s", pr[k]);
      gchar *a = g_strdup_printf("{\"preset\":\"%s\"}", pr[k]);
      worst = fmax(worst, run_button(n, "agx", "reset_primaries", a, pickref_agx, wa, ag, G_N_ELEMENTS(ag), bound));
      MUST(dtb_history_goto(end0));
      g_free(n); g_free(a);
    }
    MUST(dtb_params_set("agx", 0, "{\"red_inset\":0.2,\"green_rotation\":0.2,\"blue_inset\":0.1}"));
    MUST(dtb_history_seal());
    wa.which = 14;
    worst = fmax(worst, run_button("agx_primaries_from_above", "agx", "primaries_from_above", NULL, pickref_agx, wa, ag,
                                   G_N_ELEMENTS(ag), bound));
    MUST(dtb_history_goto(end0));
  }

  // white balance (item 1): every camera preset of this camera, temperature and tint against desktop's _mul2temp
  {
    char *s = NULL;
    MUST(dtb_module_action("temperature", 0, "list_settings", NULL, &s));
    JsonParser *jp = json_parser_new();
    json_parser_load_from_data(jp, s, -1, NULL);
    dtb_free(s);
    JsonArray *l = json_node_get_array(json_parser_get_root(jp));
    double wt = 0.0, wtint = 0.0;
    int n = 0;
    for(guint i = 0; i < json_array_get_length(l); i++)
    {
      JsonObject *o = json_array_get_object_element(l, i);
      if(strcmp(json_object_get_string_member(o, "kind"), "camera")) continue;
      const int tmin = (int)json_object_get_int_member(o, "tuning_min"), tmax = (int)json_object_get_int_member(o, "tuning_max");
      for(int t = tmin; t <= tmax; t++)
      {
        gchar *a = g_strdup_printf("{\"name\":\"%s\",\"tuning\":%d}", json_object_get_string_member(o, "name"), t);
        char *r = NULL;
        MUST(dtb_module_action("temperature", 0, "apply_preset", a, &r));
        dtb_free(r);
        g_free(a);
        MUST(dtb_module_action("temperature", 0, "get_wb", NULL, &r));
        JsonNode *keep;
        JsonObject *wb = parse(r, &keep);
        const double T = json_object_get_double_member(wb, "temperature"), tint = json_object_get_double_member(wb, "tint");
        json_node_unref(keep);
        dt_iop_module_t *m = dtb__find("temperature", 0);
        dt_iop_module_t copy = *m;
        float dT = 0.f, dtint = 0.f;
        pickref_temperature_mul2temp(&copy, &dT, &dtint);
        wt = fmax(wt, fabs(T - dT));
        wtint = fmax(wtint, fabs(tint - dtint));
        if(t == tmin) fprintf(stdout, "RESULT wb '%s' tuning %d: bridge %.2f K tint %.5f, darktable %.2f K tint %.5f\n",
                              json_object_get_string_member(o, "name"), t, T, tint, dT, dtint);
        n++;
      }
    }
    g_object_unref(jp);
    fprintf(stdout, "RESULT wb presets: %d preset tunings, temperature largest difference %.3g K, tint %.3g\n", n, wt, wtint);
    if(!(wt <= 1e-3 && wtint <= 1e-6)) { fprintf(stdout, "FAIL wb presets: temperature/tint differ from desktop's _mul2temp\n"); failures++; }
    MUST(dtb_history_goto(end0));
  }

  fprintf(stdout, "RESULT all P6a cases: largest difference %.3g (bound %.1g), %d failures\n", worst, bound, failures);
  dtb_close();
  return failures;
}

int main(int argc, char **argv)
{
  if(argc != 5 && !(argc == 6 && !strcmp(argv[5], "p6a")))
  {
    fprintf(stderr, "usage: dtb_pickref <workdir> <raw> <xmp> <outdir> [p6a]\n");
    return 2;
  }
  setvbuf(stdout, NULL, _IOLBF, 0);
  engine_start(argv[1]);
  const char *raw = argv[2], *xmp = argv[3];
  if(argc == 6)
  {
    // brief P6a (test C43)
    const int f = run_p6a(raw, xmp);
    dtb_shutdown();
    return f ? 1 : 0;
  }

  picks_on_default_edit(raw);

  MUST(dtb_open(raw, xmp));
  // the modules the actions need, on (a picker picks on modules that are on)
  MUST(dtb_set_enabled("rgblevels", 0, 1));
  MUST(dtb_set_enabled("rgbcurve", 0, 1));
  MUST(dtb_set_enabled("channelmixerrgb", 0, 1));
  MUST(dtb_history_seal());
  const int end0 = history_end();
  const double bound = 1e-6;
  double worst = 0.0;
  const char *A = "\"area\":[0.40,0.35,0.62,0.55]", *B = "\"area\":[0.05,0.05,0.25,0.30]", *P = "\"point\":[0.5,0.5]";
  char args[512];

#define CASE(name, op, action, argfmt, argval, ref, w, paths)                                               \
  do {                                                                                                      \
    snprintf(args, sizeof(args), argfmt, argval);                                                           \
    worst = fmax(worst, run_case(name, op, action, args, ref, w, paths, G_N_ELEMENTS(paths), NULL, bound));  \
    MUST(dtb_history_goto(end0));                                                                           \
  } while(0)

  // white balance: temperature.c color_picker_apply
  static const char *const wb[] = { "red", "green", "blue", "various" };
  pickref_widgets_t w0 = { 0 };
  CASE("wb_area", "temperature", "wb_from_area", "{%s}", A, pickref_temperature, w0, wb);
  CASE("wb_corner", "temperature", "wb_from_area", "{%s}", B, pickref_temperature, w0, wb);

  // exposure: exposure.c color_picker_apply -> _auto_set_exposure, correction to 50 % and 30 %, and measure
  static const char *const ex[] = { "exposure", "black" };
  pickref_widgets_t we = { 0 };
  we.mode = 0; we.lightness = 50.f;
  CASE("exposure_correction_50", "exposure", "spot", "{\"mode\":\"correction\",\"lightness\":50,%s}", A, pickref_exposure, we, ex);
  we.lightness = 30.f;
  CASE("exposure_correction_30", "exposure", "spot", "{\"mode\":\"correction\",\"lightness\":30,%s}", B, pickref_exposure, we, ex);
  {
    pickref_widgets_t wm = { 0 };
    wm.mode = 1; wm.lightness = 50.f;
    snprintf(args, sizeof(args), "{\"mode\":\"measure\",%s}", A);
    worst = fmax(worst, run_case("exposure_measure", "exposure", "spot", args, pickref_exposure, wm, ex, G_N_ELEMENTS(ex),
                                 "lightness", bound));
    MUST(dtb_history_goto(end0));
  }

  // rgb levels: rgblevels.c color_picker_apply, black / grey / white on a point and an area, linked channels
  static const char *const lv[] = { "levels[0][0]", "levels[0][1]", "levels[0][2]" };
  const char *which[3] = { "black", "grey", "white" };
  for(int k = 0; k < 3; k++)
  {
    pickref_widgets_t wl = { 0 };
    wl.which = k; wl.channel = 0;
    gchar *n1 = g_strdup_printf("levels_%s_point", which[k]), *n2 = g_strdup_printf("levels_%s_area", which[k]);
    gchar *f1 = g_strdup_printf("{\"which\":\"%s\",%%s}", which[k]);
    CASE(n1, "rgblevels", "pick", f1, P, pickref_rgblevels, wl, lv);
    CASE(n2, "rgblevels", "pick", f1, A, pickref_rgblevels, wl, lv);
    g_free(n1); g_free(n2); g_free(f1);
  }

  // rgb curve: rgbcurve.c color_picker_apply ("create curve"), linked channels, then independent channel G
  {
    const char *cn[2 + 2 * 20 + 1];
    gchar *own[2 + 2 * 20 + 1];
    int n = 0;
    for(int ch = 0; ch < 2; ch++)
    {
      n = 0;
      own[n] = g_strdup_printf("curve_num_nodes[%d]", ch); cn[n] = own[n]; n++;
      own[n] = g_strdup_printf("curve_type[%d]", ch); cn[n] = own[n]; n++;
      for(int k = 0; k < 20; k++)
      {
        own[n] = g_strdup_printf("curve_nodes[%d][%d].x", ch, k); cn[n] = own[n]; n++;
        own[n] = g_strdup_printf("curve_nodes[%d][%d].y", ch, k); cn[n] = own[n]; n++;
      }
      pickref_widgets_t wc = { 0 };
      wc.channel = ch;
      if(ch == 1)
      {
        MUST(dtb_params_set("rgbcurve", 0, "{\"curve_autoscale\":\"DT_S_SCALE_MANUAL_RGB\",\"compensate_middle_grey\":true}"));
        MUST(dtb_history_seal());
      }
      snprintf(args, sizeof(args), "{\"channel\":%d,%s}", ch, ch ? B : A);
      gchar *name = g_strdup_printf("rgbcurve_channel_%d%s", ch, ch ? "_independent_compensated" : "_linked");
      worst = fmax(worst, run_case(name, "rgbcurve", "set_nodes_from_area", args, pickref_rgbcurve, wc, cn, n, NULL, bound));
      g_free(name);
      for(int i = 0; i < n; i++) g_free(own[i]);
      MUST(dtb_history_goto(end0));
    }
  }

  // color calibration: channelmixerrgb.c color_picker_apply -> _auto_set_illuminant, "correction"
  static const char *const cm[] = { "x", "y", "illuminant", "temperature", "adaptation" };
  pickref_widgets_t wn = { 0 };
  wn.mode = 0; wn.lightness = 50.f; wn.hue = 0.f; wn.chroma = 0.f; wn.use_mixing = 1;
  CASE("calibration_neutral", "channelmixerrgb", "illuminant_from_area", "{\"use_mixing\":true,%s}", A, pickref_channelmixerrgb, wn, cm);
  pickref_widgets_t wt = { 0 };
  wt.mode = 0; wt.lightness = 60.f; wt.hue = 45.f; wt.chroma = 20.f; wt.use_mixing = 0;
  CASE("calibration_target", "channelmixerrgb", "spot",
       "{\"mode\":\"correction\",\"target\":{\"lightness\":60,\"hue\":45,\"chroma\":20},\"use_mixing\":false,%s}", B,
       pickref_channelmixerrgb, wt, cm);
  wt.use_mixing = 1;
  CASE("calibration_target_mixing", "channelmixerrgb", "spot",
       "{\"mode\":\"correction\",\"target\":{\"lightness\":60,\"hue\":45,\"chroma\":20},\"use_mixing\":true,%s}", A,
       pickref_channelmixerrgb, wt, cm);

  fprintf(stdout, "RESULT all cases: largest difference %.3g (bound %.1g), %d failures\n", worst, bound, failures);
  dtb_close();
  dtb_shutdown();
  (void)argv[4];
  return failures ? 1 : 0;
}

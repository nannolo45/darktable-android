// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the arithmetic of the bridge's picker actions (engine-api.md 6.3;
 * engine/src/actions.c wb_from_area, engine/src/actions3.c spot, pick, set_nodes_from_area, illuminant_from_area)
 * against darktable's own callbacks, compiled verbatim (pickref_extract.py, pickref_<op>.c).
 *
 *   dtb_pickref <workdir> <raw> <xmp> <outdir>       (DT_PREFIX: darktable's install prefix)
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

int main(int argc, char **argv)
{
  if(argc != 5) { fprintf(stderr, "usage: dtb_pickref <workdir> <raw> <xmp> <outdir>\n"); return 2; }
  setvbuf(stdout, NULL, _IOLBF, 0);
  engine_start(argv[1]);
  const char *raw = argv[2], *xmp = argv[3];

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

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Test driver for the engine bridge (briefs S3 and P1a). Uses dtbridge.h,
 * libc and json-glib (to read the bridge's JSON); never darktable's headers.
 *
 *   dtb_test <workdir> <command> [args]
 *
 * Environment: DT_PREFIX = darktable install prefix (share/darktable and
 * lib/darktable below it). <workdir> holds the config, cache and private
 * library of this run; the library is recreated for every invocation.
 *
 * Commands (run_tests.sh drives them and compares with darktable-cli):
 *   check <xmp>                    print the guard's JSON; exit 3 if problems
 *   render <raw> <xmp> <out.ppm>   T1: full render at scale 1
 *   param <raw> <xmp> <outdir>     T2: exposure +1 EV
 *   instances <raw> <xmp> <outdir> T3
 *   history <raw> <xmp> <outdir>   T4
 *   region <raw> <xmp> <outdir>    T5: render_region equals the crop of the full render
 *   guard <raw> <xmp> <outdir>     T6 (expects outdir/newer.xmp, outdir/unknown.xmp)
 *   timing <raw> <xmp> <outdir>    T7
 *   cancel <raw> <xmp> <outdir>    T8
 *   mask <raw> <xmp> <outdir>      T9
 *   extras <raw> <xmp> <outdir>    X1: preview, export, move, delete
 *   api <raw> <xmp> <outdir>       C1: engine_info, image_info, modules JSON, can_switch
 *   params <raw> <xmp> <outdir>    C2: paths, types, ranges, params_set, grouping, reset
 *   undo <raw> <xmp> <outdir>      C3: undo/redo equality over whole-history states
 *   actions <raw> <xmp> <outdir>   C4: module_action (temperature, lens, lut3d, denoiseprofile)
 *   threads <raw> <xmp> <outdir>   C5: set_threads, set_thread_cpus
 *   readonly <raw> <xmp> <outdir>  C6: read-only refusal (expects outdir/newer.xmp)
 *   output <raw> <xmp> <outdir>    C7: atomic xmp_write, export options, metadata_reload
 *   exposures <raw> <xmp>          list exposure instances (fresh session)
 *   modules <raw> <xmp>            print modules and history JSON
 * Results are "RESULT key value" lines; exit code 0 = the checks the driver
 * can do on its own passed. Pixel comparisons with darktable-cli are done by
 * run_tests.sh.
 */
#include "dtbridge.h"

#include <dirent.h>
#include <errno.h>
#include <json-glib/json-glib.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int g_fail = 0;

#define CHECK(cond, ...)                                                \
  do {                                                                  \
    if(!(cond)) {                                                       \
      fprintf(stdout, "FAIL %s:%d: ", __FILE__, __LINE__);              \
      fprintf(stdout, __VA_ARGS__);                                     \
      fprintf(stdout, " [%s]\n", dtb_last_error());                     \
      g_fail = 1;                                                       \
    }                                                                   \
  } while(0)

#define MUST(expr)                                                      \
  do {                                                                  \
    const int _r = (expr);                                              \
    if(_r != DTB_OK) {                                                  \
      fprintf(stdout, "FAIL %s:%d: %s returned %d [%s]\n", __FILE__,     \
              __LINE__, #expr, _r, dtb_last_error());                   \
      exit(1);                                                          \
    }                                                                   \
  } while(0)

/* expect a given return code */
#define EXPECT(expr, code)                                              \
  do {                                                                  \
    const int _r = (expr);                                              \
    if(_r != (code)) {                                                  \
      fprintf(stdout, "FAIL %s:%d: %s returned %d, expected %d [%s]\n", \
              __FILE__, __LINE__, #expr, _r, (code), dtb_last_error()); \
      g_fail = 1;                                                       \
    }                                                                   \
  } while(0)

static double now(void)
{
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec + t.tv_nsec * 1e-9;
}

static char *path_join(const char *a, const char *b)
{
  char *r = malloc(strlen(a) + strlen(b) + 2);
  sprintf(r, "%s/%s", a, b);
  return r;
}

static void write_ppm(const char *path, const uint8_t *rgba, int w, int h)
{
  FILE *f = fopen(path, "wb");
  if(!f)
  {
    fprintf(stdout, "FAIL cannot write %s: %s\n", path, strerror(errno));
    exit(1);
  }
  fprintf(f, "P6\n%d %d\n255\n", w, h);
  uint8_t *row = malloc((size_t)3 * w);
  for(int y = 0; y < h; y++)
  {
    for(int x = 0; x < w; x++)
      memcpy(row + 3 * x, rgba + 4 * ((size_t)y * w + x), 3);
    fwrite(row, 3, w, f);
  }
  free(row);
  fclose(f);
}

static char *read_file(const char *path)
{
  gchar *s = NULL;
  if(!g_file_get_contents(path, &s, NULL, NULL)) return NULL;
  return s;
}

static void engine_start_ext(const char *workdir, int threads, const char *extra_rc)
{
  const char *prefix = getenv("DT_PREFIX");
  if(!prefix)
  {
    fprintf(stdout, "FAIL DT_PREFIX not set\n");
    exit(1);
  }
  mkdir(workdir, 0700);
  char *library = path_join(workdir, "library.db");
  unlink(library);  // a fresh private library for every run
  char *datadir = path_join(prefix, "share/darktable");
  char *moduledir = path_join(prefix, "lib/darktable");
  char *configdir = path_join(workdir, "config");
  char *cachedir = path_join(workdir, "cache");
  char *tmpdir = path_join(workdir, "tmp");
  mkdir(configdir, 0700);
  // DTB_TEST_RESOURCELEVEL: darktable's memory budget for runs on a machine
  // shared with other work; run_tests.sh gives darktable-cli the same
  const char *level = getenv("DTB_TEST_RESOURCELEVEL");
  gchar *rc_all = g_strdup_printf("%s%s%s%s", extra_rc ? extra_rc : "", level ? "resourcelevel=" : "",
                                  level ? level : "", level ? "\n" : "");
  extra_rc = rc_all[0] ? rc_all : NULL;
  if(extra_rc)
  {
    // darktable reads darktablerc at start-up; the app can do the same
    char *rc = path_join(configdir, "darktablerc");
    FILE *f = fopen(rc, "w");
    if(f)
    {
      fputs(extra_rc, f);
      fclose(f);
    }
    free(rc);
  }
  dtb_paths_t p = { configdir, cachedir, datadir, moduledir, tmpdir, library };
  MUST(dtb_init(&p, threads));
}

static void engine_start(const char *workdir) { engine_start_ext(workdir, 0, NULL); }

/* ---------------------------------------------------------------- */
/* JSON helpers                                                      */

static JsonNode *jparse_owned(char *s)
{
  if(!s)
  {
    fprintf(stdout, "FAIL no JSON returned [%s]\n", dtb_last_error());
    exit(1);
  }
  GError *e = NULL;
  JsonNode *n = json_from_string(s, &e);
  if(!n)
  {
    fprintf(stdout, "FAIL invalid JSON from the bridge: %s\n", e ? e->message : "?");
    exit(1);
  }
  dtb_free(s);
  return n;
}

static char *get_modules_str(void)
{
  char *s = NULL;
  MUST(dtb_modules(&s));
  return s;
}

static char *get_history_str(void)
{
  char *s = NULL;
  MUST(dtb_history(&s));
  return s;
}

static JsonNode *get_modules(void) { return jparse_owned(get_modules_str()); }
static JsonNode *get_history(void) { return jparse_owned(get_history_str()); }

static JsonNode *get_json(int (*f)(char **))
{
  char *s = NULL;
  MUST(f(&s));
  return jparse_owned(s);
}

static JsonObject *find_mod(JsonNode *mods, const char *op, int prio)
{
  JsonArray *a = json_node_get_array(mods);
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(!strcmp(json_object_get_string_member(o, "op"), op)
       && json_object_get_int_member(o, "priority") == prio)
      return o;
  }
  return NULL;
}

/* a member of params at a JSON pointer-like path "a/0/b" */
static JsonNode *param_node(JsonObject *mod, const char *path)
{
  JsonNode *n = json_object_get_member(mod, "params");
  gchar **parts = g_strsplit(path, "/", -1);
  for(int i = 0; parts[i] && n; i++)
  {
    if(JSON_NODE_TYPE(n) == JSON_NODE_OBJECT)
      n = json_object_get_member(json_node_get_object(n), parts[i]);
    else if(JSON_NODE_TYPE(n) == JSON_NODE_ARRAY)
    {
      JsonArray *a = json_node_get_array(n);
      const guint k = atoi(parts[i]);
      n = k < json_array_get_length(a) ? json_array_get_element(a, k) : NULL;
    }
    else
      n = NULL;
  }
  g_strfreev(parts);
  return n;
}

static double param_num(const char *op, int prio, const char *path)
{
  JsonNode *m = get_modules();
  JsonObject *o = find_mod(m, op, prio);
  JsonNode *n = o ? param_node(o, path) : NULL;
  const double v = n && JSON_NODE_TYPE(n) == JSON_NODE_VALUE ? json_node_get_double(n) : NAN;
  json_node_unref(m);
  return v;
}

static int module_info(const char *op, int prio, double *exposure, char *name, size_t name_len,
                       int *enabled)
{
  JsonNode *m = get_modules();
  JsonObject *o = find_mod(m, op, prio);
  if(o)
  {
    if(name) g_strlcpy(name, json_object_get_string_member(o, "instance_name"), name_len);
    if(enabled) *enabled = json_object_get_boolean_member(o, "enabled");
    if(exposure)
    {
      JsonNode *n = param_node(o, "exposure");
      *exposure = n ? json_node_get_double(n) : NAN;
    }
  }
  json_node_unref(m);
  return o != NULL;
}

static int history_count(int *end)
{
  JsonNode *h = get_history();
  JsonObject *o = json_node_get_object(h);
  const int n = json_array_get_length(json_object_get_array_member(o, "items"));
  if(end) *end = json_object_get_int_member(o, "end");
  json_node_unref(h);
  return n;
}

static void print_exposure_instances(const char *tag)
{
  for(int prio = 0; prio < 8; prio++)
  {
    double v = NAN;
    char name[128];
    int en = 0;
    if(module_info("exposure", prio, &v, name, sizeof(name), &en))
      fprintf(stdout, "RESULT %s exposure.%d name='%s' enabled=%d exposure=%.4f\n", tag, prio, name, en, v);
  }
}

static int xmp_problems(const char *path)
{
  char *s = NULL;
  if(dtb_xmp_check(path, &s)) return -2;
  JsonNode *n = jparse_owned(s);
  JsonObject *o = json_node_get_object(n);
  const int r = json_object_get_boolean_member(o, "parsed")
                  ? (int)json_array_get_length(json_object_get_array_member(o, "problems"))
                  : -1;
  json_node_unref(n);
  return r;
}

/* ---------------------------------------------------------------- */
/* pixels                                                            */

/* full render at scale 1; returns malloc'ed RGBA */
static uint8_t *render_full(int *w, int *h, double *seconds)
{
  MUST(dtb_render_size(1.0f, w, h));
  uint8_t *buf = malloc((size_t)4 * *w * *h);
  const double t0 = now();
  MUST(dtb_render_region(0, 0, *w, *h, 1.0f, buf));
  if(seconds) *seconds = now() - t0;
  return buf;
}

static void diff_stats(const uint8_t *a, const uint8_t *b, size_t n_px, int *maxd, double *meand)
{
  long long sum = 0;
  int mx = 0;
  for(size_t i = 0; i < n_px; i++)
    for(int c = 0; c < 3; c++)
    {
      const int d = abs((int)a[4 * i + c] - (int)b[4 * i + c]);
      sum += d;
      if(d > mx) mx = d;
    }
  *maxd = mx;
  *meand = (double)sum / (3.0 * n_px);
}

/* a screen-sized region in the middle at scale 0.5: quick, and enough to
 * see whether two states render the same */
static uint8_t *render_probe(int *pw, int *ph)
{
  int w, h;
  MUST(dtb_render_size(0.5f, &w, &h));
  *pw = w < 800 ? w : 800;
  *ph = h < 600 ? h : 600;
  uint8_t *buf = malloc((size_t)4 * *pw * *ph);
  MUST(dtb_render_region((w - *pw) / 2, (h - *ph) / 2, *pw, *ph, 0.5f, buf));
  return buf;
}

static int probe_diff(const uint8_t *a, const uint8_t *b, int w, int h)
{
  int mx;
  double mean;
  diff_stats(a, b, (size_t)w * h, &mx, &mean);
  return mx;
}

/* ---------------------------------------------------------------- */
/* S3 tests                                                          */

static int cmd_check(const char *xmp)
{
  char *j = NULL;
  const int r = dtb_xmp_check(xmp, &j);
  if(r)
  {
    fprintf(stdout, "FAIL check: %s\n", dtb_last_error());
    return 1;
  }
  fprintf(stdout, "%s\n", j);
  dtb_free(j);
  const int n = xmp_problems(xmp);
  fprintf(stdout, "RESULT problems %d\n", n);
  return n == 0 ? 0 : 3;
}

static int cmd_render(const char *raw, const char *xmp, const char *out)
{
  const double t0 = now();
  MUST(dtb_open(raw, xmp));
  const double t1 = now();
  int w, h;
  double tr;
  uint8_t *buf = render_full(&w, &h, &tr);
  write_ppm(out, buf, w, h);
  fprintf(stdout, "RESULT size %dx%d\nRESULT open_s %.3f\nRESULT render_s %.3f\n", w, h, t1 - t0, tr);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_param(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int end0, end1;
  const int n0 = history_count(&end0);
  double ev = NAN;
  CHECK(module_info("exposure", 0, &ev, NULL, 0, NULL), "no exposure.0");
  char v[64];
  snprintf(v, sizeof(v), "%.9g", ev + 1.0);
  MUST(dtb_param_set("exposure", 0, "exposure", v));
  const int n1 = history_count(&end1);
  double ev1 = NAN;
  module_info("exposure", 0, &ev1, NULL, 0, NULL);
  fprintf(stdout, "RESULT exposure_before %.4f\nRESULT exposure_after %.4f\n", ev, ev1);
  fprintf(stdout, "RESULT history_before %d end %d\nRESULT history_after %d end %d\n", n0, end0, n1, end1);
  CHECK(n1 == n0 + 1 && end1 == end0 + 1, "history did not grow by exactly one (%d -> %d)", n0, n1);
  CHECK(fabs(ev1 - (ev + 1.0)) < 1e-5, "exposure not set");
  MUST(dtb_xmp_write(path_join(outdir, "param.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "param_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_instances(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  print_exposure_instances("before");
  int newp = -1;
  MUST(dtb_instance_duplicate("exposure", 0, &newp));
  fprintf(stdout, "RESULT new_priority %d\n", newp);
  double base = NAN, copy = NAN;
  module_info("exposure", 0, &base, NULL, 0, NULL);
  module_info("exposure", newp, &copy, NULL, 0, NULL);
  CHECK(fabs(base - copy) < 1e-6, "duplicate did not copy parameters (%f vs %f)", base, copy);
  MUST(dtb_param_set("exposure", newp, "exposure", "-0.5"));
  MUST(dtb_set_enabled("exposure", newp, 0));
  int en = 1;
  module_info("exposure", newp, NULL, NULL, 0, &en);
  CHECK(en == 0, "copy not switched off");
  MUST(dtb_set_enabled("exposure", newp, 1));
  module_info("exposure", newp, NULL, NULL, 0, &en);
  CHECK(en == 1, "copy not switched on");
  print_exposure_instances("after");
  int end;
  fprintf(stdout, "RESULT history %d\n", history_count(&end));
  MUST(dtb_xmp_write(path_join(outdir, "instances.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "instances_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_history(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int end;
  const int n = history_count(&end);
  fprintf(stdout, "RESULT history %d end %d\n", n, end);
  int w, h;
  uint8_t *top = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "history_top.ppm"), top, w, h);

  const int k = end - 1;
  MUST(dtb_history_goto(k));
  uint8_t *back = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "history_back.ppm"), back, w, h);
  MUST(dtb_xmp_write(path_join(outdir, "history_back.xmp")));
  int mx;
  double mean;
  diff_stats(top, back, (size_t)w * h, &mx, &mean);
  fprintf(stdout, "RESULT goto %d differs_from_top max %d mean %.4f\n", k, mx, mean);

  MUST(dtb_history_goto(end));
  uint8_t *again = render_full(&w, &h, NULL);
  diff_stats(top, again, (size_t)w * h, &mx, &mean);
  fprintf(stdout, "RESULT goto_back_to_top max %d mean %.4f\n", mx, mean);
  CHECK(mx == 0, "returning to the top does not render as before");

  MUST(dtb_history_compress());
  int end2;
  const int n2 = history_count(&end2);
  fprintf(stdout, "RESULT compressed history %d end %d\n", n2, end2);
  CHECK(n2 <= n, "compress grew the history");
  CHECK(end2 == n2, "end %d does not match the %d items after compress", end2, n2);
  uint8_t *comp = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "history_compressed.ppm"), comp, w, h);
  diff_stats(top, comp, (size_t)w * h, &mx, &mean);
  fprintf(stdout, "RESULT compress_vs_top max %d mean %.4f\n", mx, mean);
  CHECK(mx == 0, "compress changed the result");
  MUST(dtb_xmp_write(path_join(outdir, "history_compressed.xmp")));
  free(top);
  free(back);
  free(again);
  free(comp);
  MUST(dtb_close());
  return g_fail;
}

/* T5: dtb_render_region must equal the same crop of the full render. With
 * DTB_TEST_DEMOSAIC_METHOD=<enum constant> (t5_bisect.sh) demosaic is set
 * to that method first. */
static int cmd_region(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  const char *dm = getenv("DTB_TEST_DEMOSAIC_METHOD");
  if(dm)
  {
    char v[128];
    snprintf(v, sizeof(v), "\"%s\"", dm);
    MUST(dtb_param_set("demosaic", 0, "demosaicing_method", v));
    fprintf(stdout, "RESULT demosaicing_method %s\n", dm);
  }
  // DTB_TEST_PARAMS="op:path=value;...": parameter changes before the
  // region test (t5_bisect.sh, P1c), for example "spektrafilm:grain_on=false"
  const char *tp = getenv("DTB_TEST_PARAMS");
  if(tp && *tp)
  {
    gchar **items = g_strsplit(tp, ";", -1);
    for(int i = 0; items[i]; i++)
    {
      gchar **kv = g_strsplit(items[i], "=", 2);
      gchar **opp = kv[0] && kv[1] ? g_strsplit(kv[0], ":", 2) : NULL;
      if(opp && opp[0] && opp[1])
      {
        MUST(dtb_param_set(opp[0], 0, opp[1], kv[1]));
        fprintf(stdout, "RESULT param %s.%s = %s\n", opp[0], opp[1], kv[1]);
      }
      g_strfreev(opp);
      g_strfreev(kv);
    }
    g_strfreev(items);
  }
  // DTB_TEST_ON="op,op": switch modules on with their defaults (P1c)
  const char *ton = getenv("DTB_TEST_ON");
  if(ton && *ton)
  {
    gchar **ops = g_strsplit(ton, ",", -1);
    for(int i = 0; ops[i]; i++)
    {
      const int r = dtb_set_enabled(ops[i], 0, 1);
      fprintf(stdout, "RESULT on %s: %d\n", ops[i], r);
    }
    g_strfreev(ops);
  }
  // DTB_TEST_OFF="op,op": switch modules off (t5_bisect.sh, P1c)
  const char *toff = getenv("DTB_TEST_OFF");
  if(toff && *toff)
  {
    gchar **ops = g_strsplit(toff, ",", -1);
    for(int i = 0; ops[i]; i++)
    {
      const int r = dtb_set_enabled(ops[i], 0, 0);
      fprintf(stdout, "RESULT off %s: %d\n", ops[i], r);
    }
    g_strfreev(ops);
  }
  fprintf(stdout, "RESULT margin_env %s\n", getenv("DTB_REGION_MARGIN") ? getenv("DTB_REGION_MARGIN") : "default");
  int w, h;
  uint8_t *full = render_full(&w, &h, NULL);
  const int rw = 512, rh = 512;
  // three regions: centre, top left corner, an odd offset near the bottom right
  const int xs[3] = { (w - rw) / 2, 0, w - rw - 37 };
  const int ys[3] = { (h - rh) / 2, 0, h - rh - 23 };
  uint8_t *reg = malloc((size_t)4 * rw * rh);
  uint8_t *crop = malloc((size_t)4 * rw * rh);
  for(int i = 0; i < 3; i++)
  {
    const double t0 = now();
    MUST(dtb_render_region(xs[i], ys[i], rw, rh, 1.0f, reg));
    const double t = now() - t0;
    for(int y = 0; y < rh; y++)
      memcpy(crop + (size_t)4 * rw * y, full + 4 * ((size_t)(ys[i] + y) * w + xs[i]), (size_t)4 * rw);
    int mx;
    double mean;
    diff_stats(reg, crop, (size_t)rw * rh, &mx, &mean);
    // where the differing pixels are: their largest distance from the region's edge
    int far = -1;
    for(int y = 0; y < rh; y++)
      for(int x = 0; x < rw; x++)
      {
        const size_t k = 4 * ((size_t)y * rw + x);
        if(reg[k] != crop[k] || reg[k + 1] != crop[k + 1] || reg[k + 2] != crop[k + 2])
        {
          const int d = MIN(MIN(x, rw - 1 - x), MIN(y, rh - 1 - y));
          if(d > far) far = d;
        }
      }
    fprintf(stdout, "RESULT region %d at %d,%d max %d mean %.4f deepest_diff_from_edge %d render_s %.3f\n",
            i, xs[i], ys[i], mx, mean, far, t);
    char name[64];
    snprintf(name, sizeof(name), "region%d.ppm", i);
    write_ppm(path_join(outdir, name), reg, rw, rh);
    snprintf(name, sizeof(name), "region%d_crop.ppm", i);
    write_ppm(path_join(outdir, name), crop, rw, rh);
    fprintf(stdout, "RESULT region%d_origin %d %d\n", i, xs[i], ys[i]);
  }
  // P1c: the same regions again, in reverse order: a region must not depend
  // on what was rendered before it
  for(int i = 2; i >= 0; i--)
  {
    uint8_t *again = malloc((size_t)4 * rw * rh);
    MUST(dtb_render_region(xs[i], ys[i], rw, rh, 1.0f, again));
    for(int y = 0; y < rh; y++)
      memcpy(crop + (size_t)4 * rw * y, full + 4 * ((size_t)(ys[i] + y) * w + xs[i]), (size_t)4 * rw);
    int mx;
    double mean;
    diff_stats(again, crop, (size_t)rw * rh, &mx, &mean);
    fprintf(stdout, "RESULT region %d again (reverse order) vs full max %d mean %.4f\n", i, mx, mean);
    free(again);
  }
  // information (P1c): the same at scale 0.5, the app's zoomed-out view
  {
    int hw, hh;
    MUST(dtb_render_size(0.5f, &hw, &hh));
    uint8_t *hfull = malloc((size_t)4 * hw * hh);
    MUST(dtb_render_region(0, 0, hw, hh, 0.5f, hfull));
    const int rx[2] = { (hw - 256) / 2 + 1, 0 }, ry[2] = { (hh - 256) / 2 + 1, 0 };
    uint8_t *hr = malloc((size_t)4 * 256 * 256), *hc = malloc((size_t)4 * 256 * 256);
    for(int i = 0; i < 2; i++)
    {
      MUST(dtb_render_region(rx[i], ry[i], 256, 256, 0.5f, hr));
      for(int y = 0; y < 256; y++)
        memcpy(hc + (size_t)4 * 256 * y, hfull + 4 * ((size_t)(ry[i] + y) * hw + rx[i]), (size_t)4 * 256);
      int mx;
      double mean;
      diff_stats(hr, hc, (size_t)256 * 256, &mx, &mean);
      fprintf(stdout, "RESULT half_scale region at %d,%d vs full render at scale 0.5: max %d mean %.4f\n", rx[i], ry[i],
              mx, mean);
    }
    free(hr);
    free(hc);
    free(hfull);
  }
  free(reg);
  free(crop);
  free(full);
  MUST(dtb_close());
  return g_fail;
}

/* a small whole-image render, for comparing history states */
static uint8_t *preview_px(int *w, int *h)
{
  uint8_t *p = NULL;
  MUST(dtb_render_preview(384, &p, w, h));
  return p;
}

/* P1c item 1: the history of a read-only session lists every entry of the
 * XMP, flags the one the engine does not know, has end <= items, and
 * history_goto works for every position; an unknown item changes nothing */
static void guard_history(const char *xmp_path, const char *unknown_op)
{
  char *cj = NULL;
  MUST(dtb_xmp_check(xmp_path, &cj));
  JsonNode *cn = jparse_owned(cj);
  const int entries = json_object_get_int_member(json_node_get_object(cn), "entries");
  JsonArray *probs = json_object_get_array_member(json_node_get_object(cn), "problems");
  const int problem_num = json_array_get_length(probs)
                            ? json_object_get_int_member(json_array_get_object_element(probs, 0), "num") : -1;
  json_node_unref(cn);

  JsonNode *h = get_history();
  JsonObject *ho = json_node_get_object(h);
  JsonArray *items = json_object_get_array_member(ho, "items");
  const int n = json_array_get_length(items);
  const int end = json_object_get_int_member(ho, "end");
  int unknown = 0, unknown_at = -1, flag_missing = 0;
  for(int k = 0; k < n; k++)
  {
    JsonObject *it = json_array_get_object_element(items, k);
    if(!json_object_has_member(it, "unknown")) { flag_missing++; continue; }
    if(json_object_get_boolean_member(it, "unknown"))
    {
      unknown++;
      unknown_at = k;
      CHECK(unknown_op && !strcmp(json_object_get_string_member(it, "op"), unknown_op)
              && !strcmp(json_object_get_string_member(it, "name"), unknown_op),
            "unknown item %d has op '%s'", k, json_object_get_string_member(it, "op"));
    }
    CHECK(json_object_get_int_member(it, "num") == k, "item %d has num %ld", k,
          (long)json_object_get_int_member(it, "num"));
  }
  json_node_unref(h);
  fprintf(stdout, "RESULT read-only history %s: %d XMP entries, %d items, end %d, unknown items %d (at %d)\n",
          xmp_path, entries, n, end, unknown, unknown_at);
  CHECK(flag_missing == 0, "%d items without the unknown flag", flag_missing);
  CHECK(end >= 0 && end <= n, "end %d outside 0..%d", end, n);
  // every XMP entry is an item; the engine may add items of its own (a
  // module that is on by default and missing from an old XMP's history)
  CHECK(n >= entries, "%d items for %d XMP entries", n, entries);
  CHECK(!unknown_op || unknown_at >= problem_num, "unknown item at %d, the XMP has it at %d", unknown_at, problem_num);
  CHECK(unknown == (unknown_op ? 1 : 0), "%d unknown items, expected %d", unknown, unknown_op ? 1 : 0);

  int w0, h0;
  uint8_t *p0 = preview_px(&w0, &h0);
  uint8_t *prev = NULL;
  int moved = 0, unknown_step = -1;
  for(int k = 0; k <= n; k++)
  {
    const int r = dtb_history_goto(k);
    CHECK(r == DTB_OK, "history_goto(%d) of %d returned %d", k, n, r);
    if(r) continue;
    int e = -1;
    history_count(&e);
    CHECK(e == k, "after history_goto(%d) end is %d", k, e);
    moved++;
    int w, hh;
    uint8_t *pk = preview_px(&w, &hh);
    if(prev && k == unknown_at + 1 && w == w0 && hh == h0)
    {
      unknown_step = probe_diff(prev, pk, w, hh);
      CHECK(unknown_step == 0, "the unknown item changed the picture (max %d)", unknown_step);
    }
    free(prev);
    prev = pk;
  }
  free(prev);
  MUST(dtb_history_goto(end));
  int w1, h1;
  uint8_t *p1 = preview_px(&w1, &h1);
  const int back = (w1 == w0 && h1 == h0) ? probe_diff(p0, p1, w0, h0) : 999;
  CHECK(back == 0, "back at end %d the picture differs from the opened one (max %d)", end, back);
  free(p0);
  free(p1);
  fprintf(stdout, "RESULT read-only history_goto: %d of %d positions; step over the unknown item max %d;"
          " back at end %d max %d\n", moved, n + 1, unknown_step, end, back);
}

static int cmd_guard(const char *raw, const char *xmp, const char *outdir)
{
  const char *bad[3] = { "newer.xmp", "unknown.xmp", "unknown_first.xmp" };
  for(int i = 0; i < 3; i++)
  {
    char *bx = path_join(outdir, bad[i]);
    char *j = NULL;
    dtb_xmp_check(bx, &j);
    const int n = xmp_problems(bx);
    fprintf(stdout, "RESULT check %s problems %d %s\n", bad[i], n, j ? j : "(null)");
    CHECK(n > 0, "%s not reported", bad[i]);
    dtb_free(j);

    MUST(dtb_open(raw, bx));
    JsonNode *info = get_json(dtb_image_info);
    CHECK(json_object_get_boolean_member(json_node_get_object(info), "read_only"), "%s: session not read-only", bad[i]);
    json_node_unref(info);
    guard_history(bx, i >= 1 ? "s3notamodule" : NULL);
    int r = dtb_xmp_write(bx);
    fprintf(stdout, "RESULT write_over_%s %d\n", bad[i], r);
    CHECK(r == DTB_E_READ_ONLY, "write over %s not refused", bad[i]);
    char *other = path_join(outdir, "guard_other.xmp");
    r = dtb_xmp_write(other);
    fprintf(stdout, "RESULT write_elsewhere_from_%s %d\n", bad[i], r);
    CHECK(r == DTB_E_READ_ONLY, "write from read-only session not refused");
    MUST(dtb_close());

    MUST(dtb_open(raw, xmp));
    info = get_json(dtb_image_info);
    CHECK(!json_object_get_boolean_member(json_node_get_object(info), "read_only"), "good XMP opened read-only");
    json_node_unref(info);
    r = dtb_xmp_write(bx);
    fprintf(stdout, "RESULT healthy_session_write_over_%s %d\n", bad[i], r);
    CHECK(r == DTB_E_READ_ONLY, "healthy session overwrote %s", bad[i]);
    MUST(dtb_close());
  }
  const int n = xmp_problems(xmp);
  fprintf(stdout, "RESULT check original problems %d\n", n);
  CHECK(n == 0, "original XMP reported");
  return g_fail;
}

static int cmd_timing(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  double t0 = now();
  MUST(dtb_open(raw, xmp));
  const double t_open = now() - t0;
  int w, h;
  double t_first, t_again, t_late, t_early, t_region;
  uint8_t *buf = render_full(&w, &h, &t_first);
  free(buf);
  buf = render_full(&w, &h, &t_again);
  free(buf);

  MUST(dtb_set_enabled("vignette", 0, 1));
  buf = render_full(&w, &h, &t_late);
  free(buf);
  double ev = 0;
  module_info("exposure", 0, &ev, NULL, 0, NULL);
  char v[64];
  snprintf(v, sizeof(v), "%.9g", ev + 0.25);
  MUST(dtb_param_set("exposure", 0, "exposure", v));
  buf = render_full(&w, &h, &t_early);
  free(buf);

  MUST(dtb_param_set("vignette", 0, "brightness", "-0.3"));
  uint8_t *reg = malloc((size_t)4 * 1920 * 1080);
  const int rw = w < 1920 ? w : 1920, rh = h < 1080 ? h : 1080;
  t0 = now();
  MUST(dtb_render_region((w - rw) / 2, (h - rh) / 2, rw, rh, 1.0f, reg));
  t_region = now() - t0;
  free(reg);

  uint8_t *pv = NULL;
  int pw, ph;
  t0 = now();
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  const double t_prev_first = now() - t0;
  dtb_free(pv);
  MUST(dtb_param_set("vignette", 0, "brightness", "-0.5"));
  t0 = now();
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  const double t_prev_late = now() - t0;
  dtb_free(pv);
  snprintf(v, sizeof(v), "%.9g", ev + 0.5);
  MUST(dtb_param_set("exposure", 0, "exposure", v));
  t0 = now();
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  const double t_prev_early = now() - t0;
  dtb_free(pv);

  fprintf(stdout, "RESULT size %dx%d preview %dx%d\n", w, h, pw, ph);
  fprintf(stdout, "RESULT open_s %.3f\n", t_open);
  fprintf(stdout, "RESULT first_full_render_s %.3f\n", t_first);
  fprintf(stdout, "RESULT unchanged_full_render_s %.3f\n", t_again);
  fprintf(stdout, "RESULT late_change_full_render_s %.3f\n", t_late);
  fprintf(stdout, "RESULT early_change_full_render_s %.3f\n", t_early);
  fprintf(stdout, "RESULT late_change_region_%dx%d_s %.3f\n", rw, rh, t_region);
  fprintf(stdout, "RESULT preview_first_s %.3f\n", t_prev_first);
  fprintf(stdout, "RESULT preview_late_change_s %.3f\n", t_prev_late);
  fprintf(stdout, "RESULT preview_early_change_s %.3f\n", t_prev_early);
  MUST(dtb_close());
  return g_fail;
}

typedef struct { double delay; double t_cancel; } cancel_arg_t;

static void *cancel_thread(void *p)
{
  cancel_arg_t *a = p;
  usleep((useconds_t)(a->delay * 1e6));
  a->t_cancel = now();
  dtb_render_cancel();
  return NULL;
}

static int cmd_cancel(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  int w, h;
  double t_full;
  uint8_t *ref = render_full(&w, &h, &t_full);
  double ev = 0;
  module_info("exposure", 0, &ev, NULL, 0, NULL);
  char v[64];
  snprintf(v, sizeof(v), "%.9g", ev + 0.1);
  MUST(dtb_param_set("exposure", 0, "exposure", v));
  uint8_t *buf = malloc((size_t)4 * w * h);
  double t_uncancelled;
  {
    const double t0 = now();
    MUST(dtb_render_region(0, 0, w, h, 1.0f, buf));
    t_uncancelled = now() - t0;
  }
  double worst = 0;
  for(int i = 0; i < 3; i++)
  {
    snprintf(v, sizeof(v), "%.9g", ev + 0.2 + 0.05 * i);
    MUST(dtb_param_set("exposure", 0, "exposure", v));
    const double delays[3] = { 0.05, 0.3 * t_uncancelled, 0.6 * t_uncancelled };
    cancel_arg_t a = { delays[i], 0 };
    pthread_t th;
    pthread_create(&th, NULL, cancel_thread, &a);
    const double t0 = now();
    const int r = dtb_render_region(0, 0, w, h, 1.0f, buf);
    const double t1 = now();
    pthread_join(th, NULL);
    const double latency = t1 - a.t_cancel;
    fprintf(stdout, "RESULT cancel %d after %.3f s: returned %d, %.3f s after the cancel (render alone takes %.3f s)\n",
            i, a.t_cancel - t0, r, latency, t_uncancelled);
    CHECK(r == DTB_E_CANCELLED, "render %d not cancelled (returned %d)", i, r);
    if(latency > worst) worst = latency;
  }
  fprintf(stdout, "RESULT cancel_worst_latency_s %.3f\n", worst);
  snprintf(v, sizeof(v), "%.9g", ev);
  MUST(dtb_param_set("exposure", 0, "exposure", v));
  MUST(dtb_render_region(0, 0, w, h, 1.0f, buf));
  int mx;
  double mean;
  diff_stats(ref, buf, (size_t)w * h, &mx, &mean);
  fprintf(stdout, "RESULT after_cancel_vs_reference max %d mean %.4f\n", mx, mean);
  CHECK(mx == 0, "render after cancels differs from reference");
  free(ref);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_mask(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int w, h;
  uint8_t *before = render_full(&w, &h, NULL);
  int p = -1;
  MUST(dtb_instance_new("exposure", 0, &p));
  MUST(dtb_param_set("exposure", p, "exposure", "2.0"));
  MUST(dtb_mask_add_circle("exposure", p, 0.5f, 0.5f, 0.1f, 0.02f));
  uint8_t *after = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "mask_before.ppm"), before, w, h);
  write_ppm(path_join(outdir, "mask_bridge.ppm"), after, w, h);
  MUST(dtb_xmp_write(path_join(outdir, "mask.xmp")));

  const int cw = w / 20, ch = h / 20;
  long long in_d = 0, out_d = 0;
  long in_n = 0, out_n = 0;
  for(int y = 0; y < h; y++)
    for(int x = 0; x < w; x++)
    {
      const size_t i = 4 * ((size_t)y * w + x);
      const int d = abs(after[i] - before[i]) + abs(after[i + 1] - before[i + 1]) + abs(after[i + 2] - before[i + 2]);
      if(abs(x - w / 2) < cw && abs(y - h / 2) < ch) { in_d += d; in_n++; }
      else if((x < w / 5 || x > 4 * w / 5) && (y < h / 5 || y > 4 * h / 5)) { out_d += d; out_n++; }
    }
  const double in_mean = (double)in_d / (3.0 * in_n), out_mean = (double)out_d / (3.0 * out_n);
  fprintf(stdout, "RESULT mask_change_inside_mean %.3f\nRESULT mask_change_corners_mean %.3f\n", in_mean, out_mean);
  CHECK(in_mean > 10.0 && out_mean < 0.5, "effect is not local");
  free(before);
  free(after);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_extras(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int w, h;
  uint8_t *pv = NULL;
  int pw, ph;
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  write_ppm(path_join(outdir, "x_preview.ppm"), pv, pw, ph);
  dtb_free(pv);
  fprintf(stdout, "RESULT preview %dx%d\n", pw, ph);

  MUST(dtb_export(path_join(outdir, "x_export.jpg"), "{\"format\":\"jpeg\",\"quality\":95}"));

  print_exposure_instances("start");
  MUST(dtb_instance_move("exposure", 0, 1));
  MUST(dtb_xmp_write(path_join(outdir, "x_move.xmp")));
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "x_move_bridge.ppm"), buf, w, h);
  free(buf);

  int p1 = -1, p2 = -1;
  MUST(dtb_instance_new("exposure", 0, &p1));
  MUST(dtb_param_set("exposure", p1, "exposure", "0.3"));
  MUST(dtb_instance_duplicate("exposure", p1, &p2));
  MUST(dtb_param_set("exposure", p2, "exposure", "-0.4"));
  MUST(dtb_instance_delete("exposure", p1));
  CHECK(!module_info("exposure", p1, NULL, NULL, 0, NULL), "deleted instance still listed");
  MUST(dtb_instance_delete("exposure", 0));
  int last = 0;
  for(int i = 0; i < 8; i++)
    if(module_info("exposure", i, NULL, NULL, 0, NULL)) last++;
  CHECK(module_info("exposure", 0, NULL, NULL, 0, NULL), "no exposure.0 after deleting priority 0");
  CHECK(dtb_instance_delete("vignette", 0) == DTB_E_ARG, "deleting the last instance not refused");
  print_exposure_instances("after");
  fprintf(stdout, "RESULT exposure_instances_after %d\n", last);
  int end;
  fprintf(stdout, "RESULT history %d\n", history_count(&end));
  MUST(dtb_xmp_write(path_join(outdir, "x_delete.xmp")));
  buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "x_delete_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

/* ---------------------------------------------------------------- */
/* P1a tests                                                         */

/* C1: engine_info, image_info, the modules JSON */
static int cmd_api(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  JsonNode *ei = get_json(dtb_engine_info);
  JsonObject *e = json_node_get_object(ei);
  JsonObject *pl = json_object_get_object_member(e, "plugins");
  fprintf(stdout, "RESULT engine darktable %s api %ld threads %ld/%ld plugins %ld/%ld\n",
          json_object_get_string_member(e, "darktable_version"), (long)json_object_get_int_member(e, "api_version"),
          (long)json_object_get_int_member(e, "threads"), (long)json_object_get_int_member(e, "threads_max"),
          (long)json_object_get_int_member(pl, "loaded"), (long)json_object_get_int_member(pl, "files"));
  CHECK(json_object_get_int_member(e, "api_version") == DTB_API_VERSION, "api_version");
  CHECK(json_object_get_int_member(pl, "files") > 0
        && json_object_get_int_member(pl, "files") == json_object_get_int_member(pl, "loaded")
        && json_array_get_length(json_object_get_array_member(pl, "missing")) == 0,
        "not every plugin loaded");
  json_node_unref(ei);

  EXPECT(dtb_modules(&(char *){ NULL }), DTB_E_STATE);  // no session yet
  MUST(dtb_open(raw, xmp));
  JsonNode *ii = get_json(dtb_image_info);
  JsonObject *im = json_node_get_object(ii);
  int w, h;
  MUST(dtb_render_size(1.0f, &w, &h));
  JsonObject *ex = json_object_get_object_member(im, "exif");
  fprintf(stdout, "RESULT image %ldx%ld raw %ldx%ld class %s maker %s model %s iso %g datetime %s rating %ld\n",
          (long)json_object_get_int_member(im, "width"), (long)json_object_get_int_member(im, "height"),
          (long)json_object_get_int_member(im, "raw_width"), (long)json_object_get_int_member(im, "raw_height"),
          json_object_get_string_member(im, "image_class"),
          json_object_get_string_member(ex, "maker"), json_object_get_string_member(ex, "model"),
          json_object_get_null_member(ex, "iso") ? -1.0 : json_object_get_double_member(ex, "iso"),
          json_object_get_null_member(ex, "datetime") ? "null" : json_object_get_string_member(ex, "datetime"),
          (long)json_object_get_int_member(im, "rating"));
  CHECK(json_object_get_int_member(im, "width") == w && json_object_get_int_member(im, "height") == h,
        "image_info size differs from render_size");
  const char *expect_class = getenv("DTB_TEST_CLASS");
  if(expect_class)
    CHECK(!strcmp(json_object_get_string_member(im, "image_class"), expect_class), "image class %s, expected %s",
          json_object_get_string_member(im, "image_class"), expect_class);
  CHECK(!json_object_get_boolean_member(im, "read_only"), "opened read-only");
  json_node_unref(ii);

  JsonNode *mods = get_modules();
  JsonArray *a = json_node_get_array(mods);
  static const char *keys[] = { "op", "name", "priority", "instance_name", "iop_order", "enabled",
                                "can_switch", "default_enabled", "hidden", "deprecated", "one_instance",
                                "supports_blending", "version", "in_history", "params", NULL };
  int missing = 0, no_switch = 0, in_hist = 0, deprecated = 0, one = 0, structs = 0, arrays = 0, enums = 0;
  int last_order = -1000000, order_ok = 1;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    for(int k = 0; keys[k]; k++)
      if(!json_object_has_member(o, keys[k]))
      {
        missing++;
        fprintf(stdout, "INFO module %s lacks %s\n", json_object_get_string_member(o, "op"), keys[k]);
      }
    if(!json_object_get_boolean_member(o, "can_switch")) no_switch++;
    if(json_object_get_boolean_member(o, "in_history")) in_hist++;
    if(json_object_get_boolean_member(o, "deprecated")) deprecated++;
    if(json_object_get_boolean_member(o, "one_instance")) one++;
    JsonNode *on = json_object_get_member(o, "iop_order");
    if(json_node_get_value_type(on) != G_TYPE_INT64) order_ok = 0;
    const int order = json_object_get_int_member(o, "iop_order");
    if(order < last_order) order_ok = 0;
    last_order = order;
    JsonObject *p = json_object_get_object_member(o, "params");
    GList *mem = json_object_get_members(p);
    for(GList *m = mem; m; m = g_list_next(m))
    {
      JsonNode *n = json_object_get_member(p, m->data);
      if(JSON_NODE_TYPE(n) == JSON_NODE_OBJECT) structs++;
      if(JSON_NODE_TYPE(n) == JSON_NODE_ARRAY) arrays++;
      if(JSON_NODE_TYPE(n) == JSON_NODE_VALUE && json_node_get_value_type(n) == G_TYPE_STRING
         && g_str_has_prefix(json_node_get_string(n), "DT_")) enums++;
    }
    g_list_free(mem);
  }
  fprintf(stdout, "RESULT modules %u, without switch %d, in history %d, deprecated %d, one_instance %d; params: %d struct, %d array, %d enum-like fields\n",
          json_array_get_length(a), no_switch, in_hist, deprecated, one, structs, arrays, enums);
  CHECK(missing == 0, "%d missing keys", missing);
  CHECK(order_ok, "modules not in pipeline order, or iop_order not an integer");
  {
    // what re-reading the list costs the app (x86_64, 2 cores; JNI not included)
    size_t bytes = 0;
    const double t0 = now();
    for(int i = 0; i < 10; i++)
    {
      char *js = get_modules_str();
      bytes = strlen(js);
      dtb_free(js);
    }
    const double t = (now() - t0) / 10.0;
    fprintf(stdout, "RESULT modules() JSON %zu bytes, %.1f ms per call (x86_64)\n", bytes, t * 1000.0);
  }
  CHECK(no_switch > 0 && in_hist > 0 && deprecated > 0 && arrays > 0 && enums > 0, "flags or param tree incomplete");

  // the nested shapes the specification names
  JsonObject *rc = find_mod(mods, "rgbcurve", 0);
  JsonNode *node = rc ? param_node(rc, "curve_nodes/0/1/x") : NULL;
  CHECK(node && JSON_NODE_TYPE(node) == JSON_NODE_VALUE, "rgbcurve curve_nodes[0][1].x not in the tree");
  JsonObject *ex0 = find_mod(mods, "exposure", 0);
  node = ex0 ? param_node(ex0, "mode") : NULL;
  CHECK(node && json_node_get_value_type(node) == G_TYPE_STRING, "exposure mode is not an enum name");
  JsonObject *dm = find_mod(mods, "demosaic", 0);
  CHECK(dm && !json_object_get_boolean_member(dm, "can_switch"), "demosaic should have no switch");
  EXPECT(dtb_set_enabled("demosaic", 0, 0), DTB_E_ARG);
  EXPECT(dtb_set_enabled("nosuchmodule", 0, 1), DTB_E_NOT_FOUND);
  EXPECT(dtb_set_enabled("exposure", 99, 1), DTB_E_NOT_FOUND);
  // no NaN or infinity anywhere: the JSON parsed, so they were written as null
  json_node_unref(mods);

  JsonNode *hist = get_history();
  JsonObject *ho = json_node_get_object(hist);
  JsonArray *items = json_object_get_array_member(ho, "items");
  int hk = 0;
  for(guint i = 0; i < json_array_get_length(items); i++)
  {
    JsonObject *o = json_array_get_object_element(items, i);
    if(json_object_has_member(o, "num") && json_object_has_member(o, "op") && json_object_has_member(o, "priority")
       && json_object_has_member(o, "name") && json_object_has_member(o, "instance_name")
       && json_object_has_member(o, "enabled"))
      hk++;
  }
  CHECK(json_object_has_member(ho, "end") && hk == (int)json_array_get_length(items), "history JSON shape");
  json_node_unref(hist);
  MUST(dtb_close());
  return g_fail;
}

/* C2: parameter paths, types, ranges, params_set, grouping, reset */
static int cmd_params(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int n0 = history_count(NULL);

  // errors: nothing changes
  EXPECT(dtb_param_set("exposure", 0, "exposure", "\"one\""), DTB_E_TYPE);
  EXPECT(dtb_param_set("exposure", 0, "exposure", "true"), DTB_E_TYPE);
  EXPECT(dtb_param_set("exposure", 0, "exposure", "19"), DTB_E_RANGE);
  EXPECT(dtb_param_set("exposure", 0, "exposure", "-18.5"), DTB_E_RANGE);
  EXPECT(dtb_param_set("exposure", 0, "nosuchfield", "1"), DTB_E_NOT_FOUND);
  EXPECT(dtb_param_set("exposure", 0, "exposure[1]", "1"), DTB_E_NOT_FOUND);
  EXPECT(dtb_param_set("exposure", 0, "exposure..x", "1"), DTB_E_ARG);
  EXPECT(dtb_param_set("exposure", 0, "exposure", "{"), DTB_E_ARG);
  EXPECT(dtb_param_set("exposure", 0, "mode", "1"), DTB_E_TYPE);                 // enum by name only
  EXPECT(dtb_param_set("exposure", 0, "mode", "\"EXPOSURE_MODE_NOPE\""), DTB_E_RANGE);
  EXPECT(dtb_param_set("exposure", 0, "compensate_exposure_bias", "1"), DTB_E_TYPE);
  EXPECT(dtb_param_set("rgbcurve", 0, "curve_nodes[3][0].x", "0.5"), DTB_E_NOT_FOUND);  // 3 channels
  EXPECT(dtb_param_set("rgbcurve", 0, "curve_nodes[0][0].x", "1.5"), DTB_E_RANGE);
  EXPECT(dtb_param_set("rgbcurve", 0, "curve_nodes[0]", "0.5"), DTB_E_TYPE);
  EXPECT(dtb_param_set("rgbcurve", 0, "curve_num_nodes[0]", "2.5"), DTB_E_TYPE);
  EXPECT(dtb_params_set("exposure", 0, "{\"exposure\": 1.0, \"black\": 5}"), DTB_E_RANGE);
  EXPECT(dtb_params_set("exposure", 0, "[1]"), DTB_E_ARG);
  CHECK(history_count(NULL) == n0, "a refused change added history");
  double ev0 = param_num("exposure", 0, "exposure");

  // a path into an array of structs, an int array element, an enum by name
  MUST(dtb_param_set("rgbcurve", 0, "curve_nodes[0][0].y", "0.08"));
  CHECK(fabs(param_num("rgbcurve", 0, "curve_nodes/0/0/y") - 0.08) < 1e-6, "curve_nodes[0][0].y not set");
  MUST(dtb_param_set("rgbcurve", 0, "preserve_colors", "\"DT_RGB_NORM_NONE\""));
  JsonNode *m = get_modules();
  JsonObject *rc = find_mod(m, "rgbcurve", 0);
  CHECK(rc && json_object_get_boolean_member(rc, "enabled"), "param_set did not switch rgbcurve on");
  CHECK(rc && !strcmp(json_node_get_string(param_node(rc, "preserve_colors")), "DT_RGB_NORM_NONE"), "enum not set");
  json_node_unref(m);
  const int n1 = history_count(NULL);
  fprintf(stdout, "RESULT history after two rgbcurve changes %d -> %d\n", n0, n1);
  CHECK(n1 == n0 + 1, "consecutive param_set on one instance did not merge (%d -> %d)", n0, n1);

  // seal: the next change is a new item
  MUST(dtb_history_seal());
  MUST(dtb_param_set("rgbcurve", 0, "curve_nodes[0][1].y", "0.95"));
  const int n2 = history_count(NULL);
  CHECK(n2 == n1 + 1, "change after seal did not start a new item (%d -> %d)", n1, n2);
  // another instance: new item
  MUST(dtb_param_set("exposure", 0, "exposure", "1.25"));
  const int n3 = history_count(NULL);
  CHECK(n3 == n2 + 1, "change on another instance merged (%d -> %d)", n2, n3);
  // set_enabled seals first, and the next param_set is a new item again
  MUST(dtb_set_enabled("vignette", 0, 1));
  MUST(dtb_param_set("vignette", 0, "brightness", "-0.4"));
  const int n4 = history_count(NULL);
  CHECK(n4 == n3 + 2, "set_enabled + param_set on the same instance: %d -> %d, expected +2", n3, n4);
  // params_set: several fields, one step
  MUST(dtb_history_seal());
  MUST(dtb_params_set("exposure", 0, "{\"exposure\": 0.75, \"black\": 0.002, \"compensate_exposure_bias\": true}"));
  const int n5 = history_count(NULL);
  CHECK(n5 == n4 + 1, "params_set made %d items", n5 - n4);
  CHECK(fabs(param_num("exposure", 0, "exposure") - 0.75) < 1e-6 && fabs(param_num("exposure", 0, "black") - 0.002) < 1e-6,
        "params_set values not set");
  fprintf(stdout, "RESULT grouping history %d -> %d -> %d -> %d -> %d -> %d\n", n0, n1, n2, n3, n4, n5);
  MUST(dtb_xmp_write(path_join(outdir, "params.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "params_bridge.ppm"), buf, w, h);
  free(buf);

  // module_reset: back to the defaults. A new instance starts from the
  // defaults for its place (exposure's first instance has its own, see
  // reload_defaults in iop/exposure.c), so change one and reset it
  int np = -1;
  MUST(dtb_instance_new("exposure", 0, &np));
  m = get_modules();
  JsonNode *fresh = json_node_copy(json_object_get_member(find_mod(m, "exposure", np), "params"));
  json_node_unref(m);
  MUST(dtb_params_set("exposure", np, "{\"exposure\": -1.5, \"black\": 0.01, \"mode\": \"EXPOSURE_MODE_DEFLICKER\"}"));
  MUST(dtb_set_enabled("exposure", np, 0));
  const int nr0 = history_count(NULL);
  MUST(dtb_module_reset("exposure", np));
  CHECK(history_count(NULL) == nr0 + 1, "reset is not one history item");
  m = get_modules();
  JsonObject *e1 = find_mod(m, "exposure", np);
  if(!json_node_equal(fresh, json_object_get_member(e1, "params")))
  {
    gchar *x = json_to_string(fresh, FALSE), *y = json_to_string(json_object_get_member(e1, "params"), FALSE);
    fprintf(stdout, "INFO new instance params %s\nINFO reset params        %s\n", x, y);
    g_free(x);
    g_free(y);
  }
  CHECK(json_node_equal(fresh, json_object_get_member(e1, "params")), "reset params differ from the instance's defaults");
  CHECK(json_object_get_boolean_member(e1, "enabled"), "reset did not switch the module on (desktop does)");
  json_node_unref(m);
  json_node_unref(fresh);
  // and the first instance: reset twice gives the same params
  MUST(dtb_module_reset("exposure", 0));
  char *r1 = get_modules_str();
  MUST(dtb_module_reset("exposure", 0));
  char *r2 = get_modules_str();
  CHECK(!strcmp(r1, r2), "reset is not idempotent");
  fprintf(stdout, "RESULT exposure.0 before %g, after reset %g\n", ev0, param_num("exposure", 0, "exposure"));
  dtb_free(r1);
  dtb_free(r2);
  EXPECT(dtb_module_reset("exposure", 42), DTB_E_NOT_FOUND);
  fprintf(stdout, "RESULT exposure before %g, reset to defaults\n", ev0);
  MUST(dtb_instance_delete("exposure", np));
  // instance_move(+1): one step later in the pipeline, iop_order grows
  m = get_modules();
  const gint64 o0 = json_object_get_int_member(find_mod(m, "exposure", 0), "iop_order");
  json_node_unref(m);
  MUST(dtb_instance_move("exposure", 0, 1));
  m = get_modules();
  const gint64 o1 = json_object_get_int_member(find_mod(m, "exposure", 0), "iop_order");
  json_node_unref(m);
  MUST(dtb_instance_move("exposure", 0, -1));
  m = get_modules();
  const gint64 o2 = json_object_get_int_member(find_mod(m, "exposure", 0), "iop_order");
  json_node_unref(m);
  fprintf(stdout, "RESULT exposure iop_order %ld, after move +1 %ld, after -1 %ld\n", (long)o0, (long)o1, (long)o2);
  CHECK(o1 > o0 && o2 < o1, "move +1 did not go later in the pipeline, or -1 not earlier");
  MUST(dtb_xmp_write(path_join(outdir, "reset.xmp")));
  buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "reset_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

/* C3: undo and redo restore whole-history states exactly */
typedef struct state_t
{
  char *modules, *history;
  uint8_t *px;
} state_t;

static int g_pw, g_ph;

static state_t state_take(void)
{
  state_t s = { get_modules_str(), get_history_str(), render_probe(&g_pw, &g_ph) };
  return s;
}

static int state_equal(const state_t *a, const char *label)
{
  state_t b = state_take();
  const int mj = strcmp(a->modules, b.modules) == 0;
  const int hj = strcmp(a->history, b.history) == 0;
  const int px = probe_diff(a->px, b.px, g_pw, g_ph);
  fprintf(stdout, "RESULT %s modules %s history %s pixels max %d\n", label, mj ? "equal" : "DIFFER",
          hj ? "equal" : "DIFFER", px);
  if(!mj || !hj)
  {
    // the first difference, for the log
    const char *x = mj ? a->history : a->modules, *y = mj ? b.history : b.modules;
    size_t i = 0;
    while(x[i] && x[i] == y[i]) i++;
    const size_t s = i > 120 ? i - 120 : 0;
    fprintf(stdout, "INFO first difference at %zu\nINFO had: %.300s\nINFO got: %.300s\n", i, x + s, y + s);
  }
  dtb_free(b.modules);
  dtb_free(b.history);
  free(b.px);
  return mj && hj && px == 0;
}

static void state_free(state_t *s)
{
  dtb_free(s->modules);
  dtb_free(s->history);
  free(s->px);
}

static int undo_can(int *redo)
{
  JsonNode *n = get_json(dtb_undo_state);
  JsonObject *o = json_node_get_object(n);
  const int u = json_object_get_boolean_member(o, "can_undo");
  if(redo) *redo = json_object_get_boolean_member(o, "can_redo");
  json_node_unref(n);
  return u;
}

static int cmd_undo(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int redo = 0;
  CHECK(!undo_can(&redo) && !redo, "fresh session can undo or redo");
  EXPECT(dtb_undo(), DTB_E_STATE);
  EXPECT(dtb_redo(), DTB_E_STATE);

#define NSTEPS 11
  state_t st[NSTEPS + 1];
  const char *what[NSTEPS];
  int k = 0, p = -1;
  st[k] = state_take();
  what[k] = "param_set x2 (merged)";
  MUST(dtb_param_set("exposure", 0, "exposure", "1.1"));
  MUST(dtb_param_set("exposure", 0, "exposure", "1.3"));
  st[++k] = state_take();
  what[k] = "set_enabled vignette";
  MUST(dtb_set_enabled("vignette", 0, 1));
  st[++k] = state_take();
  what[k] = "params_set";
  MUST(dtb_params_set("vignette", 0, "{\"brightness\": -0.6, \"saturation\": -0.2}"));
  st[++k] = state_take();
  what[k] = "instance_new";
  MUST(dtb_instance_new("exposure", 0, &p));
  st[++k] = state_take();
  what[k] = "param_set new instance";
  MUST(dtb_param_set("exposure", p, "exposure", "-0.7"));
  st[++k] = state_take();
  what[k] = "instance_rename";
  MUST(dtb_instance_rename("exposure", p, "darker"));
  st[++k] = state_take();
  what[k] = "instance_move";
  MUST(dtb_instance_move("exposure", p, 1));
  st[++k] = state_take();
  what[k] = "instance_duplicate";
  int p2 = -1;
  MUST(dtb_instance_duplicate("exposure", p, &p2));
  st[++k] = state_take();
  what[k] = "instance_delete priority 0";
  MUST(dtb_instance_delete("exposure", 0));
  st[++k] = state_take();
  what[k] = "module_reset";
  MUST(dtb_module_reset("vignette", 0));
  st[++k] = state_take();
  what[k] = "history_compress";
  // history_goto is navigation, not an undo step (engine-api.md 2.6): undo
  // of the following compress returns to the state at the goto; the undo
  // before that returns to the state before the reset, end included
  int end;
  history_count(&end);
  int can_u0 = 0, can_r0 = 0;
  can_u0 = undo_can(&can_r0);
  MUST(dtb_history_goto(end - 3));
  int can_u1 = 0, can_r1 = 0;
  can_u1 = undo_can(&can_r1);
  CHECK(can_u0 == can_u1 && can_r0 == can_r1, "history_goto changed the undo state");
  state_t at_goto = state_take();
  MUST(dtb_history_compress());
  st[++k] = state_take();
  fprintf(stdout, "RESULT states %d\n", k + 1);

  // undo back to the start, checking each state
  int ok = 1;
  for(int i = k - 1; i >= 0; i--)
  {
    MUST(dtb_undo());
    char label[160];
    snprintf(label, sizeof(label), "undo of '%s' (state %d%s)", what[i], i, i == k - 1 ? ", at the goto" : "");
    ok &= state_equal(i == k - 1 ? &at_goto : &st[i], label);
  }
  CHECK(ok, "undo did not restore every state exactly");
  CHECK(!undo_can(&redo) && redo, "after undoing everything: can_undo or not can_redo");
  EXPECT(dtb_undo(), DTB_E_STATE);
  // and forward again
  ok = 1;
  for(int i = 1; i <= k; i++)
  {
    MUST(dtb_redo());
    char label[160];
    // redo returns to the state undo left: after the reset that is the
    // position the goto had moved to
    snprintf(label, sizeof(label), "redo of '%s' (state %d%s)", what[i - 1], i, i == k - 1 ? ", at the goto" : "");
    ok &= state_equal(i == k - 1 ? &at_goto : &st[i], label);
  }
  CHECK(ok, "redo did not restore every state exactly");
  EXPECT(dtb_redo(), DTB_E_STATE);
  // a new change after an undo clears the redo states
  MUST(dtb_undo());
  // (priority 0 is gone: the remaining instances were renumbered)
  MUST(dtb_param_set("exposure", 0, "exposure", "0.2"));
  undo_can(&redo);
  CHECK(!redo, "redo still possible after a new change");
  MUST(dtb_xmp_write(path_join(outdir, "undo.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "undo_bridge.ppm"), buf, w, h);
  free(buf);
  for(int i = 0; i <= k; i++) state_free(&st[i]);
  state_free(&at_goto);

  // depth: 60 sealed changes, at least 50 undo steps
  int steps = 0;
  for(int i = 0; i < 60; i++)
  {
    char v[32];
    snprintf(v, sizeof(v), "%.3f", 0.01 * i);
    MUST(dtb_param_set("exposure", 0, "exposure", v));
    MUST(dtb_history_seal());
  }
  while(dtb_undo() == DTB_OK && steps < 1000) steps++;
  fprintf(stdout, "RESULT undo depth after 60 changes: %d steps\n", steps);
  CHECK(steps >= 50, "only %d undo steps", steps);
  MUST(dtb_close());
  return g_fail;
}

/* C4: module actions */
static JsonNode *action(const char *op, int prio, const char *a, const char *args, int expect)
{
  char *res = NULL;
  const int r = dtb_module_action(op, prio, a, args, &res);
  if(r != expect)
  {
    fprintf(stdout, "FAIL module_action %s %s %s returned %d, expected %d [%s]\n", op, a, args ? args : "", r,
            expect, dtb_last_error());
    g_fail = 1;
  }
  if(r || !res)
  {
    dtb_free(res);
    return NULL;
  }
  return jparse_owned(res);
}

/* the LUT root folder of C4, with one small .cube file generated here (a
 * gentle warm tone curve); returns the darktablerc line that sets it, which
 * must be in place before the engine starts */
static char *actions_prepare(const char *outdir)
{
  char *lutdir = path_join(outdir, "luts");
  mkdir(outdir, 0700);
  mkdir(lutdir, 0700);
  char *cube = path_join(lutdir, "warm.cube");
  FILE *f = fopen(cube, "w");
  if(!f)
  {
    fprintf(stdout, "FAIL cannot write %s\n", cube);
    exit(1);
  }
  fprintf(f, "TITLE \"warm\"\nLUT_3D_SIZE 2\n");
  for(int b = 0; b < 2; b++)
    for(int g = 0; g < 2; g++)
      for(int r = 0; r < 2; r++)
        fprintf(f, "%.3f %.3f %.3f\n", r ? 1.0 : 0.06, g ? 0.96 : 0.03, b ? 0.85 : 0.0);
  fclose(f);
  return g_strdup_printf("plugins/darkroom/lut3d/def_path=%s\n", lutdir);
}

static int cmd_actions(const char *raw, const char *xmp, const char *outdir)
{
  char *cube = path_join(outdir, "luts/warm.cube");
  MUST(dtb_open(raw, xmp));
  action("exposure", 0, "anything", NULL, DTB_E_NOT_FOUND);
  action("temperature", 0, "nosuchaction", NULL, DTB_E_NOT_FOUND);

  // white balance
  JsonNode *wb = action("temperature", 0, "get_wb", NULL, DTB_OK);
  double T0 = 0, tint0 = 0;
  if(wb)
  {
    JsonObject *o = json_node_get_object(wb);
    T0 = json_object_get_double_member(o, "temperature");
    tint0 = json_object_get_double_member(o, "tint");
    fprintf(stdout, "RESULT get_wb %.1f K tint %.4f preset %s\n", T0, tint0,
            json_object_get_null_member(o, "preset") ? "null" : json_object_get_string_member(o, "preset"));
    CHECK(T0 >= 1901 && T0 <= 25000, "temperature out of range");
    json_node_unref(wb);
  }
  action("temperature", 0, "set_wb", "{\"temperature\": 100000, \"tint\": 1}", DTB_E_RANGE);
  action("temperature", 0, "set_wb", "{\"temperature\": \"warm\"}", DTB_E_TYPE);
  action("temperature", 0, "set_wb", "{\"temperature\": 4500, \"tint\": 1.05}", DTB_OK);
  wb = action("temperature", 0, "get_wb", NULL, DTB_OK);
  if(wb)
  {
    JsonObject *o = json_node_get_object(wb);
    const double T1 = json_object_get_double_member(o, "temperature"), tint1 = json_object_get_double_member(o, "tint");
    fprintf(stdout, "RESULT after set_wb 4500/1.05: get_wb %.1f K tint %.4f preset %s\n", T1, tint1,
            json_object_get_string_member(o, "preset"));
    // the module's own conversion is a binary search to 1 K
    CHECK(fabs(T1 - 4500) < 2.0 && fabs(tint1 - 1.05) < 0.002, "set_wb then get_wb: %.1f %.4f", T1, tint1);
    CHECK(!strcmp(json_object_get_string_member(o, "preset"), "user modified"), "set_wb mode");
    json_node_unref(wb);
  }
  JsonNode *pr = action("temperature", 0, "list_presets", NULL, DTB_OK);
  const char *camera_preset = NULL;
  if(pr)
  {
    JsonArray *a = json_node_get_array(pr);
    fprintf(stdout, "RESULT white balance presets %u:", json_array_get_length(a));
    for(guint i = 0; i < json_array_get_length(a); i++)
    {
      JsonObject *o = json_array_get_object_element(a, i);
      fprintf(stdout, " '%s'", json_object_get_string_member(o, "name"));
      if(!camera_preset && !strcmp(json_object_get_string_member(o, "kind"), "camera"))
        camera_preset = g_strdup(json_object_get_string_member(o, "name"));
    }
    fprintf(stdout, "\n");
    CHECK(json_array_get_length(a) >= 2, "no standard modes listed");
    json_node_unref(pr);
  }
  action("temperature", 0, "apply_preset", "{\"name\": \"no such preset\"}", DTB_E_NOT_FOUND);
  action("temperature", 0, "apply_preset", "{\"name\": \"camera reference\"}", DTB_OK);
  wb = action("temperature", 0, "get_wb", NULL, DTB_OK);
  if(wb)
  {
    CHECK(!strcmp(json_object_get_string_member(json_node_get_object(wb), "preset"), "camera reference"), "mode");
    json_node_unref(wb);
  }
  if(camera_preset)
  {
    char args[256];
    snprintf(args, sizeof(args), "{\"name\": \"%s\"}", camera_preset);
    action("temperature", 0, "apply_preset", args, DTB_OK);
    wb = action("temperature", 0, "get_wb", NULL, DTB_OK);
    if(wb)
    {
      const char *got = json_object_get_string_member(json_node_get_object(wb), "preset");
      fprintf(stdout, "RESULT camera preset '%s' applied, get_wb reports '%s'\n", camera_preset, got);
      CHECK(!strcmp(got, camera_preset), "camera preset not reported back");
      json_node_unref(wb);
    }
    snprintf(args, sizeof(args), "{\"name\": \"%s\", \"tuning\": 99}", camera_preset);
    action("temperature", 0, "apply_preset", args, DTB_E_RANGE);
  }
  else
    fprintf(stdout, "INFO no camera white balance presets for this camera\n");
  action("temperature", 0, "set_wb", "{\"temperature\": 5200, \"tint\": 0.98}", DTB_OK);

  // lens
  JsonNode *cams = action("lens", 0, "list_cameras", NULL, DTB_OK);
  if(cams)
  {
    fprintf(stdout, "RESULT lensfun cameras %u\n", json_array_get_length(json_node_get_array(cams)));
    CHECK(json_array_get_length(json_node_get_array(cams)) > 100, "too few cameras");
    json_node_unref(cams);
  }
  JsonNode *mods = get_modules();
  JsonObject *lens = find_mod(mods, "lens", 0);
  gchar *cam = g_strdup(json_node_get_string(param_node(lens, "camera")));
  gchar *lensname = g_strdup(json_node_get_string(param_node(lens, "lens")));
  json_node_unref(mods);
  fprintf(stdout, "RESULT lens params now: camera '%s' lens '%s'\n", cam, lensname);
  action("lens", 0, "list_lenses", "{\"camera\": \"no such camera\"}", DTB_E_NOT_FOUND);
  if(cam[0])
  {
    char args[512];
    snprintf(args, sizeof(args), "{\"camera\": \"%s\"}", cam);
    JsonNode *lenses = action("lens", 0, "list_lenses", args, DTB_OK);
    const char *pick = NULL;
    if(lenses)
    {
      JsonArray *a = json_node_get_array(lenses);
      fprintf(stdout, "RESULT lenses for '%s': %u\n", cam, json_array_get_length(a));
      // a lens other than the current one, to see the change
      for(guint i = 0; i < json_array_get_length(a) && !pick; i++)
      {
        const char *mdl = json_object_get_string_member(json_array_get_object_element(a, i), "model");
        if(strcmp(mdl, lensname)) pick = mdl;
      }
      if(pick)
      {
        snprintf(args, sizeof(args), "{\"camera\": \"%s\", \"lens\": \"%s\"}", cam, pick);
        action("lens", 0, "set_lens", args, DTB_OK);
        mods = get_modules();
        lens = find_mod(mods, "lens", 0);
        fprintf(stdout, "RESULT set_lens '%s': params lens '%s' scale %.5f crop %.3f\n", pick,
                json_node_get_string(param_node(lens, "lens")), json_node_get_double(param_node(lens, "scale")),
                json_node_get_double(param_node(lens, "crop")));
        CHECK(!strcmp(json_node_get_string(param_node(lens, "lens")), pick), "set_lens did not set the lens");
        json_node_unref(mods);
        // and back to the original lens if Lensfun knows it (the XMP may name
        // a lens that is not in the database)
        gboolean known = FALSE;
        for(guint i = 0; i < json_array_get_length(a) && !known; i++)
          known = !strcmp(json_object_get_string_member(json_array_get_object_element(a, i), "model"), lensname);
        snprintf(args, sizeof(args), "{\"camera\": \"%s\", \"lens\": \"%s\"}", cam, lensname);
        if(known) action("lens", 0, "set_lens", args, DTB_OK);
        else fprintf(stdout, "INFO original lens '%s' is not in Lensfun; the fisheye stays set\n", lensname);
      }
      json_node_unref(lenses);
    }
    snprintf(args, sizeof(args), "{\"camera\": \"%s\", \"lens\": \"no such lens at all\"}", cam);
    action("lens", 0, "set_lens", args, DTB_E_NOT_FOUND);
  }
  else
    fprintf(stdout, "INFO the image's camera is not set in lens params\n");
  g_free(cam);
  g_free(lensname);

  // lut3d
  action("lut3d", 0, "set_file", "{\"path\": \"/etc/passwd\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"../x.cube\"}", DTB_E_ARG);
  action("lut3d", 0, "set_file", "{\"path\": \"missing.cube\"}", DTB_E_IO);
  char args[1024];
  snprintf(args, sizeof(args), "{\"path\": \"%s\"}", cube);
  action("lut3d", 0, "set_file", args, DTB_OK);
  mods = get_modules();
  JsonObject *lut = find_mod(mods, "lut3d", 0);
  CHECK(!strcmp(json_node_get_string(param_node(lut, "filepath")), "warm.cube")
        && json_object_get_boolean_member(lut, "enabled"), "lut3d filepath not set relative to the root");
  json_node_unref(mods);

  // denoise (profiled)
  JsonNode *pf = action("denoiseprofile", 0, "list_profiles", NULL, DTB_OK);
  const char *other = NULL;
  if(pf)
  {
    JsonArray *a = json_node_get_array(pf);
    fprintf(stdout, "RESULT noise profiles %u:", json_array_get_length(a));
    for(guint i = 0; i < json_array_get_length(a); i++)
      fprintf(stdout, " '%s'", json_object_get_string_member(json_array_get_object_element(a, i), "name"));
    fprintf(stdout, "\n");
    CHECK(json_array_get_length(a) >= 1
          && json_object_get_boolean_member(json_array_get_object_element(a, 0), "auto"), "no automatic entry");
    if(json_array_get_length(a) > 1)
      other = g_strdup(json_object_get_string_member(json_array_get_object_element(a, 1), "name"));
    json_node_unref(pf);
  }
  action("denoiseprofile", 0, "set_profile", "{\"name\": \"no such profile\"}", DTB_E_NOT_FOUND);
  if(other)
  {
    snprintf(args, sizeof(args), "{\"name\": \"%s\"}", other);
    action("denoiseprofile", 0, "set_profile", args, DTB_OK);
    const double a0 = param_num("denoiseprofile", 0, "a/0");
    fprintf(stdout, "RESULT set_profile '%s': a[0] %g\n", other, a0);
    CHECK(a0 > 0, "profile a not set");
  }
  action("denoiseprofile", 0, "set_profile", "{\"name\": \"auto\"}", DTB_OK);
  CHECK(param_num("denoiseprofile", 0, "a/0") == -1.0 || param_num("denoiseprofile", 0, "a/0") > 0,
        "automatic profile not set");

  // P1c item 6: every entry carries its position as "index", set_profile
  // takes it, and two entries of one name are told apart by it
  pf = action("denoiseprofile", 0, "list_profiles", NULL, DTB_OK);
  if(pf)
  {
    JsonArray *a = json_node_get_array(pf);
    const guint n = json_array_get_length(a);
    int bad_index = 0, dup_pairs = 0, dup_distinct = 0, by_index_ok = 0;
    for(guint i = 0; i < n; i++)
    {
      JsonObject *e = json_array_get_object_element(a, i);
      if(!json_object_has_member(e, "index") || json_object_get_int_member(e, "index") != (gint64)i) bad_index++;
    }
    CHECK(bad_index == 0, "%d noise profile entries without their position as index", bad_index);
    for(guint i = 1; i < n; i++)
    {
      snprintf(args, sizeof(args), "{\"index\": %u}", i);
      action("denoiseprofile", 0, "set_profile", args, DTB_OK);
      if(param_num("denoiseprofile", 0, "a/0") > 0) by_index_ok++;
      const char *ni = json_object_get_string_member(json_array_get_object_element(a, i), "name");
      for(guint k = 1; k < i; k++)
      {
        if(strcmp(ni, json_object_get_string_member(json_array_get_object_element(a, k), "name"))) continue;
        // the same name twice: both reachable by index, and the name selects the first
        dup_pairs++;
        double ai[3], ak[3], an[3];
        for(int c = 0; c < 3; c++)
        {
          char pth[16];
          snprintf(pth, sizeof(pth), "a/%d", c);
          ai[c] = param_num("denoiseprofile", 0, pth);
        }
        snprintf(args, sizeof(args), "{\"index\": %u}", k);
        action("denoiseprofile", 0, "set_profile", args, DTB_OK);
        for(int c = 0; c < 3; c++)
        {
          char pth[16];
          snprintf(pth, sizeof(pth), "a/%d", c);
          ak[c] = param_num("denoiseprofile", 0, pth);
        }
        snprintf(args, sizeof(args), "{\"name\": \"%s\"}", ni);
        action("denoiseprofile", 0, "set_profile", args, DTB_OK);
        for(int c = 0; c < 3; c++)
        {
          char pth[16];
          snprintf(pth, sizeof(pth), "a/%d", c);
          an[c] = param_num("denoiseprofile", 0, pth);
        }
        const gboolean distinct = ai[0] != ak[0] || ai[1] != ak[1] || ai[2] != ak[2];
        if(distinct) dup_distinct++;
        fprintf(stdout, "RESULT noise profile name '%s' at index %u and %u: a[0] %g and %g (%s); by name: %g\n", ni,
                k, i, ak[0], ai[0], distinct ? "different profiles" : "same values", an[0]);
        CHECK(an[0] == ak[0] && an[1] == ak[1] && an[2] == ak[2], "set_profile by name did not take the first entry");
      }
    }
    CHECK(by_index_ok == (int)n - 1, "set_profile by index set a profile for %d of %u entries", by_index_ok, n - 1);
    action("denoiseprofile", 0, "set_profile", "{\"index\": 0}", DTB_OK);
    const double a_auto = param_num("denoiseprofile", 0, "a/0");
    CHECK(a_auto == -1.0 || a_auto > 0, "index 0 did not set the automatic profile");
    snprintf(args, sizeof(args), "{\"index\": %u}", n);
    action("denoiseprofile", 0, "set_profile", args, DTB_E_RANGE);
    action("denoiseprofile", 0, "set_profile", "{\"index\": -1}", DTB_E_RANGE);
    action("denoiseprofile", 0, "set_profile", "{\"index\": 1.5}", DTB_E_TYPE);
    action("denoiseprofile", 0, "set_profile", "{\"index\": \"1\"}", DTB_E_TYPE);
    action("denoiseprofile", 0, "set_profile", "{\"index\": 1, \"name\": \"auto\"}", DTB_E_ARG);
    fprintf(stdout, "RESULT noise profile index: %u entries, index = position, %d set by index, %d repeated names"
            " (%d with different values), errors RANGE/TYPE/ARG as specified\n", n, by_index_ok, dup_pairs,
            dup_distinct);
    json_node_unref(pf);
    // back to the automatic profile, as the rest of the test expects
    action("denoiseprofile", 0, "set_profile", "{\"name\": \"auto\"}", DTB_OK);
  }

  MUST(dtb_xmp_write(path_join(outdir, "actions.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "actions_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

/* C5: thread count and pinning */
static int count_threads_pinned_to(const char *list)
{
  DIR *d = opendir("/proc/self/task");
  if(!d) return -1;
  int n = 0;
  struct dirent *e;
  while((e = readdir(d)))
  {
    if(e->d_name[0] == '.') continue;
    char p[512];
    snprintf(p, sizeof(p), "/proc/self/task/%s/status", e->d_name);
    gchar *s = read_file(p);
    if(!s) continue;
    const char *c = strstr(s, "Cpus_allowed_list:");
    if(c)
    {
      c += strlen("Cpus_allowed_list:");
      while(*c == ' ' || *c == '\t') c++;
      if(!strncmp(c, list, strlen(list)) && (c[strlen(list)] == '\n' || c[strlen(list)] == '\0')) n++;
    }
    g_free(s);
  }
  closedir(d);
  return n;
}

static long info_int(const char *key)
{
  JsonNode *n = get_json(dtb_engine_info);
  const long v = json_object_get_int_member(json_node_get_object(n), key);
  json_node_unref(n);
  return v;
}

static int cmd_threads(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  const long max = info_int("threads_max");
  MUST(dtb_open(raw, xmp));
  int pw, ph;
  uint8_t *ref = render_probe(&pw, &ph);
  char black0[64];
  snprintf(black0, sizeof(black0), "%.9g", param_num("exposure", 0, "black"));
  MUST(dtb_set_threads(1));
  CHECK(info_int("threads") == 1, "threads not 1");
  // a change, so that the render really runs with one thread
  MUST(dtb_param_set("exposure", 0, "black", "0.001"));
  const double t0 = now();
  uint8_t *one = render_probe(&pw, &ph);
  const double t1 = now() - t0;
  MUST(dtb_set_threads(1000));
  CHECK(info_int("threads") == max, "threads above the start-up count not clamped");
  MUST(dtb_param_set("exposure", 0, "black", black0));
  const double t2 = now();
  uint8_t *all = render_probe(&pw, &ph);
  const double t3 = now() - t2;
  MUST(dtb_set_threads(0));
  CHECK(info_int("threads") == max, "threads not restored");
  CHECK(probe_diff(ref, all, pw, ph) == 0, "render with all threads differs");
  fprintf(stdout, "RESULT threads max %ld; probe render 1 thread %.3f s, %ld threads %.3f s (%s); 1 vs all max diff %d\n",
          max, t1, max, t3, "build workspace or CI runner, not a phone", probe_diff(one, all, pw, ph) /* black differs */);

  // pinning: every engine thread on CPU 0
  EXPECT(dtb_set_thread_cpus((int[]){ 4096 }, 1), DTB_E_ARG);
  EXPECT(dtb_set_thread_cpus(NULL, 1), DTB_E_ARG);
  const int before = count_threads_pinned_to("0");
  MUST(dtb_set_thread_cpus((int[]){ 0 }, 1));
  MUST(dtb_param_set("exposure", 0, "black", "0.002"));
  uint8_t *pinned = render_probe(&pw, &ph);
  const int after = count_threads_pinned_to("0");
  JsonNode *ei = get_json(dtb_engine_info);
  JsonArray *tc = json_object_get_array_member(json_node_get_object(ei), "thread_cpus");
  CHECK(json_array_get_length(tc) == 1 && json_array_get_int_element(tc, 0) == 0, "thread_cpus not reported");
  json_node_unref(ei);
  fprintf(stdout, "RESULT threads pinned to CPU 0: %d before, %d after (engine thread + %ld OpenMP threads expected)\n",
          before, after, max - 1);
  CHECK(after >= before + max, "fewer threads pinned than the engine thread and its workers (%d -> %d)", before, after);
  MUST(dtb_set_thread_cpus(NULL, 0));
  const int unpinned = count_threads_pinned_to("0");
  fprintf(stdout, "RESULT after unpinning: %d threads still on CPU 0 only\n", unpinned);
  CHECK(unpinned <= before, "unpinning left threads pinned");
  MUST(dtb_param_set("exposure", 0, "black", black0));
  uint8_t *again = render_probe(&pw, &ph);
  CHECK(probe_diff(ref, again, pw, ph) == 0, "render after pinning differs");
  free(ref);
  free(one);
  free(all);
  free(pinned);
  free(again);
  MUST(dtb_close());
  return g_fail;
}

/* C6: a read-only session refuses every mutating call */
static int cmd_readonly(const char *raw, const char *xmp, const char *outdir)
{
  (void)xmp;
  char *bad = path_join(outdir, "newer.xmp");
  gchar *before = read_file(bad);
  MUST(dtb_open(raw, bad));
  char *res = NULL;
  int n = 0, refused = 0;
#define RO(expr)                                                               \
  do {                                                                         \
    n++;                                                                       \
    const int _r = (expr);                                                     \
    if(_r == DTB_E_READ_ONLY) refused++;                                       \
    else { fprintf(stdout, "FAIL read-only: %s returned %d\n", #expr, _r); g_fail = 1; } \
  } while(0)
  int np;
  RO(dtb_param_set("exposure", 0, "exposure", "1.0"));
  RO(dtb_params_set("exposure", 0, "{\"exposure\": 1.0}"));
  RO(dtb_set_enabled("vignette", 0, 1));
  RO(dtb_module_reset("exposure", 0));
  RO(dtb_module_action("temperature", 0, "set_wb", "{\"temperature\": 5000, \"tint\": 1}", &res));
  RO(dtb_module_action("temperature", 0, "apply_preset", "{\"name\": \"as shot\"}", &res));
  RO(dtb_module_action("lens", 0, "set_lens", "{\"camera\": \"x\", \"lens\": \"y\"}", &res));
  RO(dtb_module_action("lut3d", 0, "set_file", "{\"path\": \"x.cube\"}", &res));
  RO(dtb_module_action("denoiseprofile", 0, "set_profile", "{\"name\": \"auto\"}", &res));
  RO(dtb_instance_new("exposure", 0, &np));
  RO(dtb_instance_duplicate("exposure", 0, &np));
  RO(dtb_instance_delete("exposure", 0));
  RO(dtb_instance_move("exposure", 0, 1));
  RO(dtb_instance_rename("exposure", 0, "x"));
  RO(dtb_history_compress());
  RO(dtb_undo());
  RO(dtb_redo());
  RO(dtb_mask_add_circle("exposure", 0, 0.5f, 0.5f, 0.1f, 0.01f));
  RO(dtb_metadata_reload(bad));
  RO(dtb_xmp_write(path_join(outdir, "ro_out.xmp")));
  fprintf(stdout, "RESULT read-only session: %d of %d mutating calls refused\n", refused, n);
  // what stays allowed: navigation, reading, rendering, export
  {
    int end = 0;
    const int items = history_count(&end);
    MUST(dtb_history_goto(0));
    int e0 = -1;
    history_count(&e0);
    CHECK(e0 == 0, "goto 0 in a read-only session did not move the end");
    MUST(dtb_history_goto(end));
    CHECK(history_count(NULL) == items, "goto lost items");
    fprintf(stdout, "RESULT read-only session: history_goto 0 and back to %d allowed\n", end);
  }
  JsonNode *m = get_modules();
  json_node_unref(m);
  JsonNode *h = get_history();
  json_node_unref(h);
  int w, hh;
  uint8_t *px = render_probe(&w, &hh);
  free(px);
  JsonNode *wb = action("temperature", 0, "get_wb", NULL, DTB_OK);
  if(wb) json_node_unref(wb);
  MUST(dtb_export(path_join(outdir, "ro_export.jpg"), "{\"max_width\": 512, \"max_height\": 512}"));
  MUST(dtb_close());
  gchar *after = read_file(bad);
  CHECK(before && after && !strcmp(before, after), "the guarded XMP changed");
  struct stat sb;
  CHECK(stat(path_join(outdir, "ro_out.xmp"), &sb) != 0, "a read-only session wrote an XMP");
  g_free(before);
  g_free(after);
  return g_fail;
}

/* C8 (P1c item 2): the edit a session starts from, within one process.
 * open(raw, NULL) without a sidecar is the default edit every time; open
 * with an XMP (given, or the sidecar next to the RAW) is exactly that XMP's
 * edit, whatever earlier sessions changed. */
typedef struct
{
  char *modules, *history;
  uint8_t *px;
  int w, h;
} snap_t;

static snap_t snap(void)
{
  snap_t s;
  s.modules = get_modules_str();
  s.history = get_history_str();
  s.px = preview_px(&s.w, &s.h);
  return s;
}

static void snap_free(snap_t *s)
{
  dtb_free(s->modules);
  dtb_free(s->history);
  free(s->px);
}

static void snap_same(const snap_t *a, const char *what)
{
  snap_t b = snap();
  const int mod = !strcmp(a->modules, b.modules), his = !strcmp(a->history, b.history);
  const int px = (a->w == b.w && a->h == b.h) ? probe_diff(a->px, b.px, a->w, a->h) : 999;
  fprintf(stdout, "RESULT %s: modules %s, history %s, pixels max %d\n", what, mod ? "equal" : "DIFFER",
          his ? "equal" : "DIFFER", px);
  CHECK(mod && his && px == 0, "%s differs from the expected edit", what);
  snap_free(&b);
}

static void some_edits(void)
{
  MUST(dtb_param_set("exposure", 0, "exposure", "1.5"));
  MUST(dtb_history_seal());
  int np = 0;
  MUST(dtb_instance_duplicate("exposure", 0, &np));
  MUST(dtb_param_set("exposure", np, "exposure", "-0.7"));
  MUST(dtb_set_enabled("vignette", 0, 1));
  MUST(dtb_history_seal());
  // items above the end, as P2b's reproduction left them
  int end = 0;
  history_count(&end);
  MUST(dtb_history_goto(end - 1));
}

static int copy_file(const char *from, const char *to)
{
  gchar *buf = NULL;
  gsize len = 0;
  if(!g_file_get_contents(from, &buf, &len, NULL)) return 1;
  const gboolean ok = g_file_set_contents(to, buf, len, NULL);
  g_free(buf);
  return ok ? 0 : 1;
}

static int cmd_reopen(const char *raw, const char *xmp, const char *outdir)
{
  // the default edit: the first import in this process (fresh library)
  MUST(dtb_open(raw, NULL));
  snap_t d0 = snap();
  int d0_end = -1;
  const int d0_items = history_count(&d0_end);
  fprintf(stdout, "RESULT default edit: %d history items, end %d\n", d0_items, d0_end);
  // every item of the default edit is in effect (a first import shows them all)
  CHECK(d0_end == d0_items && d0_items > 0, "default edit: end %d with %d items", d0_end, d0_items);
  {
    int w, h;
    uint8_t *buf = render_full(&w, &h, NULL);
    write_ppm(path_join(outdir, "reopen_default_bridge.ppm"), buf, w, h);
    free(buf);
  }
  some_edits();
  MUST(dtb_close());

  MUST(dtb_open(raw, NULL));
  snap_same(&d0, "open(raw, NULL) after an edited session");
  {
    int e = -1;
    const int n = history_count(&e);
    CHECK(e == n, "default edit after reopening: end %d with %d items", e, n);
  }
  some_edits();
  MUST(dtb_xmp_write(path_join(outdir, "reopen_edited.xmp")));
  MUST(dtb_close());

  MUST(dtb_open(raw, xmp));
  snap_t x0 = snap();
  some_edits();
  MUST(dtb_close());
  MUST(dtb_open(raw, xmp));
  snap_same(&x0, "open(raw, xmp) after an edited session");
  MUST(dtb_close());

  MUST(dtb_open(raw, path_join(outdir, "reopen_edited.xmp")));
  MUST(dtb_close());
  MUST(dtb_open(raw, NULL));
  snap_same(&d0, "open(raw, NULL) after a session on another XMP");
  MUST(dtb_close());
  MUST(dtb_open(raw, xmp));
  snap_same(&x0, "open(raw, xmp) after a session on another XMP");
  MUST(dtb_close());

  // the sidecar next to the RAW counts as the XMP
  char *dir = path_join(outdir, "sidecar");
  mkdir(dir, 0700);
  gchar *base = g_path_get_basename(raw);
  char *copy = path_join(dir, base);
  gchar *side = g_strconcat(copy, ".xmp", NULL);
  CHECK(copy_file(raw, copy) == 0 && copy_file(xmp, side) == 0, "cannot copy the RAW and its sidecar");
  MUST(dtb_open(copy, NULL));
  snap_same(&x0, "open(copy, NULL) with a sidecar");
  some_edits();
  MUST(dtb_close());
  MUST(dtb_open(copy, NULL));
  snap_same(&x0, "open(copy, NULL) with a sidecar, after an edited session");
  MUST(dtb_close());
  unlink(side);
  MUST(dtb_open(copy, NULL));
  snap_same(&d0, "open(copy, NULL) after its sidecar was removed");
  MUST(dtb_close());
  unlink(copy);
  g_free(base);
  g_free(side);
  snap_free(&d0);
  snap_free(&x0);
  return g_fail;
}

/* C9 (P1c item 5): consecutive parameter changes of one instance between
 * seals (a slider drag) only resynchronise that item in the pipes
 * (DT_DEV_PIPE_TOP_CHANGED, as desktop). After each drag the picture must
 * equal a fresh session of the same edit, and at the end darktable-cli's. */
static int cmd_drag(const char *raw, const char *xmp, const char *outdir)
{
  const char *klass = getenv("DTB_TEST_CLASS");
  const gboolean bayer = klass && !strcmp(klass, "bayer");
  struct { const char *op, *path, *v[3]; const char *before; int sealed; } cases[] = {
    { "exposure", "exposure", { "0.3", "0.6", "0.9" }, NULL },
    // three separate changes (a new item each, appended on top)
    { "colorbalancergb", "saturation_global", { "0.1", "0.2", "0.3" }, NULL, 1 },
    { "exposure", "black", { "0.001", "0.002", "0.003" }, NULL, 1 },
    { "colorbalancergb", "vibrance", { "0.1", "0.2", "0.3" }, NULL },
    { "sigmoid", "middle_grey_contrast", { "1.6", "1.8", "2.0" }, NULL },
    { "temperature", "red", { "2.0", "1.9", "1.8" }, NULL },
    // white balance dragged while color calibration adapts from it
    { "temperature", "blue", { "1.3", "1.2", "1.1" }, "channelmixerrgb" },
    { "colorin", "type", { "\"DT_COLORSPACE_STANDARD_MATRIX\"", "\"DT_COLORSPACE_LIN_REC709\"",
                           "\"DT_COLORSPACE_ENHANCED_MATRIX\"" }, NULL },
    { "demosaic", "demosaicing_method", { "\"DT_IOP_DEMOSAIC_RCD\"", "\"DT_IOP_DEMOSAIC_AMAZE\"",
                                          "\"DT_IOP_DEMOSAIC_LMMSE\"" }, NULL },
  };
  char *tmpx = path_join(outdir, "drag_step.xmp");
  MUST(dtb_open(raw, xmp));
  int worst = 0, n = 0;
  for(size_t c = 0; c < sizeof(cases) / sizeof(cases[0]); c++)
  {
    if(!strcmp(cases[c].op, "demosaic") && !bayer) continue;
    if(cases[c].before) MUST(dtb_set_enabled(cases[c].before, 0, 1));
    MUST(dtb_history_seal());
    int items0 = history_count(NULL);
    for(int k = 0; k < 3; k++)
    {
      MUST(dtb_param_set(cases[c].op, 0, cases[c].path, cases[c].v[k]));
      if(cases[c].sealed) MUST(dtb_history_seal());
      // the app renders the small picture after every step
      int w, h;
      uint8_t *p = preview_px(&w, &h);
      free(p);
    }
    const int items1 = history_count(NULL);
    int pw, ph, qw, qh;
    uint8_t *probe = render_probe(&pw, &ph);
    uint8_t *prev = preview_px(&qw, &qh);
    MUST(dtb_history_seal());
    MUST(dtb_xmp_write(tmpx));
    MUST(dtb_close());
    MUST(dtb_open(raw, tmpx));
    int pw2, ph2, qw2, qh2;
    uint8_t *probe2 = render_probe(&pw2, &ph2);
    uint8_t *prev2 = preview_px(&qw2, &qh2);
    const int d1 = (pw == pw2 && ph == ph2) ? probe_diff(probe, probe2, pw, ph) : 999;
    const int d2 = (qw == qw2 && qh == qh2) ? probe_diff(prev, prev2, qw, qh) : 999;
    fprintf(stdout, "RESULT %s %s.%s (3 steps, %d item%s): region vs fresh session max %d, preview max %d\n",
            cases[c].sealed ? "sealed changes" : "drag", cases[c].op, cases[c].path, items1 - items0,
            items1 - items0 == 1 ? "" : "s", d1, d2);
    CHECK(items1 - items0 == (cases[c].sealed ? 3 : 1), "%s made %d items", cases[c].op, items1 - items0);
    CHECK(d1 == 0 && d2 == 0, "after a drag of %s.%s the picture differs from a fresh session", cases[c].op,
          cases[c].path);
    worst = MAX(worst, MAX(d1, d2));
    n++;
    free(probe);
    free(probe2);
    free(prev);
    free(prev2);
  }
  fprintf(stdout, "RESULT drag: %d drags, worst difference to a fresh session %d\n", n, worst);
  MUST(dtb_xmp_write(path_join(outdir, "drag.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "drag_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

/* P1c item 5: where the time of a small preview render after a change goes.
 * Prints per-step medians; run with DTB_TIMING=1 for the bridge's own step
 * timers and DTB_DT_DEBUG=perf for darktable's per-module times. */
static int cmp_dbl(const void *a, const void *b)
{
  const double x = *(const double *)a, y = *(const double *)b;
  return x < y ? -1 : x > y;
}

static double median(double *v, int n)
{
  qsort(v, n, sizeof(double), cmp_dbl);
  return n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

static void cost_series(const char *label, const char *op, const char *path, double base, int seal, int size,
                        int n)
{
  double tp[64], tr[64];
  for(int i = 0; i < n; i++)
  {
    char v[32];
    snprintf(v, sizeof(v), "%.4f", base + 0.01 * (i + 1));
    // the step's marker first, so the change's own timers count to this step
    fprintf(stderr, "DTB_STEP %s %d %d\n", label, size, i);
    fprintf(stdout, "DTB_STEP %s %d %d\n", label, size, i);
    double t0 = now();
    if(op) MUST(dtb_param_set(op, 0, path, v));
    if(op && seal) MUST(dtb_history_seal());
    tp[i] = (now() - t0) * 1e3;
    t0 = now();
    uint8_t *px = NULL;
    int w, h;
    MUST(dtb_render_preview(size, &px, &w, &h));
    tr[i] = (now() - t0) * 1e3;
    dtb_free(px);
  }
  const double mp = median(tp, n), mr = median(tr, n);  // sorts both
  fprintf(stdout, "RESULT cost %s size %d: change %.2f ms, preview render %.2f ms (medians of %d; min %.2f max %.2f)\n",
          label, size, mp, mr, n, tr[0], tr[n - 1]);
}

static int cmd_cost(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  uint8_t *px = NULL;
  int w, h;
  // warm: input loaded, pipe built, caches filled
  for(int k = 0; k < 3; k++)
  {
    MUST(dtb_render_preview(320, &px, &w, &h));
    dtb_free(px);
  }
  fprintf(stdout, "RESULT cost preview size %dx%d\n", w, h);
  const int n = 9;
  cost_series("unchanged", NULL, NULL, 0, 0, 320, n);
  // a late module: color balance rgb (P2b's late change), merged into one
  // item as a slider drag does, then one item per step (sealed)
  cost_series("late_drag", "colorbalancergb", "vibrance", 0.1, 0, 320, n);
  cost_series("late_sealed", "colorbalancergb", "vibrance", 0.3, 1, 320, n);
  cost_series("early_drag", "exposure", "exposure", 0.5, 0, 320, n);
  cost_series("late_drag", "colorbalancergb", "vibrance", 0.1, 0, 1024, n);
  cost_series("unchanged", NULL, NULL, 0, 0, 1024, n);
  MUST(dtb_close());
  return g_fail;
}

/* C7: atomic xmp_write, export options, metadata_reload */
static int count_temp_files(const char *dir)
{
  DIR *d = opendir(dir);
  int n = 0;
  struct dirent *e;
  while(d && (e = readdir(d)))
    if(e->d_name[0] == '.' && strstr(e->d_name, ".xmp.")) n++;
  if(d) closedir(d);
  return n;
}

static int cmd_output(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  char *out = path_join(outdir, "out.xmp");
  unlink(out);
  MUST(dtb_xmp_write(out));
  MUST(dtb_param_set("exposure", 0, "exposure", "0.4"));
  MUST(dtb_xmp_write(out));  // over an existing, healthy file
  CHECK(xmp_problems(out) == 0, "written XMP fails the guard");
  CHECK(count_temp_files(outdir) == 0, "temporary files left next to the XMP");
  EXPECT(dtb_xmp_write(path_join(outdir, "no/such/dir/x.xmp")), DTB_E_IO);
  CHECK(count_temp_files(outdir) == 0, "temporary files left after a failed write");

  // exports: format, depth, size, profile
  struct { const char *name, *opts; int expect; } ex[] = {
    { "e_default.jpg", NULL, DTB_OK },
    { "e_q80_small.jpg", "{\"format\": \"jpeg\", \"quality\": 80, \"max_width\": 1000, \"max_height\": 1000}", DTB_OK },
    { "e_8.png", "{\"format\": \"png\", \"bit_depth\": 8}", DTB_OK },
    { "e_16.png", "{\"format\": \"png\", \"bit_depth\": 16, \"max_width\": 800, \"max_height\": 800}", DTB_OK },
    { "e_16.tif", "{\"format\": \"tiff\", \"bit_depth\": 16, \"max_width\": 800, \"max_height\": 800}", DTB_OK },
    { "e_32.tif", "{\"format\": \"tiff\", \"bit_depth\": 32, \"max_width\": 400, \"max_height\": 400}", DTB_OK },
    // webp only where darktable was built with libwebp (its plugin file
    // exists); otherwise the specification asks for ARG with a message
    { "e.webp", "{\"format\": \"webp\", \"quality\": 90, \"max_width\": 800, \"max_height\": 800}", -100 },
    { "e_adobe.jpg", "{\"profile\": \"adobergb\", \"max_width\": 800, \"max_height\": 800}", DTB_OK },
    { "e_p3.jpg", "{\"profile\": \"displayp3\", \"max_width\": 800, \"max_height\": 800}", DTB_OK },
    { "e_srgb.jpg", "{\"profile\": \"srgb\", \"max_width\": 800, \"max_height\": 800, \"high_quality\": false}", DTB_OK },
    { "e_up.jpg", "{\"max_width\": 9000, \"max_height\": 9000, \"upscale\": true}", DTB_OK },
    { "bad1.jpg", "{\"format\": \"bmp\"}", DTB_E_ARG },
    { "bad2.jpg", "{\"format\": \"jpeg\", \"bit_depth\": 16}", DTB_E_ARG },
    { "bad3.jpg", "{\"quality\": 0}", DTB_E_RANGE },
    { "bad4.jpg", "{\"colour\": 1}", DTB_E_ARG },
    { "bad5.jpg", "{\"profile\": \"prophoto\"}", DTB_E_ARG },
    { "bad6.jpg", "{\"upscale\": 1}", DTB_E_TYPE },
  };
  gchar *webp_plugin = g_build_filename(getenv("DT_PREFIX"), "lib/darktable/plugins/imageio/format/libwebp.so", NULL);
  const int webp_expect = g_file_test(webp_plugin, G_FILE_TEST_EXISTS) ? DTB_OK : DTB_E_ARG;
  fprintf(stdout, "RESULT webp format plugin %s\n", webp_expect == DTB_OK ? "built" : "not built: ARG expected");
  g_free(webp_plugin);
  for(size_t i = 0; i < sizeof(ex) / sizeof(ex[0]); i++)
  {
    if(ex[i].expect == -100) ex[i].expect = webp_expect;
    char *p = path_join(outdir, ex[i].name);
    unlink(p);
    const int r = dtb_export(p, ex[i].opts);
    struct stat sb;
    const long size = stat(p, &sb) == 0 ? (long)sb.st_size : -1;
    fprintf(stdout, "RESULT export %s %s -> %d, %ld bytes\n", ex[i].name, ex[i].opts ? ex[i].opts : "(defaults)", r, size);
    CHECK(r == ex[i].expect, "export %s returned %d, expected %d", ex[i].name, r, ex[i].expect);
    if(ex[i].expect == DTB_OK) CHECK(size > 0, "export %s wrote nothing", ex[i].name);
  }

  // metadata_reload: a copy of the XMP with another rating and a colour
  // label; history must stay as it is
  gchar *text = read_file(out);
  GString *s = g_string_new(text);
  gchar *pos = strstr(s->str, "xmp:Rating=\"");
  CHECK(pos != NULL, "no rating in the written XMP");
  if(pos)
  {
    const gsize at = pos - s->str + strlen("xmp:Rating=\"");
    gsize len = 0;
    while(s->str[at + len] != '"') len++;
    g_string_erase(s, at, len);
    g_string_insert(s, at, "4");
  }
  // a colour label: darktable writes them as darktable:colorlabels <rdf:Seq>
  pos = strstr(s->str, "<darktable:history>");
  if(pos)
    g_string_insert(s, pos - s->str,
                    "<darktable:colorlabels>\n    <rdf:Seq>\n     <rdf:li>0</rdf:li>\n     <rdf:li>3</rdf:li>\n    </rdf:Seq>\n   </darktable:colorlabels>\n   ");
  char *meta = path_join(outdir, "meta.xmp");
  g_file_set_contents(meta, s->str, -1, NULL);
  g_string_free(s, TRUE);
  g_free(text);
  char *hist_before = get_history_str();
  char *mods_before = get_modules_str();
  MUST(dtb_metadata_reload(meta));
  char *hist_after = get_history_str();
  char *mods_after = get_modules_str();
  CHECK(!strcmp(hist_before, hist_after) && !strcmp(mods_before, mods_after), "metadata_reload changed the edit");
  JsonNode *ii = get_json(dtb_image_info);
  JsonObject *im = json_node_get_object(ii);
  JsonArray *labels = json_object_get_array_member(im, "color_labels");
  const guint nl = json_array_get_length(labels);
  fprintf(stdout, "RESULT metadata_reload: rating %ld, labels [%ld, %ld]\n", (long)json_object_get_int_member(im, "rating"),
          nl > 0 ? (long)json_array_get_int_element(labels, 0) : -1L, nl > 1 ? (long)json_array_get_int_element(labels, 1) : -1L);
  CHECK(json_object_get_int_member(im, "rating") == 4, "rating not reloaded");
  CHECK(nl == 2 && json_array_get_int_element(labels, 0) == 0 && json_array_get_int_element(labels, 1) == 3,
        "colour labels not reloaded as numbers [0, 3]");
  json_node_unref(ii);
  // the reloaded metadata go out with the next write, the edit as before
  MUST(dtb_xmp_write(path_join(outdir, "meta_out.xmp")));
  gchar *mo = read_file(path_join(outdir, "meta_out.xmp"));
  CHECK(mo && strstr(mo, "xmp:Rating=\"4\""), "rating not written back");
  g_free(mo);
  EXPECT(dtb_metadata_reload(path_join(outdir, "missing.xmp")), DTB_E_IO);
  MUST(dtb_close());
  return g_fail;
}

int main(int argc, char **argv)
{
  if(argc < 3)
  {
    fprintf(stderr, "usage: dtb_test <workdir> <command> [args]\n");
    return 2;
  }
  setvbuf(stdout, NULL, _IOLBF, 0);
  const char *cmd = argv[2];
  if(!strcmp(cmd, "actions") && argc == 6)
  {
    char *rc = actions_prepare(argv[5]);
    engine_start_ext(argv[1], 0, rc);
    g_free(rc);
  }
  else
    engine_start(argv[1]);
  {
    char *s = NULL;
    MUST(dtb_engine_info(&s));
    JsonNode *n = jparse_owned(s);
    fprintf(stdout, "RESULT engine %s\n", json_object_get_string_member(json_node_get_object(n), "darktable_version"));
    json_node_unref(n);
  }
  int r = 2;
  if(!strcmp(cmd, "check") && argc == 4) r = cmd_check(argv[3]);
  else if(!strcmp(cmd, "render") && argc == 6) r = cmd_render(argv[3], argv[4], argv[5]);
  else if(argc == 6)
  {
    static const struct { const char *name; int (*f)(const char *, const char *, const char *); } cmds[] = {
      { "param", cmd_param }, { "instances", cmd_instances }, { "history", cmd_history },
      { "region", cmd_region }, { "guard", cmd_guard }, { "timing", cmd_timing },
      { "cancel", cmd_cancel }, { "mask", cmd_mask }, { "extras", cmd_extras },
      { "api", cmd_api }, { "params", cmd_params }, { "undo", cmd_undo },
      { "actions", cmd_actions }, { "threads", cmd_threads }, { "readonly", cmd_readonly },
      { "output", cmd_output }, { "reopen", cmd_reopen },
      { "cost", cmd_cost }, { "drag", cmd_drag },
    };
    for(size_t i = 0; i < sizeof(cmds) / sizeof(cmds[0]); i++)
      if(!strcmp(cmd, cmds[i].name)) r = cmds[i].f(argv[3], argv[4], argv[5]);
    if(r == 2) fprintf(stderr, "unknown command: %s\n", cmd);
  }
  else if(!strcmp(cmd, "exposures") && argc == 5)
  {
    MUST(dtb_open(argv[3], argv[4]));
    print_exposure_instances("listed");
    int end;
    fprintf(stdout, "RESULT history %d\n", history_count(&end));
    MUST(dtb_close());
    r = 0;
  }
  else if(!strcmp(cmd, "modules") && argc == 5)
  {
    MUST(dtb_open(argv[3], argv[4]));
    char *j = get_modules_str();
    fprintf(stdout, "%s\n", j);
    dtb_free(j);
    char *hj = get_history_str();
    fprintf(stdout, "%s\n", hj);
    dtb_free(hj);
    r = 0;
  }
  else
    fprintf(stderr, "unknown command or wrong arguments: %s\n", cmd);
  dtb_shutdown();
  fprintf(stdout, "RESULT exit %d\n", r);
  return r;
}

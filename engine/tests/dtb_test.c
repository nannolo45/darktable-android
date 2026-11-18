// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Test driver for the bridge prototype (brief S3). Uses dtbridge.h only.
 *
 *   dtb_test <workdir> <command> [args]
 *
 * Environment: DT_PREFIX = darktable install prefix (share/darktable and
 * lib/darktable below it). <workdir> holds the config, cache and private
 * library of this run; the library is recreated for every invocation.
 *
 * Commands (one per test; run_tests.sh drives them and compares with
 * darktable-cli):
 *   check <xmp>                               print the guard's JSON; exit 3 if problems
 *   render <raw> <xmp> <out.ppm>              T1: full render at scale 1
 *   param <raw> <xmp> <outdir>                T2
 *   instances <raw> <xmp> <outdir>            T3
 *   history <raw> <xmp> <outdir>              T4
 *   region <raw> <xmp> <outdir>               T5
 *   guard <raw> <xmp> <outdir>                T6 (expects outdir/newer.xmp, outdir/unknown.xmp)
 *   timing <raw> <xmp> <outdir>               T7
 *   cancel <raw> <xmp> <outdir>               T8
 *   mask <raw> <xmp> <outdir>                 T9
 *   extras <raw> <xmp> <outdir>               X1: preview, export, move, delete
 *   exposures <raw> <xmp>                    list exposure instances (T3, fresh session)
 *   modules <raw> <xmp>                       print modules and history JSON
 * Results are printed as "RESULT key value" lines; exit code 0 = the checks
 * the driver can do on its own passed. Pixel comparisons with darktable-cli
 * are done by run_tests.sh.
 */
#include "dtbridge.h"

#include <errno.h>
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

static void engine_start(const char *workdir)
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
  dtb_paths_t p = { configdir, cachedir, datadir, moduledir, tmpdir, library, 0 };
  MUST(dtb_init(&p));
}

/* full render at scale 1; returns malloc'ed RGBA */
static uint8_t *render_full(int *w, int *h, double *seconds)
{
  MUST(dtb_render_size(1.0f, w, h));
  uint8_t *buf = malloc((size_t)4 * *w * *h);
  const double t0 = now();
  MUST(dtb_render(0, 0, *w, *h, 1.0f, buf));
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

/* count history items and read history_end from the JSON (no JSON parser in
 * the driver on purpose: it only uses dtbridge.h and libc) */
static int history_count(int *end)
{
  char *j = dtb_history_json();
  int n = 0;
  for(const char *p = j; (p = strstr(p, "\"num\"")); p++) n++;
  if(end)
  {
    const char *e = strstr(j, "\"history_end\":");
    *end = e ? atoi(e + strlen("\"history_end\":")) : -1;
  }
  dtb_free(j);
  return n;
}

/* the value of params.<param> of instance op/prio in the modules JSON */
static int module_info(const char *op, int prio, const char *param, double *value,
                       char *name, size_t name_len, int *enabled)
{
  char *j = dtb_modules_json();
  char key[256];
  snprintf(key, sizeof(key), "\"operation\":\"%s\"", op);
  int found = 0;
  for(char *p = strstr(j, key); p; p = strstr(p + 1, key))
  {
    char *end = strstr(p, "}}");
    char *pr = strstr(p, "\"multi_priority\":");
    if(!pr || (end && pr > end) || atoi(pr + 17) != prio) continue;
    found = 1;
    if(name)
    {
      char *in = strstr(p, "\"instance_name\":\"");
      name[0] = 0;
      if(in)
      {
        in += 17;
        size_t k = 0;
        while(in[k] && in[k] != '"' && k + 1 < name_len) { name[k] = in[k]; k++; }
        name[k] = 0;
      }
    }
    if(enabled)
    {
      char *en = strstr(p, "\"enabled\":");
      *enabled = en && !strncmp(en + 10, "true", 4);
    }
    if(param && value)
    {
      char pk[256];
      snprintf(pk, sizeof(pk), "\"%s\":", param);
      char *pv = strstr(strstr(p, "\"params\":"), pk);
      *value = pv && (!end || pv < end) ? atof(pv + strlen(pk)) : NAN;
    }
    break;
  }
  dtb_free(j);
  return found;
}

static void print_exposure_instances(const char *tag)
{
  for(int prio = 0; prio < 8; prio++)
  {
    double v = NAN;
    char name[128];
    int en = 0;
    if(module_info("exposure", prio, "exposure", &v, name, sizeof(name), &en))
      fprintf(stdout, "RESULT %s exposure.%d name='%s' enabled=%d exposure=%.4f\n", tag, prio, name, en, v);
  }
}

/* ---------------------------------------------------------------- */

static int cmd_check(const char *xmp)
{
  int n = 0;
  char *j = dtb_xmp_check(xmp, &n);
  if(!j)
  {
    fprintf(stdout, "FAIL check: %s\n", dtb_last_error());
    return 1;
  }
  fprintf(stdout, "%s\nRESULT problems %d\n", j, n);
  dtb_free(j);
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
  CHECK(module_info("exposure", 0, "exposure", &ev, NULL, 0, NULL), "no exposure.0");
  MUST(dtb_param_set_float("exposure", 0, "exposure", (float)(ev + 1.0)));
  const int n1 = history_count(&end1);
  double ev1 = NAN;
  module_info("exposure", 0, "exposure", &ev1, NULL, 0, NULL);
  fprintf(stdout, "RESULT exposure_before %.4f\nRESULT exposure_after %.4f\n", ev, ev1);
  fprintf(stdout, "RESULT history_before %d end %d\nRESULT history_after %d end %d\n", n0, end0, n1, end1);
  CHECK(n1 == n0 + 1 && end1 == end0 + 1, "history did not grow by exactly one (%d -> %d)", n0, n1);
  CHECK(fabs(ev1 - (ev + 1.0)) < 1e-5, "exposure not set");
  char *x = path_join(outdir, "param.xmp");
  MUST(dtb_xmp_write(x));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  char *o = path_join(outdir, "param_bridge.ppm");
  write_ppm(o, buf, w, h);
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
  module_info("exposure", 0, "exposure", &base, NULL, 0, NULL);
  module_info("exposure", newp, "exposure", &copy, NULL, 0, NULL);
  CHECK(fabs(base - copy) < 1e-6, "duplicate did not copy parameters (%f vs %f)", base, copy);
  MUST(dtb_param_set_float("exposure", newp, "exposure", -0.5f));
  MUST(dtb_set_enabled("exposure", newp, 0));
  int en = 1;
  module_info("exposure", newp, NULL, NULL, NULL, 0, &en);
  CHECK(en == 0, "copy not switched off");
  MUST(dtb_set_enabled("exposure", newp, 1));
  module_info("exposure", newp, NULL, NULL, NULL, 0, &en);
  CHECK(en == 1, "copy not switched on");
  print_exposure_instances("after");
  int end;
  fprintf(stdout, "RESULT history %d\n", history_count(&end));
  char *x = path_join(outdir, "instances.xmp");
  MUST(dtb_xmp_write(x));
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

  // one step back: the state before the last item
  const int k = end - 1;
  MUST(dtb_history_goto(k));
  uint8_t *back = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "history_back.ppm"), back, w, h);
  // the XMP written now has history_end k: darktable-cli renders step k from it
  MUST(dtb_xmp_write(path_join(outdir, "history_back.xmp")));
  int mx;
  double mean;
  diff_stats(top, back, (size_t)w * h, &mx, &mean);
  fprintf(stdout, "RESULT goto %d differs_from_top max %d mean %.4f\n", k, mx, mean);

  // and forward again: must equal the first render
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
  CHECK(end2 == n2, "history_end %d does not match the %d items after compress", end2, n2);
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

static int cmd_region(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  // diagnosis only (t5_bisect.sh): render with another demosaic method
  const char *dm = getenv("DTB_TEST_DEMOSAIC_METHOD");
  if(dm)
  {
    MUST(dtb_param_set_int("demosaic", 0, "demosaicing_method", atoi(dm)));
    fprintf(stdout, "RESULT demosaicing_method %s\n", dm);
  }
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
    MUST(dtb_render(xs[i], ys[i], rw, rh, 1.0f, reg));
    const double t = now() - t0;
    for(int y = 0; y < rh; y++)
      memcpy(crop + (size_t)4 * rw * y, full + 4 * ((size_t)(ys[i] + y) * w + xs[i]), (size_t)4 * rw);
    int mx;
    double mean;
    diff_stats(reg, crop, (size_t)rw * rh, &mx, &mean);
    fprintf(stdout, "RESULT region %d at %d,%d max %d mean %.4f render_s %.3f\n", i, xs[i], ys[i], mx, mean, t);
    char name[64];
    snprintf(name, sizeof(name), "region%d.ppm", i);
    write_ppm(path_join(outdir, name), reg, rw, rh);
    snprintf(name, sizeof(name), "region%d_crop.ppm", i);
    write_ppm(path_join(outdir, name), crop, rw, rh);
    fprintf(stdout, "RESULT region%d_origin %d %d\n", i, xs[i], ys[i]);

    // measurement for the write-up, not part of the pass/fail check: the
    // same region rendered with a margin around it and cropped
    const int margins[3] = { 16, 64, 256 };
    for(int k = 0; k < 3; k++)
    {
      const int m = margins[k];
      const int px = xs[i] - m < 0 ? 0 : xs[i] - m, py = ys[i] - m < 0 ? 0 : ys[i] - m;
      const int pw = (xs[i] + rw + m > w ? w : xs[i] + rw + m) - px;
      const int ph = (ys[i] + rh + m > h ? h : ys[i] + rh + m) - py;
      uint8_t *pad = malloc((size_t)4 * pw * ph);
      const double t1 = now();
      MUST(dtb_render(px, py, pw, ph, 1.0f, pad));
      const double tp = now() - t1;
      for(int y = 0; y < rh; y++)
        memcpy(reg + (size_t)4 * rw * y, pad + 4 * ((size_t)(ys[i] - py + y) * pw + (xs[i] - px)), (size_t)4 * rw);
      diff_stats(reg, crop, (size_t)rw * rh, &mx, &mean);
      fprintf(stdout, "RESULT region %d margin %d max %d mean %.4f render_s %.3f\n", i, m, mx, mean, tp);
      free(pad);
    }
  }
  free(reg);
  free(crop);
  free(full);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_guard(const char *raw, const char *xmp, const char *outdir)
{
  const char *bad[2] = { "newer.xmp", "unknown.xmp" };
  for(int i = 0; i < 2; i++)
  {
    char *bx = path_join(outdir, bad[i]);
    int n = 0;
    char *j = dtb_xmp_check(bx, &n);
    fprintf(stdout, "RESULT check %s problems %d %s\n", bad[i], n, j ? j : "(null)");
    CHECK(n > 0, "%s not reported", bad[i]);
    dtb_free(j);

    // opening it gives a read-only session, which refuses to write anywhere
    MUST(dtb_open(raw, bx));
    CHECK(dtb_is_read_only(), "%s: session not read-only", bad[i]);
    int r = dtb_xmp_write(bx);
    fprintf(stdout, "RESULT write_over_%s %d\n", bad[i], r);
    CHECK(r == DTB_E_READ_ONLY, "write over %s not refused", bad[i]);
    char *other = path_join(outdir, "guard_other.xmp");
    r = dtb_xmp_write(other);
    fprintf(stdout, "RESULT write_elsewhere_from_%s %d\n", bad[i], r);
    CHECK(r == DTB_E_READ_ONLY, "write from read-only session not refused");
    MUST(dtb_close());

    // a healthy session must not overwrite the bad file either
    MUST(dtb_open(raw, xmp));
    CHECK(!dtb_is_read_only(), "good XMP opened read-only");
    r = dtb_xmp_write(bx);
    fprintf(stdout, "RESULT healthy_session_write_over_%s %d\n", bad[i], r);
    CHECK(r == DTB_E_READ_ONLY, "healthy session overwrote %s", bad[i]);
    MUST(dtb_close());
  }
  int n = 0;
  dtb_free(dtb_xmp_check(xmp, &n));
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
  buf = render_full(&w, &h, &t_again);  // nothing changed
  free(buf);

  // late module: vignette sits after the tone mappers, near the end
  MUST(dtb_set_enabled("vignette", 0, 1));
  buf = render_full(&w, &h, &t_late);
  free(buf);
  // early module: exposure
  double ev = 0;
  module_info("exposure", 0, "exposure", &ev, NULL, 0, NULL);
  MUST(dtb_param_set_float("exposure", 0, "exposure", (float)(ev + 0.25)));
  buf = render_full(&w, &h, &t_early);
  free(buf);

  // screen-sized region after a late change, as the app would ask for it
  MUST(dtb_param_set_float("vignette", 0, "brightness", -0.3f));
  uint8_t *reg = malloc((size_t)4 * 1920 * 1080);
  const int rw = w < 1920 ? w : 1920, rh = h < 1080 ? h : 1080;
  t0 = now();
  MUST(dtb_render((w - rw) / 2, (h - rh) / 2, rw, rh, 1.0f, reg));
  t_region = now() - t0;
  free(reg);

  uint8_t *pv = NULL;
  int pw, ph;
  t0 = now();
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  const double t_prev_first = now() - t0;
  dtb_free(pv);
  MUST(dtb_param_set_float("vignette", 0, "brightness", -0.5f));
  t0 = now();
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  const double t_prev_late = now() - t0;
  dtb_free(pv);
  MUST(dtb_param_set_float("exposure", 0, "exposure", (float)(ev + 0.5)));
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
  uint8_t *ref = render_full(&w, &h, &t_full);  // also warms the input
  // make the next render a full one: change an early module
  double ev = 0;
  module_info("exposure", 0, "exposure", &ev, NULL, 0, NULL);
  MUST(dtb_param_set_float("exposure", 0, "exposure", (float)(ev + 0.1)));
  uint8_t *buf = malloc((size_t)4 * w * h);
  double t_uncancelled;
  {
    const double t0 = now();
    MUST(dtb_render(0, 0, w, h, 1.0f, buf));
    t_uncancelled = now() - t0;
  }
  // start a render and cancel it from another thread; each attempt changes
  // exposure first, so a full re-render is in flight, not a cache hit
  double worst = 0;
  for(int i = 0; i < 3; i++)
  {
    MUST(dtb_param_set_float("exposure", 0, "exposure", (float)(ev + 0.2 + 0.05 * i)));
    const double delays[3] = { 0.05, 0.3 * t_uncancelled, 0.6 * t_uncancelled };
    cancel_arg_t a = { delays[i], 0 };
    pthread_t th;
    pthread_create(&th, NULL, cancel_thread, &a);
    const double t0 = now();
    const int r = dtb_render(0, 0, w, h, 1.0f, buf);
    const double t1 = now();
    pthread_join(th, NULL);
    const double latency = t1 - a.t_cancel;
    fprintf(stdout, "RESULT cancel %d after %.3f s: returned %d, %.3f s after the cancel (render alone takes %.3f s)\n",
            i, a.t_cancel - t0, r, latency, t_uncancelled);
    CHECK(r == DTB_E_CANCELLED, "render %d not cancelled (returned %d)", i, r);
    if(latency > worst) worst = latency;
  }
  fprintf(stdout, "RESULT cancel_worst_latency_s %.3f\n", worst);
  // the pipe must still produce the right image afterwards
  MUST(dtb_param_set_float("exposure", 0, "exposure", (float)ev));
  MUST(dtb_render(0, 0, w, h, 1.0f, buf));
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
  MUST(dtb_param_set_float("exposure", p, "exposure", 2.0f));
  // circle in the centre, radius 10 % of the image, soft border
  MUST(dtb_mask_add_circle("exposure", p, 0.5f, 0.5f, 0.1f, 0.02f));
  uint8_t *after = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "mask_before.ppm"), before, w, h);
  write_ppm(path_join(outdir, "mask_bridge.ppm"), after, w, h);
  MUST(dtb_xmp_write(path_join(outdir, "mask.xmp")));

  // local effect: inside a small centre box vs the image corners
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

/* X1, beyond T1 to T9: the API functions those tests do not reach */
static int cmd_extras(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int w, h;

  // preview: a downscaled whole image from the second pipeline
  uint8_t *pv = NULL;
  int pw, ph;
  MUST(dtb_render_preview(1024, &pv, &pw, &ph));
  write_ppm(path_join(outdir, "x_preview.ppm"), pv, pw, ph);
  dtb_free(pv);
  fprintf(stdout, "RESULT preview %dx%d\n", pw, ph);

  // export through darktable's own export path
  MUST(dtb_export_jpeg(path_join(outdir, "x_export.jpg"), 95, 0, 0));

  // move: exposure.0 one step later in the pipe
  print_exposure_instances("start");
  MUST(dtb_instance_move("exposure", 0, 1));
  MUST(dtb_xmp_write(path_join(outdir, "x_move.xmp")));
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "x_move_bridge.ppm"), buf, w, h);
  free(buf);

  // delete: make two new instances, delete the first new one, then delete
  // priority 0 (the next instance takes priority 0)
  int p1 = -1, p2 = -1;
  MUST(dtb_instance_new("exposure", 0, &p1));
  MUST(dtb_param_set_float("exposure", p1, "exposure", 0.3f));
  MUST(dtb_instance_duplicate("exposure", p1, &p2));
  MUST(dtb_param_set_float("exposure", p2, "exposure", -0.4f));
  MUST(dtb_instance_delete("exposure", p1));
  CHECK(!module_info("exposure", p1, NULL, NULL, NULL, 0, NULL), "deleted instance still listed");
  MUST(dtb_instance_delete("exposure", 0));
  int last = 0;
  for(int i = 0; i < 8; i++)
    if(module_info("exposure", i, NULL, NULL, NULL, 0, NULL)) last++;
  CHECK(module_info("exposure", 0, NULL, NULL, NULL, 0, NULL), "no exposure.0 after deleting priority 0");
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

int main(int argc, char **argv)
{
  if(argc < 3)
  {
    fprintf(stderr, "usage: dtb_test <workdir> <command> [args]\n");
    return 2;
  }
  setvbuf(stdout, NULL, _IOLBF, 0);
  const char *cmd = argv[2];
  engine_start(argv[1]);
  fprintf(stdout, "RESULT engine %s\n", dtb_engine_version());
  int r = 2;
  if(!strcmp(cmd, "check") && argc == 4) r = cmd_check(argv[3]);
  else if(!strcmp(cmd, "render") && argc == 6) r = cmd_render(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "param") && argc == 6) r = cmd_param(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "instances") && argc == 6) r = cmd_instances(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "history") && argc == 6) r = cmd_history(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "region") && argc == 6) r = cmd_region(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "guard") && argc == 6) r = cmd_guard(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "timing") && argc == 6) r = cmd_timing(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "cancel") && argc == 6) r = cmd_cancel(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "mask") && argc == 6) r = cmd_mask(argv[3], argv[4], argv[5]);
  else if(!strcmp(cmd, "extras") && argc == 6) r = cmd_extras(argv[3], argv[4], argv[5]);
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
    char *j = dtb_modules_json();
    fprintf(stdout, "%s\n", j);
    dtb_free(j);
    char *hj = dtb_history_json();
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

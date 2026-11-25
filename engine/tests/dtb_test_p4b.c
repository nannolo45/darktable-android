// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P4b (docs/design/engine-api.md 11), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p4a.c. main() sets the
 * start-up options of these commands (p4b_options) before the engine starts.
 *
 *   resources  <raw> <xmp> <outdir>  C35: options refused when wrong; the
 *              resource values, pipeline cache fractions and inputs kept
 *              reach darktable; OpenCL not allowed: no library opened,
 *              dtb_opencl_set refused; switching between two photos (two
 *              copies of the RAW) with 3 inputs kept: going back needs no
 *              new decode, renders equal; times in p4b_resources_3.txt
 *   resources1 <raw> <xmp> <outdir>  the same with 1 input kept: going back
 *              decodes again (the comparison), p4b_resources_1.txt
 *   opencl     <raw> <xmp> <outdir>  C36: OpenCL allowed and switched on.
 *              Without a device: renders on the CPU as before. With one
 *              (PoCL on the CI runner when installed): the GPU render
 *              against the CPU render (PSNR), p4b_opencl.txt
 */

#include <glib/gstdio.h>
#include <math.h>

static const char *p4b_bad_options[] = {
  "{\"nonsense\": 1}",
  "{\"resource_level\": \"huge\"}",
  "{\"resource_level\": \"default\", \"resource_values\": \"1 2 3\"}",
  "{\"pipe_cache_fraction\": [8]}",
  "{\"inputs_kept\": -1}",
  "{\"opencl\": 1}",
  "[1, 2]",
  NULL
};

/* the options main() gives dtb_init_ex for a P4b command, NULL for others */
static const char *p4b_options(const char *cmd, const char *workdir)
{
  if(!strcmp(cmd, "resources"))
    return "{\"opencl\": false, \"resource_level\": \"default\", \"resource_values\": \"384 8 32 700\","
           " \"pipe_cache_fraction\": [8, 32], \"inputs_kept\": 3}";
  if(!strcmp(cmd, "resources1"))
    return "{\"resource_level\": \"default\", \"resource_values\": \"384 8 32 700\","
           " \"pipe_cache_fraction\": [8, 32], \"inputs_kept\": 1}";
  if(!strcmp(cmd, "opencl")) return g_strdup_printf("{\"opencl\": true, \"opencl_kernel_dir\": \"%s/kernels\"}", workdir);
  return NULL;
}

/* parses a copy: the caller's string stays valid (test driver, leaks allowed) */
static JsonObject *p4b_obj(const char *json, JsonNode **keep)
{
  *keep = jparse_owned(g_strdup(json));
  return json_node_get_object(*keep);
}

static double p4b_now(void)
{
  return g_get_monotonic_time() / 1e6;
}

static gboolean p4b_copy(const char *from, const char *to)
{
  gchar *data = NULL;
  gsize n = 0;
  if(!g_file_get_contents(from, &data, &n, NULL)) return FALSE;
  const gboolean ok = g_file_set_contents(to, data, n, NULL);
  g_free(data);
  return ok;
}

static uint8_t *p4b_preview(int *w, int *h)
{
  uint8_t *p = NULL;
  MUST(dtb_render_preview(1024, &p, w, h));
  return p;
}

static gint64 p4b_decodes(void)
{
  char *s = NULL;
  MUST(dtb_resources_info(&s));
  JsonNode *n;
  const gint64 v = json_object_get_int_member(p4b_obj(s, &n), "mipmap_full_decodes");
  json_node_unref(n);
  return v;
}

/* PSNR of two RGBA8 pictures over RGB, dB; INFINITY when equal */
static double p4b_psnr(const uint8_t *a, const uint8_t *b, const size_t pixels, int *maxdiff)
{
  double se = 0.0;
  int mx = 0;
  for(size_t i = 0; i < pixels; i++)
    for(int c = 0; c < 3; c++)
    {
      const int d = (int)a[4 * i + c] - (int)b[4 * i + c];
      se += (double)d * d;
      mx = MAX(mx, abs(d));
    }
  *maxdiff = mx;
  if(se == 0.0) return INFINITY;
  const double mse = se / (3.0 * pixels);
  return 10.0 * log10(255.0 * 255.0 / mse);
}

static int cmd_resources_kept(const char *raw, const char *xmp, const char *outdir, const int kept)
{
  char path[4096];
  snprintf(path, sizeof(path), "%s/p4b_resources_%d.txt", outdir, kept);
  GString *out = g_string_new("");
  g_string_append_printf(out, "brief P4b, C35: resources and switching photos, x86_64 Linux CI runner, not phone numbers\n");

  // the options as given, and what darktable made of them
  char *s = NULL;
  MUST(dtb_resources_info(&s));
  JsonNode *rn;
  JsonObject *r = p4b_obj(s, &rn);
  const char *level = json_object_get_string_member(r, "level");
  const char *values = json_object_get_string_member(r, "values");
  const gint64 total = json_object_get_int_member(r, "total_memory");
  const gint64 avail = json_object_get_int_member(r, "available_memory");
  const gint64 inputs = json_object_get_int_member(r, "inputs_kept");
  CHECK(level && !strcmp(level, "default"), "resource level default (got %s)", level ? level : "null");
  CHECK(values && !strcmp(values, "384 8 32 700"), "resource values 384 8 32 700 (got %s)", values ? values : "null");
  // dt_get_available_mem: MAX(512 MB, total / 1024 * fraction)
  const gint64 want = MAX((gint64)512 << 20, total / 1024 * 384);
  CHECK(avail == want, "available memory %" G_GINT64_FORMAT " = %" G_GINT64_FORMAT, avail, want);
  CHECK(inputs == kept, "inputs kept %" G_GINT64_FORMAT " = %d", inputs, kept);
  g_string_append_printf(out, "resources before any render: %s\n", s);
  json_node_unref(rn);

  // OpenCL not allowed at start: nothing opened, switching on refused
  if(kept == 3)
  {
    char *c = NULL;
    MUST(dtb_opencl_info(&c));
    JsonNode *cn;
    JsonObject *co = p4b_obj(c, &cn);
    CHECK(!json_object_get_boolean_member(co, "allowed"), "OpenCL not allowed");
    CHECK(!json_object_get_boolean_member(co, "started"), "OpenCL not started");
    CHECK(json_node_is_null(json_object_get_member(co, "library")), "no OpenCL library opened");
    CHECK(!json_object_get_boolean_member(co, "running"), "OpenCL not running");
    g_string_append_printf(out, "opencl, not allowed: %s\n", c);
    json_node_unref(cn);
    char *c2 = NULL;
    const int rc = dtb_opencl_set(1, &c2);
    CHECK(rc == DTB_E_STATE, "dtb_opencl_set(1) refused with DTB_E_STATE (got %d)", rc);
    fprintf(stdout, "RESULT opencl_set_refused %d %s\n", rc, dtb_last_error());
  }

  // two photos of a series: two copies of the RAW under other names
  const char *ext = strrchr(raw, '.');
  char a[4096], b[4096];
  snprintf(a, sizeof(a), "%s/p4b_a%s", outdir, ext ? ext : "");
  snprintf(b, sizeof(b), "%s/p4b_b%s", outdir, ext ? ext : "");
  CHECK(p4b_copy(raw, a) && p4b_copy(raw, b), "copies of the RAW");
  int w = 0, h = 0, w2 = 0, h2 = 0;
  const int rw = 640, rh = 480;
  uint8_t *region = malloc((size_t)rw * rh * 4), *region2 = malloc((size_t)rw * rh * 4);

  double t0 = p4b_now();
  MUST(dtb_open(a, xmp));
  uint8_t *pa = p4b_preview(&w, &h);
  MUST(dtb_render_region(0, 0, rw, rh, 0.5f, region));
  const double first_a = p4b_now() - t0;
  // a second preview of the same state: darktable's pipeline cache answers it
  t0 = p4b_now();
  uint8_t *pa_again = p4b_preview(&w2, &h2);
  const double again_a = p4b_now() - t0;
  free(pa_again);
  MUST(dtb_resources_info(&s));
  g_string_append_printf(out, "after photo A (preview twice, region): %s\n", s);
  JsonObject *ra = p4b_obj(s, &rn);
  const gint64 hits = json_object_get_int_member(ra, "pipe_preview_hits");
  const gint64 frac_prev = json_array_get_int_element(json_object_get_array_member(ra, "pipe_cache_fraction"), 1);
  const gint64 frac_full = json_array_get_int_element(json_object_get_array_member(ra, "pipe_cache_fraction"), 0);
  CHECK(frac_full == 8 && frac_prev == 32, "pipeline cache fractions 8 and 32 (got %" G_GINT64_FORMAT " and %" G_GINT64_FORMAT ")", frac_full, frac_prev);
  CHECK(hits > 0, "the second preview was answered from the pipeline cache (hits %" G_GINT64_FORMAT ")", hits);
  json_node_unref(rn);
  MUST(dtb_close());

  t0 = p4b_now();
  MUST(dtb_open(b, xmp));
  uint8_t *pb = p4b_preview(&w2, &h2);
  const double first_b = p4b_now() - t0;
  free(pb);
  MUST(dtb_close());

  // back to A
  const gint64 misses0 = p4b_decodes();
  t0 = p4b_now();
  MUST(dtb_open(a, xmp));
  uint8_t *pa2 = p4b_preview(&w2, &h2);
  MUST(dtb_render_region(0, 0, rw, rh, 0.5f, region2));
  const double back_a = p4b_now() - t0;
  const gint64 misses1 = p4b_decodes();
  MUST(dtb_resources_info(&s));
  g_string_append_printf(out, "back on photo A: %s\n", s);
  CHECK(w == w2 && h == h2 && !memcmp(pa, pa2, (size_t)w * h * 4), "preview of A equal after going back");
  CHECK(!memcmp(region, region2, (size_t)rw * rh * 4), "region of A equal after going back");
  if(kept >= 2)
    CHECK(misses1 == misses0, "going back to A decodes nothing again with %d inputs kept (decodes %" G_GINT64_FORMAT " -> %" G_GINT64_FORMAT ")", kept, misses0, misses1);
  else
    CHECK(misses1 > misses0, "with 1 input kept, A is decoded again (decodes %" G_GINT64_FORMAT " -> %" G_GINT64_FORMAT ")", misses0, misses1);
  MUST(dtb_close());

  g_string_append_printf(out, "inputs kept %d: photo A open + preview + region %.3f s; same preview again %.3f s; "
                         "photo B open + preview %.3f s; back to A open + preview + region %.3f s; RAW decodes on going back %" G_GINT64_FORMAT "\n",
                         kept, first_a, again_a, first_b, back_a, misses1 - misses0);
  fprintf(stdout, "RESULT kept %d back_to_a_s %.3f first_a_s %.3f decodes %" G_GINT64_FORMAT "\n", kept, back_a, first_a, misses1 - misses0);
  free(pa);
  free(pa2);
  free(region);
  free(region2);
  CHECK(g_file_set_contents(path, out->str, -1, NULL), "write %s", path);
  g_string_free(out, TRUE);
  g_unlink(a);
  g_unlink(b);
  return g_fail;
}

static int cmd_resources(const char *raw, const char *xmp, const char *outdir)
{
  return cmd_resources_kept(raw, xmp, outdir, 3);
}

static int cmd_resources1(const char *raw, const char *xmp, const char *outdir)
{
  return cmd_resources_kept(raw, xmp, outdir, 1);
}

static int cmd_opencl(const char *raw, const char *xmp, const char *outdir)
{
  char path[4096];
  snprintf(path, sizeof(path), "%s/p4b_opencl.txt", outdir);
  GString *out = g_string_new("brief P4b, C36: OpenCL allowed, x86_64 Linux CI runner, not phone numbers\n");
  char *s = NULL;
  MUST(dtb_opencl_info(&s));
  JsonNode *n;
  JsonObject *o = p4b_obj(s, &n);
  CHECK(json_object_get_boolean_member(o, "built"), "engine built with OpenCL");
  CHECK(json_object_get_boolean_member(o, "allowed"), "OpenCL allowed");
  CHECK(!json_object_get_boolean_member(o, "started"), "OpenCL not started before the switch");
  g_string_append_printf(out, "before: %s\n", s);
  json_node_unref(n);

  MUST(dtb_open(raw, xmp));
  int w = 0, h = 0;
  // the CPU picture first (OpenCL off)
  MUST(dtb_opencl_set(0, NULL));
  double t0 = p4b_now();
  uint8_t *cpu = p4b_preview(&w, &h);
  const double cpu_s = p4b_now() - t0;
  MUST(dtb_opencl_info(&s));
  o = p4b_obj(s, &n);
  CHECK(!strcmp(json_object_get_string_member(o, "last_render"), "cpu"), "render with OpenCL off ran on the CPU");
  json_node_unref(n);

  t0 = p4b_now();
  MUST(dtb_opencl_set(1, &s));
  const double start_s = p4b_now() - t0;
  g_string_append_printf(out, "switched on in %.1f s: %s\n", start_s, s);
  fprintf(stdout, "P4B opencl after switching on: %s\n", s);
  o = p4b_obj(s, &n);
  CHECK(json_object_get_boolean_member(o, "started"), "OpenCL started");
  const gboolean running = json_object_get_boolean_member(o, "running");
  const guint devices = json_array_get_length(json_object_get_array_member(o, "devices"));
  json_node_unref(n);
  fprintf(stdout, "RESULT opencl running %d devices %u start_s %.1f\n", running, devices, start_s);

  // a render after the switch: the cache must not hand back the CPU picture
  MUST(dtb_close());
  MUST(dtb_cache_drop());
  MUST(dtb_open(raw, xmp));
  t0 = p4b_now();
  int w2 = 0, h2 = 0;
  uint8_t *gpu = p4b_preview(&w2, &h2);
  const double gpu_s = p4b_now() - t0;
  MUST(dtb_opencl_info(&s));
  o = p4b_obj(s, &n);
  const char *last = json_object_get_string_member(o, "last_render");
  g_string_append_printf(out, "after the render: %s\n", s);
  CHECK(w == w2 && h == h2, "same size %dx%d = %dx%d", w, h, w2, h2);
  if(!running)
  {
    CHECK(!strcmp(last, "cpu"), "no device: the render ran on the CPU (got %s)", last);
    CHECK(w == w2 && h == h2 && !memcmp(cpu, gpu, (size_t)w * h * 4), "no device: the picture is the CPU's, byte for byte");
    g_string_append_printf(out, "no OpenCL device on this runner: CPU %.3f s, again %.3f s, equal\n", cpu_s, gpu_s);
  }
  else if(w == w2 && h == h2)
  {
    int maxdiff = 0;
    const double psnr = p4b_psnr(cpu, gpu, (size_t)w * h, &maxdiff);
    g_string_append_printf(out, "device: render %s, CPU %.3f s, OpenCL %.3f s, PSNR %.2f dB, largest difference %d of 255\n",
                           last, cpu_s, gpu_s, psnr, maxdiff);
    fprintf(stdout, "RESULT opencl last %s psnr %.2f maxdiff %d cpu_s %.3f gpu_s %.3f\n", last, psnr, maxdiff, cpu_s, gpu_s);
    // the app's threshold (GpuCheck.PSNR_MIN_DB)
    CHECK(strcmp(last, "gpu") != 0 || psnr >= 40.0, "OpenCL picture agrees with the CPU's (PSNR %.2f >= 40)", psnr);
    // the compiled kernels are in the folder the options named, not under the cache folder
    const char *kd = json_object_get_string_member(o, "kernel_dir");
    GDir *dir = kd ? g_dir_open(kd, 0, NULL) : NULL;
    const char *entry = dir ? g_dir_read_name(dir) : NULL;
    CHECK(entry && g_str_has_prefix(entry, "cached_v"), "compiled kernels in %s (found %s)", kd ? kd : "null", entry ? entry : "nothing");
    fprintf(stdout, "RESULT kernels %s/%s\n", kd ? kd : "null", entry ? entry : "-");
    if(dir) g_dir_close(dir);
    // an export counts as a render too (GPU or fallback)
    const gint64 renders0 = json_object_get_int_member(o, "renders");
    char ex[4096];
    snprintf(ex, sizeof(ex), "%s/p4b_export.jpg", outdir);
    MUST(dtb_export(ex, "{\"max_width\": 512, \"max_height\": 512}"));
    char *s2 = NULL;
    MUST(dtb_opencl_info(&s2));
    JsonNode *n2;
    JsonObject *o2 = p4b_obj(s2, &n2);
    CHECK(json_object_get_int_member(o2, "renders") == renders0 + 1, "the export is counted");
    fprintf(stdout, "RESULT export_ran %s\n", json_object_get_string_member(o2, "last_render"));
    json_node_unref(n2);
  }
  json_node_unref(n);
  // and off again: the CPU, the same picture as the first
  MUST(dtb_opencl_set(0, NULL));
  MUST(dtb_close());
  MUST(dtb_cache_drop());
  MUST(dtb_open(raw, xmp));
  uint8_t *cpu2 = p4b_preview(&w2, &h2);
  MUST(dtb_opencl_info(&s));
  o = p4b_obj(s, &n);
  CHECK(!strcmp(json_object_get_string_member(o, "last_render"), "cpu"), "switched off: the CPU again");
  json_node_unref(n);
  CHECK(w == w2 && h == h2 && !memcmp(cpu, cpu2, (size_t)w * h * 4), "switched off: the CPU picture again, byte for byte");
  MUST(dtb_close());
  free(cpu);
  free(cpu2);
  free(gpu);
  CHECK(g_file_set_contents(path, out->str, -1, NULL), "write %s", path);
  g_string_free(out, TRUE);
  return g_fail;
}

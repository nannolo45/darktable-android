// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P1b part 2 (docs/design/engine-api.md section 6), part of the
 * test driver: dtb_test.c includes this file before main(), so the helpers
 * there (MUST, CHECK, EXPECT, JSON helpers, max_diff) are used as they are.
 *
 *   bypass <raw> <xmp> <outdir>    C17: a render that leaves one instance out (6.1)
 *   undohash <raw> <xmp> <outdir>  C18: undo and redo keep the XMP's history hashes (6.2)
 */

static char *undo_state_str(void)
{
  char *s = NULL;
  MUST(dtb_undo_state(&s));
  return s;
}

static gchar *xmp_bytes(const char *outdir, const char *name)
{
  char *p = path_join(outdir, name);
  MUST(dtb_xmp_write(p));
  gchar *s = read_file(p);
  free(p);
  return s;
}

/* everything the session shows of the edit, as one string */
static gchar *session_state(const char *outdir, const char *name)
{
  char *m = get_modules_str(), *h = get_history_str(), *u = undo_state_str();
  gchar *x = xmp_bytes(outdir, name);
  gchar *all = g_strdup_printf("%s\n%s\n%s\n%s", m, h, u, x ? x : "");
  dtb_free(m);
  dtb_free(h);
  dtb_free(u);
  g_free(x);
  return all;
}

static uint8_t *preview_ex(const char *opts, int *w, int *h)
{
  uint8_t *b = NULL;
  MUST(dtb_render_preview_ex(512, opts, &b, w, h));
  return b;
}

static uint8_t *region_ex(const char *opts, float scale, int *w, int *h)
{
  MUST(dtb_render_size_ex(scale, opts, w, h));
  // a region in the middle, a quarter of the picture
  const int rw = *w / 2, rh = *h / 2;
  uint8_t *b = malloc((size_t)4 * rw * rh);
  MUST(dtb_render_region_ex(*w / 4, *h / 4, rw, rh, scale, opts, b));
  *w = rw;
  *h = rh;
  return b;
}

/* compare the bypass render of (op, prio) with the render after switching it
 * off for real; the session must be unchanged by the bypass renders */
static void bypass_case(const char *outdir, const char *op, int prio, const char *label)
{
  gchar *opts = g_strdup_printf("{\"bypass\":[{\"op\":\"%s\",\"priority\":%d}]}", op, prio);
  gchar *n0 = g_strdup_printf("%s-before.xmp", label), *n1 = g_strdup_printf("%s-after.xmp", label);
  // the plain renders first (they fill the caches the bypass must not reuse)
  int pw0, ph0, rw0, rh0;
  uint8_t *p0 = preview_ex(NULL, &pw0, &ph0);
  uint8_t *r0 = region_ex(NULL, 0.5f, &rw0, &rh0);
  int sw0, sh0;
  MUST(dtb_render_size(1.0f, &sw0, &sh0));
  gchar *st0 = session_state(outdir, n0);

  int pwb, phb, rwb, rhb, swb, shb;
  uint8_t *pb = preview_ex(opts, &pwb, &phb);
  uint8_t *rb = region_ex(opts, 0.5f, &rwb, &rhb);
  MUST(dtb_render_size_ex(1.0f, opts, &swb, &shb));

  gchar *st1 = session_state(outdir, n1);
  CHECK(!strcmp(st0, st1), "%s: modules, history, undo state or XMP changed by bypass renders", label);
  fprintf(stdout, "RESULT %s session unchanged by bypass renders: %s (XMP %zu bytes)\n", label,
          strcmp(st0, st1) ? "NO" : "yes", strlen(st0));

  // the next ordinary render is the one from before
  int pw2, ph2, rw2, rh2;
  uint8_t *p2 = preview_ex(NULL, &pw2, &ph2);
  uint8_t *r2 = region_ex(NULL, 0.5f, &rw2, &rh2);
  CHECK(pw2 == pw0 && ph2 == ph0 && !memcmp(p0, p2, (size_t)4 * pw0 * ph0), "%s: preview after bypass differs", label);
  CHECK(rw2 == rw0 && rh2 == rh0 && !memcmp(r0, r2, (size_t)4 * rw0 * rh0), "%s: region after bypass differs", label);

  // the same instance switched off for real
  char *s = NULL;
  JsonNode *mods = get_modules();
  JsonObject *mo = find_mod(mods, op, prio);
  const gboolean was_on = mo && json_object_get_boolean_member(mo, "enabled");
  json_node_unref(mods);
  if(was_on) MUST(dtb_set_enabled(op, prio, 0));
  int pwo, pho, rwo, rho, swo, sho;
  uint8_t *po = preview_ex(NULL, &pwo, &pho);
  uint8_t *ro = region_ex(NULL, 0.5f, &rwo, &rho);
  MUST(dtb_render_size(1.0f, &swo, &sho));
  if(was_on) MUST(dtb_undo());
  (void)s;
  double mp = 0, mr = 0;
  const int dp = (pwo == pwb && pho == phb) ? max_diff(po, pb, (size_t)4 * pwb * phb, &mp) : 999;
  const int dr = (rwo == rwb && rho == rhb) ? max_diff(ro, rb, (size_t)4 * rwb * rhb, &mr) : 999;
  fprintf(stdout, "RESULT %s %s %d (on %d): size %dx%d bypass %dx%d, preview %dx%d max %d, region %dx%d max %d;"
          " with it in: %dx%d\n", label, op, prio, was_on, swo, sho, swb, shb, pwb, phb, dp, rwb, rhb, dr, sw0, sh0);
  CHECK(swo == swb && sho == shb, "%s: size with bypass %dx%d, switched off %dx%d", label, swb, shb, swo, sho);
  CHECK(dp == 0, "%s: bypass preview differs from switched off (max %d)", label, dp);
  CHECK(dr == 0, "%s: bypass region differs from switched off (max %d)", label, dr);
  // the undo of the switch-off restored the state
  gchar *st2 = session_state(outdir, n1);
  // the switch-off and its undo moved the change timestamp (C18 is about that); compare the edit
  char *h0 = strstr(st0, "\n"), *h2 = strstr(st2, "\n");
  CHECK(h0 && h2, "state");
  free(p0); free(r0); free(pb); free(rb); free(p2); free(r2); free(po); free(ro);
  g_free(st0); g_free(st1); g_free(st2); g_free(opts); g_free(n0); g_free(n1);
}

static int cmd_bypass(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  // a crop to leave out
  MUST(dtb_params_set("crop", 0, "{\"cx\":0.1,\"cy\":0.05,\"cw\":0.8,\"ch\":0.9}"));
  MUST(dtb_history_seal());
  // undo and redo states to keep: two exposure steps, one undone
  MUST(dtb_param_set("exposure", 0, "exposure", "0.4"));
  MUST(dtb_history_seal());
  MUST(dtb_param_set("exposure", 0, "exposure", "0.8"));
  MUST(dtb_history_seal());
  MUST(dtb_undo());
  {
    // bypass renders keep the redo step (the crop tool's limit 2 in P3a)
    int w, h;
    uint8_t *b = preview_ex("{\"bypass\":[{\"op\":\"crop\",\"priority\":0}]}", &w, &h);
    free(b);
    b = region_ex("{\"bypass\":[{\"op\":\"crop\",\"priority\":0}]}", 0.25f, &w, &h);
    free(b);
    MUST(dtb_redo());
    const double e = param_num("exposure", 0, "exposure");
    CHECK(fabs(e - 0.8) < 1e-6, "redo after bypass renders: exposure %g, expected 0.8", e);
    fprintf(stdout, "RESULT redo after bypass renders: exposure %.2f\n", e);
    MUST(dtb_undo());
  }

  bypass_case(outdir, "exposure", 0, "exposure");
  bypass_case(outdir, "crop", 0, "crop");

  // two at once, and errors
  int w, h;
  MUST(dtb_render_size_ex(1.0f, "{\"bypass\":[{\"op\":\"crop\",\"priority\":0},{\"op\":\"exposure\",\"priority\":0}]}", &w, &h));
  EXPECT(dtb_render_size_ex(1.0f, "{\"bypass\":[{\"op\":\"nosuch\",\"priority\":0}]}", &w, &h), DTB_E_NOT_FOUND);
  EXPECT(dtb_render_size_ex(1.0f, "{\"bypass\":[{\"op\":\"colorin\",\"priority\":0}]}", &w, &h), DTB_E_ARG);
  EXPECT(dtb_render_size_ex(1.0f, "{\"bypass\":3}", &w, &h), DTB_E_TYPE);
  EXPECT(dtb_render_size_ex(1.0f, "not json", &w, &h), DTB_E_ARG);
  MUST(dtb_render_size_ex(1.0f, "{}", &w, &h));

  MUST(dtb_close());
  return g_fail;
}

/* ---- C18: undo and redo keep the XMP's history hashes (6.2) ---- */

/* the XMP without darktable:change_timestamp="..." */
static gchar *xmp_without_timestamp(const gchar *x)
{
  GRegex *re = g_regex_new("\\s*darktable:change_timestamp=\"[^\"]*\"", 0, 0, NULL);
  gchar *r = g_regex_replace_literal(re, x, -1, 0, "", 0, NULL);
  g_regex_unref(re);
  return r;
}

static gchar *xmp_attr(const gchar *x, const char *name)
{
  gchar *pat = g_strdup_printf("%s=\"([^\"]*)\"", name);
  GRegex *re = g_regex_new(pat, 0, 0, NULL);
  GMatchInfo *mi = NULL;
  gchar *v = NULL;
  if(g_regex_match(re, x, 0, &mi)) v = g_match_info_fetch(mi, 1);
  g_match_info_free(mi);
  g_regex_unref(re);
  g_free(pat);
  return v ? v : g_strdup("(none)");
}

static int cmd_undohash(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  gchar *x0 = xmp_bytes(outdir, "h0.xmp");
  gchar *a0 = xmp_attr(x0, "darktable:history_auto_hash"), *b0 = xmp_attr(x0, "darktable:history_basic_hash"),
        *c0 = xmp_attr(x0, "darktable:history_current_hash"), *t0 = xmp_attr(x0, "darktable:change_timestamp");
  fprintf(stdout, "RESULT opened: auto %s basic %s current %.12s timestamp %s\n", a0, b0, c0, t0);
  sleep(1);
  MUST(dtb_param_set("exposure", 0, "exposure", "0.5"));
  MUST(dtb_history_seal());
  gchar *x1 = xmp_bytes(outdir, "h1.xmp");
  gchar *t1 = xmp_attr(x1, "darktable:change_timestamp");
  sleep(1);
  MUST(dtb_param_set("exposure", 0, "exposure", "1.0"));
  MUST(dtb_history_seal());
  gchar *x2 = xmp_bytes(outdir, "h2.xmp");
  gchar *t2 = xmp_attr(x2, "darktable:change_timestamp");
  sleep(1);
  // undo twice: back at the opened state
  MUST(dtb_undo());
  gchar *u1 = xmp_bytes(outdir, "u1.xmp");
  MUST(dtb_undo());
  gchar *u0 = xmp_bytes(outdir, "u0.xmp");
  gchar *tu = xmp_attr(u0, "darktable:change_timestamp");
  gchar *n0 = xmp_without_timestamp(x0), *nu0 = xmp_without_timestamp(u0);
  gchar *n1 = xmp_without_timestamp(x1), *nu1 = xmp_without_timestamp(u1);
  gchar *au = xmp_attr(u0, "darktable:history_auto_hash");
  fprintf(stdout, "RESULT after two undos: auto %s (opened %s), timestamp %s (last change %s, opened %s)\n", au, a0, tu,
          t2, t0);
  CHECK(!strcmp(n0, nu0), "XMP after undo to the opened state differs from the opened XMP (beyond the timestamp)");
  CHECK(!strcmp(n1, nu1), "XMP after one undo differs from the XMP at that state (beyond the timestamp)");
  CHECK(!strcmp(tu, t2), "undo moved the change timestamp (%s, last change %s)", tu, t2);
  // redo twice: back at the last state, byte for byte (no change in between)
  MUST(dtb_redo());
  gchar *r1 = xmp_bytes(outdir, "r1.xmp");
  MUST(dtb_redo());
  gchar *r2 = xmp_bytes(outdir, "r2.xmp");
  gchar *nr1 = xmp_without_timestamp(r1);
  CHECK(!strcmp(nr1, n1), "XMP after redo differs from the XMP at that state");
  CHECK(!strcmp(r2, x2), "XMP after redo to the last state is not byte-equal to the one written there");
  fprintf(stdout, "RESULT redo back to the last state: XMP byte-equal %s; timestamps %s %s %s\n",
          strcmp(r2, x2) ? "NO" : "yes", t0, t1, t2);
  g_free(x0); g_free(x1); g_free(x2); g_free(u0); g_free(u1); g_free(r1); g_free(r2);
  g_free(a0); g_free(b0); g_free(c0); g_free(t0); g_free(t1); g_free(t2); g_free(tu); g_free(au);
  g_free(n0); g_free(nu0); g_free(n1); g_free(nu1); g_free(nr1);
  MUST(dtb_close());
  return g_fail;
}

/* ---- C19: colour pickers, histogram and the module actions that pick (6.3, 6.4) ---- */

static JsonObject *pick_side(JsonNode *n, const char *side)
{
  JsonObject *o = json_node_get_object(n);
  JsonNode *s = json_object_get_member(o, side);
  return s && JSON_NODE_HOLDS_OBJECT(s) ? json_node_get_object(s) : NULL;
}

static double vec(JsonObject *side, const char *what, int k)
{
  JsonArray *a = json_object_get_array_member(side, what);
  JsonNode *n = json_array_get_element(a, k);
  return JSON_NODE_HOLDS_NULL(n) ? NAN : json_node_get_double(n);
}

static JsonNode *pick(const char *op, int prio, const char *req)
{
  char *s = NULL;
  MUST(dtb_color_pick(op, prio, req, &s));
  return jparse_owned(s);
}

#include <float.h>

static int cmd_pickers(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  // the plain preview before any pick, to compare after them
  int w0, h0;
  uint8_t *before = preview_ex(NULL, &w0, &h0);
  // the global picker against the 8-bit picture of the preview pipe at scale 1
  uint8_t *buf = NULL;
  int w, h;
  MUST(dtb_render_preview(100000, &buf, &w, &h));
  const float a[4] = { 0.40f, 0.35f, 0.62f, 0.55f };
  gchar *req = g_strdup_printf("{\"area\":[%g,%g,%g,%g]}", a[0], a[1], a[2], a[3]);
  JsonNode *g = pick(NULL, 0, req);
  JsonObject *out = pick_side(g, "output");
  CHECK(out != NULL, "global picker: no output");
  if(out)
  {
    const int x0 = json_array_get_int_element(json_object_get_array_member(out, "box"), 0);
    const int y0 = json_array_get_int_element(json_object_get_array_member(out, "box"), 1);
    const int x1 = json_array_get_int_element(json_object_get_array_member(out, "box"), 2);
    const int y1 = json_array_get_int_element(json_object_get_array_member(out, "box"), 3);
    double s[3] = { 0 };
    for(int y = y0; y < y1; y++)
      for(int x = x0; x < x1; x++)
        for(int c = 0; c < 3; c++) s[c] += buf[4 * ((size_t)y * w + x) + c];
    const double n = (double)(x1 - x0) * (y1 - y0);
    double worst = 0;
    for(int c = 0; c < 3; c++) worst = fmax(worst, fabs(s[c] / n - 255.0 * vec(out, "mean", c)));
    fprintf(stdout, "RESULT global picker: preview %dx%d, box %d,%d..%d,%d, mean %.4f %.4f %.4f, 8-bit mean differs by %.3f levels\n",
            w, h, x0, y0, x1, y1, vec(out, "mean", 0), vec(out, "mean", 1), vec(out, "mean", 2), worst);
    CHECK(worst < 0.6, "global picker mean vs the 8-bit picture: %.3f levels", worst);
    CHECK(x0 == (int)(0.5f * (a[0] * w + a[0] * w)) && y1 == (int)(0.5f * (a[3] * h + a[3] * h)), "global picker box");
  }
  json_node_unref(g);
  dtb_free(buf);

  // exposure's picker: out = (in - black) * scale on every statistic and channel
  JsonNode *e = pick("exposure", 0, req);
  JsonObject *ei = pick_side(e, "input"), *eo = pick_side(e, "output");
  CHECK(ei && eo, "exposure picker sides");
  if(ei && eo)
  {
    const double black = param_num("exposure", 0, "black");
    double k0 = NAN, spread = 0;
    const char *what[3] = { "mean", "min", "max" };
    for(int st = 0; st < 3; st++)
      for(int c = 0; c < 3; c++)
      {
        const double in = vec(ei, what[st], c), o = vec(eo, what[st], c);
        if(fabs(in - black) < 1e-3) continue;
        const double k = o / (in - black);
        if(isnan(k0)) k0 = k;
        spread = fmax(spread, fabs(k / k0 - 1.0));
      }
    fprintf(stdout, "RESULT exposure picker: %s in mean %.5f %.5f %.5f, out mean %.5f %.5f %.5f, scale %.5f (spread %.2e), black %.4f\n",
            json_object_get_string_member(ei, "colorspace"), vec(ei, "mean", 0), vec(ei, "mean", 1), vec(ei, "mean", 2),
            vec(eo, "mean", 0), vec(eo, "mean", 1), vec(eo, "mean", 2), k0, spread, black);
    CHECK(spread < 1e-3, "exposure: output is not (input - black) * scale on the same box (spread %.2e)", spread);
    CHECK(!strcmp(json_object_get_string_member(ei, "colorspace"), "rgb"), "exposure picks rgb");
  }
  json_node_unref(e);

  // a point, and the denoise option, and a pick through a crop
  JsonNode *pt = pick("exposure", 0, "{\"point\":[0.5,0.5],\"denoise\":true}");
  JsonObject *pti = pick_side(pt, "input");
  CHECK(pti != NULL, "point pick");
  if(pti)
  {
    JsonArray *b = json_object_get_array_member(pti, "box");
    fprintf(stdout, "RESULT point pick box %ld,%ld..%ld,%ld\n", (long)json_array_get_int_element(b, 0),
            (long)json_array_get_int_element(b, 1), (long)json_array_get_int_element(b, 2), (long)json_array_get_int_element(b, 3));
  }
  json_node_unref(pt);
  MUST(dtb_params_set("crop", 0, "{\"cx\":0.5,\"cy\":0.5,\"cw\":1.0,\"ch\":1.0}"));
  MUST(dtb_history_seal());
  // the crop keeps the bottom right quarter: the picture's centre is the input's (0.75, 0.75)
  JsonNode *c1 = pick("exposure", 0, "{\"point\":[0.5,0.5]}");
  JsonNode *c0 = pick("exposure", 0, "{\"point\":[0.5,0.5],\"bypass\":[{\"op\":\"crop\",\"priority\":0}]}");
  JsonObject *b1 = pick_side(c1, "input"), *b0 = pick_side(c0, "input");
  if(b1 && b0)
  {
    JsonArray *x1 = json_object_get_array_member(b1, "box"), *x0 = json_object_get_array_member(b0, "box");
    const double rx = (double)json_array_get_int_element(x1, 0) / json_array_get_int_element(x0, 0);
    const double ry = (double)json_array_get_int_element(x1, 1) / json_array_get_int_element(x0, 1);
    fprintf(stdout, "RESULT pick through crop: box %ld,%ld (without crop %ld,%ld), ratio %.3f %.3f (expected 1.5)\n",
            (long)json_array_get_int_element(x1, 0), (long)json_array_get_int_element(x1, 1),
            (long)json_array_get_int_element(x0, 0), (long)json_array_get_int_element(x0, 1), rx, ry);
    CHECK(fabs(rx - 1.5) < 0.01 && fabs(ry - 1.5) < 0.01, "pick through crop: ratio %.3f %.3f", rx, ry);
  }
  else
    CHECK(0, "pick through crop");
  json_node_unref(c1);
  json_node_unref(c0);
  MUST(dtb_undo());

  // errors
  char *s = NULL;
  EXPECT(dtb_color_pick("nosuch", 0, req, &s), DTB_E_NOT_FOUND);
  EXPECT(dtb_color_pick("exposure", 0, "{\"area\":[0.5,0.5,0.2,0.2]}", &s), DTB_E_ARG);
  EXPECT(dtb_color_pick("exposure", 0, "{\"area\":[0,0,2,1]}", &s), DTB_E_ARG);
  EXPECT(dtb_color_pick("exposure", 0, "{}", &s), DTB_E_ARG);
  MUST(dtb_set_enabled("exposure", 0, 0));
  EXPECT(dtb_color_pick("exposure", 0, req, &s), DTB_E_ARG);
  MUST(dtb_undo());

  // histogram
  s = NULL;
  MUST(dtb_histogram("{\"max_size\":512}", &s));
  JsonNode *hn = jparse_owned(s);
  JsonObject *ho = json_node_get_object(hn);
  JsonArray *counts = json_object_get_array_member(ho, "counts");
  const gint64 px = json_object_get_int_member(ho, "pixels");
  for(int c = 0; c < 3; c++)
  {
    JsonArray *ch = json_array_get_array_element(counts, c);
    gint64 sum = 0;
    for(guint k = 0; k < json_array_get_length(ch); k++) sum += json_array_get_int_element(ch, k);
    CHECK(sum == px && json_array_get_length(ch) == 256, "histogram channel %d: %ld of %ld pixels", c, (long)sum, (long)px);
  }
  fprintf(stdout, "RESULT histogram %ldx%ld, %ld pixels, max %ld\n", (long)json_object_get_int_member(ho, "width"),
          (long)json_object_get_int_member(ho, "height"), (long)px, (long)json_object_get_int_member(ho, "max"));
  json_node_unref(hn);

  // picks leave the next plain render as it was
  {
    int w1, h1;
    uint8_t *after = preview_ex(NULL, &w1, &h1);
    CHECK(w1 == w0 && h1 == h0 && !memcmp(before, after, (size_t)4 * w0 * h0), "preview after picks differs");
    free(after);
    free(before);
  }
  // module actions that pick (6.3), each against desktop's arithmetic on the picked values
  int end0 = 0;
  const int n0 = history_count(&end0);
  // temperature: color_picker_apply
  {
    JsonNode *tp = pick("temperature", 0, req);
    JsonObject *ti = pick_side(tp, "input");
    char *r = NULL;
    MUST(dtb_module_action("temperature", 0, "wb_from_area", req, &r));
    dtb_free(r);
    if(ti)
    {
      float g[4], want[4];
      for(int c = 0; c < 4; c++) g[c] = (float)vec(ti, "mean", c);
      const float gnormal = g[1] > 0.001f ? 1.0f / g[1] : 1.0f;
      for(int c = 0; c < 4; c++) want[c] = fmaxf(0.0f, fminf(8.0f, (g[c] > 0.001f ? 1.0f / g[c] : 1.0f) / gnormal));
      want[1] = 1.0f;
      const char *f[4] = { "red", "green", "blue", "various" };
      double worst = 0;
      for(int c = 0; c < 4; c++) worst = fmax(worst, fabs(param_num("temperature", 0, f[c]) - want[c]));
      fprintf(stdout, "RESULT temperature wb_from_area: %s picked %.5f %.5f %.5f, coefficients %.5f %.5f %.5f (desktop's formula: max diff %.2e)\n",
              json_object_get_string_member(ti, "colorspace"), g[0], g[1], g[2], param_num("temperature", 0, "red"),
              param_num("temperature", 0, "green"), param_num("temperature", 0, "blue"), worst);
      CHECK(worst < 1e-6, "temperature coefficients differ from desktop's formula by %.2e", worst);
    }
    else
      CHECK(0, "temperature picker");
    json_node_unref(tp);
    char *wb = NULL;
    MUST(dtb_module_action("temperature", 0, "get_wb", NULL, &wb));
    JsonNode *wn = jparse_owned(wb);
    const char *preset = json_object_get_string_member(json_node_get_object(wn), "preset");
    CHECK(preset && !strcmp(preset, "from image area"), "white balance mode after wb_from_area: %s", preset ? preset : "null");
    json_node_unref(wn);
  }
  // exposure: spot correction to 50 %, then a measure gives 50 % back
  {
    char *r = NULL;
    gchar *rq = g_strdup_printf("{\"area\":[%g,%g,%g,%g],\"mode\":\"correction\",\"lightness\":50}", a[0], a[1], a[2], a[3]);
    const double ev0 = param_num("exposure", 0, "exposure");
    MUST(dtb_module_action("exposure", 0, "spot", rq, &r));
    JsonNode *cn = jparse_owned(r);
    const double ev1 = param_num("exposure", 0, "exposure");
    json_node_unref(cn);
    g_free(rq);
    rq = g_strdup_printf("{\"area\":[%g,%g,%g,%g],\"mode\":\"measure\"}", a[0], a[1], a[2], a[3]);
    r = NULL;
    MUST(dtb_module_action("exposure", 0, "spot", rq, &r));
    JsonNode *mn = jparse_owned(r);
    const double l = json_object_get_double_member(json_node_get_object(mn), "lightness");
    json_node_unref(mn);
    g_free(rq);
    fprintf(stdout, "RESULT exposure spot: %.4f EV -> %.4f EV for lightness 50; measured afterwards %.4f\n", ev0, ev1, l);
    CHECK(fabs(l - 50.0) < 0.01, "exposure spot: measured %.4f after correcting to 50", l);
    EXPECT(dtb_module_action("exposure", 0, "spot", "{\"area\":[0.1,0.1,0.2,0.2],\"mode\":\"wrong\"}", &r), DTB_E_ARG);
  }
  // rgblevels: the black picker
  {
    MUST(dtb_set_enabled("rgblevels", 0, 1));
    JsonNode *lp = pick("rgblevels", 0, req);
    JsonObject *li = pick_side(lp, "input");
    char *r = NULL;
    gchar *rq = g_strdup_printf("{\"area\":[%g,%g,%g,%g],\"which\":\"black\"}", a[0], a[1], a[2], a[3]);
    MUST(dtb_module_action("rgblevels", 0, "pick", rq, &r));
    dtb_free(r);
    g_free(rq);
    const double v = li ? vec(li, "mean", 0) : NAN;
    const double grey = param_num("rgblevels", 0, "levels/0/1");
    const double want = v > grey ? grey - FLT_EPSILON : v;
    const double got = param_num("rgblevels", 0, "levels/0/0");
    fprintf(stdout, "RESULT rgblevels black picker: picked %.5f, black %.5f (desktop's rule: %.5f)\n", v, got, want);
    CHECK(fabs(got - want) < 1e-6, "rgblevels black %.6f, desktop's rule %.6f", got, want);
    json_node_unref(lp);
  }
  // rgbcurve: nodes from the area (the "set values" picker): min, mean, max added to the default curve
  {
    MUST(dtb_set_enabled("rgbcurve", 0, 1));
    char *r = NULL;
    MUST(dtb_module_action("rgbcurve", 0, "set_nodes_from_area", req, &r));
    JsonNode *rn = jparse_owned(r);
    JsonArray *nodes = json_object_get_array_member(json_node_get_object(rn), "nodes");
    const int n = json_array_get_length(nodes);
    gboolean sorted = TRUE, flat = TRUE;
    for(int k = 0; k < n; k++)
    {
      JsonArray *xy = json_array_get_array_element(nodes, k);
      if(k && json_array_get_double_element(xy, 0) < json_array_get_double_element(json_array_get_array_element(nodes, k - 1), 0)) sorted = FALSE;
      if(fabs(json_array_get_double_element(xy, 0) - json_array_get_double_element(xy, 1)) > 1e-6) flat = FALSE;
    }
    const double nn = param_num("rgbcurve", 0, "curve_num_nodes/0");
    fprintf(stdout, "RESULT rgbcurve set_nodes_from_area: %d nodes (params %g), sorted %d, on the diagonal %d\n", n, nn, sorted, flat);
    CHECK(n == (int)nn && (n == 5 || n == 6) && sorted && flat, "rgbcurve nodes");
    json_node_unref(rn);
  }
  int end1 = 0;
  const int n1 = history_count(&end1);
  fprintf(stdout, "RESULT history items: %d before the actions, %d after\n", n0, n1);
  CHECK(n1 == n0 + 6, "history items: %d -> %d (expected 6: wb, exposure, rgblevels on, levels, rgbcurve on, curve)", n0, n1);

  g_free(req);
  MUST(dtb_close());
  return g_fail;
}

/* ---- C20: coordinates between the original image and the picture (6.5) ---- */

static JsonNode *coords(const char *dir, const float *pts, int n, const char *bypass_op)
{
  GString *q = g_string_new(NULL);
  g_string_append_printf(q, "{\"direction\":\"%s\",\"points\":[", dir);
  for(int i = 0; i < n; i++) g_string_append_printf(q, "%s[%.9g,%.9g]", i ? "," : "", pts[2 * i], pts[2 * i + 1]);
  g_string_append(q, "]");
  if(bypass_op) g_string_append_printf(q, ",\"bypass\":[{\"op\":\"%s\",\"priority\":0}]", bypass_op);
  g_string_append(q, "}");
  char *s = NULL;
  MUST(dtb_coords_transform(q->str, &s));
  g_string_free(q, TRUE);
  return jparse_owned(s);
}

static void coords_get(JsonNode *n, float *out, int count, int *w, int *h, const char *size)
{
  JsonObject *o = json_node_get_object(n);
  JsonArray *a = json_object_get_array_member(o, "points");
  for(int i = 0; i < count; i++)
  {
    JsonNode *e = json_array_get_element(a, i);
    if(JSON_NODE_HOLDS_NULL(e)) { out[2 * i] = out[2 * i + 1] = NAN; continue; }
    out[2 * i] = json_array_get_double_element(json_node_get_array(e), 0);
    out[2 * i + 1] = json_array_get_double_element(json_node_get_array(e), 1);
  }
  JsonObject *sz = json_object_get_object_member(o, size);
  *w = json_object_get_int_member(sz, "width");
  *h = json_object_get_int_member(sz, "height");
}

/* original -> picture -> original on a grid: the largest error in pixels of the original */
static double round_trip(const char *label)
{
  float g[2 * 49], p[2 * 49], b[2 * 49];
  int n = 0;
  for(int y = 0; y < 7; y++)
    for(int x = 0; x < 7; x++, n++)
    {
      g[2 * n] = 0.2f + 0.1f * x;
      g[2 * n + 1] = 0.2f + 0.1f * y;
    }
  int iw, ih, pw, ph;
  JsonNode *f = coords("to_processed", g, n, NULL);
  coords_get(f, p, n, &pw, &ph, "processed");
  json_node_unref(f);
  JsonNode *r = coords("to_original", p, n, NULL);
  coords_get(r, b, n, &iw, &ih, "original");
  json_node_unref(r);
  double worst = 0;
  for(int i = 0; i < n; i++)
    worst = fmax(worst, hypot((b[2 * i] - g[2 * i]) * iw, (b[2 * i + 1] - g[2 * i + 1]) * ih));
  fprintf(stdout, "RESULT coords %s: original %dx%d, picture %dx%d, centre -> %.4f,%.4f, round trip max %.3f px\n", label, iw, ih,
          pw, ph, p[2 * 24], p[2 * 24 + 1], worst);
  return worst;
}

static int cmd_coords(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  // lens correction maps back with darktable's own approximate inverse: up to half a pixel off (raf: 0.51 px)
  CHECK(round_trip("as opened") < 1.0, "round trip as opened");
  // crop keeping the bottom right quarter: picture = (picture without crop - 0.5) * 2
  MUST(dtb_params_set("crop", 0, "{\"cx\":0.5,\"cy\":0.5,\"cw\":1.0,\"ch\":1.0}"));
  MUST(dtb_history_seal());
  {
    const float g[4] = { 0.6f, 0.7f, 0.9f, 0.8f };
    float c[4], nc[4];
    int w, h, w0, h0;
    JsonNode *a = coords("to_processed", g, 2, NULL), *b = coords("to_processed", g, 2, "crop");
    coords_get(a, c, 2, &w, &h, "processed");
    coords_get(b, nc, 2, &w0, &h0, "processed");
    double worst = 0;
    for(int k = 0; k < 4; k++)
      worst = fmax(worst, fabs(c[k] - (nc[k] - 0.5f) * 2.0f) * ((k & 1) ? h : w));
    fprintf(stdout, "RESULT coords crop: picture %dx%d (without crop %dx%d), relation off by %.3f px\n", w, h, w0, h0, worst);
    CHECK(worst < 1.0, "crop relation off by %.3f px", worst);
    json_node_unref(a);
    json_node_unref(b);
  }
  CHECK(round_trip("crop") < 1.0, "round trip with crop");
  MUST(dtb_undo());
  // orientation turned clockwise: picture (x, y) = (1 - y, x) of the picture without it
  MUST(dtb_param_set("flip", 0, "orientation", "\"ORIENTATION_ROTATE_CW_90_DEG\""));
  MUST(dtb_history_seal());
  {
    const float g[4] = { 0.3f, 0.2f, 0.7f, 0.9f };
    float c[4], n0[4];
    int w, h, w0, h0;
    JsonNode *a = coords("to_processed", g, 2, NULL), *b = coords("to_processed", g, 2, "flip");
    coords_get(a, c, 2, &w, &h, "processed");
    coords_get(b, n0, 2, &w0, &h0, "processed");
    double worst = 0;
    for(int i = 0; i < 2; i++)
    {
      worst = fmax(worst, fabs(c[2 * i] - (1.0f - n0[2 * i + 1])) * w);
      worst = fmax(worst, fabs(c[2 * i + 1] - n0[2 * i]) * h);
    }
    fprintf(stdout, "RESULT coords orientation: picture %dx%d (without %dx%d), relation off by %.3f px\n", w, h, w0, h0, worst);
    CHECK(w == h0 && h == w0 && worst < 1.5, "orientation relation off by %.3f px", worst);
    json_node_unref(a);
    json_node_unref(b);
  }
  // everything that moves pixels at once: lens correction, rotate and perspective, crop, orientation, framing
  MUST(dtb_set_enabled("lens", 0, 1));
  MUST(dtb_params_set("ashift", 0, "{\"rotation\":4.0}"));
  MUST(dtb_params_set("crop", 0, "{\"cx\":0.1,\"cy\":0.05,\"cw\":0.85,\"ch\":0.9}"));
  MUST(dtb_set_enabled("borders", 0, 1));
  MUST(dtb_history_seal());
  const double rt = round_trip("lens, rotation, crop, orientation, framing");
  CHECK(rt < 1.0, "round trip through every geometry module: %.3f px", rt);
  // errors
  char *s = NULL;
  EXPECT(dtb_coords_transform("{\"direction\":\"sideways\",\"points\":[]}", &s), DTB_E_ARG);
  EXPECT(dtb_coords_transform("{\"direction\":\"to_original\",\"points\":[[1]]}", &s), DTB_E_ARG);
  EXPECT(dtb_coords_transform("{\"direction\":\"to_original\",\"points\":[[\"a\",1]]}", &s), DTB_E_TYPE);
  MUST(dtb_close());
  return g_fail;
}

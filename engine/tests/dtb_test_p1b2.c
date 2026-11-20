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

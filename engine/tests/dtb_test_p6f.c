// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of brief P6f, part of the test driver: dtb_test.c includes this file
 * after dtb_test_p6e.c.
 *
 *   graphs <raw> <xmp> <outdir>  C47: the curves the app's graph editors
 *                                write, for base curve, saturation curve,
 *                                lowlight vision, raw denoise and denoise
 *                                (profiled, wavelets): one params_set is one
 *                                history item and reads back as written; the
 *                                render differs from the module with its
 *                                default curve (pixels changed, mean
 *                                difference); XMP round trip (the module's
 *                                parameters and the render identical); one
 *                                XMP per module (p6f_<op>.xmp) for
 *                                darktable-cli
 */

typedef struct p6f_case_t
{
  const char *op;
  /* written before the default render (the module's setting the curve works with) */
  const char *setup;
  /* the curve the editor writes, one params_set */
  const char *curve;
  /* a few of its paths ("/" separated) and values, read back */
  const char *paths[3];
  double values[3];
} p6f_case_t;

static gchar *p6f_params_text(const char *op)
{
  JsonNode *mods = get_modules();
  JsonObject *o = find_mod(mods, op, 0);
  JsonNode *p = o ? json_object_get_member(o, "params") : NULL;
  gchar *s = p ? json_to_string(p, FALSE) : g_strdup("");
  json_node_unref(mods);
  return s;
}

/* pixels whose 8-bit value differs by more than 2 in a channel, the mean absolute difference over all channels */
static size_t p6f_diff(const uint8_t *a, const uint8_t *b, int w, int h, double *mean, int *mx)
{
  size_t n = 0;
  double sum = 0.0;
  *mx = 0;
  for(size_t i = 0; i < (size_t)w * h; i++)
  {
    int d = 0;
    for(int c = 0; c < 3; c++)
    {
      const int e = abs((int)a[4 * i + c] - (int)b[4 * i + c]);
      sum += e;
      d = MAX(d, e);
    }
    *mx = MAX(*mx, d);
    if(d > 2) n++;
  }
  *mean = sum / (3.0 * w * h);
  return n;
}

static void p6f_case(const p6f_case_t *t, const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  MUST(dtb_set_enabled(t->op, 0, 1));
  if(t->setup) MUST(dtb_params_set(t->op, 0, t->setup));
  MUST(dtb_history_seal());
  int w0 = 0, h0 = 0;
  uint8_t *def = render_full(&w0, &h0, NULL);

  // the editor's write: one history item, read back as written
  const int before = history_count(NULL);
  MUST(dtb_params_set(t->op, 0, t->curve));
  MUST(dtb_history_seal());
  const int after = history_count(NULL);
  CHECK(after == before + 1, "%s: %d history items before the curve, %d after (one expected)", t->op, before, after);
  for(int k = 0; k < 3 && t->paths[k]; k++)
  {
    const double v = param_num(t->op, 0, t->paths[k]);
    CHECK(fabs(v - t->values[k]) < 1e-6, "%s %s: wrote %g, reads %g", t->op, t->paths[k], t->values[k], v);
  }

  // the render differs from the module with its default curve
  int w1 = 0, h1 = 0;
  uint8_t *cur = render_full(&w1, &h1, NULL);
  CHECK(w0 == w1 && h0 == h1, "%s: render sizes differ", t->op);
  double mean = 0.0;
  int mx = 0;
  const size_t changed = (w0 == w1 && h0 == h1) ? p6f_diff(def, cur, w0, h0, &mean, &mx) : 0;
  fprintf(stdout, "RESULT %s curve vs default curve: %zu of %d pixels differ by more than 2 (%.3f%%), mean difference %.4f, max %d\n",
          t->op, changed, w0 * h0, 100.0 * changed / ((double)w0 * h0), mean, mx);
  CHECK(changed > (size_t)w0 * h0 / 2000, "%s: the curve changed only %zu pixels", t->op, changed);

  // XMP round trip: the same parameters, the same picture
  gchar *name = g_strdup_printf("p6f_%s", t->op);
  gchar *ppm = g_strdup_printf("%s_bridge.ppm", name);
  gchar *xname = g_strdup_printf("%s.xmp", name);
  write_ppm(path_join(outdir, ppm), cur, w1, h1);
  gchar *p1 = p6f_params_text(t->op);
  MUST(dtb_xmp_write(path_join(outdir, xname)));
  MUST(dtb_close());
  MUST(dtb_open(raw, path_join(outdir, xname)));
  gchar *p2 = p6f_params_text(t->op);
  CHECK(!strcmp(p1, p2), "%s: the XMP round trip changed the parameters", t->op);
  int w2 = 0, h2 = 0;
  uint8_t *again = render_full(&w2, &h2, NULL);
  int md = 0;
  for(size_t i = 0; w1 == w2 && h1 == h2 && i < (size_t)w1 * h1 * 4; i++)
    if((i & 3) != 3) md = MAX(md, abs((int)again[i] - (int)cur[i]));
  fprintf(stdout, "RESULT %s XMP round trip render differs by %d\n", t->op, md);
  CHECK(w1 == w2 && h1 == h2 && md == 0, "%s: the render after reopening the XMP differs (%d)", t->op, md);
  MUST(dtb_close());
  free(def);
  free(cur);
  free(again);
  g_free(p1); g_free(p2); g_free(name); g_free(ppm); g_free(xname);
}

static int cmd_graphs(const char *raw, const char *xmp, const char *outdir)
{
  static const p6f_case_t cases[] = {
    // base curve: a third node lifting the shadows (basecurve.c, curve 0 only)
    { "basecurve", NULL,
      "{\"basecurve_nodes[0]\": 3, \"basecurve[0][0].x\": 0, \"basecurve[0][0].y\": 0, \"basecurve[0][1].x\": 0.25,"
      " \"basecurve[0][1].y\": 0.4, \"basecurve[0][2].x\": 1, \"basecurve[0][2].y\": 1}",
      { "basecurve_nodes/0", "basecurve/0/1/x", "basecurve/0/1/y" }, { 3, 0.25, 0.4 } },
    // saturation curve: the saturation channel raised in the middle (satcurvergb.c channel[0])
    { "satcurvergb", NULL,
      "{\"channel[0].curve_num_nodes\": 3, \"channel[0].curve[0].x\": 0, \"channel[0].curve[0].y\": 0.5,"
      " \"channel[0].curve[1].x\": 0.3, \"channel[0].curve[1].y\": 0.8, \"channel[0].curve[2].x\": 1, \"channel[0].curve[2].y\": 0.6}",
      { "channel/0/curve_num_nodes", "channel/0/curve/1/x", "channel/0/curve/1/y" }, { 3, 0.3, 0.8 } },
    // lowlight: night vision in the shadows, day vision in the highlights (lowlight.c transition_y)
    { "lowlight", NULL,
      "{\"transition_y[0]\": 0.05, \"transition_y[1]\": 0.2, \"transition_y[2]\": 0.5, \"transition_y[3]\": 0.85,"
      " \"transition_y[4]\": 1, \"transition_y[5]\": 1, \"transition_x[2]\": 0.45}",
      { "transition_y/0", "transition_y/3", "transition_x/2" }, { 0.05, 0.85, 0.45 } },
    // raw denoise: every band of "all" stronger, with a threshold that shows (rawdenoise.c y[0])
    { "rawdenoise", "{\"threshold\": 0.1}",
      "{\"y[0][0]\": 1, \"y[0][1]\": 1, \"y[0][2]\": 1, \"y[0][3]\": 0.9, \"y[0][4]\": 0.8, \"y[1][2]\": 0.9}",
      { "y/0/0", "y/0/4", "y/1/2" }, { 1, 0.8, 0.9 } },
    // denoise (profiled), wavelets, Y0U0V0: the luma curve raised (denoiseprofile.c y[4])
    { "denoiseprofile", "{\"mode\": \"MODE_WAVELETS\", \"wavelet_color_mode\": \"MODE_Y0U0V0\", \"use_new_vst\": true}",
      "{\"y[4][0]\": 1, \"y[4][1]\": 1, \"y[4][2]\": 1, \"y[4][3]\": 1, \"y[4][4]\": 0.9, \"y[4][5]\": 0.8, \"y[4][6]\": 0.8}",
      { "y/4/0", "y/4/5", "y/5/0" }, { 1, 0.8, 0.5 } },
  };
  for(size_t k = 0; k < G_N_ELEMENTS(cases); k++) p6f_case(&cases[k], raw, xmp, outdir);

  // saturation curve's picker (satcurvergb.c color_picker_apply): the picked saturation, nothing written
  MUST(dtb_open(raw, xmp));
  MUST(dtb_set_enabled("satcurvergb", 0, 1));
  MUST(dtb_history_seal());
  const int before = history_count(NULL);
  gchar *p0 = p6f_params_text("satcurvergb");
  JsonNode *r = action("satcurvergb", 0, "pick", "{\"point\": [0.5, 0.5]}", DTB_OK);
  JsonObject *o = r ? json_node_get_object(r) : NULL;
  const double s = o ? json_object_get_double_member(o, "saturation") : NAN;
  const double s0 = o ? json_object_get_double_member(o, "saturation_min") : NAN;
  const double s1 = o ? json_object_get_double_member(o, "saturation_max") : NAN;
  const gboolean lut = o && json_object_get_boolean_member(o, "gamut_lut");
  fprintf(stdout, "RESULT satcurvergb pick at the centre: saturation %.4f (min %.4f, max %.4f), pipe gamut LUT %s\n", s, s0, s1, lut ? "yes" : "no");
  CHECK(isfinite(s) && s >= 0.0 && s < 4.0, "picked saturation %g", s);
  CHECK(s0 <= s + 1e-6 && s <= s1 + 1e-6, "saturation not within min..max");
  CHECK(history_count(NULL) == before, "the picker added a history item");
  gchar *p1 = p6f_params_text("satcurvergb");
  CHECK(!strcmp(p0, p1), "the picker changed the parameters");
  g_free(p0); g_free(p1);
  if(r) json_node_unref(r);
  MUST(dtb_close());
  return g_fail;
}

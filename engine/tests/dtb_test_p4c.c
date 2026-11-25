// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P4c item 1 (dtb_retouch_focus), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3n.c.
 *
 *   rtfocus <raw> <xmp> <outdir>   C37: retouch's focus shows the whole,
 *                                  uncropped picture (desktop skips crop);
 *                                  frame, sizes, coordinates, pause/resume,
 *                                  no history; a heal spot outside the crop
 *                                  placed while focused survives the XMP;
 *                                  XMP for darktable-cli
 */

static JsonNode *p4c_focus(const char *action)
{
  char *s = NULL;
  MUST(dtb_retouch_focus("retouch", 0, action, &s));
  return jparse_owned(s);
}

static void p4c_point(const char *dir, double x, double y, double *ox, double *oy)
{
  gchar *req = g_strdup_printf("{\"direction\":\"%s\",\"points\":[[%.6f,%.6f]]}", dir, x, y);
  char *s = NULL;
  MUST(dtb_coords_transform(req, &s));
  g_free(req);
  JsonNode *n = jparse_owned(s);
  JsonArray *p = json_array_get_array_element(json_object_get_array_member(json_node_get_object(n), "points"), 0);
  *ox = json_array_get_double_element(p, 0);
  *oy = json_array_get_double_element(p, 1);
  json_node_unref(n);
}

/* image_info's width (the picture's processed size) */
static long p4c_info_width(void)
{
  char *s = NULL;
  MUST(dtb_image_info(&s));
  JsonNode *n = jparse_owned(s);
  const long w = (long)json_object_get_int_member(json_node_get_object(n), "width");
  json_node_unref(n);
  return w;
}

static uint8_t *p4c_preview(const char *opts, int *w, int *h)
{
  uint8_t *px = NULL;
  MUST(dtb_render_preview_ex(800, opts, &px, w, h));
  return px;
}

static int cmd_rtfocus(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int end = 0;
  char *s = NULL;
  // a crop of the middle (freehand), as one history item
  MUST(dtb_params_set("crop", 0, "{\"cx\":0.25,\"cy\":0.25,\"cw\":0.75,\"ch\":0.75,\"ratio_d\":0,\"ratio_n\":0}"));
  MUST(dtb_set_enabled("crop", 0, 1));
  MUST(dtb_history_seal());
  int wc, hc;
  MUST(dtb_render_size(1.0f, &wc, &hc));
  double ox, oy;
  p4c_point("to_processed", 0.1, 0.1, &ox, &oy);
  fprintf(stdout, "RESULT cropped: %dx%d, original 0.1,0.1 -> %.4f,%.4f\n", wc, hc, ox, oy);
  CHECK(ox < 0.0 && oy < 0.0, "a point left of and above the crop is outside the cropped picture");

  // 0. errors: not retouch, unknown action; "state" before begin is not focused
  EXPECT(dtb_retouch_focus("exposure", 0, "begin", &s), DTB_E_ARG);
  EXPECT(dtb_retouch_focus("retouch", 0, "spin", &s), DTB_E_ARG);
  JsonNode *st = p4c_focus("state");
  CHECK(!json_object_get_boolean_member(json_node_get_object(st), "focused"), "not focused before begin");
  json_node_unref(st);

  // 1. begin: the whole picture, crop left out, the frame where crop's parameters say; no history item
  const int h0 = history_count(&end);
  st = p4c_focus("begin");
  JsonObject *o = json_node_get_object(st);
  CHECK(json_object_get_boolean_member(o, "focused"), "begin: focused");
  JsonArray *lo = json_object_get_array_member(o, "left_out");
  gboolean crop_out = FALSE;
  for(guint i = 0; i < json_array_get_length(lo); i++)
    crop_out |= !strcmp(json_object_get_string_member(json_array_get_object_element(lo, i), "op"), "crop");
  CHECK(crop_out, "crop is left out while retouch has the focus");
  JsonNode *fr = json_object_get_member(o, "frame");
  CHECK(fr && JSON_NODE_HOLDS_ARRAY(fr), "a frame while crop is left out");
  double f[8] = { 0 };
  if(fr && JSON_NODE_HOLDS_ARRAY(fr))
    for(int k = 0; k < 4; k++)
    {
      JsonArray *c = json_array_get_array_element(json_node_get_array(fr), k);
      f[2 * k] = json_array_get_double_element(c, 0);
      f[2 * k + 1] = json_array_get_double_element(c, 1);
    }
  fprintf(stdout, "RESULT frame: %.4f,%.4f %.4f,%.4f %.4f,%.4f %.4f,%.4f (crop 0.25..0.75)\n", f[0], f[1], f[2], f[3],
          f[4], f[5], f[6], f[7]);
  const double tol = 0.01;
  CHECK(fabs(f[0] - 0.25) < tol && fabs(f[1] - 0.25) < tol && fabs(f[4] - 0.75) < tol && fabs(f[5] - 0.75) < tol
            && fabs(f[2] - 0.75) < tol && fabs(f[7] - 0.75) < tol,
        "frame = crop's box within %.2f", tol);
  json_node_unref(st);
  CHECK(history_count(&end) == h0, "begin adds no history item");
  int wf, hf;
  MUST(dtb_render_size(1.0f, &wf, &hf));
  const long info_w = p4c_info_width();
  fprintf(stdout, "RESULT focused: %dx%d (image_info width %ld), cropped %dx%d\n", wf, hf, info_w, wc, hc);
  CHECK(wf > wc && hf > hc, "focused: the uncropped picture is larger");
  CHECK(info_w == wf, "image_info follows the focus");
  p4c_point("to_processed", 0.1, 0.1, &ox, &oy);
  fprintf(stdout, "RESULT focused: original 0.1,0.1 -> %.4f,%.4f\n", ox, oy);
  CHECK(ox > 0.0 && oy > 0.0 && ox < 0.25 && oy < 0.25, "the point is inside the whole picture, left of and above the frame");

  // the focused render equals one that leaves crop out with "bypass" (unfocused), byte for byte
  int pw, ph, bw, bh;
  uint8_t *pf = p4c_preview(NULL, &pw, &ph);
  json_node_unref(p4c_focus("pause"));
  uint8_t *pb = p4c_preview("{\"bypass\":[{\"op\":\"crop\",\"priority\":0}]}", &bw, &bh);
  int md = -1;
  double mean = 0.0;
  if(pw == bw && ph == bh) md = max_diff(pf, pb, (size_t)4 * pw * ph, &mean);
  fprintf(stdout, "RESULT focused preview %dx%d vs bypass of crop %dx%d: max %d mean %.4f\n", pw, ph, bw, bh, md, mean);
  CHECK(md == 0, "focused render = render with crop bypassed");
  free(pb);

  // 2. pause: the edit as it is (for LightPhotoLibrary), resume: whole again; no history
  int wp, hp, wr, hr;
  MUST(dtb_render_size(1.0f, &wp, &hp));
  st = p4c_focus("resume");
  MUST(dtb_render_size(1.0f, &wr, &hr));
  fprintf(stdout, "RESULT paused %dx%d, resumed %dx%d\n", wp, hp, wr, hr);
  CHECK(wp == wc && hp == hc, "paused: the cropped picture");
  CHECK(wr == wf && hr == hf, "resumed: the whole picture");
  CHECK(json_object_get_boolean_member(json_node_get_object(st), "focused")
            && !json_object_get_boolean_member(json_node_get_object(st), "paused"), "resumed: focused");
  json_node_unref(st);
  CHECK(history_count(&end) == h0, "pause/resume add no history item");

  // 3. a heal spot outside the crop (left of it), placed while focused: one item; the whole picture changes there
  const int hA = history_count(&end);
  MUST(dtb_retouch_add("retouch", 0, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.1,0.5],\"radius\":0.03,\"border\":0.01},"
                                     "\"source\":[0.45,0.45]}", &s));
  JsonNode *idn = jparse_owned(s);
  const int id = (int)json_object_get_int_member(json_node_get_object(idn), "id");
  json_node_unref(idn);
  CHECK(history_count(&end) == hA + 1, "the heal spot is one history item");
  int pw2, ph2;
  uint8_t *pf2 = p4c_preview(NULL, &pw2, &ph2);
  md = -1;
  if(pw2 == pw && ph2 == ph) md = max_diff(pf, pf2, (size_t)4 * pw * ph, &mean);
  // heal keeps the target's border and takes the source's detail: where both are flat at preview size (arw at this
  // place, runs 45606251102 and 45610250484) nothing visible changes, so that is reported, not checked
  fprintf(stdout, "RESULT heal outside the crop, focused picture: max %d mean %.4f\n", md, mean);
  // a clone spot outside the crop copies the source's pixels: the uncropped picture must change there
  MUST(dtb_retouch_add("retouch", 0, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.1,0.3],\"radius\":0.04,\"border\":0.01},"
                                     "\"source\":[0.5,0.5],\"algorithm\":\"clone\"}", &s));
  dtb_free(s);
  s = NULL;
  CHECK(history_count(&end) == hA + 2, "the clone spot is one history item");
  uint8_t *pf3 = p4c_preview(NULL, &pw2, &ph2);
  md = -1;
  if(pw2 == pw && ph2 == ph) md = max_diff(pf2, pf3, (size_t)4 * pw * ph, &mean);
  fprintf(stdout, "RESULT clone outside the crop, focused picture: max %d mean %.4f\n", md, mean);
  CHECK(md > 0, "a clone spot outside the crop changes the whole picture");
  free(pf);
  free(pf2);
  free(pf3);

  // 4. end: the cropped picture again, no history item; the XMP for darktable-cli
  const int hE = history_count(&end);
  st = p4c_focus("end");
  CHECK(!json_object_get_boolean_member(json_node_get_object(st), "focused"), "end: not focused");
  CHECK(JSON_NODE_HOLDS_NULL(json_object_get_member(json_node_get_object(st), "frame")), "end: no frame");
  json_node_unref(st);
  int we, he;
  MUST(dtb_render_size(1.0f, &we, &he));
  CHECK(we == wc && he == hc, "end: the cropped picture (%dx%d)", we, he);
  CHECK(history_count(&end) == hE, "end adds no history item");
  save_render(outdir, "p4c_focus");

  // 5. round trip: the spot is in the XMP where it was placed
  char *x = path_join(outdir, "p4c_focus.xmp");
  MUST(dtb_close());
  MUST(dtb_open(raw, x));
  JsonNode *rt = p3m_state();
  JsonObject *sh = p3m_shape(rt, id);
  CHECK(sh != NULL, "the heal spot is in the XMP");
  if(sh)
  {
    gchar *g = json_to_string(json_object_get_member(sh, "geometry"), FALSE);
    fprintf(stdout, "RESULT reopened: shape %d %s geometry %s\n", id, json_object_get_string_member(sh, "algorithm"), g);
    g_free(g);
  }
  json_node_unref(rt);
  st = p4c_focus("state");
  CHECK(!json_object_get_boolean_member(json_node_get_object(st), "focused"), "a new session starts unfocused");
  json_node_unref(st);
  free(x);
  MUST(dtb_close());
  return g_fail;
}

/*
 *   pickwork <raw> <xmp> <outdir>  C38: a module pick carries the pipe's
 *                                  working profile and rgb curve's
 *                                  picker_scale values; checked against the
 *                                  formulas (linear profiles) for Rec 2020
 *                                  and linear Rec 709
 */
static double p4c_labL(double y)
{
  const double eps = 216.0 / 24389.0, kappa = 24389.0 / 27.0;
  const double f = y > eps ? cbrt(y) : (kappa * y + 16.0) / 116.0;
  return (116.0 * f - 16.0) / 100.0;
}

static void p4c_pick_check(const char *what, double *y1)
{
  char *s = NULL;
  MUST(dtb_color_pick("rgbcurve", 0, "{\"area\":[0.4,0.4,0.6,0.6]}", &s));
  JsonNode *n = jparse_owned(s);
  JsonObject *o = json_node_get_object(n);
  JsonNode *wn = json_object_get_member(o, "work");
  CHECK(wn && JSON_NODE_HOLDS_OBJECT(wn), "%s: the pick has the working profile", what);
  if(!wn || !JSON_NODE_HOLDS_OBJECT(wn))
  {
    json_node_unref(n);
    return;
  }
  JsonObject *w = json_node_get_object(wn);
  JsonArray *co = json_object_get_array_member(w, "luminance_coeffs");
  const double c0 = json_array_get_double_element(co, 0), c1 = json_array_get_double_element(co, 1),
               c2 = json_array_get_double_element(co, 2);
  const gboolean nonlinear = json_object_get_boolean_member(w, "nonlinear");
  JsonArray *mean = json_object_get_array_member(json_object_get_object_member(o, "input"), "mean");
  const double r = json_array_get_double_element(mean, 0), g = json_array_get_double_element(mean, 1),
               b = json_array_get_double_element(mean, 2);
  JsonObject *wi = json_object_get_object_member(w, "input");
  const double lum = json_array_get_double_element(json_object_get_array_member(wi, "luminance"), 0);
  const double glum = json_array_get_double_element(json_object_get_array_member(wi, "grey_luminance"), 0);
  const double grey_g = json_array_get_double_element(
      json_array_get_array_element(json_object_get_array_member(wi, "grey"), 0), 1);
  const double y = c0 * r + c1 * g + c2 * b;
  fprintf(stdout, "RESULT %s: coeffs %.7f %.7f %.7f (sum %.6f) nonlinear %d, mean %.5f %.5f %.5f, luminance %.6f "
                  "(formula %.6f), grey luminance %.6f (formula %.6f), grey g %.6f (formula %.6f)\n",
          what, c0, c1, c2, c0 + c1 + c2, nonlinear, r, g, b, lum, y, glum, p4c_labL(lum * (c0 + c1 + c2)), grey_g,
          p4c_labL(g * (c0 + c1 + c2)));
  CHECK(!nonlinear, "%s: a linear working profile", what);
  CHECK(fabs(lum - y) < 1e-5, "%s: luminance = matrix row . rgb", what);
  CHECK(fabs(glum - p4c_labL(lum * (c0 + c1 + c2))) < 1e-4, "%s: middle grey = Lab L / 100", what);
  CHECK(fabs(grey_g - p4c_labL(g * (c0 + c1 + c2))) < 1e-4, "%s: each channel's middle grey", what);
  *y1 = c1;
  json_node_unref(n);
}

static int cmd_pickwork(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  MUST(dtb_set_enabled("rgbcurve", 0, 1));
  double y2020 = 0.0, y709 = 0.0;
  p4c_pick_check("rec2020", &y2020);
  // the global picker has no module, so no working profile values
  char *s = NULL;
  MUST(dtb_color_pick(NULL, 0, "{\"area\":[0.4,0.4,0.6,0.6]}", &s));
  JsonNode *n = jparse_owned(s);
  CHECK(JSON_NODE_HOLDS_NULL(json_object_get_member(json_node_get_object(n), "work")), "global picker: work null");
  json_node_unref(n);
  // another working profile: input color profile's "working profile" = linear Rec 709
  const int r = dtb_param_set("colorin", 0, "type_work", "\"DT_COLORSPACE_LIN_REC709\"");
  CHECK(r == DTB_OK, "colorin working profile set to linear Rec 709 (%d: %s)", r, dtb_last_error());
  if(r == DTB_OK)
  {
    MUST(dtb_history_seal());
    p4c_pick_check("rec709", &y709);
    CHECK(fabs(y709 - y2020) > 0.01, "the luminance row follows the working profile (%.4f vs %.4f)", y709, y2020);
  }
  MUST(dtb_close());
  return g_fail;
}

/*
 *   diffpreset <raw> <xmp> <outdir> C39: diffuse or sharpen's built-in
 *                                  presets are listed under their stored
 *                                  names ("_builtin_" + the shown name,
 *                                  gui/presets.c); "lens deblur | medium"
 *                                  applies by that name, one history item,
 *                                  its parameters; the shown name is no row
 */
static int cmd_diffpreset(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  char *s = NULL;
  MUST(dtb_presets_list("diffuse", 0, &s));
  JsonNode *n = jparse_owned(s);
  JsonArray *a = json_node_get_array(n);
  int builtins = 0;
  gboolean medium = FALSE;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *p = json_array_get_object_element(a, i);
    const char *name = json_object_get_string_member(p, "name");
    if(!json_object_get_boolean_member(p, "builtin")) continue;
    builtins++;
    fprintf(stdout, "RESULT diffuse built-in: '%s' usable %d\n", name, json_object_get_boolean_member(p, "usable"));
    if(!strcmp(name, "_builtin_lens deblur | medium")) medium = json_object_get_boolean_member(p, "usable");
  }
  json_node_unref(n);
  CHECK(builtins >= 2, "diffuse has built-in presets (%d)", builtins);
  CHECK(medium, "'_builtin_lens deblur | medium' is listed and usable");
  int end = 0;
  const int h0 = history_count(&end);
  EXPECT(dtb_preset_apply("diffuse", 0, "lens deblur | medium"), DTB_E_NOT_FOUND);
  CHECK(history_count(&end) == h0, "the shown name applies nothing");
  MUST(dtb_preset_apply("diffuse", 0, "_builtin_lens deblur | medium"));
  const int h1 = history_count(&end);
  const double it = param_num("diffuse", 0, "iterations"), rad = param_num("diffuse", 0, "radius");
  fprintf(stdout, "RESULT applied: items %d -> %d, iterations %g, radius %g\n", h0, h1, it, rad);
  CHECK(h1 == h0 + 1, "one history item");
  CHECK(it == 16 && rad == 10, "the preset's parameters (iterations 16, radius 10: iop/diffuse.c init_presets)");
  MUST(dtb_close());
  return g_fail;
}

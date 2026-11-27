// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of brief P6e, part of the test driver: dtb_test.c includes this file
 * after dtb_test_p6d.c.
 *
 *   colordialog <raw> <xmp> <outdir>  C46: framing's border and frame line
 *                                     colours written as the colour dialog
 *                                     writes them (three floats, one history
 *                                     item, no drift), framing's pickers
 *                                     (borders pick border / frame, desktop's
 *                                     loop guard), watermark's list of files,
 *                                     its fonts (non-empty), file, text, font,
 *                                     colour, alignment and its picker; a text
 *                                     watermark that draws pixels; renders
 *                                     against the modules off; XMP round trip;
 *                                     the XMP (p6e_watermark.xmp) for
 *                                     darktable-cli
 */

static int p6e_items(void)
{
  MUST(dtb_history_seal());
  return history_count(NULL);
}

static void p6e_color(const char *op, const char *field, float out[3])
{
  JsonNode *mods = get_modules();
  JsonObject *o = find_mod(mods, op, 0);
  for(int k = 0; k < 3; k++)
  {
    gchar *path = g_strdup_printf("%s/%d", field, k);
    JsonNode *n = o ? param_node(o, path) : NULL;
    out[k] = n ? (float)json_node_get_double(n) : NAN;
    g_free(path);
  }
  json_node_unref(mods);
}

static gchar *p6e_string(const char *op, const char *field)
{
  JsonNode *mods = get_modules();
  JsonObject *o = find_mod(mods, op, 0);
  JsonNode *n = o ? param_node(o, field) : NULL;
  gchar *s = g_strdup(n && JSON_NODE_HOLDS_VALUE(n) ? json_node_get_string(n) : "");
  json_node_unref(mods);
  return s;
}

static int p6e_int(const char *op, const char *field)
{
  JsonNode *mods = get_modules();
  JsonObject *o = find_mod(mods, op, 0);
  JsonNode *n = o ? param_node(o, field) : NULL;
  const int v = n ? (int)json_node_get_int(n) : -999;
  json_node_unref(mods);
  return v;
}

/* the colour dialog's OK: the three floats of one colour, one history item */
static void p6e_set_color(const char *op, const char *field, const double v[3])
{
  const int before = p6e_items();
  gchar *js = g_strdup_printf("{\"%s[0]\": %.17g, \"%s[1]\": %.17g, \"%s[2]\": %.17g}", field, v[0], field, v[1], field, v[2]);
  MUST(dtb_params_set(op, 0, js));
  g_free(js);
  const int after = p6e_items();
  CHECK(after == before + 1, "%s %s: %d history items before, %d after (one expected)", op, field, before, after);
  float got[3];
  p6e_color(op, field, got);
  for(int k = 0; k < 3; k++)
    CHECK(got[k] == (float)v[k], "%s %s[%d]: wrote %.9g, reads %.9g", op, field, k, (float)v[k], got[k]);
}

/* pixels (8-bit, any channel) that differ by more than 24 */
static size_t p6e_changed(const uint8_t *a, const uint8_t *b, int w, int h, int *mx)
{
  size_t n = 0;
  *mx = 0;
  for(size_t i = 0; i < (size_t)w * h; i++)
  {
    int d = 0;
    for(int c = 0; c < 3; c++) d = MAX(d, abs((int)a[4 * i + c] - (int)b[4 * i + c]));
    *mx = MAX(*mx, d);
    if(d > 24) n++;
  }
  return n;
}

static int cmd_colordialog(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int w0 = 0, h0 = 0;
  uint8_t *base = render_full(&w0, &h0, NULL);

  // ---- framing: border and frame line colours as the dialog writes them (8-bit values / 255, as GdkRGBA)
  MUST(dtb_params_set("borders", 0, "{\"size\": 0.1, \"frame_size\": 0.3}"));
  MUST(dtb_history_seal());
  const double border[3] = { 51 / 255.0, 102 / 255.0, 204 / 255.0 };
  const double frame[3] = { 1.0, 0.5, 0.0 };
  p6e_set_color("borders", "color", border);
  p6e_set_color("borders", "frame_color", frame);
  // no drift: a value off the 8-bit grid (a picked one) written back unchanged reads the same
  {
    float c[3];
    p6e_color("borders", "color", c);
    const double back[3] = { c[0], c[1], c[2] };
    gchar *js = g_strdup_printf("{\"color[0]\": %.17g, \"color[1]\": %.17g, \"color[2]\": %.17g}", back[0], back[1], back[2]);
    MUST(dtb_params_set("borders", 0, js));
    g_free(js);
    float d[3];
    p6e_color("borders", "color", d);
    CHECK(!memcmp(c, d, sizeof(c)), "colour round trip drifted");
  }
  int wb = 0, hb = 0;
  uint8_t *framed = render_full(&wb, &hb, NULL);
  CHECK(wb > w0 && hb > h0, "framing did not enlarge the picture (%dx%d -> %dx%d)", w0, h0, wb, hb);
  // the top-left corner is border colour
  {
    const uint8_t *px = framed + 4 * ((size_t)2 * wb + 2);
    fprintf(stdout, "RESULT framing corner pixel %d %d %d (border colour set to 51 102 204)\n", px[0], px[1], px[2]);
    CHECK(abs(px[0] - 51) <= 3 && abs(px[1] - 102) <= 3 && abs(px[2] - 204) <= 3, "border colour not in the corner: %d %d %d",
          px[0], px[1], px[2]);
  }
  free(framed);

  // ---- framing's pickers: the picked mean into the colour, one item; the same pick again changes nothing
  {
    const int before = p6e_items();
    JsonNode *r = action("borders", 0, "pick", "{\"which\": \"border\", \"area\": [0.4, 0.4, 0.6, 0.6]}", DTB_OK);
    const int after = p6e_items();
    JsonObject *o = r ? json_node_get_object(r) : NULL;
    CHECK(o && json_object_get_boolean_member(o, "changed"), "border pick did not change the colour");
    CHECK(after == before + 1, "border pick: %d items before, %d after", before, after);
    float c[3];
    p6e_color("borders", "color", c);
    JsonArray *ca = o ? json_object_get_array_member(o, "color") : NULL;
    for(int k = 0; ca && k < 3; k++)
      CHECK(fabsf(c[k] - (float)json_array_get_double_element(ca, k)) < 1e-6f, "pick answer and parameter differ");
    fprintf(stdout, "RESULT border pick: %.4f %.4f %.4f\n", c[0], c[1], c[2]);
    if(r) json_node_unref(r);
    const int b2 = p6e_items();
    JsonNode *r2 = action("borders", 0, "pick", "{\"which\": \"frame\", \"area\": [0.4, 0.4, 0.6, 0.6]}", DTB_OK);
    const int a2 = p6e_items();
    JsonObject *o2 = r2 ? json_node_get_object(r2) : NULL;
    // desktop's guard: the pick equals the border colour, so the frame colour is left alone
    CHECK(o2 && !json_object_get_boolean_member(o2, "changed") && a2 == b2, "frame pick equal to the border colour was written");
    if(r2) json_node_unref(r2);
    JsonNode *r3 = action("borders", 0, "pick", "{\"which\": \"frame\", \"point\": [0.25, 0.3]}", DTB_OK);
    if(r3) json_node_unref(r3);
    action("borders", 0, "pick", "{\"which\": \"line\", \"point\": [0.5, 0.5]}", DTB_E_ARG);
  }

  // ---- watermark: files, fonts
  JsonNode *fl = action("watermark", 0, "list_files", NULL, DTB_OK);
  JsonObject *flo = fl ? json_node_get_object(fl) : NULL;
  JsonArray *files = flo ? json_object_get_array_member(flo, "files") : NULL;
  int has_simple = 0, has_dt = 0;
  for(guint i = 0; files && i < json_array_get_length(files); i++)
  {
    JsonObject *f = json_array_get_object_element(files, i);
    const char *n = json_object_get_string_member(f, "file");
    has_simple |= !g_strcmp0(n, "simple-text.svg");
    has_dt |= !g_strcmp0(n, "darktable.svg");
  }
  fprintf(stdout, "RESULT watermark files: %u (current '%s')\n", files ? json_array_get_length(files) : 0,
          flo ? json_object_get_string_member(flo, "current") : "");
  CHECK(has_simple && has_dt, "list_files lacks simple-text.svg or darktable.svg");

  JsonNode *fo = action("watermark", 0, "list_fonts", NULL, DTB_OK);
  JsonObject *foo = fo ? json_node_get_object(fo) : NULL;
  JsonArray *fams = foo ? json_object_get_array_member(foo, "families") : NULL;
  const guint nf = fams ? json_array_get_length(fams) : 0;
  const char *font = NULL;
  for(guint i = 0; i < nf && !font; i++)
  {
    JsonObject *f = json_array_get_object_element(fams, i);
    if(!g_strcmp0(json_object_get_string_member(f, "family"), "DejaVu Sans"))
    {
      JsonArray *faces = json_object_get_array_member(f, "faces");
      for(guint j = 0; j < json_array_get_length(faces); j++)
      {
        const char *fn = json_object_get_string_member(json_array_get_object_element(faces, j), "font");
        if(!font || !strcmp(fn, "DejaVu Sans 10")) font = fn;
      }
    }
  }
  if(!font && nf)
  {
    JsonArray *faces = json_object_get_array_member(json_array_get_object_element(fams, 0), "faces");
    font = json_object_get_string_member(json_array_get_object_element(faces, 0), "font");
  }
  fprintf(stdout, "RESULT fonts: %u families, using '%s'\n", nf, font ? font : "(none)");
  CHECK(nf > 0 && font, "the font list is empty");

  // ---- watermark: file, text, font, colour, alignment, scale as one step each as the panel writes them
  {
    const int before = p6e_items();
    MUST(dtb_params_set("watermark", 0, "{\"filename\": \"simple-text.svg\"}"));
    MUST(dtb_history_seal());
    MUST(dtb_params_set("watermark", 0, "{\"text\": \"P6e watermark 0123456789\"}"));
    MUST(dtb_history_seal());
    gchar *fj = g_strdup_printf("{\"font\": \"%s\"}", font ? font : "DejaVu Sans 10");
    MUST(dtb_params_set("watermark", 0, fj));
    g_free(fj);
    MUST(dtb_history_seal());
    MUST(dtb_params_set("watermark", 0, "{\"alignment\": 0, \"scale\": 60, \"scale_base\": \"DT_SCALE_MAINMENU_IMAGE\"}"));
    const int after = p6e_items();
    CHECK(after == before + 4, "watermark settings: %d items before, %d after (four expected)", before, after);
  }
  const double wcol[3] = { 1.0, 1.0, 0.0 };
  p6e_set_color("watermark", "color", wcol);
  gchar *fn = p6e_string("watermark", "filename"), *tx = p6e_string("watermark", "text"), *ft = p6e_string("watermark", "font");
  CHECK(!strcmp(fn, "simple-text.svg") && !strcmp(tx, "P6e watermark 0123456789") && !strcmp(ft, font ? font : "DejaVu Sans 10")
            && p6e_int("watermark", "alignment") == 0,
        "watermark parameters read back wrong: '%s' '%s' '%s' %d", fn, tx, ft, p6e_int("watermark", "alignment"));

  // the watermark picker writes its colour
  {
    JsonNode *r = action("watermark", 0, "pick", "{\"point\": [0.5, 0.5]}", DTB_OK);
    if(r) json_node_unref(r);
    float c[3];
    p6e_color("watermark", "color", c);
    CHECK(!(c[0] == 1.0f && c[1] == 1.0f && c[2] == 0.0f), "watermark pick did not write the colour");
    p6e_set_color("watermark", "color", wcol);
  }

  // ---- a text watermark draws pixels: framing off, watermark on vs off
  MUST(dtb_set_enabled("borders", 0, 0));
  MUST(dtb_set_enabled("watermark", 0, 0));
  MUST(dtb_history_seal());
  int w1, h1, w2, h2, mx = 0;
  uint8_t *off = render_full(&w1, &h1, NULL);
  MUST(dtb_set_enabled("watermark", 0, 1));
  MUST(dtb_history_seal());
  uint8_t *on = render_full(&w2, &h2, NULL);
  CHECK(w1 == w2 && h1 == h2, "render sizes differ");
  const size_t changed = (w1 == w2 && h1 == h2) ? p6e_changed(on, off, w1, h1, &mx) : 0;
  fprintf(stdout, "RESULT text watermark: %zu pixels changed of %d (%.3f%%), max %d\n", changed, w1 * h1,
          100.0 * changed / ((double)w1 * h1), mx);
  CHECK(changed > 500, "the text watermark drew %zu pixels", changed);
  // alignment moves it: bottom-right (8) changes other pixels than top-left (0)
  MUST(dtb_param_set("watermark", 0, "alignment", "8"));
  MUST(dtb_history_seal());
  uint8_t *br = render_full(&w2, &h2, NULL);
  int mx2 = 0;
  const size_t moved = p6e_changed(on, br, w1, h1, &mx2);
  CHECK(moved > 500, "alignment 8 left the picture as alignment 0 (%zu)", moved);
  free(br);
  MUST(dtb_param_set("watermark", 0, "alignment", "0"));
  MUST(dtb_set_enabled("borders", 0, 1));
  MUST(dtb_history_seal());

  // ---- XMP round trip
  int w3, h3, w4, h4;
  uint8_t *both = render_full(&w3, &h3, NULL);
  write_ppm(path_join(outdir, "p6e_watermark_bridge.ppm"), both, w3, h3);
  float bc[3], fc[3], wc[3];
  p6e_color("borders", "color", bc);
  p6e_color("borders", "frame_color", fc);
  p6e_color("watermark", "color", wc);
  MUST(dtb_xmp_write(path_join(outdir, "p6e_watermark.xmp")));
  MUST(dtb_close());
  MUST(dtb_open(raw, path_join(outdir, "p6e_watermark.xmp")));
  float bc2[3], fc2[3], wc2[3];
  p6e_color("borders", "color", bc2);
  p6e_color("borders", "frame_color", fc2);
  p6e_color("watermark", "color", wc2);
  CHECK(!memcmp(bc, bc2, sizeof(bc)) && !memcmp(fc, fc2, sizeof(fc)) && !memcmp(wc, wc2, sizeof(wc)), "XMP round trip changed a colour");
  gchar *fn2 = p6e_string("watermark", "filename"), *tx2 = p6e_string("watermark", "text"), *ft2 = p6e_string("watermark", "font");
  CHECK(!strcmp(fn, fn2) && !strcmp(tx, tx2) && !strcmp(ft, ft2), "XMP round trip changed a watermark text field");
  uint8_t *again = render_full(&w4, &h4, NULL);
  int md = 0;
  for(size_t i = 0; w3 == w4 && h3 == h4 && i < (size_t)w3 * h3 * 4; i++)
    if((i & 3) != 3) md = MAX(md, abs((int)again[i] - (int)both[i]));
  fprintf(stdout, "RESULT XMP round trip render differs by %d\n", md);
  CHECK(w3 == w4 && h3 == h4 && md == 0, "the render after reopening the XMP differs (%d)", md);
  free(again);
  free(both);
  free(on);
  free(off);
  free(base);
  g_free(fn); g_free(tx); g_free(ft); g_free(fn2); g_free(tx2); g_free(ft2);
  if(fl) json_node_unref(fl);
  if(fo) json_node_unref(fo);
  MUST(dtb_close());
  return g_fail;
}

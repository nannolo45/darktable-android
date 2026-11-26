// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of brief P6a, part of the test driver: dtb_test.c includes this file
 * after dtb_test_p5a.c.
 *
 *   wbsettings <raw> <xmp> <outdir>  C41: white balance's "settings" list
 *                                    (temperature list_settings): desktop's
 *                                    order and the one current entry; a
 *                                    camera preset and a fine-tuning step
 *                                    give the multipliers of wb_presets.json
 *                                    (read here on its own, desktop's
 *                                    interpolation between two listed
 *                                    tunings), one history item each; the
 *                                    finetune range of get_wb; XMP for
 *                                    darktable-cli (p6a_wb)
 *   gradoffset <raw> <xmp> <outdir>  C42: graduated density's offset set
 *                                    as the app's slider sets it
 *                                    (params_set), XMP for darktable-cli
 *                                    (p6a_grad)
 */

/* the presets of wb_presets.json for maker/model, as common/wb_presets.c
 * reads them (names in lower case) */
typedef struct p6a_wbp_t
{
  char name[64];
  int tuning;
  double ch[4];
} p6a_wbp_t;

static int p6a_wb_json(const char *maker, const char *model, p6a_wbp_t *out, int max)
{
  const char *prefix = getenv("DT_PREFIX");
  gchar *path = g_build_filename(prefix ? prefix : "", "share/darktable/wb_presets.json", NULL);
  JsonParser *parser = json_parser_new();
  int n = 0;
  if(json_parser_load_from_file(parser, path, NULL))
  {
    JsonArray *makers = json_object_get_array_member(json_node_get_object(json_parser_get_root(parser)), "wb_presets");
    for(guint i = 0; i < json_array_get_length(makers); i++)
    {
      JsonObject *mk = json_array_get_object_element(makers, i);
      if(strcmp(json_object_get_string_member(mk, "maker"), maker)) continue;
      JsonArray *models = json_object_get_array_member(mk, "models");
      for(guint j = 0; j < json_array_get_length(models); j++)
      {
        JsonObject *mo = json_array_get_object_element(models, j);
        if(strcmp(json_object_get_string_member(mo, "model"), model)) continue;
        JsonArray *ps = json_object_get_array_member(mo, "presets");
        for(guint k = 0; k < json_array_get_length(ps) && n < max; k++)
        {
          JsonObject *p = json_array_get_object_element(ps, k);
          gchar *lower = g_utf8_strdown(json_object_get_string_member(p, "name"), -1);
          g_strlcpy(out[n].name, lower, sizeof(out[n].name));
          g_free(lower);
          out[n].tuning = (int)json_object_get_int_member(p, "tuning");
          JsonArray *c = json_object_get_array_member(p, "channels");
          for(int q = 0; q < 4; q++) out[n].ch[q] = json_array_get_double_element(c, q);
          n++;
        }
      }
    }
  }
  else
    fprintf(stdout, "FAIL cannot read %s\n", path), g_fail = 1;
  g_object_unref(parser);
  g_free(path);
  return n;
}

/* the multipliers desktop's _preset_tune_callback applies for preset `name`
 * at `tune`: a listed tuning as it is, else dt_wb_preset_interpolate between
 * the two listed tunings around it (common/wb_presets.c) */
static gboolean p6a_wb_expected(const p6a_wbp_t *ps, int n, const char *name, int tune, double out[4])
{
  for(int i = 0; i < n; i++)
    if(!strcmp(ps[i].name, name) && ps[i].tuning == tune)
    {
      memcpy(out, ps[i].ch, sizeof(double) * 4);
      return TRUE;
    }
  for(int i = 1; i < n; i++)
    if(!strcmp(ps[i].name, name) && !strcmp(ps[i - 1].name, name) && ps[i - 1].tuning < tune && ps[i].tuning > tune)
    {
      const double t = CLAMP((double)(tune - ps[i - 1].tuning) / (double)(ps[i].tuning - ps[i - 1].tuning), 0.0, 1.0);
      for(int k = 0; k < 3; k++) out[k] = 1.0 / (((1.0 - t) / ps[i - 1].ch[k]) + (t / ps[i].ch[k]));
      // the fourth channel keeps what the interpolated entry was initialised with (0, as the bridge's)
      out[3] = 0.0;
      return TRUE;
    }
  return FALSE;
}

static double p6a_wb_coeff_diff(const double want[4], gboolean four)
{
  static const char *f[4] = { "red", "green", "blue", "various" };
  double worst = 0.0;
  for(int k = 0; k < (four ? 4 : 3); k++)
    worst = fmax(worst, fabs(param_num("temperature", 0, f[k]) - (float)want[k]));
  return worst;
}

static JsonObject *p6a_find_current(JsonArray *a, int *count)
{
  JsonObject *cur = NULL;
  *count = 0;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(json_object_get_boolean_member(o, "current"))
    {
      (*count)++;
      cur = o;
    }
  }
  return cur;
}

static int cmd_wbsettings(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  char *s = NULL;
  MUST(dtb_image_info(&s));
  JsonNode *info = jparse_owned(s);
  JsonObject *exif = json_object_get_object_member(json_node_get_object(info), "exif");
  const char *maker = json_object_get_string_member(exif, "maker");
  const char *model = json_object_get_string_member(exif, "model");
  fprintf(stdout, "RESULT camera: %s %s\n", maker, model);

  JsonNode *ln = action("temperature", 0, "list_settings", NULL, DTB_OK);
  if(!ln) { json_node_unref(info); MUST(dtb_close()); return 1; }
  JsonArray *a = json_node_get_array(ln);
  static const char *std[4] = { "as shot", "from image area", "user modified", "camera reference" };
  for(int i = 0; i < 4; i++)
    CHECK(json_array_get_length(a) > (guint)i
            && !strcmp(json_object_get_string_member(json_array_get_object_element(a, i), "name"), std[i]),
          "entry %d is not '%s' (desktop's order)", i, std[i]);
  int ncur = 0;
  JsonObject *cur = p6a_find_current(a, &ncur);
  CHECK(ncur == 1, "%d current entries", ncur);
  JsonNode *wb = action("temperature", 0, "get_wb", NULL, DTB_OK);
  JsonObject *wo = wb ? json_node_get_object(wb) : NULL;
  const char *wbname = wo && !json_node_is_null(json_object_get_member(wo, "preset"))
                       ? json_object_get_string_member(wo, "preset") : "(none)";
  fprintf(stdout, "RESULT settings: %u entries, current '%s', get_wb preset '%s'\n", json_array_get_length(a),
          cur ? json_object_get_string_member(cur, "name") : "(none)", wbname);
  if(cur && wo && strcmp(wbname, "(none)"))
    CHECK(!strcmp(json_object_get_string_member(cur, "name"), wbname), "current entry is not get_wb's preset");
  if(wb) json_node_unref(wb);

  // the camera's presets in wb_presets.json
  p6a_wbp_t ps[256];
  const int np = p6a_wb_json(maker, model, ps, 256);
  int ncam = 0, nsec = 0;
  const char *withft = NULL;
  int tmin = 0, tmax = 0, tdef = 0;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    const char *kind = json_object_get_string_member(o, "kind");
    nsec += !strcmp(kind, "section");
    if(strcmp(kind, "camera")) continue;
    ncam++;
    if(!withft && json_object_get_int_member(o, "tuning_max") > json_object_get_int_member(o, "tuning_min"))
    {
      withft = json_object_get_string_member(o, "name");
      tmin = (int)json_object_get_int_member(o, "tuning_min");
      tmax = (int)json_object_get_int_member(o, "tuning_max");
      tdef = (int)json_object_get_int_member(o, "tuning_default");
    }
  }
  fprintf(stdout, "RESULT camera presets: %d listed, %d rows in wb_presets.json, section %d, with fine tuning: %s [%d, %d]\n",
          ncam, np, nsec, withft ? withft : "(none)", tmin, tmax);
  CHECK((ncam > 0) == (np > 0) && nsec == (ncam > 0), "camera presets listed (%d) but %d rows in wb_presets.json", ncam, np);

  // every camera preset at its default tuning, then a fine-tuning step on the first with fine tuning
  int end = 0;
  double worst = 0.0;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(strcmp(json_object_get_string_member(o, "kind"), "camera")) continue;
    const char *name = json_object_get_string_member(o, "name");
    const int t = (int)json_object_get_int_member(o, "tuning_default");
    gchar *args = g_strdup_printf("{\"name\": \"%s\"}", name);
    const int h0 = history_count(&end);
    json_node_unref(action("temperature", 0, "apply_preset", args, DTB_OK));
    g_free(args);
    CHECK(history_count(&end) == h0 + 1, "'%s': not one history item", name);
    double want[4];
    if(p6a_wb_expected(ps, np, name, t, want)) worst = fmax(worst, p6a_wb_coeff_diff(want, TRUE));
    else CHECK(FALSE, "'%s' tuning %d not in wb_presets.json", name, t);
  }
  fprintf(stdout, "RESULT %d camera presets applied: largest multiplier difference to wb_presets.json %.3g\n", ncam, worst);
  CHECK(worst < 1e-6, "multipliers differ from wb_presets.json by %.3g", worst);

  if(withft)
  {
    gchar *args = g_strdup_printf("{\"name\": \"%s\"}", withft);
    json_node_unref(action("temperature", 0, "apply_preset", args, DTB_OK));
    g_free(args);
    JsonNode *g1 = action("temperature", 0, "get_wb", NULL, DTB_OK);
    JsonObject *o1 = json_node_get_object(g1);
    CHECK(!json_node_is_null(json_object_get_member(o1, "finetune_min"))
            && json_object_get_int_member(o1, "finetune_min") == tmin
            && json_object_get_int_member(o1, "finetune_max") == tmax
            && json_object_get_int_member(o1, "finetune_default") == tdef,
          "get_wb's finetune range is not the preset's");
    // the list now marks it current, and its entry carries the tuning
    JsonNode *l2 = action("temperature", 0, "list_settings", NULL, DTB_OK);
    int n2 = 0;
    JsonObject *c2 = p6a_find_current(json_node_get_array(l2), &n2);
    CHECK(n2 == 1 && c2 && !strcmp(json_object_get_string_member(c2, "name"), withft), "the applied preset is not current");
    json_node_unref(l2);
    json_node_unref(g1);
    // every tuning step of its range, as the finetune slider gives them
    double wft = 0.0;
    int steps = 0;
    for(int t = tmin; t <= tmax; t++)
    {
      args = g_strdup_printf("{\"name\": \"%s\", \"tuning\": %d}", withft, t);
      const int h0 = history_count(&end);
      json_node_unref(action("temperature", 0, "apply_preset", args, DTB_OK));
      g_free(args);
      CHECK(history_count(&end) == h0 + 1, "tuning %d: not one history item", t);
      double want[4];
      if(!p6a_wb_expected(ps, np, withft, t, want)) { CHECK(FALSE, "no expected multipliers for tuning %d", t); continue; }
      // an interpolated tuning: the fourth multiplier is not interpolated by desktop either (left at 0)
      wft = fmax(wft, p6a_wb_coeff_diff(want, FALSE));
      JsonNode *g2 = action("temperature", 0, "get_wb", NULL, DTB_OK);
      JsonObject *o2 = json_node_get_object(g2);
      CHECK(!json_node_is_null(json_object_get_member(o2, "tuning")) && json_object_get_int_member(o2, "tuning") == t,
            "get_wb does not report tuning %d", t);
      if(t == tdef + 1 || (t == tmax && tdef == tmax))
        fprintf(stdout, "RESULT '%s' tuning %d: temperature %.1f K, tint %.4f, multipliers %.6f %.6f %.6f\n", withft, t,
                json_object_get_double_member(o2, "temperature"), json_object_get_double_member(o2, "tint"),
                param_num("temperature", 0, "red"), param_num("temperature", 0, "green"), param_num("temperature", 0, "blue"));
      json_node_unref(g2);
      steps++;
    }
    fprintf(stdout, "RESULT '%s': %d tuning steps, largest multiplier difference %.3g\n", withft, steps, wft);
    CHECK(wft < 1e-6, "fine-tuned multipliers differ by %.3g", wft);
    // the step the parity check renders: one above the default (or below at the top)
    const int tp = tdef < tmax ? tdef + 1 : tdef - 1;
    args = g_strdup_printf("{\"name\": \"%s\", \"tuning\": %d}", withft, tp);
    json_node_unref(action("temperature", 0, "apply_preset", args, DTB_OK));
    g_free(args);
  }
  else if(ncam)
  {
    JsonObject *o = NULL;
    for(guint i = 0; i < json_array_get_length(a) && !o; i++)
      if(!strcmp(json_object_get_string_member(json_array_get_object_element(a, i), "kind"), "camera"))
        o = json_array_get_object_element(a, i);
    gchar *args = g_strdup_printf("{\"name\": \"%s\"}", json_object_get_string_member(o, "name"));
    json_node_unref(action("temperature", 0, "apply_preset", args, DTB_OK));
    g_free(args);
    JsonNode *g1 = action("temperature", 0, "get_wb", NULL, DTB_OK);
    CHECK(json_node_is_null(json_object_get_member(json_node_get_object(g1), "finetune_min")),
          "a preset without fine tuning has a finetune range");
    json_node_unref(g1);
  }
  else
    action("temperature", 0, "apply_preset", "{\"name\": \"camera reference\"}", DTB_OK);
  // modes: as shot and camera reference have no fine tuning
  JsonNode *g3 = action("temperature", 0, "get_wb", NULL, DTB_OK);
  json_node_unref(g3);
  MUST(dtb_xmp_write(path_join(outdir, "p6a_wb.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "p6a_wb_bridge.ppm"), buf, w, h);
  free(buf);
  json_node_unref(ln);
  json_node_unref(info);
  MUST(dtb_close());
  return g_fail;
}

static int cmd_gradoffset(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  MUST(dtb_set_enabled("graduatednd", 0, 1));
  MUST(dtb_history_seal());
  // a turned filter line moved off the centre, as the slider writes it
  MUST(dtb_params_set("graduatednd", 0, "{\"density\": 2.0, \"rotation\": 20.0}"));
  MUST(dtb_history_seal());
  int end = 0;
  const int h0 = history_count(&end);
  MUST(dtb_params_set("graduatednd", 0, "{\"offset\": 30.0}"));
  MUST(dtb_history_seal());
  const int h1 = history_count(&end);
  const double off = param_num("graduatednd", 0, "offset");
  fprintf(stdout, "RESULT offset %g, history %d -> %d\n", off, h0, h1);
  CHECK(fabs(off - 30.0) < 1e-6 && h1 == h0 + 1, "offset not written as one item");
  MUST(dtb_xmp_write(path_join(outdir, "p6a_grad.xmp")));
  int w, h;
  uint8_t *buf = render_full(&w, &h, NULL);
  write_ppm(path_join(outdir, "p6a_grad_bridge.ppm"), buf, w, h);
  free(buf);
  MUST(dtb_close());
  return g_fail;
}

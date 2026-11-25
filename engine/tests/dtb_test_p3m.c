// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3m (docs/design/engine-api.md 6.10), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3d.c, so the helpers of the
 * earlier files are used as they are.
 *
 *   retouch <raw> <xmp> <outdir>   C32: retouch shapes (each algorithm, each
 *                                  shape, source moved, settings, remove,
 *                                  undo), wavelet scales kept, colour look
 *                                  up table edits; XMPs for darktable-cli
 */

static JsonNode *p3m_state(void)
{
  char *s = NULL;
  MUST(dtb_retouch("retouch", 0, &s));
  return jparse_owned(s);
}

static JsonObject *p3m_shape(JsonNode *st, const int id)
{
  JsonArray *a = json_object_get_array_member(json_node_get_object(st), "shapes");
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(json_object_get_int_member(o, "id") == id) return o;
  }
  return NULL;
}

static int p3m_add(const char *req)
{
  char *s = NULL;
  MUST(dtb_retouch_add("retouch", 0, req, &s));
  JsonNode *n = jparse_owned(s);
  const int id = (int)json_object_get_int_member(json_node_get_object(n), "id");
  json_node_unref(n);
  return id;
}

static gchar *p3m_str(void)
{
  JsonNode *st = p3m_state();
  // the module's own description without the transient selection
  gchar *s = json_to_string(st, FALSE);
  json_node_unref(st);
  return s;
}

static int cmd_retouch(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  int end = 0;
  int w0, h0, w1, h1;
  uint8_t *plain = render_full(&w0, &h0, NULL);

  // 0. what an untouched instance says; a module that is not retouch
  JsonNode *st = p3m_state();
  JsonObject *o = json_node_get_object(st);
  fprintf(stdout, "RESULT empty: group null %d, count %ld, algorithm %s, scales %ld\n",
          json_object_get_null_member(o, "group"), (long)json_object_get_int_member(o, "count"),
          json_object_get_string_member(o, "algorithm"), (long)json_object_get_int_member(o, "num_scales"));
  CHECK(!strcmp(json_object_get_string_member(o, "algorithm"), "heal"), "default algorithm heal (retouch.c $DEFAULT)");
  CHECK(json_object_get_int_member(o, "max") == 300, "300 shapes at most");
  json_node_unref(st);
  char *s = NULL;
  EXPECT(dtb_retouch("exposure", 0, &s), DTB_E_ARG);
  EXPECT(dtb_retouch_add("retouch", 0, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.5,0.5],\"radius\":0.05}}", &s),
         DTB_E_ARG);  // heal needs a source
  EXPECT(dtb_retouch_add("retouch", 0, "{\"type\":\"gradient\",\"geometry\":{\"anchor\":[0.5,0.5]},\"source\":[0.1,0.1]}", &s),
         DTB_E_ARG);
  EXPECT(dtb_retouch_add("retouch", 0, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.5,0.5]},\"source\":[0.1,0.1],\"algorithm\":\"smudge\"}", &s),
         DTB_E_ARG);

  // 1. one shape of each kind and algorithm, one history item each
  const int hA = history_count(&end);
  const int heal = p3m_add("{\"type\":\"circle\",\"geometry\":{\"center\":[0.3,0.4],\"radius\":0.04,\"border\":0.01},\"source\":[0.36,0.33]}");
  const int hB = history_count(&end);
  CHECK(hB == hA + 1, "a shape is one history item (%d -> %d)", hA, hB);
  const int clone = p3m_add("{\"type\":\"ellipse\",\"geometry\":{\"center\":[0.6,0.4],\"radius\":[0.05,0.03],\"rotation\":20,\"border\":0.01},"
                            "\"source\":[0.66,0.35],\"algorithm\":\"clone\"}");
  const int path = p3m_add("{\"type\":\"path\",\"geometry\":{\"points\":[{\"corner\":[0.2,0.7]},{\"corner\":[0.3,0.7]},{\"corner\":[0.25,0.8]}]},"
                           "\"source\":[0.22,0.72]}");
  const int blur = p3m_add("{\"type\":\"brush\",\"geometry\":{\"stroke\":[[0.5,0.7],[0.55,0.72],[0.6,0.7]],\"size\":0.02,\"hardness\":0.8},"
                           "\"algorithm\":\"blur\"}");
  const int fill = p3m_add("{\"type\":\"circle\",\"geometry\":{\"center\":[0.8,0.7],\"radius\":0.05},\"algorithm\":\"fill\"}");
  const int hC = history_count(&end);
  CHECK(hC == hA + 5, "five shapes, five history items (%d -> %d)", hA, hC);
  st = p3m_state();
  o = json_node_get_object(st);
  struct { int id; const char *type, *algo; gboolean clone; } want[] = {
    { heal, "circle", "heal", TRUE }, { clone, "ellipse", "clone", TRUE }, { path, "path", "clone", TRUE },
    { blur, "brush", "blur", FALSE }, { fill, "circle", "fill", FALSE } };
  for(size_t k = 0; k < G_N_ELEMENTS(want); k++)
  {
    JsonObject *x = p3m_shape(st, want[k].id);
    CHECK(x != NULL, "shape %d listed", want[k].id);
    if(!x) continue;
    CHECK(!strcmp(json_object_get_string_member(x, "type"), want[k].type), "shape %d is a %s", want[k].id, want[k].type);
    CHECK(!strcmp(json_object_get_string_member(x, "algorithm"), want[k].algo), "shape %d does %s (%s)", want[k].id,
          want[k].algo, json_object_get_string_member(x, "algorithm"));
    CHECK(json_object_get_boolean_member(x, "clone") == want[k].clone, "shape %d clone flag", want[k].id);
    CHECK(json_object_get_int_member(x, "scale") == 0, "shape %d on scale 0", want[k].id);
    CHECK(json_object_get_int_member(x, "index") == (gint64)k, "shape %d is entry %zu", want[k].id, k);
    if(want[k].clone)
      CHECK(json_object_has_member(json_object_get_object_member(x, "geometry"), "source"), "shape %d has a source", want[k].id);
  }
  CHECK(json_object_get_int_member(o, "count") == 5, "count 5 (%ld)", (long)json_object_get_int_member(o, "count"));
  CHECK(!strcmp(json_object_get_string_member(o, "algorithm"), "fill"), "the module's algorithm is the last one asked");
  const int group = (int)json_object_get_int_member(o, "group");
  json_node_unref(st);
  // retouch shapes are flagged in the mask list and refused by the mask calls
  MUST(dtb_masks(&s));
  JsonNode *all = jparse_owned(s);
  JsonObject *mf = mask_find(all, heal);
  CHECK(mf && json_object_get_boolean_member(mf, "retouch"), "dtb_masks flags a retouch shape");
  json_node_unref(all);
  EXPECT(dtb_mask_delete(heal), DTB_E_ARG);
  EXPECT(dtb_mask_update(blur, "{\"points\":[]}"), DTB_E_ARG);
  EXPECT(dtb_mask_attach("exposure", 0, heal, NULL), DTB_E_ARG);
  uint8_t *five = render_full(&w1, &h1, NULL);
  double mean = 0;
  int md = (w0 == w1 && h0 == h1) ? max_diff(plain, five, (size_t)4 * w0 * h0, &mean) : 999;
  fprintf(stdout, "RESULT five shapes: render max diff %d mean %.3f against no retouch\n", md, mean);
  CHECK(md > 10, "the shapes change the picture");
  save_render(outdir, "p3m_shapes");

  // 2. source moved: picture changes, one item, undo restores
  int w2, h2;
  MUST(dtb_retouch_shape_set("retouch", 0, heal, "{\"source\":[0.2,0.25]}"));
  CHECK(history_count(&end) == hC + 1, "moving a source is one item");
  uint8_t *moved = render_full(&w2, &h2, NULL);
  md = max_diff(five, moved, (size_t)4 * w1 * h1, &mean);
  fprintf(stdout, "RESULT heal source moved: render max diff %d mean %.4f\n", md, mean);
  CHECK(md > 0, "moving the source changes the picture");
  save_render(outdir, "p3m_source");
  MUST(dtb_undo());
  uint8_t *back = render_full(&w2, &h2, NULL);
  md = max_diff(five, back, (size_t)4 * w1 * h1, &mean);
  CHECK(md == 0, "undo puts the source back (max %d)", md);
  MUST(dtb_redo());
  free(back);
  // target moved with the geometry
  MUST(dtb_retouch_shape_set("retouch", 0, clone, "{\"geometry\":{\"center\":[0.62,0.45],\"radius\":[0.05,0.03],\"rotation\":20,\"border\":0.01}}"));
  EXPECT(dtb_retouch_shape_set("retouch", 0, blur, "{\"source\":[0.1,0.1]}"), DTB_E_ARG);

  // 3. settings: algorithm swaps as ctrl+click, blur and fill settings of their shapes only
  const int hD = history_count(&end);
  EXPECT(dtb_retouch_shape_set("retouch", 0, heal, "{\"algorithm\":\"blur\"}"), DTB_E_ARG);
  EXPECT(dtb_retouch_shape_set("retouch", 0, heal, "{\"blur_radius\":20}"), DTB_E_ARG);
  EXPECT(dtb_retouch_shape_set("retouch", 0, fill, "{\"blur_radius\":20}"), DTB_E_ARG);
  EXPECT(dtb_retouch_shape_set("retouch", 0, blur, "{\"blur_radius\":500}"), DTB_E_RANGE);
  EXPECT(dtb_retouch_shape_set("retouch", 0, 424242, "{\"opacity\":0.5}"), DTB_E_NOT_FOUND);
  CHECK(history_count(&end) == hD, "refused changes leave no item");
  MUST(dtb_retouch_shape_set("retouch", 0, heal, "{\"algorithm\":\"clone\"}"));
  MUST(dtb_retouch_shape_set("retouch", 0, blur, "{\"blur_type\":\"bilateral\",\"blur_radius\":25}"));
  MUST(dtb_retouch_shape_set("retouch", 0, blur, "{\"blur_radius\":30}"));
  MUST(dtb_history_seal());
  MUST(dtb_retouch_shape_set("retouch", 0, fill, "{\"fill_mode\":\"color\",\"fill_color\":[0.9,0.1,0.1],\"fill_brightness\":0.1}"));
  MUST(dtb_history_seal());
  MUST(dtb_retouch_shape_set("retouch", 0, path, "{\"opacity\":0.01}"));
  const int hE = history_count(&end);
  fprintf(stdout, "RESULT settings: history %d -> %d\n", hD, hE);
  CHECK(hE == hD + 3, "algorithm + blur merged, fill, opacity: three items (%d -> %d)", hD, hE);
  st = p3m_state();
  JsonObject *x = p3m_shape(st, heal);
  CHECK(x && !strcmp(json_object_get_string_member(x, "algorithm"), "clone"), "heal became clone");
  x = p3m_shape(st, blur);
  CHECK(x && !strcmp(json_object_get_string_member(x, "blur_type"), "bilateral")
          && fabs(json_object_get_double_member(x, "blur_radius") - 30) < 1e-4, "blur settings of the blur shape");
  x = p3m_shape(st, fill);
  CHECK(x && !strcmp(json_object_get_string_member(x, "fill_mode"), "color"), "fill colour mode");
  x = p3m_shape(st, path);
  CHECK(x && fabs(json_object_get_double_member(x, "opacity") - 0.05) < 1e-6, "opacity clamped to 0.05 as desktop");
  o = json_node_get_object(st);
  CHECK(!strcmp(json_object_get_string_member(o, "fill_mode"), "color"), "the fill settings became the module's");
  json_node_unref(st);
  // selection: the module takes the selected shape's algorithm and settings
  MUST(dtb_retouch_select("retouch", 0, blur));
  st = p3m_state();
  o = json_node_get_object(st);
  CHECK(!strcmp(json_object_get_string_member(o, "algorithm"), "blur") && !strcmp(json_object_get_string_member(o, "blur_type"), "bilateral"),
        "selecting the blur shape makes blur the module's algorithm");
  json_node_unref(st);
  save_render(outdir, "p3m_settings");

  // 4. remove: one item, the entry goes, undo brings it back
  const int hF = history_count(&end);
  MUST(dtb_retouch_shape_remove("retouch", 0, clone));
  CHECK(history_count(&end) == hF + 1, "remove is one item");
  st = p3m_state();
  CHECK(p3m_shape(st, clone) == NULL && json_object_get_int_member(json_node_get_object(st), "count") == 4, "shape gone");
  x = p3m_shape(st, path);
  CHECK(x && json_object_get_int_member(x, "index") == 1, "the entries after it move up (rt_resynch_params)");
  json_node_unref(st);
  save_render(outdir, "p3m_removed");
  MUST(dtb_undo());
  st = p3m_state();
  CHECK(p3m_shape(st, clone) != NULL, "undo brings the shape back");
  json_node_unref(st);
  MUST(dtb_redo());

  // 5. XMP round trip: the same shapes and the same picture
  gchar *before = p3m_str();
  int wr, hr;
  uint8_t *ra = render_full(&wr, &hr, NULL);
  char *x1 = path_join(outdir, "p3m_roundtrip.xmp");
  MUST(dtb_xmp_write(x1));
  MUST(dtb_close());
  MUST(dtb_open(raw, x1));
  gchar *after = p3m_str();
  uint8_t *rb = render_full(&w2, &h2, NULL);
  md = (wr == w2 && hr == h2) ? max_diff(ra, rb, (size_t)4 * wr * hr, &mean) : 999;
  fprintf(stdout, "RESULT reopened: shapes %s, render max diff %d\n", strcmp(before, after) ? "differ" : "equal", md);
  CHECK(!strcmp(before, after), "shapes equal after reopening");
  CHECK(md == 0, "render equal after reopening (max %d)", md);
  g_free(before);
  g_free(after);
  free(ra);
  free(rb);
  free(x1);
  (void)group;

  // 6. wavelet scales: set as desktop's bar would (the app never does), then
  //    a shape edited, saved, reopened: the wavelet settings untouched
  MUST(dtb_params_set("retouch", 0, "{\"num_scales\":4,\"curr_scale\":2,\"merge_from_scale\":3,\"preview_levels[0]\":-2.5}"));
  MUST(dtb_history_seal());
  const int onscale = p3m_add("{\"type\":\"circle\",\"geometry\":{\"center\":[0.45,0.55],\"radius\":0.04},\"source\":[0.5,0.5],\"algorithm\":\"heal\"}");
  st = p3m_state();
  x = p3m_shape(st, onscale);
  CHECK(x && json_object_get_int_member(x, "scale") == 2, "a new shape goes on the current scale");
  json_node_unref(st);
  char *x2 = path_join(outdir, "p3m_wavelet_in.xmp");
  MUST(dtb_xmp_write(x2));
  MUST(dtb_close());
  MUST(dtb_open(raw, x2));
  MUST(dtb_retouch_shape_set("retouch", 0, onscale, "{\"source\":[0.4,0.45]}"));
  MUST(dtb_retouch_shape_set("retouch", 0, onscale, "{\"opacity\":0.7}"));
  const double ns = param_num("retouch", 0, "num_scales"), cs = param_num("retouch", 0, "curr_scale"),
               mf2 = param_num("retouch", 0, "merge_from_scale"), pl = param_num("retouch", 0, "preview_levels/0");
  fprintf(stdout, "RESULT wavelet after edits: num_scales %g curr_scale %g merge_from %g preview_levels[0] %g\n", ns, cs, mf2, pl);
  CHECK(ns == 4 && cs == 2 && mf2 == 3 && fabs(pl + 2.5) < 1e-6, "wavelet settings untouched");
  st = p3m_state();
  x = p3m_shape(st, heal);
  CHECK(x && json_object_get_int_member(x, "scale") == 0, "shapes of other scales keep their scale");
  json_node_unref(st);
  save_render(outdir, "p3m_wavelet");
  free(x2);
  MUST(dtb_close());

  // 7. colour look up table: the patch edits the app makes with params_set
  MUST(dtb_open(raw, xmp));
  const double L3 = param_num("colorchecker", 0, "source_L/3");
  MUST(dtb_params_set("colorchecker", 0, "{\"target_L[3]\":70,\"target_a[3]\":-30,\"target_b[3]\":40}"));
  MUST(dtb_history_seal());
  save_render(outdir, "p3m_lut_changed");
  // add a patch (desktop's shift+click on empty space); then reset and delete below
  MUST(dtb_params_set("colorchecker", 0, "{\"num_patches\":25,\"source_L[24]\":60,\"source_a[24]\":20,\"source_b[24]\":-10,"
                                         "\"target_L[24]\":50,\"target_a[24]\":30,\"target_b[24]\":-20}"));
  MUST(dtb_history_seal());
  save_render(outdir, "p3m_lut_added");
  fprintf(stdout, "RESULT lut: source_L[3] %g, patches %g\n", L3, param_num("colorchecker", 0, "num_patches"));
  CHECK(param_num("colorchecker", 0, "num_patches") == 25, "25 patches");
  // reset patch 3 (double-click): its target back to its source, as Lut.reset writes it
  int wl, hl;
  uint8_t *added = render_full(&wl, &hl, NULL);
  {
    char q[256];
    snprintf(q, sizeof(q), "{\"target_L[3]\":%.9g,\"target_a[3]\":%.9g,\"target_b[3]\":%.9g}",
             param_num("colorchecker", 0, "source_L/3"), param_num("colorchecker", 0, "source_a/3"),
             param_num("colorchecker", 0, "source_b/3"));
    MUST(dtb_params_set("colorchecker", 0, q));
  }
  MUST(dtb_history_seal());
  CHECK(param_num("colorchecker", 0, "target_L/3") == param_num("colorchecker", 0, "source_L/3"), "reset: target is source");
  uint8_t *reset = render_full(&wl, &hl, NULL);
  md = max_diff(added, reset, (size_t)4 * wl * hl, &mean);
  fprintf(stdout, "RESULT lut reset: render max diff %d mean %.4f\n", md, mean);
  CHECK(md > 0, "resetting a changed patch changes the picture");
  save_render(outdir, "p3m_lut_reset");
  // delete patch 0 (right-click): the patches after it move up one place, as Lut.deleteValues writes it
  {
    const int n = (int)param_num("colorchecker", 0, "num_patches");
    const double moved_L = param_num("colorchecker", 0, "target_L/1");
    GString *q = g_string_new("{");
    static const char *f[] = { "source_L", "source_a", "source_b", "target_L", "target_a", "target_b" };
    for(int k = 0; k < n - 1; k++)
      for(size_t j = 0; j < G_N_ELEMENTS(f); j++)
      {
        char path[32];
        snprintf(path, sizeof(path), "%s/%d", f[j], k + 1);
        g_string_append_printf(q, "\"%s[%d]\":%.9g,", f[j], k, param_num("colorchecker", 0, path));
      }
    g_string_append_printf(q, "\"num_patches\":%d}", n - 1);
    MUST(dtb_params_set("colorchecker", 0, q->str));
    g_string_free(q, TRUE);
    MUST(dtb_history_seal());
    CHECK(param_num("colorchecker", 0, "num_patches") == n - 1, "delete: one patch fewer");
    CHECK(param_num("colorchecker", 0, "target_L/0") == moved_L, "delete: patch 1 moved up to 0");
  }
  uint8_t *deleted = render_full(&wl, &hl, NULL);
  md = max_diff(reset, deleted, (size_t)4 * wl * hl, &mean);
  fprintf(stdout, "RESULT lut delete: render max diff %d mean %.4f, patches %g\n", md, mean,
          param_num("colorchecker", 0, "num_patches"));
  CHECK(md > 0, "deleting a patch changes the picture");
  save_render(outdir, "p3m_lut_deleted");
  free(added);
  free(reset);
  free(deleted);
  MUST(dtb_close());
  return g_fail;
}

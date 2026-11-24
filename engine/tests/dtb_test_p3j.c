// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3j (docs/design/engine-api.md 9), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3h.c.
 *
 *   copypaste <raw> <xmp> <outdir>  C29: copy and (selective) paste of the
 *                                   history. Photo A = the test photo with
 *                                   edits (two exposure changes, vignette, a
 *                                   new exposure instance with a drawn
 *                                   circle). Targets are byte copies of the
 *                                   RAW in <outdir> (the RAW itself is never
 *                                   written, its checksum is compared).
 *   1. copy all + overwrite onto B (no XMP): B's history is A's rows
 *      without the modules unsafe to copy, as desktop's verbatim row copy
 *      (common/history.c _history_copy_and_paste_on_image_overwrite);
 *      p3j_b_overwrite.xmp is rendered against darktable-cli (run_tests.sh)
 *   2. selective copy of every item + overwrite onto B2: the same pixels
 *      as A (unsafe modules included, so nothing differs)
 *   3. selective copy of the new instance + append onto C (the test XMP):
 *      C keeps its own modules and values, gets the instance and its circle
 *      (masks travel), and not the vignette
 *   4. paste onto the open photo: one undo step; saved and reopened, the
 *      history is the same
 *   5. refusals: nothing copied, a session open for paste_files, a target
 *      XMP with an unknown module (file unchanged), a read-only session,
 *      items that are not listed
 */

static char *p3j_copy_raw(const char *raw, const char *outdir, const char *name)
{
  gchar *base = g_path_get_basename(raw);
  const char *dot = strrchr(base, '.');
  gchar *fn = g_strdup_printf("%s%s", name, dot ? dot : "");
  char *dst = path_join(outdir, fn);
  gchar *data = NULL;
  gsize len = 0;
  if(!g_file_get_contents(raw, &data, &len, NULL) || !g_file_set_contents(dst, data, len, NULL))
  {
    fprintf(stdout, "FAIL cannot copy %s to %s\n", raw, dst);
    exit(1);
  }
  g_free(data);
  g_free(fn);
  g_free(base);
  return dst;
}

static char *p3j_md5(const char *path)
{
  gchar *data = NULL;
  gsize len = 0;
  if(!g_file_get_contents(path, &data, &len, NULL)) return g_strdup("(unreadable)");
  gchar *s = g_compute_checksum_for_data(G_CHECKSUM_MD5, (const guchar *)data, len);
  g_free(data);
  return s;
}

/* "op/priority/enabled" of every history item, one per line */
static GString *p3j_ops(gboolean (*keep)(const char *op, void *u), void *u)
{
  JsonNode *h = get_history();
  JsonArray *a = json_object_get_array_member(json_node_get_object(h), "items");
  GString *s = g_string_new(NULL);
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    const char *op = json_object_get_string_member(o, "op");
    if(keep && !keep(op, u)) continue;
    g_string_append_printf(s, "%s/%d/%d\n", op, (int)json_object_get_int_member(o, "priority"),
                           json_object_get_boolean_member(o, "enabled"));
  }
  json_node_unref(h);
  return s;
}

/* safe ops of the copy items list */
static gboolean p3j_safe(const char *op, void *u)
{
  return g_hash_table_contains((GHashTable *)u, op);
}

static char *p3j_paste_files(const char *raw, const char *xmp, const char *opts, int *code)
{
  gchar *t = g_strdup_printf("[{\"raw\": \"%s\", \"xmp\": \"%s\"}]", raw, xmp);
  char *j = NULL;
  const int r = dtb_history_paste_files(t, opts, &j);
  g_free(t);
  *code = -100;
  if(r != DTB_OK) return NULL;
  JsonNode *n = jparse_owned(j);
  JsonObject *o = json_array_get_object_element(json_node_get_array(n), 0);
  *code = (int)json_object_get_int_member(o, "code");
  char *e = g_strdup(json_object_get_null_member(o, "error") ? "" : json_object_get_string_member(o, "error"));
  json_node_unref(n);
  return e;
}

static int cmd_copypaste(const char *raw, const char *xmp, const char *outdir)
{
  gchar *raw_md5 = p3j_md5(raw);
  char *j = NULL;

  // the test XMP as it opens: C's reference
  MUST(dtb_open(raw, xmp));
  double c_ev0 = NAN;
  module_info("exposure", 0, &c_ev0, NULL, 0, NULL);
  GString *c_ops0 = p3j_ops(NULL, NULL);
  const gboolean c_had_vignette = strstr(c_ops0->str, "vignette/") != NULL;
  EXPECT(dtb_history_paste(NULL), DTB_E_STATE);  // nothing copied yet
  MUST(dtb_close());

  // A
  if(p3h_edits(raw, xmp, 0)) return g_fail + 1;
  MUST(dtb_history_seal());
  save_render(outdir, "p3j_a");
  MUST(dtb_history_copy_items(&j));
  fprintf(stdout, "RESULT copy items %s\n", j);
  JsonNode *items = jparse_owned(j);
  JsonArray *ia = json_object_get_array_member(json_node_get_object(items), "items");
  GHashTable *safe = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, NULL);
  GString *all_nums = g_string_new(NULL);
  int inst_num = -1, unsafe = 0;
  gboolean inst_mask = FALSE;
  for(guint i = 0; i < json_array_get_length(ia); i++)
  {
    JsonObject *o = json_array_get_object_element(ia, i);
    const char *op = json_object_get_string_member(o, "op");
    if(json_object_get_boolean_member(o, "safe")) g_hash_table_add(safe, g_strdup(op));
    else unsafe++;
    g_string_append_printf(all_nums, "%s%d", all_nums->len ? "," : "", (int)json_object_get_int_member(o, "num"));
    // the instance's newest entry: desktop's list (dt_history_get_items) groups by operation, instance name
    // and priority, so an instance whose name changed (an automatic preset's name) is listed twice
    if(!strcmp(op, "exposure") && json_object_get_int_member(o, "priority") == 1
       && json_object_get_int_member(o, "num") > inst_num)
    {
      inst_num = (int)json_object_get_int_member(o, "num");
      inst_mask = json_object_get_boolean_member(o, "mask");
    }
    if(!strcmp(op, "temperature") || !strcmp(op, "rawprepare"))
      CHECK(!json_object_get_boolean_member(o, "safe"), "%s is listed as safe to copy", op);
  }
  fprintf(stdout, "RESULT items %u, unsafe %d, new instance num %d\n", json_array_get_length(ia), unsafe, inst_num);
  CHECK(inst_num >= 0, "the new exposure instance is not listed");
  CHECK(inst_mask, "the new exposure instance is not listed with its mask");
  json_node_unref(items);
  // the rows of A that a plain copy takes
  GString *a_safe_ops = p3j_ops(p3j_safe, safe);
  gchar *a_hist = get_history_str();

  EXPECT(dtb_history_copy("{\"items\": [99999]}"), DTB_E_ARG);
  EXPECT(dtb_history_copy("{\"items\": []}"), DTB_E_ARG);
  MUST(dtb_history_copy(NULL));
  MUST(dtb_history_clipboard(&j));
  fprintf(stdout, "RESULT clipboard after copy: %.200s\n", j);
  CHECK(strstr(j, "\"parts\":false") || strstr(j, "\"parts\" : false"), "plain copy listed as parts");
  dtb_free(j);
  {
    gchar *h = get_history_str();
    CHECK(!strcmp(h, a_hist), "copy changed the history");
    g_free(h);
  }
  EXPECT(dtb_history_paste_files("[]", NULL, &j), DTB_E_STATE);  // a session is open
  MUST(dtb_close());

  // 1. copy all, overwrite onto B (no XMP: default edit first)
  char *b = p3j_copy_raw(raw, outdir, "p3j_b");
  char *bx = path_join(outdir, "p3j_b_overwrite.xmp");
  int code = 0;
  char *err = p3j_paste_files(b, bx, "{\"mode\": \"overwrite\"}", &code);
  CHECK(code == DTB_OK, "paste onto B: %d %s", code, err ? err : "");
  g_free(err);
  MUST(dtb_open(b, bx));
  {
    // the unsafe modules (and hidden ones) are B's own, as on desktop: left out of both sides
    GString *b_ops = p3j_ops(p3j_safe, safe);
    fprintf(stdout, "RESULT B after copy all + overwrite: %d items\n", history_count(NULL));
    CHECK(!strcmp(b_ops->str, a_safe_ops->str), "B's history is not A's without the unsafe modules:\nA:\n%sB:\n%s",
          a_safe_ops->str, b_ops->str);
    g_string_free(b_ops, TRUE);
  }
  save_render(outdir, "p3j_b_overwrite");
  MUST(dtb_close());

  // 2. selective copy of every item, overwrite onto B2: A's pixels
  MUST(dtb_open(raw, path_join(outdir, "p3j_a.xmp")));
  {
    gchar *o = g_strdup_printf("{\"items\": [%s]}", all_nums->str);
    MUST(dtb_history_copy(o));
    g_free(o);
  }
  MUST(dtb_close());
  char *b2 = p3j_copy_raw(raw, outdir, "p3j_b2");
  char *b2x = path_join(outdir, "p3j_b2_parts_overwrite.xmp");
  err = p3j_paste_files(b2, b2x, "{\"mode\": \"overwrite\"}", &code);
  CHECK(code == DTB_OK, "paste onto B2: %d %s", code, err ? err : "");
  g_free(err);
  MUST(dtb_open(b2, b2x));
  save_render(outdir, "p3j_b2_parts_overwrite");
  MUST(dtb_close());

  // 3. selective copy of the new instance, append onto C (the test XMP)
  MUST(dtb_open(raw, path_join(outdir, "p3j_a.xmp")));
  {
    gchar *o = g_strdup_printf("{\"items\": [%d]}", inst_num);
    MUST(dtb_history_copy(o));
    g_free(o);
  }
  MUST(dtb_close());
  char *c = p3j_copy_raw(raw, outdir, "p3j_c");
  char *cx = path_join(outdir, "p3j_c_append.xmp");
  {
    gchar *data = NULL;
    gsize len = 0;
    MUST(g_file_get_contents(xmp, &data, &len, NULL) ? DTB_OK : DTB_E_IO);
    MUST(g_file_set_contents(cx, data, len, NULL) ? DTB_OK : DTB_E_IO);
    g_free(data);
  }
  err = p3j_paste_files(c, cx, "{\"mode\": \"append\"}", &code);
  CHECK(code == DTB_OK, "paste onto C: %d %s", code, err ? err : "");
  g_free(err);
  MUST(dtb_open(c, cx));
  {
    double ev = NAN, ev1 = NAN;
    module_info("exposure", 0, &ev, NULL, 0, NULL);
    module_info("exposure", 1, &ev1, NULL, 0, NULL);
    fprintf(stdout, "RESULT C exposure 0: %.4f (was %.4f), new instance: %.4f\n", ev, c_ev0, ev1);
    CHECK(fabs(ev - c_ev0) < 1e-6, "append changed C's own exposure: %.6f, was %.6f", ev, c_ev0);
    CHECK(fabs(ev1 + 0.5) < 1e-4, "the pasted instance has exposure %.6f, expected -0.5", ev1);
    GString *ops = p3j_ops(NULL, NULL);
    CHECK(!strncmp(ops->str, c_ops0->str, c_ops0->len), "append did not keep C's history:\nbefore:\n%safter:\n%s",
          c_ops0->str, ops->str);
    CHECK(c_had_vignette || !strstr(ops->str, "vignette/"), "the vignette was pasted though not chosen");
    g_string_free(ops, TRUE);
    MUST(dtb_masks(&j));
    fprintf(stdout, "RESULT C masks %.300s\n", j);
    CHECK(strstr(j, "circle") && strstr(j, "\"exposure\""), "the circle did not travel with the instance");
    dtb_free(j);
  }
  save_render(outdir, "p3j_c_append");

  // 4. paste onto the open photo (C): a plain copy of A, appended (A's exposure differs from C's): one undo step
  MUST(dtb_close());
  MUST(dtb_open(raw, path_join(outdir, "p3j_a.xmp")));
  MUST(dtb_history_copy(NULL));
  MUST(dtb_close());
  MUST(dtb_open(c, cx));
  {
    gchar *h0 = get_history_str();
    const int n0 = history_count(NULL);
    MUST(dtb_history_paste("{\"mode\": \"append\"}"));
    const int n1 = history_count(NULL);
    fprintf(stdout, "RESULT open photo: %d items before the paste, %d after\n", n0, n1);
    CHECK(n1 > n0, "the paste added no history item");
    MUST(dtb_undo());
    gchar *h1 = get_history_str();
    CHECK(!strcmp(h0, h1), "one undo did not take the paste back");
    MUST(dtb_redo());
    gchar *h2 = get_history_str();
    char *cx2 = path_join(outdir, "p3j_c_open_paste.xmp");
    MUST(dtb_xmp_write(cx2));
    MUST(dtb_close());
    MUST(dtb_open(c, cx2));
    gchar *h3 = get_history_str();
    CHECK(!strcmp(h2, h3), "the pasted photo's XMP does not reopen the same history");
    g_free(h0); g_free(h1); g_free(h2); g_free(h3);
    free(cx2);
  }
  MUST(dtb_close());

  // 5. refusals: a target XMP with an unknown module is not rewritten
  char *d = p3j_copy_raw(raw, outdir, "p3j_d");
  char *dx = path_join(outdir, "p3j_d_unknown.xmp");
  {
    gchar *data = NULL;
    MUST(g_file_get_contents(xmp, &data, NULL, NULL) ? DTB_OK : DTB_E_IO);
    GString *s = g_string_new(data);
    const guint n = g_string_replace(s, "darktable:operation=\"exposure\"", "darktable:operation=\"dtbnosuchop\"", 0);
    CHECK(n > 0, "could not make an XMP with an unknown module");
    MUST(g_file_set_contents(dx, s->str, s->len, NULL) ? DTB_OK : DTB_E_IO);
    g_string_free(s, TRUE);
    g_free(data);
  }
  gchar *d_md5 = p3j_md5(dx);
  err = p3j_paste_files(d, dx, NULL, &code);
  fprintf(stdout, "RESULT unknown-module target: code %d (%s)\n", code, err ? err : "");
  CHECK(code == DTB_E_READ_ONLY, "a target with an unknown module returned %d", code);
  g_free(err);
  {
    gchar *m = p3j_md5(dx);
    CHECK(!strcmp(m, d_md5), "the refused XMP was changed");
    g_free(m);
  }
  MUST(dtb_open(d, dx));  // read-only session
  EXPECT(dtb_history_paste(NULL), DTB_E_READ_ONLY);
  EXPECT(dtb_history_copy(NULL), DTB_E_READ_ONLY);
  MUST(dtb_close());
  {
    gchar *m = p3j_md5(raw);
    CHECK(!strcmp(m, raw_md5), "the RAW changed");
    g_free(m);
  }
  EXPECT(dtb_history_paste_files("{}", NULL, &j), DTB_E_ARG);
  EXPECT(dtb_history_paste_files("[]", "{\"mode\": \"sideways\"}", &j), DTB_E_ARG);

  g_string_free(c_ops0, TRUE);
  g_string_free(a_safe_ops, TRUE);
  g_string_free(all_nums, TRUE);
  g_hash_table_destroy(safe);
  g_free(a_hist);
  g_free(d_md5);
  g_free(raw_md5);
  free(b); free(bx); free(b2); free(b2x); free(c); free(cx); free(d); free(dx);
  fprintf(stdout, "RESULT copypaste %s\n", g_fail ? "failed" : "passed");
  return g_fail;
}

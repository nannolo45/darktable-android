// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3l (PLAN.md 4.5, request R11), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3j.c.
 *
 *   shared <raw> <xmp> <outdir>  C31: styles and presets shared through
 *                                LightPhotoLibrary. DTB_TEST_R11 = the shared
 *                                hash vectors (docs/design/R11-hash-vectors.json).
 *   1. every vector: dtb_shared_hash gives its canonical text and hash
 *   2. the snapshot of a fresh library: no built-in preset, no preset of a
 *      lib module, every style; every listed hash is the hash of its body
 *   3. the preset and the style vector applied: usable, listed by the
 *      engine's own presets and styles lists, and the snapshot gives the
 *      vector's hash back (the body's REAL, BLOB, NULL and non-ASCII values
 *      survive the round trip; the extra column is ignored)
 *   4. applied again with another description: replaced, not added
 *   5. a newer module version, a newer blend version, an unknown operation:
 *      usable false and nothing written (an existing style is unchanged); a
 *      built-in preset's key and a layout body: refused
 *   6. a user preset stored from the open photo and a style created from it
 *      appear in the snapshot; a body lacking columns is stored with NULLs
 *   7. delete: gone from the snapshot and the engine's lists; again:
 *      DTB_E_NOT_FOUND
 */

static JsonNode *p3l_snapshot(void)
{
  char *s = NULL;
  MUST(dtb_shared_snapshot(&s));
  return jparse_owned(s);
}

/* the snapshot entry of (kind, operation, op_version, name), NULL if none;
 * *count = how many entries have that key */
static JsonObject *p3l_find(JsonNode *snap, const char *kind, const char *op, int opv, const char *name, int *count)
{
  JsonArray *a = json_node_get_array(snap);
  JsonObject *hit = NULL;
  int n = 0;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(strcmp(json_object_get_string_member(o, "kind"), kind) || strcmp(json_object_get_string_member(o, "name"), name))
      continue;
    if(!strcmp(kind, "preset")
       && (strcmp(json_object_get_string_member(o, "operation"), op) || json_object_get_int_member(o, "op_version") != opv))
      continue;
    hit = o;
    n++;
  }
  if(count) *count = n;
  return hit;
}

/* a deep copy (json_node_copy shares the object or array it holds) */
static JsonNode *p3l_copy(JsonNode *n)
{
  gchar *t = json_to_string(n, FALSE);
  JsonNode *c = json_from_string(t, NULL);
  g_free(t);
  return c;
}

static char *p3l_hash_of(JsonNode *body)
{
  gchar *t = json_to_string(body, FALSE);
  char *s = NULL;
  MUST(dtb_shared_hash(t, &s));
  g_free(t);
  JsonNode *n = jparse_owned(s);
  char *h = g_strdup(json_object_get_string_member(json_node_get_object(n), "hash"));
  json_node_unref(n);
  return h;
}

/* apply a body given as text -> usable (exits on a failed call) */
static gboolean p3l_apply(const char *body)
{
  char *s = NULL;
  MUST(dtb_shared_apply(body, &s));
  JsonNode *n = jparse_owned(s);
  const gboolean u = json_object_get_boolean_member(json_node_get_object(n), "usable");
  json_node_unref(n);
  return u;
}

static gboolean p3l_listed_preset(const char *op, const char *name, gboolean *builtin)
{
  char *s = NULL;
  if(dtb_presets_list(op, 0, &s) != DTB_OK) return FALSE;
  JsonNode *n = jparse_owned(s);
  JsonArray *a = json_node_get_array(n);
  gboolean found = FALSE;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(!strcmp(json_object_get_string_member(o, "name"), name))
    {
      found = TRUE;
      if(builtin) *builtin = json_object_get_boolean_member(o, "builtin");
    }
  }
  json_node_unref(n);
  return found;
}

static int p3l_style_items(const char *name)
{
  char *s = NULL;
  MUST(dtb_styles_list(&s));
  JsonNode *n = jparse_owned(s);
  JsonArray *a = json_node_get_array(n);
  int items = -1;
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    JsonObject *o = json_array_get_object_element(a, i);
    if(!strcmp(json_object_get_string_member(o, "name"), name))
      items = (int)json_array_get_length(json_object_get_array_member(o, "items"));
  }
  json_node_unref(n);
  return items;
}

static int cmd_shared(const char *raw, const char *xmp, const char *outdir)
{
  const char *vpath = getenv("DTB_TEST_R11");
  CHECK(vpath && *vpath, "DTB_TEST_R11 (the hash vectors) not set");
  if(!vpath || !*vpath) return 1;
  char *vtext = read_file(vpath);
  if(!vtext)
  {
    fprintf(stdout, "FAIL cannot read %s\n", vpath);
    return 1;
  }
  JsonNode *vroot = jparse_owned(vtext);
  JsonArray *vectors = json_object_get_array_member(json_node_get_object(vroot), "vectors");
  JsonNode *preset_body = NULL, *style_body = NULL, *layout_body = NULL;
  const char *preset_hash = NULL, *style_hash = NULL;

  // 1. the shared vectors
  int vok = 0;
  for(guint i = 0; i < json_array_get_length(vectors); i++)
  {
    JsonObject *v = json_array_get_object_element(vectors, i);
    JsonNode *body = json_object_get_member(v, "body");
    gchar *t = json_to_string(body, FALSE);
    char *s = NULL;
    MUST(dtb_shared_hash(t, &s));
    g_free(t);
    JsonNode *n = jparse_owned(s);
    JsonObject *o = json_node_get_object(n);
    const gboolean okc = !strcmp(json_object_get_string_member(o, "canonical"), json_object_get_string_member(v, "canonical"));
    const gboolean okh = !strcmp(json_object_get_string_member(o, "hash"), json_object_get_string_member(v, "hash"));
    const char *kind = json_object_get_string_member(json_node_get_object(body), "kind");
    CHECK(okc, "vector %u (%s): canonical text differs: %s", i, kind, json_object_get_string_member(o, "canonical"));
    CHECK(okh, "vector %u (%s): hash %s, expected %s", i, kind, json_object_get_string_member(o, "hash"),
          json_object_get_string_member(v, "hash"));
    vok += okc && okh;
    json_node_unref(n);
    if(!strcmp(kind, "preset")) { preset_body = body; preset_hash = json_object_get_string_member(v, "hash"); }
    else if(!strcmp(kind, "style")) { style_body = body; style_hash = json_object_get_string_member(v, "hash"); }
    else if(!strcmp(kind, "layout")) layout_body = body;
  }
  fprintf(stdout, "RESULT vectors %d of %u reproduced\n", vok, json_array_get_length(vectors));
  CHECK(preset_body && style_body && layout_body, "the vectors lack a preset, a style or a layout");
  if(!preset_body || !style_body || !layout_body) return 1;

  // 2. a fresh library
  MUST(dtb_open(raw, xmp));
  JsonNode *snap = p3l_snapshot();
  JsonArray *sa = json_node_get_array(snap);
  int presets0 = 0, styles0 = 0, bad_hash = 0, builtin_listed = 0, lib_listed = 0;
  for(guint i = 0; i < json_array_get_length(sa); i++)
  {
    JsonObject *o = json_array_get_object_element(sa, i);
    const char *kind = json_object_get_string_member(o, "kind");
    char *h = p3l_hash_of(json_object_get_member(o, "body"));
    if(strcmp(h, json_object_get_string_member(o, "hash"))) bad_hash++;
    g_free(h);
    if(!strcmp(kind, "style")) { styles0++; continue; }
    presets0++;
    const char *op = json_object_get_string_member(o, "operation");
    gboolean builtin = FALSE;
    if(!p3l_listed_preset(op, json_object_get_string_member(o, "name"), &builtin)) lib_listed++;
    else if(builtin) builtin_listed++;
  }
  char *sl = NULL;
  MUST(dtb_styles_list(&sl));
  JsonNode *sln = jparse_owned(sl);
  const int engine_styles = (int)json_array_get_length(json_node_get_array(sln));
  json_node_unref(sln);
  fprintf(stdout, "RESULT fresh snapshot: %d presets, %d styles (engine lists %d styles)\n", presets0, styles0, engine_styles);
  CHECK(bad_hash == 0, "%d snapshot entries carry a hash that is not their body's", bad_hash);
  CHECK(builtin_listed == 0, "%d built-in presets in the snapshot", builtin_listed);
  CHECK(lib_listed == 0, "%d presets of modules the engine does not list (lib modules?) in the snapshot", lib_listed);
  CHECK(styles0 == engine_styles, "the snapshot has %d styles, the engine %d", styles0, engine_styles);
  json_node_unref(snap);

  // 3. the vectors applied
  gchar *pb = json_to_string(preset_body, FALSE), *stb = json_to_string(style_body, FALSE);
  CHECK(p3l_apply(pb), "the preset vector is not usable [%s]", dtb_last_error());
  CHECK(p3l_apply(stb), "the style vector is not usable [%s]", dtb_last_error());
  JsonObject *prow = json_object_get_object_member(json_node_get_object(preset_body), "row");
  const char *pop = json_object_get_string_member(prow, "operation"), *pname = json_object_get_string_member(prow, "name");
  const int popv = (int)json_object_get_int_member(prow, "op_version");
  const char *sname = json_object_get_string_member(json_object_get_object_member(json_node_get_object(style_body), "style"), "name");
  gboolean builtin = TRUE;
  CHECK(p3l_listed_preset(pop, pname, &builtin) && !builtin, "the applied preset is not listed as a user preset of %s", pop);
  CHECK(p3l_style_items(sname) == 2, "the applied style is not listed with its 2 items (%d)", p3l_style_items(sname));
  snap = p3l_snapshot();
  int cnt = 0;
  JsonObject *pe = p3l_find(snap, "preset", pop, popv, pname, &cnt);
  CHECK(pe && cnt == 1, "the applied preset is in the snapshot %d times", cnt);
  CHECK(pe && !strcmp(json_object_get_string_member(pe, "hash"), preset_hash), "preset round trip: hash %s, vector %s",
        pe ? json_object_get_string_member(pe, "hash") : "-", preset_hash);
  CHECK(pe && !json_object_has_member(json_object_get_object_member(json_object_get_object_member(pe, "body"), "row"), "writeprotect"),
        "the preset body carries writeprotect");
  CHECK(pe && !json_object_has_member(json_object_get_object_member(json_object_get_object_member(pe, "body"), "row"), "extra_column_ignored"),
        "a body column the table does not have was kept");
  JsonObject *se = p3l_find(snap, "style", "", 0, sname, &cnt);
  CHECK(se && cnt == 1, "the applied style is in the snapshot %d times", cnt);
  CHECK(se && !strcmp(json_object_get_string_member(se, "hash"), style_hash), "style round trip: hash %s, vector %s",
        se ? json_object_get_string_member(se, "hash") : "-", style_hash);
  fprintf(stdout, "RESULT round trip: preset %s, style %s\n", pe ? json_object_get_string_member(pe, "hash") : "-",
          se ? json_object_get_string_member(se, "hash") : "-");
  json_node_unref(snap);

  // 4. replaced, not added
  {
    JsonNode *b2 = p3l_copy(preset_body);
    json_object_set_string_member(json_object_get_object_member(json_node_get_object(b2), "row"), "description", "changed");
    gchar *t = json_to_string(b2, FALSE);
    CHECK(p3l_apply(t), "changed preset not usable");
    g_free(t);
    JsonNode *s2 = p3l_copy(style_body);
    JsonArray *items = json_object_get_array_member(json_node_get_object(s2), "items");
    json_array_remove_element(items, 0);
    t = json_to_string(s2, FALSE);
    CHECK(p3l_apply(t), "changed style not usable");
    g_free(t);
    snap = p3l_snapshot();
    pe = p3l_find(snap, "preset", pop, popv, pname, &cnt);
    CHECK(pe && cnt == 1 && strcmp(json_object_get_string_member(pe, "hash"), preset_hash), "the changed preset did not replace the old one (%d)", cnt);
    se = p3l_find(snap, "style", "", 0, sname, &cnt);
    CHECK(se && cnt == 1 && p3l_style_items(sname) == 1, "the changed style did not replace the old one (%d, %d items)", cnt, p3l_style_items(sname));
    json_node_unref(snap);
    json_node_unref(b2);
    json_node_unref(s2);
    CHECK(p3l_apply(stb), "style vector not usable again");
  }

  // 5. refused
  {
    JsonNode *b = p3l_copy(preset_body);
    JsonObject *row = json_object_get_object_member(json_node_get_object(b), "row");
    json_object_set_string_member(row, "name", "p3l newer module");
    json_object_set_int_member(row, "op_version", 999);
    gchar *t = json_to_string(b, FALSE);
    CHECK(!p3l_apply(t), "a preset of a newer module version was usable");
    g_free(t);
    json_object_set_int_member(row, "op_version", popv);
    json_object_set_int_member(row, "blendop_version", 999);
    json_object_set_string_member(row, "name", "p3l newer blend");
    t = json_to_string(b, FALSE);
    CHECK(!p3l_apply(t), "a preset of a newer blend version was usable");
    g_free(t);
    json_object_set_int_member(row, "blendop_version", 14);
    json_object_set_string_member(row, "operation", "p3l_no_such_module");
    t = json_to_string(b, FALSE);
    CHECK(!p3l_apply(t), "a preset of an unknown module was usable");
    g_free(t);
    json_node_unref(b);
    // a style with one item of a newer module version: the stored style of that name stays as it was
    JsonNode *s = p3l_copy(style_body);
    JsonObject *it = json_array_get_object_element(json_object_get_array_member(json_node_get_object(s), "items"), 0);
    json_object_set_int_member(it, "module", 999);
    t = json_to_string(s, FALSE);
    CHECK(!p3l_apply(t), "a style with a newer module version was usable");
    g_free(t);
    json_node_unref(s);
    snap = p3l_snapshot();
    se = p3l_find(snap, "style", "", 0, sname, &cnt);
    CHECK(se && !strcmp(json_object_get_string_member(se, "hash"), style_hash), "a refused style changed the stored one");
    CHECK(!p3l_find(snap, "preset", pop, 999, "p3l newer module", NULL) && !p3l_find(snap, "preset", pop, popv, "p3l newer blend", NULL),
          "a refused preset was stored");
    json_node_unref(snap);
    gchar *lb = json_to_string(layout_body, FALSE);
    char *out = NULL;
    EXPECT(dtb_shared_apply(lb, &out), DTB_E_ARG);
    g_free(lb);
    EXPECT(dtb_shared_apply("{\"kind\":\"preset\",\"row\":{\"name\":\"x\",\"operation\":\"exposure\",\"op_version\":1,\"op_params\":\"zz\"}}", &out), DTB_E_ARG);
    EXPECT(dtb_shared_apply("not json", &out), DTB_E_ARG);
    // a built-in preset's key
    char *pl = NULL;
    MUST(dtb_presets_list("exposure", 0, &pl));
    JsonNode *pn = jparse_owned(pl);
    JsonArray *pa = json_node_get_array(pn);
    for(guint i = 0; i < json_array_get_length(pa); i++)
    {
      JsonObject *o = json_array_get_object_element(pa, i);
      if(!json_object_get_boolean_member(o, "builtin")) continue;
      gchar *bt = g_strdup_printf("{\"kind\":\"preset\",\"row\":{\"name\":\"%s\",\"operation\":\"exposure\",\"op_version\":%d,\"op_params\":\"00\"}}",
                                  json_object_get_string_member(o, "name"), (int)json_object_get_int_member(o, "op_version"));
      EXPECT(dtb_shared_apply(bt, &out), DTB_E_ARG);
      fprintf(stdout, "RESULT built-in key refused: '%s'\n", json_object_get_string_member(o, "name"));
      g_free(bt);
      break;
    }
    json_node_unref(pn);
  }

  // 6. made on this device; a short body
  MUST(dtb_param_set("exposure", 0, "exposure", "0.61"));
  MUST(dtb_history_seal());
  MUST(dtb_preset_store("exposure", 0, "p3l user preset", "made by C31", 1));
  MUST(dtb_style_create("p3l user style", "made by C31", NULL, 1));
  CHECK(p3l_apply("{\"kind\":\"preset\",\"row\":{\"name\":\"p3l short\",\"operation\":\"exposure\",\"op_version\":1,\"op_params\":\"0000\"}}"),
        "a short preset body was not usable");
  snap = p3l_snapshot();
  int exposure_version = -1;
  {
    char *s = NULL;
    MUST(dtb_presets_list("exposure", 0, &s));
    JsonNode *n = jparse_owned(s);
    JsonArray *a = json_node_get_array(n);
    for(guint i = 0; i < json_array_get_length(a); i++)
    {
      JsonObject *o = json_array_get_object_element(a, i);
      if(!strcmp(json_object_get_string_member(o, "name"), "p3l user preset")) exposure_version = (int)json_object_get_int_member(o, "op_version");
    }
    json_node_unref(n);
  }
  CHECK(p3l_find(snap, "preset", "exposure", exposure_version, "p3l user preset", NULL), "a preset stored from the photo is not in the snapshot");
  CHECK(p3l_find(snap, "style", "", 0, "p3l user style", NULL), "a style created from the photo is not in the snapshot");
  JsonObject *sh = p3l_find(snap, "preset", "exposure", 1, "p3l short", NULL);
  CHECK(sh && JSON_NODE_HOLDS_NULL(json_object_get_member(json_object_get_object_member(json_object_get_object_member(sh, "body"), "row"), "iso_max")),
        "a column the body lacked is not NULL");
  fprintf(stdout, "RESULT snapshot after this device's own: %u items\n", json_array_get_length(json_node_get_array(snap)));
  json_node_unref(snap);

  // 7. delete
  MUST(dtb_shared_delete("preset", pop, popv, pname));
  MUST(dtb_shared_delete("style", NULL, 0, sname));
  MUST(dtb_shared_delete("preset", "exposure", 1, "p3l short"));
  EXPECT(dtb_shared_delete("preset", pop, popv, pname), DTB_E_NOT_FOUND);
  EXPECT(dtb_shared_delete("style", NULL, 0, sname), DTB_E_NOT_FOUND);
  EXPECT(dtb_shared_delete("layout", "modulegroups", 1, "x"), DTB_E_ARG);
  snap = p3l_snapshot();
  CHECK(!p3l_find(snap, "preset", pop, popv, pname, NULL) && !p3l_find(snap, "style", "", 0, sname, NULL), "deleted items still in the snapshot");
  CHECK(!p3l_listed_preset(pop, pname, NULL) && p3l_style_items(sname) < 0, "deleted items still listed by the engine");
  json_node_unref(snap);
  g_free(pb);
  g_free(stb);
  MUST(dtb_close());
  json_node_unref(vroot);
  (void)outdir;
  fprintf(stdout, "RESULT shared %s\n", g_fail ? "failed" : "ok");
  return g_fail;
}

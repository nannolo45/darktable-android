// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3d stage 2a (docs/design/engine-api.md 6.9), part of the test
 * driver: dtb_test.c includes this file after dtb_test_p1b2.c, so the helpers
 * of both are used as they are.
 *
 *   masktree <raw> <xmp> <outdir>  C26: one history item per shape and per
 *                                  brush session, cancel, tree editing,
 *                                  parametric elements, XMP round trip
 */

static int p3d_add(const char *op, int prio, const char *req)
{
  char *s = NULL;
  MUST(dtb_mask_add(op, prio, req, &s));
  JsonNode *n = jparse_owned(s);
  const int id = json_object_get_int_member(json_node_get_object(n), "id");
  json_node_unref(n);
  return id;
}

/* the members of group g as listed by dtb_masks, bottom first, into ids (at most 16) */
static int p3d_members(const int g, int *ids)
{
  char *s = NULL;
  MUST(dtb_masks(&s));
  JsonNode *all = jparse_owned(s);
  JsonObject *o = mask_find(all, g);
  int n = 0;
  if(o)
  {
    JsonArray *m = json_object_get_array_member(json_object_get_object_member(o, "geometry"), "members");
    for(guint i = 0; i < json_array_get_length(m) && n < 16; i++)
      ids[n++] = json_object_get_int_member(json_array_get_object_element(m, i), "id");
  }
  json_node_unref(all);
  return o ? n : -1;
}

static gboolean p3d_exists(const int id)
{
  char *s = NULL;
  MUST(dtb_masks(&s));
  JsonNode *all = jparse_owned(s);
  const gboolean found = mask_find(all, id) != NULL;
  json_node_unref(all);
  return found;
}

static int p3d_root(const char *op, int prio)
{
  JsonNode *k = NULL;
  JsonObject *b = blend_of(&k, op, prio);
  const int g = json_object_get_null_member(b, "mask_id") ? -1 : (int)json_object_get_int_member(b, "mask_id");
  json_node_unref(k);
  return g;
}

static int cmd_masktree(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  MUST(dtb_param_set("exposure", 0, "exposure", "1.5"));
  MUST(dtb_history_seal());
  int end = 0;

  // 1. one shape = one history item, one undo step
  const int h0 = history_count(&end);
  const int c = p3d_add("exposure", 0, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.3,0.35],\"radius\":0.12,\"border\":0.05}}");
  const int h1 = history_count(&end);
  fprintf(stdout, "RESULT circle added in one call: history %d -> %d\n", h0, h1);
  CHECK(h1 == h0 + 1, "a shape is one history item (%d -> %d)", h0, h1);
  MUST(dtb_undo());
  const int hu = history_count(&end);
  CHECK(hu == h0 && !p3d_exists(c), "one undo removes the shape (history %d, form %s)", hu, p3d_exists(c) ? "kept" : "gone");
  MUST(dtb_redo());
  CHECK(history_count(&end) == h1 && p3d_exists(c), "redo brings the shape back");

  // 2. a brush session of three strokes = one item, one undo step
  MUST(dtb_history_group_begin());
  EXPECT(dtb_history_group_begin(), DTB_E_STATE);
  int strokes[3];
  for(int k = 0; k < 3; k++)
  {
    gchar *req = g_strdup_printf("{\"type\":\"brush\",\"geometry\":{\"stroke\":[[0.55,%g],[0.6,%g],[0.65,%g],[0.7,%g]],"
                                 "\"size\":0.02,\"hardness\":0.7}}",
                                 0.5 + 0.1 * k, 0.52 + 0.1 * k, 0.55 + 0.1 * k, 0.53 + 0.1 * k);
    strokes[k] = p3d_add("exposure", 0, req);
    g_free(req);
    MUST(dtb_history_seal());  // the app's seals inside a group change nothing
  }
  MUST(dtb_history_group_end(1));
  const int h2 = history_count(&end);
  fprintf(stdout, "RESULT brush session of 3 strokes: history %d -> %d\n", h1, h2);
  CHECK(h2 == h1 + 1, "a brush session is one history item (%d -> %d)", h1, h2);
  MUST(dtb_undo());
  CHECK(history_count(&end) == h1 && !p3d_exists(strokes[0]) && !p3d_exists(strokes[2]),
        "one undo removes the whole brush session");
  MUST(dtb_redo());
  CHECK(history_count(&end) == h2 && p3d_exists(strokes[1]), "redo brings the session back");
  EXPECT(dtb_history_group_end(1), DTB_E_STATE);

  // 3. cancel leaves no item; an empty group no undo step
  char *us = NULL;
  MUST(dtb_history_group_begin());
  const int x = p3d_add("exposure", 0, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.6,0.6],\"radius\":0.1}}");
  MUST(dtb_history_group_end(0));
  const int h3 = history_count(&end);
  CHECK(h3 == h2 && !p3d_exists(x), "cancel leaves no item (history %d, form %s)", h3, p3d_exists(x) ? "kept" : "gone");
  MUST(dtb_history_group_begin());
  MUST(dtb_history_group_end(1));
  MUST(dtb_undo());
  CHECK(!p3d_exists(strokes[0]) && p3d_exists(c), "an empty group made an undo step");
  MUST(dtb_redo());
  MUST(dtb_undo_state(&us));
  fprintf(stdout, "RESULT cancel: history %d (before %d); undo state %s\n", h3, h2, us);
  dtb_free(us);

  // 4. order matters for difference: a second instance, a big and a small circle
  int np = 0;
  MUST(dtb_instance_new("exposure", 0, &np));
  MUST(dtb_param_set("exposure", np, "exposure", "-2.0"));
  MUST(dtb_history_seal());
  const int big = p3d_add("exposure", np, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.5,0.5],\"radius\":0.3,\"border\":0.02}}");
  const int small = p3d_add("exposure", np, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.5,0.5],\"radius\":0.1,\"border\":0.02}}");
  const int root = p3d_root("exposure", np);
  MUST(dtb_mask_group_set(root, "{\"operator\":\"difference\"}"));
  int ids[16];
  int n = p3d_members(root, ids);
  CHECK(n == 2 && ids[0] == big && ids[1] == small, "members in the order added (bottom first)");
  int wa, ha, wb, hb;
  uint8_t *ra = render_full(&wa, &ha, NULL);
  const int h4 = history_count(&end);
  MUST(dtb_mask_member_move(root, small, root, 0));
  const int h5 = history_count(&end);
  n = p3d_members(root, ids);
  CHECK(n == 2 && ids[0] == small && ids[1] == big, "move to the bottom");
  CHECK(h5 == h4 + 1, "a move is one history item");
  uint8_t *rb = render_full(&wb, &hb, NULL);
  double mean = 0;
  const int md = (wa == wb && ha == hb) ? max_diff(ra, rb, (size_t)4 * wa * ha, &mean) : 999;
  fprintf(stdout, "RESULT difference of two circles, order swapped: render max diff %d, mean %.3f\n", md, mean);
  CHECK(md > 20, "difference does not change with the order of its members (max %d)", md);
  save_render(outdir, "masktree_difference");
  MUST(dtb_undo());
  n = p3d_members(root, ids);
  CHECK(n == 2 && ids[0] == big, "undo of a move");
  MUST(dtb_redo());
  EXPECT(dtb_mask_member_move(root, small, root, 5), DTB_E_RANGE);
  EXPECT(dtb_mask_member_move(root, 424242, root, 0), DTB_E_NOT_FOUND);
  EXPECT(dtb_mask_member_move(root, small, big, 0), DTB_E_ARG);  // not a group
  free(ra);
  free(rb);

  // 5. wrap in group, group together, move between groups, member settings, remove
  char *s = NULL;
  const int third = p3d_add("exposure", np, "{\"type\":\"ellipse\",\"geometry\":{\"center\":[0.3,0.7],\"radius\":[0.1,0.05]}}");
  const int h6 = history_count(&end);
  EXPECT(dtb_mask_group_wrap(root, "[424242]", NULL, NULL), DTB_E_NOT_FOUND);
  gchar *one = g_strdup_printf("[%d]", big);
  MUST(dtb_mask_group_wrap(root, one, NULL, &s));
  g_free(one);
  JsonNode *wn = jparse_owned(s);
  const int w = json_object_get_int_member(json_node_get_object(wn), "id");
  json_node_unref(wn);
  n = p3d_members(root, ids);
  // small, big, third -> small, [big], third: the new group where big was
  CHECK(n == 3 && ids[0] == small && ids[1] == w && ids[2] == third, "wrap in group keeps the place");
  CHECK(p3d_members(w, ids) == 1 && ids[0] == big, "the wrapped member is in the new group");
  CHECK(history_count(&end) == h6 + 1, "wrap is one history item");
  gchar *two = g_strdup_printf("[%d,%d]", third, small);
  s = NULL;
  MUST(dtb_mask_group_wrap(root, two, "minimum", &s));
  g_free(two);
  JsonNode *gn = jparse_owned(s);
  const int gt = json_object_get_int_member(json_node_get_object(gn), "id");
  json_node_unref(gn);
  n = p3d_members(root, ids);
  CHECK(n == 2 && ids[0] == w && ids[1] == gt, "group together where the topmost was");
  n = p3d_members(gt, ids);
  CHECK(n == 2 && ids[0] == small && ids[1] == third, "group together keeps the members' order");
  // a member into another group, its settings in any group, removal from any group
  MUST(dtb_mask_member_update(gt, third, "{\"inverse\":true,\"opacity\":0.4}"));
  MUST(dtb_mask_member_move(gt, third, w, -1));
  n = p3d_members(w, ids);
  CHECK(n == 2 && ids[1] == third, "move into another group, on top");
  s = NULL;
  MUST(dtb_masks(&s));
  JsonNode *all = jparse_owned(s);
  JsonArray *wm = json_object_get_array_member(json_object_get_object_member(mask_find(all, w), "geometry"), "members");
  JsonObject *tm = json_array_get_object_element(wm, 1);
  CHECK(json_object_get_boolean_member(tm, "inverse") && fabs(json_object_get_double_member(tm, "opacity") - 0.4) < 1e-6,
        "a moved member keeps its settings");
  json_node_unref(all);
  const int h7 = history_count(&end);
  MUST(dtb_mask_member_remove(w, third));
  CHECK(history_count(&end) == h7 + 1 && p3d_members(w, ids) == 1 && p3d_exists(third), "remove from a nested group");
  // refusals: cycles, settings of a group member, nesting
  EXPECT(dtb_mask_member_move(root, gt, gt, 0), DTB_E_ARG);
  gchar *inner = g_strdup_printf("[%d]", small);
  s = NULL;
  MUST(dtb_mask_group_wrap(gt, inner, NULL, &s));
  g_free(inner);
  JsonNode *in = jparse_owned(s);
  const int deep = json_object_get_int_member(json_node_get_object(in), "id");
  json_node_unref(in);
  EXPECT(dtb_mask_member_move(root, gt, deep, 0), DTB_E_ARG);
  EXPECT(dtb_mask_member_update(root, gt, "{\"opacity\":0.5}"), DTB_E_ARG);
  MUST(dtb_mask_member_update(root, gt, "{\"disabled\":false}"));
  EXPECT(dtb_mask_group_wrap(root, "[]", NULL, &s), DTB_E_ARG);
  EXPECT(dtb_mask_group_wrap(root, "[1]", "mean", &s), DTB_E_ARG);
  {
    // nest as deep as darktable walks, then one more is refused
    int cur = deep, made = 0, rr = DTB_OK;
    while(made < 12)
    {
      gchar *l = g_strdup_printf("[%d]", small);
      s = NULL;
      rr = dtb_mask_group_wrap(cur, l, NULL, &s);
      g_free(l);
      if(rr) break;
      JsonNode *nn = jparse_owned(s);
      cur = json_object_get_int_member(json_node_get_object(nn), "id");
      json_node_unref(nn);
      made++;
    }
    fprintf(stdout, "RESULT nesting: %d more groups made below depth 3, then %d\n", made, rr);
    CHECK(rr == DTB_E_ARG && made < 12, "nesting has no limit");
    for(int k = 0; k < made; k++) MUST(dtb_undo());
  }
  save_render(outdir, "masktree_groups");

  // 6. a parametric element in the tree (deliverable 3)
  const int h8 = history_count(&end);
  const int pe = p3d_add("exposure", np, "{\"type\":\"parametric\",\"group\":0,\"geometry\":{\"channel\":\"g\",\"in\":[0.2,0.3,0.7,0.8]},"
                                         "\"member\":{\"opacity\":0.8}}");
  CHECK(history_count(&end) == h8 + 1, "a parametric element is one history item");
  s = NULL;
  MUST(dtb_masks(&s));
  all = jparse_owned(s);
  JsonObject *po = mask_find(all, pe);
  JsonObject *pg = po ? json_object_get_object_member(po, "geometry") : NULL;
  JsonArray *pin = pg ? json_object_get_array_member(pg, "in") : NULL;
  fprintf(stdout, "RESULT parametric element %d: type %s, colorspace %s, channel %s, in [%g %g %g %g], in_active %d\n", pe,
          po ? json_object_get_string_member(po, "type") : "?", pg ? json_object_get_string_member(pg, "colorspace") : "?",
          pg ? json_object_get_string_member(pg, "channel") : "?", pin ? json_array_get_double_element(pin, 0) : -1,
          pin ? json_array_get_double_element(pin, 1) : -1, pin ? json_array_get_double_element(pin, 2) : -1,
          pin ? json_array_get_double_element(pin, 3) : -1, pg ? json_object_get_boolean_member(pg, "in_active") : -1);
  CHECK(pg && !strcmp(json_object_get_string_member(pg, "channel"), "g") && pin
          && fabs(json_array_get_double_element(pin, 1) - 0.3) < 1e-6 && json_object_get_boolean_member(pg, "in_active")
          && !json_object_get_boolean_member(pg, "whole_range"),
        "parametric element read back");
  json_node_unref(all);
  n = p3d_members(root, ids);
  CHECK(n >= 3 && ids[n - 1] == pe, "a new element goes on top of the module's group");
  MUST(dtb_mask_update(pe, "{\"in\":[0,0,1,1]}"));
  s = NULL;
  MUST(dtb_masks(&s));
  all = jparse_owned(s);
  pg = json_object_get_object_member(mask_find(all, pe), "geometry");
  CHECK(json_object_get_boolean_member(pg, "whole_range") && !json_object_get_boolean_member(pg, "in_active"),
        "a whole-range element restricts nothing");
  json_node_unref(all);
  EXPECT(dtb_mask_update(pe, "{\"channel\":\"R\"}"), DTB_E_ARG);
  EXPECT(dtb_mask_update(pe, "{\"range\":[0,0,1,1]}"), DTB_E_NOT_FOUND);
  EXPECT(dtb_mask_update(pe, "{\"in\":[0.5,0.4,0.6,0.7]}"), DTB_E_RANGE);
  EXPECT(dtb_mask_add("exposure", np, "{\"type\":\"parametric\",\"geometry\":{\"channel\":\"nope\"}}", &s), DTB_E_ARG);
  EXPECT(dtb_mask_add("exposure", np, "{\"type\":\"parametric\",\"geometry\":{}}", &s), DTB_E_ARG);
  EXPECT(dtb_mask_add("exposure", np, "{\"type\":\"circle\",\"geometry\":{\"center\":[0.5,0.5]},\"group\":424242}", &s), DTB_E_NOT_FOUND);
  EXPECT(dtb_mask_add("exposure", 0, "{\"type\":\"triangle\",\"geometry\":{}}", &s), DTB_E_ARG);
  MUST(dtb_mask_update(pe, "{\"in\":[0.1,0.25,0.75,0.9],\"show_output\":true}"));
  // a parametric element in a nested group
  gchar *nreq = g_strdup_printf("{\"type\":\"parametric\",\"group\":%d,\"geometry\":{\"channel\":\"g\",\"out\":[0,0.05,0.6,0.7]}}", w);
  const int pe2 = p3d_add("exposure", np, nreq);
  g_free(nreq);
  n = p3d_members(w, ids);
  CHECK(n >= 1 && ids[n - 1] == pe2, "an element added to a nested group");
  save_render(outdir, "masktree_parametric");

  // 7. the XMP the bridge writes holds the tree: a fresh session lists and renders the same
  s = NULL;
  MUST(dtb_masks(&s));
  gchar *listed = g_strdup(s);
  dtb_free(s);
  int w0, hh0;
  uint8_t *r0 = render_full(&w0, &hh0, NULL);
  char *xp = path_join(outdir, "masktree_parametric.xmp");
  MUST(dtb_close());
  MUST(dtb_open(raw, xp));
  s = NULL;
  MUST(dtb_masks(&s));
  CHECK(!strcmp(s, listed), "the mask tree after reopening the bridge's XMP differs");
  fprintf(stdout, "RESULT reopened from the bridge's XMP: tree %s\n", strcmp(s, listed) ? "DIFFERS" : "equal");
  dtb_free(s);
  int w1, hh1;
  uint8_t *r1 = render_full(&w1, &hh1, NULL);
  const int mr = (w1 == w0 && hh1 == hh0) ? max_diff(r0, r1, (size_t)4 * w0 * hh0, &mean) : 999;
  fprintf(stdout, "RESULT reopened render vs before: max %d\n", mr);
  CHECK(mr == 0, "render after reopening differs (max %d)", mr);
  free(r0);
  free(r1);
  free(xp);
  g_free(listed);
  MUST(dtb_close());
  return g_fail;
}

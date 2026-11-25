// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3n (docs/design/engine-api.md 6.11), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3m.c, so the helpers of the
 * earlier files are used as they are.
 *
 *   perspective <raw> <xmp> <outdir>   C33: rotate and perspective's
 *                                      structure (rectangle, lines found with
 *                                      each enhancement, drawn lines), line
 *                                      selection (tap, area), fit (each axis
 *                                      and variant), automatic crop drag,
 *                                      focus, undo, XMP round trip; XMPs for
 *                                      darktable-cli
 *
 * The fits are checked against the geometry they must recover: the
 * rectangle desktop starts with (20 % .. 80 % of the picture), and lines drawn
 * along the picture's axes, are taken with known parameters P0 applied; with
 * the parameters back at zero, the fit must give P0 again.
 */

static JsonNode *p3n(const char *req)
{
  char *s = NULL;
  MUST(dtb_ashift("ashift", 0, req, &s));
  return jparse_owned(s);
}

static JsonObject *p3n_o(JsonNode *n) { return json_node_get_object(n); }

static double p3n_num(JsonNode *n, const char *k)
{
  return json_object_get_double_member(json_object_get_object_member(p3n_o(n), "params"), k);
}

static int p3n_count(JsonNode *n, const char *k)
{
  return (int)json_array_get_length(json_object_get_array_member(p3n_o(n), k));
}

static int p3n_kind(JsonNode *n, const char *kind)
{
  JsonArray *a = json_object_get_array_member(p3n_o(n), "lines");
  int c = 0;
  for(guint i = 0; i < json_array_get_length(a); i++)
    c += !strcmp(json_object_get_string_member(json_array_get_object_element(a, i), "kind"), kind);
  return c;
}

/* a press, moves and a release of one finger (desktop's mouse) */
static JsonNode *p3n_drag(double x0, double y0, double x1, double y1, int button, gboolean shift, double radius)
{
  char q[256];
  snprintf(q, sizeof(q), "{\"action\":\"pointer\",\"event\":\"press\",\"x\":%.6f,\"y\":%.6f,\"button\":%d,\"shift\":%s,\"radius\":%.6f}",
           x0, y0, button, shift ? "true" : "false", radius);
  json_node_unref(p3n(q));
  for(int k = 1; k <= 4; k++)
  {
    snprintf(q, sizeof(q), "{\"action\":\"pointer\",\"event\":\"move\",\"x\":%.6f,\"y\":%.6f,\"button\":%d,\"shift\":%s,\"radius\":%.6f}",
             x0 + (x1 - x0) * k / 4.0, y0 + (y1 - y0) * k / 4.0, button, shift ? "true" : "false", radius);
    json_node_unref(p3n(q));
  }
  snprintf(q, sizeof(q), "{\"action\":\"pointer\",\"event\":\"release\",\"x\":%.6f,\"y\":%.6f,\"button\":%d,\"shift\":%s,\"radius\":%.6f}",
           x1, y1, button, shift ? "true" : "false", radius);
  return p3n(q);
}

static void p3n_params(const char *json)
{
  MUST(dtb_params_set("ashift", 0, json));
  MUST(dtb_history_seal());
}

static void p3n_end_and_save(const char *outdir, const char *name)
{
  json_node_unref(p3n("{\"action\":\"end\"}"));
  save_render(outdir, name);
}

/* a structure button pressed until its structure is on (a button that is on
 * goes off when pressed, as desktop's) */
static JsonNode *p3n_structure_on(const char *method)
{
  char q[128];
  snprintf(q, sizeof(q), "{\"action\":\"structure\",\"method\":\"%s\"}", method);
  JsonNode *st = p3n(q);
  if(strcmp(json_object_get_string_member(p3n_o(st), "method"), method))
  {
    json_node_unref(st);
    st = p3n(q);
  }
  return st;
}

/* P0 known, the structure taken, parameters back at zero, fit: P0 again */
static void p3n_recover(const char *what, const char *structure, const char *p0, const char *fit,
                        double rot, double sv, double sh, double tol_rot, double tol_shift)
{
  p3n_params(p0);
  json_node_unref(p3n("{\"action\":\"changed\"}"));
  // no saved rectangle or lines: desktop then starts from its default
  // rectangle (20 % .. 80 % of the picture) or from no lines
  // every structure button off (desktop: the focus leaves and comes back)
  json_node_unref(p3n("{\"action\":\"end\"}"));
  json_node_unref(p3n("{\"action\":\"begin\"}"));
  JsonNode *st = NULL;
  p3n_params("{\"last_quad_lines[0]\":0,\"last_quad_lines[1]\":0,\"last_quad_lines[2]\":0,\"last_quad_lines[3]\":0,"
             "\"last_drawn_lines_count\":0}");
  char q[256];
  if(!strcmp(structure, "rectangle"))
  {
    st = p3n_structure_on("rectangle");
    CHECK(p3n_count(st, "handles") == 4, "%s: the rectangle has 4 corners (%d)", what, p3n_count(st, "handles"));
  }
  else
  {
    st = p3n_structure_on("drawn");
    CHECK(p3n_count(st, "lines") == 0, "%s: no drawn lines yet (%d)", what, p3n_count(st, "lines"));
    json_node_unref(st);
    // two lines along the picture's height, two along its width
    const double L[4][4] = { { 0.25, 0.15, 0.25, 0.85 }, { 0.75, 0.15, 0.75, 0.85 },
                             { 0.15, 0.25, 0.85, 0.25 }, { 0.15, 0.75, 0.85, 0.75 } };
    for(int k = 0; k < 4; k++) json_node_unref(p3n_drag(L[k][0], L[k][1], L[k][2], L[k][3], 1, FALSE, 0.01));
    st = p3n("{\"action\":\"state\"}");
    CHECK(p3n_count(st, "lines") == 4, "%s: 4 drawn lines (%d)", what, p3n_count(st, "lines"));
    CHECK(p3n_num(st, "saved_lines") == 4, "%s: 4 lines saved in the parameters (%g)", what, p3n_num(st, "saved_lines"));
  }
  json_node_unref(st);
  p3n_params("{\"rotation\":0,\"lensshift_v\":0,\"lensshift_h\":0,\"shear\":0}");
  json_node_unref(p3n("{\"action\":\"changed\"}"));
  int end = 0;
  const int h0 = history_count(&end);
  snprintf(q, sizeof(q), "{\"action\":\"fit\",%s}", fit);
  st = p3n(q);
  const int h1 = history_count(&end);
  const double r = p3n_num(st, "rotation"), v = p3n_num(st, "lensshift_v"), h = p3n_num(st, "lensshift_h");
  const JsonNode *msg = json_object_get_member(p3n_o(st), "message");
  fprintf(stdout, "RESULT %s: rotation %.4f lensshift_v %.4f lensshift_h %.4f shear %.4f (expected %.4f %.4f %.4f), items %d -> %d%s%s\n",
          what, r, v, h, p3n_num(st, "shear"), rot, sv, sh, h0, h1, JSON_NODE_HOLDS_NULL(msg) ? "" : ", message: ",
          JSON_NODE_HOLDS_NULL(msg) ? "" : json_object_get_string_member(p3n_o(st), "message"));
  CHECK(fabs(r - rot) <= tol_rot, "%s: rotation %.4f, expected %.4f", what, r, rot);
  CHECK(fabs(v - sv) <= tol_shift, "%s: lens shift v %.4f, expected %.4f", what, v, sv);
  CHECK(fabs(h - sh) <= tol_shift, "%s: lens shift h %.4f, expected %.4f", what, h, sh);
  CHECK(h1 == h0 + 1, "%s: the fit is one history item (%d -> %d)", what, h0, h1);
  json_node_unref(st);
}

/* The fits are ashift.c's Nelder-Mead (nmsfit), which stops when the simplex is within NMS_EPSILON (1e-3) in logit
 * space over desktop's soft ranges (rotation +-180 degrees): one parameter comes back to 1e-4 degrees, three together
 * (rotation and both lens shifts) to about 0.15 degrees on these pictures (first run: 0.049 cr2, 0.113 and 0.131 raf,
 * 0.014 arw). The bound for three parameters is 0.2 degrees; for one 0.05 as before. */
#define TOL_ROT3 0.2

static int cmd_perspective(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  char *s = NULL;
  int end = 0;
  EXPECT(dtb_ashift("exposure", 0, "{\"action\":\"begin\"}", &s), DTB_E_ARG);
  EXPECT(dtb_ashift("ashift", 0, "{\"action\":\"state\"}", &s), DTB_E_STATE);
  EXPECT(dtb_ashift("ashift", 0, "{\"action\":\"nothing\"}", &s), DTB_E_STATE);
  int w0, h0;
  MUST(dtb_render_size(1.0f, &w0, &h0));

  // 1. focus: the whole picture while focused; nothing in the history
  const int hA = history_count(&end);
  JsonNode *st = p3n("{\"action\":\"begin\"}");
  CHECK(json_object_get_boolean_member(p3n_o(st), "focused"), "begin: focused");
  CHECK(!strcmp(json_object_get_string_member(p3n_o(st), "method"), "none"), "begin: no structure");
  CHECK(!json_object_get_boolean_member(p3n_o(st), "fit_enabled"), "begin: fit buttons off (no structure)");
  json_node_unref(st);
  EXPECT(dtb_ashift("ashift", 0, "{\"action\":\"fit\",\"axis\":\"diagonal\"}", &s), DTB_E_ARG);
  EXPECT(dtb_ashift("ashift", 0, "{\"action\":\"fit\",\"axis\":\"vertical\",\"variant\":\"no_shear\"}", &s), DTB_E_ARG);
  EXPECT(dtb_ashift("ashift", 0, "{\"action\":\"structure\",\"method\":\"lines_found\",\"enhance\":\"sharper\"}", &s), DTB_E_ARG);
  CHECK(history_count(&end) == hA, "begin changes no history");

  // 2. rectangle: corners, corner drag, fit both, desktop's geometry recovered
  st = p3n("{\"action\":\"structure\",\"method\":\"rectangle\"}");
  CHECK(!strcmp(json_object_get_string_member(p3n_o(st), "method"), "rectangle"), "rectangle method");
  CHECK(json_object_get_boolean_member(p3n_o(st), "enabled"), "the rectangle switches the module on");
  CHECK(json_object_get_boolean_member(p3n_o(st), "fit_enabled"), "fit buttons on with a structure");
  CHECK(p3n_count(st, "handles") == 4, "4 corners (%d)", p3n_count(st, "handles"));
  JsonObject *h0o = json_array_get_object_element(json_object_get_array_member(p3n_o(st), "handles"), 0);
  const double cx = json_array_get_double_element(json_object_get_array_member(h0o, "p"), 0);
  const double cy = json_array_get_double_element(json_object_get_array_member(h0o, "p"), 1);
  fprintf(stdout, "RESULT rectangle: first corner %.4f %.4f (desktop: 0.2 0.2)\n", cx, cy);
  CHECK(fabs(cx - 0.2) < 0.01 && fabs(cy - 0.2) < 0.01, "desktop's rectangle starts at 20 %% (%.4f %.4f)", cx, cy);
  json_node_unref(st);
  int hB = history_count(&end);
  st = p3n_drag(cx, cy, cx + 0.05, cy + 0.02, 1, FALSE, 0.02);
  h0o = json_array_get_object_element(json_object_get_array_member(p3n_o(st), "handles"), 0);
  const double mx = json_array_get_double_element(json_object_get_array_member(h0o, "p"), 0);
  const double my = json_array_get_double_element(json_object_get_array_member(h0o, "p"), 1);
  int hC = history_count(&end);
  fprintf(stdout, "RESULT corner drag: %.4f %.4f -> %.4f %.4f, items %d -> %d\n", cx, cy, mx, my, hB, hC);
  CHECK(fabs(mx - (cx + 0.05)) < 0.002 && fabs(my - (cy + 0.02)) < 0.002, "the corner follows the finger");
  CHECK(hC == hB + 1, "a corner drag is one history item (%d -> %d)", hB, hC);
  json_node_unref(st);
  int w1, h1;
  uint8_t *plain = render_full(&w1, &h1, NULL);
  st = p3n("{\"action\":\"fit\",\"axis\":\"both\"}");
  fprintf(stdout, "RESULT rectangle fit both: rotation %.4f v %.4f h %.4f shear %.4f\n", p3n_num(st, "rotation"),
          p3n_num(st, "lensshift_v"), p3n_num(st, "lensshift_h"), p3n_num(st, "shear"));
  CHECK(p3n_num(st, "rotation") != 0.0 || p3n_num(st, "lensshift_v") != 0.0 || p3n_num(st, "lensshift_h") != 0.0,
        "the fit of a skewed rectangle changes the parameters");
  json_node_unref(st);
  {
    int w2, h2;
    uint8_t *fitted = render_full(&w2, &h2, NULL);
    double mean = 0.0;
    const int md = (w1 == w2 && h1 == h2) ? max_diff(plain, fitted, (size_t)4 * w1 * h1, &mean) : 255;
    fprintf(stdout, "RESULT rectangle fit render: %dx%d -> %dx%d, max diff %d\n", w1, h1, w2, h2, md);
    CHECK(md > 0, "the fit changes the picture");
    free(fitted);
  }
  free(plain);
  // undo: the parameters before the fit
  MUST(dtb_undo());
  CHECK(param_num("ashift", 0, "rotation") == 0.0 && param_num("ashift", 0, "lensshift_v") == 0.0,
        "undo brings the parameters before the fit back");
  MUST(dtb_redo());
  p3n_end_and_save(outdir, "p3n_rect_fit");
  {
    int wf, hf;
    MUST(dtb_render_size(1.0f, &wf, &hf));
    json_node_unref(p3n("{\"action\":\"begin\"}"));
    int wb, hb;
    MUST(dtb_render_size(1.0f, &wb, &hb));
    fprintf(stdout, "RESULT focus: picture %dx%d, focused %dx%d (whole picture, as desktop with the focus)\n", wf, hf, wb, hb);
    CHECK(wb >= wf && hb >= hf && (wb > wf || hb > hf), "focused: the uncropped picture (%dx%d vs %dx%d)", wb, hb, wf, hf);
  }

  // 3. recovering known parameters (rectangle and drawn lines, each fit)
  p3n_recover("rectangle both no_shear", "rectangle", "{\"rotation\":3,\"lensshift_v\":0.25,\"lensshift_h\":0,\"shear\":0}",
              "\"axis\":\"both\",\"variant\":\"no_shear\"", 3.0, 0.25, 0.0, TOL_ROT3, 0.01);
  p3n_recover("rectangle vertical rotation", "rectangle", "{\"rotation\":2,\"lensshift_v\":0,\"lensshift_h\":0,\"shear\":0}",
              "\"axis\":\"vertical\",\"variant\":\"rotation\"", 2.0, 0.0, 0.0, 0.05, 1e-6);
  p3n_recover("rectangle vertical shift", "rectangle", "{\"rotation\":0,\"lensshift_v\":0.2,\"lensshift_h\":0,\"shear\":0}",
              "\"axis\":\"vertical\",\"variant\":\"shift\"", 0.0, 0.2, 0.0, 1e-6, 0.01);
  p3n_recover("rectangle horizontal", "rectangle", "{\"rotation\":1.5,\"lensshift_v\":0,\"lensshift_h\":0.15,\"shear\":0}",
              "\"axis\":\"horizontal\"", 1.5, 0.0, 0.15, 0.05, 0.01);
  p3n_recover("rectangle horizontal shift", "rectangle", "{\"rotation\":0,\"lensshift_v\":0,\"lensshift_h\":-0.2,\"shear\":0}",
              "\"axis\":\"horizontal\",\"variant\":\"shift\"", 0.0, 0.0, -0.2, 1e-6, 0.01);
  p3n_recover("drawn both no_shear", "drawn", "{\"rotation\":-2.5,\"lensshift_v\":0.3,\"lensshift_h\":0.1,\"shear\":0}",
              "\"axis\":\"both\",\"variant\":\"no_shear\"", -2.5, 0.3, 0.1, TOL_ROT3, 0.01);
  p3n_recover("drawn both rotation", "drawn", "{\"rotation\":4,\"lensshift_v\":0,\"lensshift_h\":0,\"shear\":0}",
              "\"axis\":\"both\",\"variant\":\"rotation\"", 4.0, 0.0, 0.0, 0.05, 1e-6);
  // a drawn line deleted with the right button (desktop) / long press (app). The last line is taken: at the pinned
  // commit button_pressed (ashift.c, "we completely remove the line from the list") copies only the first
  // lines_count - 1 lines without the removed one, so removing any other line also drops the last line (first
  // run of this test: line 0 of 4 removed, 2 left). The bridge runs that code as it is (CLAUDE.md rule 1).
  {
    int hD = history_count(&end);
    st = p3n("{\"action\":\"state\"}");
    JsonArray *ls = json_object_get_array_member(p3n_o(st), "lines");
    const int n0 = (int)json_array_get_length(ls);
    JsonArray *pp = json_object_get_array_member(json_array_get_object_element(ls, n0 - 1), "p");
    const double lx = 0.5 * (json_array_get_double_element(pp, 0) + json_array_get_double_element(pp, 2));
    const double ly = 0.5 * (json_array_get_double_element(pp, 1) + json_array_get_double_element(pp, 3));
    json_node_unref(st);
    st = p3n_drag(lx, ly, lx, ly, 3, FALSE, 0.01);
    const int hE = history_count(&end);
    fprintf(stdout, "RESULT drawn line deleted (the last of %d): lines %d, saved %g, items %d -> %d\n", n0, p3n_count(st, "lines"),
            p3n_num(st, "saved_lines"), hD, hE);
    CHECK(p3n_count(st, "lines") == n0 - 1 && p3n_num(st, "saved_lines") == n0 - 1, "right button deletes a drawn line");
    CHECK(hE == hD + 1, "deleting a drawn line is one history item");
    json_node_unref(st);
  }
  p3n_end_and_save(outdir, "p3n_drawn_fit");
  // the drawn lines come back from the parameters (desktop: _draw_retrieve_lines_from_params)
  json_node_unref(p3n("{\"action\":\"begin\"}"));
  st = p3n("{\"action\":\"structure\",\"method\":\"drawn\"}");
  CHECK(p3n_count(st, "lines") == 3, "drawn lines from the parameters (%d)", p3n_count(st, "lines"));
  json_node_unref(st);
  st = p3n("{\"action\":\"structure\",\"method\":\"drawn\"}");  // the button again: off
  CHECK(!strcmp(json_object_get_string_member(p3n_o(st), "method"), "none"), "a structure button that is on goes off");
  json_node_unref(st);

  // 4. lines found, with each enhancement; selection; fit
  p3n_params("{\"rotation\":0,\"lensshift_v\":0,\"lensshift_h\":0,\"shear\":0}");
  json_node_unref(p3n("{\"action\":\"changed\"}"));
  static const char *const enh[] = { "none", "edges", "detail", "both" };
  int vused = 0, hused = 0;
  for(int k = 0; k < 4; k++)
  {
    char q[128];
    snprintf(q, sizeof(q), "{\"action\":\"structure\",\"method\":\"lines_found\",\"enhance\":\"%s\"}", enh[k]);
    st = p3n(q);
    vused = p3n_kind(st, "vertical_used");
    hused = p3n_kind(st, "horizontal_used");
    fprintf(stdout, "RESULT lines found (%s): %d lines, vertical used %d outliers %d, horizontal used %d outliers %d, other %d; counts %ld %ld\n",
            enh[k], p3n_count(st, "lines"), vused, p3n_kind(st, "vertical_outlier"), hused,
            p3n_kind(st, "horizontal_outlier"), p3n_kind(st, "other"), (long)json_object_get_int_member(p3n_o(st), "vertical"),
            (long)json_object_get_int_member(p3n_o(st), "horizontal"));
    CHECK(!strcmp(json_object_get_string_member(p3n_o(st), "method"), "lines_found"), "lines found (%s)", enh[k]);
    CHECK(p3n_count(st, "lines") > 0, "structure found (%s)", enh[k]);
    CHECK(vused == json_object_get_int_member(p3n_o(st), "vertical"), "vertical count = green lines (%s)", enh[k]);
    json_node_unref(st);
  }
  CHECK(vused >= 2 && hused >= 2, "enough lines for a fit (%d vertical, %d horizontal)", vused, hused);
  // tap with the right button (app: long press) on a used vertical line: deselected
  st = p3n("{\"action\":\"state\"}");
  JsonArray *ls = json_object_get_array_member(p3n_o(st), "lines");
  int pick = -1;
  double lx = 0, ly = 0;
  for(guint i = 0; i < json_array_get_length(ls) && pick < 0; i++)
  {
    JsonObject *l = json_array_get_object_element(ls, i);
    if(strcmp(json_object_get_string_member(l, "kind"), "vertical_used")) continue;
    JsonArray *pp = json_object_get_array_member(l, "p");
    lx = 0.5 * (json_array_get_double_element(pp, 0) + json_array_get_double_element(pp, 2));
    ly = 0.5 * (json_array_get_double_element(pp, 1) + json_array_get_double_element(pp, 3));
    pick = (int)i;
  }
  const int v0 = (int)json_object_get_int_member(p3n_o(st), "vertical");
  json_node_unref(st);
  st = p3n_drag(lx, ly, lx, ly, 3, FALSE, 0.001);
  const int v1 = (int)json_object_get_int_member(p3n_o(st), "vertical");
  const char *k1 = json_object_get_string_member(json_array_get_object_element(json_object_get_array_member(p3n_o(st), "lines"), pick), "kind");
  fprintf(stdout, "RESULT deselect line %d: vertical %d -> %d, now %s\n", pick, v0, v1, k1);
  CHECK(v1 < v0 && !strcmp(k1, "vertical_outlier"), "a tap with the right button deselects the line");
  json_node_unref(st);
  st = p3n_drag(lx, ly, lx, ly, 1, FALSE, 0.001);
  const int v2 = (int)json_object_get_int_member(p3n_o(st), "vertical");
  fprintf(stdout, "RESULT select line %d: vertical %d -> %d\n", pick, v1, v2);
  CHECK(v2 > v1, "a tap selects the line again");
  json_node_unref(st);
  // the area (shift+drag): right button deselects every line inside, left selects
  st = p3n_drag(-0.01, -0.01, 1.01, 1.01, 3, TRUE, 0.01);
  fprintf(stdout, "RESULT area deselect: vertical %ld horizontal %ld\n", (long)json_object_get_int_member(p3n_o(st), "vertical"),
          (long)json_object_get_int_member(p3n_o(st), "horizontal"));
  CHECK(json_object_get_int_member(p3n_o(st), "vertical") == 0 && json_object_get_int_member(p3n_o(st), "horizontal") == 0,
        "the area deselects every line");
  json_node_unref(st);
  st = p3n("{\"action\":\"fit\",\"axis\":\"both\"}");
  const JsonNode *msg = json_object_get_member(p3n_o(st), "message");
  fprintf(stdout, "RESULT fit without lines: %s\n", JSON_NODE_HOLDS_NULL(msg) ? "(no message)" : json_node_get_string(msg));
  CHECK(!JSON_NODE_HOLDS_NULL(msg) && p3n_num(st, "rotation") == 0.0, "no lines: desktop's message, no change");
  json_node_unref(st);
  st = p3n_drag(-0.01, -0.01, 1.01, 1.01, 1, TRUE, 0.01);
  fprintf(stdout, "RESULT area select: vertical %ld horizontal %ld\n", (long)json_object_get_int_member(p3n_o(st), "vertical"),
          (long)json_object_get_int_member(p3n_o(st), "horizontal"));
  CHECK(json_object_get_int_member(p3n_o(st), "vertical") >= vused, "the area selects every line");
  json_node_unref(st);
  // back to desktop's selection, then fit both (plain: rotation, lens shift and shear)
  json_node_unref(p3n("{\"action\":\"structure\",\"method\":\"lines_found\",\"enhance\":\"none\"}"));  // off
  json_node_unref(p3n("{\"action\":\"structure\",\"method\":\"lines_found\",\"enhance\":\"none\"}"));  // on
  st = p3n("{\"action\":\"fit\",\"axis\":\"both\"}");
  msg = json_object_get_member(p3n_o(st), "message");
  fprintf(stdout, "RESULT found lines fit both: rotation %.4f v %.4f h %.4f shear %.4f crop %.4f %.4f %.4f %.4f%s\n",
          p3n_num(st, "rotation"), p3n_num(st, "lensshift_v"), p3n_num(st, "lensshift_h"), p3n_num(st, "shear"),
          json_array_get_double_element(json_object_get_array_member(p3n_o(st), "crop"), 0),
          json_array_get_double_element(json_object_get_array_member(p3n_o(st), "crop"), 1),
          json_array_get_double_element(json_object_get_array_member(p3n_o(st), "crop"), 2),
          json_array_get_double_element(json_object_get_array_member(p3n_o(st), "crop"), 3),
          JSON_NODE_HOLDS_NULL(msg) ? "" : " (message)");
  CHECK(JSON_NODE_HOLDS_NULL(msg), "the fit on the lines found succeeds");
  json_node_unref(st);
  p3n_end_and_save(outdir, "p3n_found_fit");

  // 5. automatic crop "original format", dragged on the photo
  json_node_unref(p3n("{\"action\":\"begin\"}"));
  MUST(dtb_param_set("ashift", 0, "cropmode", "\"ASHIFT_CROP_ASPECT\""));
  st = p3n("{\"action\":\"changed\",\"what\":\"cropmode\"}");
  JsonArray *cr = json_object_get_array_member(p3n_o(st), "crop");
  const double c0l = json_array_get_double_element(cr, 0), c0t = json_array_get_double_element(cr, 2);
  const double c0r = json_array_get_double_element(cr, 1), c0b = json_array_get_double_element(cr, 3);
  fprintf(stdout, "RESULT original format: crop %.4f %.4f %.4f %.4f, clip %s\n", c0l, c0r, c0t, c0b,
          JSON_NODE_HOLDS_NULL(json_object_get_member(p3n_o(st), "clip")) ? "none" : "shown");
  CHECK(!JSON_NODE_HOLDS_NULL(json_object_get_member(p3n_o(st), "clip")), "the clip is shown while focused");
  json_node_unref(st);
  int hF = history_count(&end);
  st = p3n_drag(0.5, 0.5, 0.56, 0.5, 1, FALSE, 0.01);
  cr = json_object_get_array_member(p3n_o(st), "crop");
  const double c1l = json_array_get_double_element(cr, 0), c1t = json_array_get_double_element(cr, 2);
  int hG = history_count(&end);
  fprintf(stdout, "RESULT crop drag: left %.4f -> %.4f, top %.4f -> %.4f, items %d -> %d\n", c0l, c1l, c0t, c1t, hF, hG);
  CHECK(c1l != c0l || c1t != c0t, "dragging moves the automatic crop");
  CHECK(hG == hF + 1, "a crop drag is one history item");
  json_node_unref(st);
  p3n_end_and_save(outdir, "p3n_crop");

  // 6. XMP round trip: the parameters, the saved lines
  const double rot = param_num("ashift", 0, "rotation"), lv = param_num("ashift", 0, "lensshift_v");
  const double nl = param_num("ashift", 0, "last_drawn_lines_count");
  char *x = path_join(outdir, "p3n_roundtrip.xmp");
  MUST(dtb_xmp_write(x));
  MUST(dtb_close());
  MUST(dtb_open(raw, x));
  fprintf(stdout, "RESULT round trip: rotation %.4f/%.4f lensshift_v %.4f/%.4f drawn lines %g/%g\n", rot,
          param_num("ashift", 0, "rotation"), lv, param_num("ashift", 0, "lensshift_v"), nl,
          param_num("ashift", 0, "last_drawn_lines_count"));
  CHECK(param_num("ashift", 0, "rotation") == rot && param_num("ashift", 0, "lensshift_v") == lv
        && param_num("ashift", 0, "last_drawn_lines_count") == nl, "the XMP keeps every parameter");
  free(x);
  MUST(dtb_close());
  return g_fail;
}

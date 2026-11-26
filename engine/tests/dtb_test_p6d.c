// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of brief P6d, part of the test driver: dtb_test.c includes this file
 * after dtb_test_p6b.c.
 *
 *   lensbyhand <raw> <xmp> <outdir>  C45: lens correction's camera and lens by
 *                                    hand. The lists (cameras, lenses of a
 *                                    camera, lenses without a camera), the
 *                                    auto-searches (find_cameras on Exif's
 *                                    model, find_lenses on Exif's lens name),
 *                                    get_state (what gui_update shows),
 *                                    set_camera (also with a variant),
 *                                    set_lens, set_value for the focal length,
 *                                    aperture and distance, one history item
 *                                    each, refusals, the XMP round trip, a
 *                                    render that differs from the module
 *                                    switched off, and the XMP (p6d_lens.xmp)
 *                                    for darktable-cli
 */

typedef struct
{
  char camera[128], lens[256];
  double focal, aperture, distance, crop;
  int has_been_set;
} p6d_params_t;

static const char *p6d_str(JsonObject *o, const char *k)
{
  JsonNode *n = json_object_get_member(o, k);
  return (n && JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_STRING) ? json_node_get_string(n)
                                                                                         : NULL;
}

static int p6d_is_null(JsonObject *o, const char *k)
{
  JsonNode *n = json_object_get_member(o, k);
  return !n || JSON_NODE_HOLDS_NULL(n);
}

static double p6d_num(JsonObject *o, const char *k)
{
  JsonNode *n = json_object_get_member(o, k);
  return (n && JSON_NODE_HOLDS_VALUE(n)) ? json_node_get_double(n) : NAN;
}

static int p6d_bool_node(JsonNode *n)
{
  if(!n || !JSON_NODE_HOLDS_VALUE(n)) return -1;
  return json_node_get_value_type(n) == G_TYPE_BOOLEAN ? json_node_get_boolean(n) : json_node_get_int(n) != 0;
}

static void p6d_read(p6d_params_t *p)
{
  memset(p, 0, sizeof(*p));
  JsonNode *mods = get_modules();
  JsonObject *l = find_mod(mods, "lens", 0);
  CHECK(l != NULL, "no lens module");
  if(l)
  {
    const char *c = json_node_get_string(param_node(l, "camera"));
    const char *n = json_node_get_string(param_node(l, "lens"));
    g_strlcpy(p->camera, c ? c : "", sizeof(p->camera));
    g_strlcpy(p->lens, n ? n : "", sizeof(p->lens));
    p->focal = json_node_get_double(param_node(l, "focal"));
    p->aperture = json_node_get_double(param_node(l, "aperture"));
    p->distance = json_node_get_double(param_node(l, "distance"));
    p->crop = json_node_get_double(param_node(l, "crop"));
    p->has_been_set = p6d_bool_node(param_node(l, "has_been_set"));
  }
  json_node_unref(mods);
}

/* history items after sealing the open one */
static int p6d_items(void)
{
  MUST(dtb_history_seal());
  return history_count(NULL);
}

/* one module action that must add exactly one history item */
static JsonNode *p6d_edit(const char *what, const char *act, const char *args)
{
  const int before = p6d_items();
  JsonNode *r = action("lens", 0, act, args, DTB_OK);
  const int after = p6d_items();
  CHECK(after == before + 1, "%s: %d history items before, %d after (one expected)", what, before, after);
  return r;
}

/* a JSON string literal */
static gchar *p6d_q(const char *s)
{
  JsonNode *n = json_node_new(JSON_NODE_VALUE);
  json_node_set_string(n, s ? s : "");
  gchar *r = json_to_string(n, FALSE);
  json_node_unref(n);
  return r;
}

/* {"camera": .., "lens": .., "maker": ..}, each part only when given */
static gchar *p6d_args(const char *camera, const char *lens, const char *maker, const char *model, const char *variant)
{
  GString *g = g_string_new("{");
  const char *key[5] = { "camera", "lens", "maker", "model", "variant" };
  const char *val[5] = { camera, lens, maker, model, variant };
  for(int i = 0; i < 5; i++)
    if(val[i])
    {
      gchar *q = p6d_q(val[i]);
      g_string_append_printf(g, "%s\"%s\": %s", g->len > 1 ? ", " : "", key[i], q);
      g_free(q);
    }
  g_string_append(g, "}");
  return g_string_free(g, FALSE);
}

static int p6d_has_model(JsonArray *a, const char *model)
{
  for(guint i = 0; i < json_array_get_length(a); i++)
  {
    const char *m = p6d_str(json_array_get_object_element(a, i), "model");
    if(m && !strcmp(m, model)) return 1;
  }
  return 0;
}

static int cmd_lensbyhand(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  // the Lensfun method, whatever the XMP says (the X-Trans XMP uses the embedded metadata)
  MUST(dtb_param_set("lens", 0, "method", "\"DT_IOP_LENS_METHOD_LENSFUN\""));
  MUST(dtb_history_seal());

  // ---- the state, as gui_update() shows it
  JsonNode *sn = action("lens", 0, "get_state", NULL, DTB_OK);
  JsonObject *st = sn ? json_node_get_object(sn) : NULL;
  CHECK(st != NULL, "no get_state");
  if(!st) return g_fail;
  JsonObject *exif = json_object_get_object_member(st, "exif");
  const char *xmodel = exif ? p6d_str(exif, "model") : NULL, *xlens = exif ? p6d_str(exif, "lens") : NULL;
  fprintf(stdout, "RESULT state: camera param '%s', lens param '%s', Exif model '%s' lens '%s', Lensfun camera %s, lens %s\n",
          p6d_str(st, "camera_param") ? p6d_str(st, "camera_param") : "", p6d_str(st, "lens_param") ? p6d_str(st, "lens_param") : "",
          xmodel ? xmodel : "", xlens ? xlens : "", p6d_is_null(st, "camera") ? "unknown" : "found",
          p6d_is_null(st, "lens") ? "unknown" : "found");
  CHECK(xmodel && xmodel[0], "Exif camera model missing from get_state");
  CHECK(!p6d_is_null(st, "camera"), "Lensfun does not know the Exif camera '%s'", xmodel ? xmodel : "");
  JsonObject *cam0 = json_object_get_object_member(st, "camera");
  gchar *cam_maker = g_strdup(p6d_str(cam0, "maker")), *cam_model = g_strdup(p6d_str(cam0, "model"));
  gchar *cam_variant = p6d_str(cam0, "variant") ? g_strdup(p6d_str(cam0, "variant")) : NULL;
  const double cam_crop = p6d_num(cam0, "crop_factor");
  CHECK(cam_maker && cam_model && p6d_str(cam0, "mount") && cam_crop > 0.5, "camera object incomplete");
  const int exif_lens_known = !p6d_is_null(st, "lens");
  if(exif_lens_known)
  {
    JsonObject *l0 = json_object_get_object_member(st, "lens");
    CHECK(p6d_str(l0, "model") && p6d_str(l0, "type") && p6d_num(l0, "min_focal") > 0
          && json_array_get_length(json_object_get_array_member(l0, "mounts")) > 0 && p6d_num(l0, "crop_factor") > 0,
          "lens object incomplete");
  }

  // ---- the lists
  JsonNode *allc = action("lens", 0, "list_cameras", NULL, DTB_OK);
  JsonArray *all = json_node_get_array(allc);
  const guint ncam = json_array_get_length(all);
  fprintf(stdout, "RESULT list_cameras: %u\n", ncam);
  CHECK(ncam > 500, "only %u cameras", ncam);
  CHECK(p6d_has_model(all, cam_model), "list_cameras lacks '%s'", cam_model);

  // find_cameras: FindCamerasExt on Exif's model; a short list that holds the camera
  JsonNode *fc = action("lens", 0, "find_cameras", NULL, DTB_OK);
  const guint nfc = json_array_get_length(json_node_get_array(fc));
  fprintf(stdout, "RESULT find_cameras: %u of %u\n", nfc, ncam);
  CHECK(nfc >= 1 && nfc < ncam / 4, "find_cameras gave %u of %u", nfc, ncam);
  CHECK(p6d_has_model(json_node_get_array(fc), cam_model), "find_cameras lacks '%s'", cam_model);

  // lenses of the camera, find_lenses on Exif's lens name, lenses without a camera
  gchar *args = p6d_args(cam_model, NULL, NULL, NULL, NULL);
  JsonNode *ls = action("lens", 0, "list_lenses", args, DTB_OK);
  JsonNode *fl = action("lens", 0, "find_lenses", args, DTB_OK);
  g_free(args);
  JsonNode *wl = action("lens", 0, "list_lenses", "{}", DTB_OK);
  const guint nls = json_array_get_length(json_node_get_array(ls)), nfl = json_array_get_length(json_node_get_array(fl)),
              nwl = json_array_get_length(json_node_get_array(wl));
  fprintf(stdout, "RESULT lenses: %u for '%s', find_lenses %u, without a camera %u\n", nls, cam_model, nfl, nwl);
  CHECK(nls > 10 && nwl > nls, "lens lists: %u for the camera, %u without", nls, nwl);
  CHECK(nfl <= nls, "find_lenses gave %u of %u", nfl, nls);
  if(exif_lens_known)
  {
    const char *want = p6d_str(json_object_get_object_member(st, "lens"), "model");
    CHECK(p6d_has_model(json_node_get_array(ls), want), "list_lenses lacks the Exif lens '%s'", want);
    CHECK(nfl >= 1 && p6d_has_model(json_node_get_array(fl), want), "find_lenses lacks the Exif lens '%s'", want);
  }
  // find_lenses without a camera searches the whole database, by the same name
  JsonNode *fl0 = action("lens", 0, "find_lenses", "{}", DTB_OK);
  CHECK(json_array_get_length(json_node_get_array(fl0)) >= nfl, "find_lenses without a camera gave fewer lenses");
  json_node_unref(fl0);
  // a lens carries the fields the picker and the value lists need
  for(guint i = 0; i < MIN(nls, 40u); i++)
  {
    JsonObject *o = json_array_get_object_element(json_node_get_array(ls), i);
    CHECK(p6d_str(o, "model") && p6d_str(o, "maker") && p6d_num(o, "min_focal") > 0
              && p6d_num(o, "max_focal") >= p6d_num(o, "min_focal"),
          "lens %u incomplete", i);
  }
  action("lens", 0, "list_lenses", "{\"camera\": \"no such camera\"}", DTB_E_NOT_FOUND);
  action("lens", 0, "find_lenses", "{\"camera\": 7}", DTB_E_TYPE);

  // ---- set_camera: a camera with a variant (its own crop factor), one history item
  JsonObject *other = NULL;
  for(guint i = 0; i < ncam && !other; i++)
  {
    JsonObject *o = json_array_get_object_element(all, i);
    if(!p6d_is_null(o, "variant") && p6d_num(o, "crop_factor") != cam_crop) other = o;
  }
  CHECK(other != NULL, "no camera with a variant and another crop factor");
  if(other)
  {
    gchar *a = p6d_args(NULL, NULL, p6d_str(other, "maker"), p6d_str(other, "model"), p6d_str(other, "variant"));
    json_node_unref(p6d_edit("set_camera (variant)", "set_camera", a));
    g_free(a);
    p6d_params_t p;
    p6d_read(&p);
    fprintf(stdout, "RESULT set_camera '%s (%s)': params camera '%s' crop %.3f (has_been_set %d)\n", p6d_str(other, "model"),
            p6d_str(other, "variant"), p.camera, p.crop, p.has_been_set);
    CHECK(!strcmp(p.camera, p6d_str(other, "model")) && fabs(p.crop - p6d_num(other, "crop_factor")) < 1e-3
              && p.has_been_set == 1,
          "set_camera did not write camera, crop and has_been_set");
    JsonNode *s2 = action("lens", 0, "get_state", NULL, DTB_OK);
    CHECK(s2 && !p6d_is_null(json_node_get_object(s2), "camera"), "get_state: camera unknown after set_camera");
    json_node_unref(s2);
  }
  action("lens", 0, "set_camera", "{\"model\": \"no such camera at all\"}", DTB_E_NOT_FOUND);
  action("lens", 0, "set_camera", "{}", DTB_E_TYPE);
  // back to the Exif camera
  args = p6d_args(NULL, NULL, cam_maker, cam_model, cam_variant);
  json_node_unref(p6d_edit("set_camera (back)", "set_camera", args));
  g_free(args);
  {
    p6d_params_t p;
    p6d_read(&p);
    CHECK(!strcmp(p.camera, cam_model) && fabs(p.crop - cam_crop) < 1e-3, "camera not back: '%s' crop %.3f", p.camera, p.crop);
  }

  // ---- set_lens: a fisheye, so the render shows it
  JsonObject *fish = NULL;
  JsonArray *lsa = json_node_get_array(ls);
  for(guint i = 0; i < nls && !fish; i++)
  {
    JsonObject *o = json_array_get_object_element(lsa, i);
    gchar *low = g_ascii_strdown(p6d_str(o, "model"), -1);
    if((strstr(low, "fish") || strstr(low, "fisheye")) && p6d_num(o, "min_focal") <= p6d_num(o, "max_focal")) fish = o;
    g_free(low);
  }
  CHECK(fish != NULL, "no fisheye lens for '%s'", cam_model);
  if(fish)
  {
    args = p6d_args(cam_model, p6d_str(fish, "model"), p6d_str(fish, "maker"), NULL, NULL);
    json_node_unref(p6d_edit("set_lens", "set_lens", args));
    g_free(args);
    p6d_params_t p;
    p6d_read(&p);
    fprintf(stdout, "RESULT set_lens '%s': focal %.2f, has_been_set %d\n", p.lens, p.focal, p.has_been_set);
    CHECK(!strcmp(p.lens, p6d_str(fish, "model")) && p.has_been_set == 1, "set_lens did not write the lens");
    if(p6d_num(fish, "min_focal") >= p6d_num(fish, "max_focal"))
      CHECK(fabs(p.focal - p6d_num(fish, "min_focal")) < 1e-3, "a prime lens did not fix the focal length");
    JsonNode *s2 = action("lens", 0, "get_state", NULL, DTB_OK);
    JsonObject *s2o = s2 ? json_node_get_object(s2) : NULL;
    CHECK(s2o && !p6d_is_null(s2o, "lens") && !strcmp(p6d_str(json_object_get_object_member(s2o, "lens"), "model"), p6d_str(fish, "model")),
          "get_state does not show the lens that was set");
    json_node_unref(s2);
  }
  // a lens without a camera: the camera and crop factor stay
  {
    p6d_params_t before, after;
    p6d_read(&before);
    args = p6d_args(NULL, p6d_str(json_array_get_object_element(lsa, 0), "model"), NULL, NULL, NULL);
    json_node_unref(p6d_edit("set_lens without a camera", "set_lens", args));
    g_free(args);
    p6d_read(&after);
    CHECK(!strcmp(before.camera, after.camera) && before.crop == after.crop, "set_lens without a camera changed the camera");
    if(fish)
    {
      args = p6d_args(cam_model, p6d_str(fish, "model"), p6d_str(fish, "maker"), NULL, NULL);
      json_node_unref(p6d_edit("set_lens (fisheye again)", "set_lens", args));
      g_free(args);
    }
  }
  args = p6d_args(cam_model, "no such lens at all", NULL, NULL, NULL);
  action("lens", 0, "set_lens", args, DTB_E_NOT_FOUND);
  g_free(args);

  // ---- the three values: parameter and has_been_set, one history item each
  static const struct { const char *name; double v; } vals[] = { { "focal", 17.5 }, { "aperture", 5.6 }, { "distance", 2.5 } };
  for(size_t i = 0; i < G_N_ELEMENTS(vals); i++)
  {
    char vargs[128];
    snprintf(vargs, sizeof(vargs), "{\"name\": \"%s\", \"value\": %g}", vals[i].name, vals[i].v);
    json_node_unref(p6d_edit(vals[i].name, "set_value", vargs));
    p6d_params_t p;
    p6d_read(&p);
    const double got = i == 0 ? p.focal : i == 1 ? p.aperture : p.distance;
    fprintf(stdout, "RESULT set_value %s %g: parameter %g, has_been_set %d\n", vals[i].name, vals[i].v, got, p.has_been_set);
    CHECK(fabs(got - vals[i].v) < 1e-5 && p.has_been_set == 1, "set_value %s: %g (has_been_set %d)", vals[i].name, got, p.has_been_set);
  }
  action("lens", 0, "set_value", "{\"name\": \"focal\", \"value\": 0}", DTB_E_ARG);
  action("lens", 0, "set_value", "{\"name\": \"focal\", \"value\": -5}", DTB_E_ARG);
  action("lens", 0, "set_value", "{\"name\": \"focal\", \"value\": \"50\"}", DTB_E_TYPE);
  action("lens", 0, "set_value", "{\"name\": \"scale\", \"value\": 1}", DTB_E_TYPE);
  action("lens", 0, "set_value", "{\"value\": 1}", DTB_E_TYPE);
  action("lens", 0, "nope", NULL, DTB_E_NOT_FOUND);

  // ---- undo: the last item is the distance; going back one step restores the previous value
  {
    int end = 0;
    const int n = history_count(&end);
    MUST(dtb_history_goto(end - 1));
    p6d_params_t p;
    p6d_read(&p);
    CHECK(fabs(p.distance - 2.5) > 1e-5, "undo did not restore the distance (still %g of %d items)", p.distance, n);
    MUST(dtb_history_goto(end));
    p6d_read(&p);
    CHECK(fabs(p.distance - 2.5) < 1e-5, "redo lost the distance (%g)", p.distance);
  }

  // ---- render: differs from the module switched off
  int w = 0, h = 0, w2 = 0, h2 = 0;
  MUST(dtb_set_enabled("lens", 0, 0));
  MUST(dtb_history_seal());
  uint8_t *off = render_full(&w, &h, NULL);
  MUST(dtb_set_enabled("lens", 0, 1));
  MUST(dtb_history_seal());
  uint8_t *on = render_full(&w2, &h2, NULL);
  CHECK(w == w2 && h == h2, "render sizes differ: %dx%d and %dx%d", w, h, w2, h2);
  double sum = 0;
  int mx = 0;
  const size_t npx = (size_t)w * h;
  for(size_t i = 0; i < npx * 4 && w == w2 && h == h2; i++)
    if((i & 3) != 3)
    {
      const int d = abs((int)on[i] - (int)off[i]);
      sum += d;
      mx = MAX(mx, d);
    }
  fprintf(stdout, "RESULT lens set vs module off: max %d, mean %.4f (8-bit)\n", mx, sum / (npx * 3.0));
  CHECK(mx >= 8 && sum / (npx * 3.0) >= 0.5, "the lens did not change the picture (max %d)", mx);
  free(off);

  // ---- XMP round trip: parameters, state and render
  p6d_params_t pa;
  p6d_read(&pa);
  write_ppm(path_join(outdir, "p6d_lens_bridge.ppm"), on, w2, h2);
  MUST(dtb_xmp_write(path_join(outdir, "p6d_lens.xmp")));
  MUST(dtb_close());
  MUST(dtb_open(raw, path_join(outdir, "p6d_lens.xmp")));
  p6d_params_t pb;
  p6d_read(&pb);
  CHECK(!strcmp(pa.camera, pb.camera) && !strcmp(pa.lens, pb.lens) && fabs(pa.focal - pb.focal) < 1e-5
            && fabs(pa.aperture - pb.aperture) < 1e-5 && fabs(pa.distance - pb.distance) < 1e-5 && pb.has_been_set == 1,
        "XMP round trip changed the parameters: '%s' '%s' %g %g %g -> '%s' '%s' %g %g %g", pa.camera, pa.lens, pa.focal, pa.aperture,
        pa.distance, pb.camera, pb.lens, pb.focal, pb.aperture, pb.distance);
  JsonNode *s3 = action("lens", 0, "get_state", NULL, DTB_OK);
  CHECK(s3 && !p6d_is_null(json_node_get_object(s3), "lens"), "get_state after the round trip lost the lens");
  json_node_unref(s3);
  int w3, h3;
  uint8_t *again = render_full(&w3, &h3, NULL);
  int md = 0;
  for(size_t i = 0; i < npx * 4 && w3 == w2 && h3 == h2; i++)
    if((i & 3) != 3) md = MAX(md, abs((int)again[i] - (int)on[i]));
  fprintf(stdout, "RESULT XMP round trip render differs by %d\n", md);
  CHECK(w3 == w2 && h3 == h2 && md == 0, "the render after reopening the XMP differs (%d)", md);
  free(again);
  free(on);
  json_node_unref(sn);
  json_node_unref(allc);
  json_node_unref(fc);
  json_node_unref(ls);
  json_node_unref(fl);
  json_node_unref(wl);
  g_free(cam_maker);
  g_free(cam_model);
  g_free(cam_variant);
  MUST(dtb_close());
  return g_fail;
}

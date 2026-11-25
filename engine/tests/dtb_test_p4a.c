// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P4a (docs/design/engine-api.md 10), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3n.c.
 *
 *   memory <raw> <xmp> <outdir>   C34: memory_info before and after renders
 *                                 and after trim levels 1 and 2; the edit
 *                                 (history, a parameter, undo) is kept and
 *                                 the renders after a trim equal the ones
 *                                 before; numbers in <outdir>/p4a_memory.txt
 */

static gint64 p4a_num(const char *json, const char *k)
{
  JsonNode *n = jparse_owned(g_strdup(json));
  const gint64 v = json_object_get_int_member(json_node_get_object(n), k);
  json_node_unref(n);
  return v;
}

static void p4a_line(FILE *f, const char *what, const char *json)
{
  fprintf(f, "%-34s pipes %" G_GINT64_FORMAT " / %" G_GINT64_FORMAT " MB (full / preview), mipmap full %" G_GINT64_FORMAT
          " MB, f %" G_GINT64_FORMAT " MB, thumbs %" G_GINT64_FORMAT " MB, rss %" G_GINT64_FORMAT " MB\n",
          what, p4a_num(json, "pipe_full") >> 20, p4a_num(json, "pipe_preview") >> 20, p4a_num(json, "mipmap_full") >> 20,
          p4a_num(json, "mipmap_f") >> 20, p4a_num(json, "mipmap_thumbs") >> 20, p4a_num(json, "rss") >> 20);
  fprintf(stdout, "P4A %s %s\n", what, json);
}

static char *p4a_info(void)
{
  char *s = NULL;
  MUST(dtb_memory_info(&s));
  return s;
}

static uint8_t *p4a_preview(int *w, int *h)
{
  uint8_t *p = NULL;
  MUST(dtb_render_preview(1024, &p, w, h));
  return p;
}

static uint8_t *p4a_region(int w, int h)
{
  uint8_t *r = malloc((size_t)w * h * 4);
  MUST(dtb_render_region(0, 0, w, h, 0.5f, r));
  return r;
}

static int cmd_memory(const char *raw, const char *xmp, const char *outdir)
{
  char path[4096];
  snprintf(path, sizeof(path), "%s/p4a_memory.txt", outdir);
  FILE *f = fopen(path, "w");
  CHECK(f != NULL, "open %s", path);
  if(!f) return 1;
  fprintf(f, "engine memory (dtb_memory_info), x86_64 Linux build workspace, not phone numbers\n");

  char *s = p4a_info();
  p4a_line(f, "started, nothing open", s);
  dtb_free(s);
  EXPECT(dtb_memory_trim(0, NULL), DTB_E_ARG);
  EXPECT(dtb_memory_trim(3, NULL), DTB_E_ARG);

  MUST(dtb_open(raw, xmp));
  // a value the photo does not have yet (the default edit already has +0.7 EV on some cameras: undo must change something)
  const double ev0 = param_num("exposure", 0, "exposure");
  const double ev1 = ev0 + 0.5;
  char evs[64];
  snprintf(evs, sizeof(evs), "%.6f", ev1);
  MUST(dtb_param_set("exposure", 0, "exposure", evs));
  int end0 = 0;
  const int h0 = history_count(&end0);
  int pw, ph;
  uint8_t *pv0 = p4a_preview(&pw, &ph);
  uint8_t *rg0 = p4a_region(640, 480);
  s = p4a_info();
  p4a_line(f, "open, preview and region rendered", s);
  const gint64 full0 = p4a_num(s, "mipmap_full"), pipes0 = p4a_num(s, "pipe_full") + p4a_num(s, "pipe_preview");
  CHECK(p4a_num(s, "pipes_ready") == 2, "both pipelines ready after renders");
  CHECK(full0 > 0, "the open image's input is in the cache");
  dtb_free(s);

  // level 1: the pipelines' buffers go, the pipelines and the open image's input stay
  s = NULL;
  MUST(dtb_memory_trim(1, &s));
  p4a_line(f, "after trim level 1", s);
  CHECK(p4a_num(s, "pipe_full") + p4a_num(s, "pipe_preview") < pipes0 || pipes0 == 0, "level 1 frees pipeline buffers");
  CHECK(p4a_num(s, "pipes_ready") == 2, "level 1 keeps the pipelines");
  dtb_free(s);
  int end1 = 0;
  CHECK(history_count(&end1) == h0 && end1 == end0, "level 1 keeps the history");
  int w1, h1;
  uint8_t *pv1 = p4a_preview(&w1, &h1);
  CHECK(w1 == pw && h1 == ph && !memcmp(pv0, pv1, (size_t)pw * ph * 4), "preview after level 1 equals the one before");
  dtb_free(pv1);
  uint8_t *rg1 = p4a_region(640, 480);
  CHECK(!memcmp(rg0, rg1, 640 * 480 * 4), "region after level 1 equals the one before");
  free(rg1);

  // level 2: the pipelines and the open image's input go; the next render reads the RAW again
  s = NULL;
  MUST(dtb_memory_trim(2, &s));
  p4a_line(f, "after trim level 2", s);
  CHECK(p4a_num(s, "pipes_ready") == 0, "level 2 frees the pipelines");
  CHECK(p4a_num(s, "pipe_full") == 0 && p4a_num(s, "pipe_preview") == 0, "level 2: no pipeline buffers");
  CHECK(p4a_num(s, "mipmap_full") < full0, "level 2 frees the open image's input");
  dtb_free(s);
  int end2 = 0;
  CHECK(history_count(&end2) == h0 && end2 == end0, "level 2 keeps the history");
  CHECK(fabs(param_num("exposure", 0, "exposure") - ev1) < 1e-5, "level 2 keeps the parameter");
  int w2, h2;
  uint8_t *pv2 = p4a_preview(&w2, &h2);
  CHECK(w2 == pw && h2 == ph && !memcmp(pv0, pv2, (size_t)pw * ph * 4), "preview after level 2 equals the one before");
  dtb_free(pv2);
  uint8_t *rg2 = p4a_region(640, 480);
  CHECK(!memcmp(rg0, rg2, 640 * 480 * 4), "region after level 2 equals the one before");
  free(rg2);
  s = p4a_info();
  p4a_line(f, "rendered again after level 2", s);
  dtb_free(s);
  // the undo step of the change is still there
  MUST(dtb_history_seal());
  MUST(dtb_undo());
  CHECK(fabs(param_num("exposure", 0, "exposure") - ev0) < 1e-5, "undo after the trims undoes the change (%.4f, before the change %.4f)",
        param_num("exposure", 0, "exposure"), ev0);

  dtb_free(pv0);
  free(rg0);
  MUST(dtb_close());
  MUST(dtb_memory_trim(2, NULL));
  s = p4a_info();
  p4a_line(f, "closed, trim level 2", s);
  dtb_free(s);
  fclose(f);
  fprintf(stdout, "RESULT memory numbers in %s\n", path);
  return g_fail;
}

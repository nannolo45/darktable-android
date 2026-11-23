// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3h (docs/design/engine-api.md 8), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3g.c.
 *
 *   snaphist <raw> <xmp> <outdir>  C28: a render with the option
 *                                  "history_end": n equals history_goto(n),
 *                                  the same render, history_goto(back), for
 *                                  several positions (preview, region, size);
 *                                  history, history end, undo state and the
 *                                  open history item stay as they were; a
 *                                  cancel returns DTB_E_CANCELLED and leaves
 *                                  the session as it was; bad positions fail
 */

#define P3H_MAXPOS 8

typedef struct
{
  uint8_t *prev;
  int pw, ph;
  uint8_t *reg;
  int sw, sh;  // render_size at P3H_SCALE
} p3h_shot_t;

#define P3H_SCALE 0.25f
#define P3H_PREVIEW 720

static char *p3h_history(void)
{
  char *j = NULL;
  if(dtb_history(&j) != DTB_OK) return g_strdup("(history failed)");
  char *c = g_strdup(j);
  dtb_free(j);
  return c;
}

static char *p3h_undo(void)
{
  char *j = NULL;
  if(dtb_undo_state(&j) != DTB_OK) return g_strdup("(undo_state failed)");
  char *c = g_strdup(j);
  dtb_free(j);
  return c;
}

static int p3h_count(void)
{
  char *j = p3h_history();
  int n = 0;
  for(const char *p = j; (p = strstr(p, "\"num\"")); p++) n++;
  g_free(j);
  return n;
}

/* render preview, size and the whole picture as one region at P3H_SCALE,
 * with the given options (NULL = plain) */
static int p3h_shoot(const char *opts, p3h_shot_t *s)
{
  memset(s, 0, sizeof(*s));
  int r = dtb_render_preview_ex(P3H_PREVIEW, opts, &s->prev, &s->pw, &s->ph);
  if(r) return r;
  r = dtb_render_size_ex(P3H_SCALE, opts, &s->sw, &s->sh);
  if(r) return r;
  s->reg = malloc((size_t)4 * s->sw * s->sh);
  return dtb_render_region_ex(0, 0, s->sw, s->sh, P3H_SCALE, opts, s->reg);
}

static void p3h_free(p3h_shot_t *s)
{
  if(s->prev) dtb_free(s->prev);
  free(s->reg);
  memset(s, 0, sizeof(*s));
}

/* 0 when equal; prints the differences */
static int p3h_compare(const char *what, const p3h_shot_t *a, const p3h_shot_t *b)
{
  if(a->pw != b->pw || a->ph != b->ph || a->sw != b->sw || a->sh != b->sh)
  {
    fprintf(stdout, "RESULT %s: sizes differ: preview %dx%d vs %dx%d, size %dx%d vs %dx%d\n", what, a->pw, a->ph,
            b->pw, b->ph, a->sw, a->sh, b->sw, b->sh);
    return 1;
  }
  int m1, m2;
  double e1, e2;
  diff_stats(a->prev, b->prev, (size_t)a->pw * a->ph, &m1, &e1);
  diff_stats(a->reg, b->reg, (size_t)a->sw * a->sh, &m2, &e2);
  fprintf(stdout, "RESULT %s: preview %dx%d max %d mean %.4f; region %dx%d max %d mean %.4f\n", what, a->pw, a->ph,
          m1, e1, a->sw, a->sh, m2, e2);
  return m1 != 0 || m2 != 0;
}

static int cmd_snaphist(const char *raw, const char *xmp, const char *outdir)
{
  (void)outdir;
  MUST(dtb_open(raw, xmp));
  MUST(dtb_history_seal());
  // several history items of different kinds: two exposure changes, a
  // module switched on, a new instance, a drawn mask; the last change is
  // left open (not sealed)
  double ev = 0;
  module_info("exposure", 0, &ev, NULL, 0, NULL);
  char v[64];
  snprintf(v, sizeof(v), "%.9g", ev + 0.7);
  MUST(dtb_param_set("exposure", 0, "exposure", v));
  MUST(dtb_history_seal());
  MUST(dtb_param_set("vignette", 0, "brightness", "-0.4"));
  MUST(dtb_history_seal());
  int np = -1;
  MUST(dtb_instance_new("exposure", 0, &np));
  MUST(dtb_param_set("exposure", np, "exposure", "-0.5"));
  MUST(dtb_history_seal());
  const int mr = dtb_mask_add_circle("exposure", np, 0.5f, 0.5f, 0.2f, 0.05f);
  fprintf(stdout, "RESULT circle mask on the new instance: returned %d\n", mr);
  MUST(dtb_history_seal());
  snprintf(v, sizeof(v), "%.9g", ev + 0.3);
  MUST(dtb_param_set("exposure", 0, "exposure", v));  // open item

  const int n = p3h_count();
  char *hist0 = p3h_history();
  char *undo0 = p3h_undo();
  fprintf(stdout, "RESULT history items %d\n", n);
  CHECK(n >= 5, "expected at least 5 history items, got %d", n);

  p3h_shot_t live0;
  MUST(p3h_shoot(NULL, &live0));

  int pos[P3H_MAXPOS], npos = 0;
  pos[npos++] = 0;
  pos[npos++] = 1;
  pos[npos++] = n / 2;
  pos[npos++] = n - 2;
  pos[npos++] = n - 1;
  pos[npos++] = n;  // the session's own end: the plain render

  // 1. the new option, for every position, before any history_goto
  p3h_shot_t at[P3H_MAXPOS];
  double t_at = 0;
  for(int i = 0; i < npos; i++)
  {
    char o[64];
    snprintf(o, sizeof(o), "{\"history_end\": %d}", pos[i]);
    const double t0 = now();
    const int r = p3h_shoot(o, &at[i]);
    t_at += now() - t0;
    CHECK(r == DTB_OK, "history_end %d: returned %d (%s)", pos[i], r, dtb_last_error());
    char *h = p3h_history(), *u = p3h_undo();
    CHECK(!strcmp(h, hist0), "history_end %d changed history()", pos[i]);
    CHECK(!strcmp(u, undo0), "history_end %d changed undo_state()", pos[i]);
    g_free(h);
    g_free(u);
  }
  fprintf(stdout, "RESULT %d positions rendered with history_end in %.3f s (x86_64, CI)\n", npos, t_at);

  // 2. the session is untouched: the live render is the same, and the open
  // item is still open (the next change of the same parameter merges)
  {
    p3h_shot_t live1;
    MUST(p3h_shoot(NULL, &live1));
    CHECK(!p3h_compare("live render before vs after the history_end renders", &live0, &live1),
          "the live render changed after history_end renders");
    p3h_free(&live1);
    snprintf(v, sizeof(v), "%.9g", ev + 0.35);
    MUST(dtb_param_set("exposure", 0, "exposure", v));
    const int n2 = p3h_count();
    fprintf(stdout, "RESULT items after a further change of the open item: %d (before %d)\n", n2, n);
    CHECK(n2 == n, "the open history item was closed by a history_end render (%d -> %d items)", n, n2);
    snprintf(v, sizeof(v), "%.9g", ev + 0.3);
    MUST(dtb_param_set("exposure", 0, "exposure", v));
    g_free(hist0);
    hist0 = p3h_history();
  }

  // 3. equal to goto + render + goto back
  for(int i = 0; i < npos; i++)
  {
    p3h_shot_t ref;
    MUST(dtb_history_goto(pos[i]));
    const int r = p3h_shoot(NULL, &ref);
    MUST(dtb_history_goto(n));
    CHECK(r == DTB_OK, "goto %d render returned %d", pos[i], r);
    char what[96];
    snprintf(what, sizeof(what), "history_end %d vs goto %d + render + goto back", pos[i], pos[i]);
    if(!r) CHECK(!p3h_compare(what, &at[i], &ref), "%s: pixels differ", what);
    p3h_free(&ref);
  }

  // 4. bad positions
  {
    int w = 0, h = 0;
    char o[64];
    snprintf(o, sizeof(o), "{\"history_end\": %d}", n + 1);
    int r = dtb_render_size_ex(1.0f, o, &w, &h);
    CHECK(r == DTB_E_ARG, "history_end n+1 returned %d", r);
    r = dtb_render_size_ex(1.0f, "{\"history_end\": -1}", &w, &h);
    CHECK(r == DTB_E_ARG, "history_end -1 returned %d", r);
    r = dtb_render_size_ex(1.0f, "{\"history_end\": \"2\"}", &w, &h);
    CHECK(r == DTB_E_TYPE, "history_end as a string returned %d", r);
    r = dtb_render_size_ex(1.0f, "{\"history_end\": null}", &w, &h);
    CHECK(r == DTB_OK, "history_end null returned %d", r);
    fprintf(stdout, "RESULT bad positions refused\n");
  }

  // 5. cancel: a full-size render of position 1 cancelled from another thread
  {
    int w, h;
    MUST(dtb_render_size_ex(1.0f, "{\"history_end\": 1}", &w, &h));
    uint8_t *buf = malloc((size_t)4 * w * h);
    double t0 = now();
    MUST(dtb_render_region_ex(0, 0, w, h, 1.0f, "{\"history_end\": 1}", buf));
    const double t_full = now() - t0;
    // the pipe cache holds that window now: the cancelled render asks for
    // another one, so that it runs through the modules again
    w -= 8;
    h -= 8;
    cancel_arg_t a = { 0.3 * t_full, 0 };
    pthread_t thr;
    pthread_create(&thr, NULL, cancel_thread, &a);
    t0 = now();
    const int r = dtb_render_region_ex(0, 0, w, h, 1.0f, "{\"history_end\": 1}", buf);
    const double t1 = now();
    pthread_join(thr, NULL);
    fprintf(stdout, "RESULT cancel after %.3f s: returned %d, %.3f s after the cancel (render alone %.3f s)\n",
            a.t_cancel - t0, r, t1 - a.t_cancel, t_full);
    CHECK(r == DTB_E_CANCELLED, "history_end render not cancelled (returned %d)", r);
    free(buf);
    char *h2 = p3h_history(), *u2 = p3h_undo();
    CHECK(!strcmp(h2, hist0), "history() changed after a cancelled history_end render");
    CHECK(!strcmp(u2, undo0), "undo_state() changed after a cancelled history_end render");
    g_free(h2);
    g_free(u2);
    p3h_shot_t live2, again;
    MUST(p3h_shoot(NULL, &live2));
    CHECK(!p3h_compare("live render after the cancel vs before", &live0, &live2), "live render changed after cancel");
    MUST(p3h_shoot("{\"history_end\": 1}", &again));
    CHECK(!p3h_compare("history_end 1 after the cancel vs before", &at[1], &again),
          "history_end 1 differs after the cancel");
    p3h_free(&live2);
    p3h_free(&again);
  }

  for(int i = 0; i < npos; i++) p3h_free(&at[i]);
  p3h_free(&live0);
  g_free(hist0);
  g_free(undo0);
  MUST(dtb_close());
  return g_fail;
}

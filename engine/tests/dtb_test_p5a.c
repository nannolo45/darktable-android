// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Brief P5a bridge test (included by dtb_test.c):
 *   modaudit <raw> <xmp> <outdir>  C40: every module of the pipe, one at a
 *              time, switched on with its default parameters for this image
 *              on top of the test edit, rendered at full size by the bridge,
 *              the state written as an XMP, then undone. After each module the
 *              command given in DTB_TEST_P5A_HOOK is run with the operation,
 *              the XMP and the bridge's picture (run_tests.sh renders the XMP
 *              with darktable-cli, compares and deletes the pictures, so the
 *              disk never holds more than one module's pictures).
 *              Modules already on in the test edit, and modules without an
 *              on/off switch, are not switched: the suite's T1 render covers
 *              them. Writes p5a_modules.json (every instance's flags, without
 *              parameters) and p5a_progress.txt ("start op" / "end op ...");
 *              with DTB_TEST_P5A_SKIP naming that file, operations listed in it
 *              are skipped (run_tests.sh restarts the command after a crash).
 *              RESULT lines: one per module, "RESULT module <op> <state> ...".
 *              Checks: the undo returns the edit exactly (modules and history).
 */
#include <stdarg.h>

static gboolean p5a_listed(const char *file, const char *op)
{
  gchar *txt = NULL;
  if(!file || !g_file_get_contents(file, &txt, NULL, NULL)) return FALSE;
  gboolean found = FALSE;
  gchar **lines = g_strsplit(txt, "\n", -1);
  for(gchar **l = lines; *l && !found; l++)
  {
    gchar **w = g_strsplit(*l, " ", 3);
    if(w[0] && w[1] && !strcmp(w[1], op)) found = TRUE;
    g_strfreev(w);
  }
  g_strfreev(lines);
  g_free(txt);
  return found;
}

static void p5a_progress(const char *file, const char *fmt, ...)
{
  FILE *f = fopen(file, "a");
  if(!f) return;
  va_list ap;
  va_start(ap, fmt);
  vfprintf(f, fmt, ap);
  va_end(ap);
  fputc('\n', f);
  fclose(f);
}

static int cmd_modaudit(const char *raw, const char *xmp, const char *outdir)
{
  const char *hook = g_getenv("DTB_TEST_P5A_HOOK");
  const char *skip = g_getenv("DTB_TEST_P5A_SKIP");
  char *progress = path_join(outdir, "p5a_progress.txt");
  MUST(dtb_open(raw, xmp));
  char *base = get_modules_str();
  const int hist0 = history_count(NULL);

  // the instance list with flags, without parameters, for tools/module-audit.py
  JsonNode *bn = json_from_string(base, NULL);
  JsonArray *mods = json_node_get_array(bn);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_array(b);
  for(guint i = 0; i < json_array_get_length(mods); i++)
  {
    JsonObject *o = json_array_get_object_element(mods, i);
    json_builder_begin_object(b);
    static const char *keys[] = { "op", "name", "priority", "iop_order", "enabled", "can_switch", "default_enabled",
                                  "hidden", "deprecated", "one_instance", "supports_blending", "version", "in_history" };
    for(size_t k = 0; k < sizeof(keys) / sizeof(keys[0]); k++)
      if(json_object_has_member(o, keys[k]))
      {
        json_builder_set_member_name(b, keys[k]);
        json_builder_add_value(b, json_node_copy(json_object_get_member(o, keys[k])));
      }
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  JsonGenerator *g = json_generator_new();
  JsonNode *root = json_builder_get_root(b);
  json_generator_set_root(g, root);
  json_generator_set_pretty(g, TRUE);
  char *mpath = path_join(outdir, "p5a_modules.json");
  json_generator_to_file(g, mpath, NULL);
  json_node_unref(root);
  g_object_unref(g);
  g_object_unref(b);

  int switched = 0, on_in_base = 0, no_switch = 0, refused = 0, skipped = 0, total = 0;
  for(guint i = 0; i < json_array_get_length(mods); i++)
  {
    JsonObject *o = json_array_get_object_element(mods, i);
    if(json_object_get_int_member(o, "priority") != 0) continue;
    const char *op = json_object_get_string_member(o, "op");
    total++;
    if(json_object_get_boolean_member(o, "enabled"))
    {
      fprintf(stdout, "RESULT module %s on_in_base\n", op);
      on_in_base++;
      continue;
    }
    if(!json_object_get_boolean_member(o, "can_switch"))
    {
      fprintf(stdout, "RESULT module %s no_switch\n", op);
      no_switch++;
      continue;
    }
    if(p5a_listed(skip, op))
    {
      fprintf(stdout, "RESULT module %s skipped (listed in %s)\n", op, skip);
      skipped++;
      continue;
    }
    p5a_progress(progress, "start %s", op);
    const int rc = dtb_set_enabled(op, 0, 1);
    if(rc != DTB_OK)
    {
      fprintf(stdout, "RESULT module %s refused %d %s\n", op, rc, dtb_last_error());
      p5a_progress(progress, "end %s refused %d", op, rc);
      refused++;
      continue;
    }
    MUST(dtb_history_seal());
    // is it on in the pipe now?
    gboolean on = FALSE;
    {
      char *now = get_modules_str();
      JsonNode *nn = json_from_string(now, NULL);
      JsonArray *na = json_node_get_array(nn);
      for(guint k = 0; k < json_array_get_length(na); k++)
      {
        JsonObject *x = json_array_get_object_element(na, k);
        if(!strcmp(json_object_get_string_member(x, "op"), op) && json_object_get_int_member(x, "priority") == 0)
          on = json_object_get_boolean_member(x, "enabled");
      }
      json_node_unref(nn);
      dtb_free(now);
    }
    int w = 0, h = 0;
    double secs = 0.0;
    gchar *name = g_strdup_printf("p5a_%s", op);
    gchar *xname = g_strdup_printf("%s.xmp", name), *pname = g_strdup_printf("%s_bridge.ppm", name);
    char *xpath = path_join(outdir, xname), *ppath = path_join(outdir, pname);
    int rr = dtb_render_size(1.0f, &w, &h);
    uint8_t *buf = NULL;
    if(rr == DTB_OK)
    {
      buf = malloc((size_t)4 * w * h);
      const double t0 = now();
      rr = dtb_render_region(0, 0, w, h, 1.0f, buf);
      secs = now() - t0;
    }
    if(rr == DTB_OK)
    {
      write_ppm(ppath, buf, w, h);
      MUST(dtb_xmp_write(xpath));
      fprintf(stdout, "RESULT module %s switched on=%d %dx%d %.2f s\n", op, on, w, h, secs);
      fflush(stdout);
      p5a_progress(progress, "end %s switched on=%d %dx%d %.2f", op, on, w, h, secs);
      if(hook)
      {
        gchar *q1 = g_shell_quote(op), *q2 = g_shell_quote(xpath), *q3 = g_shell_quote(ppath);
        gchar *cmd = g_strdup_printf("%s %s %s %s", hook, q1, q2, q3);
        const int hr = system(cmd);
        if(hr != 0) fprintf(stdout, "RESULT hook %s exit %d\n", op, hr);
        g_free(cmd);
        g_free(q1);
        g_free(q2);
        g_free(q3);
      }
      switched++;
    }
    else
    {
      fprintf(stdout, "RESULT module %s render_failed %d %s\n", op, rr, dtb_last_error());
      p5a_progress(progress, "end %s render_failed %d", op, rr);
      refused++;
    }
    free(buf);
    g_free(name);
    g_free(xname);
    g_free(pname);
    // back to the test edit
    MUST(dtb_undo());
    char *back = get_modules_str();
    const int hist1 = history_count(NULL);
    const gboolean same = !strcmp(back, base) && hist1 == hist0;
    CHECK(same, "undo after %s did not return the edit (history %d, was %d)", op, hist1, hist0);
    dtb_free(back);
    if(!same)
    {
      // carry on from a fresh session of the test edit
      MUST(dtb_close());
      MUST(dtb_open(raw, xmp));
    }
  }
  fprintf(stdout, "RESULT modaudit %d modules: %d switched on and rendered, %d on in the test edit, %d without a switch, "
          "%d refused or failed, %d skipped\n", total, switched, on_in_base, no_switch, refused, skipped);
  json_node_unref(bn);
  dtb_free(base);
  MUST(dtb_close());
  return g_fail;
}

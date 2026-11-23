// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tests of P3g (docs/design/engine-api.md 6.10), part of the test driver:
 * dtb_test.c includes this file after dtb_test_p3d.c.
 *
 *   exportprog <raw> <xmp> <outdir>  C27: export progress events in order,
 *                                    ending at 100 %; cancel at several points
 *                                    (from the callback, from another thread,
 *                                    while writing) leaves no file and keeps
 *                                    an older file at the path; run_tests.sh
 *                                    compares the pixels of the first export
 *                                    with darktable-cli's and with a second
 *                                    export, the export after the cancels and
 *                                    dtb_export's (JPEG metadata carries the
 *                                    export time, so bytes may differ)
 */

typedef struct
{
  int n;
  int step[512], module[512], modules[512];
  double fraction[512];
  int cancel_step, cancel_module;  // cancel when this step (and module) is reached; -1 = never
} p3g_log_t;

static void p3g_cb(int step, int module, int modules, double fraction, void *user)
{
  p3g_log_t *l = user;
  if(l->n < 512)
  {
    l->step[l->n] = step;
    l->module[l->n] = module;
    l->modules[l->n] = modules;
    l->fraction[l->n] = fraction;
    l->n++;
  }
  if(l->cancel_step == step && (step != DTB_EXPORT_PROCESSING || module >= l->cancel_module))
    dtb_export_cancel();
}

/* files in dir whose name holds the bridge's temporary marker */
static int p3g_leftovers(const char *dir)
{
  int n = 0;
  GDir *d = g_dir_open(dir, 0, NULL);
  if(!d) return -1;
  const char *f;
  while((f = g_dir_read_name(d)))
    if(strstr(f, ".dtb-part-")) n++;
  g_dir_close(d);
  return n;
}

static gboolean p3g_same_file(const char *a, const char *b)
{
  gchar *x = NULL, *y = NULL;
  gsize nx = 0, ny = 0;
  const gboolean ok = g_file_get_contents(a, &x, &nx, NULL) && g_file_get_contents(b, &y, &ny, NULL)
                      && nx == ny && memcmp(x, y, nx) == 0;
  g_free(x);
  g_free(y);
  return ok;
}

typedef struct { double delay; double t; } p3g_cancel_arg_t;

static void *p3g_cancel_thread(void *p)
{
  p3g_cancel_arg_t *a = p;
  usleep((useconds_t)(a->delay * 1e6));
  a->t = now();
  dtb_export_cancel();
  return NULL;
}

static int cmd_exportprog(const char *raw, const char *xmp, const char *outdir)
{
  MUST(dtb_open(raw, xmp));
  const char *opts = "{\"format\":\"jpeg\",\"quality\":95}";  // as X1's export and darktable-cli's
  char *first = path_join(outdir, "p3g_export.jpg");
  unlink(first);

  // 1. events in order, ending at 100 %
  p3g_log_t *l = calloc(1, sizeof(*l));
  l->cancel_step = -1;
  const double t0 = now();
  MUST(dtb_export_ex(first, opts, p3g_cb, l));
  const double t_export = now() - t0;
  struct stat sb;
  CHECK(stat(first, &sb) == 0 && sb.st_size > 0, "export wrote nothing");
  int writing = 0, done = 0, prep = 0, proc = 0, order_ok = 1, back = 0;
  int last_step = -1, last_module = 0, modules = 0;
  for(int i = 0; i < l->n; i++)
  {
    const int s = l->step[i];
    if(s < last_step) order_ok = 0;
    if(i > 0 && l->fraction[i] < l->fraction[i - 1]) back = 1;
    if(s == DTB_EXPORT_PREPARING) prep++;
    if(s == DTB_EXPORT_PROCESSING)
    {
      proc++;
      if(l->module[i] != last_module + 1) order_ok = 0;
      last_module = l->module[i];
      modules = l->modules[i];
    }
    if(s == DTB_EXPORT_WRITING) writing++;
    if(s == DTB_EXPORT_DONE) done++;
    last_step = s;
  }
  fprintf(stdout, "RESULT events %d: preparing %d, processing %d (modules counted %d), writing %d, done %d; export %.3f s\n",
          l->n, prep, proc, modules, writing, done, t_export);
  for(int i = 0; i < l->n && i < 80; i++)
    fprintf(stdout, "EVENT %d step %d module %d of %d fraction %.4f\n", i, l->step[i], l->module[i], l->modules[i],
            l->fraction[i]);
  CHECK(l->n >= 4 && l->step[0] == DTB_EXPORT_PREPARING && l->fraction[0] == 0.0, "the first event is not preparing at 0");
  CHECK(prep == 1 && writing == 1 && done == 1, "preparing %d, writing %d, done %d times (1 each expected)", prep, writing,
        done);
  // an enabled module whose pixel code is not reached through process_plain is
  // not seen; the count is reported, the bar then jumps to writing
  CHECK(proc >= 2 && last_module <= modules, "processing events %d for %d modules", proc, modules);
  fprintf(stdout, "RESULT modules seen %d of %d enabled in the export pipe\n", last_module, modules);
  CHECK(order_ok, "steps or module numbers out of order");
  CHECK(!back, "the fraction went back");
  CHECK(l->n > 0 && l->step[l->n - 1] == DTB_EXPORT_DONE && l->fraction[l->n - 1] == 1.0,
        "the last event is not done at 100 %%");

  // a second export with nothing in between: darktable's JPEG carries the
  // export time in its metadata, so two exports need not be equal byte for
  // byte; run_tests.sh compares the decoded pixels of all of them
  char *control = path_join(outdir, "p3g_control.jpg");
  unlink(control);
  MUST(dtb_export_ex(control, opts, NULL, NULL));
  fprintf(stdout, "RESULT two exports in a row: %s byte for byte\n", p3g_same_file(first, control) ? "equal" : "not equal");

  // 2. cancels: each leaves no file at the path and no temporary file; an
  // older file at the path stays as it was
  char *path = path_join(outdir, "p3g_cancel.jpg");
  char *older = path_join(outdir, "p3g_older.jpg");
  const struct { const char *what; int step, module; gboolean keep_older; } cases[] = {
    { "while preparing", DTB_EXPORT_PREPARING, 0, FALSE },
    { "at the first module", DTB_EXPORT_PROCESSING, 1, FALSE },
    { "half-way through the modules", DTB_EXPORT_PROCESSING, modules / 2, FALSE },
    { "at the last module", DTB_EXPORT_PROCESSING, modules, FALSE },
    { "while writing", DTB_EXPORT_WRITING, 0, FALSE },
    { "half-way, over an older file", DTB_EXPORT_PROCESSING, modules / 2, TRUE },
  };
  for(size_t k = 0; k < sizeof(cases) / sizeof(cases[0]); k++)
  {
    unlink(path);
    if(cases[k].keep_older) g_file_set_contents(path, "older file", -1, NULL);
    memset(l, 0, sizeof(*l));
    l->cancel_step = cases[k].step;
    l->cancel_module = cases[k].module;
    const double c0 = now();
    const int r = dtb_export_ex(path, opts, p3g_cb, l);
    const double dt = now() - c0;
    const int last = l->n ? l->step[l->n - 1] : -1;
    gchar *content = NULL;
    const gboolean exists = g_file_test(path, G_FILE_TEST_EXISTS);
    if(exists) g_file_get_contents(path, &content, NULL, NULL);
    const int left = p3g_leftovers(outdir);
    fprintf(stdout, "RESULT cancel %s: returned %d after %.3f s (export alone %.3f s), %d events, last step %d, file %s, temporary files %d\n",
            cases[k].what, r, dt, t_export, l->n, last,
            !exists ? "none" : (content && !strcmp(content, "older file")) ? "the older one" : "WRITTEN", left);
    CHECK(r == DTB_E_CANCELLED, "cancel %s: returned %d", cases[k].what, r);
    CHECK(last != DTB_EXPORT_DONE, "cancel %s: done was reported", cases[k].what);
    if(cases[k].keep_older)
      CHECK(exists && content && !strcmp(content, "older file"), "cancel %s: the older file changed", cases[k].what);
    else
      CHECK(!exists, "cancel %s: a file was left at the path", cases[k].what);
    CHECK(left == 0, "cancel %s: %d temporary files left", cases[k].what, left);
    g_free(content);
  }
  unlink(path);

  // 3. cancel from another thread while the export runs
  for(int k = 0; k < 2; k++)
  {
    p3g_cancel_arg_t a = { (k == 0 ? 0.3 : 0.6) * t_export, 0 };
    pthread_t th;
    memset(l, 0, sizeof(*l));
    l->cancel_step = -1;
    const double c0 = now();
    pthread_create(&th, NULL, p3g_cancel_thread, &a);
    const int r = dtb_export_ex(path, opts, p3g_cb, l);
    const double t1 = now();
    pthread_join(th, NULL);
    const gboolean exists = g_file_test(path, G_FILE_TEST_EXISTS);
    fprintf(stdout, "RESULT cancel from a thread after %.3f s: returned %d, %.3f s after the cancel, %d events, file %s, temporary files %d\n",
            a.t - c0, r, t1 - a.t, l->n, exists ? "WRITTEN" : "none", p3g_leftovers(outdir));
    // a cancel that arrives after the file was renamed is too late: then the export is complete
    if(r == DTB_OK)
      CHECK(exists && l->n && l->step[l->n - 1] == DTB_EXPORT_DONE, "an export that was not cancelled is incomplete");
    else
      CHECK(r == DTB_E_CANCELLED && !exists, "cancel from a thread: returned %d, file %s", r, exists ? "left" : "none");
    CHECK(p3g_leftovers(outdir) == 0, "temporary files left after a cancel from a thread");
    unlink(path);
  }

  // 4. a cancel while no export runs has no effect on the next export, and
  // that export is the first one byte for byte
  dtb_export_cancel();
  char *again = path_join(outdir, "p3g_again.jpg");
  unlink(again);
  memset(l, 0, sizeof(*l));
  l->cancel_step = -1;
  const int r = dtb_export_ex(again, opts, p3g_cb, l);
  fprintf(stdout, "RESULT export after the cancels: returned %d, %d events, %s byte for byte to the first export (pixels: run_tests.sh)\n",
          r, l->n, r == DTB_OK && p3g_same_file(first, again) ? "equal" : "not equal");
  CHECK(r == DTB_OK, "export after an idle cancel returned %d", r);
  CHECK(g_file_test(again, G_FILE_TEST_EXISTS), "the export after the cancels wrote nothing");
  // dtb_export (no callback) is the same export; run_tests.sh compares its pixels
  char *plain = path_join(outdir, "p3g_plain.jpg");
  unlink(plain);
  MUST(dtb_export(plain, opts));
  free(l);
  free(first); free(path); free(older); free(again); free(plain); free(control);
  return g_fail;
}

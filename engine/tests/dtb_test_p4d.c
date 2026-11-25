// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Brief P4d bridge test (included by dtb_test.c):
 *   opencl_lib <raw> <xmp> <outdir>  C4d: dtb_opencl_library is honoured. The
 *              runner's OpenCL library is given by its absolute path
 *              (DTB_TEST_OCL_LIB, PoCL through the ICD loader) after a path
 *              that does not exist: the first try fails with dlerror's text,
 *              the second is handed to darktable, darktable opens exactly that
 *              file and finds the PoCL device; darktable's OpenCL log is in
 *              dtb_opencl_diagnostics and still on stdout; the platforms and
 *              devices are reported; asking again after the start is refused.
 *              p4d_opencl.txt
 */

static int cmd_opencl_lib(const char *raw, const char *xmp, const char *outdir)
{
  (void)raw; (void)xmp;
  const char *lib = g_getenv("DTB_TEST_OCL_LIB");
  CHECK(lib && lib[0] == '/', "DTB_TEST_OCL_LIB names the runner's OpenCL library by absolute path (got %s)", lib ? lib : "nothing");
  if(!lib || lib[0] != '/') return 1;
  GString *out = g_string_new("brief P4d, C4d: OpenCL library chosen by the app, x86_64 Linux CI runner (PoCL), not phone numbers\n");
  char *s = NULL;
  MUST(dtb_opencl_diagnostics(&s));
  JsonNode *n;
  JsonObject *o = p4b_obj(s, &n);
  CHECK(json_object_get_boolean_member(o, "built"), "built with OpenCL");
  CHECK(!json_object_get_boolean_member(o, "started"), "not started before the switch");
  CHECK(json_array_get_length(json_object_get_array_member(o, "library_tries")) == 0, "no library tried before the switch");
  json_node_unref(n);

  gchar *names = g_strdup_printf("/nonexistent/libOpenCL.so:%s", lib);
  MUST(dtb_opencl_library(names));
  MUST(dtb_opencl_set(1, &s));
  o = p4b_obj(s, &n);
  const gboolean running = json_object_get_boolean_member(o, "running");
  CHECK(running, "OpenCL running with the given library");
  json_node_unref(n);

  MUST(dtb_opencl_diagnostics(&s));
  g_string_append_printf(out, "diagnostics after the switch: %s\n", s);
  o = p4b_obj(s, &n);
  CHECK(!g_strcmp0(json_object_get_string_member(o, "preferred"), names), "preferred list kept");
  JsonArray *tries = json_object_get_array_member(o, "library_tries");
  CHECK(json_array_get_length(tries) == 2, "two tries: the missing path, then the runner's library (got %u)", json_array_get_length(tries));
  if(json_array_get_length(tries) >= 2)
  {
    JsonObject *t0 = json_array_get_object_element(tries, 0), *t1 = json_array_get_object_element(tries, 1);
    CHECK(!strcmp(json_object_get_string_member(t0, "name"), "/nonexistent/libOpenCL.so") && !json_object_get_boolean_member(t0, "loaded"),
          "first try: the missing path, not opened");
    const char *err = json_object_get_null_member(t0, "error") ? NULL : json_object_get_string_member(t0, "error");
    CHECK(err && strstr(err, "nonexistent"), "dlerror's text kept (%s)", err ? err : "null");
    CHECK(!strcmp(json_object_get_string_member(t0, "from"), "app"), "from the app");
    CHECK(!strcmp(json_object_get_string_member(t1, "name"), lib) && json_object_get_boolean_member(t1, "loaded")
          && json_object_get_boolean_member(t1, "symbol") && json_object_get_int_member(t1, "platforms") >= 1,
          "second try: %s opened with at least one platform", lib);
    fprintf(stdout, "RESULT try0_error %s\n", err ? err : "null");
  }
  CHECK(!g_strcmp0(json_object_get_string_member(o, "chosen"), lib), "handed to darktable: %s", json_object_get_string_member(o, "chosen"));
  CHECK(!g_strcmp0(json_object_get_string_member(o, "loaded"), lib), "darktable opened %s (got %s)", lib, json_object_get_string_member(o, "loaded"));
  const char *log = json_object_get_null_member(o, "init_log") ? NULL : json_object_get_string_member(o, "init_log");
  gchar *want = g_strdup_printf("opencl library '%s' found", lib);
  CHECK(log && strstr(log, want), "darktable's OpenCL log names the library (%s)", want);
  CHECK(log && strstr(log, "[opencl_init] FINALLY"), "darktable's OpenCL log is complete");
  JsonArray *plats = json_object_get_array_member(o, "platforms");
  guint devs = 0;
  for(guint i = 0; i < json_array_get_length(plats); i++)
  {
    JsonObject *p = json_array_get_object_element(plats, i);
    JsonArray *d = json_object_get_array_member(p, "devices");
    devs += json_array_get_length(d);
    for(guint j = 0; j < json_array_get_length(d); j++)
    {
      JsonObject *x = json_array_get_object_element(d, j);
      fprintf(stdout, "RESULT platform %s device %s type %s image %d memory_mb %" G_GINT64_FORMAT "\n",
              json_object_get_string_member(p, "name"), json_object_get_string_member(x, "name"),
              json_object_get_string_member(x, "type"), json_object_get_boolean_member(x, "image_support"),
              json_object_get_int_member(x, "global_memory") / 1048576);
    }
  }
  CHECK(devs >= 1, "the library's platforms and devices are reported (%u devices)", devs);
  fprintf(stdout, "RESULT log_bytes %zu running %d\n", log ? strlen(log) : (size_t)0, running);
  g_free(want);
  json_node_unref(n);

  const int again = dtb_opencl_library(lib);
  CHECK(again == DTB_E_STATE, "dtb_opencl_library after the start refused with DTB_E_STATE (got %d)", again);
  MUST(dtb_opencl_set(0, NULL));
  g_free(names);
  char path[4096];
  snprintf(path, sizeof(path), "%s/p4d_opencl.txt", outdir);
  CHECK(g_file_set_contents(path, out->str, -1, NULL), "write %s", path);
  g_string_free(out, TRUE);
  return g_fail;
}

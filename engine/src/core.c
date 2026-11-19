// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: errors, JSON helpers, lifecycle, engine information and
 * thread control (Engine API v1, section 2.1).
 *
 * The bridge drives upstream darktable, started without its desktop
 * interface. Where upstream code assumes the desktop interface, the bridge
 * does the non-interface part of that code itself; each place says which
 * upstream function it follows. Nothing in upstream is changed.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <sched.h>

#include "dtb_internal.h"

#include "control/conf.h"
#include "imageio/imageio_common.h"
#include "imageio/imageio_module.h"

#include <omp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

dtb_session_t dtb_S;
gboolean dtb_inited = FALSE;

static char g_err[2048];
static gchar *g_argv_store[32];
static int g_init_threads = 0;      // darktable.num_openmp_threads after dt_init
static cpu_set_t g_init_cpus;       // the engine thread's CPUs at dtb_init
static gboolean g_have_init_cpus = FALSE;
static GArray *g_thread_cpus = NULL;  // CPUs set by dtb_set_thread_cpus

int dtb__err(int code, const char *fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(g_err, sizeof(g_err), fmt, ap);
  va_end(ap);
  return code;
}

const char *dtb_last_error(void) { return g_err; }
void dtb_free(void *p) { g_free(p); }

char *dtb__json_finish(JsonBuilder *b)
{
  JsonGenerator *gen = json_generator_new();
  JsonNode *root = json_builder_get_root(b);
  json_generator_set_root(gen, root);
  gchar *s = json_generator_to_data(gen, NULL);
  json_node_free(root);
  g_object_unref(gen);
  g_object_unref(b);
  return s;
}

JsonNode *dtb__json_parse(const char *text)
{
  if(!text)
  {
    dtb__err(DTB_E_ARG, "no JSON given");
    return NULL;
  }
  GError *e = NULL;
  JsonNode *n = json_from_string(text, &e);
  if(!n)
  {
    dtb__err(DTB_E_ARG, "invalid JSON: %s", e ? e->message : "empty");
    if(e) g_error_free(e);
  }
  return n;
}

/* ------------------------------------------------------------------ */
/* lifecycle                                                          */

double dtb__t(void)
{
  return g_get_monotonic_time() * 1e-3;
}

void dtb__tlog(const char *what, const double t0)
{
  static int on = -1;
  if(on < 0) on = g_getenv("DTB_TIMING") != NULL;
  if(on) fprintf(stderr, "DTB_TIMING %s %.3f\n", what, dtb__t() - t0);
}

int dtb_init(const dtb_paths_t *p, int threads)
{
  if(dtb_inited) return dtb__err(DTB_E_STATE, "already initialised");
  if(!p || !p->datadir || !p->moduledir || !p->configdir || !p->cachedir || !p->library)
    return dtb__err(DTB_E_ARG, "configdir, cachedir, datadir, moduledir and library are required");
  if(threads < 0) return dtb__err(DTB_E_ARG, "threads must be >= 0");

  int argc = 0;
  gchar **a = g_argv_store;
  a[argc++] = g_strdup("dtbridge");
  a[argc++] = g_strdup("--library");    a[argc++] = g_strdup(p->library);
  a[argc++] = g_strdup("--configdir");  a[argc++] = g_strdup(p->configdir);
  a[argc++] = g_strdup("--cachedir");   a[argc++] = g_strdup(p->cachedir);
  a[argc++] = g_strdup("--datadir");    a[argc++] = g_strdup(p->datadir);
  a[argc++] = g_strdup("--moduledir");  a[argc++] = g_strdup(p->moduledir);
  if(p->tmpdir) { a[argc++] = g_strdup("--tmpdir"); a[argc++] = g_strdup(p->tmpdir); }
  if(threads > 0)
  {
    a[argc++] = g_strdup("--threads");
    a[argc++] = g_strdup_printf("%d", threads);
  }
  // as darktable-cli (cli/main.c): the engine itself never writes a sidecar;
  // only dtb_xmp_write() does, after the version guard
  a[argc++] = g_strdup("--conf");
  a[argc++] = g_strdup("write_sidecar_files=never");
  // DTB_DT_DEBUG="perf,params": darktable's own debug output (-d perf ...),
  // for measurements only (P1c item 5)
  const char *dbg = g_getenv("DTB_DT_DEBUG");
  if(dbg)
  {
    gchar **parts = g_strsplit(dbg, ",", 6);
    for(int i = 0; parts[i] && argc < 30; i++)
    {
      a[argc++] = g_strdup("-d");
      a[argc++] = g_strdup(parts[i]);
    }
    g_strfreev(parts);
  }
  a[argc] = NULL;

  g_mkdir_with_parents(p->configdir, 0700);
  g_mkdir_with_parents(p->cachedir, 0700);
  if(p->tmpdir) g_mkdir_with_parents(p->tmpdir, 0700);

  // init_gui FALSE as darktable-cli; load_data TRUE = darktable-cli's
  // default (custom_presets), so presets and styles come from data.db
  if(dt_init(argc, a, FALSE, TRUE, NULL))
    return dtb__err(DTB_E_ENGINE, "dt_init failed");

  memset(&dtb_S, 0, sizeof(dtb_S));
  g_init_threads = darktable.num_openmp_threads;
  g_have_init_cpus = sched_getaffinity(0, sizeof(g_init_cpus), &g_init_cpus) == 0;
  if(!g_thread_cpus) g_thread_cpus = g_array_new(FALSE, FALSE, sizeof(int));
  g_array_set_size(g_thread_cpus, 0);
  dtb_inited = TRUE;
  return DTB_OK;
}

void dtb_shutdown(void)
{
  if(!dtb_inited) return;
  if(dtb_S.open) dtb_close();
  dt_cleanup();
  dtb_inited = FALSE;
  for(int i = 0; g_argv_store[i]; i++)
  {
    g_free(g_argv_store[i]);
    g_argv_store[i] = NULL;
  }
}

/* ------------------------------------------------------------------ */
/* engine information                                                 */

/* count lib*.so files in <moduledir>/<subdir>; adds names (without "lib" and
 * ".so") to *names when names is not NULL */
static int _count_plugin_files(const char *subdir, GList **names)
{
  gchar *dir = g_build_filename(darktable.plugindir, subdir, NULL);
  GDir *d = g_dir_open(dir, 0, NULL);
  g_free(dir);
  if(!d) return 0;
  int n = 0;
  const gchar *f;
  while((f = g_dir_read_name(d)))
  {
    if(!g_str_has_prefix(f, "lib") || !g_str_has_suffix(f, ".so")) continue;
    n++;
    if(names) *names = g_list_prepend(*names, g_strndup(f + 3, strlen(f) - 6));
  }
  g_dir_close(d);
  return n;
}

typedef const char *(*_plugin_name_f)(const void *);
static const char *_iop_name(const void *m) { return ((const dt_iop_module_so_t *)m)->op; }
static const char *_format_name(const void *m)
{
  return ((const dt_imageio_module_format_t *)m)->plugin_name;
}
static const char *_storage_name(const void *m)
{
  return ((const dt_imageio_module_storage_t *)m)->plugin_name;
}

/* one "kind" object; adds its missing files to *missing as "<kind>/<name>" */
static void _plugins_kind(JsonBuilder *b, const char *kind, const char *subdir, GList *loaded,
                          _plugin_name_f name, int *files_total, int *loaded_total,
                          GList **missing)
{
  GList *files = NULL;
  const int nfiles = _count_plugin_files(subdir, &files);
  files = g_list_sort(files, (GCompareFunc)g_strcmp0);
  json_builder_set_member_name(b, kind);
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "files");
  json_builder_add_int_value(b, nfiles);
  json_builder_set_member_name(b, "loaded");
  json_builder_add_int_value(b, g_list_length(loaded));
  json_builder_set_member_name(b, "not_loaded");
  json_builder_begin_array(b);
  for(GList *f = files; f; f = g_list_next(f))
  {
    gboolean found = FALSE;
    for(GList *l = loaded; l && !found; l = g_list_next(l))
      found = !g_strcmp0(name(l->data), f->data);
    if(!found)
    {
      json_builder_add_string_value(b, f->data);
      *missing = g_list_append(*missing, g_strdup_printf("%s/%s", kind, (char *)f->data));
    }
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
  *files_total += nfiles;
  *loaded_total += g_list_length(loaded);
  g_list_free_full(files, g_free);
}

int dtb_engine_info(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "darktable_version");
  json_builder_add_string_value(b, darktable_package_version);
  json_builder_set_member_name(b, "api_version");
  json_builder_add_int_value(b, DTB_API_VERSION);
  json_builder_set_member_name(b, "threads");
  json_builder_add_int_value(b, darktable.num_openmp_threads);
  json_builder_set_member_name(b, "threads_max");
  json_builder_add_int_value(b, g_init_threads);
  json_builder_set_member_name(b, "thread_cpus");
  json_builder_begin_array(b);
  for(guint i = 0; g_thread_cpus && i < g_thread_cpus->len; i++)
    json_builder_add_int_value(b, g_array_index(g_thread_cpus, int, i));
  json_builder_end_array(b);

  json_builder_set_member_name(b, "plugins");
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "moduledir");
  json_builder_add_string_value(b, darktable.plugindir);
  int files = 0, loaded = 0;
  GList *missing = NULL;
  // dt_iop_load_modules_so: <moduledir>/plugins;
  // dt_imageio_load_modules_format/_storage: plugins/imageio/format, /storage
  _plugins_kind(b, "iop", "plugins", darktable.iop, _iop_name, &files, &loaded, &missing);
  _plugins_kind(b, "format", "plugins/imageio/format", darktable.imageio->plugins_format,
                _format_name, &files, &loaded, &missing);
  _plugins_kind(b, "storage", "plugins/imageio/storage", darktable.imageio->plugins_storage,
                _storage_name, &files, &loaded, &missing);
  json_builder_set_member_name(b, "files");
  json_builder_add_int_value(b, files);
  json_builder_set_member_name(b, "loaded");
  json_builder_add_int_value(b, loaded);
  json_builder_set_member_name(b, "missing");
  json_builder_begin_array(b);
  for(GList *m = missing; m; m = g_list_next(m)) json_builder_add_string_value(b, m->data);
  json_builder_end_array(b);
  g_list_free_full(missing, g_free);
  json_builder_set_member_name(b, "gui_only");
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "views");
  json_builder_add_int_value(b, _count_plugin_files("views", NULL));
  json_builder_set_member_name(b, "lighttable");
  json_builder_add_int_value(b, _count_plugin_files("plugins/lighttable", NULL));
  json_builder_end_object(b);
  json_builder_end_object(b);

  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* threads                                                            */

int dtb_set_threads(int n)
{
  NEED_INIT();
  // as dt_init's --threads (common/darktable.c), but capped at the start-up
  // count: some per-thread buffers are sized from it (dt_get_num_threads)
  darktable.num_openmp_threads = n <= 0 ? g_init_threads : CLAMP(n, 1, g_init_threads);
  return DTB_OK;
}

/* apply a CPU set to the calling thread and to every OpenMP worker. libgomp
 * (Linux) and the NDK's libomp (Android) keep a pool of worker threads; a
 * parallel region as large as the start-up thread count runs on all of
 * them, and each sets the affinity of its own thread. Threads created later
 * by the engine thread inherit its set. */
static int _apply_cpus(const cpu_set_t *set)
{
  if(sched_setaffinity(0, sizeof(*set), set) != 0)
    return dtb__err(DTB_E_ARG, "sched_setaffinity failed for the engine thread");
  int failed = 0;
#ifdef _OPENMP
#pragma omp parallel num_threads(g_init_threads) reduction(+ : failed)
  {
    if(sched_setaffinity(0, sizeof(*set), set) != 0) failed++;
  }
#endif
  if(failed) return dtb__err(DTB_E_ENGINE, "sched_setaffinity failed on %d worker threads", failed);
  return DTB_OK;
}

int dtb_set_thread_cpus(const int *cpu_ids, int n)
{
  NEED_INIT();
  if(n < 0 || (n > 0 && !cpu_ids)) return dtb__err(DTB_E_ARG, "bad CPU list");
  if(n == 0)
  {
    if(!g_have_init_cpus) return dtb__err(DTB_E_ENGINE, "start-up CPU set unknown");
    const int r = _apply_cpus(&g_init_cpus);
    if(r) return r;
    g_array_set_size(g_thread_cpus, 0);
    return DTB_OK;
  }
  cpu_set_t set;
  CPU_ZERO(&set);
  for(int i = 0; i < n; i++)
  {
    const int c = cpu_ids[i];
    if(c < 0 || c >= CPU_SETSIZE || (g_have_init_cpus && !CPU_ISSET(c, &g_init_cpus)))
      return dtb__err(DTB_E_ARG, "CPU %d is not available to this process", c);
    CPU_SET(c, &set);
  }
  const int r = _apply_cpus(&set);
  if(r) return r;
  g_array_set_size(g_thread_cpus, 0);
  for(int c = 0; c < CPU_SETSIZE; c++)
    if(CPU_ISSET(c, &set)) g_array_append_val(g_thread_cpus, c);
  return DTB_OK;
}

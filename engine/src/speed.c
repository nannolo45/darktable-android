// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Speed (brief P4b, engine-api.md 11): start-up options for darktable's
 * memory budget, the bridge pipelines' cache size and the decoded inputs
 * kept; OpenCL, opt-in, started late.
 *
 * darktable's memory model (common/darktable.c, dt_init and
 * dt_get_available_mem): every resource level has four numbers, fractions of
 * 1024 of the machine's memory: memory a pipeline may plan with (tiling is
 * decided against it), the single-buffer size, the thumbnail cache and the
 * share of an OpenCL device's memory. darktablerc's resource_<level> can
 * change them; the bridge passes them as --conf entries, so nothing is
 * written to darktablerc. A pipeline's cache of intermediate buffers may hold
 * (available memory / mem_fraction) bytes (develop/pixelpipe_cache.c,
 * dt_dev_pixelpipe_cache_checkmem); dt_dev_pixelpipe_init gives 8, the
 * darkroom's preview pipe 32 (develop/pixelpipe_hb.c). The image cache keeps
 * (worker threads + 1) decoded inputs per level (common/mipmap_cache.c).
 *
 * OpenCL: dt_init runs dt_opencl_init synchronously when there is no
 * interface, which would open the library and build the kernels at every
 * start. The bridge always passes --disable-opencl (DT_OPENCL_OPTION_EXCLUDE):
 * no library is opened at start. When the app switches the GPU on (allowed by
 * the "opencl" option), dtb_opencl_set runs dt_opencl_init once, as
 * desktop does from a background job after its modules are loaded
 * (_detect_opencl_job_run): modules only record their kernel names at load
 * (dt_opencl_create_kernel) and kernels are created per device on first use.
 */
#include "dtb_internal.h"

#include "common/darktable.h"
#include "common/mipmap_cache.h"
#include "common/opencl.h"
#ifdef HAVE_OPENCL
#include "common/dlopencl.h"
#endif
#include "control/conf.h"
#include "develop/pixelpipe_cache.h"

#include <dlfcn.h>
#include <glib/gstdio.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct dtb_speed_t
{
  gboolean opencl_allowed;
  gboolean opencl_started;
  gchar *opencl_library;
  gchar *kernel_dir;      // where darktable keeps compiled kernels; NULL = its cache folder
  int pipe_fraction[2];   // full, preview; 0 = darktable's
  int inputs_kept;        // 0 = darktable's
  gint64 renders, renders_gpu, renders_fallback;
  const char *last_render;
  // per render in flight
  gboolean cl_at_start;
  int errors_at_start;
} dtb_speed_t;

static dtb_speed_t g_speed = { .last_render = "none" };

#ifdef HAVE_OPENCL
// brief P4d: the library tries, darktable's OpenCL log, the platforms (below)
static void _cl_diag_reset(void);
#endif

void dtb__speed_reset(void)
{
#ifdef HAVE_OPENCL
  _cl_diag_reset();
#endif
  g_free(g_speed.opencl_library);
  g_free(g_speed.kernel_dir);
  memset(&g_speed, 0, sizeof(g_speed));
  g_speed.last_render = "none";
}

/* parse dtb_init_ex options into g_speed and the --conf entries for dt_init.
 * extra: where to append argv strings (g_strdup), *argc advanced. */
static int _parse(const char *json, gchar **argv, int *argc, const int max_argc)
{
  JsonNode *n = dtb__json_parse(json);
  if(!n) return DTB_E_ARG;
  if(!JSON_NODE_HOLDS_OBJECT(n))
  {
    json_node_unref(n);
    return dtb__err(DTB_E_ARG, "options must be a JSON object");
  }
  JsonObject *o = json_node_get_object(n);
  int r = DTB_OK;
  const char *level = NULL, *values = NULL;
  GList *members = json_object_get_members(o);
  for(GList *m = members; m && r == DTB_OK; m = g_list_next(m))
  {
    const char *k = m->data;
    JsonNode *v = json_object_get_member(o, k);
    if(!strcmp(k, "opencl"))
    {
      if(json_node_get_value_type(v) != G_TYPE_BOOLEAN) r = dtb__err(DTB_E_TYPE, "opencl is a boolean");
      else g_speed.opencl_allowed = json_node_get_boolean(v);
    }
    else if(!strcmp(k, "resource_level") || !strcmp(k, "resource_values"))
    {
      if(json_node_get_value_type(v) != G_TYPE_STRING) r = dtb__err(DTB_E_TYPE, "%s is a string", k);
      else if(!strcmp(k, "resource_level")) level = json_node_get_string(v);
      else values = json_node_get_string(v);
    }
    else if(!strcmp(k, "pipe_cache_fraction"))
    {
      JsonArray *a = JSON_NODE_HOLDS_ARRAY(v) ? json_node_get_array(v) : NULL;
      if(!a || json_array_get_length(a) != 2) r = dtb__err(DTB_E_TYPE, "pipe_cache_fraction is [full, preview]");
      else
        for(int i = 0; i < 2; i++)
        {
          const gint64 f = json_array_get_int_element(a, i);
          if(f < 0 || f > 1024) r = dtb__err(DTB_E_RANGE, "pipe_cache_fraction must be 0 to 1024");
          else g_speed.pipe_fraction[i] = (int)f;
        }
    }
    else if(!strcmp(k, "opencl_kernel_dir"))
    {
      if(json_node_get_value_type(v) != G_TYPE_STRING) r = dtb__err(DTB_E_TYPE, "opencl_kernel_dir is a string");
      else g_speed.kernel_dir = g_strdup(json_node_get_string(v));
    }
    else if(!strcmp(k, "inputs_kept"))
    {
      if(json_node_get_value_type(v) != G_TYPE_INT64) r = dtb__err(DTB_E_TYPE, "inputs_kept is an integer");
      else
      {
        const gint64 i = json_node_get_int(v);
        if(i < 0 || i > 64) r = dtb__err(DTB_E_RANGE, "inputs_kept must be 0 to 64");
        else g_speed.inputs_kept = (int)i;
      }
    }
    else r = dtb__err(DTB_E_ARG, "unknown option %s", k);
  }
  g_list_free(members);
  if(r == DTB_OK && values && !level) r = dtb__err(DTB_E_ARG, "resource_values needs resource_level");
  if(r == DTB_OK && level)
  {
    static const char *known[] = { "small", "default", "large", "unrestricted", NULL };
    gboolean ok = FALSE;
    for(int i = 0; known[i]; i++) ok |= !strcmp(level, known[i]);
    if(!ok) r = dtb__err(DTB_E_ARG, "resource_level must be small, default, large or unrestricted");
  }
  if(r == DTB_OK && values)
  {
    int a, b, c, d;
    char rest;
    if(sscanf(values, "%d %d %d %d %c", &a, &b, &c, &d, &rest) != 4 || a <= 0 || b <= 0 || c <= 0 || d <= 0)
      r = dtb__err(DTB_E_ARG, "resource_values must be four positive numbers");
  }
  if(r == DTB_OK && level && *argc + 4 < max_argc)
  {
    argv[(*argc)++] = g_strdup("--conf");
    argv[(*argc)++] = g_strdup_printf("resourcelevel=%s", level);
    if(values)
    {
      argv[(*argc)++] = g_strdup("--conf");
      argv[(*argc)++] = g_strdup_printf("resource_%s=%s", level, values);
    }
  }
  json_node_unref(n);
  return r;
}

int dtb__speed_options(const char *json, gchar **argv, int *argc, const int max_argc)
{
  dtb__speed_reset();
  if(json && json[0])
  {
    const int r = _parse(json, argv, argc, max_argc);
    if(r != DTB_OK) return r;
  }
  // always: dt_init would otherwise open the library and build the kernels
  // at every start; dtb_opencl_set starts OpenCL later when asked
  if(*argc < max_argc) argv[(*argc)++] = g_strdup("--disable-opencl");
  return DTB_OK;
}

/* after dt_init: the image cache's quotas for decoded inputs */
void dtb__speed_after_init(void)
{
  dt_mipmap_cache_t *m = darktable.mipmap_cache;
  if(!m || g_speed.inputs_kept <= 0) return;
  dt_pthread_mutex_lock(&m->mip_full.cache.lock);
  m->mip_full.cache.cost_quota = g_speed.inputs_kept;
  dt_pthread_mutex_unlock(&m->mip_full.cache.lock);
  dt_pthread_mutex_lock(&m->mip_f.cache.lock);
  m->mip_f.cache.cost_quota = g_speed.inputs_kept;
  dt_pthread_mutex_unlock(&m->mip_f.cache.lock);
}

/* a bridge pipeline was made (render.c _pipe_ensure) */
void dtb__speed_pipe_made(dtb_pipe_t *p, const gboolean full)
{
  const int f = g_speed.pipe_fraction[full ? 0 : 1];
  if(f > 0) p->pipe.cache.mem_fraction = f;
}

/* brief P4e: darktable's -d perf -d tiling -d opencl -d memory output of one render (dtb_render_trace) */
#define DTB_TRACE_MAX (256 * 1024)
static struct
{
  gboolean armed, active, cut;
  int muted, saved, fd;
  gchar *tmp, *log;
} g_trace = { .saved = -1, .fd = -1 };

static void _trace_begin(void)
{
  if(!g_trace.armed) return;
  g_trace.armed = FALSE;
  g_trace.fd = g_file_open_tmp("dtb-trace-XXXXXX.log", &g_trace.tmp, NULL);
  if(g_trace.fd < 0) return;
  fflush(stdout);
  g_trace.saved = dup(STDOUT_FILENO);
  if(g_trace.saved < 0 || dup2(g_trace.fd, STDOUT_FILENO) < 0)
  {
    if(g_trace.saved >= 0) close(g_trace.saved);
    close(g_trace.fd);
    g_trace.saved = g_trace.fd = -1;
    g_unlink(g_trace.tmp);
    g_clear_pointer(&g_trace.tmp, g_free);
    return;
  }
  g_trace.muted = darktable.unmuted;
  darktable.unmuted |= DT_DEBUG_PERF | DT_DEBUG_TILING | DT_DEBUG_OPENCL | DT_DEBUG_MEMORY;
  g_trace.active = TRUE;
}

static void _trace_end(void)
{
  if(!g_trace.active) return;
  g_trace.active = FALSE;
  fflush(stdout);
  darktable.unmuted = g_trace.muted;
  dup2(g_trace.saved, STDOUT_FILENO);
  close(g_trace.saved);
  close(g_trace.fd);
  g_trace.saved = g_trace.fd = -1;
  gchar *text = NULL;
  gsize len = 0;
  g_clear_pointer(&g_trace.log, g_free);
  g_trace.cut = FALSE;
  if(g_file_get_contents(g_trace.tmp, &text, &len, NULL))
  {
    // back to the real stdout too (the app's log ring and logcat)
    if(len) fwrite(text, 1, len, stdout);
    fflush(stdout);
    if(len > DTB_TRACE_MAX)
    {
      text[DTB_TRACE_MAX] = '\0';
      g_trace.cut = TRUE;
    }
    g_trace.log = g_utf8_make_valid(text, -1);
    g_free(text);
  }
  g_unlink(g_trace.tmp);
  g_clear_pointer(&g_trace.tmp, g_free);
}

void dtb__speed_render_begin(void)
{
  _trace_begin();
  g_speed.cl_at_start = dt_opencl_running();
  g_speed.errors_at_start = darktable.opencl ? darktable.opencl->error_count : 0;
}

void dtb__speed_render_end(void)
{
  _trace_end();
  const int errors = darktable.opencl ? darktable.opencl->error_count : 0;
  g_speed.renders++;
  if(errors != g_speed.errors_at_start)
  {
    g_speed.renders_fallback++;
    g_speed.last_render = "fallback";
  }
  else if(g_speed.cl_at_start)
  {
    g_speed.renders_gpu++;
    g_speed.last_render = "gpu";
  }
  else
    g_speed.last_render = "cpu";
}

static void _int(JsonBuilder *b, const char *k, const gint64 v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_int_value(b, v);
}

static void _bool(JsonBuilder *b, const char *k, const gboolean v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_boolean_value(b, v);
}

static void _str(JsonBuilder *b, const char *k, const char *v)
{
  json_builder_set_member_name(b, k);
  if(v) json_builder_add_string_value(b, v);
  else json_builder_add_null_value(b);
}

static int _fraction(const dtb_pipe_t *p, const int i)
{
  if(p->ready) return (int)p->pipe.cache.mem_fraction;
  return g_speed.pipe_fraction[i] > 0 ? g_speed.pipe_fraction[i] : 8;
}

int dtb_resources_info(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  const char *level = dt_conf_get_string_const("resourcelevel");
  _str(b, "level", level);
  gchar *key = g_strdup_printf("resource_%s", level ? level : "default");
  _str(b, "values", dt_conf_key_exists(key) ? dt_conf_get_string_const(key) : NULL);
  g_free(key);
  _int(b, "total_memory", (gint64)darktable.dtresources.total_memory);
  _int(b, "available_memory", (gint64)dt_get_available_mem());
  _int(b, "singlebuffer_memory", (gint64)dt_get_singlebuffer_mem());
  _int(b, "mipmap_memory", (gint64)darktable.dtresources.mipmap_memory);
  const dtb_pipe_t *pipes[2] = { &dtb_S.full, &dtb_S.preview };
  json_builder_set_member_name(b, "pipe_cache_fraction");
  json_builder_begin_array(b);
  for(int i = 0; i < 2; i++) json_builder_add_int_value(b, _fraction(pipes[i], i));
  json_builder_end_array(b);
  json_builder_set_member_name(b, "pipe_cache_limit");
  json_builder_begin_array(b);
  for(int i = 0; i < 2; i++) json_builder_add_int_value(b, (gint64)(dt_get_available_mem() / MAX(1, _fraction(pipes[i], i))));
  json_builder_end_array(b);
  dt_mipmap_cache_t *m = darktable.mipmap_cache;
  _int(b, "inputs_kept", m ? (gint64)m->mip_full.cache.cost_quota : -1);
  _int(b, "worker_threads", dt_worker_threads());
  _int(b, "pipe_full_hits", dtb_S.full.ready ? (gint64)dtb_S.full.pipe.cache.hits : 0);
  _int(b, "pipe_full_tests", dtb_S.full.ready ? (gint64)dtb_S.full.pipe.cache.tests : 0);
  _int(b, "pipe_preview_hits", dtb_S.preview.ready ? (gint64)dtb_S.preview.pipe.cache.hits : 0);
  _int(b, "pipe_preview_tests", dtb_S.preview.ready ? (gint64)dtb_S.preview.pipe.cache.tests : 0);
  // buffers generated (a RAW decoded, a downscaled input made) on blocking
  // gets, the way the bridge's pipelines ask (dt_mipmap_cache_get_with_caller:
  // stats_fetches; requests and misses are only counted for best-effort gets)
  _int(b, "mipmap_full_decodes", m ? (gint64)m->mip_full.stats_fetches : -1);
  _int(b, "mipmap_f_decodes", m ? (gint64)m->mip_f.stats_fetches : -1);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return *json ? DTB_OK : dtb__err(DTB_E_ENGINE, "could not write the resources");
}

static char *_cl_info(void)
{
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
#ifdef HAVE_OPENCL
  _bool(b, "built", TRUE);
#else
  _bool(b, "built", FALSE);
#endif
  _bool(b, "allowed", g_speed.opencl_allowed);
  _bool(b, "started", g_speed.opencl_started);
  dt_opencl_t *cl = darktable.opencl;
#ifdef HAVE_OPENCL
  _str(b, "library", cl && cl->dlocl ? cl->dlocl->library : g_speed.opencl_library);
  _bool(b, "inited", cl && cl->inited);
  _bool(b, "enabled", cl && cl->enabled);
  _bool(b, "stopped", cl && cl->stopped);
  _int(b, "error_count", cl ? cl->error_count : 0);
#else
  _str(b, "library", NULL);
  _bool(b, "inited", FALSE);
  _bool(b, "enabled", FALSE);
  _bool(b, "stopped", FALSE);
  _int(b, "error_count", 0);
#endif
  _bool(b, "running", dt_opencl_running());
  json_builder_set_member_name(b, "devices");
  json_builder_begin_array(b);
#ifdef HAVE_OPENCL
  if(cl && cl->inited)
    for(int i = 0; i < cl->num_devs; i++)
    {
      const dt_opencl_device_t *d = &cl->dev[i];
      json_builder_begin_object(b);
      _str(b, "name", d->fullname);
      _str(b, "platform", d->platform);
      _str(b, "version", d->device_version);
      _int(b, "memory", (gint64)d->max_global_mem);
      _bool(b, "unified", d->unified_memory);
      _bool(b, "disabled", d->disabled);
      json_builder_end_object(b);
    }
#endif
  json_builder_end_array(b);
  _int(b, "renders", g_speed.renders);
  _int(b, "renders_gpu", g_speed.renders_gpu);
  _int(b, "renders_fallback", g_speed.renders_fallback);
  _str(b, "last_render", g_speed.last_render);
  _str(b, "kernel_dir", g_speed.kernel_dir);
  json_builder_end_object(b);
  return dtb__json_finish(b);
}

int dtb_opencl_info(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  *json = _cl_info();
  return *json ? DTB_OK : dtb__err(DTB_E_ENGINE, "could not write the OpenCL information");
}

#ifdef HAVE_OPENCL
/* Brief P4d: which OpenCL library the first switch on uses, and why.
 * Candidates in order: what the app asked for (dtb_opencl_library: a name
 * it already loaded with System.loadLibrary, so the library is in the app's
 * linker namespace), a value already in darktablerc, darktable's own names
 * (common/dlopencl.c ocllib), Qualcomm's own name, then the absolute vendor
 * paths. Each is opened with dlopen and asked for its platforms
 * (clGetPlatformIDs); the first with a platform wins, else the first that
 * has the symbol. It becomes darktable's "opencl_library", so
 * dt_opencl_init (dt_dlopencl_init) opens exactly that one. Every try is
 * kept for dtb_opencl_info "library_tries". */
typedef struct dtb_cl_try_t
{
  gchar *name, *from, *error;
  gboolean loaded, symbol, called;
  int status, platforms;
} dtb_cl_try_t;

static GPtrArray *g_cl_tries = NULL;  // dtb_cl_try_t
static gchar *g_cl_prefer = NULL;     // dtb_opencl_library
static gchar *g_cl_log = NULL;        // darktable's -d opencl output of dt_opencl_init
static gboolean g_cl_log_cut = FALSE;
#define DTB_CL_LOG_MAX (256 * 1024)

static void _try_free(gpointer p)
{
  dtb_cl_try_t *t = p;
  g_free(t->name);
  g_free(t->from);
  g_free(t->error);
  g_free(t);
}

static void _cl_diag_reset(void)
{
  if(g_cl_tries) g_ptr_array_free(g_cl_tries, TRUE);
  g_cl_tries = NULL;
  g_free(g_cl_prefer);
  g_cl_prefer = NULL;
  g_free(g_cl_log);
  g_cl_log = NULL;
  g_cl_log_cut = FALSE;
}

static dtb_cl_try_t *_try_one(const char *name, const char *from)
{
  dtb_cl_try_t *t = g_new0(dtb_cl_try_t, 1);
  t->name = g_strdup(name);
  t->from = g_strdup(from);
  t->status = 1;  // not called
  dlerror();
  // not closed when it loaded: darktable opens the same one, and a vendor
  // driver need not survive being unloaded
  void *h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
  if(!h)
  {
    const char *e = dlerror();
    t->error = g_strdup(e ? e : "dlopen failed, no dlerror");
    return t;
  }
  t->loaded = TRUE;
  dt_clGetPlatformIDs_t ids = (dt_clGetPlatformIDs_t)dlsym(h, "clGetPlatformIDs");
  t->symbol = ids != NULL;
  if(!ids)
  {
    const char *e = dlerror();
    t->error = g_strdup(e ? e : "no clGetPlatformIDs");
    return t;
  }
  cl_uint n = 0;
  t->called = TRUE;
  t->status = ids(0, NULL, &n);
  t->platforms = t->status == CL_SUCCESS ? (int)n : 0;
  return t;
}

static gboolean _tried(const char *name)
{
  for(guint i = 0; g_cl_tries && i < g_cl_tries->len; i++)
    if(!strcmp(((dtb_cl_try_t *)g_ptr_array_index(g_cl_tries, i))->name, name)) return TRUE;
  return FALSE;
}

static void _choose_library(void)
{
  if(g_cl_tries) g_ptr_array_free(g_cl_tries, TRUE);
  g_cl_tries = g_ptr_array_new_with_free_func(_try_free);
  GPtrArray *names = g_ptr_array_new_with_free_func(g_free);
  GPtrArray *froms = g_ptr_array_new();
#define ADD(n, f) do { g_ptr_array_add(names, g_strdup(n)); g_ptr_array_add(froms, (gpointer)(f)); } while(0)
  if(g_cl_prefer)
  {
    gchar **p = g_strsplit(g_cl_prefer, ":", 16);
    for(int i = 0; p[i]; i++)
      if(p[i][0]) ADD(p[i], "app");
    g_strfreev(p);
  }
  const char *set = dt_conf_get_string_const("opencl_library");
  if(set && set[0]) ADD(set, "darktablerc");
  static const char *fixed[][2] = {
    { "libOpenCL.so", "darktable" }, { "libOpenCL.so.1", "darktable" },
    { "libOpenCL_adreno.so", "qualcomm" },
    { "/vendor/lib64/libOpenCL.so", "vendor path" }, { "/vendor/lib64/libOpenCL_adreno.so", "vendor path" },
    { "/system/vendor/lib64/libOpenCL.so", "vendor path" }, { "/vendor/lib/libOpenCL.so", "vendor path" },
  };
  for(size_t i = 0; i < G_N_ELEMENTS(fixed); i++) ADD(fixed[i][0], fixed[i][1]);
#undef ADD
  int best = -1, with_symbol = -1;
  for(guint i = 0; i < names->len && best < 0; i++)
  {
    const char *n = g_ptr_array_index(names, i);
    if(_tried(n)) continue;
    dtb_cl_try_t *t = _try_one(n, g_ptr_array_index(froms, i));
    g_ptr_array_add(g_cl_tries, t);
    if(t->platforms > 0) best = (int)g_cl_tries->len - 1;
    else if(t->symbol && with_symbol < 0) with_symbol = (int)g_cl_tries->len - 1;
  }
  g_ptr_array_free(names, TRUE);
  g_ptr_array_free(froms, TRUE);
  if(best < 0) best = with_symbol;
  g_free(g_speed.opencl_library);
  g_speed.opencl_library = NULL;
  if(best >= 0)
  {
    g_speed.opencl_library = g_strdup(((dtb_cl_try_t *)g_ptr_array_index(g_cl_tries, best))->name);
    dt_conf_set_string("opencl_library", g_speed.opencl_library);
  }
  // nothing usable: darktable's own search still runs and says so in its log
  else if(set && set[0])
    dt_conf_set_string("opencl_library", "");
}

/* run dt_opencl_init with darktable's OpenCL debug output (-d opencl -d
 * verbose) on and caught: darktable prints with printf (dt_print_nts_ext),
 * so stdout goes to a temporary file for this call and is then written back
 * to the real stdout (the app's log ring and logcat). */
static void _init_logged(void)
{
  const int muted = darktable.unmuted;
  darktable.unmuted |= DT_DEBUG_OPENCL | DT_DEBUG_VERBOSE;
  gchar *tmp = NULL;
  const int fd = g_file_open_tmp("dtb-opencl-XXXXXX.log", &tmp, NULL);
  fflush(stdout);
  const int saved = fd >= 0 ? dup(STDOUT_FILENO) : -1;
  const gboolean caught = saved >= 0 && dup2(fd, STDOUT_FILENO) >= 0;
  dt_opencl_init(darktable.opencl, DT_OPENCL_OPTION_NONE, FALSE);
  fflush(stdout);
  if(caught) dup2(saved, STDOUT_FILENO);
  if(saved >= 0) close(saved);
  if(fd >= 0) close(fd);
  darktable.unmuted = muted;
  gchar *text = NULL;
  gsize len = 0;
  g_free(g_cl_log);
  g_cl_log = NULL;
  if(caught && tmp && g_file_get_contents(tmp, &text, &len, NULL))
  {
    if(len) fwrite(text, 1, len, stdout);
    fflush(stdout);
    if(len > DTB_CL_LOG_MAX)
    {
      text[DTB_CL_LOG_MAX] = '\0';
      g_cl_log_cut = TRUE;
    }
    // JSON wants valid UTF-8
    g_cl_log = g_utf8_make_valid(text, -1);
    g_free(text);
  }
  else
    g_cl_log = g_strdup("(darktable's OpenCL log could not be caught)");
  if(tmp) g_unlink(tmp);
  g_free(tmp);
}

static gchar *_cl_string(dt_opencl_t *cl, const gboolean device, void *id, const cl_uint what)
{
  char buf[8192] = { 0 };
  const cl_int e = device
    ? (cl->dlocl->symbols->dt_clGetDeviceInfo)((cl_device_id)id, what, sizeof(buf) - 1, buf, NULL)
    : (cl->dlocl->symbols->dt_clGetPlatformInfo)((cl_platform_id)id, what, sizeof(buf) - 1, buf, NULL);
  if(e != CL_SUCCESS) return g_strdup_printf("(error %d)", e);
  return g_utf8_make_valid(g_strstrip(buf), -1);
}

/* the platforms and devices the loaded library reports, whatever darktable
 * decided about them (dt_opencl_init keeps the library open even when it
 * found no usable device) */
static void _platforms(JsonBuilder *b, dt_opencl_t *cl)
{
  json_builder_set_member_name(b, "platforms");
  json_builder_begin_array(b);
  if(cl && cl->dlocl && cl->dlocl->symbols)
  {
    cl_platform_id p[8];
    cl_uint np = 0;
    if((cl->dlocl->symbols->dt_clGetPlatformIDs)(8, p, &np) != CL_SUCCESS) np = 0;
    for(cl_uint i = 0; i < MIN(np, 8); i++)
    {
      json_builder_begin_object(b);
      static const struct { const char *k; cl_uint w; } pk[] = {
        { "name", CL_PLATFORM_NAME }, { "vendor", CL_PLATFORM_VENDOR },
        { "version", CL_PLATFORM_VERSION }, { "profile", CL_PLATFORM_PROFILE } };
      for(size_t k = 0; k < G_N_ELEMENTS(pk); k++)
      {
        gchar *v = _cl_string(cl, FALSE, p[i], pk[k].w);
        _str(b, pk[k].k, v);
        g_free(v);
      }
      json_builder_set_member_name(b, "devices");
      json_builder_begin_array(b);
      cl_device_id d[8];
      cl_uint nd = 0;
      const cl_int de = (cl->dlocl->symbols->dt_clGetDeviceIDs)(p[i], CL_DEVICE_TYPE_ALL, 8, d, &nd);
      for(cl_uint j = 0; de == CL_SUCCESS && j < MIN(nd, 8); j++)
      {
        json_builder_begin_object(b);
        static const struct { const char *k; cl_uint w; } dk[] = {
          { "name", CL_DEVICE_NAME }, { "vendor", CL_DEVICE_VENDOR }, { "version", CL_DEVICE_VERSION },
          { "driver", CL_DRIVER_VERSION }, { "c_version", CL_DEVICE_OPENCL_C_VERSION },
          { "extensions", CL_DEVICE_EXTENSIONS } };
        for(size_t k = 0; k < G_N_ELEMENTS(dk); k++)
        {
          gchar *v = _cl_string(cl, TRUE, d[j], dk[k].w);
          _str(b, dk[k].k, v);
          g_free(v);
        }
        cl_device_type type = 0;
        cl_bool image = 0, avail = 0, little = 0;
        cl_ulong mem = 0, alloc = 0;
        (cl->dlocl->symbols->dt_clGetDeviceInfo)(d[j], CL_DEVICE_TYPE, sizeof(type), &type, NULL);
        (cl->dlocl->symbols->dt_clGetDeviceInfo)(d[j], CL_DEVICE_IMAGE_SUPPORT, sizeof(image), &image, NULL);
        (cl->dlocl->symbols->dt_clGetDeviceInfo)(d[j], CL_DEVICE_AVAILABLE, sizeof(avail), &avail, NULL);
        (cl->dlocl->symbols->dt_clGetDeviceInfo)(d[j], CL_DEVICE_ENDIAN_LITTLE, sizeof(little), &little, NULL);
        (cl->dlocl->symbols->dt_clGetDeviceInfo)(d[j], CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(mem), &mem, NULL);
        (cl->dlocl->symbols->dt_clGetDeviceInfo)(d[j], CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(alloc), &alloc, NULL);
        _str(b, "type", (type & CL_DEVICE_TYPE_GPU) ? "GPU" : (type & CL_DEVICE_TYPE_CPU) ? "CPU"
                        : (type & CL_DEVICE_TYPE_ACCELERATOR) ? "accelerator" : (type & CL_DEVICE_TYPE_CUSTOM) ? "custom" : "other");
        _bool(b, "available", avail);
        _bool(b, "image_support", image);
        _bool(b, "little_endian", little);
        _int(b, "global_memory", (gint64)mem);
        _int(b, "max_alloc", (gint64)alloc);
        json_builder_end_object(b);
      }
      json_builder_end_array(b);
      _int(b, "devices_status", de);
      json_builder_end_object(b);
    }
  }
  json_builder_end_array(b);
}

static void _tries(JsonBuilder *b)
{
  json_builder_set_member_name(b, "library_tries");
  json_builder_begin_array(b);
  for(guint i = 0; g_cl_tries && i < g_cl_tries->len; i++)
  {
    const dtb_cl_try_t *t = g_ptr_array_index(g_cl_tries, i);
    json_builder_begin_object(b);
    _str(b, "name", t->name);
    _str(b, "from", t->from);
    _bool(b, "loaded", t->loaded);
    _bool(b, "symbol", t->symbol);
    _str(b, "error", t->error);
    if(t->called)
    {
      _int(b, "status", t->status);
      _int(b, "platforms", t->platforms);
    }
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
}

#endif

int dtb_opencl_diagnostics(char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
#ifdef HAVE_OPENCL
  _bool(b, "built", TRUE);
  _bool(b, "started", g_speed.opencl_started);
  _str(b, "preferred", g_cl_prefer);
  _str(b, "chosen", g_speed.opencl_library);
  dt_opencl_t *cl = darktable.opencl;
  _str(b, "loaded", cl && cl->dlocl ? cl->dlocl->library : NULL);
  _tries(b);
  _str(b, "init_log", g_cl_log);
  _bool(b, "init_log_cut", g_cl_log_cut);
  _platforms(b, cl);
#else
  _bool(b, "built", FALSE);
#endif
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return *json ? DTB_OK : dtb__err(DTB_E_ENGINE, "could not write the OpenCL diagnostics");
}

int dtb_opencl_library(const char *names)
{
  NEED_INIT();
#ifndef HAVE_OPENCL
  (void)names;
  return dtb__err(DTB_E_STATE, "this engine was built without OpenCL");
#else
  if(g_speed.opencl_started) return dtb__err(DTB_E_STATE, "OpenCL was already started; the library is chosen at the first switch on");
  g_free(g_cl_prefer);
  g_cl_prefer = names && names[0] ? g_strdup(names) : NULL;
  return DTB_OK;
#endif
}

int dtb_opencl_set(const int enabled, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
#ifndef HAVE_OPENCL
  (void)enabled;
  return dtb__err(DTB_E_STATE, "this engine was built without OpenCL");
#else
  if(!g_speed.opencl_allowed)
    return dtb__err(DTB_E_STATE, "OpenCL was not allowed at start (dtb_init_ex option \"opencl\")");
  dt_conf_set_bool("opencl", enabled ? TRUE : FALSE);
  if(enabled && !g_speed.opencl_started)
  {
    g_speed.opencl_started = TRUE;
    // Qualcomm's and ARM's platforms have no darktablerc key of their own
    dt_conf_set_bool("clplatform_other", TRUE);
    _choose_library();
    const double t0 = dtb__t();
    // darktable puts its compiled kernels under its cache folder
    // (common/opencl.c _opencl_init_device: dt_loc_get_user_cache_dir), only
    // while dt_opencl_init runs; the app wants them where Android does not
    // clear them, so the cache folder points there for that call only
    gchar *saved = NULL;
    if(g_speed.kernel_dir)
    {
      g_mkdir_with_parents(g_speed.kernel_dir, 0700);
      saved = darktable.cachedir;
      darktable.cachedir = g_strdup(g_speed.kernel_dir);
    }
    _init_logged();
    if(saved)
    {
      g_free(darktable.cachedir);
      darktable.cachedir = saved;
    }
    dtb__tlog("opencl.init", t0);
    // dt_opencl_init leaves "opencl" off when nothing usable was found
  }
  dt_opencl_update_settings();
  // pipelines made before keep CPU cache lines; they stay valid (the hash
  // does not depend on the device), as on desktop when the preference changes
  if(json) *json = _cl_info();
  return DTB_OK;
#endif
}

/* brief P4e */
int dtb_render_trace(int arm, char **json)
{
  if(json) *json = NULL;
  NEED_INIT();
  if(json)
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    _bool(b, "armed", g_trace.armed);
    _str(b, "log", g_trace.log);
    _bool(b, "cut", g_trace.cut);
    json_builder_end_object(b);
    *json = dtb__json_finish(b);
    if(!*json) return dtb__err(DTB_E_ENGINE, "could not write the render trace");
  }
  if(arm) g_trace.armed = TRUE;
  return DTB_OK;
}

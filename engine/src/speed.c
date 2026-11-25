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
#include <string.h>

typedef struct dtb_speed_t
{
  gboolean opencl_allowed;
  gboolean opencl_started;
  gchar *opencl_library;
  int pipe_fraction[2];   // full, preview; 0 = darktable's
  int inputs_kept;        // 0 = darktable's
  gint64 renders, renders_gpu, renders_fallback;
  const char *last_render;
  // per render in flight
  gboolean cl_at_start;
  int errors_at_start;
} dtb_speed_t;

static dtb_speed_t g_speed = { .last_render = "none" };

void dtb__speed_reset(void)
{
  g_free(g_speed.opencl_library);
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

void dtb__speed_render_begin(void)
{
  g_speed.cl_at_start = dt_opencl_running();
  g_speed.errors_at_start = darktable.opencl ? darktable.opencl->error_count : 0;
}

void dtb__speed_render_end(void)
{
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
/* darktable's own search (common/dlopencl.c: libOpenCL, libOpenCL.so,
 * libOpenCL.so.1) first; then where Android vendors put the library. A path
 * found here becomes darktable's "opencl_library" for this session. */
static void _choose_library(void)
{
  const char *set = dt_conf_get_string_const("opencl_library");
  if(set && set[0]) return;
  static const char *names[] = { "libOpenCL.so", "libOpenCL.so.1",
                                 "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so",
                                 "/vendor/lib64/libOpenCL_adreno.so", "/vendor/lib/libOpenCL.so", NULL };
  for(int i = 0; names[i]; i++)
  {
    void *h = dlopen(names[i], RTLD_LAZY | RTLD_LOCAL);
    if(!h) continue;
    const gboolean ok = dlsym(h, "clGetPlatformIDs") != NULL;
    dlclose(h);
    if(ok)
    {
      g_speed.opencl_library = g_strdup(names[i]);
      if(i > 0) dt_conf_set_string("opencl_library", names[i]);
      return;
    }
  }
}
#endif

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
    dt_opencl_init(darktable.opencl, DT_OPENCL_OPTION_NONE, FALSE);
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

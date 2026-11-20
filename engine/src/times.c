// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: per-module times of the last render (E2,
 * docs/design/engine-api.md section 5.6, brief P1b deliverable 7).
 *
 * darktable measures module times only with "-d perf" and prints them
 * (dt_show_times_f, develop/pixelpipe_hb.c). Without changing upstream the
 * bridge measures them itself: every processing module reaches its pixel
 * code through module->process_plain (default_process in develop/imageop.c
 * calls it, for whole buffers and for each tile alike), which each instance
 * copies from its library's so->process_plain when it is loaded
 * (dt_iop_load_module_by_so). After dt_init the bridge replaces
 * so->process_plain of every loaded module library with one timing
 * function that calls the module's own; instances loaded later carry it.
 * Modules process on the engine thread one after the other (OpenMP threads
 * work inside a module), so a plain table suffices.
 */
#include "dtb_internal.h"

#include <time.h>

typedef void (*_plain_f)(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_iop_t *piece, const void *const i,
                         void *const o, const struct dt_iop_roi_t *const roi_in,
                         const struct dt_iop_roi_t *const roi_out);

typedef struct _entry_t
{
  char op[32];
  int priority;
  char name[128];
  int iop_order;
  double ms, cpu_ms;
  int tiles;
  const void *module;
} _entry_t;

static GHashTable *g_orig = NULL;  // dt_iop_module_so_t * -> original process_plain
static GArray *g_entries = NULL;   // of _entry_t, the render in flight or the last one
static const char *g_pipe = NULL;
static double g_total_ms = 0.0, g_t0 = 0.0;

static double _cpu_ms(void)
{
  struct timespec t;
  clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &t);
  return t.tv_sec * 1e3 + t.tv_nsec * 1e-6;
}

static void _timed_plain(struct dt_iop_module_t *self, struct dt_dev_pixelpipe_iop_t *piece, const void *const i,
                         void *const o, const struct dt_iop_roi_t *const roi_in,
                         const struct dt_iop_roi_t *const roi_out)
{
  _plain_f f = g_orig ? g_hash_table_lookup(g_orig, self->so) : NULL;
  if(!f) return;
  const double w0 = dtb__t(), c0 = _cpu_ms();
  f(self, piece, i, o, roi_in, roi_out);
  const double w = dtb__t() - w0, c = _cpu_ms() - c0;
  if(!g_entries) return;
  for(guint k = 0; k < g_entries->len; k++)
  {
    _entry_t *e = &g_array_index(g_entries, _entry_t, k);
    if(e->module == self)
    {
      e->ms += w;
      e->cpu_ms += c;
      e->tiles++;
      return;
    }
  }
  _entry_t e = { { 0 } };
  g_strlcpy(e.op, self->op, sizeof(e.op));
  e.priority = self->multi_priority;
  g_strlcpy(e.name, self->multi_name, sizeof(e.name));
  e.iop_order = self->iop_order;
  e.ms = w;
  e.cpu_ms = c;
  e.tiles = 1;
  e.module = self;
  g_array_append_val(g_entries, e);
}

void dtb__times_install(void)
{
  if(g_orig) return;
  g_orig = g_hash_table_new(g_direct_hash, g_direct_equal);
  g_entries = g_array_new(FALSE, TRUE, sizeof(_entry_t));
  for(GList *l = darktable.iop; l; l = g_list_next(l))
  {
    dt_iop_module_so_t *so = l->data;
    if(!so->process_plain || so->process_plain == (void *)_timed_plain) continue;
    g_hash_table_insert(g_orig, so, (gpointer)so->process_plain);
    so->process_plain = (void *)_timed_plain;
  }
}

void dtb__times_begin(const char *pipe)
{
  if(!g_entries) return;
  g_array_set_size(g_entries, 0);
  g_pipe = pipe;
  g_total_ms = 0.0;
  g_t0 = dtb__t();
}

void dtb__times_end(void)
{
  g_total_ms = dtb__t() - g_t0;
}

void dtb__times_reset(void)
{
  if(g_entries) g_array_set_size(g_entries, 0);
  g_pipe = NULL;
  g_total_ms = 0.0;
}

static gint _by_order(gconstpointer a, gconstpointer b)
{
  const _entry_t *x = a, *y = b;
  return x->iop_order - y->iop_order;
}

int dtb_render_times(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "pipe");
  if(g_pipe) json_builder_add_string_value(b, g_pipe);
  else json_builder_add_null_value(b);
  json_builder_set_member_name(b, "total_ms");
  json_builder_add_double_value(b, g_pipe ? g_total_ms : 0.0);
  json_builder_set_member_name(b, "modules");
  json_builder_begin_array(b);
  if(g_pipe && g_entries)
  {
    GArray *s = g_array_sized_new(FALSE, TRUE, sizeof(_entry_t), g_entries->len);
    g_array_append_vals(s, g_entries->data, g_entries->len);
    g_array_sort(s, _by_order);
    for(guint k = 0; k < s->len; k++)
    {
      const _entry_t *e = &g_array_index(s, _entry_t, k);
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "op");
      json_builder_add_string_value(b, e->op);
      json_builder_set_member_name(b, "priority");
      json_builder_add_int_value(b, e->priority);
      json_builder_set_member_name(b, "instance_name");
      gchar *v = g_utf8_make_valid(e->name, -1);
      json_builder_add_string_value(b, v);
      g_free(v);
      json_builder_set_member_name(b, "ms");
      json_builder_add_double_value(b, e->ms);
      json_builder_set_member_name(b, "cpu_ms");
      json_builder_add_double_value(b, e->cpu_ms);
      json_builder_set_member_name(b, "tiles");
      json_builder_add_int_value(b, e->tiles);
      json_builder_end_object(b);
    }
    g_array_free(s, TRUE);
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

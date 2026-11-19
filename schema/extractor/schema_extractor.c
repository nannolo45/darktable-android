// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Control schema extractor (brief S5).
 *
 * An out-of-tree darktable "lib" plugin. It is inert unless the environment
 * variable DT_SCHEMA_OUT names a directory. Then, once the darkroom has built
 * every processing module's panel, it walks each module's GTK widget tree,
 * records every control with its containers, sweeps every list and switch
 * through its values to observe which controls appear or disappear, writes one
 * JSON file per module into DT_SCHEMA_OUT and quits darktable.
 *
 * Environment:
 *   DT_SCHEMA_OUT        output directory (required to activate)
 *   DT_SCHEMA_SKIP       comma separated operations to skip (e.g. after a crash)
 *   DT_SCHEMA_ONLY       comma separated operations to process (debugging)
 *   DT_SCHEMA_NO_SWEEP   if set, do not sweep lists and switches
 *   DT_SCHEMA_MAX_STATES cap on observed states per module (default 1500)
 *
 * Before each module it writes the operation name to DT_SCHEMA_OUT/progress,
 * so a driver script can tell which module was being processed if darktable
 * crashes, and rerun with that module in DT_SCHEMA_SKIP.
 *
 * Nothing in darktable is changed. Fields of the bauhaus widget that have no
 * public getter are read through a verbatim copy of its private struct
 * (generated/bauhaus_private.h, made at build time from bauhaus.c), and every
 * value that also has a public getter is cross-checked against it.
 */

#include "bauhaus/bauhaus.h"
#include "bauhaus_private.h"
#include "common/darktable.h"
#include "common/introspection.h"
#include "control/control.h"
#include "develop/develop.h"
#include "develop/imageop.h"
#include "dtgtk/button.h"
#include "dtgtk/expander.h"
#include "dtgtk/paint.h"
#include "dtgtk/togglebutton.h"
#include "gui/accelerators.h"
#include "gui/color_picker_proxy.h"
#include "gui/gtk.h"
#include "libs/lib.h"
#include "libs/lib_api.h"

#include <glib/gstdio.h>
#include <json-glib/json-glib.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

DT_MODULE(1)

#define SCHEMA_FORMAT_VERSION 1

const char *name(dt_lib_module_t *self)
{
  return "schema extractor";
}

dt_view_type_flags_t views(dt_lib_module_t *self)
{
  return DT_VIEW_DARKROOM;
}

uint32_t container(dt_lib_module_t *self)
{
  return DT_UI_CONTAINER_PANEL_LEFT_CENTER;
}

gboolean expandable(dt_lib_module_t *self)
{
  return FALSE;
}

int position(const dt_lib_module_t *self)
{
  return 1;
}

/* ------------------------------------------------------------------ */
/* state                                                              */
/* ------------------------------------------------------------------ */

typedef enum node_kind_t
{
  NK_SLIDER,
  NK_COMBOBOX,
  NK_TOGGLE,
  NK_BAUHAUS_BUTTON,
  NK_BUTTON,
  NK_TOGGLEBUTTON,
  NK_COLORPICKER_BUTTON,
  NK_GRAPH,
  NK_LABEL,
  NK_SECTION,
  NK_TABS,
  NK_PAGE,
  NK_COLLAPSIBLE,
  NK_ROW,
  NK_GRID,
  NK_STACK,
  NK_OTHER,
} node_kind_t;

static const char *_kind_name[] = {
  "slider", "combobox", "toggle", "bauhaus_button", "button", "togglebutton",
  "colorpicker_button", "graph", "label", "section", "tabs", "page",
  "collapsible", "row", "grid", "stack", "other",
};

typedef struct node_t
{
  int id;
  node_kind_t kind;
  GtkWidget *w;
  gboolean driver;     // list or switch swept for visibility rules
  int n_values;        // number of values a driver takes
  int default_pos;     // position at default parameters
  gboolean bound;      // bound to a module parameter
} node_t;

typedef struct ctx_t
{
  dt_iop_module_t *m;
  GPtrArray *nodes;           // node_t*
  int n_mismatch_getter;      // private struct vs public getter disagreements
  int n_custom;               // graphs and other non-bauhaus custom widgets
  GHashTable *bodies;         // bodies of collapsible sections: hidden when collapsed
} ctx_t;

static gboolean _active = FALSE;
static gchar *_outdir = NULL;

/* ------------------------------------------------------------------ */
/* helpers                                                            */
/* ------------------------------------------------------------------ */

static void _pump(void)
{
  for(int i = 0; i < 400 && gtk_events_pending(); i++) gtk_main_iteration_do(FALSE);
}

static gboolean _in_list(const char *env, const char *op)
{
  const char *v = g_getenv(env);
  if(!v || !*v) return FALSE;
  gchar **parts = g_strsplit(v, ",", -1);
  gboolean found = FALSE;
  for(gchar **p = parts; *p; p++)
    if(!strcmp(g_strstrip(*p), op)) found = TRUE;
  g_strfreev(parts);
  return found;
}

static void _add_float(JsonBuilder *b, const char *key, double v)
{
  json_builder_set_member_name(b, key);
  if(isfinite(v))
    json_builder_add_double_value(b, v);
  else
    json_builder_add_null_value(b);
}

static void _add_str(JsonBuilder *b, const char *key, const char *v)
{
  json_builder_set_member_name(b, key);
  if(v)
    json_builder_add_string_value(b, v);
  else
    json_builder_add_null_value(b);
}

static void _add_int(JsonBuilder *b, const char *key, gint64 v)
{
  json_builder_set_member_name(b, key);
  json_builder_add_int_value(b, v);
}

static void _add_bool(JsonBuilder *b, const char *key, gboolean v)
{
  json_builder_set_member_name(b, key);
  json_builder_add_boolean_value(b, v);
}

static const char *_type_name(dt_introspection_type_t t)
{
  switch(t)
  {
    case DT_INTROSPECTION_TYPE_FLOAT: return "float";
    case DT_INTROSPECTION_TYPE_DOUBLE: return "double";
    case DT_INTROSPECTION_TYPE_CHAR: return "char";
    case DT_INTROSPECTION_TYPE_INT8: return "int8";
    case DT_INTROSPECTION_TYPE_UINT8: return "uint8";
    case DT_INTROSPECTION_TYPE_SHORT: return "short";
    case DT_INTROSPECTION_TYPE_USHORT: return "ushort";
    case DT_INTROSPECTION_TYPE_INT: return "int";
    case DT_INTROSPECTION_TYPE_UINT: return "uint";
    case DT_INTROSPECTION_TYPE_LONG: return "long";
    case DT_INTROSPECTION_TYPE_ULONG: return "ulong";
    case DT_INTROSPECTION_TYPE_BOOL: return "bool";
    case DT_INTROSPECTION_TYPE_ENUM: return "enum";
    case DT_INTROSPECTION_TYPE_ARRAY: return "array";
    case DT_INTROSPECTION_TYPE_STRUCT: return "struct";
    case DT_INTROSPECTION_TYPE_UNION: return "union";
    case DT_INTROSPECTION_TYPE_OPAQUE: return "opaque";
    case DT_INTROSPECTION_TYPE_FLOATCOMPLEX: return "floatcomplex";
    default: return "none";
  }
}

static gboolean _is_scalar(dt_introspection_type_t t)
{
  switch(t)
  {
    case DT_INTROSPECTION_TYPE_FLOAT: case DT_INTROSPECTION_TYPE_DOUBLE:
    case DT_INTROSPECTION_TYPE_CHAR: case DT_INTROSPECTION_TYPE_INT8:
    case DT_INTROSPECTION_TYPE_UINT8: case DT_INTROSPECTION_TYPE_SHORT:
    case DT_INTROSPECTION_TYPE_USHORT: case DT_INTROSPECTION_TYPE_INT:
    case DT_INTROSPECTION_TYPE_UINT: case DT_INTROSPECTION_TYPE_LONG:
    case DT_INTROSPECTION_TYPE_ULONG: case DT_INTROSPECTION_TYPE_BOOL:
    case DT_INTROSPECTION_TYPE_ENUM:
      return TRUE;
    default:
      return FALSE;
  }
}

static dt_introspection_field_t *_linear(dt_iop_module_t *m)
{
  if(!m->have_introspection || !m->get_introspection_linear) return NULL;
  return m->get_introspection_linear();
}

// resolve an offset inside an array (or nested arrays / array of structs)
static gchar *_resolve_in_array(dt_introspection_field_t *arr, size_t rel, const char *prefix,
                                dt_introspection_field_t **leaf)
{
  const size_t count = arr->Array.count;
  if(!count) return NULL;
  const size_t esz = arr->header.size / count;
  if(!esz) return NULL;
  const size_t idx = rel / esz, r = rel % esz;
  gchar *name = g_strdup_printf("%s[%zu]", prefix, idx);
  dt_introspection_field_t *e = arr->Array.field;
  if(!e) return name;
  if(e->header.type == DT_INTROSPECTION_TYPE_ARRAY)
  {
    gchar *res = _resolve_in_array(e, r, name, leaf);
    g_free(name);
    return res;
  }
  if(e->header.type == DT_INTROSPECTION_TYPE_STRUCT)
  {
    for(size_t i = 0; e->Struct.fields && e->Struct.fields[i]; i++)
    {
      dt_introspection_field_t *f = e->Struct.fields[i];
      const size_t fo = f->header.offset - e->header.offset;
      if(r >= fo && r < fo + f->header.size)
      {
        gchar *sub = g_strdup_printf("%s.%s", name, f->header.field_name);
        g_free(name);
        if(f->header.type == DT_INTROSPECTION_TYPE_ARRAY)
        {
          gchar *res = _resolve_in_array(f, r - fo, sub, leaf);
          g_free(sub);
          return res;
        }
        if(r == fo)
        {
          *leaf = f;
          return sub;
        }
        g_free(sub);
        return NULL;
      }
    }
    g_free(name);
    return NULL;
  }
  if(r != 0)
  {
    g_free(name);
    return NULL;
  }
  *leaf = e;
  return name;
}

// name of the introspection field at byte offset `off` in params, or NULL
static gchar *_field_name(dt_iop_module_t *m, size_t off, dt_introspection_field_t **leaf)
{
  *leaf = NULL;
  dt_introspection_field_t *lin = _linear(m);
  if(!lin) return NULL;
  for(dt_introspection_field_t *f = lin; f->header.type != DT_INTROSPECTION_TYPE_NONE; f++)
  {
    if(_is_scalar(f->header.type) && !strchr(f->header.name, '[') && f->header.offset == off)
    {
      *leaf = f;
      return g_strdup(f->header.name);
    }
  }
  for(dt_introspection_field_t *f = lin; f->header.type != DT_INTROSPECTION_TYPE_NONE; f++)
  {
    if(f->header.type == DT_INTROSPECTION_TYPE_ARRAY && !strchr(f->header.name, '[')
       && off >= f->header.offset && off < f->header.offset + f->header.size)
      return _resolve_in_array(f, off - f->header.offset, f->header.name, leaf);
  }
  return NULL;
}

static void _action_info(JsonBuilder *b, GtkWidget *w)
{
  dt_action_t *ac = dt_action_widget(w);
  if(!ac) return;
  GString *path = g_string_new(NULL);
  int depth = 0;
  for(dt_action_t *a = ac; a && depth < 8; a = a->owner, depth++)
  {
    if(a->type == DT_ACTION_TYPE_IOP || a->type == DT_ACTION_TYPE_CATEGORY) break;
    if(path->len) g_string_prepend(path, "/");
    g_string_prepend(path, a->id ? a->id : "?");
  }
  _add_str(b, "action_path", path->str);
  _add_str(b, "action_label", ac->label);
  g_string_free(path, TRUE);
}

static gchar *_tooltip(GtkWidget *w)
{
  gchar *t = gtk_widget_get_tooltip_text(w);
  return t;
}

static gboolean _has_class(GtkWidget *w, const char *cls)
{
  return gtk_style_context_has_class(gtk_widget_get_style_context(w), cls);
}

// children in visual order: GtkBox pack-end children come last, reversed;
// GtkGrid children sorted by row, then column
static gint _grid_cmp(gconstpointer a, gconstpointer b, gpointer user)
{
  GtkContainer *c = user;
  int ta, la, tb, lb;
  gtk_container_child_get(c, (GtkWidget *)a, "top-attach", &ta, "left-attach", &la, NULL);
  gtk_container_child_get(c, (GtkWidget *)b, "top-attach", &tb, "left-attach", &lb, NULL);
  return ta != tb ? ta - tb : la - lb;
}

static GList *_ordered_children(GtkWidget *w)
{
  GList *ch = gtk_container_get_children(GTK_CONTAINER(w));
  if(GTK_IS_BOX(w))
  {
    GList *start = NULL, *end = NULL;
    for(GList *l = ch; l; l = l->next)
    {
      GtkPackType pt = GTK_PACK_START;
      gtk_container_child_get(GTK_CONTAINER(w), l->data, "pack-type", &pt, NULL);
      if(pt == GTK_PACK_END)
        end = g_list_prepend(end, l->data);
      else
        start = g_list_append(start, l->data);
    }
    g_list_free(ch);
    return g_list_concat(start, end);
  }
  if(GTK_IS_GRID(w))
  {
    GPtrArray *a = g_ptr_array_new();
    for(GList *l = ch; l; l = l->next) g_ptr_array_add(a, l->data);
    g_ptr_array_sort_with_data(a, (GCompareDataFunc)_grid_cmp, w);
    GList *res = NULL;
    for(guint i = 0; i < a->len; i++) res = g_list_append(res, a->pdata[i]);
    g_ptr_array_free(a, TRUE);
    g_list_free(ch);
    return res;
  }
  return ch;
}

// does this subtree contain anything we would record?
static gboolean _is_leaf_kind(GtkWidget *w)
{
  return DT_IS_BAUHAUS_WIDGET(w) || GTK_IS_BUTTON(w) || GTK_IS_DRAWING_AREA(w)
         || GTK_IS_LABEL(w) || GTK_IS_ENTRY(w) || GTK_IS_NOTEBOOK(w);
}

static int _count_interesting(GtkWidget *w)
{
  if(_is_leaf_kind(w)) return 1;
  if(!GTK_IS_CONTAINER(w)) return 0;
  int n = 0;
  GList *ch = gtk_container_get_children(GTK_CONTAINER(w));
  for(GList *l = ch; l; l = l->next) n += _count_interesting(l->data) ? 1 : 0;
  g_list_free(ch);
  return n;
}

/* ------------------------------------------------------------------ */
/* walking the widget tree                                            */
/* ------------------------------------------------------------------ */

static node_t *_new_node(ctx_t *c, GtkWidget *w, node_kind_t k, JsonBuilder *b)
{
  node_t *n = g_new0(node_t, 1);
  n->id = c->nodes->len;
  n->kind = k;
  n->w = w;
  g_ptr_array_add(c->nodes, n);
  json_builder_begin_object(b);
  _add_int(b, "id", n->id);
  _add_str(b, "kind", _kind_name[k]);
  _add_str(b, "gtk_type", G_OBJECT_TYPE_NAME(w));
  return n;
}

static void _walk_children(ctx_t *c, GtkWidget *w, JsonBuilder *b);
static void _walk(ctx_t *c, GtkWidget *w, JsonBuilder *b);

static void _bauhaus_node(ctx_t *c, GtkWidget *w, JsonBuilder *b)
{
  dt_iop_module_t *m = c->m;
  dt_bauhaus_widget_t *bw = DT_BAUHAUS_WIDGET(w);
  const dt_bauhaus_type_t t = dt_bauhaus_widget_get_type(w);

  // cross-check the private copy against public getters
  if(bw->type != t || bw->field != dt_bauhaus_widget_get_field(w)) c->n_mismatch_getter++;

  node_kind_t k = t == DT_BAUHAUS_SLIDER     ? NK_SLIDER
                  : t == DT_BAUHAUS_COMBOBOX ? NK_COMBOBOX
                  : t == DT_BAUHAUS_TOGGLE   ? NK_TOGGLE
                                             : NK_BAUHAUS_BUTTON;
  node_t *n = _new_node(c, w, k, b);

  _add_str(b, "label", dt_bauhaus_widget_get_label(w));
  _add_str(b, "section", bw->section);
  gchar *tt = _tooltip(w);
  _add_str(b, "tooltip", tt);
  g_free(tt);
  _action_info(b, w);

  // binding
  dt_introspection_field_t *leaf = NULL;
  gchar *fname = NULL;
  const char *bound_to = NULL;
  if(bw->field)
  {
    const ptrdiff_t po = (uint8_t *)bw->field - (uint8_t *)m->params;
    const ptrdiff_t bo = (uint8_t *)bw->field - (uint8_t *)m->blend_params;
    if(m->params && po >= 0 && po < m->params_size)
    {
      bound_to = "params";
      fname = _field_name(m, po, &leaf);
      _add_int(b, "param_offset", po);
    }
    else if(m->blend_params && bo >= 0 && bo < (ptrdiff_t)sizeof(dt_develop_blend_params_t))
    {
      bound_to = "blend_params";
      _add_int(b, "param_offset", bo);
    }
    else
      bound_to = "other";
  }
  n->bound = bound_to && !strcmp(bound_to, "params");
  _add_str(b, "bound_to", bound_to);
  _add_str(b, "param", fname);
  if(bw->field) _add_str(b, "param_type", _type_name(bw->field_type));
  if(leaf)
  {
    _add_str(b, "param_description", leaf->header.description);
    if(leaf->header.type == DT_INTROSPECTION_TYPE_ENUM) _add_str(b, "enum_type", leaf->header.type_name);
  }
  g_free(fname);

  // quad (the small button at the right of a slider or list)
  if(bw->quad_paint || bw->show_quad)
  {
    json_builder_set_member_name(b, "quad");
    json_builder_begin_object(b);
    _add_bool(b, "colorpicker", bw->quad_paint == dtgtk_cairo_paint_colorpicker);
    _add_bool(b, "toggle", bw->quad_toggle);
    _add_bool(b, "has_icon", bw->quad_paint != NULL);
    _add_str(b, "tooltip", bw->tooltip);
    json_builder_end_object(b);
  }

  if(t == DT_BAUHAUS_SLIDER)
  {
    const dt_bauhaus_slider_data_t *d = &bw->slider;
    if(d->hard_min != dt_bauhaus_slider_get_hard_min(w) || d->hard_max != dt_bauhaus_slider_get_hard_max(w)
       || d->digits != dt_bauhaus_slider_get_digits(w))
      c->n_mismatch_getter++;
    _add_float(b, "hard_min", d->hard_min);
    _add_float(b, "hard_max", d->hard_max);
    _add_float(b, "soft_min", d->soft_min);
    _add_float(b, "soft_max", d->soft_max);
    _add_float(b, "default", dt_bauhaus_slider_get_default(w));
    _add_float(b, "step", dt_bauhaus_slider_get_step(w));
    _add_int(b, "digits", d->digits);
    _add_float(b, "factor", d->factor);
    _add_float(b, "offset", d->offset);
    _add_str(b, "format", d->format);
    _add_bool(b, "fill_feedback", d->fill_feedback);
    _add_int(b, "gradient_stops", d->grad_cnt);
    if(d->curve)
    {
      const float q1 = d->curve(0.25f, DT_BAUHAUS_GET), q2 = d->curve(0.5f, DT_BAUHAUS_GET),
                  q3 = d->curve(0.75f, DT_BAUHAUS_GET);
      const gboolean linear = fabsf(q1 - 0.25f) < 1e-5f && fabsf(q2 - 0.5f) < 1e-5f
                              && fabsf(q3 - 0.75f) < 1e-5f;
      _add_bool(b, "linear_curve", linear);
      if(!linear)
      {
        json_builder_set_member_name(b, "curve_samples");
        json_builder_begin_array(b);
        json_builder_add_double_value(b, q1);
        json_builder_add_double_value(b, q2);
        json_builder_add_double_value(b, q3);
        json_builder_end_array(b);
      }
    }
    gchar *td = dt_bauhaus_slider_get_text(w, dt_bauhaus_slider_get_default(w));
    gchar *tmin = dt_bauhaus_slider_get_text(w, d->soft_min);
    gchar *tmax = dt_bauhaus_slider_get_text(w, d->soft_max);
    _add_str(b, "text_default", td);
    _add_str(b, "text_soft_min", tmin);
    _add_str(b, "text_soft_max", tmax);
    g_free(td);
    g_free(tmin);
    g_free(tmax);
  }
  else if(t == DT_BAUHAUS_COMBOBOX)
  {
    const dt_bauhaus_combobox_data_t *d = &bw->combobox;
    const int len = d->entries ? d->entries->len : 0;
    if(len != dt_bauhaus_combobox_length(w)) c->n_mismatch_getter++;
    _add_int(b, "default_pos", dt_bauhaus_combobox_get_default(w));
    _add_int(b, "current_pos", dt_bauhaus_combobox_get(w));
    _add_bool(b, "editable", d->editable);
    _add_bool(b, "dynamic_entries", d->populate != NULL);
    json_builder_set_member_name(b, "entries");
    json_builder_begin_array(b);
    for(int i = 0; i < len; i++)
    {
      const dt_bauhaus_combobox_entry_t *e = g_ptr_array_index(d->entries, i);
      json_builder_begin_object(b);
      _add_str(b, "label", e->label);
      if(bw->field && bw->field_type == DT_INTROSPECTION_TYPE_ENUM)
        _add_int(b, "value", GPOINTER_TO_INT(e->data));
      else
        _add_int(b, "value", i);
      _add_bool(b, "sensitive", e->sensitive);
      if(e->alignment == DT_BAUHAUS_COMBOBOX_ALIGN_MIDDLE) _add_bool(b, "is_heading", TRUE);
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
    if(!d->editable && len >= 2 && len <= 64)
    {
      n->driver = TRUE;
      n->n_values = len;
      n->default_pos = dt_bauhaus_combobox_get(w);
    }
  }
  else if(t == DT_BAUHAUS_TOGGLE)
  {
    _add_bool(b, "default", dt_bauhaus_toggle_get_default(w));
    n->driver = TRUE;
    n->n_values = 2;
    n->default_pos = dt_bauhaus_toggle_get(w) ? 1 : 0;
  }
  json_builder_end_object(b);
}

static void _walk(ctx_t *c, GtkWidget *w, JsonBuilder *b)
{
  if(!GTK_IS_WIDGET(w)) return;

  if(DT_IS_BAUHAUS_WIDGET(w))
  {
    _bauhaus_node(c, w, b);
    return;
  }

  if(DTGTK_IS_EXPANDER(w))
  {
    node_t *n = _new_node(c, w, NK_COLLAPSIBLE, b);
    (void)n;
    GtkWidget *header = dtgtk_expander_get_header(DTGTK_EXPANDER(w));
    // the header holds a section label and an arrow toggle
    const char *label = NULL;
    GList *stack = g_list_prepend(NULL, header);
    while(stack && !label)
    {
      GtkWidget *x = stack->data;
      stack = g_list_delete_link(stack, stack);
      if(GTK_IS_LABEL(x))
        label = gtk_label_get_text(GTK_LABEL(x));
      else if(GTK_IS_CONTAINER(x))
        stack = g_list_concat(gtk_container_get_children(GTK_CONTAINER(x)), stack);
    }
    g_list_free(stack);
    _add_str(b, "label", label);
    _add_str(b, "widget_name", gtk_widget_get_name(w));
    _add_bool(b, "expanded", dtgtk_expander_get_expanded(DTGTK_EXPANDER(w)));
    json_builder_set_member_name(b, "children");
    json_builder_begin_array(b);
    GtkWidget *body = dtgtk_expander_get_body(DTGTK_EXPANDER(w));
    if(body)
    {
      g_hash_table_add(c->bodies, body);
      _walk(c, body, b);
    }
    json_builder_end_array(b);
    json_builder_end_object(b);
    return;
  }

  if(GTK_IS_NOTEBOOK(w))
  {
    _new_node(c, w, NK_TABS, b);
    _action_info(b, w);
    json_builder_set_member_name(b, "children");
    json_builder_begin_array(b);
    const int np = gtk_notebook_get_n_pages(GTK_NOTEBOOK(w));
    for(int i = 0; i < np; i++)
    {
      GtkWidget *page = gtk_notebook_get_nth_page(GTK_NOTEBOOK(w), i);
      _new_node(c, page, NK_PAGE, b);
      _add_str(b, "label", gtk_notebook_get_tab_label_text(GTK_NOTEBOOK(w), page));
      GtkWidget *tl = gtk_notebook_get_tab_label(GTK_NOTEBOOK(w), page);
      gchar *tt = tl ? _tooltip(tl) : NULL;
      _add_str(b, "tooltip", tt);
      g_free(tt);
      json_builder_set_member_name(b, "children");
      json_builder_begin_array(b);
      _walk(c, page, b);
      json_builder_end_array(b);
      json_builder_end_object(b);
    }
    json_builder_end_array(b);
    json_builder_end_object(b);
    return;
  }

  if(GTK_IS_TOGGLE_BUTTON(w) || GTK_IS_BUTTON(w))
  {
    const gboolean picker = g_object_get_data(G_OBJECT(w), DT_COLOR_PICKER_INSTANCE_KEY) != NULL
                            || (DTGTK_IS_TOGGLEBUTTON(w)
                                && DTGTK_TOGGLEBUTTON(w)->icon == dtgtk_cairo_paint_colorpicker);
    node_kind_t k = picker ? NK_COLORPICKER_BUTTON : GTK_IS_TOGGLE_BUTTON(w) ? NK_TOGGLEBUTTON : NK_BUTTON;
    _new_node(c, w, k, b);
    const char *bl = gtk_button_get_label(GTK_BUTTON(w));
    if(!bl)
    {
      GtkWidget *child = gtk_bin_get_child(GTK_BIN(w));
      if(GTK_IS_LABEL(child)) bl = gtk_label_get_text(GTK_LABEL(child));
    }
    _add_str(b, "label", bl);
    gchar *tt = _tooltip(w);
    _add_str(b, "tooltip", tt);
    g_free(tt);
    _add_bool(b, "icon", DTGTK_IS_TOGGLEBUTTON(w) || DTGTK_IS_BUTTON(w));
    if(GTK_IS_TOGGLE_BUTTON(w)) _add_bool(b, "active", gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w)));
    _action_info(b, w);
    json_builder_end_object(b);
    return;
  }

  if(GTK_IS_DRAWING_AREA(w))
  {
    c->n_custom++;
    _new_node(c, w, NK_GRAPH, b);
    gchar *tt = _tooltip(w);
    _add_str(b, "tooltip", tt);
    g_free(tt);
    int ww = -1, hh = -1;
    gtk_widget_get_size_request(w, &ww, &hh);
    _add_int(b, "size_request_w", ww);
    _add_int(b, "size_request_h", hh);
    _action_info(b, w);
    json_builder_end_object(b);
    return;
  }

  if(GTK_IS_LABEL(w))
  {
    const gboolean section = _has_class(w, "dt_section_label");
    _new_node(c, w, section ? NK_SECTION : NK_LABEL, b);
    _add_str(b, "label", gtk_label_get_text(GTK_LABEL(w)));
    gchar *tt = _tooltip(w);
    _add_str(b, "tooltip", tt);
    g_free(tt);
    json_builder_end_object(b);
    return;
  }

  if(GTK_IS_ENTRY(w) || !GTK_IS_CONTAINER(w))
  {
    c->n_custom++;
    _new_node(c, w, NK_OTHER, b);
    gchar *tt = _tooltip(w);
    _add_str(b, "tooltip", tt);
    g_free(tt);
    _action_info(b, w);
    json_builder_end_object(b);
    return;
  }

  // containers
  if(GTK_IS_STACK(w))
  {
    _new_node(c, w, NK_STACK, b);
    GtkWidget *vis = gtk_stack_get_visible_child(GTK_STACK(w));
    json_builder_set_member_name(b, "children");
    json_builder_begin_array(b);
    GList *ch = gtk_container_get_children(GTK_CONTAINER(w));
    for(GList *l = ch; l; l = l->next)
    {
      _new_node(c, l->data, NK_PAGE, b);
      gchar *nm = NULL;
      gtk_container_child_get(GTK_CONTAINER(w), l->data, "name", &nm, NULL);
      _add_str(b, "label", nm);
      g_free(nm);
      _add_bool(b, "visible_child", l->data == vis);
      json_builder_set_member_name(b, "children");
      json_builder_begin_array(b);
      _walk(c, l->data, b);
      json_builder_end_array(b);
      json_builder_end_object(b);
    }
    g_list_free(ch);
    json_builder_end_array(b);
    json_builder_end_object(b);
    return;
  }

  const gboolean horizontal = GTK_IS_ORIENTABLE(w) && GTK_IS_BOX(w)
                              && gtk_orientable_get_orientation(GTK_ORIENTABLE(w)) == GTK_ORIENTATION_HORIZONTAL;
  if((horizontal || GTK_IS_GRID(w)) && _count_interesting(w) >= 2)
  {
    _new_node(c, w, GTK_IS_GRID(w) ? NK_GRID : NK_ROW, b);
    json_builder_set_member_name(b, "children");
    json_builder_begin_array(b);
    _walk_children(c, w, b);
    json_builder_end_array(b);
    json_builder_end_object(b);
    return;
  }

  // plain container: transparent
  _walk_children(c, w, b);
}

static void _walk_children(ctx_t *c, GtkWidget *w, JsonBuilder *b)
{
  GList *ch = _ordered_children(w);
  for(GList *l = ch; l; l = l->next) _walk(c, l->data, b);
  g_list_free(ch);
}

/* ------------------------------------------------------------------ */
/* visibility                                                         */
/* ------------------------------------------------------------------ */

// visible in the panel, counting a collapsed section as open (the user can
// open it) and a stack page that is not the stack's current page as hidden
static gboolean _eff_visible(GtkWidget *w, GtkWidget *root, GHashTable *bodies)
{
  while(w && w != root)
  {
    if(!gtk_widget_get_visible(w) && !g_hash_table_contains(bodies, w)) return FALSE;
    GtkWidget *p = gtk_widget_get_parent(w);
    if(p && GTK_IS_STACK(p) && gtk_stack_get_visible_child(GTK_STACK(p)) != w) return FALSE;
    w = p;
  }
  return TRUE;
}

static gchar *_snapshot(ctx_t *c, gboolean sensitive)
{
  GString *s = g_string_sized_new(c->nodes->len + 1);
  for(guint i = 0; i < c->nodes->len; i++)
  {
    node_t *n = c->nodes->pdata[i];
    const gboolean v = sensitive ? gtk_widget_is_sensitive(n->w) : _eff_visible(n->w, c->m->widget, c->bodies);
    g_string_append_c(s, v ? '1' : '0');
  }
  return g_string_free(s, FALSE);
}

static int _driver_get(node_t *n)
{
  if(n->kind == NK_TOGGLE) return dt_bauhaus_toggle_get(n->w) ? 1 : 0;
  return dt_bauhaus_combobox_get(n->w);
}

static void _driver_set(node_t *n, int pos)
{
  if(n->kind == NK_TOGGLE)
    dt_bauhaus_toggle_set(n->w, pos != 0);
  else
    dt_bauhaus_combobox_set(n->w, pos);
}

// back to default parameters, as the reset button would leave the panel
static void _reset_module(ctx_t *c)
{
  dt_iop_module_t *m = c->m;
  // unbound drivers hold interface state only: put them back first
  for(guint i = 0; i < c->nodes->len; i++)
  {
    node_t *n = c->nodes->pdata[i];
    if(n->driver && !n->bound && _driver_get(n) != n->default_pos) _driver_set(n, n->default_pos);
  }
  memcpy(m->params, m->default_params, m->params_size);
  dt_iop_gui_update(m);
  _pump();
}

typedef struct assign_t
{
  int driver; // node id
  int pos;
} assign_t;

static int _n_states = 0, _max_states = 1500;
static gboolean _capped = FALSE;

static void _observe(ctx_t *c, JsonBuilder *b, const assign_t *as, int nas, const char *level)
{
  if(_n_states >= _max_states)
  {
    _capped = TRUE;
    return;
  }
  _n_states++;
  _reset_module(c);
  for(int i = 0; i < nas; i++)
  {
    node_t *n = c->nodes->pdata[as[i].driver];
    _driver_set(n, as[i].pos);
    _pump();
  }
  gchar *vis = _snapshot(c, FALSE);
  gchar *sen = _snapshot(c, TRUE);
  // the same parameters, as an undo or history step would show them
  dt_iop_gui_update(c->m);
  _pump();
  gchar *vis2 = _snapshot(c, FALSE);

  json_builder_begin_object(b);
  _add_str(b, "level", level);
  json_builder_set_member_name(b, "set");
  json_builder_begin_array(b);
  for(int i = 0; i < nas; i++)
  {
    json_builder_begin_array(b);
    json_builder_add_int_value(b, as[i].driver);
    json_builder_add_int_value(b, as[i].pos);
    json_builder_end_array(b);
  }
  json_builder_end_array(b);
  // actual position of every driver (a callback may change other lists)
  json_builder_set_member_name(b, "drivers");
  json_builder_begin_array(b);
  for(guint i = 0; i < c->nodes->len; i++)
  {
    node_t *n = c->nodes->pdata[i];
    if(!n->driver) continue;
    json_builder_begin_array(b);
    json_builder_add_int_value(b, n->id);
    json_builder_add_int_value(b, _driver_get(n));
    json_builder_end_array(b);
  }
  json_builder_end_array(b);
  _add_str(b, "visible", vis);
  _add_str(b, "sensitive", sen);
  if(strcmp(vis, vis2)) _add_str(b, "visible_after_gui_update", vis2);
  json_builder_end_object(b);
  g_free(vis);
  g_free(sen);
  g_free(vis2);
}

static void _sweep(ctx_t *c, JsonBuilder *b)
{
  _n_states = 0;
  _capped = FALSE;
  const char *ms = g_getenv("DT_SCHEMA_MAX_STATES");
  _max_states = ms && atoi(ms) > 0 ? atoi(ms) : 1500;

  json_builder_set_member_name(b, "states");
  json_builder_begin_array(b);

  // baseline
  _observe(c, b, NULL, 0, "baseline");
  _reset_module(c);
  gchar *base = _snapshot(c, FALSE);

  // level 1: every driver through every value, others at default
  GArray *revealing = g_array_new(FALSE, FALSE, sizeof(assign_t));
  for(guint i = 0; i < c->nodes->len; i++)
  {
    node_t *n = c->nodes->pdata[i];
    if(!n->driver) continue;
    for(int p = 0; p < n->n_values; p++)
    {
      if(p == n->default_pos) continue;
      assign_t a = { n->id, p };
      _observe(c, b, &a, 1, "single");
      gchar *now = _snapshot(c, FALSE);
      // the observed state is still applied after _observe (gui_update keeps params)
      if(strcmp(now, base)) g_array_append_val(revealing, a);
      g_free(now);
    }
  }

  // level 2: under every value that changed the panel, sweep every other
  // driver that is visible in that state
  for(guint r = 0; r < revealing->len; r++)
  {
    assign_t a0 = g_array_index(revealing, assign_t, r);
    _reset_module(c);
    _driver_set(c->nodes->pdata[a0.driver], a0.pos);
    _pump();
    gchar *vis = _snapshot(c, FALSE);
    for(guint i = 0; i < c->nodes->len; i++)
    {
      node_t *n = c->nodes->pdata[i];
      if(!n->driver || n->id == a0.driver || vis[n->id] != '1') continue;
      for(int p = 0; p < n->n_values; p++)
      {
        if(p == n->default_pos) continue;
        assign_t as[2] = { a0, { n->id, p } };
        _observe(c, b, as, 2, "pair");
      }
    }
    g_free(vis);
  }
  json_builder_end_array(b);
  _add_int(b, "n_states", _n_states);
  _add_bool(b, "capped", _capped);
  g_array_free(revealing, TRUE);
  g_free(base);
  _reset_module(c);
}

/* ------------------------------------------------------------------ */
/* per module                                                         */
/* ------------------------------------------------------------------ */

static void _groups(JsonBuilder *b, int g)
{
  static const struct { int bit; const char *name; } groups[] = {
    { IOP_GROUP_BASIC, "basic" },         { IOP_GROUP_TONE, "tone" },
    { IOP_GROUP_COLOR, "color" },         { IOP_GROUP_CORRECT, "correct" },
    { IOP_GROUP_EFFECT, "effect" },       { IOP_GROUP_TECHNICAL, "technical" },
    { IOP_GROUP_GRADING, "grading" },     { IOP_GROUP_EFFECTS, "effects" },
  };
  json_builder_set_member_name(b, "default_group");
  json_builder_begin_array(b);
  for(size_t i = 0; i < G_N_ELEMENTS(groups); i++)
    if(g & groups[i].bit) json_builder_add_string_value(b, groups[i].name);
  json_builder_end_array(b);
}

static void _introspection(JsonBuilder *b, dt_iop_module_t *m)
{
  dt_introspection_field_t *lin = _linear(m);
  json_builder_set_member_name(b, "params");
  json_builder_begin_object(b);
  _add_int(b, "size", m->params_size);
  _add_bool(b, "introspection", lin != NULL);
  json_builder_set_member_name(b, "fields");
  json_builder_begin_array(b);
  for(dt_introspection_field_t *f = lin; f && f->header.type != DT_INTROSPECTION_TYPE_NONE; f++)
  {
    if(!_is_scalar(f->header.type)) continue;
    json_builder_begin_object(b);
    _add_str(b, "name", f->header.name);
    _add_str(b, "type", _type_name(f->header.type));
    _add_str(b, "description", f->header.description);
    // how many values this template stands for (elements of enclosing arrays)
    size_t mult = 1;
    for(dt_introspection_field_t *a = lin; a->header.type != DT_INTROSPECTION_TYPE_NONE; a++)
    {
      if(a->header.type != DT_INTROSPECTION_TYPE_ARRAY) continue;
      const size_t l = strlen(a->header.name);
      if(!strncmp(f->header.name, a->header.name, l) && f->header.name[l] == '[') mult *= a->Array.count;
    }
    _add_int(b, "count", mult);
    _add_bool(b, "in_array", strchr(f->header.name, '[') != NULL);
    switch(f->header.type)
    {
      case DT_INTROSPECTION_TYPE_FLOAT:
        _add_float(b, "min", f->Float.Min);
        _add_float(b, "max", f->Float.Max);
        _add_float(b, "default", f->Float.Default);
        break;
      case DT_INTROSPECTION_TYPE_INT:
        _add_int(b, "min", f->Int.Min);
        _add_int(b, "max", f->Int.Max);
        _add_int(b, "default", f->Int.Default);
        break;
      case DT_INTROSPECTION_TYPE_UINT:
        _add_int(b, "min", f->UInt.Min);
        _add_int(b, "max", f->UInt.Max);
        _add_int(b, "default", f->UInt.Default);
        break;
      case DT_INTROSPECTION_TYPE_USHORT:
        _add_int(b, "min", f->UShort.Min);
        _add_int(b, "max", f->UShort.Max);
        _add_int(b, "default", f->UShort.Default);
        break;
      case DT_INTROSPECTION_TYPE_BOOL:
        _add_bool(b, "default", f->Bool.Default);
        break;
      case DT_INTROSPECTION_TYPE_ENUM:
        _add_str(b, "enum_type", f->header.type_name);
        _add_int(b, "default", f->Enum.Default);
        json_builder_set_member_name(b, "values");
        json_builder_begin_array(b);
        for(dt_introspection_type_enum_tuple_t *e = f->Enum.values; e && e->name; e++)
        {
          json_builder_begin_object(b);
          _add_str(b, "name", e->name);
          _add_int(b, "value", e->value);
          _add_str(b, "description", e->description);
          json_builder_end_object(b);
        }
        json_builder_end_array(b);
        break;
      default:
        break;
    }
    json_builder_end_object(b);
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
}

static gboolean _write_json(JsonBuilder *b, const char *path)
{
  JsonNode *root = json_builder_get_root(b);
  JsonGenerator *g = json_generator_new();
  json_generator_set_root(g, root);
  json_generator_set_pretty(g, FALSE);
  GError *err = NULL;
  const gboolean ok = json_generator_to_file(g, path, &err);
  if(!ok) fprintf(stderr, "[schema] cannot write %s: %s\n", path, err ? err->message : "?");
  g_clear_error(&err);
  g_object_unref(g);
  json_node_unref(root);
  return ok;
}

static void _progress(const char *op)
{
  gchar *p = g_build_filename(_outdir, "progress", NULL);
  FILE *f = g_fopen(p, "w");
  if(f)
  {
    fputs(op, f);
    fflush(f);
    fclose(f);
  }
  g_free(p);
}

static void _do_module(dt_iop_module_t *m, int position)
{
  ctx_t c = { .m = m, .nodes = g_ptr_array_new_with_free_func(g_free),
              .bodies = g_hash_table_new(NULL, NULL) };
  const gint64 t0 = g_get_monotonic_time();
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  _add_int(b, "schema_format", SCHEMA_FORMAT_VERSION);
  _add_str(b, "op", m->op);
  _add_str(b, "name", m->name());
  _add_str(b, "aliases", m->aliases());
  _add_int(b, "version", m->version());
  _add_int(b, "pipe_position", position);
  _groups(b, m->default_group());
  const int fl = m->flags();
  json_builder_set_member_name(b, "flags");
  json_builder_begin_object(b);
  _add_bool(b, "deprecated", (fl & IOP_FLAGS_DEPRECATED) != 0);
  _add_bool(b, "hidden", (fl & IOP_FLAGS_HIDDEN) != 0);
  _add_bool(b, "supports_blending", (fl & IOP_FLAGS_SUPPORTS_BLENDING) != 0);
  _add_bool(b, "no_masks", (fl & IOP_FLAGS_NO_MASKS) != 0);
  _add_bool(b, "one_instance", (fl & IOP_FLAGS_ONE_INSTANCE) != 0);
  _add_bool(b, "guides_widget", (fl & IOP_FLAGS_GUIDES_WIDGET) != 0);
  _add_int(b, "raw", fl);
  json_builder_end_object(b);
  if(fl & IOP_FLAGS_DEPRECATED) _add_str(b, "deprecated_msg", m->deprecated_msg());
  _add_bool(b, "default_enabled", m->default_enabled);
  _add_bool(b, "hide_enable_button", m->hide_enable_button);

  json_builder_set_member_name(b, "on_image");
  json_builder_begin_object(b);
  _add_bool(b, "draws", m->gui_post_expose != NULL);
  _add_bool(b, "mouse_moved", m->mouse_moved != NULL);
  _add_bool(b, "button_pressed", m->button_pressed != NULL);
  _add_bool(b, "button_released", m->button_released != NULL);
  _add_bool(b, "scrolled", m->scrolled != NULL);
  _add_bool(b, "mouse_actions", m->mouse_actions != NULL);
  json_builder_end_object(b);
  _add_bool(b, "color_picker_apply", m->color_picker_apply != NULL);
  _add_bool(b, "has_gui_changed", m->gui_changed != NULL);
  _introspection(b, m);

  json_builder_set_member_name(b, "controls");
  json_builder_begin_array(b);
  if(m->widget) _walk(&c, m->widget, b);
  json_builder_end_array(b);

  int n_drivers = 0;
  for(guint i = 0; i < c.nodes->len; i++) n_drivers += ((node_t *)c.nodes->pdata[i])->driver;

  // default visibility, then the sweep
  _reset_module(&c);
  gchar *vis = _snapshot(&c, FALSE);
  gchar *sen = _snapshot(&c, TRUE);
  _add_str(b, "visible_default", vis);
  _add_str(b, "sensitive_default", sen);
  g_free(vis);
  g_free(sen);
  _add_int(b, "n_nodes", c.nodes->len);
  _add_int(b, "n_drivers", n_drivers);
  _add_int(b, "n_custom", c.n_custom);

  json_builder_set_member_name(b, "sweep");
  json_builder_begin_object(b);
  if(!g_getenv("DT_SCHEMA_NO_SWEEP") && n_drivers)
  {
    dt_iop_request_focus(m);
    _pump();
    _sweep(&c, b);
    dt_iop_request_focus(NULL);
    _pump();
  }
  else
    _add_bool(b, "skipped", TRUE);
  json_builder_end_object(b);

  // leave the module as it was: default parameters, default on/off state
  _reset_module(&c);
  if(m->off && !m->default_enabled && gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(m->off)))
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(m->off), FALSE);
  _pump();

  _add_int(b, "getter_mismatches", c.n_mismatch_getter);
  _add_float(b, "seconds", (g_get_monotonic_time() - t0) / 1e6);
  _add_str(b, "status", "ok");
  json_builder_end_object(b);

  gchar *fn = g_strdup_printf("%s.json", m->op);
  gchar *path = g_build_filename(_outdir, fn, NULL);
  _write_json(b, path);
  fprintf(stderr, "[schema] %-20s nodes %3u drivers %2d states %4d getter-mismatch %d %.1fs\n", m->op,
          c.nodes->len, n_drivers, _n_states, c.n_mismatch_getter, (g_get_monotonic_time() - t0) / 1e6);
  g_free(fn);
  g_free(path);
  g_object_unref(b);
  g_ptr_array_free(c.nodes, TRUE);
  g_hash_table_destroy(c.bodies);
}

static gboolean _run(gpointer user_data)
{
  dt_develop_t *dev = darktable.develop;
  static int waited = 0;
  // wait until the darkroom has built the module panels and loaded the image
  gboolean ready = dev && dev->iop && dev->image_storage.id > 0;
  if(ready)
    for(GList *l = dev->iop; l; l = l->next)
    {
      dt_iop_module_t *m = l->data;
      if(!dt_iop_is_hidden(m) && !m->widget) ready = FALSE;
    }
  if(!ready && waited++ < 120) return G_SOURCE_CONTINUE;
  if(!ready)
  {
    fprintf(stderr, "[schema] darkroom not ready, giving up\n");
    dt_control_quit();
    return G_SOURCE_REMOVE;
  }

  // one-off file describing the run
  {
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    _add_int(b, "schema_format", SCHEMA_FORMAT_VERSION);
    _add_str(b, "darktable_version", darktable_package_version);
    _add_str(b, "darktable_package", darktable_package_string);
    _add_str(b, "image", dev->image_storage.filename);
    _add_str(b, "camera_maker", dev->image_storage.camera_maker);
    _add_str(b, "camera_model", dev->image_storage.camera_model);
    _add_bool(b, "raw", dt_image_is_raw(&dev->image_storage));
    _add_int(b, "max_states", g_getenv("DT_SCHEMA_MAX_STATES") ? atoi(g_getenv("DT_SCHEMA_MAX_STATES")) : 1500);
    json_builder_end_object(b);
    gchar *path = g_build_filename(_outdir, "_run.json", NULL);
    _write_json(b, path);
    g_free(path);
    g_object_unref(b);
  }

  int position = 0;
  for(GList *l = dev->iop; l; l = l->next, position++)
  {
    dt_iop_module_t *m = l->data;
    if(m->multi_priority != 0) continue;
    if(_in_list("DT_SCHEMA_SKIP", m->op)) continue;
    if(g_getenv("DT_SCHEMA_ONLY") && !_in_list("DT_SCHEMA_ONLY", m->op)) continue;
    gchar *fn = g_strdup_printf("%s.json", m->op);
    gchar *path = g_build_filename(_outdir, fn, NULL);
    const gboolean done = g_file_test(path, G_FILE_TEST_EXISTS);
    g_free(fn);
    g_free(path);
    if(done) continue;
    if(dt_iop_is_hidden(m) || !m->widget)
    {
      // still record that the module exists, without controls
      JsonBuilder *b = json_builder_new();
      json_builder_begin_object(b);
      _add_int(b, "schema_format", SCHEMA_FORMAT_VERSION);
      _add_str(b, "op", m->op);
      _add_str(b, "name", m->name());
      _add_int(b, "version", m->version());
      _add_int(b, "pipe_position", position);
      _add_str(b, "status", "hidden");
      json_builder_end_object(b);
      gchar *f2 = g_strdup_printf("%s/%s.json", _outdir, m->op);
      _write_json(b, f2);
      g_free(f2);
      g_object_unref(b);
      continue;
    }
    _progress(m->op);
    _do_module(m, position);
  }
  _progress("__done__");
  fprintf(stderr, "[schema] finished\n");
  dt_control_quit();
  return G_SOURCE_REMOVE;
}

void gui_init(dt_lib_module_t *self)
{
  self->widget = gtk_label_new("");
  self->data = NULL;
  const char *out = g_getenv("DT_SCHEMA_OUT");
  if(out && *out)
  {
    _active = TRUE;
    _outdir = g_strdup(out);
    g_mkdir_with_parents(_outdir, 0755);
  }
}

void gui_cleanup(dt_lib_module_t *self)
{
  g_free(_outdir);
  _outdir = NULL;
}

void view_enter(dt_lib_module_t *self, dt_view_t *old_view, dt_view_t *new_view)
{
  static gboolean started = FALSE;
  if(!_active || started) return;
  started = TRUE;
  g_timeout_add(500, _run, self);
}

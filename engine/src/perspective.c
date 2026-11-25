// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: rotate and perspective's structure, guides, fit and
 * automatic cropping (P3n, docs/design/engine-api.md 6.11).
 *
 * At the pinned commit iop/ashift.c keeps all of it in static functions that
 * work on the module's interface data (dt_iop_ashift_gui_data_t): line
 * detection (LSD, edge and detail enhancement), outlier removal (RANSAC),
 * the fit (Nelder-Mead, nmsfit), the automatic crop (do_crop, crop_adjust),
 * the rectangle and drawn lines, and desktop's mouse handlers. Nothing of it
 * is re-implemented here. This file compiles ashift.c itself, unmodified, as
 * darktable's own build does (the generated introspection_ashift.c also
 * #includes iop/ashift.c), and calls desktop's own functions:
 *   - the structure buttons (_event_structure_auto_clicked with desktop's
 *     ctrl/shift for the enhancements, _event_structure_quad_clicked,
 *     _event_structure_lines_clicked),
 *   - the fit buttons (_event_fit_v/h/both_button_clicked with ctrl/shift),
 *   - mouse_moved, button_pressed, button_released (select, deselect, sweep,
 *     the shift+drag area, drawing and moving lines and corners, dragging the
 *     automatic crop),
 *   - gui_focus (in and out), gui_changed (a slider or the crop mode moved),
 *   - _event_process_after_preview_callback (jobs queued while the module was
 *     off),
 *   - _get_points (the lines and corners on the processed picture).
 *
 * What desktop's interface provides is provided here by macros defined after
 * darktable's headers and before ashift.c, so only ashift.c's code sees them:
 *   - widgets: the gui data's widget pointers are tokens; the three structure
 *     toggles keep their state in pv_toggle[]; sensitivity, visibility, sliders
 *     and the combobox are not updated (the app reads the parameters);
 *   - the event of a click or a press: button and modifiers from the request;
 *   - dt_dev_add_history_item(darktable.develop, ...) -> the bridge's history
 *     (one undo step per request, opened at the first item);
 *   - dt_control_log, dt_toast_log -> "message" of the result;
 *   - desktop's preferences near_delta and near_delta_draw and
 *     DT_PIXEL_APPLY_DPI -> the request's touch radius;
 *   - the darkroom's preview pipe (self->dev->preview_pipe) -> the bridge's
 *     preview pipe, during a request;
 *   - g->buf, which process() fills on desktop's preview pipe -> the same copy
 *     (the same code) taken by a hook after ashift's process on the bridge's
 *     preview pipe (dtb__ashift_hook);
 *   - focus: commit_params gives the whole, uncropped picture while the module
 *     has the focus; between "begin" and "end" the bridge's pipes get the same
 *     (dtb__ashift_after_sync).
 * Desktop keeps the new crop in the gui data and writes it to the module's
 * parameters when the focus leaves (gui_focus); the bridge writes it at the
 * end of every request, so the parameters always equal the history.
 *
 * Every symbol of this file is hidden (compiled with -fvisibility=hidden, see
 * CMakeLists.txt) except dtb_ashift: the copy of the module's entry points
 * (process, gui_init, ...) never meets the module darktable loads.
 */
#include "dtb_internal.h"

// the headers iop/ashift.c includes, first, so that the macros below reach
// only ashift.c's own code
#include "common/gdk_event_utils.h"
#include "bauhaus/bauhaus.h"
#include "common/bilateral.h"
#include "common/colorspaces_inline_conversions.h"
#include "common/debug.h"
#include "common/imagebuf.h"
#include "common/interpolation.h"
#include "common/math.h"
#include "common/matrices.h"
#include "common/opencl.h"
#include "control/conf.h"
#include "control/control.h"
#include "develop/develop.h"
#include "develop/imageop.h"
#include "develop/imageop_gui.h"
#include "dtgtk/button.h"
#include "dtgtk/expander.h"
#include "gui/accelerators.h"
#include "gui/draw.h"
#include "gui/gtk.h"
#include "iop/iop_api.h"
#include "gui/guides.h"

#include <assert.h>
#include <gtk/gtk.h>
#include <inttypes.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* what desktop's interface supplies                                  */

static guint pv_button = 1;                 // GDK_BUTTON_PRIMARY or _SECONDARY
static GdkModifierType pv_state = 0;        // GDK_SHIFT_MASK, GDK_CONTROL_MASK
static float pv_near_px = 20.0f;            // touch radius, pixels of the preview pipe's picture
static GString *pv_message = NULL;          // the last dt_control_log / dt_toast_log
static dt_iop_module_t *pv_record_module = NULL;  // history recorded in this request
static dtb_change_t pv_record_kind = DTB_CHANGE_OTHER;
static gboolean pv_recorded = FALSE;

static char pv_tokens[17];                  // widget tokens, one per pointer of the gui data
static gboolean pv_toggle[17];

static int pv_token_index(const void *w)
{
  const char *c = w;
  return (c >= pv_tokens && c < pv_tokens + sizeof(pv_tokens)) ? (int)(c - pv_tokens) : -1;
}

static gboolean pv_toggle_get(const void *w)
{
  const int k = pv_token_index(w);
  return k >= 0 ? pv_toggle[k] : FALSE;
}

static void pv_toggle_set(const void *w, const gboolean on)
{
  const int k = pv_token_index(w);
  if(k >= 0) pv_toggle[k] = on;
}

static void pv_log(const char *fmt, ...) G_GNUC_PRINTF(1, 2);
static void pv_log(const char *fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);
  if(!pv_message) pv_message = g_string_new(NULL);
  g_string_vprintf(pv_message, fmt, ap);
  va_end(ap);
}

// desktop's dt_dev_add_history_item(darktable.develop, self, enable): the
// bridge's history; the undo state is recorded before the request's first item
static void pv_history_item(dt_iop_module_t *m, const gboolean enable)
{
  if(!pv_recorded)
  {
    dtb__change_begin(pv_record_kind, m);
    pv_recorded = TRUE;
    pv_record_module = m;
  }
  dtb__history_add(m, enable);
}

// desktop's preferences for the "near" distance of a line (pixels of the
// picture at fit): the touch radius instead
static float pv_conf_float(const char *key)
{
  if(!strcmp(key, "plugins/darkroom/ashift/near_delta") || !strcmp(key, "plugins/darkroom/ashift/near_delta_draw"))
    return pv_near_px;
  return dt_conf_get_float(key);
}

#undef DT_GUARD_GUI_UPDATE
#define DT_GUARD_GUI_UPDATE(...) do { } while(0)
#undef DT_ENTER_GUI_UPDATE
#define DT_ENTER_GUI_UPDATE() do { } while(0)
#undef DT_LEAVE_GUI_UPDATE
#define DT_LEAVE_GUI_UPDATE() do { } while(0)
// desktop: 6 px for a drawn corner (_draw_near_point) at dpi factor 1
#undef DT_PIXEL_APPLY_DPI
#define DT_PIXEL_APPLY_DPI(v) ((v) * (pv_near_px / 6.0f))
#undef dt_gui_get_widget
#define dt_gui_get_widget(c) ((GtkWidget *)(void *)(c))
#define dt_gui_current_button(g) (pv_button)
#define dt_gui_current_state(g) (pv_state)
#undef GTK_TOGGLE_BUTTON
#define GTK_TOGGLE_BUTTON(w) ((GtkToggleButton *)(void *)(w))
#define gtk_toggle_button_get_active(b) pv_toggle_get((const void *)(b))
#define gtk_toggle_button_set_active(b, v) pv_toggle_set((const void *)(b), (v))
#define gtk_widget_set_sensitive(w, v) ((void)(w), (void)(v))
#define gtk_widget_set_visible(w, v) ((void)(w), (void)(v))
#define dt_bauhaus_slider_set(w, v) ((void)(w), (void)(v))
#define dt_bauhaus_combobox_set(w, v) ((void)(w), (void)(v))
#define dt_control_log(...) pv_log(__VA_ARGS__)
#define dt_toast_log(...) pv_log(__VA_ARGS__)
#define dt_control_queue_redraw_center() ((void)0)
#define dt_control_change_cursor(c) ((void)(c))
#define dt_iop_request_focus(m) ((void)(m))
#define dt_dev_invalidate_all(d) ((void)(d))
#define dt_dev_reprocess_preview(d, o) ((void)(d), (void)(o))
#define dt_dev_add_history_item(d, m, e) pv_history_item((m), (e))
#define dt_dev_get_zoomed_in() (1.0f)
#define dt_conf_get_float(k) pv_conf_float(k)
#define dt_iop_gui_enter_critical_section(m) ((void)(m))
#define dt_iop_gui_leave_critical_section(m) ((void)(m))

#include "iop/ashift.c"

#undef dt_conf_get_float
#undef dt_dev_add_history_item

/* ------------------------------------------------------------------ */
/* the gui data of the one instance in use                            */

static dt_iop_ashift_gui_data_t *pv_g = NULL;
static char pv_op[20];                       // its instance
static int pv_prio = -1;
static gboolean pv_focus = FALSE;            // between "begin" and "end"
static dt_hash_t pv_salted[2];               // focused piece hashes, full and preview pipe

static void pv_g_free(void)
{
  if(!pv_g) return;
  // a focused piece goes back to its own crop at the next synchronisation
  if(pv_focus && dtb_S.open) dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
  free(pv_g->lines);
  dt_free_align(pv_g->buf);
  free(pv_g->points);
  free(pv_g->points_idx);
  free(pv_g->draw_points);
  g_free(pv_g);
  pv_g = NULL;
  pv_prio = -1;
  pv_focus = FALSE;
  memset(pv_toggle, 0, sizeof(pv_toggle));
}

// gui_init's values (ashift.c), widgets as tokens
static void pv_g_new(const dt_iop_module_t *m)
{
  pv_g_free();
  dt_iop_ashift_gui_data_t *g = g_new0(dt_iop_ashift_gui_data_t, 1);
  GtkWidget **w[] = { &g->rotation, &g->lensshift_v, &g->lensshift_h, &g->shear, &g->cropmode, &g->mode,
                      &g->specifics, &g->f_length, &g->crop_factor, &g->orthocorr, &g->aspect, &g->fit_v,
                      &g->fit_h, &g->fit_both, &g->structure_auto, &g->structure_quad, &g->structure_lines };
  for(size_t k = 0; k < G_N_ELEMENTS(w); k++) *w[k] = (GtkWidget *)(void *)&pv_tokens[k];
  g->buf = NULL;
  g->buf_width = 0;
  g->buf_height = 0;
  g->buf_x_off = 0;
  g->buf_y_off = 0;
  g->buf_scale = 1.0f;
  g->buf_hash = DT_INVALID_HASH;
  g->isflipped = -1;
  g->lastfit = ASHIFT_FIT_NONE;
  g->fitting = 0;
  g->lines = NULL;
  g->lines_count = 0;
  g->vertical_count = 0;
  g->horizontal_count = 0;
  g->lines_version = 0;
  g->points = NULL;
  g->points_idx = NULL;
  g->points_lines_count = 0;
  g->points_version = 0;
  g->grid_hash = DT_INVALID_HASH;
  g->lines_hash = DT_INVALID_HASH;
  g->rotation_range = ROTATION_RANGE_SOFT;
  g->lensshift_v_range = LENSSHIFT_RANGE_SOFT;
  g->lensshift_h_range = LENSSHIFT_RANGE_SOFT;
  g->shear_range = SHEAR_RANGE_SOFT;
  g->isselecting = 0;
  g->isdeselecting = 0;
  g->isbounding = ASHIFT_BOUNDING_OFF;
  g->near_delta = 0;
  g->selecting_lines_version = 0;
  g->jobcode = ASHIFT_JOBCODE_NONE;
  g->jobparams = 0;
  g->adjust_crop = FALSE;
  g->lastx = g->lasty = -1.0f;
  g->crop_cx = g->crop_cy = 1.0f;
  g->draw_near_point = -1;
  g->draw_line_move = -1;
  g->current_structure_method = ASHIFT_METHOD_NONE;
  pv_g = g;
  g_strlcpy(pv_op, m->op, sizeof(pv_op));
  pv_prio = m->multi_priority;
  memset(pv_toggle, 0, sizeof(pv_toggle));
  pv_salted[0] = pv_salted[1] = DT_INVALID_HASH;
}

static gboolean pv_is_ours(const dt_iop_module_t *m)
{
  return pv_g && m && m->multi_priority == pv_prio && !strcmp(m->op, pv_op);
}

/* session.c: the image is opened or closed */
void dtb__ashift_drop(void)
{
  pv_g_free();
}

/* ------------------------------------------------------------------ */
/* hooks in the bridge's pipes                                         */

/* times.c, after each process of a module: process()'s own copy of its input
 * on the preview pipe (ashift.c process, "only for preview pipe: collect
 * input buffer data"), on the bridge's preview pipe */
void dtb__ashift_hook(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const ivoid,
                      const dt_iop_roi_t *const roi_in)
{
  if(!pv_is_ours(self) || piece->pipe != &dtb_S.preview.pipe) return;
  dt_iop_ashift_gui_data_t *g = pv_g;
  dt_dev_pixelpipe_t *saved = self->dev->preview_pipe;
  self->dev->preview_pipe = piece->pipe;
  const int ch = piece->colors;

  dt_boundingbox_t points = { 0.0f, 0.0f, (float)piece->buf_in.width, (float)piece->buf_in.height };
  const float ivec[2] = { points[2] - points[0], points[3] - points[1] };
  const float ivecl = dt_fast_hypotf(ivec[0], ivec[1]);
  dt_dev_distort_backtransform_plus(self->dev, self->dev->preview_pipe, self->iop_order,
                                    DT_DEV_TRANSFORM_DIR_FORW_EXCL, points, 2);
  const float ovec[2] = { points[2] - points[0], points[3] - points[1] };
  const float ovecl = dt_fast_hypotf(ovec[0], ovec[1]);
  const float alpha = acosf(CLAMP((ivec[0] * ovec[0] + ivec[1] * ovec[1]) / (ivecl * ovecl), -1.0f, 1.0f));
  const int isflipped = fabsf(fmodf(alpha + M_PI_F, M_PI_F) - M_PI_2f) < M_PI_4f;
  const dt_hash_t hash = dt_dev_hash_plus(self->dev, self->dev->preview_pipe, self->iop_order,
                                          DT_DEV_TRANSFORM_DIR_BACK_EXCL);
  g->isflipped = isflipped;
  const size_t requested_size = (size_t)roi_in->width * roi_in->height;
  if(g->buf == NULL || (size_t)g->buf_width * g->buf_height < requested_size)
  {
    dt_free_align(g->buf);
    g->buf = dt_alloc_align_float(4 * requested_size);
  }
  if(g->buf)
  {
    dt_iop_image_copy_by_size(g->buf, ivoid, roi_in->width, roi_in->height, ch);
    g->buf_width = roi_in->width;
    g->buf_height = roi_in->height;
    g->buf_x_off = roi_in->x;
    g->buf_y_off = roi_in->y;
    g->buf_scale = roi_in->scale;
    g->buf_hash = hash;
  }
  self->dev->preview_pipe = saved;
}

/* render.c, after each synchronisation of a bridge pipe: while focused, the
 * piece's data as commit_params() makes it when dt_iop_has_focus(self) (the
 * whole picture, no crop), and its hash changed so no cache line of the
 * cropped picture is used. The hash a commit gave is salted once. */
void dtb__ashift_after_sync(dtb_pipe_t *p)
{
  if(!pv_g || !pv_focus) return;
  const int k = p == &dtb_S.full ? 0 : 1;
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
  {
    dt_dev_pixelpipe_iop_t *piece = n->data;
    if(!pv_is_ours(piece->module) || !piece->enabled || !piece->data) continue;
    if(piece->hash == pv_salted[k]) return;
    dt_iop_ashift_data_t *d = piece->data;
    d->cl = 0.0f;
    d->cr = 1.0f;
    d->ct = 0.0f;
    d->cb = 1.0f;
    static const char tag[] = "dtbridge ashift focus";
    piece->hash = dt_hash(piece->hash, tag, sizeof(tag));
    pv_salted[k] = piece->hash;
    dt_dev_pixelpipe_get_dimensions(&p->pipe, &dtb_S.dev, p->pipe.iwidth, p->pipe.iheight,
                                    &p->pipe.processed_width, &p->pipe.processed_height);
    return;
  }
}

/* ------------------------------------------------------------------ */
/* request helpers                                                    */

static dt_dev_pixelpipe_iop_t *pv_piece(dtb_pipe_t *p, const dt_iop_module_t *m)
{
  for(GList *n = p->pipe.nodes; n; n = g_list_next(n))
    if(((dt_dev_pixelpipe_iop_t *)n->data)->module == m) return n->data;
  return NULL;
}

/* g->buf for the current input of the module: desktop's preview pipe has
 * always run before a click; here one preview render in which the module
 * runs (its piece hash changed for this render only, as dtb__pick does) */
static int pv_ensure_buf(dt_iop_module_t *m, const gboolean force)
{
  dtb_pipe_t *p = NULL;
  int r = dtb__pipe_ready(FALSE, &p);
  if(r) return r;
  dt_dev_pixelpipe_iop_t *piece = pv_piece(p, m);
  if(!piece || !piece->enabled) return DTB_OK;
  const dt_hash_t h = dt_dev_hash_plus(&dtb_S.dev, &p->pipe, m->iop_order, DT_DEV_TRANSFORM_DIR_BACK_EXCL);
  if(!force && pv_g->buf && pv_g->buf_hash == h) return DTB_OK;
  static guint64 serial = 0;
  serial++;
  const dt_hash_t h0 = piece->hash;
  piece->hash = dt_hash(piece->hash, &serial, sizeof(serial));
  const int pw = p->pipe.processed_width, ph = p->pipe.processed_height;
  uint8_t *tmp = g_try_malloc((size_t)4 * MAX(1, pw) * MAX(1, ph));
  r = tmp ? dtb__pipe_run(p, 0, 0, pw, ph, 1.0f, tmp) : dtb__err(DTB_E_ENGINE, "out of memory");
  g_free(tmp);
  piece->hash = h0;
  return r;
}

/* gui_post_expose's points: the lines on the preview pipe's picture */
static void pv_points(dt_iop_module_t *self)
{
  dt_iop_ashift_gui_data_t *g = pv_g;
  free(g->points);
  g->points = NULL;
  free(g->points_idx);
  g->points_idx = NULL;
  free(g->draw_points);
  g->draw_points = NULL;
  g->points_lines_count = 0;
  if(!g->lines || g->lines_count < 1 || g->fitting) return;
  if(_get_points(self, g->lines, g->lines_count, g->lines_version, &g->points, &g->draw_points, &g->points_idx,
                 &g->points_lines_count, 1.0f))
  {
    g->points_version = g->lines_version;
    g->lines_hash = _get_lines_hash(g->lines, g->lines_count);
  }
}

static void pv_num(JsonBuilder *b, const char *k, const double v)
{
  json_builder_set_member_name(b, k);
  json_builder_add_double_value(b, isfinite(v) ? v : 0.0);
}

static void pv_xy(JsonBuilder *b, const float x, const float y, const float wd, const float ht)
{
  json_builder_add_double_value(b, x / wd);
  json_builder_add_double_value(b, y / ht);
}

static const char *pv_method_name(const dt_iop_ashift_method_t m)
{
  switch(m)
  {
    case ASHIFT_METHOD_AUTO: return "lines_found";
    case ASHIFT_METHOD_QUAD: return "rectangle";
    case ASHIFT_METHOD_LINES: return "drawn";
    default: return "none";
  }
}

static const char *pv_kind(const dt_iop_ashift_linetype_t t)
{
  switch(t & ASHIFT_LINE_MASK)
  {
    case ASHIFT_LINE_VERTICAL_SELECTED: return "vertical_used";
    case ASHIFT_LINE_VERTICAL_NOT_SELECTED: return "vertical_outlier";
    case ASHIFT_LINE_HORIZONTAL_SELECTED: return "horizontal_used";
    case ASHIFT_LINE_HORIZONTAL_NOT_SELECTED: return "horizontal_outlier";
    default: return "other";
  }
}

static const char *const pv_colors[] = { "grey", "green", "red", "blue", "yellow" };

/* the state: what desktop draws in gui_post_expose and shows in the panel */
static char *pv_state_json(dt_iop_module_t *self, const gboolean handled)
{
  dt_iop_ashift_gui_data_t *g = pv_g;
  const dt_iop_ashift_params_t *p = self->params;
  float wd = 0.0f, ht = 0.0f;
  dt_dev_get_preview_size(self->dev, &wd, &ht);
  wd = MAX(wd, 1.0f);
  ht = MAX(ht, 1.0f);
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "focused");
  json_builder_add_boolean_value(b, pv_focus);
  json_builder_set_member_name(b, "enabled");
  json_builder_add_boolean_value(b, self->enabled);
  json_builder_set_member_name(b, "method");
  json_builder_add_string_value(b, pv_method_name(g->current_structure_method));
  json_builder_set_member_name(b, "structure");
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "lines_found");
  json_builder_add_boolean_value(b, pv_toggle_get(g->structure_auto));
  json_builder_set_member_name(b, "rectangle");
  json_builder_add_boolean_value(b, pv_toggle_get(g->structure_quad));
  json_builder_set_member_name(b, "drawn");
  json_builder_add_boolean_value(b, pv_toggle_get(g->structure_lines));
  json_builder_end_object(b);
  // _gui_update_structure_states: the fit buttons work while a structure button is on
  json_builder_set_member_name(b, "fit_enabled");
  json_builder_add_boolean_value(b, pv_toggle_get(g->structure_auto) || pv_toggle_get(g->structure_quad)
                                        || pv_toggle_get(g->structure_lines));
  json_builder_set_member_name(b, "vertical");
  json_builder_add_int_value(b, g->vertical_count);
  json_builder_set_member_name(b, "horizontal");
  json_builder_add_int_value(b, g->horizontal_count);
  json_builder_set_member_name(b, "flipped");
  json_builder_add_boolean_value(b, g->isflipped == 1);
  json_builder_set_member_name(b, "handled");
  json_builder_add_boolean_value(b, handled);
  json_builder_set_member_name(b, "size");
  json_builder_begin_array(b);
  json_builder_add_int_value(b, (int)wd);
  json_builder_add_int_value(b, (int)ht);
  json_builder_end_array(b);

  // the lines as gui_post_expose draws them: removed lines of a drawn
  // structure are hidden; "near" is drawn thicker
  const gboolean drawn = g->current_structure_method == ASHIFT_METHOD_QUAD
                         || g->current_structure_method == ASHIFT_METHOD_LINES;
  json_builder_set_member_name(b, "lines");
  json_builder_begin_array(b);
  if(g->points_idx && g->draw_points)
    for(int n = 0; n < g->points_lines_count; n++)
    {
      const dt_iop_ashift_points_idx_t *ix = &g->points_idx[n];
      if(drawn && ix->type != ASHIFT_LINE_HORIZONTAL_SELECTED && ix->type != ASHIFT_LINE_VERTICAL_SELECTED) continue;
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "p");
      json_builder_begin_array(b);
      pv_xy(b, g->draw_points[4 * n], g->draw_points[4 * n + 1], wd, ht);
      pv_xy(b, g->draw_points[4 * n + 2], g->draw_points[4 * n + 3], wd, ht);
      json_builder_end_array(b);
      json_builder_set_member_name(b, "kind");
      json_builder_add_string_value(b, pv_kind(ix->type));
      json_builder_set_member_name(b, "color");
      json_builder_add_string_value(b, pv_colors[CLAMP((int)ix->color, 0, 4)]);
      json_builder_set_member_name(b, "near");
      json_builder_add_boolean_value(b, ix->near != 0);
      json_builder_end_object(b);
    }
  json_builder_end_array(b);

  // the corners of a drawn structure (rectangle: 4, drawn lines: both ends)
  json_builder_set_member_name(b, "handles");
  json_builder_begin_array(b);
  if(drawn && g->draw_points && g->lines)
  {
    const int nb = g->current_structure_method == ASHIFT_METHOD_LINES ? g->lines_count * 2 : MIN(4, g->lines_count * 2);
    for(int i = 0; i < nb && i / 2 < g->points_lines_count; i++)
    {
      if(g->lines[i / 2].type != ASHIFT_LINE_HORIZONTAL_SELECTED && g->lines[i / 2].type != ASHIFT_LINE_VERTICAL_SELECTED)
        continue;
      json_builder_begin_object(b);
      json_builder_set_member_name(b, "p");
      json_builder_begin_array(b);
      pv_xy(b, g->draw_points[i * 2], g->draw_points[i * 2 + 1], wd, ht);
      json_builder_end_array(b);
      json_builder_set_member_name(b, "near");
      json_builder_add_boolean_value(b, g->draw_near_point == i);
      json_builder_end_object(b);
    }
  }
  json_builder_end_array(b);

  // the selection area of shift+drag
  json_builder_set_member_name(b, "area");
  if(g->isbounding != ASHIFT_BOUNDING_OFF)
  {
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "from");
    json_builder_begin_array(b);
    json_builder_add_double_value(b, g->lastx);
    json_builder_add_double_value(b, g->lasty);
    json_builder_end_array(b);
    json_builder_set_member_name(b, "select");
    json_builder_add_boolean_value(b, g->isbounding == ASHIFT_BOUNDING_SELECT);
    json_builder_end_object(b);
  }
  else
    json_builder_add_null_value(b);

  // the automatic crop as gui_post_expose outlines it (while focused)
  json_builder_set_member_name(b, "clip");
  const dt_iop_ashift_bufgeom_t bg = _get_buf_geometry(self);
  gboolean clip = FALSE;
  if(pv_focus && self->enabled && bg.valid)
  {
    const float iwd = bg.width, iht = bg.height, ixo = bg.x_off, iyo = bg.y_off;
    float V[4][2] = { { ixo, iyo }, { ixo, iyo + iht }, { ixo + iwd, iyo + iht }, { ixo + iwd, iyo } };
    if(call_distort_transform(self->dev, self->dev->preview_pipe, self, (float *)V, 4))
    {
      float xmin = FLT_MAX, ymin = FLT_MAX, xmax = FLT_MIN, ymax = FLT_MIN;
      for(int n = 0; n < 4; n++)
      {
        xmin = MIN(xmin, V[n][0]);
        xmax = MAX(xmax, V[n][0]);
        ymin = MIN(ymin, V[n][1]);
        ymax = MAX(ymax, V[n][1]);
      }
      const float owd = xmax - xmin, oht = ymax - ymin;
      float C[4][2] = { { xmin + g->cl * owd, ymin + g->ct * oht },
                        { xmin + g->cl * owd, ymin + g->cb * oht },
                        { xmin + g->cr * owd, ymin + g->cb * oht },
                        { xmin + g->cr * owd, ymin + g->ct * oht } };
      if(dt_dev_distort_transform_plus(self->dev, self->dev->preview_pipe, self->iop_order,
                                       DT_DEV_TRANSFORM_DIR_FORW_EXCL, (float *)C, 4))
      {
        clip = TRUE;
        json_builder_begin_array(b);
        for(int n = 0; n < 4; n++)
        {
          json_builder_begin_array(b);
          pv_xy(b, C[n][0], C[n][1], wd, ht);
          json_builder_end_array(b);
        }
        json_builder_end_array(b);
      }
    }
  }
  if(!clip) json_builder_add_null_value(b);

  json_builder_set_member_name(b, "crop");
  json_builder_begin_array(b);
  json_builder_add_double_value(b, p->cl);
  json_builder_add_double_value(b, p->cr);
  json_builder_add_double_value(b, p->ct);
  json_builder_add_double_value(b, p->cb);
  json_builder_end_array(b);
  json_builder_set_member_name(b, "params");
  json_builder_begin_object(b);
  pv_num(b, "rotation", p->rotation);
  pv_num(b, "lensshift_v", p->lensshift_v);
  pv_num(b, "lensshift_h", p->lensshift_h);
  pv_num(b, "shear", p->shear);
  json_builder_set_member_name(b, "cropmode");
  json_builder_add_int_value(b, p->cropmode);
  json_builder_set_member_name(b, "saved_lines");
  json_builder_add_int_value(b, p->last_drawn_lines_count);
  json_builder_end_object(b);
  json_builder_set_member_name(b, "message");
  if(pv_message && pv_message->len) json_builder_add_string_value(b, pv_message->str);
  else json_builder_add_null_value(b);
  json_builder_end_object(b);
  return dtb__json_finish(b);
}

/* ------------------------------------------------------------------ */
/* the request                                                        */

static const char *pv_str(JsonObject *o, const char *k)
{
  if(!o || !json_object_has_member(o, k)) return NULL;
  JsonNode *n = json_object_get_member(o, k);
  return JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_STRING ? json_node_get_string(n) : NULL;
}

static int pv_dbl(JsonObject *o, const char *k, double *out)
{
  if(!o || !json_object_has_member(o, k)) return 0;
  JsonNode *n = json_object_get_member(o, k);
  if(!JSON_NODE_HOLDS_VALUE(n)) return -1;
  const GType t = json_node_get_value_type(n);
  if(t != G_TYPE_DOUBLE && t != G_TYPE_INT64) return -1;
  *out = t == G_TYPE_INT64 ? (double)json_node_get_int(n) : json_node_get_double(n);
  return isfinite(*out) ? 1 : -1;
}

static gboolean pv_bool(JsonObject *o, const char *k)
{
  if(!o || !json_object_has_member(o, k)) return FALSE;
  JsonNode *n = json_object_get_member(o, k);
  return JSON_NODE_HOLDS_VALUE(n) && json_node_get_value_type(n) == G_TYPE_BOOLEAN && json_node_get_boolean(n);
}

static GdkModifierType pv_mods(gboolean shift, gboolean ctrl)
{
  return (shift ? GDK_SHIFT_MASK : 0) | (ctrl ? GDK_CONTROL_MASK : 0);
}

__attribute__((visibility("default")))
int dtb_ashift(const char *op, int priority, const char *request_json, char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  dt_iop_module_t *m = dtb__find(op, priority);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "(null)", priority);
  if(strcmp(m->op, "ashift")) return dtb__err(DTB_E_ARG, "%s is not rotate and perspective", m->op);
  JsonNode *rn = dtb__json_parse(request_json && *request_json ? request_json : "{}");
  if(!rn) return DTB_E_ARG;
  JsonObject *req = JSON_NODE_HOLDS_OBJECT(rn) ? json_node_get_object(rn) : NULL;
  const char *action = pv_str(req, "action");
  if(!req || !action)
  {
    json_node_unref(rn);
    return dtb__err(DTB_E_ARG, "expected { \"action\": ... }");
  }
  const gboolean reading = !strcmp(action, "state");
  if(!reading && dtb_S.read_only)
  {
    json_node_unref(rn);
    return dtb__err(DTB_E_READ_ONLY, "session is read-only: its XMP failed the version guard");
  }
  const gboolean begin = !strcmp(action, "begin");
  if(!pv_is_ours(m))
  {
    if(!begin)
    {
      json_node_unref(rn);
      return dtb__err(DTB_E_STATE, "%s %d: call \"begin\" first (desktop: the module has the focus)", m->op, priority);
    }
    pv_g_new(m);
  }
  double radius = 0.0;
  if(pv_dbl(req, "radius", &radius) < 0 || radius < 0.0)
  {
    json_node_unref(rn);
    return dtb__err(DTB_E_ARG, "radius: a number >= 0 (a fraction of the picture's width)");
  }

  // the request's context: the bridge's preview pipe is desktop's
  int r = dtb__pipe_ready(FALSE, NULL);
  if(r)
  {
    json_node_unref(rn);
    return r;
  }
  dt_iop_ashift_gui_data_t *g = pv_g;
  dt_iop_ashift_params_t *p = m->params;
  dt_dev_pixelpipe_t *saved_preview = dtb_S.dev.preview_pipe;
  dtb_S.dev.preview_pipe = &dtb_S.preview.pipe;
  m->gui_data = g;
  if(pv_message) g_string_truncate(pv_message, 0);
  pv_recorded = FALSE;
  pv_record_module = NULL;
  pv_record_kind = DTB_CHANGE_OTHER;
  pv_button = GDK_BUTTON_PRIMARY;
  pv_state = 0;
  {
    float wd = 1.0f, ht = 1.0f;
    dt_dev_get_preview_size(&dtb_S.dev, &wd, &ht);
    // desktop's default near_delta is 20 px of the picture at fit
    pv_near_px = radius > 0.0 ? (float)(radius * wd) : 20.0f;
  }
  // gui_update: the shadow crop follows the parameters (not while a crop drag runs)
  if(!g->adjust_crop) _shadow_crop_box(p, g);

  gboolean handled = FALSE;
  if(begin)
  {
    if(!pv_focus)
    {
      pv_focus = TRUE;
      pv_salted[0] = pv_salted[1] = DT_INVALID_HASH;
      dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
      gui_focus(m, TRUE);
    }
    if(m->enabled) r = pv_ensure_buf(m, FALSE);
  }
  else if(!strcmp(action, "end"))
  {
    if(pv_focus)
    {
      gui_focus(m, FALSE);
      pv_focus = FALSE;
      dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
    }
  }
  else if(reading)
    ;
  else if(!pv_focus)
    r = dtb__err(DTB_E_STATE, "%s %d: call \"begin\" first", m->op, priority);
  else if(!strcmp(action, "structure"))
  {
    const char *method = pv_str(req, "method");
    const char *enhance = pv_str(req, "enhance");
    if(m->enabled) r = pv_ensure_buf(m, FALSE);
    if(r)
      ;
    else if(method && !strcmp(method, "lines_found"))
    {
      // _event_structure_auto_clicked: ctrl = edges, shift = detail, both = both
      const gboolean edges = enhance && (!strcmp(enhance, "edges") || !strcmp(enhance, "both"));
      const gboolean detail = enhance && (!strcmp(enhance, "detail") || !strcmp(enhance, "both"));
      if(enhance && !edges && !detail && strcmp(enhance, "none"))
        r = dtb__err(DTB_E_ARG, "enhance: none, edges, detail or both");
      else
      {
        pv_state = pv_mods(detail, edges);
        _event_structure_auto_clicked((GtkGestureSingle *)(void *)g->structure_auto, 1, 0.0, 0.0, m);
      }
    }
    else if(method && !strcmp(method, "rectangle"))
      _event_structure_quad_clicked((GtkGestureSingle *)(void *)g->structure_quad, 1, 0.0, 0.0, m);
    else if(method && !strcmp(method, "drawn"))
      _event_structure_lines_clicked((GtkGestureSingle *)(void *)g->structure_lines, 1, 0.0, 0.0, m);
    else
      r = dtb__err(DTB_E_ARG, "method: lines_found, rectangle or drawn");
  }
  else if(!strcmp(action, "fit"))
  {
    const char *axis = pv_str(req, "axis");
    const char *variant = pv_str(req, "variant");
    // desktop: ctrl = rotation only, shift = lens shift only; "both" plain
    // fits rotation, lens shift and shear, ctrl+shift all but shear
    const gboolean rot = variant && !strcmp(variant, "rotation");
    const gboolean shift = variant && !strcmp(variant, "shift");
    const gboolean noshear = variant && !strcmp(variant, "no_shear");
    if(variant && !rot && !shift && !noshear && strcmp(variant, "plain"))
      r = dtb__err(DTB_E_ARG, "variant: plain, rotation, shift or no_shear");
    else if(noshear && !(axis && !strcmp(axis, "both")))
      r = dtb__err(DTB_E_ARG, "no_shear is a variant of both");
    else if(m->enabled && (r = pv_ensure_buf(m, FALSE)))
      ;
    else if(axis && !strcmp(axis, "vertical"))
    {
      pv_state = pv_mods(shift, rot);
      _event_fit_v_button_clicked((GtkGestureSingle *)(void *)g->fit_v, 1, 0.0, 0.0, m);
    }
    else if(axis && !strcmp(axis, "horizontal"))
    {
      pv_state = pv_mods(shift, rot);
      _event_fit_h_button_clicked((GtkGestureSingle *)(void *)g->fit_h, 1, 0.0, 0.0, m);
    }
    else if(axis && !strcmp(axis, "both"))
    {
      pv_state = pv_mods(shift || noshear, rot || noshear);
      _event_fit_both_button_clicked((GtkGestureSingle *)(void *)g->fit_both, 1, 0.0, 0.0, m);
    }
    else
      r = dtb__err(DTB_E_ARG, "axis: vertical, horizontal or both");
  }
  else if(!strcmp(action, "pointer"))
  {
    const char *ev = pv_str(req, "event");
    double x = 0.0, y = 0.0, button = 1.0;
    if(pv_dbl(req, "x", &x) != 1 || pv_dbl(req, "y", &y) != 1 || pv_dbl(req, "button", &button) < 0
       || (button != 1.0 && button != 3.0))
      r = dtb__err(DTB_E_ARG, "pointer: x, y (fractions of the picture), button 1 or 3");
    else
    {
      pv_button = (guint)button;
      pv_state = pv_mods(pv_bool(req, "shift"), FALSE);
      if(ev && !strcmp(ev, "move"))
        handled = mouse_moved(m, (float)x, (float)y, 1.0, 0, 1.0f);
      else if(ev && !strcmp(ev, "press"))
      {
        if(m->enabled) r = pv_ensure_buf(m, FALSE);
        if(!r)
        {
          // a finger has no hover: the move desktop sees before a click
          if(!g->straightening) mouse_moved(m, (float)x, (float)y, 1.0, 0, 1.0f);
          // desktop's right-button drag without lines draws a straightening line:
          // the app has its own (crop tool), so not here
          if(!(pv_button == GDK_BUTTON_SECONDARY && !g->lines))
            handled = button_pressed(m, (float)x, (float)y, 1.0, pv_button, GDK_BUTTON_PRESS, pv_state, 1.0f);
        }
      }
      else if(ev && !strcmp(ev, "release"))
      {
        if(!g->straightening) handled = button_released(m, (float)x, (float)y, pv_button, pv_state, 1.0f);
      }
      else
        r = dtb__err(DTB_E_ARG, "event: move, press or release");
    }
  }
  else if(!strcmp(action, "changed"))
  {
    // a control of the module moved (dtb_param_set): bauhaus calls gui_changed,
    // then records the item; that item is the one the control's change opened
    const char *what = pv_str(req, "what");
    if(m->enabled) r = pv_ensure_buf(m, FALSE);
    if(!r)
    {
      pv_record_kind = DTB_CHANGE_PARAMS;
      const gboolean cropmode = what && !strcmp(what, "cropmode");
      gui_changed(m, cropmode ? g->cropmode : NULL, NULL);
      if(cropmode) dt_conf_set_int("plugins/darkroom/ashift/autocrop_value", p->cropmode);
      pv_history_item(m, TRUE);
    }
  }
  else
    r = dtb__err(DTB_E_ARG, "unknown action '%s'", action);

  // jobs desktop runs after the next preview (the module was off when asked)
  if(!r && g->jobcode != ASHIFT_JOBCODE_NONE && m->enabled)
  {
    r = pv_ensure_buf(m, TRUE);
    if(!r) _event_process_after_preview_callback(NULL, m);
    if(g->jobcode == ASHIFT_JOBCODE_DO_CROP) g->jobcode = ASHIFT_JOBCODE_NONE;
  }
  // the parameters take the crop desktop keeps in the gui data until the focus leaves
  if(!g->adjust_crop) _commit_crop_box(p, g);
  if(pv_recorded) dtb__change_end(pv_record_kind, pv_record_module);

  char *out = NULL;
  if(!r)
  {
    // the pipe after the change (the lines are drawn on its picture)
    r = dtb__pipe_ready(FALSE, NULL);
    if(!r)
    {
      pv_points(m);
      if(!strcmp(action, "pointer") && g->points_idx)
      {
        // the "near" lines and corner at the finger, as the next draw shows them
        double x = 0.0, y = 0.0;
        pv_dbl(req, "x", &x);
        pv_dbl(req, "y", &y);
        float wd = 1.0f, ht = 1.0f;
        dt_dev_get_preview_size(&dtb_S.dev, &wd, &ht);
        if(g->isbounding == ASHIFT_BOUNDING_OFF)
          _get_near(g->points, g->points_idx, g->points_lines_count, (float)x * wd, (float)y * ht, pv_near_px,
                    !(g->current_structure_method == ASHIFT_METHOD_LINES
                      || g->current_structure_method == ASHIFT_METHOD_QUAD));
        else
          _get_bounded_inside(g->points, g->points_idx, g->points_lines_count, (float)x * wd, (float)y * ht,
                              g->lastx * wd, g->lasty * ht, g->isbounding);
      }
      out = pv_state_json(m, handled);
    }
  }
  m->gui_data = NULL;
  dtb_S.dev.preview_pipe = saved_preview;
  json_node_unref(rn);
  if(r) return r;
  *json = out;
  return DTB_OK;
}

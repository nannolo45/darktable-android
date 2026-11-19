// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: history and undo (Engine API v1, section 2.6), and the
 * one path every change to the edit takes (grouping into history items,
 * undo states).
 *
 * Undo and redo: desktop darktable records a copy of the whole history
 * before each change (develop.c, dt_dev_undo_start_record) and restores it
 * by writing it to the library and reloading (libs/history.c, _pop_undo).
 * That code is tied to the darkroom's interface. The bridge keeps the same
 * whole-history states, but in darktable's own snapshot tables
 * (common/history_snapshot.c: dt_history_snapshot_create / _restore /
 * _clear, which the lighttable's history undo uses), and restores one by
 * reloading its develop object from the library.
 */
#include "dtb_internal.h"

#include "common/history.h"
#include "common/history_snapshot.h"
#include "common/image.h"
#include "common/image_cache.h"
#include "common/iop_order.h"
#include "common/tags.h"

#include <string.h>

#define DTB_UNDO_MAX 100  // the specification asks for at least 50

/* ------------------------------------------------------------------ */
/* history items                                                      */

/* as desktop's _dev_add_history_item() (develop.c), minus the interface
 * work. That function returns at once without the desktop interface
 * ("if(!darktable.gui ...) return"), so the bridge calls
 * dt_dev_add_history_item_ext() with no_image TRUE (the develop object has
 * no pipes of its own; the bridge's pipes are flagged below), then does the
 * rest of what desktop does after it: the "darktable|changed" tag and the
 * change timestamp. */
static void _history_add(dt_iop_module_t *m, const gboolean enable, const gboolean params_only)
{
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  const guint n0 = g_list_length(dtb_S.dev.history);
  const int end0 = dtb_S.dev.history_end;
  const GList *top0 = g_list_last(dtb_S.dev.history);
  dt_dev_add_history_item_ext(&dtb_S.dev, m, enable, TRUE);
  const guint n1 = g_list_length(dtb_S.dev.history);
  const GList *top1 = g_list_last(dtb_S.dev.history);
  // Which pipe work the change needs. Desktop decides in
  // _dev_add_history_item_ext() (develop.c): a change merged into the top
  // item -> DT_DEV_PIPE_TOP_CHANGED (dt_dev_pixelpipe_synch_top commits that
  // item's module only); a new item -> DT_DEV_PIPE_SYNCH
  // (dt_dev_pixelpipe_synch_all resets every module to its defaults and
  // replays the whole history). The bridge set SYNCH for both before P1c;
  // synch_all is the largest fixed part of a small preview render (P1c
  // write-up, item 5).
  // - merged into the top item in effect: TOP_CHANGED, as desktop;
  // - a parameter change (param_set, params_set) that appended one item on
  //   top of a history whose end was at its top (nothing discarded): also
  //   TOP_CHANGED. The pipe then differs from the previous state in that one
  //   module, which synch_top commits from the new item, the same parameters
  //   and switch a replay would end with. Desktop replays everything here;
  //   test C9 compares both kinds with a fresh session.
  // - everything else (instances, reset, actions, discarded items): SYNCH.
  const gboolean at_top = end0 == (int)n0 && dtb_S.dev.history_end == (int)n1 && top1
                          && ((dt_dev_history_item_t *)top1->data)->module == m;
  const gboolean merged = at_top && n1 == n0 && top1 == top0;
  const gboolean appended = at_top && n1 == n0 + 1 && params_only;
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  guint tagid = 0;
  dt_tag_new("darktable|changed", &tagid);
  dt_tag_attach(tagid, dtb_S.imgid, FALSE, FALSE);
  dt_image_cache_set_change_timestamp(dtb_S.imgid);
  // DTB_FORCE_SYNCH_ALL: the behaviour before P1c, for the before/after
  // measurement only (engine/tests/cost.sh)
  static int force = -1;
  if(force < 0) force = g_getenv("DTB_FORCE_SYNCH_ALL") != NULL;
  dtb__pipes_dirty((merged || appended) && !force ? DT_DEV_PIPE_TOP_CHANGED : DT_DEV_PIPE_SYNCH);
}

void dtb__history_add(dt_iop_module_t *m, const gboolean enable)
{
  _history_add(m, enable, FALSE);
}

void dtb__history_add_params(dt_iop_module_t *m, const gboolean enable)
{
  _history_add(m, enable, TRUE);
}

/* ------------------------------------------------------------------ */
/* undo states                                                        */

static dtb_undo_entry_t *_snapshot_take(void)
{
  // the library must hold the session's history first
  dt_dev_write_history(&dtb_S.dev);
  dtb_undo_entry_t *e = g_new0(dtb_undo_entry_t, 1);
  e->snap_id = dtb_S.next_snap_id++;
  e->n_items = g_list_length(dtb_S.dev.history);
  e->history_end = dtb_S.dev.history_end;
  // every item, also those above history_end (after a history_goto)
  dt_history_snapshot_create(dtb_S.imgid, e->snap_id, e->n_items);
  return e;
}

static void _snapshot_free(gpointer p)
{
  dtb_undo_entry_t *e = p;
  if(dtb_inited) dt_history_snapshot_clear(dtb_S.imgid, e->snap_id);
  g_free(e);
}

static void _snapshot_restore(const dtb_undo_entry_t *e)
{
  // dt_history_snapshot_restore() empties the image's history with
  // dt_history_delete_on_image_ext(), which also detaches the
  // "darktable|style|..." and "darktable|changed" tags and clears the change
  // timestamp. Those belong to the session, not to the history state: keep
  // the style tags, and mark the image changed as any edit does
  GList *keep = NULL;
  GList *attached = NULL;
  dt_tag_get_attached(dtb_S.imgid, &attached, FALSE);
  for(GList *t = attached; t; t = g_list_next(t))
  {
    const dt_tag_t *tag = t->data;
    if(g_str_has_prefix(tag->tag, "darktable|style|"))
      keep = g_list_prepend(keep, GUINT_TO_POINTER(tag->id));
  }
  dt_tag_free_result(&attached);

  dt_history_snapshot_restore(dtb_S.imgid, dtb_S.imgid, e->snap_id, e->n_items);
  dt_image_set_history_end(dtb_S.imgid, e->history_end);

  for(GList *t = keep; t; t = g_list_next(t))
    dt_tag_attach(GPOINTER_TO_UINT(t->data), dtb_S.imgid, FALSE, FALSE);
  g_list_free(keep);
  guint tagid = 0;
  dt_tag_new("darktable|changed", &tagid);
  dt_tag_attach(tagid, dtb_S.imgid, FALSE, FALSE);
  dt_image_cache_set_change_timestamp(dtb_S.imgid);

  dtb__reload_dev();
}

static void _redo_clear(void)
{
  dtb_undo_entry_t *e;
  while((e = g_queue_pop_head(&dtb_S.redo))) _snapshot_free(e);
}

void dtb__undo_clear(void)
{
  dtb_undo_entry_t *e;
  while((e = g_queue_pop_head(&dtb_S.undo))) _snapshot_free(e);
  _redo_clear();
}

static void _undo_record(void)
{
  g_queue_push_tail(&dtb_S.undo, _snapshot_take());
  while(g_queue_get_length(&dtb_S.undo) > DTB_UNDO_MAX)
    _snapshot_free(g_queue_pop_head(&dtb_S.undo));
  _redo_clear();
}

/* ------------------------------------------------------------------ */
/* grouping                                                           */

void dtb__change_begin(const dtb_change_t kind, dt_iop_module_t *m)
{
  // a parameter change on the instance whose item is still open merges
  // into it, as a slider drag does on desktop
  if(kind == DTB_CHANGE_PARAMS && m && dtb_S.merge_module == m) return;
  _undo_record();
  dt_dev_history_seal(&dtb_S.dev, TRUE);
  dtb_S.merge_module = NULL;
}

void dtb__change_end(const dtb_change_t kind, dt_iop_module_t *m)
{
  if(kind == DTB_CHANGE_PARAMS)
    dtb_S.merge_module = m;
  else
  {
    dtb_S.merge_module = NULL;
    dt_dev_history_seal(&dtb_S.dev, TRUE);
  }
}

int dtb_history_seal(void)
{
  NEED_SESSION();
  dtb_S.merge_module = NULL;
  dt_dev_history_seal(&dtb_S.dev, TRUE);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* history                                                            */

static void _item(JsonBuilder *b, const int num, const char *op, const int priority, const char *name,
                  const char *instance_name, const gboolean enabled, const gboolean unknown)
{
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "num");
  json_builder_add_int_value(b, num);
  json_builder_set_member_name(b, "op");
  json_builder_add_string_value(b, op);
  json_builder_set_member_name(b, "priority");
  json_builder_add_int_value(b, priority);
  json_builder_set_member_name(b, "name");
  json_builder_add_string_value(b, name);
  json_builder_set_member_name(b, "instance_name");
  json_builder_add_string_value(b, instance_name);
  json_builder_set_member_name(b, "enabled");
  json_builder_add_boolean_value(b, enabled);
  json_builder_set_member_name(b, "unknown");
  json_builder_add_boolean_value(b, unknown);
  json_builder_end_object(b);
}

int dtb_history(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "end");
  json_builder_add_int_value(b, dtb_S.rows ? dtb_S.rows_end : dtb_S.dev.history_end);
  json_builder_set_member_name(b, "items");
  json_builder_begin_array(b);
  if(dtb_S.rows)
  {
    // read-only session whose XMP lists items the engine could not load:
    // every row, in the XMP's order (session.c, _rows_match)
    for(guint i = 0; i < dtb_S.rows->len; i++)
    {
      const dtb_row_t *r = &g_array_index(dtb_S.rows, dtb_row_t, i);
      const dt_dev_history_item_t *h = r->dev_index >= 0 ? g_list_nth_data(dtb_S.dev.history, r->dev_index) : NULL;
      if(h)
        _item(b, i, h->op_name, h->multi_priority, h->module ? h->module->name() : h->op_name, h->multi_name,
              h->enabled, FALSE);
      else
        _item(b, i, r->op, r->priority, r->op, r->instance_name, r->enabled, TRUE);
    }
  }
  else
  {
    int num = 0;
    for(GList *l = dtb_S.dev.history; l; l = g_list_next(l), num++)
    {
      const dt_dev_history_item_t *h = l->data;
      _item(b, num, h->op_name, h->multi_priority, h->module ? h->module->name() : h->op_name, h->multi_name,
            h->enabled, FALSE);
    }
  }
  json_builder_end_array(b);
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

/* desktop's history panel pops the items in memory (dt_dev_pop_history_items).
 * The bridge does that, then writes the history and reloads its develop
 * object from the library, as compress and undo do: modules whose items are
 * above `end` then show the defaults a fresh session computes for them.
 * Without the reload they keep defaults computed when they were created;
 * several modules' defaults depend on whether the instance is the first in
 * the pipe (dt_iop_is_first_instance, for example exposure's
 * compensate_hilite_pres), which an instance delete or move changes. */
int dtb_history_goto(int end)
{
  // navigation (engine-api.md 2.6): allowed in a read-only session (it only
  // writes the private library, never an XMP) and not an undo step. The
  // next edit records the state it starts from, with this end, and then
  // discards the items above it, as on desktop.
  NEED_SESSION();
  // positions count the rows history() lists; with unloaded rows (read-only
  // view) the develop object goes to the loaded items below that position
  const int n = dtb_S.rows ? (int)dtb_S.rows->len : (int)g_list_length(dtb_S.dev.history);
  if(end < 0 || end > n) return dtb__err(DTB_E_ARG, "end %d outside 0..%d", end, n);
  const int row_end = end;
  if(dtb_S.rows) end = dtb__rows_dev_end(row_end);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  dt_dev_pop_history_items_ext(&dtb_S.dev, end);
  dtb_S.dev.iop = g_list_sort(dtb_S.dev.iop, dt_sort_iop_by_order);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dt_dev_write_history(&dtb_S.dev);
  dtb__reload_dev();
  if(dtb_S.rows) dtb_S.rows_end = row_end;
  dtb_S.merge_module = NULL;
  dt_dev_history_seal(&dtb_S.dev, TRUE);
  return DTB_OK;
}

/* dt_dev_history_truncate() (develop.c) is the desktop path; it reloads
 * through dt_dev_reload_history_items(), which touches the darkroom's pipes
 * and widgets (dev->full.pipe is NULL here). The bridge keeps its library
 * steps and reloads the develop object instead. */
int dtb_history_compress(void)
{
  NEED_WRITABLE();
  dtb__change_begin(DTB_CHANGE_OTHER, NULL);
  dt_dev_write_history(&dtb_S.dev);
  dt_history_compress_on_image(dtb_S.imgid);
  dtb__reload_dev();
  // dt_history_compress_on_image() leaves images.history_end as it was, so
  // it can point past the shorter history; dt_dev_history_truncate() then
  // sets it to the new number of items, and so does the bridge
  const int n = g_list_length(dtb_S.dev.history);
  if(dtb_S.dev.history_end != n)
  {
    dt_image_set_history_end(dtb_S.imgid, n);
    dt_dev_pop_history_items_ext(&dtb_S.dev, n);
  }
  dtb__change_end(DTB_CHANGE_OTHER, NULL);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* undo and redo                                                      */

static int _step(GQueue *from, GQueue *to, const char *what)
{
  NEED_WRITABLE();
  dtb_undo_entry_t *e = g_queue_pop_tail(from);
  if(!e) return dtb__err(DTB_E_STATE, "nothing to %s", what);
  // the current state goes to the other queue, so the step can be reversed
  g_queue_push_tail(to, _snapshot_take());
  _snapshot_restore(e);
  _snapshot_free(e);
  dtb_S.merge_module = NULL;
  dt_dev_history_seal(&dtb_S.dev, TRUE);
  return DTB_OK;
}

int dtb_undo(void) { return _step(&dtb_S.undo, &dtb_S.redo, "undo"); }
int dtb_redo(void) { return _step(&dtb_S.redo, &dtb_S.undo, "redo"); }

int dtb_undo_state(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "can_undo");
  json_builder_add_boolean_value(b, !dtb_S.read_only && !g_queue_is_empty(&dtb_S.undo));
  json_builder_set_member_name(b, "can_redo");
  json_builder_add_boolean_value(b, !dtb_S.read_only && !g_queue_is_empty(&dtb_S.redo));
  json_builder_end_object(b);
  *json = dtb__json_finish(b);
  return DTB_OK;
}

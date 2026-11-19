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
void dtb__history_add(dt_iop_module_t *m, const gboolean enable)
{
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  dt_dev_add_history_item_ext(&dtb_S.dev, m, enable, TRUE);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  guint tagid = 0;
  dt_tag_new("darktable|changed", &tagid);
  dt_tag_attach(tagid, dtb_S.imgid, FALSE, FALSE);
  dt_image_cache_set_change_timestamp(dtb_S.imgid);
  dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
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

int dtb_history(char **json)
{
  if(json) *json = NULL;
  NEED_SESSION();
  if(!json) return dtb__err(DTB_E_ARG, "no result pointer");
  JsonBuilder *b = json_builder_new();
  json_builder_begin_object(b);
  json_builder_set_member_name(b, "end");
  json_builder_add_int_value(b, dtb_S.dev.history_end);
  json_builder_set_member_name(b, "items");
  json_builder_begin_array(b);
  int num = 0;
  for(GList *l = dtb_S.dev.history; l; l = g_list_next(l), num++)
  {
    const dt_dev_history_item_t *h = l->data;
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "num");
    json_builder_add_int_value(b, num);
    json_builder_set_member_name(b, "op");
    json_builder_add_string_value(b, h->op_name);
    json_builder_set_member_name(b, "priority");
    json_builder_add_int_value(b, h->multi_priority);
    json_builder_set_member_name(b, "name");
    json_builder_add_string_value(b, h->module ? h->module->name() : h->op_name);
    json_builder_set_member_name(b, "instance_name");
    json_builder_add_string_value(b, h->multi_name);
    json_builder_set_member_name(b, "enabled");
    json_builder_add_boolean_value(b, h->enabled);
    json_builder_end_object(b);
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
  const int n = g_list_length(dtb_S.dev.history);
  if(end < 0 || end > n) return dtb__err(DTB_E_ARG, "end %d outside 0..%d", end, n);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  dt_dev_pop_history_items_ext(&dtb_S.dev, end);
  dtb_S.dev.iop = g_list_sort(dtb_S.dev.iop, dt_sort_iop_by_order);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dt_dev_write_history(&dtb_S.dev);
  dtb__reload_dev();
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

// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine bridge: module instances (Engine API v1, section 2.5). Behaviour
 * follows desktop darktable as documented in S3-bridge-prototype.md,
 * section 6; each function names the desktop code it follows.
 */
#include "dtb_internal.h"

#include "common/iop_order.h"
#include "develop/blend.h"
#include "develop/masks.h"

#include <string.h>

static int _find_writable(const char *op, int prio, dt_iop_module_t **out)
{
  NEED_WRITABLE();
  dt_iop_module_t *m = dtb__find(op, prio);
  if(!m) return dtb__err(DTB_E_NOT_FOUND, "no instance %s.%d", op ? op : "(null)", prio);
  *out = m;
  return DTB_OK;
}

/* follows dt_iop_gui_duplicate() (develop/imageop.c) without its widgets */
static int _instance_add(const char *op, int prio, const gboolean copy, int *new_prio)
{
  dt_iop_module_t *base = NULL;
  const int r = _find_writable(op, prio, &base);
  if(r) return r;
  if(base->flags() & IOP_FLAGS_ONE_INSTANCE)
    return dtb__err(DTB_E_ARG, "%s allows one instance only", base->op);

  dtb__change_begin(DTB_CHANGE_OTHER, base);
  // "make sure the duplicated module appears in the history"
  dtb__history_add(base, FALSE);

  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  dt_iop_module_t *m = dt_dev_module_duplicate_ext(&dtb_S.dev, base, TRUE);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  if(!m)
  {
    dtb__change_end(DTB_CHANGE_OTHER, NULL);
    return dtb__err(DTB_E_ENGINE, "dt_dev_module_duplicate_ext failed");
  }

  dt_iop_reload_defaults(m);
  gboolean masks_copied = FALSE;
  if(copy)
  {
    memcpy(m->params, base->params, m->params_size);
    if(m->flags() & IOP_FLAGS_SUPPORTS_BLENDING)
    {
      dt_iop_commit_blend_params(m, base->blend_params, NULL);
      if(dt_is_valid_maskid(base->blend_params->mask_id)
         || dt_is_valid_maskid(base->blend_params->blend_mask_id))
      {
        m->blend_params->mask_id = NO_MASKID;
        m->blend_params->blend_mask_id = NO_MASKID;
        // P1b part 2: on the session's forms (dt_masks_iop_use_same_as works
        // on the global develop object and left the copy without its mask)
        masks_copied = dtb__masks_use_same_as(m, base);
      }
    }
  }
  // "we save the new instance creation"
  dt_dev_history_seal(&dtb_S.dev, TRUE);
  if(masks_copied)
  {
    // as dt_masks_iop_use_same_as: a masks history item for the copy
    dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
    dt_dev_add_masks_history_item_ext(&dtb_S.dev, m, TRUE, TRUE);
    dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  }
  dtb__history_add(m, TRUE);
  dtb__pipes_dirty(DT_DEV_PIPE_REMOVE);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  if(new_prio) *new_prio = m->multi_priority;
  return DTB_OK;
}

int dtb_instance_new(const char *op, int prio, int *new_prio)
{
  return _instance_add(op, prio, FALSE, new_prio);
}

int dtb_instance_duplicate(const char *op, int prio, int *new_prio)
{
  return _instance_add(op, prio, TRUE, new_prio);
}

/* follows _gui_delete_callback() (develop/imageop.c). Its history clean-up
 * lives in dt_dev_module_remove() but only runs with gui_attached, so the
 * bridge does it here. */
int dtb_instance_delete(const char *op, int prio)
{
  dt_iop_module_t *module = NULL;
  const int r = _find_writable(op, prio, &module);
  if(r) return r;

  dt_iop_module_t *next = NULL;
  gboolean found = FALSE;
  for(GList *l = dtb_S.dev.iop; l; l = g_list_next(l))
  {
    dt_iop_module_t *mod = l->data;
    if(mod == module)
    {
      found = TRUE;
      if(next) break;
    }
    else if(mod->instance == module->instance)
    {
      next = mod;
      if(found) break;
    }
  }
  if(!next) return dtb__err(DTB_E_ARG, "cannot delete the last instance of %s", module->op);

  dtb__change_begin(DTB_CHANGE_OTHER, NULL);
  const gboolean is_zero = (module->multi_priority == 0);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);

  // remove the module's history items. history_end only moves for items
  // below it (dt_dev_module_remove decrements it for every item)
  int idx = 0;
  for(GList *l = dtb_S.dev.history; l;)
  {
    GList *nl = g_list_next(l);
    dt_dev_history_item_t *hist = l->data;
    if(hist->module == module)
    {
      dt_dev_free_history_item(hist);
      dtb_S.dev.history = g_list_delete_link(dtb_S.dev.history, l);
      if(idx < dtb_S.dev.history_end) dtb_S.dev.history_end--;
    }
    else
      idx++;
    l = nl;
  }
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dt_dev_module_remove(&dtb_S.dev, module);  // takes the module out of dev.iop

  // drop its entry from the order list (as dt_dev_reload_history_items does
  // with dt_ioppr_resync_iop_list after a desktop delete)
  dt_ioppr_resync_iop_list(&dtb_S.dev);

  if(is_zero)
  {
    dt_iop_module_t *first = NULL;
    for(GList *h = dtb_S.dev.history; h; h = g_list_next(h))
    {
      dt_dev_history_item_t *hist = h->data;
      if(hist->module->instance == module->instance && hist->module != module)
      {
        first = hist->module;
        break;
      }
    }
    if(!first) first = next;
    // the order list must follow the renumbering, or the instance would
    // take the deleted one's place in the pipe when the XMP is read again
    GList *link = dt_ioppr_get_iop_order_link(dtb_S.dev.iop_order_list, first->op,
                                              first->multi_priority);
    if(link) ((dt_iop_order_entry_t *)link->data)->instance = 0;
    dt_iop_update_multi_priority(first, 0);
    for(GList *h = dtb_S.dev.history; h; h = g_list_next(h))
    {
      dt_dev_history_item_t *hist = h->data;
      if(hist->module == first) hist->multi_priority = 0;
    }
    dt_ioppr_resync_modules_order(&dtb_S.dev);
  }

  // as desktop: keep the module alive, a pipe node may still point to it
  dtb_S.dev.alliop = g_list_append(dtb_S.dev.alliop, module);
  dtb__pipes_dirty(DT_DEV_PIPE_REMOVE);
  dtb__change_end(DTB_CHANGE_OTHER, NULL);
  return DTB_OK;
}

/* follows _gui_moveup_callback / _gui_movedown_callback (imageop.c); the
 * neighbour is the next module that is not hidden, as the desktop panel
 * shows them when every module is visible */
int dtb_instance_move(const char *op, int prio, int direction)
{
  dt_iop_module_t *module = NULL;
  const int r = _find_writable(op, prio, &module);
  if(r) return r;
  if(direction != 1 && direction != -1) return dtb__err(DTB_E_ARG, "direction must be +1 or -1");

  GList *self = g_list_find(dtb_S.dev.iop, module);
  dt_iop_module_t *other = NULL;
  for(GList *l = direction > 0 ? g_list_next(self) : g_list_previous(self); l;
      l = direction > 0 ? g_list_next(l) : g_list_previous(l))
  {
    dt_iop_module_t *mod = l->data;
    if(!dt_iop_is_hidden(mod))
    {
      other = mod;
      break;
    }
  }
  if(!other) return dtb__err(DTB_E_ARG, "nothing to move past");

  const gboolean allowed = direction > 0
    ? dt_ioppr_check_can_move_after_iop(dtb_S.dev.iop, module, other)
    : dt_ioppr_check_can_move_before_iop(dtb_S.dev.iop, module, other);
  if(!allowed)
    return dtb__err(DTB_E_ARG, "pipeline rules forbid moving %s past %s", module->op, other->op);

  dtb__change_begin(DTB_CHANGE_OTHER, module);
  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  const gboolean moved = direction > 0 ? dt_ioppr_move_iop_after(&dtb_S.dev, module, other)
                                       : dt_ioppr_move_iop_before(&dtb_S.dev, module, other);
  if(moved) dtb_S.dev.iop = g_list_sort(dtb_S.dev.iop, dt_sort_iop_by_order);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  if(!moved)
  {
    dtb__change_end(DTB_CHANGE_OTHER, NULL);
    return dtb__err(DTB_E_ENGINE, "move refused by iop order");
  }
  dtb__history_add(module, TRUE);  // desktop passes enable TRUE here too
  dtb__pipes_dirty(DT_DEV_PIPE_REMOVE);
  dtb__change_end(DTB_CHANGE_OTHER, module);
  return DTB_OK;
}

/* follows the instance label entry (imageop.c, _rename_module_key_press and
 * dt_iop_update_multi_name with force TRUE): a typed name is kept as hand
 * edited and switches the module on; an empty one clears the hand-edited
 * flag, and the history item then sets darktable's automatic label */
int dtb_instance_rename(const char *op, int prio, const char *name)
{
  dt_iop_module_t *m = NULL;
  const int r = _find_writable(op, prio, &m);
  if(r) return r;
  if(!name) return dtb__err(DTB_E_ARG, "no name");
  gchar *l_name = g_strstrip(g_strdup(name));
  if(strlen(l_name) >= sizeof(m->multi_name))
  {
    g_free(l_name);
    return dtb__err(DTB_E_RANGE, "name longer than %zu bytes", sizeof(m->multi_name) - 1);
  }
  const gboolean hand_edited = l_name[0] != '\0';
  dtb__change_begin(DTB_CHANGE_OTHER, m);
  g_strlcpy(m->multi_name, l_name, sizeof(m->multi_name));
  m->multi_name_hand_edited = hand_edited;
  dtb__history_add(m, hand_edited);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  g_free(l_name);
  return DTB_OK;
}

/* ------------------------------------------------------------------ */
/* masks: prototype from S3 (test T9), replaced in work package P1b   */

int dtb_mask_add_circle(const char *op, int prio, float cx, float cy, float radius, float border)
{
  dt_iop_module_t *m = NULL;
  const int r = _find_writable(op, prio, &m);
  if(r) return r;
  if(!(m->flags() & IOP_FLAGS_SUPPORTS_BLENDING) || (m->flags() & IOP_FLAGS_NO_MASKS))
    return dtb__err(DTB_E_ARG, "%s has no drawn masks", m->op);
  if(radius <= 0.0f || border < 0.0f) return dtb__err(DTB_E_ARG, "bad circle");

  dtb__change_begin(DTB_CHANGE_OTHER, m);
  dt_masks_form_t *form = dt_masks_create(DT_MASKS_CIRCLE);
  dt_masks_point_circle_t *c = calloc(1, sizeof(dt_masks_point_circle_t));
  c->center[0] = cx;
  c->center[1] = cy;
  c->radius = radius;
  c->border = border;
  form->points = g_list_append(NULL, c);

  dt_pthread_mutex_lock(&dtb_S.dev.history_mutex);
  // as dt_masks_gui_form_save_creation() and dt_masks_group_insert_member()
  // (develop/masks/masks.c), whose history calls return without the GUI
  dt_masks_assign_unique_name(&dtb_S.dev, form);
  dtb_S.dev.forms = g_list_append(dtb_S.dev.forms, form);
  dt_masks_group_insert_point(&dtb_S.dev, m, form);
  // what the mask on/off switch sets (blend_gui.c, _blendop_mask_enable)
  m->blend_params->mask_mode |= DEVELOP_MASK_ENABLED | DEVELOP_MASK_FLEXI;
  dt_dev_add_masks_history_item_ext(&dtb_S.dev, m, TRUE, TRUE);
  dt_pthread_mutex_unlock(&dtb_S.dev.history_mutex);
  dtb__pipes_dirty(DT_DEV_PIPE_SYNCH);
  dtb__change_end(DTB_CHANGE_OTHER, m);
  return DTB_OK;
}

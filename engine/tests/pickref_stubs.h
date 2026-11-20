// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Engine test C25 (brief P3c deliverable 6): the desktop interface around the copied picker callbacks.
 * Included after a module's pickref_up_<op>_types.inc (darktable's headers) and before its
 * pickref_up_<op>_funcs.inc (the callback, verbatim). The interface calls become no-ops, and the values of the
 * module's widgets are the test's own: every widget the callback reads or writes is the address of a float (a
 * slider) or an int (a list, a switch) in pickref.h's pickref_widgets_t.
 */
#pragma once

#undef DT_GUARD_GUI_UPDATE
#define DT_GUARD_GUI_UPDATE(...) do {} while(0)
#undef DT_TRY_GUI_UPDATE
#define DT_TRY_GUI_UPDATE(...) do {} while(0)
#undef DT_ENTER_GUI_UPDATE
#define DT_ENTER_GUI_UPDATE() do {} while(0)
#undef DT_LEAVE_GUI_UPDATE
#define DT_LEAVE_GUI_UPDATE() do {} while(0)

#define dt_bauhaus_slider_set(w, v) (*(float *)(void *)(w) = (float)(v))
#define dt_bauhaus_slider_get(w) (*(float *)(void *)(w))
#define dt_bauhaus_combobox_set(w, v) (*(int *)(void *)(w) = (int)(v))
#define dt_bauhaus_combobox_get(w) (*(int *)(void *)(w))
#define dt_bauhaus_toggle_get(w) (*(int *)(void *)(w))
#define dt_dev_add_history_item(...) do {} while(0)
#define dt_iop_gui_enter_critical_section(m) do {} while(0)
#define dt_iop_gui_leave_critical_section(m) do {} while(0)
#define gtk_label_set_text(...) do {} while(0)
#define gtk_widget_queue_draw(...) do {} while(0)
#define dt_control_queue_redraw_widget(...) do {} while(0)
#define dt_conf_set_float(k, v) do {} while(0)
#define dt_conf_set_bool(k, v) do {} while(0)
/* no ctrl or shift held: rgbcurve's "flat" offset */
#define dt_key_modifier_state() ((GdkModifierType)0)

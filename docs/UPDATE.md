# Updating to a new darktable version

The steps are in PLAN.md section 5 ("Updating to a new darktable version"): `tools/bump-upstream`, CI rebuild, Linux
engine tests against that version's darktable-cli, `schema-diff`, bridge compile errors fixed inside `engine/`, APK and
device checklist. Checks that belong to particular bridge files:

- `engine/src/actions.c` and the other files that re-implement desktop callbacks: compare with upstream (list in
  `docs/experiments/P1a-engine-core.md`).
- `engine/src/p6a_actions.c` and `engine/src/p6a_agx.c` (brief P6a) carry blocks marked "copied verbatim from
  src/iop/<module>.c" (rgblevels, colorequal, colorzones, colorharmonizer, agx). On every bump: diff those modules
  against the previous pin and refresh each marked block from the new source; test C43 compiles desktop's own picker
  and button callbacks from the new source and compares them field by field with the bridge's actions (bound 1e-6), so
  a stale copy fails there.
- `engine/src/perspective.c` (brief P3n) compiles `upstream/darktable/src/iop/ashift.c` unmodified and answers its
  GUI calls with macros defined before the include (widgets and toggles, click button and modifiers,
  `dt_dev_add_history_item`, `dt_control_log`, `dt_toast_log`, `dt_conf_get_float` for `near_delta`,
  `DT_PIXEL_APPLY_DPI`, `DT_GUARD_GUI_UPDATE` and friends, critical sections) and copies `gui_init`'s non-widget values
  and `process()`'s preview-buffer capture. On every bump: diff `ashift.c` (and `ashift_lsd.c`, `ashift_nmsimplex.c`)
  against the previous pin; every GUI call reachable from the functions perspective.c calls (structure and fit button
  handlers, `mouse_moved`, `button_pressed`, `button_released`, `gui_focus`, `gui_changed`,
  `_event_process_after_preview_callback`, `_get_points`) must still be covered by a macro, and the struct fields it
  sets in `pv_g_new` must still exist. A missed GUI call crashes (GTK is never initialised); test C33 runs all of them.

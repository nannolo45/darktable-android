# P3d stage 2a: mask engine calls and the mask tree in the masks tab (report)

Branch `p3d-masks-stage2a` from `main` `35dd523`. Brief: `docs/briefs/P3d-masks-stage2a-engine.md`. Manual notes:
`docs/design/manual-notes-masks.md`. Engine calls: `docs/design/engine-api.md` 6.9. Screens: `docs/design/ui-spec.md`
section 8. The `gh` command is not signed in on the build workspace: this file is the pull request description.

## 1. What the brief asked and what was delivered

| item | state |
|---|---|
| 1 one history item per finished shape; a brush session of n strokes ended by `done` one item; cancel no item | done: `dtb_mask_add` (create + attach as one masks item of the instance) and `dtb_history_group_begin` / `_end(keep)` (see 4.1 for why both) |
| 2 tree editing: move within a group, into another group, remove from any group, wrap in group, group together; ARG checks; one item per call | done: `dtb_mask_member_move`, `_member_remove`, `_member_update` (member settings in any group), `dtb_mask_group_wrap` (one id = wrap, several = group together) |
| 3 drawn & parametric: find what desktop does; parametric elements if it is the tree | done: it is the tree (4.2); parametric elements are created with `dtb_mask_add` type `parametric`, read back by `dtb_masks`, updated with `dtb_mask_update` |
| 4 app: one undo step per shape / brush session | done: `finishDraw` uses `maskAdd` inside a history group; emulator scenario d6 checks it (see 3) |
| 5 app: the mask as a tree (seven operators, invert and opacity per group and member, move up/down, wrap, group together, remove from any group; nested groups indented and closed by default) | done (`ui/MaskTree.kt`, hooks in `MasksPanel.kt`); replaces stage 1's `combine shapes` row |
| 6 not built: geometry editing after `done`, parametric screens | not built, as the brief says |
| bridge tests for every call (history count, undo/redo, XMP round trip) | done: C26 `masktree` on three raw files, plus darktable-cli parity of three XMPs each |
| JVM tests of the tree logic against the stand-in | done: `P3dMaskTreeTest` (darkroom), `FakeMaskTreeTest` (engine-api) |
| screenshots of the tree, both skins, phone | done: `phone-p3d-05-mask-tree-{darktable,modern}` |
| emulator: circle one item and one undo; brush of three strokes one item; difference changes with order; XMP reopens with the same tree; darktable-cli renders the XMP as the app (compare step) | see section 3 (scenarios d6, d7; compare case `p3d-tree`) |
| docs: brief copied, engine-api 6.9, ui-spec 8, this report | done |
| CI triggers | p2a-app and p2b-real-engine: branch added. `engine.yml` has no push trigger (it is a reusable workflow called by p2b-real-engine and g0-app), so the branch went into `g0-app.yml`, the workflow that runs the bridge tests on Linux (C26) |

Not changed: `upstream/`, `app/lpl`, the LPL contract, the repository layout. Composables of `MasksPanel.kt` keep their
names and places; `MemberRow` is no longer called (the tree rows replace it) but is left in place, as the brief asks
not to move or rename composables there. `maskSlider` / `MaskSlider` became `internal` so the new file can use them.

## 2. How to reproduce

- Bridge (Linux, CI job g0-linux): `engine/tests/run_tests.sh <bridge build> <dt prefix> <test images> <out>`; case C26
  (`dtb_test masktree <raw> <xmp> <outdir>`, source `engine/tests/dtb_test_p3d.c`), parity of
  `masktree_{difference,groups,parametric}` with darktable-cli.
- Stand-in engine, locally: `cd app && gradle :engine-api:test --tests '*FakeMaskTreeTest*'`.
- App JVM (CI, needs the Android SDK): `gradle :darkroom:testDebugUnitTest --tests '*P3dMaskTreeTest*'`; screenshots
  `--tests '*PhoneMaskTreeScreenshots*'` (written to `app/darkroom/build/outputs/screenshots`).
- Emulator: `P3dScenarioTest.d6_oneItemPerShapeAndPerBrushSession`, `d7_differenceOrderAndTree` (in
  `tools/app/emulator-p2b.sh`'s list already); results in `files/p2b-test/p3d-results.txt`; the compare step renders
  `p3d-tree.xmp` with darktable-cli (`for name in circle brush tree`).
- Screen rules: `python3 tools/app/check-screen-rules.py` (0 violations).

## 3. Results

Bridge, C26 (x86_64 Linux runner, run 45060569473, job g0-linux: 359 PASS, 0 FAIL in all bridge tests):

| | cr2 | raf | arw |
|---|---|---|---|
| circle by `mask_add`: history items | 10 -> 11 | 13 -> 14 | 15 -> 16 |
| brush session of 3 strokes in a group | 11 -> 12 | 14 -> 15 | 16 -> 17 |
| cancelled group: history / can_redo | 12 / false | 15 / false | 17 / false |
| difference of a big and a small circle, order swapped: render max diff (mean) | 118 (7.45) | 80 (9.92) | 118 (11.25) |
| nesting: groups made below depth 3 before ARG | 5 | 5 | 5 |
| tree after reopening the bridge's XMP | equal | equal | equal |
| render after reopening: max diff | 0 | 0 | 0 |
| darktable-cli of the three XMPs vs bridge | max 0 (PSNR inf) | max 0 | max 0 |

C26 also checks: one undo removes the shape / the whole session, redo brings it back; an empty group makes no undo
step; `history_seal` inside a group changes nothing; each move, wrap, update, remove is one item; undo of a move;
refusals (`RANGE` position, `NOT_FOUND` member, `ARG` for a group into itself or into its own tree, a group member's
opacity, an unknown operator, an empty list, a channel that does not exist, a channel change); a parametric element
read back (`channel g`, `in [0.2 0.3 0.7 0.8]`, `in_active`), a whole-range element flagged `whole_range` and not
active; a parametric element in a nested group. C22 (6.7, unchanged calls) still passes on all three files.

App JVM (run 45060569452 and later): 337 tests, 0 failures, `P3dMaskTreeTest` 2/2, `P3dMasksTest` 9/9 (stage 1,
unchanged), `PhoneMaskTreeScreenshots` 2/2, `FakeMaskTreeTest` 2/2.

Emulator (x86_64 emulator, API 33, run 45070957074, 83 instrumented tests OK):

| scenario | result |
|---|---|
| d6 circle on exposure | history `..., exposure` +1 item; one undo removes it, redo brings it back |
| d6 brush, three strokes by injected touch | +1 item (`..., exposure, exposure`), one undo removes all three |
| d7 difference of a big and a small circle, order swapped by `move down` | preview mean abs. diff 10.24 (0..255); the move is one item |
| d7 wrap the big circle in a group | preview diff 0.0 (a group of one with union changes nothing) |
| d7 XMP written and reopened | tree rows `[0:group, 1:circle, 0:circle]` both times; picture diff 0.0 |
| compare: darktable-cli of `p3d-tree.xmp` vs the app's export | PSNR 45.26 dB (circle 45.06, brush 45.05; bound 40), as the other app-XMP cases |

## 4. Upstream behaviour worth knowing; where the app differs

1. **Why both a one-step call and a history group.** A shape made by `mask_create` + `mask_attach` was two items
   (mask manager, then the instance). `dtb_mask_add` makes one (the instance's masks item carries the new form). A
   brush session is several forms; the bridge's undo records one whole-history state per change
   (`history.c dtb__change_begin`), and darktable's `_dev_add_history_item_ext` (`develop/develop.c`) merges a
   masks item into the open item of the same module unless the history is sealed. So a group needs no merging code of
   its own: inside it only the first change records the undo state and seals; later ones add to the open item.
   Cancel restores the recorded state. Smaller alternatives were weaker: a "create n and attach" call would need the
   app to hold strokes back (it does today, but the group also covers later uses such as parametric edits), and
   merging items after the fact would have to know which items may be merged.
2. **Drawn & parametric at the pinned commit is the tree.** Desktop's mask panel has only on/off now
   (`blend_gui.c` `_blendop_mask_enable` -> `_blendop_masks_mode_callback(ENABLED | FLEXI)`); the mode names
   (`dt_develop_mask_mode_names`, including "drawn & parametric mask") remain for old edits, which
   `masks/migrate_legacy.c` `_migrate_drawn_and_parametric` turns into a flexi tree. Parametric channels are elements
   (`DT_MASKS_PARAMETRIC`, `dt_masks_point_parametric_t`, created by `blend_gui.c` `_add_parametric_channel`,
   evaluated by `masks/parametric.c`). The classic flags still render (`blend.c` reads `DEVELOP_MASK_CONDITIONAL`),
   so `blend_set`'s `parametric.*` paths (6.6) stay valid for classic edits.
3. **Element invert is the member's.** Desktop never sets an element's own polarity bits (comment in
   `_add_parametric_channel`); its "invert" is the member's `INVERSE`. The bridge does the same; `polarity` is only
   read back (migrated edits may carry it).
4. **A group's member settings.** The group fold (`masks/group.c`) applies the member point's opacity and inversion
   for any member, group or not, but desktop's panel keeps a group's invert and opacity on its marker and leaves the
   reference neutral (`dt_masks_group_insert_point`). The bridge refuses `inverse` / `opacity` on a group member
   (`ARG`, use `mask_group_set`), and the app's tree edits a group's own settings.
5. **Rows top first.** A group's list is bottom first (it folds from its first member); desktop's panel shows rows
   bottom-up ("Rows display bottom-up, so above is later in the list", `blend_gui.c`). The tab does the same: the top
   row is the last member.
6. **Operator labels.** Desktop lists "maximum (union)", "screen (smooth union)", "sum (additive union)", "minimum
   (intersection)", "product (smooth intersection)", "difference", "exclusion". The brief asks for plain names first:
   the sheet lists union (maximum), intersection (minimum), difference, exclusion, screen (smooth union), sum
   (additive union), product (smooth intersection); a group row shows the short plain name.
7. **Nesting limits** follow desktop exactly: `_may_move_into` for moves, `dt_masks_model_group_points` for wrap and
   group together (`DT_MASKS_NESTING_MAX` 8).
8. **New groups** are named "group #n" as desktop's `_new_nested_group`; the bridge cannot call those helpers (they
   are static and use the global `darktable.develop`, not the session's develop object), so it repeats their steps
   on the session's forms, as it already did for `dt_masks_iop_use_same_as` (6.7).
9. **Mode after `mask_add`:** ENABLED | FLEXI is added to the mode, as `mask_attach` does (6.7, stage 1 report 4.3).
10. Not built: compose, simplify (manual), drag and drop between groups (the app uses move up / down and the `more`
    sheet instead), choosing the group new shapes go into (the call supports `group`; the tab adds to the module's
    own group).

## 5. Open questions for the reviewer

- Should the tab let the user pick the group a new shape goes into (desktop: the selected group)? The engine call
  has `group` already.
- `MemberRow` in `MasksPanel.kt` is unused now; remove it in a later clean-up?
- The parametric screens (other package) can use `maskAdd(type = "parametric")`, `maskUpdate` and the tree; they
  need nothing more from the engine for elements.

## CI

Last code commit `12b44a1c` (app and engine); `f7556ae8` changes only the p2b workflow's cache key (4 below) and is
the commit the final runs built:

| workflow | run | result |
|---|---|---|
| p2a-app | 45068138347 (`12b44a1c`) | success: 337 JVM tests, 0 failures (P3dMaskTreeTest 2/2, PhoneMaskTreeScreenshots 2/2, FakeMaskTreeTest 2/2) |
| p2b-real-engine | 45070957074 (`f7556ae8`) | success: apk, emulator 83/83, compare all ok (`p3d=ok`, tree PSNR 45.26 dB) |
| g0-app (bridge tests; engine.yml is called from here and from p2b) | 45070957078 (`f7556ae8`) | success: g0-linux 359 PASS, 0 FAIL (C26 on cr2, raf, arw); engine builds, apk, emulator ok |

## Running log

- 22 Nov: read CLAUDE.md, brief, notes, engine-api 6.6-6.8, stage 1 report; upstream read with grep (develop.c
  history merge, blend_gui.c wrap / group together / mode buttons / `_add_parametric_channel`, parametric.c, group.c).
- Bridge calls, C26, JNI entry `p3d`, Kotlin API, stand-in; syntax-checked locally with gcc against the upstream
  headers (no darktable build here); `:engine-api:test` green locally. Commit `60576ee`.
- App: tree, one-step drawing, tests, screenshots, emulator d6/d7. First p2a run: compile error, a `TreeRow` class
  already existed (`StylesPresets.kt`); renamed to `MaskTreeRow` (`220c5a7`). p2a green.
- g0-app run 45060569473 (commit `a172976`): bridge tests 359 PASS / 0 FAIL, C26 on all three files, parity max 0.
- p2b run 45060569454: the apk job failed fetching a Robolectric artefact (`nativeruntime-dist-compat-1.0.18`
  transform), infrastructure; the workspace cannot rerun jobs (`gh` not signed in), so a small real change
  (`12b44a1`: the group line counts members) started the workflows again.
- p2b run 45068138293 (`12b44a1`): apk and emulator green (d6, d7 pass), compare failed at "Restore Linux build":
  the linux job had logged "Cache not found" and then "Unable to reserve cache ... another job may be creating this
  cache", so nothing was saved; a rerun of the failed job missed again. Fix (`f7556ae8`): the cache key now also
  hashes `dtb_test_p1b2.c` and `dtb_test_p3d.c`, which `dtb_test.c` includes (correct anyway), giving a fresh key.
- p2b run 45070957074 and g0 run 45070957078 on `f7556ae8`: green.

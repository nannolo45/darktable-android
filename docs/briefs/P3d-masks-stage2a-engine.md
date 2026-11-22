# Brief P3d stage 2a: mask engine calls and the mask tree in the masks tab

Branch `p3d-masks-stage2a` from `main` (`35dd523`). Follows stage 1 (`docs/briefs/P3d-masks-stage1.md`, report
`docs/experiments/P3d-masks-stage1-REPORT.md`, sections 1 and 4 list what is missing). Engine calls of today:
`docs/design/engine-api.md` 6.6 to 6.8. Manual notes for masks (read these, the manual is the reference):
`docs/design/manual-notes-masks.md` (copy it there from the path the reviewer gives you).

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours, no
re-reading of whole large files.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal; never edit `upstream/`). Never push to `main`, no pull
requests; push after every deliverable. `engine/` and `engine-jni` changes are expected here: each new call is
documented in `engine-api.md` (new section 6.9), tested in the bridge tests, and exposed in Kotlin like 6.7's calls.
Do not touch `app/lpl`. Screen code: tokens and icons only, both skins (as stage 1). Another agent works in parallel
on branch `p3d-masks-parametric` (parametric mode screens and refinement, new files only plus small hooks in
`MasksPanel.kt`): keep your app changes in `Masks.kt`, the drawn part of `MasksPanel.kt` and new files; do not
rename or move existing composables in `MasksPanel.kt`.

## Engine deliverables
1. **One history item per finished shape.** Drawing a shape and attaching it to a module is one history item and one
   undo step; a brush session with n strokes ended by `done` is one item too. Choose the smallest sound design and
   say why (for example a create-and-attach call, and/or a bridge-level history group `begin`/`end` that merges the
   items in between). Cancel leaves no item.
2. **Tree editing**, as the manual's groups-and-operators page: move a member to another position in its group;
   move a member into another group; remove a member from any group (not only a module's own group); wrap a member
   in a new group at its position; group several members together. Keep `ARG` checks (no cycles, no group into
   itself). One history item per call.
3. **Drawn & parametric.** Find what desktop at the pinned commit does for "drawn & parametric" (flexi tree with
   parametric elements, or the classic ENABLED|MASK|CONDITIONAL flags with blendif). If it is parametric elements
   in the tree, add the calls to create one (channel, ranges in/out, invert, boost, opacity), read it back in
   `dtb_masks`, and update it; if it is the classic flags, say so and add nothing. Report what you found with file
   and function names.

## App deliverables (masks tab, drawn part)
4. Use deliverable 1: one undo step per shape / brush session (emulator history shows one item).
5. The module's mask as a tree, as the manual describes: groups with their operator (all seven: union, intersection,
   difference, exclusion, screen, sum, product; plain names first, as desktop lists them), invert and opacity per
   group and per member; move up / move down per member (order matters for difference); `wrap in group` and
   `group together…` in the member's `more` sheet; `remove from this group` for any group. This replaces stage 1's
   single "combine shapes" row. Keep it compact on the phone: nested groups indented one step, collapsed by default
   beyond the first level.
6. Do not build editing a shape's geometry after `done`, nor the parametric screens: other packages.

## Tests
Bridge tests for every new call (history count, undo/redo, XMP round trip). JVM tests of the tree logic against the
stand-in engine (extend it minimally). Screenshots of the tree in both skins, phone. Emulator with the real engine:
circle on exposure is one history item and one undo; brush with three strokes is one item; difference with two
shapes changes when their order is swapped; XMP reopens with the same tree; darktable-cli renders the XMP as the
bridge does (add one case to the compare step). Do not weaken existing tests.

## Docs
This brief into `docs/briefs/`; `engine-api.md` 6.9; `ui-spec.md` section 8 updated; report
`docs/experiments/P3d-masks-stage2a-REPORT.md` in `CLAUDE.md`'s form with a short running log.

## CI
Add the branch to the triggers of `engine.yml`, `p2a-app.yml`, `p2b-real-engine.yml`. Stop when all three are green on
the last code commit including the emulator job, or after three failed fix attempts on one problem; then report
plainly. Maven Central / mirror "could not find" failures are infra: rerun the failed job once.

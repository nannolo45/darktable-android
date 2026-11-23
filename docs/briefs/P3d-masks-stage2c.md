# Brief P3d stage 2c: edit shapes after `done`, choose the group, drawn & parametric

Branch `p3d-masks-stage2c` from `main` (`15719b7`; stages 2a and 2b merged; the `l1` skin (PUI3a) is merged too and hosts the masks tab). Earlier stages: `docs/briefs/P3d-*`, reports
`docs/experiments/P3d-*-REPORT.md`. Engine calls: `docs/design/engine-api.md` 6.7 to 6.9 (2a added one-step shapes,
history groups, tree editing and parametric elements as tree members). Manual notes: `docs/design/manual-notes-masks.md`.

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal). Never push to `main`, no pull requests; push after
every deliverable. No engine change expected; if one is unavoidable, stop and describe it in the report first.
Do not touch `app/lpl`. Screen code: tokens and icons only, all skins. Another branch (`pui3a-l1-layout`) adds a
third darkroom layout and hosts the masks tab content without editing it; keep the masks tab a self-contained
composable (no new parameters without defaults).

## Deliverables
1. **Choose the group a new shape goes into** (owner's decision). In the masks tab, the tree has a selected group
   (default: the module's own top group); tapping a group row selects it (marked). The add buttons add into the
   selected group, through 2a's one-step call. A line above the add buttons says where a new shape goes
   (`new shapes go into: <group name>`).
2. **Edit a shape after `done`.** Tapping a shape in the tree (or `edit` in its `more` sheet) opens drawing mode on
   that shape: drag to move; size, feather (and rotation / flatness, compression) sliders as when drawing; path:
   drag a point; brush: move only. `done` writes one history item (`mask_update`, inside a history group if several
   calls), `cancel` restores. Shapes used by several modules: say so in the tool panel ("used by n modules").
3. **Drawn & parametric** (desktop at the pinned commit: parametric elements as members of the flexi tree). Enable
   the mode; in the tree an `add parametric…` button (channel buttons as in parametric mode) creates a parametric
   element in the selected group; its row opens the same controls as parametric mode (reuse `RangeSlider4`,
   `ParametricMask.kt` pieces; do not copy them). Show mask works.

## Tests
JVM tests against the stand-in engine (extend minimally): selected group receives the new shape; edit + cancel
leaves no history; edit + done is one item; a parametric element is added to the selected group. Screenshots in
both existing skins, phone. Emulator with the real engine: circle moved after `done` changes the render and is one
undo step; a shape added to a nested group lands there; drawn & parametric (circle intersected with a luminance
range) changes the picture as expected; XMP reopens the same; darktable-cli compare case `p3d-dp`. Do not weaken
existing tests.

## Docs
This brief into `docs/briefs/`; `ui-spec.md` section 8; report `docs/experiments/P3d-masks-stage2c-REPORT.md` in
`CLAUDE.md`'s form with CI run ids.

## CI
Add the branch to the triggers of `p2a-app.yml` and `p2b-real-engine.yml`. Stop when both are green on the last code
commit including the emulator job, or after three failed fix attempts on one problem. Maven "could not find" / 429
failures are infra: rerun with `gh run rerun <id> --failed` (gh is signed in), not with empty commits.

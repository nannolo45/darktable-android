# Brief P3h: snapshots with a split view

Branch `p3h-snapshots` from `main` (`b35edb7`). PLAN.md Phase 3 item 7; mockup agreed earlier:
`docs/design/mockups/B3-Snapshots.html` (take snapshot, list with names and delete, split view with a draggable
divider). The owner (23 Nov) also wants **snapshot** in l1's `more` tool and the compare / split view in all skins.
engine-api.md line 113 describes today's "before" view (history_goto + render + goto back) and names a call that
renders a history position without touching the session as missing.

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal; the darktable manual's snapshots page is the reference:
desktop takes a snapshot of the current history end, shows it on one side of a movable divider, can rotate the divider
and switch sides; snapshots live for the session only). Never push to `main`, no PRs; push after every deliverable.
Engine: a bridge call that renders a stored history position (preview and region, same options as today's renders)
without moving the session's history end, cancel-safe like other renders; documented in `engine-api.md` (new section),
tested in the bridge tests (equal to goto + render + goto back), exposed in Kotlin. Never `upstream/`. Screen code:
tokens and icons only; three skins: screenshots in darktable and modern, and l1.

## Deliverables
1. Engine call above; the app's "before" view moves onto it (no more goto-and-back under the lock).
2. Snapshots panel as `B3-Snapshots`: take snapshot (named by its history item, as desktop), list, select, delete; at
   most as many as desktop keeps (check: 4?), held in memory for the open photo.
3. Split view: the selected snapshot on one side, the live edit on the other, a draggable divider (44 px handle),
   switch sides, vertical/horizontal divider as desktop's rotate; works with zoom and pan (both sides follow the same
   view); editing on the live side keeps the snapshot fixed.
4. Where it lives: darktable and modern skins as the mockup places it; l1: `snapshot` in `more` and the compare
   button opens the split with the last snapshot.

## Tests
Bridge: the new render equals goto + render + goto back for several positions, history end unchanged, cancel.
JVM: take / select / delete / limit, split state, before-view on the new call. Screenshots: panel, split vertical
and horizontal, l1 more. Emulator: take a snapshot, change exposure, split shows both (left half differs from right
half as expected), history end unchanged.

## Docs and CI
Report `docs/experiments/P3h-snapshots-REPORT.md` in CLAUDE.md's form; `ui-spec.md` new section; brief into
`docs/briefs/`. Branch in the triggers of `g0-app.yml`, `p2a-app.yml`, `p2b-real-engine.yml`. Stop when green on the
last code commit incl. the emulator, or after three failed fix attempts on one problem. Maven 429:
`gh run rerun <id> --failed`.

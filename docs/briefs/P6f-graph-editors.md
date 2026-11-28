# Brief P6f: graph editors — base curve, saturation curve, lowlight, raw denoise, denoise wavelets; curve zoom

Branch `p6f-graph-editors` from `main` (this brief is its first commit). Read CLAUDE.md, PLAN.md 6.1 item 3, the audit rows "base curve", "tone curve", "saturation curve", "denoise (profiled)", "raw denoise" and "lowlight vision" and point 7 of the summary in `docs/experiments/P5a-module-audit.md`, and the briefs/reports of the existing editors: `docs/briefs/P3e-curves.md`, `docs/briefs/P3e-bands.md` and their reports. Read the manual page of each module before building its graph. Desktop reference (pinned submodule; `git submodule update --init --depth 1 upstream`): `src/iop/basecurve.c`, `src/iop/satcurvergb.c` (or wherever the pinned tree defines the `satcurvergb` op), `src/iop/lowlight.c`, `src/iop/rawdenoise.c`, `src/iop/denoiseprofile.c` — in each the graph's `draw`, `motion_notify`, `button_press`, `scrolled` handlers and the parameter layout of the nodes. Medium-to-large package; work in the order below and commit per module.

## 1. Reuse, do not fork
Build each graph on the existing **curve editor** (P3e curves) or **band editor** (P3e bands): extend them with the options a module needs (node count limits, fixed-x nodes, log/linear axes, per-channel tabs, background drawing such as a histogram or a gradient) rather than writing a new editor per module. Behaviour must follow desktop's handlers: how a node is added, moved, removed (desktop: double-click resets, right-click deletes → pick the app's existing gestures for these, as tone curve/rgb curve already do), node limits and clamping, the "reset" of a curve, and the influence radius where desktop uses one (band editors). One history item per finished gesture; undo works.

## 2. Modules
1. **base curve** (`basecurve`): the curve graph (with its log scale / "scale for graph" as desktop), presets already work and must still apply and show on the graph. Desktop's preserve-colors and exposure-fusion sliders already work; do not break them.
2. **saturation curve** (`satcurvergb`): the curve itself and its colour picker (P3c machinery) if desktop has one.
3. **lowlight vision** (`lowlight`): the lowlight curve (band-style, x = luminance, y = night↔day), the module's main control.
4. **raw denoise** (`rawdenoise`): the per-frequency curves (all / R / G / B tabs as desktop), band editor.
5. **denoise (profiled)** (`denoiseprofile`): the wavelet curves of the wavelets mode (desktop's channel tabs: all/R/G/B or Y0/U0V0 depending on the mode), shown only when desktop shows them.
6. **"scale for graph"** / curve zoom for tone curve (and rgb curve if desktop has it in the pinned tree): the display zoom of the graph as desktop's slider, display only (no parameter written unless desktop writes one — check).

Remove the matching "not available" notes from `schema/overrides/*.json`; regenerate the unbound-controls list as P6e did.

## 3. Bridge
Probably nothing new: the nodes are params arrays written with `params_set`. Add actions only if needed (for example a module's own reset of one curve), in `dtbridge.h` + `docs/design/engine-api.md` 5.4, with bridge tests at the next free C ids in `engine/tests/TEST-IDS.md`. Never touch `upstream/`.

## 4. Tests
- Bridge (g0 with `tests=` limited to the new ids plus C14): for each of the five modules, write a non-default curve, XMP round trip, render differs from default (simple statistic) and equals darktable-cli for the same XMP.
- JVM: per module, the gesture → params mapping (add/move/delete node, clamps, node limits, tab switching), and the zoom maths.
- Screenshots (p2a): each graph with a non-default curve, in the three skins; the denoise wavelet tabs; tone curve zoomed.
- Emulator (p2b): one tap test per module where practical (at least base curve, lowlight and raw denoise): drag a node → one history item, the preview changes. Wait on real conditions, not sleeps. Run p2b at least twice on the final commit; both green.

## 5. Out of scope
Monochrome's filter graph, color correction's grid, colour harmonizer analyze/import (P6a leftovers), new curve algorithms.

## Economy and report
One agent, no sub-agents; save tokens (no long log dumps into context, grep the artefacts). Report in `docs/experiments/P6f-graph-editors-REPORT.md` as CLAUDE.md says, with CI run ids, and open the pull request with the REST API (`gh api repos/nannolo45/darktable-android/pulls -f title=... -f head=p6f-graph-editors -f base=main -f body=...`; GraphQL is blocked). Do not merge it.

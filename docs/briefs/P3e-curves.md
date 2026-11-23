# Brief P3e-a: the shared curve editor (tone curve, rgb curve, rgb levels, tone equalizer)

Branch `p3e-curves` from `main` (`7c4571c`). PLAN.md Phase 3 item 3. Mockup agreed earlier for the tone equalizer:
`docs/design/mockups/B3-ToneEq.html` (ui-spec.md line 46). Engine calls: `docs/design/engine-api.md` (param_set paths
such as `curve[2].x`; module actions 6.3 incl. rgbcurve `set_nodes_from_area`; histogram 6.4; coordinates 6.5).
Schema overrides exist in `schema/overrides/tonecurve.json`, `rgbcurve.json`.

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal; the darktable manual is the reference: read the manual
pages of tone curve, rgb curve, rgb levels and tone equalizer before building; where the app differs, say so).
Never push to `main`, no pull requests; push after every deliverable. No engine change: if the engine lacks
something, build without it and report it. Do not touch `app/lpl`, `MasksPanel.kt`, `MaskTree.kt`. Screen code:
tokens and icons only; three skins exist (darktable, modern, l1): screenshots in darktable and modern, and the
graph must fit l1's panel (it hosts module controls unchanged). Another agent works in parallel on branch
`p3e-bands` (contrast equalizer, color zones, color equalizer): keep your code in new files (`ui/graph/Curve*.kt`)
and touch shared module-row code only to call your editor.

## Deliverables
1. **One curve editor component**: a square graph (token-sized, full panel width on the phone) with the module's
   histogram behind it where desktop draws one, nodes as handles (touch target 44 px), drag to move, tap empty space to
   add a node, drag a node off the graph or long press → remove, double tap the graph → reset the curve; one history
   item per lifted finger. Values written through `param_set` on the module's node arrays, nodes kept in order and
   inside desktop's limits (min / max node count as the module defines).
2. **tone curve**: channel tabs (L, a, b as desktop), interpolation method, preserve colors, the controls the schema
   already generates stay as they are.
3. **rgb curve**: channel tabs R, G, B with linked/unlinked mode as desktop; the picker that sets nodes from an area
   (`set_nodes_from_area`, reusing P3c's shared picker tool).
4. **rgb levels**: three handles (black, grey, white) over the histogram; per channel in independent mode; the
   existing pickers stay.
5. **tone equalizer**: the graph of the `advanced` tab as in `B3-ToneEq.html` (nine bands -8..0 EV, drag a band
   point, smoothing as desktop), and **drag on the photo**: a vertical drag on the photo raises or lowers the band
   of the luminance under the finger (read the exposure at the touched point with the engine's picker; if that is
   not possible without an engine change, build the graph only and report).
6. The editor replaces the "control not available" placeholders of these modules (P2j's list), and the l1 layout
   shows it in the open module.

## Tests
JVM tests (node order, add/remove/limits, one history item per drag, reset, channel switching) against the
stand-in engine; screenshots of each module's editor in darktable and modern (phone) and one tablet shot; emulator
with the real engine: a tone curve node moved changes the picture and the XMP reopens identical; rgb curve
`set_nodes_from_area`; tone equalizer band raised changes the picture; darktable-cli compare case per module
(`p3e-tonecurve`, `p3e-rgbcurve`, `p3e-rgblevels`, `p3e-toneeq`). Do not weaken existing tests.

## Docs
This brief into `docs/briefs/`; `ui-spec.md` new section "graph editors: curves"; report
`docs/experiments/P3e-curves-REPORT.md` in `CLAUDE.md`'s form with CI run ids.

## CI
Add the branch to the triggers of `p2a-app.yml` and `p2b-real-engine.yml`. Stop when both are green on the last code
commit including the emulator job, or after three failed fix attempts on one problem. Maven 429 / "could not find":
`gh run rerun <id> --failed` (gh is signed in), never empty commits.

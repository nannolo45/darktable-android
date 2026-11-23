# Brief P3e-b: the shared band editor (contrast equalizer, color zones, color equalizer)

Branch `p3e-bands` from `main` (`7c4571c`). PLAN.md Phase 3 item 4 (plus color equalizer's graph, which the roadmap
lists with the graph editors). Engine calls: `docs/design/engine-api.md` (param_set paths such as
`equalizer_y[0][3]`; colour pickers 6.3). Schema overrides: `schema/overrides/colorzones.json` and the generated
schema for atrous (contrast equalizer) and colorequal.

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal; read the manual pages of contrast equalizer, color
zones and color equalizer before building; where the app differs, say so). Never push to `main`, no pull requests;
push after every deliverable. No engine change: if the engine lacks something, build without it and report it.
Do not touch `app/lpl`, `MasksPanel.kt`, `MaskTree.kt`. Screen code: tokens and icons only; three skins exist
(darktable, modern, l1): screenshots in darktable and modern; the editor must fit l1's panel. Another agent works in
parallel on branch `p3e-curves` (tone curve, rgb curve, rgb levels, tone equalizer): keep your code in new files
(`ui/graph/Band*.kt`) and touch shared module-row code only to call your editor.

## Deliverables
1. **One band editor component**: a wide graph (full panel width, token height) with a fixed x axis (frequency for
   contrast equalizer; hue, lightness or chroma for color zones; hue for color equalizer) and one value per node;
   drag a node vertically (and horizontally where desktop allows it); a soft brush radius as desktop's mouse-wheel
   radius, set by a small slider; tabs for the curves the module has (contrast equalizer: luma, chroma, edges;
   color zones: lightness, chroma, hue; color equalizer: hue, saturation, brightness); the x axis drawn with the hue
   band for hue axes (from tokens: the band is data, not decoration; say how you keep rule 5); double tap resets the
   tab's curve; one history item per lifted finger.
2. **contrast equalizer** (atrous): the three curves, mix where desktop has it.
3. **color zones**: select by (lightness / chroma / hue), the three curves, process mode (smooth / strong), the
   picker (P3c's shared tool) that marks the picked hue on the graph if the engine gives it without change.
4. **color equalizer**: the graph view of its hue / saturation / brightness curves next to the sliders the schema
   already generates (the sliders stay), the nodes moving the same parameters.
5. The editor replaces the "control not available" placeholders of these modules (P2j's list), and the l1 layout
   shows it in the open module.

## Tests
JVM tests (node limits, brush radius effect on neighbours, one history item per drag, reset, tab switching) against
the stand-in engine; screenshots in darktable and modern (phone) and one tablet shot; emulator with the real engine:
a node moved in each module changes the picture and the XMP reopens identical; darktable-cli compare case per module
(`p3e-atrous`, `p3e-colorzones`, `p3e-colorequal`). Do not weaken existing tests.

## Docs
This brief into `docs/briefs/`; `ui-spec.md` new section "graph editors: bands"; report
`docs/experiments/P3e-bands-REPORT.md` in `CLAUDE.md`'s form with CI run ids.

## CI
Add the branch to the triggers of `p2a-app.yml` and `p2b-real-engine.yml`. Stop when both are green on the last code
commit including the emulator job, or after three failed fix attempts on one problem. Maven 429 / "could not find":
`gh run rerun <id> --failed` (gh is signed in), never empty commits.

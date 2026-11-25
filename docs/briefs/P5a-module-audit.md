# Brief P5a: module coverage audit

Branch `p5a-module-audit` from `main`. Read CLAUDE.md, PLAN.md 3.3, 3.4 and 6.1. This is an audit: **no feature work and no fixes** in this package (a one-line fix that a test proves is fine; anything larger goes into the table as a finding).

## Question to answer
For **every processing module (iop) of the pinned darktable**, including deprecated and hidden ones, not only the 29 priority modules: what can the owner do with it on the phone today, and what is missing, with the reason.

## Output
1. `docs/experiments/P5a-module-audit.md`: one table the owner picks from, plain language, sorted by darktable's default module groups, deprecated modules in a separate short table. Columns:
   - module (darktable's English name, internal name)
   - renders as desktop (yes / no / not checked, with the test)
   - editing on the phone: **works / partly / missing** (PLAN 3.4 tier reached)
   - what is missing and why, concretely (e.g. liquify's on-photo tool; LUT 3D file chooser and where LUT files would come from; lens correction's lens list and lensfun database in the APK; denoise (profiled) noise profiles; watermark and framing assets (SVGs, fonts); a list filled at run time; controls the schema marks unbound; a picker or on-photo part not built)
   - size of the gap: small (schema override or a list) / medium (a dialog or a data file set) / large (on-photo tool or graph editor)
2. `docs/experiments/P5a-results/modules.json`: the same data, machine readable, produced by a script (`tools/module-audit.py`) from the sources below so that P5b can re-run it after the engine bump and diff it.
3. A short list at the top of the report: the modules the reviewer should offer the owner first (most useful for a photographer, least work), without deciding for him.

## Sources, cheapest first
- The module list: the iop plugins built from the pinned upstream (`tools/fetch-darktable.sh`), with flags (deprecated, hidden, one-instance, default group) read from the sources or from the bridge.
- `schema/ui-schema.json` per image class and `schema/overrides/`: controls generated, controls unbound, run-time lists, file choosers, buttons with no parameter.
- The app: which modules have a hand-built editor or on-photo tool (curves, bands, crop, perspective, masks, pickers, retouch, look up table) and which are filtered out of the lists.
- Data files a module needs at run time and whether the APK carries them (lensfun db, noise profiles `noiseprofiles.json`, watermark SVGs, LUT folder option, film stocks, colour profiles).
- **Renders:** for each module that can be switched on with default parameters, a bridge render compared with `darktable-cli` on one Bayer and one X-Trans test image (existing `compare.py`, same thresholds as the suite). One loop in the existing Linux test build, as a new test command in its own file (take the next free test id; say which in the report). Modules that need input to do anything (retouch, liquify, masks) are "not checked" unless an existing test already covers them: name that test.

## Economy
One agent, no sub-agents. Do not build darktable on the workspace if the CI's cached Linux build can run the loop (g0-app); push small commits and read the CI log. Do not re-test what the existing suite proves: cite the test id. Stop and report if the render loop needs more than two CI rounds to become green for reasons that are not findings.

## Report
The audit document is the report; add `docs/experiments/P5a-module-audit-REPORT.md` only for the CLAUDE.md items (reproduce commands, CI run ids, surprises in upstream, open questions). Personal details stay out of the repository as always.

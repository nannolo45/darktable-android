# Brief S5: generated control descriptions

Phase 0 experiment. Read `CLAUDE.md` and `docs/PLAN.md` (sections 3.3, 3.4, 5) before starting.

## Question this experiment answers
Can the labels, order, sections, tabs, ranges, units and visibility rules of darktable's module controls be extracted automatically from an unmodified desktop darktable, well enough that the Android app can build most module panels from a data file instead of hand-written screens?

## Environment
- Ubuntu 24.04, root, 2 cores, `xvfb-run` installed. Another agent may be using the machine; avoid needless rebuilds.
- A Linux build of darktable at the pinned commit already exists: source `/home/claude/dt/src`, build tree `/home/claude/dt/build`, install prefix `/home/claude/dt/install` (built by `engine/tests/build-darktable-linux.sh`). Reuse it; rebuild only if it is missing. Make all paths in your scripts arguments or environment variables.
- Work in your own clone: `git clone https://github.com/nannolo45/darktable-android /home/claude/s5-work`. Do not use other directories under `/home/claude` except the darktable build above (read-only) and your own scratch directory `/home/claude/s5-build`.

## Deliverables
1. **`schema/extractor/`**: an out-of-tree darktable plugin (a "lib" module or whatever upstream's plugin mechanism allows without changing upstream), built against the installed darktable and its build tree with the same compile definitions, loaded through a private module directory or copy of the install, never by editing `upstream/`. Run inside a real darktable under a virtual display with a public test image opened in the darkroom, it walks every processing module's control tree and writes JSON.
   - Per module: operation name, display name, version, default group, flags (deprecated, hidden, supports blending), whether it draws on the image or takes mouse input, whether it has custom drawn widgets (graphs).
   - Per control, in visual order with its containers (sections, tabs, collapsible groups): type (slider, list, switch, button, colour picker button, custom), label, tooltip, the parameter it is bound to (introspection field name, found through the widget's field pointer), hard and soft range, default, step, digits, display factor and offset, format or unit string, list entries with their values.
   - Controls that are not bound to a parameter are listed too, marked as such, so that gaps are visible.
2. **Visibility rules**: for each list and switch, set every value, let the module update its panel, and record which controls become visible or hidden. Express the result as rules of the form "control X is visible when parameter P is one of {…}". Say clearly which cases this cannot capture (dependence on the image, on two parameters at once, on sliders).
3. **`schema/ui-schema.json`** for the pinned commit, committed, plus `tools/schema-extract.sh` (one command to regenerate) and `tools/schema-diff.py` (human-readable difference between two schema files: modules and controls added, removed, changed).
4. **Coverage report** in `docs/experiments/S5-control-schema.md`: for every module, bound controls found versus scalar parameters known to introspection; unbound controls; custom widgets. A table for these 29 priority modules in particular: color calibration, crop, orientation, rotate and perspective, lens correction, demosaic, white balance, tone curve, spektrafilm, AgX, rgb levels, tone equalizer, exposure, grain, contrast equalizer, blurs, diffuse or sharpen, retouch, chromatic aberrations, denoise (profiled), color zones, LUT 3D, rgb curve, color balance rgb, color equalizer, color balance, color harmonizer, color look up table, rgb primaries. For each: could a usable panel be generated from the schema alone, partly, or not, and what is missing.
5. A hand check of the visibility rules for at least five modules against the upstream source, with file and function names.
6. Verdict: go, go with conditions, or no-go for the generated-controls approach, with reasons. Report in `docs/experiments/S5-control-schema-REPORT.md`.

## Limits
- Do not modify `upstream/`. If the plugin approach cannot reach the widgets without a patch, stop and describe exactly what blocks it and what the smallest patch would be.
- No Android, no Kotlin, no app code. Translations are out of scope: record the untranslated strings and their gettext context.
- Branch `s5-control-schema`, do not merge.

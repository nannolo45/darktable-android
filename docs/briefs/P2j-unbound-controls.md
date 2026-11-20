# P2j: controls that desktop keeps outside a module's parameters

Owner, 20 Nov 2026: in color calibration, "hue" and "chroma" of a custom illuminant are greyed out on the phone; he
asked for every case of this kind to be found and dealt with. App and schema side only: **no change under `engine/`
or `upstream/`**. Branch `p2j-unbound-controls` from `main`. Report `docs/experiments/P2j-unbound-controls-REPORT.md`,
running log `docs/experiments/P2j-unbound-controls.md` (pushed at least every 30 minutes).

## The list
`python3 -I tools/schema-unbound-controls.py "scene-referred (sigmoid)" "modules: all"` gives 60 controls in 22
modules that the app draws disabled because the control schema has no parameter for them (copy of the output:
`docs/briefs/P2j-unbound-controls-list.txt`). The pattern is the one P2f solved for crop's aspect list
(`schema/overrides/crop.json`, `app/schema` `CropAspect`): on desktop the control is interface-only, and its
callback writes one or more parameters.

## Deliverables
1. **Classify all 60**, each with the upstream function that shows what the control does (file and function), in a
   table in the report:
   - **a. computed**: the control writes parameters by arithmetic (color calibration's hue and chroma → the
     illuminant's `x`, `y`; framing's aspect and positions; likely color harmonizer's hues, saturation curve's two
     sliders, tone equalizer's curve smoothing; check each).
   - **b. a list filled at run time whose choice is stored in a parameter** (spektrafilm's film stock, print paper,
     format and spectral upsampling; denoise (profiled)'s profile; lens correction's lists; input and output colour
     profiles; LUT 3D's file; watermark's file; white balance's settings and finetune; the interpolation method of
     the curve modules): say where the entries come from (the packaged data, a file list, the image, a database)
     and whether the app can read them without a new bridge call.
   - **c. needs a bridge call** that does not exist: describe the call for the next engine package; do not build it.
   - **d. belongs to a later tool**: pickers (area mode, the target sections, the chart controls), graph editors
     (scale for graph, color look up table's patch editor, color zones' edit by area), masks (mask preview and
     opacity helpers).
   - **e. not part of the edit** (a display helper that desktop stores in its configuration, not in the XMP).
2. **Build every a.** through `schema/overrides/` with upstream's arithmetic, both directions (parameter → control
   when the module is shown, control → parameter on change), enabled and hidden exactly when desktop enables and
   shows them. **Color calibration's hue and chroma first.**
3. **Build every b. that needs no new bridge call.** **Spektrafilm's four lists first** (the owner uses it; the data
   pack is in the APK since P2f), then denoise's profile, the colour profiles, lens correction.
4. **d. and e. stop looking broken**: a control of kind e. that changes nothing on a phone is not shown; a control
   of kind d. stays disabled and says in one short line under it what it waits for ("needs the colour picker",
   wording by you, plain), through the schema or an override, not by module names in screen code.
5. **The checker tells the truth afterwards**: `tools/schema-unbound-controls.py` lists only what is still open,
   with its class; its output for "modules: all" is kept under `docs/experiments/P2j-results/` and checked in CI so
   a schema update that adds a new interface-only control is noticed.
6. **Tests.** JVM: a round trip for every control built (control → parameters → control) against values computed
   from upstream's formulas, and the enable and visibility rules. Emulator: for color calibration (custom illuminant
   set by hue and chroma), spektrafilm (another film stock chosen) and one more of your choice, the app writes the
   XMP and darktable-cli's render of it equals the app's export within the usual bound. Both workflows green on the
   last code commit; APK from the usual release step; owner instructions in the report (what he can now change, and
   what still waits for which later package).

## Order and limits
Classification first, pushed as soon as it exists (the reviewer and the owner read it while you build). Then 2 and 3
in the order given. If time runs out, stop at a clean point and say exactly which controls are built.
If a control needs the darktable user manual's wording or behaviour, the manual is the reference (`CLAUDE.md` rule 8).

## Rules
`CLAUDE.md`. Nothing personal in the repository (no names of people, phone or camera models, names of layouts,
presets or styles, addresses, private links). SPDX line on new files. Do not weaken a test. Never push to `main`, no
pull requests. Report shortfalls first, plainly. Maven Central is flaky in CI: rerun before suspecting the change.
Another agent works on other branches at the same time: touch only what this brief needs, and add only your branch
to the two workflows' triggers.

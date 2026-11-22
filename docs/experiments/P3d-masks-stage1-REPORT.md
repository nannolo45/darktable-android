# P3d stage 1: masks and blending screens (report)

Branch `p3d-masks` from `main` `4c71dfb`. Brief: `docs/briefs/P3d-masks-stage1.md`. Running log:
`docs/experiments/P3d-masks-stage1-LOG.md`. Spec: `docs/design/ui-spec.md` section 8. Mockup:
`docs/design/mockups/P3d-Masks.html`.

**Engine changes: none.** `engine/`, `engine-jni` and `upstream/` are untouched. The stand-in engine (`FakeEngine`)
got one small change: `mask_attach` now adds ENABLED | FLEXI to the instance's mode flags (naming an unnamed
combination "other") as the bridge does, instead of replacing the mode with "flexi".

## 1. What the brief asked and what was delivered

| item | state |
|---|---|
| mask indicator in module headers (closed modules, active group, quick access panel), tap opens the masks tab | done |
| row `mask…` at the end of an open module with blending | done |
| masks tab: phone third bottom tab; tablet second tab beside the history in the left panel | done |
| module row and sheet of instances (those with a mask marked `· mask`) | done |
| modes off, uniform, drawn; parametric and drawn & parametric disabled with `parametric masks: coming next` | done |
| opacity (one history item per lifted finger), blend mode (sheet, desktop's modes per blend colour space) | done |
| drawn: line naming the group and its number of shapes; add buttons brush, circle, ellipse, gradient, path; use an existing shape | done |
| module's shapes with combine, invert, remove | **partly**: combine is one setting for all of a module's shapes (see 4) |
| show mask (engine mask display), invert mask | done |
| manager: groups and shapes, shapes not in a group, more sheet (rename, use in another module, duplicate, remove from this group, delete with one question, used by n modules), tap shows the shape on the photo | done (`remove from this group` only under a module's group, see 4) |
| drawing mode: tool panel replaces the bottom panel; brush, circle, ellipse, gradient, path with the brief's sliders and buttons; cancel / done | done |
| two fingers pan and zoom while drawing (the picker's one/two finger separation reused) | done |
| positions to the engine in image coordinates through coords_transform; strokes as plain points | done |
| one history item per finished shape or setting | **partly**: settings yes; a shape is two items on the real engine (see 4) |
| undo/redo | done (the tab re-reads after every change, undo and redo included) |
| read-only photos show masks, allow no change | done (JVM test) |
| XMP round trip and darktable-cli compare | done: emulator scenarios d1/d2 reopen the XMP; the workflow's compare step renders `p3d-circle.xmp` and `p3d-brush.xmp` with darktable-cli |
| editing a shape's geometry after `done` | not built (not cheap: needs handles per shape type on the photo and `mask_update`; the engine call exists) |
| mask refinement, raster masks, parametric | not in stage 1 |

## 2. How to reproduce

- JVM: `cd app && gradle :darkroom:testDebugUnitTest --tests '*P3dMasksTest*'` (CI; needs the Android SDK).
  Locally without the SDK: `gradle --offline :engine-api:test` (the stand-in's change).
- Screenshots: `:darkroom:testDebugUnitTest --tests '*MasksScreenshots*'` (CI, Robolectric native graphics), written
  to `build/outputs/screenshots`: `phone-p3d-01-masks-tab-{darktable,modern}`, `phone-p3d-02-drawing-brush-{darktable,modern}`,
  `phone-p3d-03-indicator-{darktable,modern}`, `phone-p3d-04-show-mask`, `tablet-p3d-01-masks-tab-darktable`.
- Emulator: `P3dScenarioTest` (added to `tools/app/emulator-p2b.sh`), results in `files/p2b-test/p3d-results.txt`;
  the `compare` job renders the two XMPs with darktable-cli (`p3d=` in its summary).
- Screen rules: `python3 tools/app/check-screen-rules.py` (0 violations).

## 3. Results

See the table at the end (CI run ids and counts).

Emulator (x86_64 emulator, API 33; run 45042969878, all 75 instrumented tests OK):

| scenario | unmasked vs masked preview (mean abs. diff, 0..255) | show mask: on the shape / away | reopened XMP vs before | app export vs darktable-cli of its XMP |
|---|---|---|---|---|
| d1 circle on exposure (+1.5 EV) | 54.5 | [255, 255, 0] / [141, 141, 141] | same shapes, picture diff 0.0 | PSNR 45.06 dB (bound 40) |
| d2 brush stroke by touch | 52.4 | [255, 255, 0] / [153, 153, 153] | same shapes, picture diff 0.0 | PSNR 45.05 dB |

The PSNR of about 45 dB is the same as the other app-XMP cases of the compare step (p3a 43.8, p3c 43.8 to 45.6): the
masks add no difference of their own. Earlier run 45033767124: the circle went to the engine at
`center [0.404, 0.453]`, `radius 0.149` (asked: 15 % of the picture's shorter side), the brush stroke of 17 touch
samples became 2 brush points after the engine's simplification, as desktop's brush tool does.

## 4. Upstream and engine behaviour worth knowing; where the app differs

1. **Combine mode.** At the pinned commit a module's mask is a flexi group; "combine" is the group's operator
   (engine-api.md 6.7), not a per-shape setting as on classic desktop masks. The tab shows one `combine shapes` row
   (union = maximum, intersection = minimum, difference, exclusion) for all of a module's shapes; per-shape combine
   would need a nested group per shape.
2. **History items per shape.** `dtb_mask_create` records a history item of darktable's `mask_manager` for a form no
   instance uses yet, then `dtb_mask_attach` records the instance's item: a finished shape is two history items and
   two undo steps on the real engine (emulator history: `..., mask_manager, exposure`); a brush with n strokes is 2n.
   The stand-in records one. One item would need a create-and-attach call (as the S3 prototype `dtb_mask_add_circle`
   does for circles) or an undo group in the bridge; not grown, per the brief.
3. **Mode after attaching.** `dtb_mask_attach` ORs ENABLED | FLEXI into the mode (desktop's `_blendop_mask_enable`);
   after desktop's drawn mode (ENABLED | MASK) blend_get names the result "other". The app reads drawn+flexi as the
   drawn mode (`Masks.modeName`).
4. **Sizes.** The tool's size and feather are fractions of the processed picture's shorter side, converted to the
   original image's shorter side through coords_transform. That darktable measures circle/ellipse/brush radii against
   the shorter side is an assumption from desktop's drawing code; the emulator's mask display shows whether the
   drawn size matches (the reviewer should look at the CI screenshots / the export).
5. **Angles** (ellipse and gradient rotation) are passed as set; a rotation of the picture by modules that move pixels
   is not taken into account for angles (positions are).
6. **Remove from this group** exists only for a module's own group (`dtb_mask_detach`); there is no call to remove a
   member from a group no instance owns, nor to move a member inside a group.
7. The darktable manual pages could not be read from the build workspace (the web fetch needed an approval nobody
   gave). Behaviour follows engine-api.md, the mockup and the brief.

## 5. Open questions for the reviewer

- Should a finished shape be one history item (needs a small bridge call: create + attach in one item)?
- Per-shape combine (nested groups) or the module-wide operator as now?
- Editing a shape after `done` (move, resize on the photo through `mask_update`) for stage 2?
- Stage 2 needs from the engine: nothing new for parametric masks (blend_set paths exist); for refinement the blend
  fields exist; one-item shape creation and member reordering are the gaps.

## CI

Last code commit `896f89f8`:

| workflow | run | result |
|---|---|---|
| p2a-app | 45042969844 | success (P3dMasksTest 8/8, PhoneMasksScreenshots 7/7, TabletMasksScreenshots 1/1, 0 failures in all JVM tests) |
| p2b-real-engine | 45042969878 | success: apk (JVM tests, screenshots), emulator OK (75 tests, P3dScenarioTest 2/2), compare (p3d circle and brush ok) |

Earlier failures and their causes are in the running log. Two Robolectric/Maven fetch failures were infra (reruns).


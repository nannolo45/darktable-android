# Brief P3d stage 2b: parametric masks and mask refinement (screens)

Branch `p3d-masks-parametric` from `main` (`35dd523`). Follows stage 1 (`docs/briefs/P3d-masks-stage1.md`). Engine calls:
`docs/design/engine-api.md` 6.3 (colour pickers) and 6.6 (blending: `blend_get` / `blend_set`, the `parametric`
channels, `feathering_*`, `blur_radius`, `contrast`, `brightness`, `details`). Manual notes for masks (the manual is
the reference): `docs/design/manual-notes-masks.md` (copy it there from the path the reviewer gives you).

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal). Never push to `main`, no pull requests; push after
every deliverable. **No engine change**: `engine/`, `engine-jni`, `upstream/` and `app/lpl` stay untouched. If the
engine lacks something, build the screen without it and report it. Screen code: tokens and icons only, both skins
(new tokens in `tokens.json`, overrides in `modern-tokens.json` where needed; icons in `ui-icons`).
Another agent works in parallel on branch `p3d-masks-stage2a` (engine calls and the drawn-mask tree in `Masks.kt` /
`MasksPanel.kt`). To keep merges clean: put your screens in new files (`ParametricMask.kt`, `MaskRefinement.kt`, a
reusable `RangeSlider4` component); in `MasksPanel.kt` and `Masks.kt` only enable the `parametric` mode and add the
calls that show your sections. Do not rename or move existing composables.

## Deliverables (masks tab)
1. Mode `parametric` enabled (classic parametric mode, `masks: "parametric"` through `blend_set`). `drawn & parametric`
   stays disabled with its note (the other package finds out what the engine needs for it).
2. Parametric section, from `blend_get(...).parametric`, for the module's blend colour space: channel buttons as the
   manual lists them per space; per active channel a row with: the input range slider with four markers (filled inner
   = full opacity, open outer = zero; order fixed, may touch, never cross; double tap resets to the whole range;
   whole-range warning icon), the output range (hidden behind a toggle, as on desktop), invert, boost factor where the
   channel has one, a picker (drag an area on the photo to set the range: reuse P3c's shared picker tool and the
   engine's picker call), remove. Dragging a marker is one history item per lifted finger (as other sliders).
   Changing the blend colour space asks first, because it resets the parametric channels.
3. `show mask` works in parametric mode as in drawn (the engine's mask display).
4. Refinement section (collapsed by default) for every mode except off and uniform: details threshold, feathering
   guide (four choices), feathering radius, blurring radius, mask brightness, mask contrast, with the engine's
   limits and desktop's defaults; reset. The details threshold is disabled with a note for non-RAW photos.
5. Read-only photos: shown, no change.

## Tests
JVM tests against the stand-in engine (extend it minimally): marker order kept, reset, colour space change asks and
resets, one history item per drag. Screenshots of the parametric section and refinement in both skins, phone, plus
one tablet shot. Emulator with the real engine: a luminance range on exposure changes the picture where expected and
not elsewhere; show mask renders; the XMP reopens with the same ranges; darktable-cli renders the XMP as the bridge
does (one case in the compare step). Do not weaken existing tests.

## Docs
This brief into `docs/briefs/`; `ui-spec.md` section 8 (parametric and refinement subsections); report
`docs/experiments/P3d-masks-parametric-REPORT.md` in `CLAUDE.md`'s form with a short running log.

## CI
Add the branch to the triggers of `p2a-app.yml` and `p2b-real-engine.yml`. Stop when both are green on the last code
commit including the emulator job, or after three failed fix attempts on one problem; then report plainly. Maven
Central / mirror "could not find" failures are infra: rerun the failed job once.

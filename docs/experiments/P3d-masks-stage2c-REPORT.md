# P3d stage 2c: group choice, editing a shape after `done`, drawn & parametric (report)

Branch `p3d-masks-stage2c` from main 15719b7. No engine change (`engine/`, `engine-jni`, `upstream/`, `app/lpl` untouched).

## 1. Delivered, item by item
1. **Choose the group a new shape goes into: done.** Tapping a group row chooses it (`MasksState.group`, sub-line `new shapes go here`); tapping it again or `use the module's group` goes back; the add buttons carry the group in `DrawTool.group` into `mask_add`. Line `new shapes go into: <name>` above the buttons. The chosen group opens after the add. Dropped when another module is chosen.
2. **Edit after `done`: done.** A shape row tap or `edit` in `more` starts the tool on the form (`Masks.startEdit`). Circle, ellipse, gradient: drag moves (relative to the finger), sliders as when drawing; path: drag a point; brush: moves all strokes, no sliders. `used by n modules` in the panel. `done` = one `mask_update` with the whole geometry (the engine's update replaces it, so path borders, brush density, gradient steepness are kept by merging); `cancel` writes nothing. Groups and parametric elements are not edited by the tool.
3. **Drawn & parametric: done, with one limit.** The button is enabled (`MaskMode.BOTH.available`); the engine has no such mode, so it asks for `drawn` (already drawn: a note). It shows as chosen while the tree has parametric elements. `add parametric…` in the tree opens the channel buttons and adds the element to the chosen group and selects it. Elements at any depth are read (`ParametricState.all`). Controls (`ChannelRow`, `RangeSlider4`, picker, remove) open under the row for elements of nested groups. Limit: elements of the module's own group keep their rows in 2b's parametric section, not in the tree row, because the engine reads any mask with elements as `flexi` and the tab cannot tell parametric mode from the tree (first CI run showed it: hiding the section broke the 2b screenshot test). `show mask` works.
Files: `Masks.kt`, `ParametricMasks.kt`, `ui/MaskTree.kt`, `ui/MasksPanel.kt`, `ui/ParametricMask.kt` (`ChannelRow`, `ChannelButton` internal), `ui-spec.md` 8.3.

## 2. Reproduce
`python3 tools/app/check-screen-rules.py` (59 files, 0 violations). JVM `P3dEditShapesTest` (selected group receives the shape; edit + cancel no history; edit + done one item and one undo step; brush moves, path point; used by 2 modules; parametric element in a nested group). Screenshots `P3dEditShapesScreenshotTest` (phone, darktable and modern: chosen group with a nested element's controls; circle edit used by two modules). Emulator `P3dEditScenarioTest`; compare case `p3d-dp` (workflow loop). Existing tests unchanged (the BOTH assertion of `P3dMasksTest` still holds: a note, no history item).

## 3. Results (emulator x86_64, real engine; ci-logs `runs/45096275323-a1/`)
- f1 circle moved after `done`: picture changes (mean difference 5.01), one history item, one undo step: geometry equal, picture difference 0.0.
- f2 shape added with a nested group chosen: in the nested group (`[1795399585, 1795399590]`), not in the module's group.
- f3 circle (operator minimum) with a luminance range: show-mask yellow share 0.389 with the circle alone, 0.305 with the range; picture change 5.63; XMP reopens with the same range (diff 0.0).
- compare `p3d mask dp`: psnr 44.28 against darktable-cli (circle 45.06, brush 45.05, tree 45.26, parametric 43.28).

## 4. CI log
- 45095305608 (p2a, ec1303f4): failed, `PhoneParametricScreenshots` x3 (section hidden; see item 3). Fixed in 7b4e1a21.
- 45096275420 (p2a) and 45096275323 (p2b: apk, emulator, compare) on 7b4e1a21: green.

## 5. Open questions
- Should root-level parametric rows in the tree also open their controls (duplicating the section)? Left single-place.
- A path's control points of the dragged point move with it; the others keep theirs (no re-smoothing).
- Brush edit moves points one by one through coords_transform; under crop/rotate a pure translation is only approximate.

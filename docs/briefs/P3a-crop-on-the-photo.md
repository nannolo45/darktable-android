# P3a: crop on the photo

First package of Phase 3 (PLAN.md section 6, item 2), moved to the front by the owner (20 Nov 2026): crop works
through sliders and the aspect list, but the frame cannot be dragged on the photo. Branch `p3a-crop-tool` from `main`.
Report `docs/experiments/P3a-crop-tool-REPORT.md`, running log `docs/experiments/P3a-crop-tool.md` (pushed at least
every 30 minutes). **No change under `engine/` or `upstream/`** (another agent changes `engine/` at the same time).

## Reference
The darktable user manual's page for crop, and `src/iop/crop.c` at the pinned commit (the on-image handling:
`mouse_moved`, `button_pressed`, `_aspect_apply`, the grab constants, how the frame is clamped and how the ratio is
kept). P2f built the arithmetic for the aspect list (`app/schema` `CropAspect`, `schema/overrides/crop.json`); reuse it.
Agreed with the owner in the mockups: draggable corners **and a movable frame**.

## Deliverables
1. **The crop tool on the photo.** With crop's panel open (or a "crop" button there, your proposal with reasons): the
   **whole uncropped picture** is shown, the area outside the frame dimmed, the frame with handles on the four corners
   and four edges, a rule-of-thirds grid inside. Dragging a corner or an edge resizes; **dragging inside the frame
   moves the whole frame**; the frame never leaves the picture; the chosen aspect ratio is kept exactly as desktop
   keeps it (and "freehand" lets every side move alone). Touch targets of at least the size the interface
   specification asks for; a handle near the screen's edge must stay reachable. Pinch and pan of the photo keep
   working while the tool is open (a drag that starts on a handle or inside the frame belongs to the frame).
2. **Leaving the tool.** As desktop: the crop applies when the tool is left (panel closed, another module opened,
   "done"), the picture is then shown cropped. One history item for the whole session with the tool (none when nothing
   changed); undo brings back the frame before. The margins' sliders and the aspect list of the panel show the frame
   live and can still be used.
3. **How the uncropped picture is obtained** is the hard part: desktop bypasses crop in its pipes while the module
   has the focus; the bridge has no such notion. Find a way with the existing calls (for instance working on a
   full-frame crop in the engine while the tool is open and writing the frame when it is left) that guarantees:
   the photo's XMP and what LightPhotoLibrary receives **never** contain a temporary state (think of `writeIfOpen`
   during a sync job, the automatic sync after "done", the app being killed with the tool open, "before", history
   navigation, the read-only case); the history has no extra items. Say first in the report which way you chose and
   why it is safe, with a test for each case. **If no safe way exists without a bridge call, stop on this item,
   describe the call (a render option that bypasses one instance), and build 1 and 2 against the fake engine and the
   cropped picture's coordinates so the rest is ready.**
4. **Rotation in the same tool** (second priority): the rotation of "rotate and perspective" as a dial or slider
   under the photo while the crop tool is open, and "straighten" by drawing a line along a horizon or a wall, with
   the angle computed as desktop computes it (cite the function). Only if 1 to 3 are done and green; otherwise say so.
5. **Double tap on a slider's bar resets it** to its default (owner, 20 Nov: yes), as a double click does on
   desktop; exact entry stays on long press of the bar and on double tap of nothing else; double tap on the name
   keeps resetting. Update the interface specification's line on this.
6. **Tests.** JVM: the frame arithmetic against values computed from upstream's rules (every handle, every aspect
   entry, flipped, at the picture's borders, portrait and landscape, a rotated photo); the cases of 3; screenshot
   tests of the tool on phone and tablet (frame, dimming, grid, handles). Emulator: drag a corner and the frame with
   real touch events, leave the tool, write the XMP; darktable-cli's render of that XMP has the size the app shows
   and equals the app's export within the usual bound. Both workflows green on the last code commit; APK from the
   usual release step; owner instructions in the report.

## Rules
`CLAUDE.md`. Colours, sizes and icons through tokens and the icon registry (new tokens for the dimming, the frame
and the handles; no literals in screen code). Nothing personal in the repository. SPDX line on new files. Do not
weaken a test. Never push to `main`, no pull requests. Report shortfalls first, plainly. The build workspace has no
Android SDK: darkroom and app tests run only in CI. Maven Central is flaky in CI: rerun before suspecting the change.

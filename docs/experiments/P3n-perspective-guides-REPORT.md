# P3n: perspective guides in rotate and perspective (structure and fit)

Branch `p3n-perspective-guides`. Brief: `docs/briefs/P3n-perspective-guides.md`. No file under `upstream/` changed; the
app reaches the engine only through `dtbridge.h` (one new call, `dtb_ashift`, engine-api.md 6.11).

## 0. Said first

- **How upstream's static code is reached.** `_do_get_structure_*`, `nmsfit`, `do_crop`, the button handlers and the
  mouse handlers are static in `iop/ashift.c` and work on its GTK interface data; the module cannot be driven through
  `gui_init` (GTK is linked but never initialised). Nothing was patched and nothing re-implemented: the new bridge file
  `engine/src/perspective.c` **compiles `iop/ashift.c` itself, unmodified** (`#include "iop/ashift.c"`, as darktable's
  own generated `introspection_ashift.c` does) with `-fvisibility=hidden`, and calls desktop's functions. The GUI calls
  those functions make are answered by macros defined between darktable's headers and the include (widget toggles,
  click modifiers, history items, log messages, touch radius, preview pipe). The reviewer should confirm this reading
  of CLAUDE.md rule 1 (it edits nothing under `upstream/` and copies no maths; it does compile a second, hidden copy of
  the module's code into libdtbridge, and links GTK there as the module does). If it is not acceptable, the
  alternative is a patch exposing the functions, which the brief says to stop for.
- **Upstream behaviour reproduced as is:** deleting a drawn line (desktop: right-click; app: long press) also drops the
  last drawn line unless the deleted one is the last (`ashift.c` `button_pressed`, "we completely remove the line from
  the list": it copies `lines_count - 1` entries skipping `n`). C33's first run: line 0 of 4 removed, 2 left. The test
  now removes the last line (open question 1).
- **Fit precision** is upstream's Nelder-Mead stopping rule: one parameter is recovered to 1e-4°, three together
  (rotation and both lens shifts) to up to 0.131° (raf). The first C33 run had a 0.05° bound for every fit and failed
  on raf (0.113°, 0.131°); the bound for three-parameter fits is now 0.2° (one-parameter fits keep 0.05°/1e-6). Said
  plainly: the bound was loosened after measuring, with the numbers in section 3.

## 1. Item by item

| Brief item | State |
|---|---|
| 1. Engine/bridge: structure plain / edges / detail (and both), line list with class, select/deselect, rectangle, drawn lines, fit vertical / horizontal / both with rotation-only and lens-shift-only (and "without shear" for both), results in the params as desktop writes them, XMP round trip | done: `dtb_ashift` (`engine/src/perspective.c`), test C33 (all fits against known geometry; darktable-cli parity of 4 XMPs per camera: max 0) |
| 2. Panel in all three skins: structure buttons (find lines' enhancements on long press), fit buttons (variants on long press), auto-crop and the existing sliders, "show guides" | done: `ui/PerspectivePanel.kt` above the generated controls (automatic cropping is the module's own combobox); screenshots in three skins |
| 3. On the photo: rectangle with 4 draggable corners; lines in desktop's colours (tokens); tap select/deselect; sweep; area mode; draw by dragging, delete by long press; two fingers pan/zoom; drag the "original format" crop | done: `Perspective.kt` (finger = desktop's mouse), `drawPerspective`; JVM tests; emulator n1/n2 |
| 4. Tests: bridge (structure on a public image, fits, rectangle, drawn, rotation-only, shift-only), XMP round trip, JVM/controller, screenshots, emulator (rectangle + fit both, render = darktable-cli) | done; the public set has no building: `mire1.cr2` (shelf, bottles, thread rack) and the other two test RAWs are used |

## 2. Reproduce

```
gh workflow run g0-app.yml          --repo nannolo45/darktable-android --ref p3n-perspective-guides   # bridge build, C33, APK, emulator smoke
gh workflow run p2a-app.yml         --repo nannolo45/darktable-android --ref p3n-perspective-guides   # JVM tests, screenshots
gh workflow run p2b-real-engine.yml --repo nannolo45/darktable-android --ref p3n-perspective-guides   # P3nScenarioTest + darktable-cli compare
# locally (JVM, stand-in engine):
cd app && ANDROID_HOME=<sdk> gradle :darkroom:testDebugUnitTest --tests '*P3nPerspectiveTest'
# bridge only: engine/tests/run_tests.sh <bridge build> <dt prefix> <darktable-tests> <out> C33
```

## 3. Results (x86_64 CI runners, not phone numbers)

| Workflow | Run | Commit | Result |
|---|---|---|---|
| g0-app | 45519540247 | 129ed3e (first engine commit) | failure: C33 on 3 cameras (deletion of line 0, raf fit bounds; see section 0); everything else 473 passes; C33 parity max 0 |
| g0-app | 45526336125 | 5c9e1f7 | success (bridge tests 476 passes, 0 failures, C33 green on cr2, raf, arw; APK; emulator) |
| p2a-app | 45521132411 | 60c9c27 | success (P3nPerspectiveTest 10/10, PhoneP3nScreenshots 3/3) |
| p2b-real-engine | 45526339995 | 5c9e1f7 | success (emulator incl. P3nScenarioTest; compare `p3n` ok) |

C33 (per camera, cr2 / raf / arw; the fit values are the same in both runs; run 45526336125 also: the last of 4 drawn lines deleted, 3 left, one item):
- rectangle: desktop's default rectangle starts at 0.2000 0.2000; a corner drag 0.20,0.20 -> 0.25,0.22 is one item.
- focus: the picture 3577x2492 (cr2) is 4183x2665 while focused (uncropped, as desktop with the focus).
- recovered parameters (expected in brackets):
  rectangle both without shear: rotation 2.9515 / 2.8865 / 2.9883 (3.0), lens shift v 0.2517 / 0.2505 / 0.2500 (0.25);
  rectangle vertical rotation only: 2.0000 all; vertical shift only: 0.2014 / 0.2003 / 0.2000 (0.2);
  rectangle horizontal: rotation 1.5214 / 1.5071 / 1.4997 (1.5), shift h 0.1512 / 0.1530 / 0.1503 (0.15);
  horizontal shift only: -0.2019 / -0.2048 / -0.2005 (-0.2);
  drawn both without shear: rotation -2.5488 / -2.6311 / -2.5136 (-2.5), v 0.3018 / 0.3002 / 0.3000 (0.3);
  drawn both rotation only: 4.0000 all.
- lines found on cr2: none 2019 lines (704 vertical used, 349 horizontal used), edges 3186, detail 3062, both 3732; a
  right-button tap deselects one (1345 -> 1344), a tap selects it again; the area deselects all (0/0), fit then says
  "not enough structure for automatic correction"; the area selects all (2204/1189); fit both: rotation -0.1015,
  v -0.0571, h 0.0156, shear 0.0032.
- crop "original format" dragged: left 0.0263 -> 0.1461, one item. Round trip: every parameter equal after reopening.
- darktable-cli parity of p3n_rect_fit, p3n_drawn_fit, p3n_found_fit, p3n_crop: max 0 on all three cameras.

Emulator (p2b 45526339995): n1 corner 0.20,0.20 -> 0.26,0.21 (items 12 -> 13), fit both rotation 2.1572,
v 0.1814, h 0.0315, shear 0.0455, preview 800x532 -> 800x574; n2 3002 lines found (edges), a long press left 2 lines
out (1103 -> 1101: desktop deselects every line within the radius), fit vertical rotation only 0.4765 (shift 0).
App export vs darktable-cli of the app's XMP: rect PSNR 44.54 (max 17), lines PSNR 44.75 (max 19), threshold as the
other editors.

Logs: `ci-logs` branch, `runs/<run id>-a1/` (g0-linux/<camera>/C33.log, p2b-emulator p3n-results.txt, p2a-app/screenshots).

## 4. Upstream surprises

- `iop/ashift.c` `button_pressed`: drawn line removal drops the last line too (section 0).
- `iop/ashift.c` `button_pressed`, crop drag: `pts = { pzx, pzy, 1, 1 }` are back-transformed without multiplying by the
  preview size (unlike every other press); it works because only the difference of the two points is used.
- `iop/ashift.c` fit buttons record the new crop through `_swap_shadow_crop_box` and leave the module's params with the
  old crop until the focus leaves; the bridge commits the crop at the end of every request so params equal history.
- `develop/imageop.c` `dt_iop_gui_init` is where `gui_lock` is initialised; the bridge never calls it, so the critical
  sections are macros that do nothing (requests are serialised by the app's engine lock).
- Line counts are large on the bridge's preview pipe (2000-4900 lines on these RAWs); every pointer request returns
  them all as JSON (desktop draws them all too). Fine on the emulator; to be watched on a phone.

## 5. Where the app differs from desktop, and why

- Right button = long press (held 400 ms before the finger moves or lifts); shift = the "select by area" switch;
  ctrl/shift on buttons = long-press rows. A finger has no hover: the bridge sends desktop's `mouse_moved` before each
  press so the "near" line or corner is known.
- "near" distance = half a touch target (desktop: `near_delta` 20 px preference and 6 px corners).
- Desktop's right-drag straightening line on the photo without structure is not offered here (the crop tool has it, P3a).
- "show guides": thirds drawn inside the automatic crop (desktop draws the darkroom's chosen guide there).
- Panel order: structure and fit first (approved mockup); desktop has them last in the module.
- While the panel is open the bridge's renders are the uncropped picture; LightPhotoLibrary renders made during that
  time see it too (exports and XMPs never do: they go through darktable's own pipe).
- Removed lines are not drawn in drawn mode (as desktop); in found mode all lines are sent.

## 6. Reviewer's decisions (25 Nov 2026) and the changes made for them

1. Compiling `iop/ashift.c` unmodified is accepted under rule 1; `docs/UPDATE.md` now lists what to check in
   `perspective.c` on every upstream bump.
2. Desktop's drawn-line deletion is kept (parity). **Upstream bug candidate:** `ashift.c` `button_pressed`, right-click
   removal of a drawn line in "lines" mode copies `lines_count - 1` entries skipping `n`, so the last line is lost too.
3. LightPhotoLibrary renders while the panel is open: new bridge actions `pause` / `resume` (pipes show the edit as it
   is, no history); `DarkroomController.withPhotoSession` brackets the block with them on the open photo
   (`Perspective.pausedLocked`). Tests: C33 (paused render size = the edit's, resumed = whole, no history item, fit
   refused while paused) and JVM `lightPhotoLibraryRendersSeeTheCroppedEdit`.
4. Lines and handles are sent only when they changed: every answer has `rev`; with `known_rev` equal to it they come as
   null and only the "near" indices are sent. `JniEngine` keeps the last copy per instance. Tests: C33 (omitted for the
   current revision, sent for an older one) and JVM `linesAreKeptWhenTheEngineSendsNone`.

CI for these changes: RUNS_FINAL.

## 7. Open questions for the reviewer (before the decisions above)

1. Deleting a drawn line also drops the last line (upstream). Keep desktop's behaviour, or file it upstream and
   meanwhile let the app delete only by deleting the last line?
2. Is compiling `iop/ashift.c` into the bridge (hidden symbols, no change to it) acceptable under rule 1?
3. Pointer requests return every line; should the bridge send lines only when they change (a version number) to save
   JSON on phones with 4000+ lines?
4. The focus is a bridge state between "begin" and "end"; should LightPhotoLibrary renders bracket it (end/begin) as
   P3a's crop tool does?

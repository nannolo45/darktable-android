# P3a: crop on the photo — running log

Brief: `docs/briefs/P3a-crop-on-the-photo.md`. Branch `p3a-crop-tool` from `main` at 5b43bc8.
Report: `docs/experiments/P3a-crop-tool-REPORT.md`.

## 20 Nov 2026, 10:30 UTC — deliverable 3 decided first: how the uncropped picture is obtained

### What desktop does
`src/iop/crop.c` at the pin (04477d3): `commit_params` gives the full and preview pipes a full frame
(`d->cx = d->cy = 0, d->cw = d->ch = 1`) while `dt_iop_has_focus(self)`; the parameters are not touched. The
frame lives in the module's interface data (`g->clip_x/y/w/h`) while the module has the focus; mouse moves change
only that (`mouse_moved`: "only update the sliders, not the params"), and `_commit_box` writes the parameters
when the focus goes (`gui_focus(in = FALSE)`), adding a history item only when the frame changed by more than
1e-6 (or on a slider change, `gui_changed`).

### What the bridge offers (engine-api.md, `engine/src/history.c`)
No render option that bypasses one instance. Every mutating call records an undo state first
(`dtb__change_begin` → `_undo_record`: a whole-history snapshot, redo cleared); `undo()` restores the snapshot
exactly (history, items above the end, parameters) and pushes the state it left onto the redo queue.
`history_goto` is navigation (no undo state). The engine never writes an XMP by itself; only `xmp_write` does.
All engine work of the app runs under the darkroom controller's one lock (`fg` for interactive work, `bg` for
background work such as LightPhotoLibrary's preview); `writeIfOpen` during a sync job is `saveIfOpen` → `fg`.

### Choice: the frame lives in the app while the tool is open; the engine is changed only inside one locked bracket
1. **While the tool is open the edit in the engine is never changed for the tool.** The frame (and the ratio,
   when the aspect list or the flip button is used) is kept in the controller (`CropSession`), as desktop keeps
   it in `g->clip_*`. The margins' sliders and the aspect list of the panel read and write that frame.
2. **The uncropped picture is rendered once when the tool opens**, in one locked section (`fg`, which is the
   same lock every `xmp_write`, every LightPhotoLibrary session and every render takes):
   `set_enabled(crop, off)` → `render_size(1)` + `render_preview` → `undo()`, the undo run as non-cancellable
   work in a `finally`. Nothing else can run on the engine between the switch-off and the undo, so no
   `xmp_write`, no LightPhotoLibrary preview and no other render ever sees the switched-off state. While only
   the frame changes, the uncropped picture stays valid (crop's input does not depend on crop), so no second
   bracket is needed; after any other change while the tool is open the bracket runs again.
   When crop is off already, no bracket is needed at all (the current render is the uncropped picture).
3. **Leaving the tool** (panel closed, another module opened, "done", another photo, any other edit, history
   navigation, undo/redo) writes the frame with one `params_set` (one history item, sealed), or nothing when
   the frame and ratio did not change (desktop's 1e-6 rule). The ratio arithmetic and `_commit_box`'s rounding
   to whole pixels of the ratio are applied as desktop does.
4. **Guard:** a flag set before the switch-off and cleared only after the undo succeeded; while it is set the
   controller refuses every `xmp_write` and every session for LightPhotoLibrary (the "cannot happen" case of an
   undo that fails: the edit is then not written at all rather than written wrong, and the user is told).

### Why each case of the brief is safe
- `writeIfOpen` during a sync job: takes the lock; the engine holds the last committed edit (the frame before
  the tool); the frame in the tool is not part of the edit until the tool is left — as on desktop, where the
  parameters change only on focus-out.
- automatic sync after "done": "done" leaves the tool first (frame written, one item), then saves; the sync
  sends that XMP.
- app killed with the tool open: the engine never writes an XMP itself; the last XMP is the last committed edit.
  The frame in the tool is lost, as on desktop when it quits with crop focused.
- "before": `history_goto(0)` / render / `history_goto(end)` under the lock, unchanged; the tool's frame is
  hidden while "before" shows.
- history navigation, undo, redo while the tool is open: the tool is left first (frame committed), then the
  action runs.
- read-only: the tool does not open (no editing control does); nothing is ever switched.
- history has no extra items: the bracket's switch-off is undone by `undo()`, which restores the whole history
  snapshot (also items above the end); `history()` before and after the bracket are compared in the tests.

### Side effects of the bracket (reported, not hidden)
- The bracket's switch-off clears the redo queue (any change does) and `undo()` puts the switched-off state on
  the redo queue. The controller therefore hides redo and refuses it until a real change clears that entry
  (`RedoGuard`, tested). Net effect for the user: opening the tool on a photo whose crop is on ends the redo
  steps, as an edit would. Desktop keeps them.
- At the undo limit (bridge 100 states) the oldest undo state is dropped by the bracket.
- The bridge marks the image changed (tag `darktable|changed`, change timestamp) on the switch-off and on the
  undo; the photo had crop on, so it was edited already. The emulator test compares the XMP written before
  and after a tool session without a change.

If any of these turn out unacceptable, the clean fix is the bridge call the brief describes (a render option
that bypasses one instance); it would replace step 2 only.

## 20 Nov 2026, 10:49 UTC — first full implementation pushed (ab3cb29)

- `app/schema` `CropTool`: crop.c's `_aspect_apply`, `_gui_get_grab`, `button_pressed` + `mouse_moved` (drag),
  `_commit_box` (ratio rounding), in float where crop.c uses float. `tools/p3a-crop-reference.py` copies those
  functions verbatim from crop.c at the pin, compiles them with stubs and prints reference values
  (`app/schema/src/test/resources/p3a-crop-reference.json`: 1815 `_aspect_apply` cases, 162 drags of 4 moves,
  165 commits); `CropToolTest` passes locally (x86_64 workspace) against all of them. p2a-app checks the file
  is what the script makes from the pinned crop.c.
- `app/darkroom`: `CropToolState`, `RedoGuard`; controller: tool opens when crop's panel is expanded
  (`syncCropTool`), bracket in `openCropLocked`, commit in `commitCropLocked`, leave on every other change,
  photo switch, "done"/"back", export; write guard `cropBracketOpen`. Photo view: uncropped picture, dimming,
  frame, thirds grid, 4 corner + 4 side handles (tokens), gestures; the info note under the photo shows the
  cropped size. Slider bar double tap resets (deliverable 5), ui-spec updated (section 3 and new section 6).
- Tests: `P3aCropToolTest` (darkroom JVM, stand-in engine), `P3aCropScreenshotTest` (phone and tablet, with
  Compose touch input), `P3aScenarioTest` (emulator, touches injected through UiAutomation, XMP and export for
  the darktable-cli comparison in the p2b compare job).

## 20 Nov 2026, 11:05 UTC — first CI results; deliverable 4 prepared

- Run p2a-app 44722431691 on ab3cb29: green, 230 JVM tests (16 P3aCropToolTest, 8 CropToolTest, 4 crop
  screenshots with Compose touch input). The screenshots showed the size note over the photo hiding the bottom
  handles: moved to the line under the photo (phone) and the top bar (tablet), as mockup B3-Crop has it (5cb08fe).
- Run p2b-real-engine 44722431616: the emulator test did not compile (an extension not imported); fixed in 5cb08fe,
  run 44723479124 in progress.
- Deliverable 4 prepared locally (not pushed while the emulator run is going): rotate and perspective's rotation as
  a slider under the photo while the tool is open (the picture turns on screen while the finger moves; the rotation
  is written at the lift, one history item, then the uncropped picture is rendered again), "straighten" (a line
  drawn on the photo; angle by ashift.c `_calculate_straightening`, ported and checked against 156 reference values
  from the verbatim function). Sign: desktop sets the slider (factor -1) to `slider - angle`, so the parameter
  becomes `rotation + angle`; a horizon falling 2° to the right gives -2°.

## 20 Nov 2026, 11:15 UTC — emulator run 44723479124: real touches work; the bracket's undo drops `history_auto_hash`

- 47 of 49 emulator tests passed; the two P3a cases failed on the XMP comparison. The injected touches (corner,
  then the whole frame) worked; the history and the parameters were unchanged by the bracket.
- The XMP written while the tool was open had lost `darktable:history_auto_hash` (current hash unchanged).
  Cause: upstream `dt_history_snapshot_restore` (src/common/history_snapshot.c `_history_snapshot_restore`) calls
  `dt_history_delete_on_image_ext`, which removes the image's `history_hash` row, and writes back only
  `DT_HISTORY_HASH_CURRENT`; the bridge's `undo()` restores through it (engine/src/history.c `_snapshot_restore`).
  So every undo through the bridge loses the basic and auto hashes, a user's undo as well as the bracket's.
  Desktop uses them only for the history status (basic / auto-applied / altered: `dt_history_hash_get_status`);
  with crop on, the status is "altered" with or without the auto hash. Not fixable without an engine change
  (the bridge would read the hash row before the restore and write basic and auto back after it). Reported first
  in the report; the emulator test now compares the edit without those two attributes and records exactly which
  attributes differ.

## 20 Nov 2026, 11:28 UTC — run 44725669469: 48 of 50 emulator tests; the two failures were the test's normalizer

- `p3a-results.txt` (ci-logs `runs/44725669469-a1/p2b-emulator/p2b-test/`): uncropped 3908x2602, cropped
  2602x2602 (square); corner drag by injected touches w 0.666 -> 0.499, h 1.000 -> 0.750 (square kept); move
  x 0.334 -> 0.414, y 0 -> 0.040. The XMP written by writeIfOpen while the tool was open, and the XMP after the
  tool was opened and closed without a change, differ from the XMP before only in `darktable:change_timestamp`
  and `darktable:history_auto_hash` (dropped). c3 straighten by touch: rotation 0 -> -2.72° for a line whose
  angle (desktop's function on the line asked for) is -2.83°; one history item (ashift).
- The two failures: my normalizer removed one whitespace character before a dropped attribute instead of all
  of it. Fixed in a4efb01.

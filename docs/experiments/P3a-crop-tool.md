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

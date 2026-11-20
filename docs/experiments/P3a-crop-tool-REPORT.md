# P3a: crop on the photo — report (pull request description)

Branch `p3a-crop-tool` from `main` at 5b43bc8. Brief: `docs/briefs/P3a-crop-on-the-photo.md`. Running log:
`docs/experiments/P3a-crop-tool.md`. Last code commit **321fde8**; runs on it: p2a-app **44731123711** (green on attempt 2: attempt 1 failed in Maven
Central resolution, "could not find kotlin-stdlib 2.0.21"), p2b-real-engine **44731123836** (green: JVM, emulator
50/50, darktable-cli comparison). APK release tag **dt-android-321fde880e06** (interface preview:
app-preview-321fde880e06).

No change under `engine/` or `upstream/`, none under `app/engine-api` or `app/engine-jni`, no new dependency.
No existing test was changed, weakened, skipped or deleted.

## 1. Failures and limits first

1. **The bracket that gives the uncropped picture leaves two traces in the XMP's metadata (not in the edit).**
   When the tool opens on a photo whose crop is on, the bridge's `set_enabled` and `undo` mark the image changed
   (`darktable:change_timestamp`) and the undo drops `darktable:history_auto_hash` (and `history_basic_hash` when
   present). Measured on the real engine (emulator, `p3a-results.txt`): the XMP written while the tool is open, and
   the XMP after the tool was opened and closed without a change, differ from the XMP before **only** in those two
   attributes; history, history end, every parameter and `history_current_hash` are identical. Consequences:
   - opening and closing the tool without a change makes the XMP's bytes differ, so LightPhotoLibrary's sync sees a
     changed XMP and sends it (with the same history). Desktop does not touch the XMP in that case.
   - darktable uses the auto hash only for the history status shown in the lighttable (basic / auto-applied /
     altered, `dt_history_hash_get_status`); with crop on, the photo is "altered" with or without it.
   Cause of the hash loss: upstream `dt_history_snapshot_restore` (src/common/history_snapshot.c) deletes the
   image's `history_hash` row and writes back only the current hash; the bridge's `undo()` restores through it
   (engine/src/history.c `_snapshot_restore`). **Every undo through the bridge does this, a user's undo too**: a
   pre-existing engine issue that P3a's bracket also triggers. See open questions 1 and 2 for the two fixes.
2. **Redo after opening the tool.** The bracket's switch-off clears the redo queue (any change does), and its undo
   puts the switched-off state on the redo queue. The app never offers that state (`RedoGuard`, tested), but the
   user's redo steps from before the tool opened are gone, where desktop keeps them. At the bridge's undo limit
   (100 states) the oldest one is dropped by each bracket. Both only when crop is on (no bracket otherwise).
3. While the tool is open the photo is the preview pipe's picture (as at fit since P2i); zoomed in, it is shown
   scaled, without the sharp region.
4. The tool does not open while framing (borders) is on after crop: desktop maps the frame through that module's
   geometry, which needs the coordinates call of the extended engine API. The darkroom says so; the margins work.
5. Desktop's shift (resize around the centre) and ctrl (move along one axis) have no touch equivalent here.
6. Rotation (deliverable 4): while the slider moves, the uncropped picture is turned on screen (not rendered); the
   engine's picture follows when the finger lifts. If rotate and perspective's own cropping is on, its effect shows
   only then. Straighten treats "autodetect" orientation as not mirrored (cameras write turns, not mirrors).
7. The emulator tests check the frame's XMP with darktable-cli (size and picture) but not a rotated XMP.

## 2. Deliverable 3: how the uncropped picture is obtained, and why no temporary state reaches the XMP

**Choice: the frame lives in the app while the tool is open; the engine is changed only inside one locked
bracket that is undone before the lock is released.** As on desktop, where crop keeps the frame in its interface
data (`g->clip_*`) while it has the focus and writes the parameters when it loses it (`gui_focus` →
`_commit_box`), the app keeps the frame in `CropToolState` and writes it with one `params_set` when the tool is
left. The uncropped picture (crop's input) is rendered once when the tool opens, in one section under the darkroom
controller's lock: `set_enabled(crop, off)` → `render_size(1)` + `render_preview` → `undo()` (the undo in a
`finally`, under `NonCancellable`). Every `xmp_write` of the app (`saveLocked`, `writeXmp`), every session
LightPhotoLibrary uses (`withPhotoSession` for its preview, `saveIfOpen` for `writeIfOpen`) and every render takes
that same lock, so nothing can see the switched-off state. The history is read before and after the bracket and
compared; if the undo failed or the history differs, a flag stays set and every write and LightPhotoLibrary session
is refused (the edit is then not written rather than written wrong). With crop off no bracket is needed (the
current picture is the uncropped one). While only crop changes, crop's input does not change, so the picture stays
valid; after any other change while the panel is open, the bracket runs again.

Why each case of the brief is safe, and its test (JVM: `app/darkroom/.../P3aCropToolTest.kt` on the stand-in
engine; emulator: `app/app-engine/.../P3aScenarioTest.kt` on the real engine):

| Case | Why | Test |
|---|---|---|
| `writeIfOpen` during a sync, tool open | the engine holds the last committed edit; the frame is not in it until the tool is left | JVM `aSyncWhileTheToolIsOpenWritesTheEditFromBeforeTheTool`; emulator c1 (`saveIfOpen` after real-touch drags: edit identical) |
| `writeIfOpen` while the bracket runs | it waits for the lock; the bracket ends with the undo | JVM `aSyncDuringTheBracketWaitsAndWritesTheEdit` (slow stand-in render, write requested after the switch-off) |
| automatic sync after "done" | "done" leaves the tool first (frame written, one item), then saves | JVM `doneWritesTheFrameKilledWritesNothing`; emulator c1 (panel closed, XMP, export) |
| app killed with the tool open | the engine never writes an XMP by itself; nothing is written while the tool is open | JVM `doneWritesTheFrameKilledWritesNothing` (XMP bytes and time unchanged while dragging) |
| "before" | `history_goto` 0 and back under the lock, unchanged; the tool's frame is not in the edit | JVM `beforeWhileTheToolIsOpenChangesNothing` |
| history navigation | the tool is left first (frame written), then the navigation; the tool opens again on that state | JVM `historyNavigationLeavesTheToolFirst` |
| undo / redo | as navigation; the bracket's redo entry is never offered | JVM `undoAndRedoWhileTheToolIsOpen`, `theBracketsRedoStepIsNeverOffered` |
| another module changed while the panel is open | the frame is written first, then the change | JVM `anotherModulesChangeWhileTheToolIsOpenWritesTheFrameFirst` |
| read-only | the tool does not open; nothing is switched | JVM `readOnlyPhotoHasNoTool` |
| no extra history items | the bracket's undo restores the whole snapshot; history compared before/after | JVM `theToolOpensOnTheUncroppedPictureWithoutChangingTheEdit`, `noChangeNoHistoryItem`; emulator c1, c2 |
| the undo fails (not expected) | flag stays set; writes and sessions refused | JVM `anUndoThatFailsStopsEveryWrite` (stand-in engine whose undo throws) |

The edit never contains a temporary state; the metadata traces of the bracket are limit 1 above.

## 3. Item by item

1. **The crop tool on the photo — done.** The tool opens when crop's panel is expanded on an editable photo (as
   desktop shows it when crop gets the focus; no extra button: one tap less, and the panel's margins and aspect
   list are right there). The whole uncropped picture is shown; outside the frame dimmed; the frame with square
   handles on the corners and bars on the sides; a rule-of-thirds grid inside (mockup B3-Crop; new tokens
   `color.cropDim/cropFrame/cropGrid/cropHandle/cropHandleFill`, `size.cropFrameLine/cropGridLine/cropHandle/
   cropHandleLine/cropSideHandleLength/cropSideHandleWidth`). Corners and sides resize, inside moves the frame, the
   frame never leaves the picture, the ratio is kept exactly as desktop keeps it: the arithmetic (`app/schema`
   `CropTool`) is crop.c's `_aspect_apply`, `button_pressed`/`mouse_moved` and `_commit_box`, checked against
   values computed by crop.c's own code (`tools/p3a-crop-reference.py` copies the functions verbatim, compiles them
   and prints 1815 `_aspect_apply` cases, 162 drags of 4 moves, 165 commits; CI checks the file against the pin).
   Touch targets: a side is grabbed within `size.touch` (44 dp) inside the frame (at most a third of the frame, so
   its middle always moves it) and up to half a target outside it, so a side lying on the picture's edge, next to
   the screen's edge, is taken from the surround. A drag that starts away from the frame, and two fingers, pinch
   and pan; a second finger before the frame moved turns the touch into a pinch and gives the frame back. The
   cropped size is shown in the line under the photo (phone) or in the top bar (tablet), not over the photo.
2. **Leaving the tool — done.** Closing the panel, opening another module, "done", "back", another photo, export,
   any other edit, history navigation, undo and redo write the frame (rounded as `_commit_box` does), one history
   item, none when nothing changed by more than 1e-6 (desktop's rule); undo brings back the frame before. The
   margins' sliders and the aspect list (and its flip) show and move the frame live in the tool (`gui_changed`'s
   rule: that side, then `_aspect_apply` from it).
3. **Uncropped picture — done**, with the limits 1 and 2 above (section 2).
4. **Rotation and straighten — done (second priority, after 1–3 were green).** Under the photo while the tool is
   open: rotate and perspective's `rotation` as darktable's slider (from the control schema: factor −1, soft range
   ±10°, double tap resets, long press for exact entry), and "straighten": a line drawn on the photo along a horizon
   or a wall changes the rotation by ashift.c `_calculate_straightening`'s angle (desktop's right-button drag: the
   slider is set to its value minus the angle, so `rotation + angle`). Ported in `CropTool.straighten`, checked
   against 156 values from the verbatim function (an exact 45° tie may differ in sign: the last bit of atan2
   decides; the test says so). On the emulator a line drawn by injected touches set −2.72° for a line of −2.83°.
5. **Double tap on a slider's bar resets — done.** The bar (track and marker, with the gap above it) resets on a
   double tap; a long press there opens exact entry; a double tap on the name still resets; a double tap on the
   rest of the row (the value) opens exact entry. `ui-spec.md` section 3 updated; section 6 added for the crop tool.
6. **Tests — done.** JVM: `CropToolTest` (10: every reference case above, the size line as `gui_post_expose`, hit test, frame stays inside, portrait,
   landscape and square input, flipped ratios; a photo turned by 90° is a portrait crop input), `P3aCropToolTest`
   (18: deliverables 1–4 and every case of 3), screenshot tests on phone and tablet (`P3aCropScreenshotTest`, 6,
   with Compose touch input: corner, move, beyond the picture, away from the frame, rotation, a straightening line).
   Emulator: `P3aScenarioTest` c1 (corner and frame dragged with touches injected through `UiAutomation`, sync
   during the tool, panel closed, XMP written, exported), c2 (no change), c3 (straighten by touch); the p2b compare
   job renders c1's XMP with darktable-cli and compares it with the app's export (size and PSNR ≥ 40 dB).

## 4. How to reproduce

```
# reference values from crop.c and ashift.c at the pin (CI does this and diffs the result)
PIN=$(git ls-tree HEAD upstream/darktable | awk '{print $3}')
for f in src/iop/crop.c src/iop/ashift.c src/common/math.h; do mkdir -p /tmp/p3a-src/$(dirname $f); \
  curl -fsSL -o /tmp/p3a-src/$f https://raw.githubusercontent.com/darktable-org/darktable/$PIN/$f; done
DT_UPSTREAM_COMMIT=$PIN python3 -I tools/p3a-crop-reference.py /tmp/p3a-src | diff - app/schema/src/test/resources/p3a-crop-reference.json
# JVM tests without an Android SDK (schema), and in CI (darkroom, screenshots)
cd app && gradle --no-daemon :schema:test
gradle --no-daemon :darkroom:testDebugUnitTest      # needs the Android SDK (CI: p2a-app)
# emulator: .github/workflows/p2b-real-engine.yml (tools/app/emulator-p2b.sh runs P3aScenarioTest)
```

## 5. Results with numbers

Workspace measurements are x86_64 (2 cores); emulator numbers are the x86_64 API 33 emulator in CI, not a phone.

- **Arithmetic against crop.c and ashift.c** (`CropToolTest`, local and CI): 1815 `_aspect_apply` cases (3 input
  shapes × 11 ratios × 5 frames × 11 grabs), 162 drags × 4 moves (3 shapes × 6 ratios × 9 grabs: 4 corners,
  4 sides, move), 165 `_commit_box` roundings, 156 straightening lines: all equal within 2e-6 (frames) and 1e-4°
  (angles); one exact 45° tie differs in sign only (see item 4).
- **JVM tests, p2a-app** (run 44731123711 on 321fde8): 236 tests, 0 failures (new: 10 CropToolTest,
  18 P3aCropToolTest, 6 crop screenshot tests); p2b-real-engine's JVM step: 298 tests, 0 failures, 1 skipped
  (as before).
- **Emulator, p2b-real-engine** (run 44731123836 on 321fde8): 50 of 50 tests (`p3a-results.txt` in ci-logs
  `runs/44731123836-a1/p2b-emulator/p2b-test/`):
  - c1: test RAW 3908×2602 uncropped, square crop 2602×2602; corner dragged by injected touches: frame width
    0.666 → 0.499, height 1.000 → 0.750 (square kept); frame moved: x 0.334 → 0.414, y 0.000 → 0.040; history
    and parameters unchanged while the tool was open; writeIfOpen during the tool: edit identical (differences:
    `change_timestamp`, `history_auto_hash` dropped); panel closed: history 12 → 13 items; the tool showed
    1950×1950, the picture is 1950×1950, the app's export 1950×1950. (On a4efb01 the tool showed 1951×1951 for the
    same picture: it rounded where desktop's `gui_post_expose` floors; fixed in 6c84627.)
  - c2: tool opened and closed without a change: history equal; XMP differs only in `change_timestamp` and
    `history_auto_hash` (limit 1).
  - c3: straighten by touch: rotation 0 → −2.72° for a line of −2.83° (desktop's function on the line asked
    for; the difference is the injected touch points), one history item (rotate and perspective).
- **darktable-cli** (compare job, same run): c1's XMP rendered by darktable-cli: 1950×1950, the size of the app's
  export and of the picture the app showed; app export vs darktable-cli: PSNR 43.81 dB (max 17, mean 1.13),
  accepted bound 40 dB as for the other comparisons. The other comparisons unchanged (G0 75.73 dB, app-written
  edit 74.44 dB, P2f crop ratio 0.6667, P2j 44.34 / 46.99 / 46.11 dB).
- **Found and fixed on the way**: run 44730200524 (6c84627) failed `anUndoThatFailsStopsEveryWrite`: after a
  failed bracket undo (the stand-in engine made to fail), a second request opened the tool again on the state the
  failed undo left. Writes were still refused, but the tool must not open on it: 321fde8 keeps the tool closed while
  that flag is set and clears the flag when the photo is opened again; the test now asks twice and reopens.
- **Screenshots** (release app-preview-321fde880e06, `*-p3a-*.png`): the tool on phone and tablet, after drags by
  touch, during a rotation drag, a straightening line, after straightening. Looked at: frame, dimming, grid,
  corner squares and side bars as in mockup B3-Crop; the size line under the photo (phone) and in the top bar
  (tablet).

## 6. Owner instructions

Install `dt-android-arm64-v8a.apk` from the release **dt-android-321fde880e06** (it installs over the earlier
build). Then, on a photo:

1. Open crop's panel (tap "crop" in the module list). The photo shows the whole picture with the frame; outside it
   is darker. The line under the photo shows the size the cropped picture will have.
2. Drag a corner or a side to resize (start on the white square or bar, or just outside the frame next to it);
   drag inside the frame to move it. Two fingers still zoom and pan. The aspect list and the margins below work on
   the same frame.
3. Close crop's panel (tap "crop" again), open another module, or tap "done": the crop is applied, one line in the
   history. Undo brings back the frame before.
4. Rotation: the slider under the photo turns the picture while you move it; it is applied when you lift the
   finger. "straighten", then draw a line along a horizon or a wall: the rotation levels it.
5. On any slider, a double tap on the bar now resets it (as before, a double tap on the name; long press for an
   exact value).

Worth checking on the phone: whether the handles are easy to take near the screen's edges, and how long the
uncropped picture takes to appear when crop's panel opens on a cropped photo (one preview render, as after a
change).

## 7. Open questions for the reviewer

1. The clean way to the uncropped picture is the bridge call the brief describes (a render option that bypasses one
   instance, for example `render_preview_bypass(op, priority, max_size)`): no switch-off, no undo, so none of the
   traces of limits 1 and 2. In the app it would replace the body of `openCropLocked` only. Worth adding to the
   engine package?
2. Independently of P3a: the bridge's `undo()` drops `history_basic_hash` and `history_auto_hash` (limit 1). A fix
   in `engine/src/history.c`: read the image's `history_hash` row (`dt_history_hash_read`) before
   `dt_history_snapshot_restore` and write basic and auto back after it (`dt_history_hash_write`), or keep them in
   the undo entry. For the engine agent?
3. The tool opens with crop's panel, without a separate "crop" button. Keep it so?
4. Framing after crop blocks the tool (limit 4); acceptable until the coordinates call exists?

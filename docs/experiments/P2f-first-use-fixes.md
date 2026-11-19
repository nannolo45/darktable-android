# P2f: fixes after the first real use

Brief: `docs/briefs/P2f-first-use-fixes.md` (with its addendum of 19 Nov, evening). Branch `p2f-first-use-fixes`,
from the tip of `p2g-engine-start`. Pull request text: `P2f-first-use-fixes-REPORT.md`.

## Additions from the reviewer (19 Nov, during P2g)

- **Deliverable 4, timings on any photo.** The owner could not run the timings on a photo from LightPhotoLibrary:
  `AppViewModel.runTimings` falls back to the file-picker working folder (`work.items()`) when no photo is open,
  and answers "open a photo first" when that folder is empty. Wanted: the timings (with the per-module table) run on
  the open photo whatever its source, otherwise on the last opened photo of either source; the text says the
  source (no server or person names), the number of history items and the modules on; check whether "timings…"
  from inside the darkroom still has the photo open; a test for the LightPhotoLibrary case.
- **New deliverable 10, crop's aspect** (after 1 to 8, before the final tests and report): crop's "aspect" combobox
  has `param: null` / `bound_to: null` in the schema, so it is shown disabled. Wanted, following the manual and
  `crop.c`: darktable's aspect list in its order, choosing one sets `ratio_n` / `ratio_d` and adjusts the frame and
  margins as desktop does, the orientation flip, dragging keeps the ratio, XMP round trip both ways; done through
  `schema/overrides/` (as `flip.json`, `temperature.json`); a typed "x:y" ratio may wait; JVM tests for the mapping
  and an emulator or contract test for the round trip. Also list (not fix) other interface-only controls without a
  parameter binding in the built-in "workflow: scene-referred" layout's modules.

- **R13 to R16 on LightPhotoLibrary's side** (reviewer, 19 Nov): build the client to these shapes; it must still work
  when the server answers 404 or leaves fields out; do not edit PLAN.md section 4. XMP post adds `agent=<agent id>`
  (URL-encoded). `GET /api/photos/{id}/edits` (optional `limit`) → `{"edits": [event…]}` newest first;
  `GET /api/photos/{id}` carries `"last_edit": event | null`; an event has `time` (seconds, number; the only field to
  rely on), `source` (`agent`, `outside`, `import`, `local`), `device`, `device_os`, `modules`, `changes`, `summary`;
  the line is "last edited on <device> · <time> · <summary>", `outside` said in plain words. Heartbeat items may add
  `step` (`downloading`, `sending edit`, `rendering preview`, `sending preview`) and `progress` 0..1, left out when the
  step ends. R13: a job never reported done comes back with the same id; report every job, and a job id already run
  is reported done again, not run twice. R14: `GET /api/auth/token` with `x-lpl-token` → 200
  `{"required", "ok"}`, 401 when wrong or missing; on 404 fall back to today's check.

## Running log (newest first)

- 2026-11-19 19:45 (local, not pushed yet: the "before" run is still on the emulator and a push would cancel it):
  deliverables 2 and 3 in code. Interactive work goes first (`DarkroomController.fg`/`bg`, order described in the
  controller and in the report); the LightPhotoLibrary preview is rendered through the full pipe (cancellable) and
  encoded with Android's JPEG encoder instead of `dtb_export` (not cancellable); thumbnails are no longer rendered
  before the photo opens (background, when no photo is open); a stored last picture (1024 px) and the thumbnail are
  shown while a photo opens, with a line saying what happens and a thin bar for downloads; thumbnails older than
  the XMP are shown (and refreshed in the background) instead of black. Darkroom and LPL JVM tests pass on the
  workspace; tests that asserted all thumbnails right after `start` now assert the new order (open photo first,
  the others in the background).

- 2026-11-19 19:00: branch created from `p2g-engine-start` (a4def3f). First step, deliverable 1 before any fix:
  a timeline recorder (`darkroom/Timeline.kt`, marks in the darkroom's open and preview and around the LPL preview
  render) and emulator test `P2fScenarioTest.f1` (heavy edit: denoise (profiled), diffuse or sharpen, local
  contrast, sharpen on with defaults; done; reopen from the list while the LPL preview renders). Pushed unchanged
  otherwise, so CI records the timeline **before** the fix; f1 is expected to fail its acceptance checks there.
  Reading of the code so far (to be confirmed by the timeline): the LPL preview is darktable's 1920 px export
  under the darkroom controller's lock (`AppHost.Editor.renderPreview` → `withPhotoSession`), so `openPhoto` waits
  for it while the screen has `preview = null` (black); and the list's thumbnail (`FolderThumb.load`) is refused when
  the XMP is newer than it, which a merged answer from LPL (or any XMP write after the darkroom closed) makes true.

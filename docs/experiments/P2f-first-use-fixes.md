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

## Running log (newest first)

- 2026-11-19 19:00: branch created from `p2g-engine-start` (a4def3f). First step, deliverable 1 before any fix:
  a timeline recorder (`darkroom/Timeline.kt`, marks in the darkroom's open and preview and around the LPL preview
  render) and emulator test `P2fScenarioTest.f1` (heavy edit: denoise (profiled), diffuse or sharpen, local
  contrast, sharpen on with defaults; done; reopen from the list while the LPL preview renders). Pushed unchanged
  otherwise, so CI records the timeline **before** the fix; f1 is expected to fail its acceptance checks there.
  Reading of the code so far (to be confirmed by the timeline): the LPL preview is darktable's 1920 px export
  under the darkroom controller's lock (`AppHost.Editor.renderPreview` → `withPhotoSession`), so `openPhoto` waits
  for it while the screen has `preview = null` (black); and the list's thumbnail (`FolderThumb.load`) is refused when
  the XMP is newer than it, which a merged answer from LPL (or any XMP write after the darkroom closed) makes true.

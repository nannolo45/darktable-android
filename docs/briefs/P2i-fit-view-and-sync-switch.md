# P2i: the view at fit shows the preview pipe's picture; a switch for automatic syncing

Decided by the owner on 20 Nov 2026 after a timings run on the phone with a real edit. App side only: **no change
under `engine/` or `upstream/`**. Branch `p2i-fit-view` from `main` (after P2h is merged). Report
`docs/experiments/P2i-fit-view-REPORT.md`, running log `docs/experiments/P2i-fit-view.md` (pushed at least every
30 minutes).

## Why (numbers from the owner's phone, release `dt-android-0ec21b1386a7`, 4 threads on the fast cores)
A 24 MP X-Trans RAW with an edit of 29 history items, 19 modules on (among them demosaic with capture sharpening,
two exposure instances, color calibration, contrast equalizer, color equalizer, rgb curve, spektrafilm). In ms:
- first preview 1024 px: 1245 (spektrafilm 315, color equalizer 314, demosaic 287)
- preview after an exposure change: 878 (color equalizer 321, spektrafilm 253); after a late module change: 362
- drag picture 320 px: 62 (median); by size: 256 px 41, 384 px 95, 512 px 174, 768 px 424, 1024 px 877
- **sharp region at fit (1080 x 721) after a change: 5781, of which demosaic 4720** (the whole sensor at full
  resolution, every time; the full pipe's cache does not keep demosaic's output on this photo)
- sharp region at 100 % (1080 x 798): 3522 (color equalizer 933, spektrafilm 676, demosaic 525, exposure 444 + 419)
- open 230; `modules()` 10 + 13; full-size export 47 199

A render is cancelled only between modules, so a change made while the fit region renders can wait up to 4.7 s
inside demosaic before anything happens. That is the lag the owner sees.

## Deliverables
1. **At fit, the darkroom shows the preview pipe's picture and does not start the full pipe** (P2f report, section 3,
   option A). The preview is rendered at the size the view needs (the longer side of the photo's frame on screen,
   within what the preview pipe allows; say what that limit is and what was chosen), so it is not a 1024 px picture
   stretched over a larger frame when a larger one is affordable; give the phone-side cost estimate per size from
   the numbers above and choose with a stated time target.
2. **The full pipe runs only when the user zooms in beyond fit** (and for export, the LightPhotoLibrary preview, the
   timings page). On zoom-in: the preview's picture scaled up at once, the sharp region when it is ready, cancelled
   on any change as now. Zooming back to fit cancels a region render in flight.
3. **Say it once in the interface, quietly**: at fit nothing; when zoomed in while the region renders, the existing
   "rendering…" line. No new settings.
4. **Nothing waits behind a region render the user no longer needs**: after 1 and 2, measure on the emulator with
   the heavy test edit that a change at fit is followed by the preview in the time the preview costs, with no full
   pipe module in the per-module lines.
5. **A switch "sync automatically" in the app's LightPhotoLibrary settings, default on** (owner's request). Off:
   nothing is sent or fetched by itself: "done" keeps the edit on the phone and the row says "edited here, not synced
   yet"; the background job does not sync; "sync now" (header and per photo) works as today; opening a photo that is
   not on the phone still downloads it (the user asked for it); a job from LightPhotoLibrary addressed to this phone
   ("save edited to NAS", "remove") is still carried out, because the owner pressed it on another device, and the row
   says so as today. The heartbeat continues (the phone stays visible in LightPhotoLibrary) and reports the photos as
   edited and not synced. The header says "automatic sync is off" while it is. Does not touch the LPL contract; if you
   find it would, stop and write why.
6. Tests: JVM for 1, 2 and 5 (fake engine and stub server); emulator: 4, and the timings page still reports the fit
   and 100 % region numbers (it calls the full pipe itself). Screenshot of the header with the switch off. Both
   workflows green on the last code commit; APK from the usual release step. Owner instructions in the report.

## Not in this package
A demosaic shortcut in the bridge (E3), the drag picture's size rule, anything about pickers, masks or crop.

## Rules
`CLAUDE.md`. Nothing personal in the repository (no names of people, phone or camera models, names of layouts,
presets or styles, addresses, private links). SPDX line on new files. Do not weaken a test. Never push to `main`, no
pull requests. Report shortfalls first, plainly. Maven Central is flaky in CI: rerun before suspecting the change.

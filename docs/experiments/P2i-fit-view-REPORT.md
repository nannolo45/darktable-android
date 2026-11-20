# P2i: the view at fit shows the preview pipe's picture; a switch for automatic syncing — pull request description

Branch `p2i-fit-view` into `main`. Brief: `docs/briefs/P2i-fit-view-and-sync-switch.md`. Running log:
`docs/experiments/P2i-fit-view.md`. App side only: nothing under `engine/` or `upstream/` changed; the LightPhotoLibrary
contract (PLAN.md section 4) is unchanged (section 5, question 1).

## 0. Failures, limits and corrections first

- **No phone numbers.** The preview size at fit (1024 to 1152 px) was chosen from the brief's phone numbers by
  extrapolation (section 1); the emulator numbers below are x86_64 on 4 cores, not phone numbers. Whether 1152 px
  meets the 1.2 s target on the phone with the owner's edit is for the owner to see (section 6).
- **l4's cause: shown, not caught in the act.** The l4 timeline of run 44704862471 shows the background thumbnail pass
  starting to render photo 51 **96 ms after** the `release` job had removed it (darktable opened the deleted file);
  the test ended before that render was stored, so the run passed. That is the suspected sequence up to the last
  step. In the last run (44707660180) the pass stored 51's thumbnail before the removal, so the late case did not
  occur there. The last step (the store making the folder again) is shown by the JVM test and by the emulator test l7,
  which call the app's own store after a removal and failed before the fix. The failure itself was not reproduced:
  l4 passed in every run of this package. See section 3, item 7b.
- **Behaviour changed on purpose, tests changed with it** (not weakened; each still checks what it checked, plus the
  new behaviour):
  - `DragAndBeforeTest.dragShowsTheSmallPictureThenTheFullOneAndReadsModulesOnlyAtTheEnd` waited for the sharp region
    at fit. At fit there is no region any more (deliverable 1), so the test now zooms in first; the checks (region
    hidden while the finger moves, back after it lifts) are unchanged.
  - `P2hUnreadableTest` (two tests) and emulator `f8` checked that a file remembered as having no picture is not
    opened again when the user opens it from the filmstrip. Deliverable 7 reverses that: the user's open is always
    tried. They now check that it is opened again, gives the same sentence, closes its session, renders nothing,
    and stays remembered for background passes.
- **The emulator's own darkroom frame is 304 x 250 px**, smaller than 1024, so in the app on the emulator the
  preview stays at 1024 px. f10 sets a phone-sized frame (1080 x 1500) itself; the first run of f10 (44701978994)
  measured at 304 x 250, the later ones at 1080 x 1500.
- **A slight zoom counts as zoomed in.** Any zoom above fit (more than 1.001) renders the sharp region, so a pinch
  that ends a little above fit still runs the full pipe over the whole sensor (as before P2i at fit). Not in the
  brief; question 3.
- **No local build of the Android modules** (no Android SDK on the workspace). `:lpl:test` and `:engine-api:test`
  ran locally; everything else ran in CI.
- The screenshot of the header with the switch off is the JVM screenshot of the list (Robolectric,
  `list-phone-05-auto-sync-off.png`), filled with the same line function the app uses, not a capture of the running
  app.

## 1. The preview's size at fit, and why

**What the preview pipe allows.** The bridge's `dtb_render_preview` (engine/src/render.c) renders the preview pipe on
darktable's `DT_MIPMAP_F` and never scales up (`scale = min(1, max_size / processed size)`). In upstream at the pinned
commit (`src/common/mipmap_cache.c`, `dt_mipmap_cache_init`), `DT_MIPMAP_F` is mip 3, **1440 x 900**, or mip 4,
1920 x 1200, when the preference `highres_preview_mip` is on; the bridge does not set it. `_init_f` fits the sensor
image into that box: a 3:2 sensor gives **1350 x 900**, a 4:3 one 1200 x 900, before any crop. So the preview pipe
gives at most about 1350 px on the longer side of an uncropped 3:2 photo; asking for more returns that size.

**Cost per size on the phone** (estimated from the brief's numbers: preview after an exposure change 877 ms at
1024 px; the drag table 768 px 424 ms, 1024 px 877 ms, so time grows about as pixels^1.25):

| longer side | after an exposure change | first preview after opening (x 1.4) |
|---|---|---|
| 1024 px | 877 ms (measured) | 1245 ms (measured) |
| 1080 px | about 1000 ms | about 1400 ms |
| 1152 px | about 1180 ms | about 1650 ms |
| 1200 px | about 1300 ms | about 1850 ms |
| 1350 px (pipe limit, 3:2) | about 1750 ms | about 2450 ms |

Before P2i the sharp picture at fit came 878 + 5781 ms after a change (the brief).

**Chosen** (`DarkroomController.previewSizeFor`, `FIT_PREVIEW_MAX = 1152`): the longer side of the photo's frame on
screen at fit, at least 1024 px (never less than before) and at most 1152 px. **Time target: a change at fit shown
within about 1.2 s on the phone with the owner's heavy edit.** On the phone in portrait a landscape photo's frame is
1080 px wide, so it gets **1080 px** (about 1.0 s); a portrait photo's frame is about 1400 to 1500 px high, so it gets
1152 px (about 1.2 s), shown scaled up by about 1.25. The same size is used when zoomed in (the preview is then
scaled up under the region), so a zoom does not make the preview pipe render another size. A frame of another size
(rotation) gets a preview of its size. The "before" picture uses the same size.

## 2. Brief item by item

| # | Item | State |
|---|------|-------|
| 1 | At fit the preview pipe's picture at the view's size, no full pipe; limit and choice stated with a time target | **done** (section 1) |
| 2 | Full pipe only when zoomed in (and export, LPL preview, timings); on zoom-in the preview at once, then the region, cancelled on a change; back to fit cancels a region in flight | **done** |
| 3 | At fit nothing said; zoomed in while the region renders, "rendering…"; no new settings | **done** |
| 4 | Emulator, heavy edit: a change at fit followed by the preview in the preview's time, no full-pipe module | **done** (section 4: numbers; "no full-pipe module" shown by counting, section 4) |
| 5 | Switch "sync automatically", default on, as specified | **done** (section 3) |
| 6 | Tests: JVM for 1, 2, 5; emulator 4; timings page still reports fit and 100 % region; screenshot; both workflows green; APK; owner instructions | **done** (section 4, 6) |
| 7a | A photo the user opens is always tried; test: first open fails in `image_info`, second open succeeds | **done** |
| 7b | l4: establish, fix, test that fails without the fix | **fixed and tested; cause shown up to the last step, not caught failing** (section 0) |

## 3. What changed

**Darkroom (`app/darkroom/.../DarkroomController.kt`, `ui/Photo.kt`, `DarkroomState.kt`).**
- `requestRender` and `renderNow`: the preview at `previewSize()`, then the region only when the viewport is zoomed
  in (read after the preview, so a zoom made meanwhile counts).
- `renderRegion`: reads the viewport on every try (back at fit: renders nothing), counts `regionRenders`, sets
  `regionPending` (the screen's "rendering…"), shows the region only if still zoomed in. A cancel caused by going back
  to fit is not retried (`wanted` asks whether the picture is still wanted).
- `setViewport`: back at fit, a region render in flight is cancelled (`cancelRegionRender`: only while that render runs,
  under its own guard, so it cannot stop a preview or background render) and the region is dropped. A frame of
  another size gets a preview of its size once any render in flight has ended.
- `Photo.kt`: "rendering…" (`DarkroomController.RENDERING`, the existing line) when zoomed in and the region
  renders, unless the photo's own working line (opening, downloading) is shown.
- Deliverable 7a: `openLocked` no longer refuses a remembered file; it opens it (timeline line "remembered as having
  no picture, tried again (the user opened it)"). A file that then has a picture is forgotten; one that again has
  none stays remembered. Background passes (`renderThumbnails`, `withPhotoSession`) still skip remembered files.
- Timeline lines: preview render start and size, region render start, cancelled, shown; thumbnail pass render start,
  rendered and store, with the photo's folder name (file names repeat across folders).

**Timings page (`Timings.kt`).** New line: the preview at the darkroom's fit size after an exposure change (the size
from the darkroom's last view, else the screen). The sharp region at fit and at 100 % are still measured (the page
calls the full pipe itself).

**Engine wrapper (`JniEngine.kt`, app side).** Counters `regionCalls` and `previewCalls`, for tests.

**l4 (`LplAgent.writeIfHeld`, `AppHost.thumbs`).** A thumbnail or last picture of a LightPhotoLibrary photo is written
only under the photo's record lock, which `LplAgent.remove` holds while it deletes the folder, and only while the
record and the folder exist: a write after the removal is dropped with a timeline line ("not written: the photo is
no longer on the phone"). `remove` writes a timeline line too.

**"sync automatically" (`SyncSettings`, `LplAgent`, `AppHost`, `SyncJob`, `AppViewModel`, `LplScreens`, `Screens`).**
Stored in the app's files (`lpl/settings.json`), default on. Where: the list's menu → "LightPhotoLibrary server…",
under the server form, with one line saying what on and off mean. What it does exactly when **off**:
- "done": the edit is written to the photo's XMP as always; nothing is sent (`syncAfterDone` returns nothing). The row
  says **"edited here, not synced yet"**; the line under it shows LightPhotoLibrary's last edit.
- Nothing is sent by itself: no sync when the darkroom closes, none when the app comes to the front, the background
  job is not scheduled (and if one scheduled earlier runs, it sends nothing and ends).
- Nothing is fetched by itself: the next photo of the filmstrip is no longer downloaded ahead.
- Still works: "sync now" in the list's header (all photos) and per photo, and "sync" in the darkroom's menu; opening
  a photo that is not on the phone downloads it; opening a photo that is on the phone and has no unsynced edit still
  asks LightPhotoLibrary for a newer XMP first (as today; the user asked to open it; question 2); jobs from
  LightPhotoLibrary addressed to this phone (`sync`, `release`, `edit`, `open`) are carried out and the row says so
  as today.
- The heartbeat goes on every 2 s while the app is open: the phone stays visible, its edited photos are reported
  `dirty`, and the device's message (`state.message`) says "automatic sync is off".
- The header says "this phone is "Phone" · connected · **automatic sync is off** · 2 photos not synced".
- Switched on again: what waited is sent at once; what cannot be sent goes to the background job.

## 4. How to reproduce, and results

JVM tests and rule check (as CI runs them; needs an Android SDK with platform 36 and build-tools 35.0.0):

```
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :schema:test :lpl:test :darkroom:testDebugUnitTest :checkScreenRules
```

Emulator: the `emulator` job of `p2b-real-engine.yml` (`tools/app/emulator-p2b.sh`), which runs `P2fScenarioTest`
(f10 new, f4 and f8 changed) and `LplScenarioTest` (l4 changed, l7 new) with the others.

**JVM** (`app/darkroom/src/test/.../P2iFitViewTest.kt`, 7 tests; `app/lpl/src/test/.../P2iLplTest.kt`, 6 tests):
- `previewSizeFollowsTheFrameWithinItsBounds`: 1080 for a 1080 x 1500 view and a 3:2 landscape photo, 1152 for a
  portrait photo there and for a tablet, 1024 for a small or unknown view.
- `atFitAChangeRendersThePreviewOnly`: on a 1080 x 1500 view every preview is asked at the frame's size, a change
  renders the preview only, no region, nothing said.
- `aChangeAtFitIsFollowedByThePreviewInThePreviewsTime`: stand-in with a 3 s region: three changes at fit, the
  preview shown after 405, 393, 412 ms (its render 399, 389, 399 ms; p2a run 44701978982); no region.
- `zoomInShowsThePreviewAtOnceThenTheRegion`: the same preview object at once, "rendering…", then the region; a change
  while zoomed: preview and region again.
- `backToFitStopsTheRegionInFlight`: a 3 s region stopped within 1 s of going back to fit; region dropped; a change
  at fit right after: preview, no region.
- `aNewFrameSizeGetsItsPreview`: 400 x 300 → 1024 px; then 1080 x 1500 → the frame's size.
- `aPhotoTheUserOpensIsAlwaysTried` (7a): first open fails in `image_info` (the bridge's own words), the background
  pass does not open it, the user's second open of the unchanged file opens it, its preview shows, it is forgotten.
- `P2iLplTest`: the switch is on by default and kept; off: download on open works, nothing sent after "done" or by the
  automatic paths, row status `dirty`, heartbeat says `dirty` and "automatic sync is off", "sync now" (all and one)
  sends; on: as before; off with a `sync` and a `release` job: both carried out; 7b: a plain atomic write after the
  removal makes the folder again (the mechanism), `writeIfHeld` drops it; a writer thread storing thumbnails while the
  photo is removed, 20 rounds: the folder never comes back.

**Emulator, deliverable 4** (f10, test RAW `mire1.cr2` 3908 x 2602, f1's heavy edit: denoise (profiled), diffuse or
sharpen, local contrast, sharpen; x86_64 emulator, 4 cores). Run 44701978994 at the emulator's own 304 x 250 frame
(preview 1024 px):

| change at fit | preview shown after | its render | per module (darktable's "-d perf" lines of that change) |
|---|---|---|---|
| 1 | 424 ms | 398 ms | 381 ms over 8 modules: diffuse 137, local contrast 102, sigmoid 50, color calibration 40, … exposure 11 |
| 2 | 236 ms | 214 ms | 206 ms over 8 modules |
| 3 | 239 ms | 218 ms | 210 ms over 8 modules |

Each module appears once (one pipe), the modules are those after exposure (the preview pipe's cache keeps the rest),
no demosaic, the darkroom started no region render and the bridge got no `render_region` call. For comparison the
timings page of the same run: sharp region at fit 1080 x 719 after a change 671.5 ms (demosaic 183, denoise 177, …),
which a change at fit no longer waits for. Zoomed in (3x): the region shown 338 ms after the zoom, "rendering…" seen;
back to fit while a region renders: cancelled, nothing pending 10 ms after.

**Last code commit 010cb15**, emulator at a 1080 x 1500 frame (preview asked 1080 px), run 44707660180 attempt 3
(`runs/44707660180-a3/p2b-emulator/p2b-test/p2f-results.txt` on the `ci-logs` branch):

| change at fit | preview shown after | its render (1080 x 719) | per module |
|---|---|---|---|
| 1 | 458 ms | 425 ms | 412 ms over 8 modules: diffuse 177, local contrast 86, sigmoid 53, color calibration 34, sharpen 25, … |
| 2 | 281 ms | 260 ms | 252 ms over 8 modules |
| 3 | 243 ms | 222 ms | 216 ms over 8 modules |

Again each module once, no demosaic, no region started, no `render_region` call. Zoomed in 3x: "rendering…" seen, the
region (1080 x 1500 at scale 0.83, full pipe) shown 2380 ms after the zoom (render 2258 ms); back to fit while a
region rendered after a change: cancelled, nothing pending 91 ms after. The timings page (f4) still reports the fit
and 100 % regions: sharp region at fit 1080 x 719 after a change 654 ms (demosaic 178, denoise 176, …), at 100 %
1080 x 1500 2318 ms, and the new line "preview at the darkroom's fit size … 283 ms". f9: 0 overlapping bridge calls.
l4 passed; l7 passed.

**Runs on the last code commit 010cb15:**
- p2a-app **44707660011**: green on attempt 2 (attempt 1: Maven Central 429 for a robolectric jar, no change).
  JVM tests incl. `P2iFitViewTest` 7/7, `P2hUnreadableTest` 7/7, `DragAndBeforeTest` 3/3; rule check; screenshots
  incl. `list-phone-05-auto-sync-off.png` and `list-tablet-05-auto-sync-off.png`.
- p2b-real-engine **44707660180**: green on attempt 3 (attempts 1 and 2: the apk job could not fetch
  `kotlin-stdlib` from Maven Central, no change). Emulator 44 of 44, compare job green.
- APK: release **`dt-android-010cb158501d`** (`dt-android-arm64-v8a.apk`).

Earlier runs of this package: 44701978982 and 44701978994 (735be90, green; f10 at 304 x 250); 44704862454 (710b957,
green) and 44704862471 (710b957, f10 failed: the preview for a new frame size was skipped while a render ran; fixed
in 010cb15).

## 5. Surprises in upstream, and open questions for the reviewer

Surprises (read, not changed):
- `DT_MIPMAP_F` is 1440 x 900 unless `highres_preview_mip` is on (`src/common/mipmap_cache.c`,
  `dt_mipmap_cache_init`): the preview pipe's picture is limited to about 1350 px for a 3:2 photo.
- On the emulator `dtb_open` of a RAW whose folder had just been deleted succeeded and `image_info` answered (l4,
  run 44704862471): darktable served it from its caches, presumably the image cache and the full mipmap of the earlier
  session on the same path. Not looked into further (submodule not checked out here).

Open questions:
1. PLAN.md 4.1 says the edit is sent "on leaving the darkroom, on 'sync now', and from the background job". With the
   switch off only "sync now" and jobs from LightPhotoLibrary send it. The protocol is unchanged, so section 4 was not
   touched; a one-line note there may be wanted ("unless the user switched automatic sync off").
2. With the switch off, opening a photo that is on the phone still asks LightPhotoLibrary for a newer XMP (as today).
   I took that as part of the user's open. Should it stop too?
3. A pinch that ends slightly above fit counts as zoomed in and runs the full pipe on the whole sensor. Snap to fit
   below, for instance, 1.1x?
4. `FIT_PREVIEW_MAX` is fixed from one edit on one phone. It could be measured like the drag picture (a time budget
   per device), at the cost of a preview size that changes between sessions.
5. The thumbnail pass can still render a photo that was just removed (wasted work, now harmless). A check that the RAW
   still exists right before `open` would avoid most of it; left out so l4 keeps showing the case.

## 6. What to try on the phone (owner), in order

APK: release **`dt-android-010cb158501d`** (`dt-android-arm64-v8a.apk`).

1. Open the heavy edit from LightPhotoLibrary. Move a slider (for example exposure) and lift the finger: the picture
   should change after about one second, with no "rendering…" line, and a second change right after should not wait.
2. Double-tap to zoom to 100 %: the picture is at once larger but soft, "rendering…" shows at the bottom, then it
   sharpens. Double-tap back to fit while "rendering…" is shown: it goes away at once.
3. Compare the picture at fit with what you know from darktable on the desktop: at fit you now see the preview pipe's
   picture (denoise and sharpening act on darktable's reduced input there, as the desktop's first picture does). Zoom
   in to judge sharpness and noise.
4. Run "timings…" with the photo open and share the text: new line "preview at the darkroom's fit size (asked … px)"
   gives the real cost of a change at fit on the phone.
5. List → menu → "LightPhotoLibrary server…": switch "sync automatically" off. Edit a photo, tap "done": the row says
   "edited here, not synced yet", the header "automatic sync is off · 1 photo not synced"; in LightPhotoLibrary the
   phone is still listed. Tap "sync now": it is sent. From the desktop, press "save edited to NAS" or "remove" for a
   photo on the phone: it is carried out.
6. Switch it on again: anything waiting is sent.

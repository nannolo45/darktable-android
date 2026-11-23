# Brief P3g: export with a progress bar

Branch `p3g-export` from `main` (`4fc2974`). PLAN.md Phase 3 item 7 ("export with R5"); mockup agreed earlier:
`docs/design/mockups/B3-Export.html`; the owner's addition of 22 Nov (progress), quoted below. Engine call today:
`export(path, options)` (`docs/design/engine-api.md` line 92), which reports nothing while it runs.

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` is binding (public repository: nothing personal; the darktable manual's export module page is the
reference for options and defaults). Never push to `main`, no pull requests; push after every deliverable.
Engine changes are expected (bridge only, never `upstream/`): each new call documented in `engine-api.md` (new
section), tested in the bridge tests, exposed in Kotlin. **Do not change the LPL contract** (PLAN.md section 4):
use what section 4 already defines for sending an export to LightPhotoLibrary (R5); if nothing usable exists, build
`this device` and `share` only and write the proposed LPL addition in the report. Do not touch `app/lpl` beyond
calling what exists. Screen code: tokens and icons only; three skins (darktable, modern, l1): screenshots in
darktable and modern; l1 reaches export from its top bar (an `export` action id exists since PUI3b, grep it).

## The owner's request (22 Nov)
While exporting the screen shows: a bar with percent and the current step in plain words (preparing, processing,
writing the file, sending to LightPhotoLibrary); for a series "photo n of N" with an overall bar (reuse P2k's
`BatchProgress`); cancel; progress stays visible when he leaves the export screen (small indicator on the
darkroom's top bar). Underneath: a progress event during an export and a cancel that works during an export. First
choice: progress from the pipeline itself (which module of how many) if upstream offers a hook that needs no source
change; otherwise progress by steps, with the processing step estimated from the photo's last measured render time,
and real byte progress for an upload. For a series the count of finished photos is exact.

## Deliverables
1. Engine: export progress (callback or polled state) and cancel during export; say which upstream hook you used
   (file and function) or why the estimate was needed.
2. Export screen as `B3-Export`: scope (this photo / the series), destination, format and the options the engine
   takes (format, quality, bit depth, max size, profile), with the darktable manual's defaults; the progress bar,
   steps, cancel, and the top-bar indicator after leaving the screen.
3. Destinations: this device (`Pictures/darktable`, as the mockup), share sheet, LightPhotoLibrary if section 4 allows.
4. The export keeps running while the app is in the foreground on another screen; a foreground service is a later
   package (say what happens when the app goes to the background).

## Tests
Bridge tests: progress events arrive in order and end at 100 %; cancel mid-export leaves no partial file; export
output unchanged against `darktable-cli` (existing compare). JVM tests of the progress state and the series counter
against the stand-in engine; screenshots (export screen idle, running, series, done; top-bar indicator) in darktable
and modern; emulator: export one photo with progress observed, cancel one mid-way, export a two-photo series.

## Docs
This brief into `docs/briefs/`; `engine-api.md` new section; `ui-spec.md` section "export"; report
`docs/experiments/P3g-export-REPORT.md` in `CLAUDE.md`'s form with CI run ids.

## CI
Add the branch to the triggers of `g0-app.yml` (bridge tests), `p2a-app.yml` and `p2b-real-engine.yml`. Stop when all
are green on the last code commit including the emulator job, or after three failed fix attempts on one problem.
Maven 429 / "could not find": `gh run rerun <id> --failed` (gh is signed in), never empty commits.

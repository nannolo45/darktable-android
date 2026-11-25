# darktable for Android: port plan (public edition)

Written 18 Nov 2026. Status: Phase 0 done; gate G0 passed on the target phone on 19 Nov 2026; Phase 1 (engine) and the app foundation started. This is the public edition; the owner keeps private notes with setup details.

- Interface mockups (three rounds) were agreed before this plan and are kept privately; section 1 summarises them.
- Code for the port: https://github.com/nannolo45/darktable-android (public); this file is `docs/PLAN.md` there.
- LightPhotoLibrary (LPL) is a separate, private library app that this app works with.
- Section 4 is the contract between this app and LPL.

## 0. Summary

1. The app is darktable's real processing engine running on the device, under a new touch interface that keeps darktable's look and its module stack. Edits are stored in the same XMP sidecar format, so phone, tablet and PC edit the same history.
2. The engine is **upstream darktable built without source changes**. All the mobile work lives beside it: a small C bridge, a generated description of every module's controls, and a Kotlin app. Updating to a new darktable version is then mostly "move the pin, rebuild, run the tests".
3. This is realistic because Termux already ships darktable 5.6.2 for Android arm64 with its whole dependency stack (F1).
4. The Android app joins LPL as another **agent**, using the API the PC agent already uses. LPL needs a few small additions (section 4.3).
5. Work is split into phases with gates. Phase 0 is a set of short experiments that can still stop or reshape the project cheaply.

## 1. Goals and agreed design

**Goals**
- Edit RAW files on the phone or tablet with no other machine involved.
- Same edits everywhere: an XMP written on Android opens in desktop darktable, and the other way round.
- Module stack as in darktable: switch any module on and off, multiple instances (new, duplicate, move, delete), history stack, snapshots, masks and blending, presets.
- Phone and tablet layouts.
- Photos come from LPL on the NAS and edits go back through LPL. LPL and the darktable app are two apps.
- Repeatable updates when darktable changes.
- Colours and icons easy to change later (section 3.6).

**Not goals for v1**
- Lighttable, import, tagging, map, print, tethering: LPL covers the library side.
- Lua scripting, the optional AI modules (`USE_AI`), G'MIC.
- Publishing on Google Play.

**Design agreed in the mockups**
- Direction B (module stack) in darktable's own look: colours taken from darktable's default theme, mid-grey surround, flat sliders, lowercase labels.
- Phone: photo on top, module groups, expandable modules, bottom tabs for modules and history. Instance, reset and preset icons appear on the expanded module.
- Tablet: desktop-like layout with history and image information on the left, photo in the centre, modules on the right.
- Filmstrip of the series in LPL's order, collapsible (hidden by default on the phone).
- Quick access panel, masks and blending, crop with draggable corners **and a movable frame**, tone equalizer graph, snapshots with a split view, export.

**Target setup**
- Phone for tuning and hands-on checks: Snapdragon 865, 12 GB RAM, Android 13. The tablet layout is built, but checked on an emulator only for now.
- Desktop side: darktable on the 5.7 development line, Windows and Linux.
- A priority list of 29 modules orders the work (section 6). Module groups in the app are configurable.

## 2. Findings that shape the plan

Checked on 18 Nov 2026 against the darktable source (release 5.6.2 and the development branch at commit `04477d3`), the Termux package recipe and the LPL repository.

| # | Finding | Consequence |
|---|---|---|
| F1 | Termux packages darktable 5.6.2 for Android arm64, built with the Android toolchain against GTK3 and about 30 libraries. Its recipe needs one linker addition (`libandroid-glob`) and turns OpenCL, OpenMP and G'MIC off. | The dependency stack is known to compile and run on Android. Termux's recipes are a reference for ours. |
| F2 | In 5.6.2, 88 of the 118 files in `src/iop` include GTK headers: each module's processing code and its desktop controls share one file. | The engine cannot be compiled without GTK unless upstream files are edited. We link real GTK3 and never start it. |
| F3 | `darktable-cli` starts the engine with `dt_init(..., init_gui = FALSE)`, and export uses `dt_dev_init(&dev, FALSE)`. | A supported no-interface path exists upstream. |
| F4 | `dt_dev_add_history_item()` does nothing without the desktop interface; `dt_dev_add_history_item_ext()` is the variant for callers without one. | The bridge manages history through the `_ext` functions and its own locking. |
| F5 | 5.6.2 has 93 processing modules, 16 of them deprecated. 73 create their controls with `dt_bauhaus_*_from_params` (498 controls), which ties each control to a named parameter. 26 have graph widgets and 11 draw or take input on the image. | Most controls can be generated. The graph and on-image parts are hand-built, module by module. |
| F6 | Parameter ranges and defaults are in the source as annotations (`$MIN`, `$MAX`, `$DEFAULT`) and compiled into each module as introspection data. | The bridge reads and writes parameters by name. |
| F7 | The engine takes `--configdir`, `--cachedir`, `--datadir`, `--moduledir`, `--library`, `--tmpdir`. Modules are separate libraries found by scanning the module folder. | Everything can live in the app's private storage with no source change. |
| F8 | Every one of the 29 priority modules has an OpenCL path. OpenCL is not an official Android interface; where the device vendor ships it, an app must declare `libOpenCL.so` in its manifest. The target phone's Adreno 650 normally ships it. | GPU acceleration is a realistic later step on this phone, optional, after the CPU path works. |
| F9 | The development branch still requires GTK3 (3.24.15) on 18 Nov 2026; the GTK4 migration is ongoing upstream. | 5.8 is very likely still GTK3. When upstream switches, the dependency recipes change, not the app. |
| F10 | LPL's hand-off is an agent protocol over HTTP: heartbeat, jobs, `checkout`, `original`, `xmp` (GET and POST with `checkout_ts`), `preview`. The conflict rule runs on the server. | The Android app can be an LPL agent with almost no server change. |
| F11 | The build workspace Claude uses can download the Android NDK and Google's Maven repository and install Ubuntu packages, but cannot reach gnome.org, Termux's package server or ghcr.io. It has 2 CPU cores. | Library sources come from GitHub mirrors. Heavy builds run in GitHub Actions. |
| F12 | Between 5.6.2 and the development branch, 9 modules changed their parameter version, among them demosaic, white balance, input colour profile, AgX, color calibration and highlight reconstruction. 4 modules are new, among them **spektrafilm**, which is on the priority list. | A 5.6.2 engine cannot read edits made with a 5.7 nightly, because demosaic and white balance are in every RAW edit. The engine must match the PC's darktable (section 2.1). |

### 2.1 Which darktable version the app carries

darktable cannot read a module's settings written by a newer version of that module. So an edit travels safely between two installations only when **both know every module version used in it**. In practice the phone's engine and the PC's darktable must be the same version line.

- **Now, for the experiments:** `upstream/darktable` is pinned to the development branch at `04477d3` (18 Nov 2026), close to the desktop's nightly.
- **When 5.8.0 is released** (expected, not announced; recent feature releases came in June and December): pin to `release-5.8.0`. The PCs move to 5.8.0 as well. Real use of the app starts from this point (gate G2 comes after it).
- **Afterwards:** PCs and app are updated together. If a PC runs a newer nightly than the app, photos touched there may not open on the phone until the app is rebuilt.
- **Guard in the app (required, tested):** before opening a photo, the bridge checks every history entry in the XMP. If a module is unknown or its version is newer than the engine's, the photo opens for viewing only with a clear message, and its XMP is never rewritten. The app also keeps the XMP it downloaded as a local backup until the edit is synced.
- A later option, made cheap by this design: an automatic weekly build of the app against the newest development branch for people who stay on nightlies. Not part of v1.

## 3. Architecture

```
darktable Android app (Kotlin, Jetpack Compose)        LPL web app (browser, unchanged technology)
  Working set (local photos, sync state, offline)   <-- link "Edit on this phone" / agent jobs
  Darkroom: module stack, history, masks, crop, snapshots, export
  Settings (LPL server, theme)
  Theme tokens and icon registry
        |  Kotlin interface "DarktableEngine" (also has a fake implementation for UI work and tests)
  JNI layer (thin)
        |
  Engine bridge (C, ours): stable API "dtbridge.h"
        |
  libdarktable + module plugins, upstream, unmodified, started without interface
  GTK3, GLib, exiv2, lensfun, lcms2, rawspeed, LibRaw ... built for arm64-v8a
```

### 3.1 Core: upstream, unmodified
- `upstream/darktable` is a git submodule pinned to a commit or release tag. Nobody edits it in place.
- It is built for `arm64-v8a` with the NDK, against real GTK3 that is linked but never initialised. First try GTK3 with only the "broadway" backend so no X11 libraries are needed; fall back to the X11 backend as Termux does.
- Build options: Lua, AI, G'MIC, camera, map, print, colord, libsecret, kwallet, PortMidi and SDL2 off. OpenMP **on** (to be proven in Phase 0; Termux turns it off). OpenCL off at first.
- `patches/` holds any unavoidable changes as an ordered series. Target is zero; every patch needs a written reason and an upstream issue or pull request where it makes sense.
- Modules, kernels, colour profiles, lens and camera databases are packed in the APK and unpacked to app-private storage on first start, then passed with `--moduledir` and `--datadir`.

### 3.2 Engine bridge (`engine/`, C)
A small API of our own, the only thing the app talks to. Upstream changes are absorbed here.

- Lifecycle: `dtb_init(paths, resources)`, `dtb_shutdown()`.
- Session: `dtb_open(raw_path, xmp_path)` imports into a small private library and loads the sidecar after the version guard (2.1); `dtb_close()` writes it.
- Stack: list module instances in pipeline order with name, instance label, on/off, parameters by name, blend settings; set a parameter; enable or disable; new, duplicate, delete, move, rename instance; reset; presets and styles.
- History: list, go to a step, compress, undo and redo.
- Render: asynchronous, cancellable. A fast low-resolution preview first, then the visible region at screen scale, the way darktable's two darkroom pipelines work. A new change cancels the render in flight.
- Masks: create and edit circle, ellipse, gradient, path and brush shapes in image coordinates; group them; parametric settings through the blend parameters.
- Snapshots, histogram and colour picker data, export to a file, write the XMP now.
- Events to the app: render finished, history changed, error, log line.

One engine thread owns the image being edited; all changes and renders are queued on it.

### 3.3 Generated control descriptions (`schema/`)
The labels, order, sections, tabs, units and soft ranges of each module's controls exist only in darktable's desktop code. To avoid copying them by hand:

- An out-of-tree darktable plugin runs inside a normal Linux darktable in CI (virtual display), opens the darkroom and walks every module's control tree. For each control it records type, label, the parameter it is bound to, hard and soft range, step, digits, format and list entries, plus sections and tabs.
- It then flips each list and switch through its values and records which other controls appear or disappear, giving visibility rules.
- Output: `ui-schema.json` for that darktable version, with translations from darktable's `.po` files. The app builds controls from this file.
- `schema/overrides/*.json` holds small hand corrections. A new upstream version produces a schema diff that shows exactly which controls changed.
- Measured in experiment S5: 87 % of sliders, lists and switches are bound to a named parameter and come out complete; 143 of 145 visibility rules were plain conditions that held in every observed state. The panel also depends on the kind of image (Bayer, X-Trans and non-raw differ in 11 modules), so one schema per image class is generated. A few widget details have no public accessor in darktable; the extractor reads them from a generated copy of darktable's private widget structures and its build fails if they change.

### 3.4 Module support tiers
| Tier | What it means | Covers |
|---|---|---|
| 0 | Always renders exactly as on desktop, can be switched on and off, reset, duplicated, blended. No parameter editor. | Every module, including deprecated ones, from day one |
| 1 | Generated sliders, lists, switches, tabs | Modules with bound controls |
| 2 | Hand-built graph editor | Curves, levels, tone equalizer, contrast equalizer, color zones |
| 3 | Hand-built on-image tool | Masks, crop, rotate and perspective, pickers, retouch |

An edit made on desktop with a module the phone cannot edit yet is never lost or altered: it renders, and its parameters pass through untouched.

### 3.5 App (`app/`, Kotlin + Jetpack Compose)
- Working name `dt-android`, package `app.dtandroid`. It is an unofficial port and must not present itself as an official darktable release if it is ever shared.
- Modules: `engine-jni`, `darkroom`, `lpl` (agent client and sync), `ui-theme`, `ui-icons`, `app`.
- A second, small app is built from the same project: the **LightPhotoLibrary shell** (module `lpl-shell`, package `app.dtandroid.lplshell`). It shows LPL's web interface full screen from its own icon, on the server address the user enters, so LPL behaves like an app on plain HTTP without certificates or special DNS (decided 19 Nov 2026; browsers only install a web app over HTTPS). It contains no darktable code and no engine. Brief: `docs/briefs/P2c-lpl-shell.md`.
- Screens depend on the `DarktableEngine` interface, never on JNI directly. A fake engine lets interface work and tests run without the native core.
- Phone and tablet share all logic; only layout differs (window size classes).
- Target device for tuning: Snapdragon 865, 12 GB, Android 13. Minimum Android version proposed: 10; arm64 only. Final floor after gate G0.

### 3.6 Colours and icons that are easy to change
- **Tokens**: every colour, text size, spacing, radius and control size is a named token in one file (`ui-theme/tokens.json`), initially darktable's default greys as mocked. Screens may only use tokens; CI fails on literal colours or sizes in screen code.
- **Icons**: screens ask for icons by meaning (`Icon.Power`, `Icon.Instances`, `Icon.Reset`, `Icon.Presets`). The drawings are SVG files in one folder (`ui-icons/svg/`). Replacing an icon is replacing one file.
- **Trying a palette without rebuilding**: debug builds also read an optional `theme.json` from the app's folder that overrides tokens.
- Layout changes (where things sit) remain code changes.

### 3.7 Storage and sync
- App-private folders: `work/<lpl id>/<original filename>` and its `.xmp`; darktable's config, small library database and caches (cache size capped).
- The RAW is downloaded once and never uploaded. Only the XMP and a rendered preview go back.
- The LPL server may be reached over plain HTTP (a home-network or Tailscale address, as today) or HTTPS. The app allows plain HTTP only for servers the user has added, and prefers HTTPS when the server offers it.
- Downloads and uploads run in a foreground service with a notification, because the phone vendor's Android version stops background work aggressively. A background job retries pending syncs when LPL is reachable again; the app explains how to exempt it from battery optimisation if syncs are being stopped.
  - **Built (brief P3k, 24 Nov 2026):** `SyncService`, a foreground service of type `dataSync`, runs only while LPL work is in flight: edits waiting to be sent (XMP, preview, versions; only with "sync automatically" on), exports the user asked for that wait for their server, batch downloads already started. It is started while the activity is still visible (`onPause`, "done", a batch starting), because Android 12 and later refuse a foreground service started from the background; it never polls for new work and stops by itself when nothing is left, or when nothing can progress (server unreachable, or three sync passes without progress): then the background job (`SyncJob`, JobScheduler, network required, back-off) takes over. The job does its own work and does not start the service. A photo open in the darkroom is never synced by it. One notification ("Sending edits · n of N" / "Downloading · n of N", a bar, "stop" for downloads) on its own channel, beside the export's (P3i). The LightPhotoLibrary settings show whether the app is exempt from battery optimisation, with a button to the system's list and a note about the phone makers' own background settings.

### 3.8 Performance approach
- Preview pipeline from a downscaled input for immediate feedback, full pipeline only for the visible region at screen scale, cancel on new input.
- OpenMP on all cores; darktable's resource level tuned for phones; tiling for large images.
- OpenCL later and opt-in per device (F8).
  - **Built (brief P4b, 25 Nov 2026):** the engine is built with OpenCL and starts without it; Settings › processing › "use the GPU" starts it and keeps it only if a test render agrees with the CPU's; "processing memory" (normal / more / less) chooses darktable's resource values, pipeline cache sizes and decoded photos kept by the phone's total memory; "measure speed" renders the public test RAW and copies the times. Report: docs/experiments/P4b-speed-REPORT.md.
- **Measured at gate G0** (24 MP RAF, default edit, single runs): with 4 threads, preview after an exposure change 0.09 s, screen-sized region at 100 % after a change 0.22 s, full-size render 2.6 s, full-size JPEG export 3.1 s. With all 8 threads the interactive steps were slower (preview 0.56 s), because the slow cores hold up the evenly split work. The engine therefore runs on the fast cores only, pinned.
- **Targets to confirm at gate G0 on the target phone** (targets, not measurements): preview after a slider move under 0.3 s; visible region under 3 s for a 40 MP RAF; full export under 60 s.

### 3.9 Module layouts, presets and styles (added 19 Nov 2026)
The darktable user manual is the reference for behaviour and defaults. Where the app does something else, the difference is written down with its reason.
- **Module layouts ("workspaces").** The app uses darktable's own layout presets: the same format as the desktop's "manage module layouts" (groups, the modules in each, the quick access panel's controls, the options for search and the active group). The default is darktable's "workflow: scene-referred" layout, taken from upstream by a repeatable tool, not typed by hand. Layouts are edited in the app (groups, order, modules per group, quick access controls) and a layout made on the desktop can be brought in. Several layouts can exist; one is current.
- **White balance.** As the manual says for the scene-referred workflow, the white balance module stays on and is normally left alone; colour temperature is set in color calibration. The app's default quick access panel therefore carries no white balance control, and the white balance panel says so when color calibration is active, as desktop does.
- **Presets** (per module) and **styles** (several modules at once, for example film-look packs distributed as `.dtstyle` files) live in the app's own darktable database, in the same form as on the desktop, because the engine is darktable. They are brought in as `.dtpreset` and `.dtstyle` files (one or many at once) and applied from the darkroom; a style also works when it uses modules the app has no editor for yet, because the engine renders every module (tier 0). Auto-applied presets work as on desktop.
- **Same setup on every device (decided 19 Nov 2026, as a later step):** the desktop's presets, styles and layouts travel through LPL automatically (request R11), so a style added on a PC appears on the phone and on the other PCs without copying files by hand. Import by hand comes first and stays; the automatic route is built after editing works end to end (gate G2).
  - **How updates are applied (owner, 19 Nov 2026):** the files travel through LPL without any copying by hand, but a device takes them in when the user asks ("update styles, presets and layouts"), not silently in the background; the app shows when something newer is waiting. When only one side changed, the newer version is taken. When the same item was changed on two devices since the last update (a conflict), the default is to ask which one to keep; a setting can change that to "newest wins". A deletion on one device is offered on the others at the next update and never applied silently. Calls and rules: 4.5 (R11).
- **Feel while dragging.** The stand-in engine imitates the delay measured at gate G0 (about a tenth of a second per update), and the owner found that lag noticeable in the interface preview. Target for the real engine: while a slider is dragged the photo follows within about 50 ms, using a smaller picture during the drag and the full one when the finger lifts; measured on the phone before gate G2.

## 4. LPL integration (for the LPL instance)

Two apps: LPL in the browser, darktable as a native Android app. Neither embeds the other.

### 4.1 What works with LPL as it is today
The Android app behaves as an LPL agent, like `agent/lpl_agent.py`:

| Step | Existing endpoint | Android behaviour |
|---|---|---|
| Identify | `POST /api/agents/heartbeat` | `id` = `android-<install uuid>`, `name` = the name the user gives this device in the app's settings (asked when the server is first added, for example "Phone"; never the hardware model), `os` = `"android"`. Heartbeat every 2 s **while the app is in the foreground or its sync service runs** (brief P3k; the service runs only while LPL work is in flight, see 3.7); a last heartbeat when it leaves and when the service stops. `state.working_set` uses the same item shape and statuses as the PC agent (`downloading`, `opening`, `ready`, `editing`, `dirty`, `syncing`, `synced`, `error`); `darktable_running` = darkroom open; `bridge_alive` = true; `version` = app version + engine version. |
| Take a photo | `GET /api/photos/{id}/checkout`, `/original`, `/xmp` | Stores `server_time` as `checkout_ts`. Files keep the original filename inside a per-photo folder. |
| Send the edit back | `POST /api/photos/{id}/xmp?checkout_ts=…` | On leaving the darkroom, on "sync now", and from the background job. If the response has `X-LPL-Merged: 1`, the app reloads rating, labels and tags from the returned XMP. |
| Preview | `POST /api/photos/{id}/preview` | A 1920 px JPEG rendered by the engine after each synced edit. |
| Jobs | jobs from the heartbeat, `POST /api/jobs/{id}/done` | Handles `edit`, `open`, `sync`, `release` addressed to its agent id, when the app is open. Jobs that come with a heartbeat sent while the app is not on the screen (its last one, the sync service's, an export's): `sync` and `release` are carried out at once when none of their photos is open in the darkroom; `edit`, `open` and the others are kept on the phone (`lpl/kept-jobs.txt`) and carried out when the app is in the foreground again (brief P3k). |
| Auth | header `x-lpl-token` | Server address and password entered once in the app, kept in the Android keystore. Intended for LAN and Tailscale only, as today. |

As the server behaves (found when the app's client was run against it, 19 Nov 2026): a job can carry `{"all": true}` instead of a list of ids; a job is handed out once; every XMP post answers with `X-LPL-Merged` 0 or 1, and merging only happens when `checkout_ts` is greater than 0; the app checks a password with `GET /api/status`; a `release` job for the photo that is open in the darkroom is reported `failed` by the app.

#### Batches and Stop (brief P2k, agreed with LPL; everything is opt-in by `caps`)
A heartbeat from the app has, in addition, `"caps": ["batch", "cancel", "open"]` and `"transfers": [...]`; the answer may have `"cancel"`. A server that does not know them ignores both and sends no `cancel`: the exchange is then exactly the one above, and the app handles an `edit` job as before.

| Where | Shape | Rule |
|---|---|---|
| heartbeat request | `"transfers": [{"job": 41, "state": "running", "arrived": [1201,1202], "present": [1190], "failed": [], "skipped": [], "photo": 1203, "bytes": 18350080}]` | One entry per **edit** job the device holds, from the heartbeat after it received the job until `/done` for it was answered; `[]` when none. A job the server thinks is running and that is not listed is taken for lost. `state`: `queued`, `running`, or ended `stopped`, `done`, `failed`. The four lists are cumulative: `arrived` copied by this job (RAW and XMP there), `present` already on the device, `failed`, `skipped` left out because the server said so. `photo`, `bytes`: the photo being copied now and the bytes of its original on the device; `null` and `0` between files. The device sends no totals. At least every 2 s while a batch runs; when a job ends, one heartbeat at once with the final `state`, then `/done`. |
| heartbeat answer | `"cancel": {"jobs": [41], "photos": [1207, 1210]}` | Always the whole list (replace, never add up), repeated until confirmed. `jobs`: stop these: check between files and between download chunks (at most 256 KiB per read), keep what arrived, delete the partial file, remove the half-made working-set entry, do not open the darkroom, report `stopped`. An unknown job id is ignored. A job still waiting on the device ends as `stopped` without starting. `photos`: do not copy these in any job; the one being copied is given up and the next follows; they are listed under `skipped`. A Stop acts within about 2 s of the heartbeat that carries it. A Stop that was acted on stays; one that was not acted on yet and is no longer in the list is lifted. |
| edit job payload | `"open": false`, `"ids"` once each | `false`: copy the photos, do not open the darkroom (they get status `ready`). With `batch`, `ids` has each id once. Without `open` in the payload the darkroom opens, as before. |
| `POST /api/jobs/{id}/done` | `{"status": "done" \| "failed" \| "stopped", "arrived": [...], "present": [...], "failed": [...], "skipped": [...]}` | The lists are optional. |

On the phone: the photo list shows one bar "photo n of N" (N = the ids of the jobs held; no time estimate, the device does not know sizes ahead) with a "stop" button that ends the device's own jobs as `stopped` and keeps what arrived; afterwards "stopped · n of N arrived". Photos copied by a batch have status `ready` (the word LPL's PC agent uses) until the darkroom opens them. **Foreground limit (changed by brief P3k):** heartbeats run while the app is in the foreground or its sync service runs; a batch already started keeps the service running until it ends, so it goes on and is reported with the app in the background. A batch whose copying carries on while the app is away is reported by the next heartbeat; jobs still held are listed again, and nothing is reported `done` that is not. A process that was killed holds nothing: LPL then shows the job as interrupted.


The conflict rule stays on the server and is unchanged: LPL wins for rating, labels and tags changed during the edit; darktable's history always comes from the editor.

### 4.2 How the hand-off looks to the user
- **From the phone or tablet:** in LPL's web UI on Android, a photo, a selection or a series shows **"Edit on this phone"** next to "Edit in darktable". It is a link that opens the darktable app (R1). The app downloads RAW + XMP with progress and opens the darkroom, with the rest of the list or series as filmstrip.
- **From the PC:** "Edit in darktable" lets the user pick the phone as target (R3). The job waits and is taken the next time the darktable app is opened.
- **Back to LPL:** "done" in the darkroom syncs and returns to the app's working set; "open in LightPhotoLibrary" opens the photo's page in the browser (R2).
- Without a connection the photo shows "waiting to sync" in the app's working set and can still be opened and edited there.
- **Two ways to run LPL on the phone:** in any browser, or in the LightPhotoLibrary shell app (3.5). The shell hands the "Edit on this phone" link to the darktable app itself; the darktable app's "open in LightPhotoLibrary" opens the shell when it is installed, otherwise the browser. The shell adds `LPLShell/<version>` to its user agent so LPL can tell it runs inside it (no install hints there).

### 4.3 Changes requested from LPL
| # | Where | Request | Needed for |
|---|---|---|---|
| R1 | Web UI | On Android browsers, show "Edit on this phone" on the photo page, the phone selection bar and the board. It navigates to: `intent://edit?server=<encoded origin>&ids=<comma list>&start=<id>&series=<id>&title=<encoded name>#Intent;scheme=lpldt;package=app.dtandroid;S.browser_fallback_url=<encoded help page>;end`. `ids` in display order (series order when a series is open, at most 200), `start` = the photo to open, `series` and `title` optional. No job is created. Never put the password in the link. | Phase 2 |
| R2 | Web UI | Deep links: opening `<origin>/?photo=<id>` shows that photo's page, `<origin>/?series=<id>` opens that series. The web UI has no URL parameters today. | Phase 2 |
| R3 | Web UI + server | Agent picker when more than one agent is known. Today `job_create` picks the agent only when exactly one is online, so with a PC and a phone the job has no target and the first heartbeat takes it. "Edit in darktable" should default to a PC agent (`os != "android"`); jobs for an Android agent may wait until it comes online. | Phase 2 |
| R4 | Server | `GET /api/photos/{id}/original` must answer HTTP `Range` requests so interrupted mobile downloads can resume. Please verify with the installed Starlette version and add a test. | Phase 2 |
| R5 | Server | Export upload: `POST /api/photos/{id}/export?filename=…` with the image as body, stored in an export location LPL owns (proposal: `EXPORT_ROOT`, default a folder that the scanner skips), returning the stored path. Optional `series_id` and `position` so series exports can be numbered later. | Phase 3 |
| R6 | Web UI | Show per-photo state from any agent's `working_set` with the device name ("editing on <device name>", "waiting to sync"). This is how the web UI learns what the phone did. | Phase 2, small |
| R8 | Web UI | Name the device everywhere instead of saying "PC". Each agent's own name (set by its user on that device: "Desktop", "Laptop", "Phone") is what LPL shows in actions, states and messages ("Send to Desktop", "Remove saved copies from Laptop", "on Phone: waiting to sync"); general texts say "device"; the "PC" tab becomes "Devices". Agents with equal names are told apart by operating system. LPL does not rename agents. | Phase 2 |
| R9 | Web UI | Behave like an app on the phone: back button and back gesture step out of the current thing (sheet, photo, folder, tab list) through history states instead of leaving LPL; "Install app" in settings when the browser offers it, menu guidance where it does not (Firefox), and one line saying an install needs HTTPS when the page is on plain HTTP; safe-area and pull-to-refresh fixes where needed. Inside the shell app (user agent contains `LPLShell/`) no install hints; "Edit on this phone" stays. | Phase 2 |
| R10 | Web UI | The desktop's tabs on the phone layout: a tabs button with the count, a list of the same tabs (same titles, switch, add, close, rename), each tab keeping its own view, filters and sort, state shared with desktop. | Phase 2 |
| R11 | Server + web UI + PC agent + app | Carry darktable presets, styles and module layouts between devices automatically. **Specified in 4.5** (24 Nov 2026; mockups approved the same day). The items travel as darktable's database rows in JSON rather than `.dtpreset`/`.dtstyle` files, so that every device can compare them exactly. | Phase 3 (owner started it before gate G2) |
| R12 | Web UI | Done 19 Nov 2026: "New series" on the phone's Series tab with an in-page name field. | Phase 2 |
| R13 | Server | Hand a job out again after a while when the agent that took it never reported it done (today a job is handed out exactly once, so a job taken by an app that then dies is lost; the Android app keeps such jobs on disk as a workaround). | Later, small |
| R14 | Server | A call that only checks the `x-lpl-token` header, so adding a server in the app does not depend on `GET /api/status`. | Later, small |
| R15 | Server + web UI + both agents | **Who edited last, and what changed** (owner, 19 Nov 2026). Every XMP post names the posting device (`agent=<agent id>` next to `checkout_ts`; the PC agent and the Android app both send it). The server records an edit event per accepted post: photo, device id and name at that time, time, and a summary it computes from the XMP's darktable history: the modules in use (operation, instance name, on or off) and, against the previous recorded XMP of that photo, which modules were added, removed, switched or changed. A changed XMP found by the scanner that no agent posted is recorded as edited outside LPL. `GET /api/photos/{id}/edits` lists the events newest first; the photo's detail carries the last one. The web UI shows "last edited on <device> · <time> · <modules>" on the photo and the list of edits behind it. Module names for display come from `schema/module-names.json` in the darktable-android repository (operation to English name). Parameter values are not decoded; the level is modules. | Phase 2 |
| R16 | Web UI + both agents | **Progress of a sync** (owner, 19 Nov 2026). A working-set item in the heartbeat may carry `step` (`downloading`, `sending edit`, `rendering preview`, `sending preview`) and `progress` (0 to 1). The web UI shows a progress bar with the step on the photo and in Devices while a device downloads or syncs it, and a plain state otherwise. Agents that do not send the fields keep working. | Phase 2 |
| R17 | Server + web UI + app | **Versions (duplicates), P3f; opt-in by `caps`.** Files as desktop: `IMG_01.RAF.xmp` is version 0, version n is `IMG_01_nn.RAF.xmp` (two digits, three from 100; a number is never reused); the version's optional name is `darktable:version_name` in its XMP. The app adds `"versions"` to heartbeat `caps`. **`GET /api/photos/{id}/checkout?agent=<install id>`**: only for an agent whose caps have `versions` the answer adds `versions: [{n, name, mtime, size, has_file}]` (version 0 included), `cover` (n) and `next`; LPL notes which numbers that agent was told (for 409). **`GET /api/photos/{id}/xmp?version=n`**, **`POST /api/photos/{id}/xmp?version=n&checkout_ts=..&agent=..`** (answer and `X-LPL-Merged` as for version 0), **`POST /api/photos/{id}/preview?version=n`**, **`DELETE /api/photos/{id}/xmp?version=n`** (n >= 1; LPL moves the file to its backup folder), **`POST /api/photos/{id}/cover?version=n`**; no `version` = 0, so every old call is unchanged. **`409 {"error":"version_exists","next":n}`** when a posted number exists and the agent was not told of it at its last checkout: the app renames its file to `next` and posts again; for a number the app itself sent earlier it asks `/checkout?agent=` again (which notes it) and posts the same number again. Heartbeat `working_set` items may carry `version` (n >= 1, the version being edited). **R1 link:** optional `version=<n>` (0..999): the version to open first; without it the app opens LPL's cover. Server without the cap: the app lists no versions, offers no duplicate while connected, and keeps versions made offline as local-only, never posted. App side: [P3f report](experiments/P3f-versions-REPORT.md), design `docs/design/P3f-versions.md`. | Phase 3 |
| R18 | Server + web UI + app | **Exports from the app, P3i; opt-in by `caps`** (LPL side: LPL branch `exports-r18`). The app adds `"exports"` to heartbeat `caps`; the heartbeat answer for a device that announces any caps carries `server_caps` (e.g. `["versions","exports"]`); a missing `server_caps` = an older server, the app keeps the export waiting. **`POST /api/photos/{id}/exports?agent=<install id>&version=<n>&name=<file name>[&size=<bytes>]`**, body = the file bytes (streamed, any content type) -> `200 {"export_id": n, "path": "exports/..."}`; `version` absent = 0; `name` a bare file name with extension jpg, jpeg, png, tif, tiff or webp whose first bytes match; a taken name becomes `name (2).ext`; `size` given and different from the bytes received -> 400, nothing stored. Errors: 400 bad name/format/version/empty/size, 404 unknown photo, 413 over `LPL_MAX_EXPORT_MB`, 401. No resumable upload: a cut-off upload is repeated whole. Also `GET /api/photos/{id}/exports`, `GET .../exports/{eid}/file`, `DELETE .../exports/{eid}` (web UI). Heartbeat `working_set` items may carry `step: "exporting"` with `progress` while the file is sent. App side: [P3i report](experiments/P3i-export-followup-REPORT.md). | Phase 3 |
| R7 | Server, later | Per-device tokens (`POST /api/agents/pair`) instead of the shared password in the header. | Later, optional |

The Android app only accepts an `lpldt://edit` link whose `server` is one the user has added in the app; an unknown server asks for confirmation first.

### 4.4 Rules both sides rely on
- **XMP is the only thing that carries the edit.** The Android app never writes a RAW and never writes to the NAS except through LPL's API.
- **Version rule (2.1):** phone engine and PC darktable must be the same version line. The agent reports its engine version in `state.version`; LPL may show it and may warn when two agents report different versions.
- XMPs written by the app use darktable's sidecar format because the engine writes them. LPL keeps treating `darktable:history` and `darktable|…` tags as opaque.
- Series order comes from LPL (the `ids` order in R1). The app does not reorder series.
- The Lua bridge and the PC agent are unaffected.

### 4.5 Shared styles, presets and module layouts (R11, specified 24 Nov 2026)
Owner's decisions: 3.9; mockups approved 24 Nov 2026 (PCs update from LPL's Devices tab only, not from inside darktable; a conflict offers "keep both"; "keep" after a deletion elsewhere keeps the item on that device only). Everything is opt-in: a device adds `"shared"` to heartbeat `caps`, and the server lists `"shared"` in `server_caps`. A device or server without it behaves as before.

**What is shared.** Styles (all of them); presets of processing modules (the operations in `schema/module-names.json`) with `writeprotect = 0`; module layouts (presets of the `modulegroups` lib with `writeprotect = 0`). Not shared: built-in presets; **the styles darktable ships** (names starting `_l10n_`; darktable keeps them as ordinary rows of `data.styles`, about 540 of them); desktop's **"last modified layout"** (a `modulegroups` user preset desktop rewrites on every layout edit; stored under its translated name, so both sides exclude every translation listed in `docs/design/R11-excluded-names.json`); presets of other lib modules; which layout is active on a device; the app's favourites and recent list. A rename travels as a new item plus a deletion.

**Item key.** `kind` (`style` | `preset` | `layout`) + `operation` + `op_version` + `name` for presets and layouts (darktable's unique index), `kind` + `name` for styles (`operation` "" and `op_version` 0).

**Body** (what travels; JSON, UTF-8). Columns are read by name from darktable's `data.db`; a column the receiving side does not have is ignored, one it has and the body lacks is NULL.
- preset, layout: `{"kind": …, "row": {<every column of data.presets except writeprotect>}}`.
- style: `{"kind": "style", "style": {"name", "description", "iop_list"}, "items": [<every column of data.style_items except styleid>, … sorted by num]}`.
- Values: INTEGER as a JSON number; REAL as a string formatted `%.17g` (Python `format(x, ".17g")`); BLOB as lowercase hex; TEXT as is; NULL as null.
- The app keeps its layouts in `layouts.json`, not in `data.db`; it builds and reads a layout's row with its `LayoutCodec`, byte for byte as desktop stores `op_params` (to be checked against a desktop-made row).

**Hash** = lowercase hex SHA-256 of the canonical JSON (keys sorted, separators `,` and `:`, no spaces, non-ASCII kept as UTF-8) of the **core object** — `{"kind", "row": {core columns}}` or `{"kind": "style", "style": {core}, "items": [{core} … sorted by num]}`, a missing column as null — so that devices whose darktable has a column more or less still agree:
- preset: name, description, operation, op_version, op_params, enabled, blendop_params, blendop_version, multi_priority, multi_name, multi_name_hand_edited, model, maker, lens, iso_min, iso_max, exposure_min, exposure_max, aperture_min, aperture_max, focal_length_min, focal_length_max, autoapply, filter, def, format.
- layout: name, operation, op_version, op_params.
- style: style.name, style.description, style.iop_list, and per item num, module, operation, op_params, enabled, blendop_params, blendop_version, multi_priority, multi_name, multi_name_hand_edited.
- Shared test vectors (a preset, a layout, a style with a REAL, a BLOB, a NULL and non-ASCII text, each with its expected hash) live in `docs/design/R11-hash-vectors.json` in this repository (made by the reference implementation `tools/r11/r11_hash.py`) and are copied into LPL's tests; both sides must pass them.

**What a device keeps** per item: `id`, `rev` (the server's revision it last sent or took in), `local_hash` (its own hash of the item right after that), `state` (`ok`, `kept` = kept here after a deletion elsewhere, `unusable` = module version newer than this engine, `conflict`). A local change = the device's present hash differs from `local_hash`. Comparing with the device's own hash, never the server's, stops ping-pong between devices whose rows differ outside the core fields.

**Server calls** (header `x-lpl-token`; `agent` = the agent id):

| Call | Rule |
|---|---|
| `GET /api/shared?agent=<id>&since=<seq>` | `{"seq": n, "items": [{id, kind, operation, op_version, name, rev, seq, hash, changed_at, device_id, device_name, deleted, size}], "conflicts": [{item_id, changed_at, deleted}]}`: items whose `seq` > `since` (deleted ones too), and the open conflicts of that agent. `seq` is one counter for all items; `since=0` lists everything. No bodies. |
| `GET /api/shared/{id}` | `{…meta, "body": {…}}` of the current revision. |
| `PUT /api/shared?agent=<id>` | Body `{kind, operation, op_version, name, hash, changed_at, base_rev, body}` (`base_rev` 0 = the device has no revision yet). New key: stored as rev 1. Same key and `base_rev` = current rev (or the hash equals the current one, whatever `base_rev`): stored as rev+1, or nothing changes when the hash is equal; a deleted item comes back. Otherwise the item changed elsewhere: the server stores this copy as the agent's **conflict candidate** (one per agent and item, replaced by a newer PUT) and answers `409 {"error": "changed_elsewhere", "item": {…meta}}`, unless the agent's policy is `newest` (below), in which case it decides at once and answers 200 with what it decided. 200 answer: `{id, rev, seq, "decided": null \| "mine" \| "theirs"}`. |
| `DELETE /api/shared/{id}?agent=<id>&base_rev=<n>` | `base_rev` = current rev: the item becomes a deletion (rev+1; the last body is kept for "keep" and for history). Otherwise a conflict candidate that says "deleted", 409 as above. |
| `POST /api/shared/{id}/resolve` | `{"agent": …, "choice": "mine" \| "theirs" \| "both"}` for that agent's open conflict. `mine`: the candidate becomes the current revision. `theirs`: the candidate is dropped. `both` (not for a deletion): the candidate becomes the current revision and the previous content is stored as a new item named `<name> (<device name of the previous revision>)`. Answers `{"items": [{…meta}]}` that changed. |
| `PUT /api/shared/state?agent=<id>` | `{"items": {"<id>": {"rev": n, "state": "ok" \| "kept" \| "unusable"}}, "policy": "ask" \| "newest"}`, the whole list each time (replace). Sent after an update, after uploads, and once on start. The phone sends its own policy; for a PC the policy is set in LPL's settings and the agent's `policy` is ignored. |
| `GET /api/shared/devices` | For the web UI: per agent with `shared` in caps, what it is waiting for and its open conflicts; per item, which agents have the current rev. |
| `POST /api/shared/update` | Web UI, PC agents only: `{"agent": …, "ids": [...], "delete": [...], "keep": [...]}` makes a job `shared-update` with that payload for the agent. |

**Agreed after the first build (24 Nov 2026):** `changed_at` is seconds since 1970, a JSON number. The server recomputes the hash of every uploaded body and answers `400 {"error": "hash_mismatch"}` when it differs (a bug to log; the item is retried at the next pass, it is not a conflict). `DELETE` takes an optional `changed_at` (seconds) for newest-wins. `DELETE` answers like `PUT`. In `GET /api/shared` a conflict's `deleted` means the asking device's own candidate is a deletion. An upload to a deleted item revives it only from the deletion's rev (otherwise 409). After a successful PUT, DELETE, `mine` or `both` the server records that device as up to date at the new rev. `both` is refused when either side is a deletion; a taken copy name gets " 2", " 3". The PC agent never reports `unusable` (desktop keeps presets of other module versions and ignores them). A device clears its `unusable` marks when its engine version changes, so those items are offered again.

**Waiting** for a device = items whose current rev is above the rev in its state, except: a deletion of an item the device does not have; an item it reported `kept` or `unusable` at that rev; an item in conflict for it (listed apart). A deletion waits as an offer: the device's choice is `delete` (it deletes the item, state `ok` at the deletion's rev) or `keep` (it keeps it, state `kept`; it is not offered again; a later local change of it is a PUT with `base_rev` = the deletion's rev, which brings it back for everyone).

**Uploads** are automatic (the app respects "auto sync off"): a device PUTs or DELETEs its local changes, newest first, after a change is seen. An item in `conflict` is not uploaded again until it is resolved. The phone resolves in its own dialog with `/resolve`; a PC's conflicts are resolved in LPL's web UI, which calls `/resolve` for that agent. A device learns of a `theirs` resolution because the conflict is gone and the current hash is not its own: the item is then waiting and the next update overwrites the local copy; until then it is not a local change.

**Taking in** happens only when the user asks: on the phone with Update in the review sheet; on a PC with "Update <device>" in LPL's Devices tab, which sends the `shared-update` job. The PC agent never writes `data.db` while darktable runs (darktable opens it with `journal_mode = MEMORY`, `synchronous = OFF`): it holds the job and applies it when darktable has closed, reporting `shared_update: "waiting_for_darktable" | "applying" | null` in the heartbeat `state`, then `/done`. It applies by column name in one transaction (preset: insert or replace on name + operation + op_version with `writeprotect = 0`; style: delete the style of that name and its items, insert the new ones; deletion: remove the row or style), then sends its state. On the phone the bridge applies (`dtb_shared_apply`, `dtb_shared_delete`); a preset or style item whose module version is newer than the engine's is not applied and is reported `unusable` ("needs a newer darktable").

**Finding local changes.** App: a bridge snapshot (`dtb_shared_snapshot` -> every shared item with its body and hash) after anything that changes presets, styles or layouts, and on each sync pass. PC agent: a read-only snapshot of `data.db` (sqlite backup into memory, `mode=ro`) when darktable closes and every 10 minutes while it runs; a snapshot that fails is skipped. A rename is seen as one new item and one deletion.

**Web UI.** Devices tab: per device a line "n styles, presets, layouts waiting" and, opened, the waiting list (checkboxes; conflicts with mine / theirs / keep both; deletions with delete here / keep), "Update <device>" with the note that darktable must be closed for it to be applied; a "Shared through LPL" overview (item, kind, last change by and when, which devices have it). Settings: "Same item changed on two devices: ask me / newest wins" for PCs.

## 5. Repository and update procedure

Repository: `nannolo45/darktable-android` (public, decided 18 Nov 2026), licence GPL-3.0-or-later like darktable.

```
upstream/darktable     git submodule at a pinned commit or release tag, never edited
patches/               ordered patch series, target: empty
deps/                  one pinned recipe per library (version, hash, options), NDK cross files
engine/                bridge: include/dtbridge.h, src/, tests/
schema/                control extractor plugin, overrides/
app/                   Android project (modules in 3.5)
tools/                 bump-upstream, render-compare, schema-diff
.github/workflows/     deps, engine tests on Linux, schema, app build, release
docs/                  PLAN.md (this file), UPDATE.md, briefs/, experiments/
CLAUDE.md              rules for coding agents
```

- Prebuilt dependencies are cached as a release file keyed by the hash of `deps/`, so normal builds do not recompile GTK.
- APKs are published as GitHub release files.
- The repository is public, so the long dependency builds run on GitHub's standard runners without a minutes limit.

**Updating to a new darktable version (`docs/UPDATE.md`)**
1. `tools/bump-upstream <tag or commit>` moves the submodule and re-applies `patches/`.
2. CI rebuilds; `deps/` only changes if upstream raised a minimum version.
3. Engine tests on Linux: the reference set of RAW + XMP files must render the same through the bridge as through that version's `darktable-cli`.
4. `schema-diff` lists new, removed and changed controls. Tier 1 picks them up automatically; Tier 2 and 3 modules that changed are flagged for manual work.
5. Bridge compile errors show where internal functions changed; fixes stay inside `engine/`.
6. Build the APK, run the device checklist, release. PCs and app move together (2.1).

## 6. Phases and gates

Each work package is one branch and one pull request with stated acceptance tests.

**Phase 0: experiments (decide before building)**
| | Experiment | Passes when | Where |
|---|---|---|---|
| S3 | Bridge prototype on Linux: open, change a parameter, render a region, add an instance, write the XMP, version guard | Output equals `darktable-cli`; desktop darktable opens the XMP with the same history | Workspace (started first) |
| S5 | Control extractor | `ui-schema.json` covers the bound controls, with visibility rules for a sample checked by hand | Workspace, after S3 |
| S1 | Build dependencies + darktable for arm64 from our own recipes | A command-line export runs on the phone and matches the desktop result | GitHub Actions |
| S6 | GTK3 with the broadway backend only | Builds and links; else switch to the X11 backend and record the size cost | With S1 |
| S2 | OpenMP with the NDK | Multi-core speed-up measured on the phone; no crashes over the reference set | With S1 |
| S4 | Load module plugins unpacked from the APK into app storage | All plugins load on Android 13 | Test APK |

**Gate G0:** the owner installs a test APK on the target phone that opens a RAF, moves exposure and exports. Decision on targets in 3.8 and whether to go on.

**Phase 1: engine and build system.** Full bridge API with tests on Linux, JNI layer, dependency cache, CI. Gate G1: bridge test suite green; API reviewed and frozen for Phase 2.

**Phase 2: darkroom MVP with LPL.** Working set, settings, agent client (4.1), link hand-off, sync; module layouts as in darktable with an editor and import (3.9); the LightPhotoLibrary shell app (3.5); darkroom on phone and tablet with Tier 0 and Tier 1 modules, on/off, instances, history, before/after, quick access, configurable module groups; theme tokens and icon registry; preview upload. Needs R1 to R4 and R6 from LPL, and darktable 5.8.0 (2.1). Gate G2: the owner edits a series end to end from LPL on the phone and opens the result on the PC; the tablet layout passes its emulator tests.

What experiment S5 measured for the 29 priority modules (details in `docs/experiments/S5-control-schema.md` in the repository):
- **Usable in Phase 2 from the generated schema alone (9):** demosaic, AgX, exposure, grain, blurs, diffuse or sharpen, chromatic aberrations, color balance rgb, rgb primaries.
- **Mostly generated, with small hand-written overrides in Phase 2 (12):** color calibration, crop, rotate and perspective, lens correction, white balance, spektrafilm, tone equalizer, denoise (profiled), LUT 3D, color equalizer, color balance, color harmonizer. Typical gaps: a list filled at run time (lens, profile, film stock, presets), a file chooser, sliders derived from other parameters. Their on-image and graph parts still wait for Phase 3.
- **Hand-built editor needed (8):** orientation (four buttons, done in Phase 2), and in Phase 3 tone curve, rgb curve, rgb levels, contrast equalizer, color zones, color look up table, retouch.

**Phase 3: the hard parts, local adjustments first**
1. Masks and blending on every module: drawn shapes (circle, ellipse, gradient, brush, path), parametric, show mask, mask manager for reuse across instances.
2. Crop (corners and move), rotate and perspective (rotation and straighten first, perspective after).
3. One shared curve editor: tone curve, rgb curve, rgb levels, tone equalizer (graph and drag on the photo).
4. One shared band editor: contrast equalizer, color zones.
5. Colour pickers and areas: color calibration, white balance, rgb levels, exposure spot.
6. color look up table (patch grid), retouch.
7. Snapshots, export with R5. Presets and styles are moved forward: engine calls with the extended API (P1b), the list and apply interface right after it (3.9).

Gate G3: checklist of these modules on the phone, and on the tablet layout in the emulator.

**Phase 4: speed and robustness.** OpenCL opt-in on the Adreno 650, cache and tiling tuning, low-memory behaviour, interrupted downloads and syncs, local crash log.

**Phase 5: maintenance.** `UPDATE.md` proven by a real version bump, remaining modules by demand, optional nightly-following build.

## 7. How implementation runs

- Coding is done by Opus agents, one work package each, from a written brief in `docs/briefs/`: scope, files they may touch, interfaces they must not change, acceptance tests, and the relevant sections of this plan.
- Claude (this project's session) writes the briefs, reviews every pull request against the plan and the mockups, runs the tests, and decides merge or rework. Nothing merges without that review and green checks.
- Agents may not change `dtbridge.h` after gate G1, the LPL contract in section 4, or anything under `upstream/` without approval.
- Decisions and progress are logged in the owner's private project notes; contract changes are edited into section 4 of this file.
- The owner is asked at the gates, and whenever a choice changes what the user sees or risks files.

## 8. Testing

- **Engine on Linux (every push):** bridge tests against a reference set of RAW + XMP pairs; pixel comparison with `darktable-cli`; XMP round trip (bridge writes, desktop reads, and back); history, instance and mask operations; the version guard.
- **App without the native core:** unit and Compose tests against the fake engine; screenshot tests for phone and tablet layouts and for token changes.
- **Android emulator in CI:** app start, LPL agent flow against a test LPL server, link hand-off, sync and conflict cases.
- **Real devices:** a short checklist per gate that the owner runs with the release APK. Speed is only measured on real devices.
- **Safety:** tests assert that the RAW's checksum never changes, that an XMP is only uploaded after it parses, and that a photo with unknown or newer module versions is never rewritten.

## 9. Risks

| Risk | Likelihood | Effect | Response |
|---|---|---|---|
| CPU-only editing is too slow on the Snapdragon 865 | Medium | High | S2 and G0 measure it before anything else is built; preview pipeline; OpenCL later |
| Engine and PC versions drift apart and an edit cannot be read | High without discipline | High | Same version line on both sides (2.1); guard that never rewrites such an XMP; local XMP backup |
| Following the development branch until 5.8.0 brings upstream bugs | Medium | Low to medium | Only experiments and Phase 1 run on it; real use starts on 5.8.0 |
| OpenMP or a library misbehaves on Android | Medium | Medium | Termux proves the stack without OpenMP; S2 isolates it |
| Running the engine without its interface hits code that assumes one (history, masks) | Medium | Medium | S3 exercises exactly these paths; small upstream patches as last resort |
| Memory pressure with 40 MP files | Low on 12 GB | Medium | Region rendering, tiling, capped caches |
| The phone vendor's Android version stops downloads or syncs in the background | High | Low | Foreground service, retry job, battery-optimisation hint |
| Android stops allowing code to load from app storage | Low | High | Fallback: pack modules as regular native libraries and patch the module loader (one patch) |
| Vendor OpenCL driver gives wrong results | Medium | Low | Opt-in, automatic comparison against the CPU result |
| Control extraction misses conditional controls | Medium | Low | Overrides file; a control wrongly shown is harmless because the engine validates values |
| Tablet layout is not tried on a real tablet | Certain for now | Low | Emulator and screenshot tests; real check when a tablet is available |
| Scope of graph and on-image modules | Certain | Medium | Tiers; Tier 0 guarantees no edit is lost; order from the priority list |
| Licence | Low | Low | Repository is GPL-3.0-or-later from the start; source must accompany any APK given to others |

## 10. Settled points

- Repository public; desktop darktable moves to 5.8.0 when it is released and is then updated together with the app; no tablet for now.

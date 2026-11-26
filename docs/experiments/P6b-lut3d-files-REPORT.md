# P6b: LUT 3D files from the phone and from the PCs through LPL — report

Branches: `p6b-lut3d-files` here, `p6b-lut-folder` in LPL. Brief: `docs/briefs/P6b-lut3d-files.md`. Contract: PLAN.md 4.6 (R19), copied to LPL `docs/luts-r19.md`. LPL's PR text: `docs/luts-r19-PR.md` on its branch.

Measurements on the build workspace are x86_64 numbers on 2 cores, not phone numbers.

## 1. Item by item

| Brief item | State | Where |
|---|---|---|
| Contract written first (PLAN 4.6, R19 row in 4.3) and copied to LPL | **done** | PLAN.md 4.6; LPL `docs/luts-r19.md`. Two changes made while building, in both copies: when each side announces `luts` (the phone after `server_caps` lists it, as with `shared`). |
| How desktop works, checked against pinned `src/iop/lut3d.c` | **done** | §3 below |
| G'MIC in the Android build? | **done: no** (`-DUSE_GMIC=OFF` in `tools/build-engine-android.sh`). `.gmz` files are listed greyed with the reason; the bridge's `set_file` refuses them; the phone does not download them from LPL. |
| Server: endpoints, storage, history, tests | **done** | LPL `server/lpl/luts.py`, `server/tests/test_luts.py` (32 tests) |
| Bridge: the folder told to the bridge; `set_file` takes the relative path | **done** | `dtb_lut_folder_set()` and `dtb_lut_name_usable()` in `dtbridge.h` (`engine/src/actions.c`); JNI `NativeBridge.lutFolderSet`, `DarktableEngine.lutFolderSet` |
| Bridge test: .cube, .3dl, .png made in the test, against darktable-cli with the same folder and relative path: identical | **done** | C44 (`engine/tests/dtb_test_p6b.c`): 4 comparisons × 3 images, all `max 0` (CI and local) |
| Missing file: what desktop does | **done** | pass-through, path kept; parity with darktable-cli (`p6b_missing`); read again once back and the folder is set again |
| App: file row opens a chooser of the folder's tree; unsupported greyed with reason; "Add from phone…" | **done** | `darkroom/ui/Lut3dPanel.kt`, `darkroom/Lut3dFiles.kt`, picker in `MainActivity` |
| App: missing file says "LUT file not on this phone: <path>" and "Get from LightPhotoLibrary" when LPL has it | **done** | same files; `LutSync.fetch` |
| App: Settings › LUT files (count, size, Remove per file with question, sync state) | **done** | `darkroom/ui/LutFilesScreen.kt`, `LutFilesPage` in `app-engine/.../Screens.kt`; library menu "LUT files…". Also the conflicts (mine / theirs / keep both) and "sync now". |
| App: phone sync (upload, download, conflicts, auto sync off) | **done** | `lpl/LutFolder.kt`, `lpl/LutSync.kt`; wired into the heartbeat, the once-a-minute poll, `SyncJob`, `SyncService`, leaving the app |
| JVM tests: contract client, conflict states, path checks incl. `..` and absolute paths | **done** | `lpl/src/test/.../P6bLutsTest.kt` (17 tests) against the stub server's R19 (`StubLuts.kt`) |
| Emulator tap test: choose a LUT from the list; history and XMP get the relative path | **done** (p2b 45775913088) | `app-engine/src/androidTest/.../P6bTapTest.kt` (t1 choose by taps, XMP decoded; t2 missing-file line), in `tools/app/emulator-p2b.sh` |
| LPL agent (Windows and Linux) | **done** | LPL `agent/lpl_agent.py` (`LutSync`), `server/tests/test_pc_agent_luts.py` (9 tests); Windows exe workflow green |
| LPL web UI list and removal | **done** | LPL `web/src/components/Luts.tsx` on the Devices page; `web/tests/luts.test.ts`; rendered check `web/tests/luts-check.py` (screenshots `docs/shots/luts-r19/`) |
| README / docs, NAS rebuild note | **done** | LPL README, `agent/README.md`, PR text |

## 2. How to reproduce

Bridge (Linux build of the pinned darktable and the bridge, as in earlier packages):
```
tools/fetch-darktable.sh /tmp/dt     # then engine/tests/build-darktable-linux.sh as before
DTB_TEST_RESOURCELEVEL=small sh engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests> <out> C44 C4 C6
```
CI: `gh workflow run g0-app.yml --ref p6b-lut3d-files -f tests="C44 C4 C6"`; JVM, screen rules and emulator: `p2a-app.yml`, `p2b-real-engine.yml`.

LPL:
```
cd server && python -m pytest -q
cd web && npm ci && npm run build && npm test && python tests/luts-check.py <server python> /tmp/shots
```

## 3. Desktop (pinned `src/iop/lut3d.c`) and the differences

What desktop does:
- The folder is the preference `plugins/darkroom/lut3d/def_path`; `_button_clicked()` opens a file chooser in it and `_remove_root_from_path()` keeps the path relative; `filepath_set_unix_separator()` makes it `/`. `_filepath_is_safe()` refuses `..` steps.
- `_calculate_clut()` reads `.png`/`.PNG`, `.cube`/`.CUBE`, `.3dl`/`.3DL` (exactly these spellings). `_check_extension()` (the list of the current folder's files) lowercases, so desktop **lists** `Warm.Cube` but **renders nothing** for it. `.gmz` only `#ifdef HAVE_GMIC`.
- A missing or unreadable file: `_calculate_clut_cube()` etc. log `error - invalid cube file: <full path>` and return level 0; `process()` then copies input to output (pass-through); `_update_filepath_combobox()` shows `INVALID >> <relative path>`. No folder set: the file button is insensitive (`gui_update`).

Differences in the app (each with its reason):
1. The LUT folder is the app's own (`files/lpl/luts`), not a preference the user picks: Android gives an app no free file system path, and the folder must be the one LPL fills.
2. The chooser is a folder tree inside the module panel (folders, then files), not a system file dialog plus a list of one folder: the phone has no desktop file dialog that stays inside a folder.
3. Files darktable lists but cannot read (`Warm.Cube`) and `.gmz` (no G'MIC here) are **greyed with the reason** instead of listed as choosable; the bridge's `set_file` refuses them (`DTB_E_ARG`) rather than storing a path that renders nothing.
4. A missing file: instead of `INVALID >> path` the row says "LUT file not on this phone: <path>" and offers "Get from LightPhotoLibrary" when LPL has the file. Rendering is the same as desktop (pass-through; C44 `p6b_missing` identical to darktable-cli).
5. The module switched on without a table differs from the module switched off by at most 1/255 on the cr2 (0 on raf and arw); darktable-cli renders the same, so this is darktable's behaviour (the module's colour-space conversions), not the bridge's. C44 checks `<= 1` for that comparison and **exact** equality with darktable-cli and with the no-folder render.

## 4. Results

### Bridge (C44, new id in `engine/tests/TEST-IDS.md`; C4 and C6 re-run because `set_file` changed)
- CI g0-app run **45772991036**, job linux: C4, C6, C44 PASS on cr2, raf and arw; every C44 comparison against darktable-cli `max 0, psnr inf` (cube, 3dl, png, missing). The run's apk job failed on the tap test's compile error fixed in the next commit (see p2b below).
- Local (x86_64, 2 cores): the same, 25 PASS / 0 FAIL: `docs/experiments/P6b-results/bridge-local-summary.txt`, logs `C44-local-*.log`.
- Numbers from C44 (8-bit, largest channel difference): warm LUT vs no LUT 39 (cube, 3dl), 38 (png, 8-bit Hald); missing file vs module off 1 (cr2), 0 (raf, arw); file back without setting the folder again: still the pass-through; after `dtb_lut_folder_set` again: 0 vs the warm render.

### JVM, screen rules
- p2a-app run **45772984039**: success; screen rules 99 files, 0 violations; 647 unit and screenshot tests, 0 failed.
- p2b-real-engine run **45772987636** (first): `:lpl:test` 838 tests, **1 failed** (P6bLutsTest: a file the phone had just sent was shown as "not sent yet", because the client's view of the server was only refreshed by the next list); androidTest did not compile (missing import in P6bTapTest). Both fixed in the next commit.
- p2b-real-engine run **45775913088** (after the fix): JVM 838 tests, 0 failed (P6bLutsTest 17/17); APKs built. Emulator, attempt 1: 132 of 133 passed, **P6bTapTest t1 and t2 passed**; `P5cTapTest.t3_lookUpTablePatchDeletedByLongPressAndMenu` failed ("timed out: a node described 'color look up table'", the module row did not appear after the search; the same failure happened on `main` in run 45723595363 and passed in later runs; P5c runs before P6b, and nothing in this package touches the colour look up table or the module list). Attempt 2 (failed jobs re-run): **133 of 133 passed**, the run is green. Files: `docs/experiments/P6b-results/instrument-summary-45775913088-attempt*.txt`, `p6b-tap-results-45775913088.txt` (t1: history 11 -> 12, last item lut3d; the XMP's lut3d parameters, decoded, hold `film/warm.cube`).

### LPL
- `server`: 292 passed (local; `test_luts.py` 32, `test_pc_agent_luts.py` 9; one existing assertion updated: the agent's caps list now ends with `luts`).
- `web`: `npm run build` clean, `npm test` 93 passed; `luts-check.py` 3 rendered checks passed.
- LPL CI: Docker image run **45773546528** success; Windows agent (.exe) run **45773550006** success.

## 5. Decisions made while building (reviewer please confirm)
- **First upload from a PC does not replace LPL's file.** The brief says "newest upload wins between PCs". A PC that joins later would otherwise overwrite every file LPL already has with whatever old copies it has. The agent sends a file it never synced with `x-base-sha256: ""`, so the server answers 409 when LPL has another content, and the PC shows it as "different". A change the PC makes to a file it had synced is sent without a base and wins (newest change wins).
- **"Removed here" marks**, on the phone and on PCs: without them the next pass would download a file the user just removed. A removed file comes back on the phone only with "Get from LightPhotoLibrary"; on a PC when the user puts a file there again (then it is sent as a change).
- Two calls beyond the brief's list: `PUT /api/luts/state` (each device's list, needed for "which devices have each file" and for a PC's "different" / "no LUT folder" reports) and `GET /api/luts/overview` (web UI).
- The phone downloads a file whole (no Range resume): files are at most 64 MB; the server answers Range for a later change.
- Server storage is by content (`DATA_DIR/luts/<sha256>`), history kept without a limit (see open questions).

## 6. Upstream surprises
- `iop/lut3d.c`: `_check_extension()` (folder list) lowercases the extension, `_calculate_clut()` compares `.cube`/`.CUBE` only: a `.Cube` file is listed and silently does nothing.
- Without G'MIC, `_calculate_clut_haldclut()` recognises a 2-pixel-high PNG as a compressed CLUT and says "this darktable build is not compatible with compressed CLUT".
- `.3dl` needs a shaper line of at least 4 values (`nb_token > 3`) and a maximum of at least 128: a 2-point 3dl is not read.
- A changed or newly arrived LUT file is not seen by an open image until its pipeline pieces are made again (`commit_params` reads the file; the pixelpipe cache keeps the pass-through under the same parameter hash). `dtb_lut_folder_set` therefore empties the pipeline caches and rebuilds the pieces (no history change); the app calls it whenever the folder changes.

## 7. Open questions
1. LPL keeps every earlier content of a LUT file (one copy per distinct content). Owner: is a size limit for LUT history on the NAS wanted? Nothing in the brief needed it.
2. The phone takes every LUT file LPL has (except `.gmz`) on each sync. If the PCs hold large LUT collections, the owner may prefer "only on demand" (the "Get from LightPhotoLibrary" path exists already).
3. `schema/overrides/lut3d.json` still carries the P2j note "needs a LUT folder (a later package)"; the note is no longer shown (the file row replaces control 0). Left as is; can be removed with the next schema regeneration.

## 8. What the owner must do
- LPL: rebuild / pull the NAS image after merging; update the agent on each PC (Windows exe or `lpl_agent.py`; Linux `lpl_agent.py`, restart the service).
- darktable on each PC: preferences › processing › "LUT 3D root folder" set (otherwise that PC offers no LUTs; LPL's Devices tab says so).
- Phone: install the new APK; LUT files appear under the library menu "LUT files…" and in the lut 3D module.

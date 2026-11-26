# Brief P6b: LUT 3D files, from the phone and from the PCs through LPL

Branches: `p6b-lut3d-files` here (from `main`) and `p6b-lut-folder` in LPL (`/home/claude/lpl`, from its `main`). Read CLAUDE.md, PLAN.md 3.9, 4 (all of it, especially 4.5 R11 as the model for opt-in caps and agent jobs), 6.1, the audit row for LUT 3D, and darktable's manual page for lut 3D. Owner's decision (26 Nov 2026): LUT files can come **from either place**: a folder on the phone and the PCs' darktable LUT folder through LightPhotoLibrary.

## How desktop works (check against the pinned `src/iop/lut3d.c`)
The LUT folder is a preference (`plugins/darkroom/lut3d/def_path`); the module's file button opens a chooser inside that folder; the parameter `filepath` stores the path **relative to the LUT folder** (so an XMP written on one PC works on another PC whose LUT folder has the same files at the same relative paths). Formats: .cube, .3dl, .png (Hald CLUT), and .gmz only with G'MIC (say whether the Android build has G'MIC; if not, .gmz files are listed as not usable with the reason, as desktop without G'MIC does). Find what desktop renders and shows when the file is missing.

## 1. Contract (PLAN 4.6, written first, in this repository and copied to LPL's docs)
"LUT folder", opt-in like R11: cap `luts` in heartbeat `caps` and `server_caps`.
- A file is identified by its **relative path** (forward slashes, no `..`, supported extensions only, at most 64 MB); content by SHA-256.
- PC agent: reads darktable's LUT folder from `darktablerc` (`plugins/darkroom/lut3d/def_path`; absent → the agent offers nothing and says so in its status). Uploads new or changed files (by hash) on start and every 10 minutes. Takes in files it does not have **only into empty places**: it never overwrites or deletes a file in the user's LUT folder; a different file at the same path is reported, not replaced.
- Phone: its LUT folder lives in app storage. Imported files (Android file picker, one or several, optionally into a subfolder) are uploaded like a PC's. On each sync pass (respecting "auto sync off") it downloads files it lacks or whose hash changed **and that it has not changed itself**; a phone-side change and a server-side change of the same path → conflict shown in the app, choices "mine / theirs / keep both" as R11.
- Server: stores one current file per relative path (newest upload wins between PCs; the previous content is kept as history, not deleted), and answers `GET /api/luts?since=<seq>` (path, size, sha256, seq, changed_at, device_name, deleted), `GET /api/luts/file?path=…` (Range like checkout), `PUT /api/luts/file?path=…&agent=…` (body = bytes, `x-sha256`, `x-base-sha256` for the conflict check), `DELETE` only from the web UI. LPL web UI: a "LUT files" list in Settings or Devices (which devices have each file), and a way to remove a file from LPL (not from any device).
Write the contract in PLAN as 4.6, then build to it. If something in it turns out wrong while building, change PLAN and LPL's copy in the same commit and say why in the report.

## 2. Engine and app
- Bridge: `lut3d set_file` takes the **relative** path and the app's LUT folder (or the bridge is told the folder once, as darktable's preference); a test renders a .cube, a .3dl and a .png LUT from a public source (generate small ones in the test: an identity and a simple transform, no third-party files in the repository) against darktable-cli with the same folder and relative path: identical. Missing file: what desktop does.
- App: in the lut 3D module, the file row opens a chooser of the LUT folder's tree (folders, supported files; unsupported ones greyed with the reason), plus "Add from phone…" (Android picker, copies into the folder). A missing file shows "LUT file not on this phone: <relative path>" and, when LPL has it, "Get from LightPhotoLibrary". Settings › LUT files: count, size, "Remove" per file (asks first; the phone only), sync state. Theme tokens and icon registry only.
- Tests: bridge (C ids from TEST-IDS.md), JVM (contract client, conflict states, path checks incl. `..` and absolute paths refused), one emulator tap test (choose a LUT from the list; the history and XMP get the relative path).

## 3. LPL
Server endpoints, storage, tests (pytest or whatever LPL uses), web UI list, agent (Windows and Linux; darktablerc locations as the agent already finds darktable's config for R11), README / docs update, and the note that the NAS image must be rebuilt. Keep LPL's own conventions (read its docs and the R11 code first).

## Economy and order
One agent, no sub-agents. Order: contract → LPL server + tests → app/bridge → LPL agent → web UI. Push both branches in batches; LPL's CI (if it has one) and this repository's g0 / p2a / p2b as needed. C40 not needed. Stop and report if the contract needs a decision from the owner (for example storage size on the NAS).

## Report
`docs/experiments/P6b-lut3d-files-REPORT.md` here (both repositories, item by item, CI run ids, differences from desktop), and a short PR text for LPL in `docs/` of the LPL branch.

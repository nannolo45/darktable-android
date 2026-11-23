# P3f design: duplicates and versions (request R17)

Design only. Nothing here is built. Mockups: `app-versions.html` (phone) and `lpl-versions.html` (LPL web). Open them in any browser.

Sources: the darktable manual page for the darkroom duplicate manager (read). The lighttable "selected images" page could not be fetched (site error), so duplicate and virgin duplicate follow what desktop does as I know it. LPL's `transfer.py` already copies `NAME_nn.EXT.xmp` files together with the RAW when a photo goes to a PC, so the files themselves are not new to LPL.

## 1. How versions are stored (same as desktop)
- Every photo has version 0: `IMG_01.RAF` with `IMG_01.RAF.xmp`. This is today's photo and stays as it is.
- Version n (n = 1, 2, ...) is a second sidecar next to it: `IMG_01_01.RAF.xmp`, `IMG_01_02.RAF.xmp`. The number goes before the extension, two digits (three from 100). It is the same RAW; only the edit differs. No RAW is ever copied.
- **Duplicate** = new sidecar with the same history as the version it was made from. **Virgin duplicate** (the manual calls it "duplicate original") = new sidecar with no history. Both start with the rating, labels and tags of the source version.
- A new number is always the highest in use plus one. A deleted number is not reused, so a name never means two different edits.
- An optional **version name** ("black and white", "for print") is stored inside that version's XMP, as darktable does; desktop shows it too.
- The app already opens any XMP path; the one thing it needs is to write a different path. A version's file is only ever written when it has no unknown module or newer module version (PLAN 2.1), as for version 0.

## 2. What LPL shows
**One tile per photo, with a badge, not one tile per version.** The tile shows the cover version and a small "3" badge. Reason: a grid with every version loses the point of ratings, series and board (a photo would appear two or three times in a series). In the photo panel a strip lists all versions with thumbnails, as the darkroom's duplicate manager does. A setting "show every version as its own tile" can come later; it is cheap once versions have their own rows.
- Cover: by default the **original (version 0)**; the owner can set another version as cover from the panel. (Decision 2.)
- Rating, colour labels, tags: stored per version, as in the files. The tile shows the cover's. Filters match a photo if its cover matches. Changing them in the panel changes the version being looked at.
- Series and board: an entry is a photo, as today. It shows the cover; one entry may pin a specific version ("this series uses version 2"). The board shows the pinned or cover version. Order and ids are unchanged, so R1 links keep working.
- Scan: versions are found by the file name pattern and listed under their photo. They never become photos of their own (fixes the README note). Version 0 missing but `_01` present: treated as a photo with a gap, shown normally.
- Where a photo is (R6): the line says "on Phone: version 2, edited, not synced yet".
- Last edited (R15): each event also records the version.

## 3. How a version travels
**To the phone.** R1 link: `ids` unchanged; one new optional field `version=<n>` for the version to open first (default: the cover). The app asks `GET /api/photos/{id}/checkout`, which now also lists `versions: [{n, name, mtime, size}]` (only when the app sent the `versions` capability). The app downloads every version's XMP with `GET /api/photos/{id}/xmp?version=n` (no `version` = 0, as today) and keeps them as `IMG_01_02.RAF.xmp` beside the RAW in its per-photo folder.
**Made on the phone.** The app writes the new file locally at once, so it works offline. The status is "waiting to sync", as for any edit.
**Back.** `POST /api/photos/{id}/xmp?version=n&checkout_ts=...&agent=...`. LPL writes `NAME_nn.EXT.xmp` beside the original, with its usual backup of an overwritten file. `X-LPL-Merged` answers per version. The preview is `POST .../preview?version=n`; LPL keeps one per version and shows the cover's on the tile.
**Conflicts.**
1. Same version edited on two devices: unchanged rule (history from the editor that posts last with a newer `checkout_ts`; LPL wins rating, labels, tags).
2. Two devices create the same number offline (phone and desktop both make `_02`): LPL answers `409` with `{"error":"version_exists","next":3}` when the number exists and was not in the poster's checkout. The app renames its file to `_03` and posts again. Nothing is overwritten.
3. A version deleted on one side and edited on the other: the edit wins and the version comes back (nothing is lost).
**Delete.** `DELETE /api/photos/{id}/xmp?version=n`; LPL moves the file to its backup folder, it does not erase it. Version 0 is not deletable from the app. (Decision 3.)

## 4. What the app shows
- **Photo screen:** a "versions" chip in the top bar shows the current version ("v2"). Tapping opens the **versions sheet**: a row per version with thumbnail, number, name, "edited" or "no edits", and a "sync" state dot. Buttons on the sheet: **Duplicate** (same history as the version in use) and **Virgin duplicate**; per row a menu: rename, set as cover (asks LPL when online), delete.
- **Switching:** tap a row. The current version is saved first, then the other opens; the filmstrip and series stay where they are. A long press on a row shows it for as long as it is held (as on desktop, "hold to peek").
- **Skins:** darktable and modern skin: the chip and the sheet above. l1 skin: the existing `more` tool gets a `duplicate` entry that opens the same sheet; no new tool button.
- **Working set list:** a photo with versions shows the badge; a version in "waiting to sync" is named.
- Colours, sizes and icons come from the tokens (PLAN 3.6); new icons needed: `versions`, `duplicate`, `duplicate-virgin`.

## 5. Exact additions to the LPL contract (PLAN 4.3, row R17)
| # | Where | Request |
|---|---|---|
| R17 | Server + web UI + app | Versions as in sections 1 to 3. **Server:** table `photo_versions(photo_id, n, name, xmp_path, mtime, cover)`; the scan fills it from `NAME_nn.EXT.xmp`; `/checkout` lists versions; `/xmp`, `/preview`, `DELETE /xmp` take `version` (absent = 0; with a `version` the old calls are unchanged); `409 version_exists` with `next`; `/api/photos/{id}/cover?version=n`; `GET /api/photos` carries `versions` (count) and `cover`; the edit events (R15) and `working_set` items carry `version`. **Web UI:** badge on the tile, versions strip in the photo panel (thumbnail, name, rating, labels, set cover, delete, "edit this version"), pinned version in series and board, R6 line names the version. **R1 link:** optional `version`. **Everything is opt-in:** the app adds `"versions"` to `caps`; a server without it is seen as today (the app then offers no duplicate while connected to that server, and keeps what it made as local-only versions, never posted). |

**Changes in LPL:** scan, database table, five endpoint parameters, tile badge, photo panel strip, series pin. About one small package for the server and one for the web UI.
**Changes in the app:** write and list `_nn` sidecars, versions sheet, switch, sync per version, rename on 409, l1 `more` entry. The bridge needs no change except that `xmp_write` is given the version's path; it has `open(raw, xmp)` already.
**Not changed:** the RAW, the PC agent (desktop darktable already writes these files), the contract's existing rows.

## 6. Decisions for the owner
1. **Stack or tiles?** (a) One tile with a badge [recommended]; (b) every version a tile; (c) a setting, default (a). With (a), series and board stay clean; with (b) you see everything at once but photos repeat.
2. **Which version is the cover?** (a) Always the original; (b) the most recently edited; (c) original unless you choose another [recommended]. (c) is predictable and still lets a finished version represent the photo.
3. **Can the app delete versions?** (a) No, only LPL or desktop; (b) yes, with a confirmation, LPL keeps the file in its backup folder, version 0 never [recommended]; (c) yes including 0. Without (b) a mistaken duplicate can only be cleaned up at the computer.

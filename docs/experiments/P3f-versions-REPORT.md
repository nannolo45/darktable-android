# P3f: duplicates and versions (app side)

Branch `p3f-versions`. Design `docs/design/P3f-versions.md`, mockup `docs/design/mockups/P3f-Versions.html`.

## 1. Delivered
| Item | State |
|---|---|
| Sidecars `NAME_nn.EXT.xmp` (two digits, three from 100, never reused; name in `darktable:version_name`) | done (`app/lpl/.../Versions.kt`) |
| Duplicate (same history, rating, labels, tags) and virgin duplicate (no history, rest kept) | done: engine `xmp_write` to the new path, then the XMP is edited with an XML parser. No bridge change was needed. |
| Rule 4 | done: `xmp_write` refuses a read-only session; rename asks `xmp_check` first; duplicate is refused for a read-only version |
| Versions sheet (thumbnail, number, name, edited, sync dot; rename, cover, delete with confirmation, never v0), switching saves first, hold to peek | done (see 4 for thumbnails and peek) |
| Skins: chip in darktable and modern (and tablet); l1 `more` has `duplicate…`; icons `versions`, `duplicate`, `duplicateVirgin`; tokens only | done |
| LPL: `versions` cap, `/checkout?agent=`, per-version GET/POST xmp and preview, DELETE, cover, 409 rename, old server, R1 `version` | done |
| PLAN.md 4.3 R17 row, `ui-spec.md` section 12, branch in CI triggers | done |
| `darktable-cli` comparison of `NAME_01.EXT.xmp` (case `p3f-version`) | not done |

## 2. Reproduce
- LPL module JVM tests (local): `cd app && gradle :lpl:test --offline` (98 + new `P3fVersionsTest`, all pass).
- Darkroom controller tests (no Android SDK here, compiled in a scratch JVM project over the same sources): `P3fVersionsTest` in `app/darkroom/src/test`, 9 tests pass; the other 71 controller tests pass except `P3aCropToolTest.theToolOpensOnTheUncroppedPictureWithoutChangingTheEdit` (expected 1 but was 2), not checked against `main`; CI decides.
- CI: p2a-app (JVM, screenshots `PhoneVersionsScreenshots`), p2b-real-engine (emulator `P3fScenarioTest`).

## 3. Findings
- LPL's 409 rule uses what the agent was told at its last checkout, so a version the phone itself posted gives a 409 on its next edit. The app then asks `/checkout?agent=` again (which notes it) and posts the same number again. Better fixed in LPL (note a number when it is posted).
- `/checkout` lists versions only with `?agent=<id>`; the app sends it.
- The sidecar `IMG_01_02.RAF.xmp` is also the sidecar of a RAW named `IMG_01_02.RAF`; a version is not listed when such a RAW is in the folder.

## 4. Limits and open questions
- Thumbnails and "hold to peek" use the last picture of a version seen in this run (the engine holds one photo). A version not opened yet shows its number; peek then says to open it once.
- Renaming the open version reopens it (undo history is lost); the engine has no call for the name.
- On the real engine a virgin duplicate shows "edited" once it was opened and saved (the engine adds its automatic modules to the history).
- A version deleted on LPL or desktop stays on the phone (no local removal): ask the reviewer.
- Locally made versions with an old server are never posted; the sync dot says "on this phone only".
- `darkroom` now depends on the `lpl` module (pure Kotlin) for the file logic.
- The PC agent does not carry versions (LPL report).

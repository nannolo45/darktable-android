# P3l: styles, presets and module layouts shared through LightPhotoLibrary (app side of R11): report

Branch `p3l-shared-styles-presets`. Contract: PLAN.md 4.5 (not changed here). Built against a stub server that
implements 4.5 (`app/lpl/src/testFixtures/.../StubShared.kt`), with the supervisor's three clarifications of 24 Nov
(see "Clarifications followed"). LPL's own server (branch `shared-r11`) was not run against this branch.

**CI (head `c52e9f3`, all x86_64 numbers on GitHub's 2-core runners and the API 33 x86_64 emulator, not phone numbers):**
p2a-app 45436645761 success; p2b-real-engine 45436645805 (apk, emulator 111 of 111 instrumented tests incl.
P3lScenarioTest l1 and l2, compare) success; g0-app 45436645974 success (bridge tests incl. C31). The bridge test C31 ran in g0-app
45423775629 (head `ecf6ca7`; the engine has not changed since) and passed. Earlier failures on this branch, all fixed:
g0-app 45412422104 (C31 test bug, see 4), p2a 45423775483 and p2b 45423775451 (the new screenshot tests called
`setContent` twice and tapped a line outside the sheet's scrolled area).

## 1. What the brief asked and what was delivered

| Item | State | Where |
|---|---|---|
| 1. Bridge `dtb_shared_snapshot`, `dtb_shared_apply`, `dtb_shared_delete`; hash as 4.5; vectors reproduced in a bridge test; REAL `%.17g`, BLOB hex, columns by name | done | `engine/src/shared.c`, `engine/include/dtbridge.h`; test C31 (`engine/tests/dtb_test_p3l.c`) |
| 1. Apply refreshes what the engine caches | done (nothing to refresh in the bridge, see 4) | the app re-reads the styles list and the layout after an apply |
| 1. Newer module version not written, `usable: false` | done (also newer blend version and unknown operation) | C31 step 5 |
| 2. Layouts to and from a `layout` body, bytes proved against a desktop row; apply never changes the active layout | done | `SharedLayout` (`app/lpl/.../Shared.kt`), `P3lLayoutBodyTest`, emulator l1 |
| 3. Sync client: caps, per-item state on disk, uploads (sync pass and P3k service, "auto sync off" respected), polling at most once a minute and at each pass, `PUT state`, 409 and `/resolve`, newest-wins policy | done; uploads are not ordered newest first (see 5) | `SharedSync.kt`, `Shared.kt`, `LplAgent.kt`, `AppHost.kt`, `SyncService.kt`, `SyncJob.kt` |
| 4. UI: banner + dot, review sheet, conflict dialog, deletion dialog, settings section; tokens and icons only; three skins | done | `app/darkroom/.../ui/SharedScreens.kt`, `PhotoList.kt`, `Screens.kt`, `LplScreens.kt`, `AppViewModel.kt` |
| 5. Tests: hash vectors in C and Kotlin | done | C31; `P3lSharedTest.hashVectorsAreReproduced` |
| 5. Bridge apply/delete/snapshot round trip incl. newer module refused | done | C31 |
| 5. Client against a fake server: identical first sync, one-side change, conflict + each choice, newest wins, deletion offered + delete/keep, kept item changed again, rename | done (18 JVM tests) | `app/lpl/src/test/.../P3lSharedTest.kt` |
| 5. UI tests for banner, sheet, both dialogs **in the emulator job** | partly | interaction and screenshot tests run under Robolectric in `:darkroom:testDebugUnitTest` (all three skins); the emulator test (P3lScenarioTest l2) drives the review sheet and the conflict dialog through the view model with the activity showing, but does not tap the screen: the app's instrumented tests have no Compose test library, and adding one was not in the brief |

## 2. How to reproduce

```
# bridge (Linux, as CI): C31 alone
engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests> <out> C31
# reference hash vectors
python3 tools/r11/r11_hash.py > /tmp/v.json && cmp /tmp/v.json docs/design/R11-hash-vectors.json
# client (JVM) and layout bytes
cd app && gradle :lpl:test --tests app.dtandroid.lpl.P3lSharedTest
cd app && gradle :darkroom:testDebugUnitTest --tests 'app.dtandroid.darkroom.P3l*'
# emulator (in p2b-real-engine's emulator job): app.dtandroid.real.P3lScenarioTest, results in files/p2b-test/p3l-results.txt
```

## 3. Results

- **C31 (Linux bridge, g0-app 45423775629, `runs/45423775629-a1/g0-linux/cr2/C31.log` on `ci-logs`):** vectors 3 of 3
  reproduced (canonical text and hash); fresh library snapshot 1 preset, 538 styles (= the engine's 538 styles: the
  shipped ones); after apply the snapshot gives the vectors' hashes back (preset `9951add9…`, style `74ff1111…`, with
  the REAL, BLOB, NULL and non-ASCII values, the extra column dropped); replaced not added; newer module, newer blend
  version, unknown module: `usable: false`, nothing written, existing style unchanged; a built-in key
  (`_builtin_magic lantern defaults`) and a layout body refused; a preset stored from the photo and a style created from
  it are in the snapshot (544 items); delete, then NOT_FOUND. `RESULT shared ok`.
- **JVM, `P3lSharedTest` (18 tests) and the rest of `:lpl:test` (144 in all) pass** locally and in p2a/p2b: the cases of
  deliverable 5 with two phones and a PC against the stub, plus REAL formatting against Python's `%.17g`, the
  hash_mismatch and `changed_at` clarifications, an older server, a restart.
- **Layout bytes, `P3lLayoutBodyTest` (3 tests):** the body made from the layout desktop exported
  (`desktop-export.dtpreset`, written by darktable 04477d3 from its own row) carries exactly that row's `op_params`
  bytes; all built-in layouts map byte for byte; columns outside the core do not change the hash.
- **UI, `P3lSharedScreenshotTest` (5 tests, Robolectric, three skins):** banner with Review and the settings dot, the
  "after an update" banner, the review sheet (groups, ticking changes "Update n", conflict and deletion lines open their
  dialogs, a greyed line cannot be chosen), the conflict dialog (no "Keep both" against a deletion, "same choice"
  check), the deletion dialog, the settings section. Screenshots `phone-p3l-0*.png` under
  `runs/45436645805-a1/p2b-apk/screenshots/` on `ci-logs`.
- **Emulator, `P3lScenarioTest` (p2b 45436645805, `p3l-results.txt`):** l1: four items from the PC waiting; on its
  first pass the phone uploaded its own 539 items (the 538 shipped styles and one preset of a fresh library) in
  1629 ms, every one accepted by the stub, which recomputes the hash in Kotlin: the bridge's C hash equals the Kotlin
  hash on 539 real data.db rows; Update: "3 taken in · 1 need a newer darktable"; style and preset listed by the engine,
  layout stored and not made active; the hashes after the round trip through data.db are the server's; the next pass
  sends nothing; a style deleted on the phone becomes a deletion on the server. l2: a conflict resolved "keep both"
  through the view model with the list (banner) showing: the phone keeps its copy and receives "p3l both (Desktop)".

## 4. Upstream observations

- `src/libs/modulegroups.c`: every layout is stored with `dt_lib_presets_add(name, ..., tx, strlen(tx), ...)`, so a
  layout's `op_params` has no terminating zero; `get_params` also returns `strlen`. `dt_presets_add_lib`
  (`src/common/presets.c`) gives the other columns (`exposure_max` 10000000, `iso_max` FLT_MAX written as a decimal
  literal, NULL `multi_*`); `SharedLayout.body` copies those values. They are outside the hash's core columns anyway.
- `src/libs/modulegroups.c` also keeps a user preset named `last modified layout` (`CURRENT_PRESET_NAME`, writeprotect 0)
  that desktop rewrites on every layout change. Under 4.5 it is a shared layout (see 6).
- `src/common/darktable.c` imports darktable's shipped styles (`share/darktable/styles`) into `data.styles` at every
  start (`dt_import_default_styles`), as ordinary rows; the bridge does the same (`engine/src/core.c`). Under 4.5
  ("Styles (all of them)") they are shared items (see 6).
- `json_node_copy` (json-glib) shares the object or array a node holds; C31 first failed on this in the test code
  itself (g0-app 45412422104), fixed by a deep copy.
- Caches: in the bridge nothing caches presets or styles (`dtb_presets_list` and `dtb_styles_list` read data.db each
  time); upstream's own caches (style shortcuts registered by `dt_styles_create_style_header`, GUI presets menus) do not
  exist headless. `dtb_shared_apply` writes rows with SQL by column name inside `dt_database_start_transaction` and
  does not register a style shortcut, as `dtb_style_delete` does not unregister one.

## 5. Choices made where 4.5 leaves room

- Hash for presets and styles comes from the bridge (C); for layouts the app computes it (Kotlin). Both reproduce the
  three vectors; on the emulator the stub server recomputes the Kotlin hash of every body the phone uploads from its
  real data.db and refuses a mismatch with 400: l1 checks no upload was refused.
- JNI passes strings as modified UTF-8, in which characters outside the BMP are not valid UTF-8: the bridge writes them
  as `\u` surrogate pairs in the snapshot, and the app escapes them the same way in bodies it applies.
- `conflicts[].deleted` is read as "this device's candidate is a deletion"; whether the other side deleted comes from
  the item's `deleted`. "Keep both" is offered only when neither is a deletion.
- The answer to `DELETE` is read as the PUT answer (`{id, rev, seq, decided}`) or 409 as for PUT.
- A `400` to a PUT is logged and the item left for the next pass (clarification 2), not treated as a conflict.
- Uploads are not sorted newest first: the app does not know when a preset or style changed (data.db has no time
  column for them); items go in snapshot order. Every PUT carries the time the change was seen.
- "After a change is seen": the app looks for local changes at each pass: once a minute while the app is open, when it
  leaves the foreground, and in the P3k service and job. There is no hook on each preset or style edit.
- A disabled kind (Settings switch) is neither uploaded nor offered; sharing off also removes `shared` from the caps.
- An item reported `unusable` is not offered again until its revision changes, also after the app's darktable is
  updated (see 6).
- Settings › LightPhotoLibrary shows the section only when a server is set up (the server screen in edit mode).

## 6. Open questions and proposals for 4.5

1. **`last modified layout`** (desktop's working copy of the layout being edited) is a user preset of `modulegroups`
   and therefore shared under 4.5; every layout edit on a PC would travel and could conflict with other PCs. Proposal:
   exclude that name in "What is shared".
2. **darktable's shipped styles** are rows of `data.styles` on every device. Same darktable version: identical hashes,
   adopted without a conflict (JVM test; on the emulator they were the phone's first uploads). Different versions: they would conflict.
   Measured: a fresh library holds 538 of them, and the first pass of a phone uploads all 538 (1.6 s on the emulator
   against the stub; LPL would store 538 items per darktable version in use). Proposal: exclude styles whose name is a
   shipped style's, on both sides alike (the PC agent and the bridge would need the same list), or keep 4.5 as it is
   and accept that.
6. A fresh library also holds one user preset (writeprotect 0) of a processing module that the snapshot shares; C31
   does not print its name. Worth checking which module creates it before LPL goes live.
3. **`unusable` after an engine update**: proposal: a device re-reports an item `unusable` only while its engine still
   refuses it, so that the item waits again after the app's darktable is updated (the app can do this on its side by
   clearing `unusable` when its engine version changes; not done here).
4. `conflicts[].deleted`: please confirm it means the agent's own candidate (5 above).
5. `DELETE` answer shape: please confirm (5 above).

## 7. Clarifications followed (supervisor, 24 Nov)

`changed_at` in seconds as a JSON number (PUT body; `DELETE ...&changed_at=<seconds>`); `400 {"error":
"hash_mismatch"}` logged, not a conflict; DELETE carries the time the deletion was seen. The stub follows the server
behaviour described: a deleted item revives only from `base_rev` equal to the deletion's rev, "keep both" refused when
either side is a deletion, a taken copy name gets " 2", " 3".


## 8. Second round (supervisor's review, 24 Nov)

1. **Exclusions** (PLAN.md 4.5 as updated in 6537f86): applied in one place, the client (`SharedExclusions` in
   `app/lpl/.../SharedSync.kt`), from `docs/design/R11-excluded-names.json`, packed into the `lpl` module's resources
   at build time (`packShared` in `app/lpl/build.gradle.kts`, like the schema files). An excluded item (style named
   `_l10n_…`, a layout named "last modified layout" in any listed translation) is neither uploaded, nor offered, nor
   deleted on the server when it disappears locally. Test: `P3lSharedTest.shippedStylesAndLastModifiedLayoutAreNeitherSentNorOffered`.
   Changes to the JSON file now trigger p2a and p2b.
2. **`unusable` reset:** the port reports the engine version (app: darktable version + engine package id); when it
   differs from the one the book last saw, every `unusable` entry becomes `ok` at rev 0, so the item is offered again,
   and the state is sent. Test: `P3lSharedTest.unusableMarksAreClearedWhenTheEngineChanges`.
3. **The user preset of a fresh library:** C31 now prints every preset of the fresh snapshot (`RESULT fresh preset:`),
   and the emulator test l1 records the phone's presets before the first pass (`l1_fresh_presets` in
   `p3l-results.txt`). Found (g0-app 45447820993, C31): `exposure` v7 "p1b test", left by the bridge tests' own library (P1b), not made by darktable: no exclusion needed.
4. **Interop with the real LightPhotoLibrary server** (`/home/claude/lpl`, branch `shared-r11` at `93ca7f7`, started
   locally with `PHOTO_ROOT=/tmp/lplphotos DATA_DIR=/tmp/lpldata PORT=18791 python3 -m lpl.main` from `server/`):
   `P3lInteropTest` drives the deliverable-5 scenarios only over HTTP (the PC is an agent that heartbeats with caps
   `shared` and calls the R11 API), against the stub by default and against a real server with
   `gradle :lpl:test --tests app.dtandroid.lpl.P3lInteropTest -Pdta.r11Url=http://127.0.0.1:18791 [-Pdta.lplLog=<file>]`.
   **Result: 10 of 10 pass against the real server** (and against the stub): first sync identical (no conflict, only
   the new item sent), one-side change taken in on Update and not sent back, conflict + mine / theirs / both (the copy
   arrives as "warm ·… (Studio PC)"), newest wins both ways at PUT, deletion offered + delete / keep (not offered
   again), kept item changed again comes back at the deletion's rev + 1, rename = new item + deletion, an item for a
   newer darktable kept `unusable`. 171 requests; the only non-200 answers were the three expected
   `PUT /api/shared -> 409` of the conflict scenarios. The real server's `hash_mismatch` answer was checked by hand:
   `PUT` with `"hash":"00"` -> `400 {"error":"hash_mismatch","hash":"0547abf0…"}`, the same hash `tools/r11/r11_hash.py`
   gives for that body. **No mismatch found.** Two test adjustments for a shared server (not protocol differences): item
   names carry a suffix per test, and the update summary is compared by its start, because a real server also lists
   other tests' items ("· 7 still waiting").

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoep891vjXMfqGv9Mc

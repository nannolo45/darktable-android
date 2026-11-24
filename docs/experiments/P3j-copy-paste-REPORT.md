# P3j: copy and selective paste of the history — report

Branch `p3j-copy-paste` from `main` 1c39df7. Brief: `docs/briefs/P3j-copy-paste-history.md`. No pull request (per
the brief); this file is its description.

**Reference.** The darktable manual's history stack page could not be fetched from the build workspace (the fetch
needed an approval nobody gave). Upstream's source at the pinned commit is the reference: `common/history.c`
(`dt_history_copy`, `dt_history_copy_parts`, `dt_history_paste`, `dt_history_copy_and_paste_on_image`),
`gui/hist_dialog.c` (the selective dialog), `libs/copy_history.c`, `control/jobs/control_jobs.c` (paste job),
`data/darktableconfig.xml.in` (`plugins/lighttable/copy_history/pastemode`).

## 1. Brief item by item

| Item | State |
|---|---|
| D1 engine: copy all or a list of instances to a clipboard; paste onto the open photo or onto XMP/raw pairs, append or overwrite; one undo step on the open photo | **done** — `engine/src/copypaste.c`, five calls (engine-api.md section 9). Every copy and paste is upstream's own `dt_history_copy_and_paste_on_image()`. "One history item per paste" read as one undo step: the history gets one item per pasted module, as desktop (needed for the same XMP as desktop). |
| D2 app: copy, selective copy… (ticked = what a plain copy takes, unsafe modules unticked), paste, selective paste…, mode append/overwrite remembered; darktable and modern: photo menu; photo list: choose photos, paste with BatchProgress, LPL photos fetched first; l1: `more` | **done** — `darkroom/CopyPaste.kt`, `ui/CopyPasteSheets.kt`, photo menu (`Sheets.kt`), l1 `more` tool (`L1Panel.kt`), photo list choose mode (`PhotoList.kt`, app-engine `Screens.kt`); also "paste onto photos…" over the series in the darkroom. Mode remembered in `files/settings/paste-mode.txt`. |
| D3 pasted LPL photos marked edited and synced | **done (by the existing path)** — each pasted photo is reported with `PhotoSource.left(item)`, the call the darkroom makes after saving a photo, which marks it and schedules the sync (AppHost). Not tested against a stub server in this package. |
| Tests: bridge | **see 3** — C29 (`engine/tests/dtb_test_p3j.c`). |
| Tests: JVM | **done** — `P3jCopyPasteTest` (3 tests), contract test `copyAndPasteTheHistoryBetweenPhotos` (stand-in). |
| Screenshots | **done** — `phone-p3j-{darktable,modern}-{menu,copy-sheet,paste-sheet}`, `phone-p3j-l1-more`, `phone-p3j-paste-onto` (p2a-app job screenshots). |
| Emulator | **done** — `P3jScenarioTest` (copy A, selective paste onto B, B renders, B's XMP reopens identical, paste onto two photos) and the contract test on the real engine: both pass (run 45293622649). |
| Docs, CI triggers | **done** — engine-api.md section 9, ui-spec.md section 15, branch in g0-app, p2a-app, p2b-real-engine triggers, P3jScenarioTest in `tools/app/emulator-p2b.sh`. |

## 2. How to reproduce

- Bridge: `engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests> <out> C29` (g0-app job `linux`).
- JVM: `cd app && gradle :engine-api:test --tests '*copyAndPaste*' :darkroom:testDebugUnitTest --tests '*P3j*'`.
- Emulator: p2b-real-engine workflow; results in `files/p2b-test/p3j-results.txt` (ci-logs `runs/<id>-a1/p2b-emulator/p2b-test/`).

## 3. Results

CI runs on the last code commit: p2a-app 45293622459 success; p2b-real-engine 45293622649 success (emulator 104+
tests, P3j included); g0-app 45293622468 (bridge C29): see the line added at the end of this report.

Emulator (x86_64 emulator, real engine, public test RAW), `p3j-results.txt`:
```
b_paste     exposure 0.700 -> 2.500 (A 2.500), history 11 -> 12 items, preview 1024x681
b_reopen    same history: true (12 items)
two_photos  2 pasted, 0 failed
exposure_c  2.5000 (A 2.5000); exposure_d 2.5000 (A 2.5000)
```

C29, first run (45285782614, before the test fixes): the pixel checks passed on all three images (B overwrite and C
append against darktable-cli of their XMP: max 0; selective copy of every item + overwrite against A: max 0). The
logic checks failed because of the tests, not the bridge: (a) desktop's list (`dt_history_get_items`) groups by
operation, instance name and priority, so an instance whose name changed (an automatic preset names exposure
`_builtin_scene-referred default`, a later change clears it) is listed twice, and the test took the older entry;
(b) a target keeps its own unsafe/mandatory modules (rawprepare, temperature, flip, gamma), which the comparison
did not leave out on B's side; (c) pasting an identical instance again adds no history item. Fixed in the tests
(commit "take an instance's newest list entry…"), not in the bridge.

## 4. Upstream surprises

- `dt_history_get_items()` (common/history.c) `GROUP BY operation || multi_name, multi_priority`: one instance can
  appear twice in desktop's selective dialog when its name changed during its history. The app's sheet shows the same.
- `gui/hist_dialog.c` at this commit starts the selective copy with nothing ticked (`iscopy ? FALSE : ...`). The
  brief asked for safe modules ticked; the sheet does that and has "select all"/"select none" as desktop.
- `dt_history_copy_and_paste_on_image()` records an undo entry through `dt_history_snapshot_undo_create()` before
  checking for an interface: its two snapshots land in `memory.snapshot_*`, the tables the bridge's own undo uses.
  The bridge clears them after each call (`copypaste.c _paste`); the small `dt_undo_lt_history_t` it allocates is
  not freed (no `darktable.undo` to own it): a few bytes per paste.
- `IOP_FLAGS_UNSAFE_COPY` modules at the pinned commit: flip, gamma, mask_manager, rotatepixels, scalepixels,
  temperature, rawprepare.

## 5. Open questions for the reviewer

1. The clipboard is a library image (`dt_image_duplicate_with_version`, renamed so lookups by file name never find
   it). Alternative: keep rows in memory and re-implement the static merge/overwrite functions. Chosen to call
   upstream's function unchanged.
2. Pasting onto a photo without an XMP first makes its default edit (decodes the RAW, as `dtb_open`): slower, but
   white balance defaults need the RAW. Desktop's images have it from their thumbnail.
3. Pasting onto the photo the history was copied from is allowed (desktop refuses the same image id; here versions
   of one RAW share one image).
4. Selective paste keeps its choice for later pastes (desktop's `copy_paste.selops`); fine for the app?
5. The photo list's choose mode is new (the list had no selection); rows of read-only LPL photos cannot be chosen.

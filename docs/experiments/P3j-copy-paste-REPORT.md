# P3j: copy and selective paste of the history — report

**State (24 Nov, after review round 2).** All three workflows green on 767ef5d2 (run ids in section 3). The arw
failures of the first rounds were investigated with a reference built from desktop's own path:

- **Item 1 (arw: the priority-2 instance with its circle, selectively appended onto C, "lost") is upstream's own
  behaviour.** `engine/tests/dtb_p3jref.c` calls `dt_history_copy_and_paste_on_image()` on plain library images
  (darktable-cli's import and XMP read, no bridge session, clipboard image or undo). The XMP it writes has the
  instance and its two shapes (15 entries), and so does the bridge's (`p3j_c_pasted.xmp`: equal). But with the module
  order copied (`copy_iop_order`, as desktop's copy always does), upstream writes the source's order merged with the
  target's multi-instances (`dt_ioppr_merge_multi_instance_iop_order_list`) after the merge, over the order the merge
  needed: C's module order then has exposure 0 and 1 but not 2 (`RESULT C's module order has exposure 0, 1, 2:
  1 1 0`). Reading the history back drops the item (`develop.c dt_dev_read_history_ext`: "illegal iop_order"),
  in desktop's darkroom after a paste onto its image (`dt_dev_reload_history_items`) and when the photo opens again:
  desktop's path read back gives 14 items, end 15. The bridge's photo opened from the pasted XMP equals that read-back
  exactly. C29 now expects desktop's result at both points (the XMP written, the photo read back). On cr2 and raf
  (the new instance is priority 1) everything is kept on both sides.
- **Item 2 (22 items, end 23 after paste, undo, redo)** comes from the same upstream step, not from the undo
  snapshot clean-up: the end counts the item the read-back drops (desktop's read-back: end 15 of 14 items). Fixed in
  the bridge: after a paste onto the open photo the end is set to the number of items when it is above (logged on
  stderr), so `history()`, undo/redo and the reopened XMP agree (C29 checks end <= items after the paste and after
  undo and redo; the comparator reads an end above the entries as the entries, as darktable does).
- **Refusal**: pasting onto the photo the history was copied from is refused (ARG), as desktop; versions of one RAW
  share one library image and count as that photo. Tested in C29, the contract test and the JVM test.
- On the way: the clipboard image is no longer renamed (photos are looked up without it, `dtb__image_id`) and one
  clipboard image serves the engine's life. Neither changed the arw result (the cause was upstream's, above); kept as
  simpler.

## 1. Brief item by item

| Item | State |
|---|---|
| D1 engine: copy all or a list of instances to a clipboard; paste onto the open photo or onto XMP/raw pairs, append or overwrite; one undo step on the open photo | **done** — `engine/src/copypaste.c`, five calls (engine-api.md section 9). Every copy and paste is upstream's own `dt_history_copy_and_paste_on_image()`. "One history item per paste" read as one undo step: the history gets one item per pasted module, as desktop (needed for the same XMP as desktop). |
| D2 app: copy, selective copy… (ticked = what a plain copy takes, unsafe modules unticked), paste, selective paste…, mode append/overwrite remembered; darktable and modern: photo menu; photo list: choose photos, paste with BatchProgress, LPL photos fetched first; l1: `more` | **done** — `darkroom/CopyPaste.kt`, `ui/CopyPasteSheets.kt`, photo menu (`Sheets.kt`), l1 `more` tool (`L1Panel.kt`), photo list choose mode (`PhotoList.kt`, app-engine `Screens.kt`); also "paste onto photos…" over the series in the darkroom. Mode remembered in `files/settings/paste-mode.txt`. |
| D3 pasted LPL photos marked edited and synced | **done (by the existing path)** — each pasted photo is reported with `PhotoSource.left(item)`, the call the darkroom makes after saving a photo, which marks it and schedules the sync (AppHost). Not tested against a stub server in this package. |
| Tests: bridge | **done** — C29 (`engine/tests/dtb_test_p3j.c`), reference `engine/tests/dtb_p3jref.c` (desktop's own path) and `p3j_xmp_compare.py`. |
| Tests: JVM | **done** — `P3jCopyPasteTest` (3 tests), contract test `copyAndPasteTheHistoryBetweenPhotos` (stand-in). |
| Screenshots | **done** — `phone-p3j-{darktable,modern}-{menu,copy-sheet,paste-sheet}`, `phone-p3j-l1-more`, `phone-p3j-paste-onto` (p2a-app job screenshots). |
| Emulator | **done** — `P3jScenarioTest` (copy A, selective paste onto B, B renders, B's XMP reopens identical, paste onto two photos) and the contract test on the real engine: both pass (run 45293622649). |
| Docs, CI triggers | **done** — engine-api.md section 9, ui-spec.md section 15, branch in g0-app, p2a-app, p2b-real-engine triggers, P3jScenarioTest in `tools/app/emulator-p2b.sh`. |

## 2. How to reproduce

- Bridge: `engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests> <out> C29` (g0-app job `linux`).
- JVM: `cd app && gradle :engine-api:test --tests '*copyAndPaste*' :darkroom:testDebugUnitTest --tests '*P3j*'`.
- Emulator: p2b-real-engine workflow; results in `files/p2b-test/p3j-results.txt` (ci-logs `runs/<id>-a1/p2b-emulator/p2b-test/`).

## 3. Results

CI runs on 767ef5d2: g0-app 45342762685 success (bridge tests, C29 on cr2, raf, arw: every comparison equal);
p2b-real-engine 45342762698 success (first attempt failed only on the known flake
P3eScenarioTest.p4_toneEqualizerDragOnThePhoto; whole workflow re-run as the brief says); p2a-app 45349847064 success
(dispatched; c761954e's p2a had passed its tests but failed to publish its release files with HTTP 403, a token
permission outside this branch). Earlier: one P3iExportsTest heartbeat flake in lpl on 5c129808, passed on re-run.

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
3. Answered: refused as desktop.
4. Selective paste keeps its choice for later pastes (desktop's `copy_paste.selops`); fine for the app?
6. Upstream's paste with the module order copied loses a pasted multi-instance whose priority the target's order
   lacks (above). The bridge reproduces desktop; a patch in `patches/` could keep it. Wanted?
5. The photo list's choose mode is new (the list had no selection); rows of read-only LPL photos cannot be chosen.

# P3h: snapshots with a split view — report

Branch `p3h-snapshots` (from `main` b35edb7). Brief: `docs/briefs/P3h-snapshots.md`.

**Not met first:** bridge test C28 fails on two of its checks, on all three test raws: the live picture rendered
*before the first library write and reload* of the session differs from the live picture after one (arw max 36 /
mean 14.6, cr2 37 / 19.7, raf 20-21 / 16.7, 0..255). A `history_end` render reloads (it takes `history_goto`'s steps),
and so does a plain `history_goto` round trip: C28's diagnostics show the same difference after goto and back. Every
other C28 check passes: `history_end` equals goto + render + goto back pixel for pixel at all 6 positions on all three
raws, history / undo state / open item unchanged, cancel works. Section 3 and open question 1.

## 1. What the brief asked and what was delivered

| Item | State |
|---|---|
| Engine call that renders a stored history position (preview, region, size; same options) without moving the history end, cancel-safe, documented, tested, in Kotlin | **partly** (C28: two live-picture checks fail, see above). Render option `"history_end": n` for `render_preview_ex`, `render_region_ex`, `render_size_ex`; Kotlin `RenderOptions(historyEnd = n)` / `RenderOptions.at(n)`; engine-api.md section 8. History, its end, undo state, the open item and the live picture stay as they were, and a cancel works (C28). Equal to goto + render + goto back at every tested position. |
| "before" moves onto it | done (`DarkroomController.renderBefore`: `renderPreview(size, RenderOptions.at(0))`, no history_goto). |
| Snapshots panel (take / list / select / delete, at most as desktop keeps, memory only) | done. Desktop's `MAX_SNAPSHOT` is 10 (`src/libs/snapshots.c`), not 4; named as desktop (`_take_snapshot`: module of the item at the end, instance name, "original" at 0) with the position number. |
| Split view: snapshot one side, live the other, draggable divider (44 px touch), switch sides, vertical/horizontal, zoom and pan follow, edits keep the snapshot fixed | done (Photo.kt; the snapshot side also gets its sharp region when zoomed in). |
| darktable and modern skins as the mockup; l1 `snapshot` in `more`, compare opens the split with the last snapshot | done (snapshots section above the history: phone history tab, tablet left panel, l1 more; l1 compare toggles the newest snapshot, "before" when there is none). |
| Tests: bridge, JVM, screenshots, emulator | bridge C28: equality, history, cancel pass; 2 live checks fail (see top); JVM tests pass; screenshots made; emulator scenario and contract test pass. |
| Docs, CI triggers | engine-api.md 8, ui-spec.md 14, brief copied, branch in g0-app / p2a-app / p2b-real-engine triggers, `P3hScenarioTest` in `tools/app/emulator-p2b.sh`. |

## 2. How to reproduce

- Bridge: `engine/tests/run_tests.sh <bridge build> <dt build> <test images> <out> C28` (CI: g0-app, job linux).
  Logs `g0-linux/<raw>/C28.log` on the `ci-logs` branch.
- JVM: `gradle :darkroom:testDebugUnitTest --tests '*P3hSnapshotsTest*'`, `:engine-api:test` (contract test
  `aRenderOfAHistoryPositionEqualsGotoAndBack` on the stand-in engine). CI: p2a-app.
- Screenshots: `P3hSnapshotsScreenshotTest` (p2a-app, `screenshots/*p3h*`).
- Emulator: `P3hScenarioTest` and the contract test on the real engine (p2b-real-engine).

## 3. Results

Last code commit 72430223 (merge of main; last own code commit b22cdf78); final CI on the merge with main (72430223): p2a-app 45190018651 green;
p2b-real-engine 45190018663 green on its second attempt (the first failed only in `compare`: the spektrafilm data
pack download for darktable-cli's reference failed, `fetch_spektrafilm_pack.py` urlopen error; the whole workflow was
rerun as instructed, spektrafilm PSNR 60.42 then); g0-app 45190018713 failed only C28 (374 PASS, 3 FAIL = C28 on
arw, cr2, raf).

Bridge C28 (run 45190018713, x86_64 CI, 2 cores; logs `g0-linux/<raw>/C28.log` on `ci-logs`):

| raw | history items | history_end vs goto+render+back (6 positions, preview + region) | 6 history_end renders | cancel | live before first reload vs after |
|---|---|---|---|---|---|
| arw | 21 | max 0 at every position | 5.7 s | CANCELLED, 0.43 s after the cancel (render 1.13 s) | **max 36, mean 14.6** |
| cr2 | 16 | max 0 | 3.0 s | 0.23 s (0.56 s) | **max 37, mean 19.7** |
| raf | 19 | max 0 | 9.7 s | 0.78 s (1.75 s) | **max 20, mean 16.7** |

C28 diagnostics: after each goto round trip, `history_end p` again equals it before the gotos and equals goto p
(max 0); the live picture after any goto round trip equals the live after `history_end` renders, and both differ
from the very first live render. In a fresh session (XMP alone, then vignette + new exposure instance + prototype
circle, all sealed) `history_goto(end)` changes nothing (max 0). What the main case has besides: exposure 0 changed
twice, the last change appended after sealed items and still open; so the first render after that change (pipe
updated by `DT_DEV_PIPE_TOP_CHANGED`, history.c `_history_add`) and the render after a reload (full synch) disagree.
Which of the two is right is not settled here (open question 1).

Fixes made on the way (each found by CI):
1. First version moved the module state in memory (`dt_dev_pop_history_items_ext` there and back). Replaced by
   `history_goto`'s own steps (write, move, reload, and back, without seal or group commit) so that equality holds by
   construction; costs 2 reloads per call (C28 timings above).
2. `dtb__render_opts_parse` left `history_end` at 0 for NULL options, so every plain render showed the original
   (this also broke the emulator's later tests in run 45159564316). Fixed in 151806ea.
3. Screen rules: the divider is drawn with rects and the ui-icons `splitHandle`, not drawLine/drawCircle.
4. Snapshot pruning: a change after a tap on an older history item now drops the snapshots above it even when
   `history()` looks the same (JVM test).

Emulator (run 45190018663 attempt 2, x86_64 emulator, real engine; `p2b-test/p3h-results.txt`): 101 PASS, 0 FAIL;
contract test `aRenderOfAHistoryPositionEqualsGotoAndBack` passes; `P3hScenarioTest`: history end 9 -> 10 after
the change, engine 10; split means (0..255) left half snapshot 139.13 vs live there 196.95, right half live 204.00
vs snapshot there 145.05; snapshot picture vs a fresh `history_end` render: max difference 0.

JVM (p2a 45190018651): `P3hSnapshotsTest` (take/select/delete/limit 10, split state, pruning, before and snapshots
rendered without history_goto, another photo has none), `FakeEngineContractTest` incl. the new contract test.
Screenshots: `phone-p3h-split-vertical-{darktable,modern}`, `phone-p3h-split-horizontal-{darktable,modern}`,
`phone-p3h-divider-dragged-darktable` (touch drag moved the divider past 0.6), `phone-p3h-l1-more`,
`phone-p3h-l1-compare`, `tablet-p3h-split-vertical-darktable`, `tablet-p3h-split-horizontal-modern`.

## 4. Upstream

- `src/libs/snapshots.c`: `MAX_SNAPSHOT 10`; a snapshot is a copy of the history in memory tables
  (`dt_history_snapshot_create`), so it survives later history changes. Here it is a history position, kept only
  while the history up to it is unchanged (`SnapshotRules.prune`).
- `src/common/iop_order.c`, `dt_ioppr_resync_modules_order` renumbers `dev->iop_order_list` on every call.

## 5. Open questions

1. The first live render after an appended, still open change differs from the render after a reload (C28, table
   above). It is not caused by `history_end` (a plain `history_goto` round trip shows the same), but snapshots and
   "before" now trigger a reload, so the live picture may change by that amount the first time one is used. Should a
   follow-up compare both with a fresh session opened from the XMP written at that state, to see which pipe path
   (TOP_CHANGED synch_top or full synch) is wrong?
2. `history_end` costs two library writes and reloads per call (preview and region each). The app renders a snapshot
   once (and its region per zoomed view). Acceptable, or should a cheaper path come back once (1) is understood?
3. Snapshots are history positions, not copies as on desktop (`dt_history_snapshot_create`); they go when the
   history below them changes, and they do not cross photos. Desktop's "restore" and its side-by-side mode are not
   built.
4. C28's prototype `dtb_mask_add_circle` (S3 API) is used for the masked instance; the P1b mask calls would be the
   current way.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqEFKnbCpNBgZS8tVR

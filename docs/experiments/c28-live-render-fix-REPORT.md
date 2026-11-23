# C28 live-render fix — report

Branch `c28-live-render-fix` from main 0207c5aa. Last code commit e1994e4e.

## 1. Asked and delivered
| Item | State |
|---|---|
| Which live render matches darktable-cli of the XMP of that state | done: the picture **before** the reload is right |
| Fix the other path in the bridge | done: `history.c` `_reload_keeping_forms` |
| C28 passes unweakened; all three workflows green | done; C28 also gained a CHECK: `history_goto(1)` and back leaves the picture unchanged |

## 2. Cause
`dt_masks_read_masks_history` (upstream `develop/masks/masks.c`) reads back only the masks with `num < history_end`.
A library write followed by a reload at a lower end, as `history_goto` and the P3h `history_end` renders do, left
the items above the end without their shapes. The next write then dropped those shapes for good. The fix keeps every
item's shapes across that reload. No upstream edits.

## 3. Evidence
- Run 45212159947: the bridge picture equals darktable-cli of its own XMP, before and after a reload at the
  history's own end (max 0, all three raws).
- Run 45222664080: after `history_goto(1)` and back:
  - the bridge picture equals darktable-cli of the XMP written *after* the round trip;
  - it differs from darktable-cli of the XMP written *before* it (arw max 36 / mean 14.5, cr2 37 / 19.5,
    raf 22 / 16.5);
  - the difference appears only with a drawn mask (variants: no instance 0, instance without circle 0).
- Run 45232770525 (after the fix): C28 passes on arw, cr2 and raf, and all round trips give max 0. The bridge tests
  show 392 PASS and 0 FAIL.
- Timings (x86_64 CI, 2 cores): 6 renders with `history_end` take 5.8 s (arw), 3.0 s (cr2), 9.6 s (raf), each one
  costing two library writes and reloads.

## 4. Reproduce
`engine/tests/run_tests.sh <bridge> <dt> <images> <out> C28`; logs are `g0-linux/<raw>/C28.log` and
`bridge-tests-comparisons.txt`.

## 5. CI
- g0-app 45232770525: green. The first attempt failed while fetching a Maven package (`error_prone_annotations`
  could not be downloaded); I reran the failed jobs.
- p2b-real-engine 45232770538: green.
- p2a-app 45233660448: green, started by hand because p2a does not trigger on engine-only changes.

## 6. Open question
The same reload limit also applies to undo restores and `history_compress` (`dtb__reload_dev`). Those change the
item list, so the shapes cannot be matched by position there. Has undo after a goto to a lower end ever lost a
mask? It is not tested here.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqEFKnbCpNBgZS8tVR

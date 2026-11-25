# Bridge test ids

The registry of every id used by `engine/tests/run_tests.sh`. **New ids are taken from here:** add a row for the next
free id (next is **C41**) in the same commit that adds the test, and say in your pull request which id you took.
Two parallel branches once both took C37; `check_test_ids.sh` (run first by `run_tests.sh`, also by hand) now fails
when an id is used in `run_tests.sh` without a row here, when a row has no test, when an id appears twice in this
table, or when a test block with the same id appears twice (T6 and C6 share one block and are the only exceptions,
listed under "Shared ids" below). A sub-test such as C28b, run inside another id's block, gets its own row.

Command: the `dtb_test` sub-command (or the tool) the test runs. Source: file(s) under `engine/tests/` holding the checks.
Brief: the brief (`docs/briefs/`) that introduced it.

| Id | Command | Source | Brief | What |
|----|---------|--------|-------|------|
| T1 | render, modules | dtb_test.c (cmd_render in main flow) | S3 | render equals darktable-cli; modules/history JSON parse |
| T2 | param | dtb_test.c | S3 | parameter change, XMP round trip |
| T3 | instances, exposures, render | dtb_test.c | S3 | multiple instances, reopen |
| T4 | history | dtb_test.c | S3 | history editing |
| T5 | region | dtb_test.c; t5_expected.txt | S3, P1c | region render bounds |
| T6 | guard | dtb_test.c; make_guard_xmps.py | S3 | guard against unknown module / newer version (shares a block with C6) |
| T7 | timing | dtb_test.c | S3 | timing |
| T8 | cancel | dtb_test.c | S3 | cancel a render |
| T9 | mask | dtb_test.c | S3 | parametric mask |
| X1 | extras | dtb_test.c | S3 | export, preview, extras |
| C1 | api | dtb_test.c | P1a | engine_info, image_info, modules JSON |
| C2 | params | dtb_test.c | P1a | paths, types, ranges, params_set, reset |
| C3 | undo | dtb_test.c | P1a | undo/redo |
| C4 | actions | dtb_test.c | P1a | module actions |
| C4d | opencl_lib | dtb_test_p4d.c | P4d | the app's OpenCL library choice is honoured (cr2 only) |
| C5 | threads | dtb_test.c | P1a | set_threads, set_thread_cpus |
| C6 | readonly | dtb_test.c | P1a | read-only refusal (shares a block with T6) |
| C7 | output | dtb_test.c; dtb_test_p1b2.c | P1a | xmp_write, export, metadata_reload |
| C8 | reopen | dtb_test.c | P1c | open(raw, NULL) default edit, open(raw, xmp) |
| C9 | drag | dtb_test.c | P1c | a drag resynchronises one item |
| C10 | e1 | dtb_test.c | P1b | undecodable files refused at open |
| C11 | persist (presets) | dtb_test.c | P1b | presets persist |
| C12 | persist (styles) | dtb_test.c | P1b | styles persist |
| C13 | defedit | dtb_test.c | P1b | default edit equals reopened XMP |
| C14 | actions2 | dtb_test.c | P1b | colorin profiles, denoise, WB tuning, framing, lens |
| C15 | cpus | dtb_test.c | P1b | started with CPU 0 only |
| C16 | times | dtb_test.c | P1b | per-module times |
| C17 | bypass | dtb_test_p1b2.c | P1b2 | a render that leaves one instance out |
| C18 | undohash | dtb_test_p1b2.c | P1b2 | undo/redo keep the history hashes |
| C19 | pickers | dtb_test_p1b2.c | P1b2 | colour pickers, histogram |
| C20 | coords | dtb_test_p1b2.c | P1b2 | coordinates original/picture |
| C21 | blend | dtb_test_p1b2.c | P1b2 | blending |
| C22 | masks | dtb_test_p1b2.c | P1b2 | drawn masks |
| C23 | masks_desktop | dtb_test_p1b2.c | P1b2 | masks against desktop darktable |
| C24 | maskshow | dtb_test_p1b2.c | P1b2 | mask display overlay |
| C25 | dtb_pickref (tool) | dtb_pickref.c, pickref_*.c | P3c | picker actions against darktable's callbacks |
| C26 | masktree | dtb_test_p3d.c | P3d | mask tree |
| C27 | exportprog | dtb_test_p3g.c | P3g | export progress and cancel |
| C28 | snaphist | dtb_test_p3h.c | P3h | render another history position |
| C28b | undomask | dtb_test_p3h.c | P3h | sub-test run inside the C28 block: a drawn mask above the history end survives undo, redo, compress |
| C29 | copypaste; dtb_p3jref (tool) | dtb_test_p3j.c, dtb_p3jref.c | P3j | copy/paste history against desktop |
| C30 | check | dtb_test.c | P2g | stale data.db.lock removed at start |
| C31 | shared | dtb_test_p3l.c | P3l | R11 shared styles/presets |
| C32 | retouch | dtb_test_p3m.c | P3m | retouch shapes, colour look up table |
| C33 | perspective | dtb_test_p3n.c | P3n | perspective guides |
| C34 | memory | dtb_test_p4a.c | P4a | memory info and trim |
| C35 | resources, resources1 | dtb_test_p4b.c | P4b | start-up resource options |
| C36 | opencl | dtb_test_p4b.c | P4b | OpenCL allowed (cr2 only) |
| C37 | rtfocus | dtb_test_p4c.c | P4c | retouch focus |
| C38 | pickwork | dtb_test_p4c.c | P4c | picker in working profile |
| C39 | diffpreset | dtb_test_p4c.c | P4c | diffuse built-in presets |
| C40 | modaudit | dtb_test_p5a.c | P5a | all modules; runs only when named |

## Shared ids

`T6` and `C6` are both named in one block of `run_tests.sh` (`if want T6 || want C6`), so each is allowed two `want` lines.

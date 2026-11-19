# P1a: engine core (Engine API v1, sections 1 and 2)

Brief: `docs/briefs/P1a-engine-core.md`. Branch `p1a-engine-core`.

## Running log (newest first)

- 2026-11-19 (after a server restart): test driver rewritten for API v1 (json-glib for reading JSON),
  new commands api, params, undo, actions, threads, readonly, output. On cr2 so far: T1 bit-identical,
  api/params/undo pass (undo exact over 13 states, 71 steps deep). history_goto now reloads through
  the library (stale per-instance defaults otherwise). Next: run remaining commands on 3 images,
  update run_tests.sh, JNI, CI, write-up.
- 2026-11-19: branch created from main 71964f9; prototype bridge builds against the read-only Linux
  darktable build of the pinned commit. Next: new header, split sources, tests.

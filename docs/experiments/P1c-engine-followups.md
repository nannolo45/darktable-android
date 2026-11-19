# P1c: engine follow-ups and slider colours

Brief: `docs/briefs/P1c-engine-followups.md`. Branch `p1c-engine-followups`. Pull request text:
`P1c-engine-followups-REPORT.md`.

## Running log (newest first)

- 2026-11-19 14:3x: item 5: the bridge flagged every change DT_DEV_PIPE_SYNCH (synch_all: all modules
  reset and history replayed, about 40 ms per render on cr2, workspace); a parameter change merged into
  or appended on top of the history now flags TOP_CHANGED. cr2 preview 320 px after a late change:
  about 70 -> 22 ms (workspace x86_64, 2 cores); test C9 checks drags against a fresh session. First CI
  run (e68181f) failed in the Android engine link: sqlite3 symbols (session.c) not linked; fixed (8b4bb21).
- 2026-11-19 14:0x: items 1, 2, 6 done in the bridge, tests T6 (three guard XMPs), C8 (reopen), C4
  (noise profile index); all pass on cr2 and C4 on arw locally; contract, fake, header, engine-api.md
  updated; app clamp removed; JVM tests pass locally (60, screenshots off). CI triggers added for
  g0-app and p2b-real-engine.
- 2026-11-19: branch created from main 3e252f9. Read the brief, CLAUDE.md, the contract, the P1a, P2b and
  S5 write-ups. Bridge builds on the workspace against the pinned Linux darktable (`/home/claude/dt`).
  Plan: items 1, 2, 6, then 5, 3, 4.

Next: cost numbers on raf/arw, then item 3 (regions), then item 4 (slider colours).

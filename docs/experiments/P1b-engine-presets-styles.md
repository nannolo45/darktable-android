# P1b part 1: presets and styles in the engine, and the open engine points (running log)

Brief: `docs/briefs/P1b-engine-presets-styles.md`. Report: `P1b-engine-presets-styles-REPORT.md`.
Workspace numbers are x86_64, 2 cores; CI numbers are GitHub runners (x86_64, 4 cores). Neither are phone numbers.

## 2026-11-20

- 10:20 Branch from main 5b43bc8. Read CLAUDE.md, the brief, engine-api.md, PLAN 3.2/3.9, P2f 7, P2g 5, P2h 0,
  P2j 5/6/9, P1c.
- **Build decision: rebuild darktable for Linux locally** (`tools/build-engine-linux.sh`, Ubuntu packages, pinned
  source 04477d3 fetched with `tools/fetch-darktable.sh` into a work folder outside the repository). Reason: this
  package needs many short engine iterations (presets and styles against darktable's database, the default-edit
  difference needs a bisection, E1 needs a sanitizer run), which CI cannot give at one Linux rebuild per push. CI
  (`g0-app` for the Linux bridge tests, `p2b-real-engine` for both ABIs and the emulator) is used for the final
  proof, with batched pushes. Started 10:24.
- 10:55 engine-api.md section 5 written and pushed (exact signatures and JSON of every new or changed call).

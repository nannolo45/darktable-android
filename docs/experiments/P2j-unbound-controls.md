# P2j running log

Times UTC. Measurements on the build workspace are x86_64, 2 cores, not phone numbers.

- 2026-11-20: branch `p2j-unbound-controls` from main c4c6996. Read CLAUDE.md, the brief, crop's override (P2f) and
  the checker. Upstream files fetched read-only from raw.githubusercontent.com at the submodule commit
  04477d32a00cc5abaea36d5bee084eb40c6eabb2 into /tmp (not into the repository). Spektrafilm pack fetched with
  `schema/extractor/fetch_spektrafilm_pack.py` at the workflow's pinned ref into /tmp to read the profiles.
- Classification of the 60 written into the report (section 2) and pushed before building.
- Built (schema side): computed sliders (color calibration hue/chroma, saturation curve centre/width, tone
  equalizer smoothing), fixed lists (framing aspect/positions, spektrafilm format, rgb/tone curve interpolation,
  built-in export and working profiles), spektrafilm film/paper/upsampling from a pack table, colour harmonizer's
  saturation sliders bound to `node_saturation[i]` with desktop's visibility, notes for d/c/b controls, the
  vectorscope sync hidden. Reference values from upstream's own C functions (`tools/p2j-reference-vectors.py`).
  JVM schema tests pass locally (x86_64 workspace). The workspace has no Android SDK: the darkroom module compiles
  only in CI.
- Found while building: framing's "image" (0) and "constant border" (-1) and lens' "use latest algorithm"
  (scale_md_v1 = 0) write values outside the fields' limits, which the bridge refuses (`engine/src/params.c`
  NEED_NUMBER/range check). Lens' toggle moved from a to c.
- Color harmonizer hues built through upstream's tables (computed by upstream's own code). Emulator test
  P2jScenarioTest and the darktable-cli comparison added. Darkroom tests on the stand-in engine added.
- Last code commit f64cacf: p2a-app 44698450917 green (186 tests), p2b-real-engine 44698450965 green (JVM 242,
  emulator 43/43, compare green: P2j 44.3 / 47.0 / 46.1 dB). APK dt-android-f64cacf1d111. Report written.

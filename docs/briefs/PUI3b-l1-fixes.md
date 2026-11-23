# PUI3b: l1 fixes (two fixes)

1. Export button in the l1 top bar. L1Darkroom found the host's export action with a label prefix; make it stable: `HostAction.id` (optional, default null), set on the app's export action, L1 picks by id. The button shows whenever the host offers export; the same action is not repeated in the photo menu in l1. Screenshot test shows the button; a test that L1 finds export by id.
2. Sideways l1 sections column: every cell at least 44 dp (token in l1-tokens.json); when they do not fit the column scrolls vertically. Tests: the token is >= 44; in the sideways screenshot with nine sections every section is reachable.

Rules: branch `pui3b-l1-fixes` from main; add it to the push branch lists of p2a-app.yml and p2b-real-engine.yml; do not touch MasksPanel.kt, MaskTree.kt, engine/, upstream/. Report: docs/experiments/PUI3b-l1-fixes-REPORT.md.

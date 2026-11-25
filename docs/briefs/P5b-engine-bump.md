# Brief P5b: move the engine to darktable's current development version (PLAN.md 6.1 item 2)

Branch `p5b-engine-bump` from `main`. Read CLAUDE.md, PLAN.md 2.1 (engine version, module-version guard), 5 (repository and update procedure), `docs/UPDATE.md`, `tools/bump-upstream`, `tools/apply-patches.sh`, the P4e report (patches in every build) and the S5 schema report (schema-diff).

## Target
The owner's PCs (Windows Desktop, Fedora Laptop) run darktable **5.7.0+1090** (25 Nov 2026). Pick the upstream `master` commit whose `git describe` count is 1090 after the 5.7.0 development tag (or the closest commit not newer than it; if the count is ambiguous between first-parent and full history, say how you decided). Goal: the phone's module versions equal or are no newer than the PCs', so XMPs written on the PCs open and are rewritten safely on the phone.

## Deliverables
1. Run the update routine as `docs/UPDATE.md` describes, with `tools/bump-upstream <commit>`: move the submodule pin, re-apply `patches/` (fix them if they no longer apply, keeping their reason), vector-subscript check, rebuild the engine (new ENGINE_TAG), regenerate the control schema and module names, schema-diff.
2. Act on the schema-diff: new or changed controls picked up (Tier 1 automatically); Tier 2/3 modules that changed are listed and fixed where the change breaks the app's hand-built editors (curves, bands, masks, crop/perspective, retouch, look up table, pickers…); new modules listed.
3. Module-version table: every module's version at the old pin, the new pin, and what that means for XMPs (old XMPs still open; the version guard behaves).
4. All CI green (p2a, p2b, g0), including the darktable-cli comparisons, R11 hash vectors, and C36 (GPU path on PoCL).
5. Fix whatever `docs/UPDATE.md` got wrong in practice, in that file: this is the first real bump and proves the routine.

## Report
`docs/experiments/P5b-engine-bump-REPORT.md` as CLAUDE.md says: the chosen commit and why, the diffs, CI ids, and what the owner must know (e.g. module versions newer than before; nothing for him to do on the PCs if they stay at 5.7.0+1090 or newer).

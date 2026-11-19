# S5: generated control descriptions (pull request description)

Branch `s5-control-schema`. The `gh` command is not signed in on the build workspace, so this file is the pull request description. Full write-up: `docs/experiments/S5-control-schema.md`.

**No darktable changes.** Nothing under `upstream/` or in the darktable source/build/install trees was modified; no `patches/`. The extractor reads a few private bauhaus fields through a struct copy generated from `bauhaus.c` at build time and cross-checked against public getters (0 disagreements). That copy is the main fragility.

**Not everything works, stated first:**
- The schema alone gives a usable panel for **9 of the 29 priority modules**, part of the panel for 12, and none for 8. Several modules that PLAN section 6 lists as "usable in Phase 2 with generated controls" need overrides: white balance (temperature, tint and presets are not bound to parameters), orientation (four buttons), LUT 3D (file), lens correction (camera/lens choosers); spektrafilm, denoise (profiled), color balance, color equalizer and color harmonizer are mostly but not fully covered.
- Visibility rules are learned from default parameters on one image. They do not capture dependence on the image (shown by the X-Trans diff: 11 modules differ), on slider values, on parameters only old edits change, or on notebook tabs. In the hand check, 43 of 48 rules match the source exactly; 1 is only right as a table (an OR), 3 miss a legacy-parameter condition, 1 cannot be expressed.
- Gettext contexts of widget labels are not recorded (darktable strips them before storing the label); the strings are the untranslated English ones.

## 1. Brief items

| Brief item | Status |
|---|---|
| 1. `schema/extractor/`: out-of-tree plugin, built with darktable's flags, loaded from a private module directory, runs in real darktable under Xvfb, walks every module, writes JSON | **done**. Lib plugin; flags from darktable's compile line for a lib plugin; symlinked module dir; 97 modules listed, 90 panels walked, 7 have no panel; no crash in final runs |
| 1. per module: op, name, version, default group, flags, on-image drawing/mouse, custom widgets | **done** (module groups as `default_group()` bits; the presets in `modulegroups.c` are not extracted) |
| 1. per control in visual order with containers: type, label, tooltip, bound parameter, hard/soft range, default, step, digits, factor/offset, format, list entries with values | **done**. Sections, tabs, collapsible groups, stacks, rows, grids. Format, factor, offset, section, quad and entry values come from the private struct copy |
| 1. unbound controls listed and marked | **done** (97 sliders/lists/switches, plus 103 buttons, 26 picker buttons, 46 graphs/custom) |
| 2. visibility rules from sweeping lists and switches; limits stated | **done**, with a self-check of each rule against every observed state, a table fallback for non-AND rules, sensitivity rules as a bonus. Limits in section 6 of the write-up |
| 3. `schema/ui-schema.json`, `tools/schema-extract.sh`, `tools/schema-diff.py` | **done**. Regeneration is byte-identical |
| 4. coverage report, table for the 29 priority modules | **done** (write-up sections 3 and 7; generated tables in `S5-results/`) |
| 5. hand check of visibility rules for at least five modules | **done**: 8 modules, 48 rules (section 5) |
| 6. verdict | **go, with conditions** (section 8) |
| Translations | out of scope as briefed; untranslated strings recorded, **gettext context only for bound controls** (via the parameter description) |

## 2. How to reproduce

```
# clean Ubuntu 24.04, packages from engine/tests/build-darktable-linux.sh plus: apt-get install -y xvfb python3
git submodule update --init --depth 1 upstream/darktable
engine/tests/build-darktable-linux.sh /tmp/dt-build /tmp/dt-install
engine/tests/fetch-test-images.sh /tmp/dt-tests
tools/schema-extract.sh upstream/darktable /tmp/dt-build /tmp/dt-install /tmp/dt-tests/images/mire1.cr2 /tmp/s5-work
git diff --stat schema/ui-schema.json      # the script rewrote it in place: expect no change
# counts and tables:
python3 schema/extractor/coverage.py schema/ui-schema.json [--priority | --unbound]
# image dependence:
tools/schema-extract.sh ... /tmp/dt-tests/images/xtransIV.raf /tmp/s5-xt /tmp/s5-xt/ui-schema.json
tools/schema-diff.py schema/ui-schema.json /tmp/s5-xt/ui-schema.json
```
(`schema-extract.sh` writes to `schema/ui-schema.json` unless a sixth argument names another file.) The script downloads spektrafilm's public data pack (8.9 MB, checksums from its manifest) into the work directory; `SKIP_SPEKTRAFILM_PACK=1` skips it.

## 3. Results (x86_64, 2 cores; not phone numbers)

- 902 controls; 630 bound to a parameter (69.8 % of all, 86.7 % of sliders/lists/switches), 625 distinct parameters, 0 unresolved.
- 711 scalar parameters, 582 with a control (81.9 %); 43 of 33 021 array values have a slider (the rest are graphs and on-image tools).
- 32 modules with graphs/custom widgets, 12 drawing on or taking input from the image.
- 18 tab sets, 30 collapsible sections, 65 section headings, 13 stacks.
- 145 visibility rules (143 AND rules consistent with all 1374 observed states, 2 table rules, 1 inconsistent), 17 on interface-only lists, 62 sensitivity rules; 90 widgets hidden in every observed state.
- Priority modules: 9 yes, 12 partly, 8 no (table in section 7).
- Run time: `schema-extract.sh` 41 s wall including the plugin build and pack download; about 25 s inside darktable.
- Determinism: two regenerations from scratch are byte-identical.

## 4. Surprises in upstream

- `src/bauhaus/bauhaus.c`: the widget struct is private and there are no getters for the slider format, factor, offset, the widget section, the quad paint/tooltip or a list entry's value.
- `dt_bauhaus_widget_set_label` stores `Q_(label)`: the gettext context is gone before anything can read it.
- `src/iop/temperature.c:1940` (`_preset_tune_callback`) dereferences a preset entry that does not exist when a list heading of the preset list is selected programmatically. A user cannot select headings, so this is not a user-facing bug, but it crashed the first run.
- `src/iop/colorbalance.c:1711` (`_configure_slider_blocks`) destroys and recreates widgets during an update, depending on a layout preference.
- darktable shows a first-run welcome dialog with its own main loop inside `dt_init` (`src/gui/welcome.c`, `dt_welcome_screen_run_if_needed`); `--conf ui/show_welcome_screen=false` is needed for unattended runs, or quitting hangs.
- spektrafilm hides all controls until a data pack from a separate repository is installed (`src/common/spektra_fetch.c`).
- `darktable-mcp` (`src/mcp/main.c:110`) starts the engine with `init_gui = FALSE` and exposes introspection and history, not widgets. The Lua sources (`src/lua/`) have no access to bauhaus widgets (no reference to them), and Lua is off in this build anyway. Neither could replace the plugin.

## 5. Open questions for the reviewer

1. Overrides: should `schema/overrides/` (PLAN 3.3) become an explicit Phase 2 work item for about ten priority modules, and should the "usable in Phase 2" list in PLAN section 6 be corrected to what section 7 found? (No change to PLAN was made here.)
2. Image classes: extract per class (Bayer, X-Trans, non-raw, monochrome) and merge, or add a bridge call that reports which variant applies? The first is cheap with this tool.
3. The spektrafilm data repository is read at branch `main`; pin it to a tag or commit in CI?
4. Is reading the private bauhaus struct acceptable as a standing dependency, or should an upstream pull request adding the missing getters be proposed?
5. The schema file is 1.4 MB of indented JSON; fine to commit per pinned version, or should the app get a compacted copy?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

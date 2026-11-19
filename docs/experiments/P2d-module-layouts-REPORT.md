# P2d: module layouts as in darktable (pull request description)

Branch `p2d-module-layouts`. Running log, owner instructions: `P2d-module-layouts.md`. Results:
`P2d-results/`.

**Status: all nine deliverables and the four acceptance points are met, with the limits listed below.
Nothing failed on the last code commit.** Proof: CI run 44499763458 on 3c6190e (see section 3). The run
before the fixes, 44494055618 on bf9f344, failed: two screenshot tests had wrong expectations (2.6).

Not exercised anywhere: the app on a phone, tablet or emulator (only JVM screenshot tests); Android's real
file picker (the screens are tested with a stand-in, `MainActivity`'s storage access code only compiles);
import into desktop darktable of a file exported **from the app's screens** (desktop imported the app's
codec output, see 2.4, which is the same code path); the real engine (everything runs on the fake engine).

The work was done by two agents in turn: the first was stopped by an interruption unrelated to the work;
the second finished it (from commit f24d9d1 on).

## 1. Brief, item by item

| # | Deliverable | State | Where |
|---|---|---|---|
| 1 | Layout model; parse and write darktable's string; byte-exact round trip of every built-in | done | `app/schema/.../layout/Layout.kt` (`Layout`, `LayoutCodec`, ports `_preset_from_string`/`_preset_to_string` with their quirks); `LayoutTest.everyBuiltinLayoutRoundTripsByteExact` (30 layouts, 6 workflows) |
| 2 | Built-in layouts by a repeatable tool, upstream commit recorded, fails loudly | done | `tools/layouts/extract-builtin-layouts.py` copies `init_presets()`, `_presets_get_minimal()` and their macros verbatim from `modulegroups.c`, compiles them with stand-ins, runs once per workflow → `schema/builtin-layouts.json` (`darktable_commit`, sha256 of the three source files). CI reruns it with `--check` against a fresh sparse checkout of the pin. Failure checks: `P2d-results/tool-failure-checks.txt`. Compared with a real darktable run: `P2d-results/verify-with-darktable.txt` (30 of 30 identical) |
| 2a | the deprecated layout | done (none exists) | at 04477d3 "modules: deprecated" is inside `#if 0` in `init_presets`; the data file records `deprecated_layout_built: false` |
| 3 | Darkroom on the current layout: tabs, groups, quick panel, active group, deprecated modules, search | done | `DarkroomController.visibleModules`, `quickPanel`, `loadLayout`; `ModuleList.kt` (`GroupTabs`, `SearchLine`, `QuickPanel`) |
| 4 | Layout editor (phone and tablet), reached from the tabs' row | done | `LayoutEditor.kt` (rules), `LayoutEditorScreen.kt`; menu at the end of the tabs → "manage module layouts…" |
| 5 | Import / export as `.dtpreset` through Android's file picker; what desktop offers, written down | done | `DtPreset.kt`; `MainActivity` (`OpenMultipleDocuments`, `CreateDocument`); section 2.4 below |
| 6 | `LayoutStore` in `engine-api`, file implementation | done | `LayoutStore.kt`, `FileLayoutStore.kt` (app-private `layouts/layouts.json`); screens use only the interface |
| 7 | White balance as the manual says | done | default quick panel has color calibration's controls and no white balance control (asserted in `LayoutDarkroomTest` and `PhoneScreenshots.quickPanel`); desktop's warnings: follow-up, section 4 |
| 8 | Tests | done | 107 tests (section 3); quick access table `P2d-results/quick-access-mapping.md`; changed expectations listed in 2.6 |
| 9 | CI and preview; owner instructions | done | `.github/workflows/p2a-app.yml`; release `app-preview-3c6190ecfc21` (section 3); "For the owner" in `P2d-module-layouts.md` |

| Acceptance | State | Evidence |
|---|---|---|
| Byte-exact round trip of every built-in layout; tool reruns cleanly from a clean checkout | met | `LayoutTest.everyBuiltinLayoutRoundTripsByteExact`; CI step "Built-in module layouts match the pinned upstream" (fresh sparse checkout, `--check`, log `layouts-check.log` on `ci-logs`) |
| Default layout's groups and quick panel equal desktop's (scene-referred, 04477d3) | met, with 5 quick access entries shown disabled | `P2d-results/default-layout-comparison.md` (written by `LayoutDarkroomTest.defaultLayoutIsDarktablesSceneReferredLayout`, also on `ci-logs` per run); the stored layout string is the one a real darktable stores (`verify-with-darktable.txt`) |
| Edited in the app, exported, imported again: identical | met | `LayoutDarkroomTest.editExportImportIsIdentical` (every editor operation, export, delete, import: same layout, same stored bytes, same file) |
| CI green on the last code commit | met | run 44499763458 on 3c6190e |

## 2. What was built, and the findings

### 2.1 Default layout compared with desktop

Desktop at 04477d3 with the pixel workflow "scene-referred (sigmoid)" (its default): tabs *active, quick
access, base, color, correct, effect*, search line on, opening group *color* (`plugins/darkroom/groups` =
2). The app's default layout is the same string. Per group, the set of modules the app lists equals the
group's modules (all four groups: same set = true). Order: desktop lists a group in pipe order, last in
the pipe on top; so does the app.

Quick access panel, top to bottom (module order as `_basics_show`: last in the pipe first, then each
module's widgets in its panel's order): sigmoid (contrast, skew), color balance rgb (4), color equalizer
(page, graph, node placement), contrast & texture (fine details), color calibration (illuminant, F source,
LED source, temperature, hue, chroma), tone equalizer (2 sliders, graph), exposure, rotate and perspective
(rotation), lens correction (on-off), denoise (profiled) (strength). 23 entries, **18 usable, 5 shown
disabled with their name and reason**: color calibration hue and chroma (desktop computes them, no
parameter; needs engine support), color equalizer page (notebook tabs) and graph, tone equalizer graph
(graph editors are Phase 3). No white balance entry.

### 2.2 Quick access mapping (full table: `P2d-results/quick-access-mapping.md`)

Every entry of every built-in layout and of desktop's "recommended" list: **38 entries, 32 mapped**
(29 to a schema control, 3 on-off), **6 not mapped**: `channelmixerrgb/chroma`, `channelmixerrgb/hue`
(no parameter behind them), `colorequal/page` (notebook), `colorequal/graph`, `toneequal/graph` (graphs,
Phase 3), `agx/auto tune levels` (see section 4). Entries that cannot be mapped are kept in the layout and
shown disabled with their name; nothing is dropped. Entries of modules the panel does not show (deprecated,
or not in the pipe) come last, disabled, with the reason.

### 2.3 Layout editor

As "manage module layouts": choose the layout to edit; new (from darktable's minimal layout,
`_presets_get_minimal`), duplicate, rename, remove (with desktop's confirmation text); built-in layouts are
read-only with desktop's message and can only be duplicated; options "show search line", "show quick
access panel", "show all history modules in active group"; quick access: add (searchable list, desktop's
"recommended" first), remove; groups: add, rename, icon (darktable's icon names), move up/down, remove
(refused for the last group while neither search nor quick access is shown, as desktop), add and remove
modules (searchable list, "recommended" by default group first); "reset". Choosing the current layout is in
the menu next to the tabs, where desktop has its presets menu. Edits are kept when another layout is
chosen and when the editor closes (back button included), as desktop does.

### 2.4 What desktop offers for layout files (from upstream's source)

- Layouts are presets of the lib module `modulegroups`, version 1; `op_params` is the layout string
  (`_preset_to_string`) with a terminating zero.
- **Export:** preferences → presets → "export..." (`export_preset` in `src/gui/preferences.c`) writes every
  user preset (not write-protected) as `<operation>_<name>.dtpreset` into a chosen folder, so layouts come
  out as `modulegroups_<name>.dtpreset`. The preset edit dialog (`src/gui/presets.c`) can export one preset.
  The file is written by `dt_presets_save_to_file` (`src/common/presets.c`): XML `darktable_preset`, with
  `op_params` encoded like XMP blobs (`dt_exif_xmp_encode`: hex, or "gz" + compression factor + base64 of
  zlib data above 100 bytes with the default setting).
- **Import:** preferences → presets → "import..." (`dt_presets_import_from_file`), `INSERT OR REPLACE` on
  (name, operation, op_version): a layout of the same name is replaced.
- So desktop can export layouts as files; no new format was needed. The app reads and writes exactly this
  format. A real darktable at 04477d3 exported a layout (fixture `desktop-export.dtpreset`, which the app
  imports in a test and in screenshot `phone-23-imported-layout`) and imported two files written by the
  app's code (`verify-with-darktable.txt`). The app's file differs from desktop's only in the compressed
  bytes (zlib versions), not in content.

### 2.5 Storage

`LayoutStore` (list, read, write, rename, delete, current, setCurrent, workflow) in `engine-api`; built-in
layouts come from `builtin-layouts.json` for the chosen workflow and cannot be written; `FileLayoutStore`
keeps user layouts and the current name in one app-private JSON file and reports a damaged file instead of
ignoring it. The real engine can implement the same interface on darktable's `presets` table and
`plugins/darkroom/modulegroups_preset` without a change in the screens.

### 2.6 Tests whose expectations changed (the old invented quick panel)

- `SchemaTest`: checked the invented `module-groups.json`/`ModuleGroups` (removed); now checks darktable's
  default layout.
- `PhoneScreenshots.quickPanel` (P2a): showed the invented panel with white balance temperature; now
  asserts darktable's panel (sigmoid first, color calibration, no white balance).
- `DarkroomControllerTest` and `DarkroomScreenshotTest`: P2a assumed the darkroom opens on the active
  modules; darktable opens on its default group (the second group). P2a's screenshots still start on the
  active modules on purpose (explicit `selectGroup`), new ones show the opening group.
- In this session: `PhoneScreenshots.searchLine` (color calibration is below the fold of the lazy list for
  "col"; the test now checks the controller's list and scrolls to it before asserting) and
  `PhoneScreenshots.editorUserLayout` (the editor shows one "move group down" per group; the test now
  asserts 4 groups and 4 buttons instead of expecting one node). Both were wrong expectations, not app
  faults; neither was weakened.

## 3. How to reproduce, and results

```
git checkout p2d-module-layouts
python3 -I tools/layouts/extract-builtin-layouts.py <darktable source at the pin> --check   # needs cc and glib-2.0
tools/layouts/verify-with-darktable.sh        # optional: needs a Linux darktable build at the pin (see the script)
GRADLE=$(tools/app/setup-gradle.sh /tmp/gradle)
cd app
$GRADLE :checkScreenRulesSelfTest :checkScreenRules
$GRADLE :engine-api:test :schema:test :ui-theme:testDebugUnitTest :darkroom:testDebugUnitTest   # add -Pdta.screenshots=false where Maven's CDN is unreachable
$GRADLE :app:assembleDebug
```
Or run the workflow `p2a-app` (it runs on pushes to this branch).

CI (GitHub runner, 4 cores), run 44499763458 on 3c6190e (the last code commit):
- built-in layouts check: `schema/builtin-layouts.json` matches the pin 04477d32a00c;
- screen rule check: 0 violations (covers all screen code, the new editor included);
- tests: 107 of 107 passed (darkroom 46 incl. 32 screenshot tests, engine-api 31, schema 25, ui-theme 5);
- preview APK built and checked (no INTERNET permission, `builtin-layouts.json` packed), published with
  31 screenshots as release `app-preview-3c6190ecfc21`
  (https://github.com/nannolo45/darktable-android/releases/tag/app-preview-3c6190ecfc21); the comparison
  and mapping tables it wrote are identical to those in `P2d-results/`.
Earlier runs: 44494055618 on bf9f344 failed, 105 of 107 (the two screenshot tests in 2.6); 44496669366 on
5f141b0 green, 107 of 107; 44497441293 on 12da7ab hung for over 15 minutes in the built-in layouts step
(fetch from github.com, no output) and was cancelled by the next push, which bounds that fetch with
timeouts and three attempts (3c6190e; the signing step is main's, unchanged). Logs, test
results, the comparison and mapping tables and the screenshots of each run are on the `ci-logs` branch
under `runs/<run id>-a1/p2a-app/`.

On the build workspace (x86_64, 2 cores): the non-screenshot tests and the rule check pass; the tool's
`--check` passes against the read-only upstream tree.

## 4. Upstream surprises and follow-ups

- `agx/auto tune levels` in the built-in layouts (`modulegroups.c`, macro block near line 1657) does not
  match the action path AgX defines for that picker, `agx/exposure range/auto tune levels`
  (`src/iop/agx.c`, `dt_action_define_iop(real_self, N_("exposure range"), N_("auto tune levels"), …)`).
  It looks as if desktop's quick access panel cannot find this widget either. Not verified in a desktop
  GUI; the app keeps the entry and shows it disabled.
- `toneequal/graph` is an action on a drawing area (`src/iop/toneequal.c`), not a bauhaus widget, so the
  control schema has no entry for it; desktop does show the graph in its quick panel.
- "modules: deprecated" is disabled with `#if 0` at the pin, and no module is deprecated; the deprecated
  behaviour is implemented and unit-tested with a module marked deprecated by the test.
- **White balance and color calibration warnings (follow-up, needs the engine):** desktop sets "trouble
  messages" in `_set_trouble_messages` (`src/iop/channelmixerrgb.c`): "white balance applied twice",
  "double CAT applied", "white balance module error", "white balance missing", "not prepared for color
  calibration's illuminant", "white balance is not prepared for this illuminant". They depend on the
  pipe's state; the app shows none of them and does not imitate them. PLAN 3.9's "the white balance panel
  says so when color calibration is active" needs an engine call that reports a module's trouble message.

## 5. Differences from desktop, and why

- Group tabs show the icon and the group's name under it; desktop shows icons with the name as tooltip
  (no hover on a phone).
- The editor is one scrolling page with sheets instead of popups; groups move up and down with buttons
  instead of left and right arrows; modules and widgets are picked from searchable lists.
- Desktop's direct editing (right-click on a module header or a quick access widget to add it to a group
  or the panel, `_manage_direct_*`) is not built; everything is done in the editor.
- The editor lists modules this darktable version does not know (from an imported layout) with a note,
  and keeps them; desktop hides them in the editor and keeps them in the string.
- The pixel workflow choice (desktop: preferences → processing → "auto-apply pixel workflow defaults") is
  shown in the editor, because the app has no preferences screen yet; it only chooses the built-in
  layouts' variants.
- Import refuses a file whose layout has a built-in layout's name, so built-in layouts stay as upstream
  defines them (desktop's `INSERT OR REPLACE` would replace the row; what it does after that was not
  checked); export is one layout per file through the save picker, named as desktop names it.
- Quick access entries the app cannot use are shown disabled with their name and reason (desktop has no
  such case).

## 6. Deviations from the brief

- Commit trailers name the model the session's attribution names (Claude Opus 5.5), as the earlier
  commits on this branch do, not the one in the hand-over message.
- Screenshot tests use a no-op file picker; the real picker was not exercised (no device or emulator).

## 7. Open questions for the reviewer

1. The repository secret `SIGNING_KEYSTORE_B64` is not set (the APK's certificate differs from run to
   run), so each preview needs an uninstall first and loses the owner's layouts. Set it before asking the
   owner to import his layouts?
2. Should direct editing from module headers and the quick panel (desktop's right-click menus) be a
   follow-up brief, or is the editor enough on a phone?
3. Report the `agx/auto tune levels` path to upstream after checking it in a desktop GUI?
4. The app's workflow switch lives in the editor until a preferences screen exists. Acceptable?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

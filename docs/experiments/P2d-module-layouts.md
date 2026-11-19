# P2d: module layouts as in darktable

Brief: `docs/briefs/P2d-module-layouts.md`. Branch `p2d-module-layouts`. Pull request text:
`P2d-module-layouts-REPORT.md`.

## Running log (newest first)

- 2026-11-19: CI green on 3c6190e (run 44499763458, 107 of 107, release `app-preview-3c6190ecfc21`).
  Run 44497441293 hung in the layouts step (upstream fetch); the step now has timeouts and retries.
  Screenshots show the editor's import/export buttons. Owner instructions and report written.
- 2026-11-19 (resumed by a second agent after an unrelated interruption): CI run 44494055618 on bf9f344
  failed on two screenshot tests (`searchLine`: color calibration is below the fold of the lazy list for
  "col"; `editorUserLayout`: one "move group down" per group, 4 nodes, the test expected one). Both tests
  fixed without weakening them (ee99df7). `origin/main` merged (f24d9d1): the signing step of
  `p2a-app.yml` is main's, the branch's trigger and layouts check stay. Quick access panel now leaves
  deprecated modules out as `_basics_show` does (entry kept, disabled, with the reason).
- 2026-11-19: layout model and codec, built-in layouts, quick access mapping, `.dtpreset` read/write in
  `app/schema` (package `layout`), `LayoutStore` + `FileLayoutStore` in `engine-api`. A real darktable
  (Linux build at 04477d3, own config dirs) stores exactly the 30 built-in layouts of the data file, exported
  a layout with its own code (fixture `desktop-export.dtpreset`) and imported the app's two files
  (`docs/experiments/P2d-results/verify-with-darktable.txt`). Schema and engine-api tests pass here.
- 2026-11-19: `tools/layouts/extract-builtin-layouts.py` and `schema/builtin-layouts.json`.
- 2026-11-19: started. Read CLAUDE.md, the brief, PLAN 3.4 to 3.6 and 3.9, ui-spec, engine-api section 3,
  P2a write-up and report, S5 write-up, upstream `src/libs/modulegroups.c`, `src/common/presets.c`,
  `src/gui/preferences.c`, `src/gui/presets.c`, `src/libs/lib.c` at 04477d3. Branch created.

Next: none; the work package is finished and waits for review (`P2d-module-layouts-REPORT.md`).

---

## For the owner: module layouts in the interface preview

### What is new

The preview now arranges its modules the way darktable on your PC does, with darktable's own
**module layouts** (in darktable: the menu next to the module group tabs, and "manage module layouts").

- **The default is darktable's "workflow: scene-referred" layout**, taken from darktable's own source, not
  typed by hand: the tabs are *active* (power symbol), *quick* (the quick access panel), then the groups
  *base, color, correct, effect* with darktable's icons, and the search line under them. The preview
  opens on the *color* group, as darktable does at its first start.
- **The quick access panel is darktable's**: sigmoid's contrast and skew, color balance rgb, color
  equalizer, contrast & texture, **color calibration** (illuminant, temperature, ...), tone equalizer,
  exposure, rotation, lens correction, denoise. **There is no white balance slider any more**: the earlier
  preview invented one, and darktable's manual says to leave white balance alone in the scene-referred
  workflow and set the colour temperature in color calibration. A few entries darktable lists cannot be
  used in the app yet (for example color calibration's hue and chroma, the tone equalizer graph): they are
  shown greyed with their name and the reason, never left out.
- **Search**: type in the line under the tabs ("search modules by name or tag") to list every module whose
  name contains the text, as on the PC. Tapping a tab clears it.
- **Tap the selected tab again** to see all modules of all groups, as on the PC.
- **The menu at the end of the tabs** (the three lines) lists the layouts. Pick one to use it:
  "modules: all", "search only", "workflow: beginner", "workflow: display-referred",
  "workflow: scene-referred", and your own.
- **"manage module layouts…"** at the bottom of that menu: duplicate, rename, remove, new; for your own
  layouts: switch the search line and the quick access panel on or off, "show all history modules in
  active group", add and remove quick access widgets, add, rename, give an icon to, move up and down and
  remove groups, add and remove modules in a group, "reset" (back to how the layout was when you opened
  it). The built-in layouts cannot be changed: duplicate one first, as on the PC. The back button closes
  the editor and keeps your changes.
- **import…** and **export…** in "manage module layouts" bring layouts in from darktable on the PC and
  take them out, as `.dtpreset` files, darktable's own preset files.

### Install

1. On the phone, open https://github.com/nannolo45/darktable-android/releases/tag/app-preview-3c6190ecfc21
   (or the newest entry titled "Interface preview" on the releases page).
2. Under "Assets", tap **dt-android-preview-arm64-v8a.apk** (about 8.7 MB) and install it.
3. If Android says "App not installed", uninstall "dt-android preview" first and try again: this build
   was signed with a temporary key (the signing key secret is not set in the repository yet), so it
   cannot replace an earlier preview. Uninstalling removes layouts you made in the earlier preview.

### Bring a layout from darktable on the PC to the phone, step by step

On the PC (darktable 5.x):
1. Make or edit the layout in darktable as usual (darkroom → the menu next to the module group tabs →
   "manage module layouts"). Give it its own name; a layout named like a built-in one cannot be imported.
2. Open **preferences** (the gear at the top right) → tab **presets**.
3. Click **export...** at the bottom and choose a folder. darktable writes **one file per preset you made
   yourself**, for every module. Your layouts are the files whose names start with **`modulegroups_`**,
   for example `modulegroups_my layout.dtpreset`. The other files are presets of other modules; the
   preview does not use them yet.
4. Copy the `modulegroups_...` file(s) to the phone: by USB cable into the phone's `Download` folder, or
   through any cloud folder or e-mail to yourself that the phone's file picker can open.

On the phone:
5. Open the preview, tap the menu at the end of the module group tabs (three lines), then
   **manage module layouts…**.
6. Tap **import…**. Android's file picker opens; pick one or more `modulegroups_...dtpreset` files.
   A line under the buttons says "imported \"<name>\"" per file, or why a file was not taken.
7. Tap the back arrow (or the phone's back button) to close the editor, open the menu at the end of the
   tabs again and tap your layout's name. Its groups, quick access panel and search line are now used.

The other way: in "manage module layouts…" choose your layout, tap **export…**, save the file (Android
suggests `modulegroups_<name>.dtpreset`), copy it to the PC and import it in darktable under preferences →
presets → **import...**. darktable replaces its layout of the same name.

### What to look at and send back

- Does the default layout look and behave like darktable on your PC (tabs, groups, quick access panel)?
- Import one of your own layouts from the PC. Is anything missing or different? Which quick access entries
  are greyed out for you?
- Is the editor usable with a finger? Moving groups uses up and down buttons instead of dragging.
- Nothing of this touches photos; the picture is still the generated sample, and speed means nothing yet.

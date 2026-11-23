# Brief P3j: copy and (selective) paste of history between photos

Branch `p3j-copy-paste` from `main` (`1c39df7`). Owner's request (doc 12 additions, 23 Nov): copy and selective paste
of the edit history between photos as on desktop: choose modules, append or overwrite; the same XMP result as
desktop. When built: `copy`, `paste…` in l1's `more` tool and in the other skins' menus. The bridge has no such call
today (`engine/include/dtbridge.h`: only `dtb_instance_duplicate`, history goto/compress/seal/groups, styles).

Budget rule set by the owner: token use is a priority. Read with grep and line ranges, no exploratory tours.

## Rules
`CLAUDE.md` binding (public repo, nothing personal; the darktable manual's history stack page (lighttable →
"selected images" → copy, copy parts, paste, paste parts, mode append / overwrite) is the reference). Never push
to `main`, no PRs; push after every deliverable. Bridge changes expected (never `upstream/`): new calls documented in
`engine-api.md` (new section), tested in the bridge tests, exposed in Kotlin. Find how upstream does it
(`src/common/history.c` `dt_history_copy_and_paste_on_image`, `dt_history_merge_module_into_history`, the copy
parts dialog) and reuse that path rather than re-implementing; masks used by pasted modules travel with them as on
desktop. Rule 4 of CLAUDE.md for the target photo. The engine holds one photo at a time (engine-api.md 1): pasting
onto other photos than the open one works on their XMP files (as the series export of P3i opens photos on its own
session; reuse that pattern), and the darkroom's photo is saved first.

## Deliverables
1. Engine: copy the open photo's history (all, or a list of module instances) to a clipboard object; paste onto the
   open photo or onto a list of XMP/raw pairs, mode append or overwrite; one history item per paste on the open photo.
2. App: `copy`, `copy parts…` (sheet listing the history's modules with checkboxes, as desktop's dialog: safe modules
   pre-selected as desktop does, e.g. white balance, raw-specific ones unchecked by default), `paste`, `paste parts…`,
   mode append / overwrite (remembered setting); in darktable and modern skins in the darkroom's menu and on the photo
   list's selection (paste onto several photos, with LPL photos fetched first as P3i does, progress with
   BatchProgress); in l1 in `more`.
3. Pasted photos from LightPhotoLibrary are marked edited and synced like any edit.

## Tests
Bridge: copy all + paste overwrite equals desktop's result (compare with darktable-cli's `--style` or a reference XMP
made by darktable's own copy/paste path in the Linux build: say which); copy parts; append keeps the target's own
modules; masks travel; read-only target refused. JVM: sheet defaults, mode setting, paste onto several photos with
the stand-in. Screenshots: copy parts sheet, menus in darktable/modern, l1 more. Emulator: copy from photo A, paste
parts onto photo B, B renders as expected and its XMP reopens identical; paste onto two photos from the list.

## Docs and CI
Report `docs/experiments/P3j-copy-paste-REPORT.md`; `ui-spec.md` new section; brief into `docs/briefs/`. Branch in
the triggers of `g0-app.yml`, `p2a-app.yml`, `p2b-real-engine.yml`. Known emulator flakes on main:
P3cScenarioTest.p2_exposureSpotByTouch, P3eScenarioTest.p4_toneEqualizerDragOnThePhoto — if only those fail, rerun the
whole workflow. Stop when green, or after three failed fix attempts on one problem. Maven 429: rerun.

# Brief P6d: lens correction — camera and lens by hand; focal length, aperture, distance

Branch `p6d-lens-correction` from `main` (this brief is its first commit). Read CLAUDE.md, PLAN.md 6.1 item 1, the audit row "lens correction" in `docs/experiments/P5a-module-audit.md`, `schema/overrides/lens.json`, and the darktable manual page for lens correction (https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/lens-correction/). The desktop reference is `upstream/darktable/src/iop/lens.cc` (pinned commit), mainly `camera_menu_fill`, `_camera_menusearch_clicked`, `_camera_autosearch_clicked`, `_lens_set`, `_lens_menu_fill`, `_lens_menusearch_clicked`, `_lens_autosearch_clicked`, the three `_lens_comboentry_*_update` callbacks and `gui_init`. Medium package.

## 1. What to build (as desktop does, laid out for a phone)
Only for the Lensfun correction method; with the embedded-metadata method these controls are hidden, as on desktop.
1. **Camera model** row showing the current camera (or Exif's, with desktop's "no matching camera" state). Tapping it opens a full-screen picker: all Lensfun cameras **grouped by maker** (desktop's submenus), a search field filtering maker + model + variant as you type, variant shown as `model (variant)`. A **"find camera"** button next to the row opens the same picker pre-filtered to desktop's auto-search result (`FindCamerasExt` on the Exif model).
2. **Lens model** row, the same way: tapping opens all lenses that fit the chosen camera (desktop: `FindLenses(camera, NULL, NULL, …)`), grouped by maker, searchable; **"find lens"** pre-filters with desktop's auto-search. When Exif names a lens Lensfun does not know, show the Exif name and desktop's hint (no matching lens; tap to pick from the full list). After a lens is chosen, show its focal range, aperture and mount as desktop's lens label/tooltip does.
3. **Focal length, aperture, distance**: three editable lists as desktop's: focal values and f-numbers from desktop's tables **clipped to the chosen lens's range** (with the lens's own min/max inserted, as `_lens_set` does), distance 0.25 m doubling by √2 up to 1000; the current value first; and the option to type a value (number keyboard). Writing sets the parameter and `has_been_set` as desktop's callbacks do. Remove the three "not available yet" notes from `schema/overrides/lens.json`.
4. Choosing a camera clears/refreshes the lens choice the way desktop does; everything is one history item per choice; undo works.
5. Bridge: extend the `lens` actions only where needed (for example `find_cameras` / `find_lenses` giving desktop's auto-search results, lens `mount`, the value lists), in `dtbridge.h` and `docs/design/engine-api.md` 5.4, with new bridge tests (next free C ids, registered in `engine/tests/TEST-IDS.md`). Keep using the lensfun database already in the APK. Do not touch `upstream/`.
6. All three skins (darktable, modern, lightcraft); tokens and icons only (CLAUDE.md rule 5); the picker must be usable one-handed and with the keyboard up (the P5c/P6c keyboard lessons apply).

## 2. Tests
- Bridge (g0 with `tests=` limited to the lens ids plus C14): camera and lens lists, auto-search for the test RAWs (the CR2 and the RAF), set_lens then the three values, XMP round trip; render differs from no-correction when a lens is set (simple statistic, not a golden image).
- JVM: list grouping/search filtering, value-list clipping against `_lens_set`'s rules (a zoom and a prime), typed value parsing.
- Screenshots (p2a): module with a lens chosen, both pickers (with and without a search), the unknown-lens state, in the three skins.
- Emulator (p2b): one tap test — open lens correction, pick a camera via search, pick a lens, choose a focal length from the list; the history gains the items. Waits on real conditions, not sleeps.
- Run p2b at least twice on the final commit; both green.

## 3. Out of scope
Changing correction algorithms, vignetting/TCA tuning sliders (already generated), the embedded-metadata method, updating the lensfun database.

## Economy and report
One agent, no sub-agents; save tokens (no long log dumps into context, grep the artefacts). Report in `docs/experiments/P6d-lens-correction-REPORT.md` as CLAUDE.md says, with CI run ids, and open the pull request. Do not merge it.

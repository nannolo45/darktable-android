# P3b: styles and presets in the app

The interface for what P1b part 1 put into the engine (`docs/design/engine-api.md` section 5; PLAN.md 3.9). The owner
edits with film-look style packs and module presets made on the desktop and wants them on the phone. App side only:
**no change under `engine/` or `upstream/`** (another agent changes `engine/` at the same time; if a call is missing or
wrong, describe it in the report and work around it in the fake engine). Branch `p3b-styles-presets` from `main`.
Report `docs/experiments/P3b-styles-presets-REPORT.md`, running log `docs/experiments/P3b-styles-presets.md` (pushed
at least every 30 minutes).

## Reference
The darktable user manual: "presets" (darkroom, processing modules), "styles" (lighttable's styles module and the
darkroom's quick styles button), and the preference that says whether applying a style appends or overwrites.

## Deliverables
1. **Presets on a module.** The presets icon on an open module (the icon exists in the registry) opens the module's
   preset list as desktop's menu shows it: built-in and the user's, the ones that do not fit the open image marked as
   desktop marks them, the one that equals the current settings marked. Tap applies (one history item). "store new
   preset…" (name, description), "update", "rename…", "delete" on the user's own, with desktop's confirmations.
   Read-only photos: the list is shown, nothing can be applied.
2. **Styles.** A "styles…" entry in the darkroom's menu opens the list: darktable's names use `|` for categories, so
   the list is a tree that folds, with a search line over names and descriptions (the engine ships several hundred
   styles; the list must open and scroll without a wait: say how long `styles_list` takes on the emulator and what
   you cache). Tapping a style shows what it holds (its modules, by display name) with "apply" in desktop's two modes
   (append, overwrite; the default as desktop's preference, remembered), one undo step back. "create style from this
   edit…" (name, description, which history items), "delete" for the user's own.
3. **Import from the phone's files**: `.dtstyle` (one or many at once, a whole look pack) and `.dtpreset`, through
   Android's file picker; a result line per file (imported, replaced, refused and why: unknown module, newer module
   version). Asks before replacing a style or preset of the same name, with "for all" when many.
4. **Recently used and favourites** for styles (the owner applies the same few looks often): the last few applied
   on top of the list, and a star to keep some there. Stored in the app's settings, not in darktable's database.
5. **Tablet layout** follows the phone's with the list beside the photo where the specification gives room.
6. **The four controls of P2j that waited for the engine** (its report section 6; the calls exist now): input colour
   profile's list, denoise (profiled)'s current profile shown, white balance's fine tuning, lens correction's "use
   latest algorithm", and framing's "image" and "constant border" entries. Second priority: only when 1 to 4 are
   done and green; then update `tools/schema-unbound-controls.py`'s kept list.
7. **Tests.** JVM with the fake engine for every action above, including refusals and the read-only case;
   screenshot tests of the preset list, the style tree, a style's detail, the import result (phone and tablet).
   Emulator: import a public `.dtstyle`, apply it in both modes, write the XMP; darktable-cli's render of that XMP
   equals the app's export within the usual bound; store a preset and apply it on a second instance. Both workflows
   green on the last code commit; APK from the usual release step; owner instructions in the report (how to export
   styles and presets on the desktop and bring them in).

## Not in this package
Styles, presets and layouts travelling through LightPhotoLibrary (R11); a picture preview per style; editing a
preset's automatic-application rules; exporting a style to a file (the engine has no call yet).

## Rules
`CLAUDE.md`. Colours, sizes and icons through tokens and the icon registry. Nothing personal in the repository (no
names of people, phone or camera models, names of the owner's layouts, presets or styles; test styles come from the
shipped ones or are made by the tests). SPDX line on new files. Do not weaken a test. Never push to `main`, no pull
requests. Report shortfalls first, plainly. The build workspace has no Android SDK: darkroom and app tests run only
in CI. Maven Central is flaky in CI: rerun before suspecting the change.

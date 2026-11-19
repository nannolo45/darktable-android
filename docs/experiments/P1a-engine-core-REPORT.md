# P1a: engine core (Engine API v1, sections 1 and 2) — pull request description

Branch `p1a-engine-core` (merged with main at cedca8b). Write-up: `docs/experiments/P1a-engine-core.md`.

**Failures first.** The region test (T5) still fails in two cases, both reported per module below and kept
failing in CI: the X-Trans image's top-left corner region (max 11, all differences within 10 px of the
image's own top and left edge, where no margin is possible), and the spektrafilm image (centre region
max 21 with differences spread over the region, as in S3; one more region max 1). Every other test passes
on all three images in CI (RUN_RESULTS). Locally only the 10 MP image could be run: the workspace's shell
cgroup (6 GB, shared with another agent's Gradle daemons) kills 20 and 26 MP renders.

## 1. Brief item by item

| # | Item | State |
|---|---|---|
| 1 | `dtbridge.h` function for function as the specification, documented | **done**; renames in the write-up, section 1 |
| 2 | Split sources, no darktable types in the header | **done**: core, guard, session, params, actions, instances, history, render, output |
| 3a | `modules()` with the full tree, flags | **done** (C1) |
| 3b | `param_set` paths, type and range checks; `params_set` one item | **done** (C2) |
| 3c | `module_action` and the listed actions | **done**, with two data points the desktop GUI holds (open question 3) |
| 3d | Undo/redo over whole-history states | **done** (C3), via darktable's history snapshot tables |
| 3e | `render_region` with internal margin | **partly**: fixed 32 px margin; region = crop on cr2 and in 4 of the 6 raf/arw regions; 2 failures above |
| 3f | `set_threads`, `set_thread_cpus` (per-thread `sched_setaffinity`) | **done** (C5) |
| 3g | `export` options, atomic `xmp_write`, `metadata_reload` | **done** (C7) |
| 3h | Read-only refuses every mutating call | **done** (C6: 20 of 20; `history_goto` allowed, per cedca8b) |
| 4 | Tests covering section 4 on three images, in the Linux CI job, failing it on any failure | **done**: C1 to C7 added, T5 strict, the job's T5 exception removed |
| 5 | Android engine builds `libdtbridge.so` for both ABIs; G0 app builds against the header | **done**: run 44478936128 built both (about 100 KB each), the APKs, and the emulator test passed |
| 6 | Write-up and report | **done** |

Function groups of the specification: 2.1 lifecycle done; 2.2 guard done; 2.3 session done; 2.4 modules
and parameters done (actions: see open question 3); 2.5 instances done; 2.6 history done; 2.7 rendering
partly (region margin, see above); 2.8 output done.

Clarifications of cedca8b: (1) `history_goto` is allowed read-only and is not an undo step — aligned in
9c4ec6c, tested in C6 and C3; (2) `instance_move(+1)` is later in the pipeline — already so, C2 now checks
`iop_order` 1400 → 1500 → 1400 on cr2; (3) `iop_order` is an integer — already so, C1 checks the JSON
type; (4) `color_labels` are numbers — changed, C7 reads back `[0, 3]`.

## 2. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p1a-engine-core
git submodule update --init --depth 1 upstream/darktable
tools/build-engine-linux.sh <darktable source> /tmp/dt-linux     # darktable + bridge + dtb_test (as CI)
tools/engine-test/fetch-paths.sh /tmp/dtt images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw \
  0001-exposure/exposure.xmp 0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp \
  0179-spektrafilm/spektrafilm.xmp
engine/tests/run_tests.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/results          # all; or name tests: T5 C3 ...
engine/tests/t5_bisect.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt/images/hlrecovery.arw \
  /tmp/dtt/0179-spektrafilm/spektrafilm.xmp /tmp/t5-arw                                            # region diagnosis
```
Against an existing darktable build: `cmake -S engine -B <build> -G Ninja -DCMAKE_BUILD_TYPE=Release
-DDT_SOURCE_DIR=<src> -DDT_BUILD_DIR=<build tree> -DDT_INSTALL_PREFIX=<prefix> && ninja -C <build>`.
On a machine with little memory, `DTB_TEST_RESOURCELEVEL=small` gives darktable (bridge and darktable-cli
alike) a smaller budget. CI: workflow `g0-app` on this branch (trigger added in a separate commit, 3cc3dcf).

## 3. Results

RESULTS_SECTION

## 4. Things in upstream that surprised me

- `dt_iop_is_first_instance()` decides some defaults (exposure `compensate_hilite_pres`, the first
  instance's +0.7 EV in `reload_defaults` of `iop/exposure.c`) by position in the pipe, and nothing
  recomputes them when instances are deleted or moved; a reloaded session computes different defaults
  than the live one. The bridge's `history_goto` reloads for that reason (write-up 3.3).
- `dt_history_snapshot_restore()` empties the history through `dt_history_delete_on_image_ext()`, which
  also detaches the "darktable|style|…" and "darktable|changed" tags and clears the change timestamp.
- `dt_dev_add_history_item_ext()` (the no-GUI path) does not attach the "darktable|changed" tag or set
  the change timestamp; `_dev_add_history_item()` does. The bridge now does both.
- `dt_dev_cat_is_active()` is FALSE whenever no color calibration panel exists (darktable-cli, the
  bridge): it asks `dev->proxy.cat_source`, which only that panel's `gui_init` sets.
- `dt_iop_reset_params()` removes a drawn mask through `darktable.develop`, not the module's own dev.
- Generic parameter writes skip the modules' `gui_changed()` logic: for example writing temperature's
  `red` leaves `preset` at "as shot", so the pipe ignores the new coefficient. Actions cover the modules
  in the brief; the schema overrides should mark such fields (open question 4).
- Demosaic keeps its tile border private (`_tiling_requirements()`), and its `tiling_callback` reports no
  overlap, so the region margin cannot be derived from upstream.

## 5. Open questions for the reviewer

1. **Region test, remaining failures** (numbers above). Proposal: accept them as known per-module
   differences (X-Trans corner at the image edge; spektrafilm's window), keep T5 failing visibly for those
   two cases, and keep the rule that the app never stitches regions. Alternatively an upstream issue for
   spektrafilm (S3 already suggested one).
2. **History snapshot side effects**: undo re-attaches style tags and the "changed" tag itself.
3. **Actions that need desktop-only data.** (a) White balance: leaving "camera reference" for "as shot" or
   a camera preset sets `late_correction` on desktop from `dt_dev_cat_is_active()` (color calibration's
   panel); the bridge cannot know it and leaves the flag unchanged. (b) "from image area" needs the colour
   picker (P1b) and "user modified" the panel's memory of the last slider values: neither is listed by
   `list_presets`. (c) The bridge's names are untranslated (`wb_presets.json`, `noiseprofiles.json`,
   "found ISO 1600"), while desktop shows translated labels; the app should translate for display.
4. **Generic `param_set` bypasses `gui_changed()`**; see section 4. Should schema overrides flag such
   fields as "use the action"?
5. **lut3d root folder**: as on desktop, `set_file` needs `plugins/darkroom/lut3d/def_path`; the API has no
   setter, so today the app must write it into `darktablerc` before `init` (as the test does). Add
   `paths.lutdir` to `init`, or a conf setter?
6. **Lensfun**: the bridge loads its own copy of the database (the module's is private), a few MB more;
   the lens actions are written for Lensfun < 0.3.95 (what `deps/` and Ubuntu ship) and refuse to compile
   otherwise.
7. **`modules()` size**: MODULES_SIZE
8. Export of a read-only session is allowed (it writes the private library, not the XMP).

## 6. Zero changes to darktable

Held: nothing under `upstream/` changed, no `patches/`. The bridge includes upstream's
`external/cie_colorimetric_tables.c` (read only) for the white balance conversion.

## 7. Notes

- The first commit (8e87811) carries the author name "dt-android agent" instead of the repository's usual
  one; rewriting it would need a force push, which I did not do.
- Measurements are x86_64 (2 cores locally, 4 on CI runners), not phone numbers.
- During the local runs I stopped only my own test processes after the out-of-memory kills.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

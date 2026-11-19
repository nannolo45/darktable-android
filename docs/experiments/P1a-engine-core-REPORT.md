# P1a: engine core (Engine API v1, sections 1 and 2) — pull request description

Branch `p1a-engine-core` (main merged twice: at cedca8b in 4bdc99b, and at 65f1c90 in a2e5cbb). Write-up:
`docs/experiments/P1a-engine-core.md`.

**Failures first.** The brief's acceptance point "the region test must now pass" is **not met** for two of
the three images. T5 fails, visibly and as the brief asks for that case, in three of nine regions:
the X-Trans image's top-left corner region (max 11, mean 0.0076, all differences within 10 px of the
image's own top and left edge, where no margin is possible; lens + demosaic), and the spektrafilm image
(centre region max 21, mean 0.30, differences spread over the region, as in S3; one more region max 1;
spektrafilm + demosaic). Each failing region is compared twice (against the crop of the bridge's full
render and against the crop of darktable-cli's), so the Linux job reports **115 PASS, 6 FAIL**, all six
T5, and the `g0-app` workflow ends "failure" because of them. Every other test passes on all three images
in CI. Locally only the 10 MP image could be run: the workspace's shell cgroup (6 GB, shared with another
agent's Gradle daemons) kills 20 and 26 MP renders.

## 1. Brief item by item

| # | Item | State |
|---|---|---|
| 1 | `dtbridge.h` function for function as the specification, documented | **done**; renames in the write-up, section 1 |
| 2 | Split sources, no darktable types in the header | **done**: core, guard, session, params, actions, instances, history, render, output |
| 3a | `modules()` with the full tree, flags | **done** (C1) |
| 3b | `param_set` paths, type and range checks; `params_set` one item | **done** (C2) |
| 3c | `module_action` and the listed actions | **done**, with two data points the desktop GUI holds (open question 3) |
| 3d | Undo/redo over whole-history states | **done** (C3), via darktable's history snapshot tables |
| 3e | `render_region` with internal margin | **partly**: fixed 32 px margin; region = crop on cr2 and in 3 of the 6 raf/arw regions; 3 regions fail (above) |
| 3f | `set_threads`, `set_thread_cpus` (per-thread `sched_setaffinity`) | **done** (C5) |
| 3g | `export` options, atomic `xmp_write`, `metadata_reload` | **done** (C7) |
| 3h | Read-only refuses every mutating call | **done** (C6: 20 of 20 on each image; `history_goto` allowed, per the contract) |
| 4 | Tests covering section 4 on three images, in the Linux CI job, failing it on any failure | **partly**: tests done (C1 to C7 added, T5 strict, the job's T5 exception removed, the job fails on any failure); the acceptance "region test must now pass" is **not met** for raf and arw (above) |
| 5 | Android engine builds `libdtbridge.so` for both ABIs; G0 app builds against the header | **done**: runs 44478936128, 44489957409 and 44496589328 (after the last merge of main) built both ABIs (about 100 KB each) and the G0 APKs; the emulator test (API 33, x86_64) passed in all three |
| 6 | Write-up and report | **done** |

Function groups of the specification: 2.1 lifecycle done; 2.2 guard done; 2.3 session done; 2.4 modules
and parameters done (actions: see open question 3); 2.5 instances done; 2.6 history done; 2.7 rendering
partly (region margin, see above); 2.8 output done.

**Contract clarifications** (engine-api.md 2.5, 2.6 and "Clarifications after P2a"). The bridge behaves as
written in all four; nothing differs.

| Point | Bridge | Test that shows it (run 44489957409, all three images) |
|---|---|---|
| `history_goto(end)` is navigation: allowed read-only, not an undo step | aligned in 9c4ec6c (before, goto recorded an undo state and was refused read-only) | C6: "read-only session: history_goto 0 and back to 9/12/14 allowed", XMP byte-identical afterwards; C3: goto leaves `undo_state` unchanged (checked), undo of the next change returns to the goto position and redo returns there ("undo of 'history_compress' (state 10, at the goto) modules equal history equal pixels max 0"); undo depth after 60 changes is now 70 steps (71 while goto was a step) |
| `instance_move(+1)` = one step later in the pipeline | already so | C2: "exposure iop_order 1400, after move +1 1500, after -1 1400" (cr2), plus darktable-cli parity of the moved result (X1) |
| `iop_order` in `modules()` is a JSON integer | already so | C1 checks the JSON node type is an integer (`G_TYPE_INT64`) for every instance and that the list is in pipeline order |
| `color_labels` in `image_info()` is a list of label numbers | changed in 9c4ec6c (was names) | C7: metadata_reload of an XMP with red and blue labels reads back `[0, 3]` |

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

Reference run **44489957409** (commit 024151b, GitHub runners, x86_64, 4 cores; logs on the `ci-logs`
branch under `runs/44489957409-a1/`). Linux job: **115 PASS, 6 FAIL** (all six are T5, three regions × two
comparisons, listed below); the job fails because of them, as the brief asks. 66 pixel comparisons in
`bridge-tests-comparisons.txt`: 60 with max 0, the 6 T5 ones as below. Run 44483237019 (4bdc99b) gave the
same 115/6 and the same T5 numbers. Emulator job (API 33, x86_64, G0 app on the new bridge): test exit 0;
open 196 ms, preview 191 ms, export 1893 ms, region after a change 349 ms, cancel returns
`DTB_E_CANCELLED`; export vs darktable-cli on Linux max 26, mean 0.00092, PSNR 75.7 (within G0's
tolerance, comparison ok); XMP guard ok, RAW unchanged. **Final run 44496589328** (commit 6c84736, after the last merge of main; logs under
`runs/44496589328-a1/`): the same result: Linux 115 PASS, 6 FAIL (the same six T5 comparisons with the same
numbers, the other 60 pixel comparisons max 0); engine for both ABIs and the G0 APKs built; emulator test
exit 0, export vs darktable-cli max 26, mean 0.00092 (comparison ok), XMP guard ok, RAW unchanged. The
workflow's overall conclusion is "failure", only because of T5.

**Pixel parity with darktable-cli** (8-bit, max difference, every case max 0, mean 0): T1 render, T2
parameter, T3 instances (session and reopened), T4 goto and compress, T9 drawn mask, X1 move and delete,
C2 parameter paths and reset, C3 result after undo/redo, C4 after every action (lut3d with the LUT root
given to darktable-cli; on raf the lens is a fisheye picked by the test, because the XMP's own lens is not
in Lensfun), C7 default JPEG export and 8-bit PNG export — on all three images. RAW checksums unchanged.

| Test | cr2 (Canon, Bayer) | raf (Fujifilm X-Trans) | arw (Sony, spektrafilm) |
|---|---|---|---|
| C1 | class bayer, 97 instances, 9 without switch | class xtrans, 97, 10 | class bayer, 98, 9 |
| C2 grouping | history 9→10→11→12→14→15 | 12→13→…→18 | 14→15→…→20 |
| C3 undo | 12 states, each undo/redo byte-identical JSON, pixels max 0; 70 steps deep after 60 changes | same | same |
| C4 get_wb / set_wb 4500 K, 1.05 → get_wb | 5105 K / 4499.7 K, 1.0500 | 6502 K (camera reference) / 4499.7, 1.0500 | 6010 K / 4499.7, 1.0500 |
| C4 lists | 8 WB presets, 948 Lensfun cameras, 15 noise profiles | 9, 948, 23 | 17, 948, 25 |
| C5 pinning | engine thread + 3 workers on CPU 0, all released | same | same |
| C6 read-only | 20 of 20 refused; goto allowed | same | same |
| C7 exports | 11 accepted, 6 refused as specified; webp: plugin not built on the runner → ARG (built locally: written) | same | same |
| T8 cancel, worst latency | 0.12 s | 1.04 s | 0.34 s |

**T5, region = crop of the full render** (512 × 512 regions, internal margin 32 px):

| Region | cr2 | raf | arw |
|---|---|---|---|
| centre | max 0 | max 0 | **max 21**, mean 0.30, differences across the whole region |
| top-left corner (0,0) | max 0 | **max 11**, mean 0.0076, all within 10 px of the image's own top/left edge | max 0 |
| near bottom right | max 0 | max 0 | **max 1**, mean 0.00000 (0.0003 % of values) |

Diagnosis (`t5_bisect.sh`, CI step "Region test diagnosis", `t5-raf.txt`, `t5-arw.txt`):
- **raf corner: lens + demosaic.** Switching lens off → max 0; demosaic passthrough → max 0; every other
  module off → still 9 to 13. Margin 0/16/32/64/256 px → 12/10/11/8/4: a margin on the right and bottom
  shrinks it slowly, though the differences sit at the top-left image edge, where no margin is possible.
  Plausible but **not verified**: lens' `modify_roi_in()` maps a corner region to an input window whose
  origin and size differ from the full render's, and demosaic (Markesteijn) treats the window's border
  differently. Without the margin the same image had max 40/12/17 in S3; with it the centre and
  bottom-right regions are exact.
- **arw: spektrafilm (+ demosaic).** Margin has no effect (0 to 256 px give the same 21 and 1). Spektrafilm
  off → centre max 0, but the bottom-right region then differs (max 68, everywhere); demosaic
  passthrough → all regions max 0. As in S3, spektrafilm's own window (`_sf_work_window()`, iop/spektrafilm.c)
  makes the output depend on the requested region; why demosaic passthrough also removes it is not
  understood.
- **cr2: exact** with the margin (S3: max 182/75/64 without it).

Margin choice: fixed 32 px (upstream exposes no reach for demosaic; it covers Markesteijn 3-pass's
18 + 6 px border). The diagnosis shows 16 px already suffices where a margin can help; 32 is kept as the
safe value.

**Speed (CI runner, x86_64, 4 cores; not phone numbers)**, T7: open 0.12/0.50/0.08 s; preview after a
late change 0.04/0.05/0.07 s; 1920 × 1080 region after a late change 0.20/0.81/1.08 s (cr2/raf/arw). The
margin adds at most 64 px to each side of a region.

**`modules()` size and time** (C1, CI runner x86_64 4 cores): 185 204 / 185 300 / 185 982 bytes of JSON
for 97 / 97 / 98 instances (cr2/raf/arw); 5.3 ms per call on all three (7.3 ms on the 2-core x86_64
workspace, cr2, 185 203 bytes, shared with another agent's builds; `dtb_test <work> api` run directly). Most of the bytes are array parameters: on cr2 retouch's
parameters are 73.5 KB (`rt_forms`, 300 structs), liquify's 45 KB, colormapping's 16.8 KB, colortransfer's
10.4 KB; those four are 79 % of the total. This is the C call only: no JNI copy and no parsing on the Kotlin side. Not measured on a
phone or in the emulator (G0 does not call `modules()` with the new tree).

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
7. **`modules()` size**: about 185 KB of JSON (numbers in section 3). A per-instance call would cut the
   bytes about a hundredfold; worth it only if the phone shows the re-read in the interaction loop. Proposal:
   measure `modules()` plus Kotlin parsing on a phone in P2b before adding it.
8. Export of a read-only session is allowed (it writes the private library, not the XMP).
9. **Noise profiles with the same name.** `set_profile {name}` takes the first profile of that name. Upstream's
   `data/noiseprofiles.json` has two different "DSC-RX100M3 iso 1600" profiles (the arw test image's
   camera; C4 lists 25 entries with that name twice), so the second cannot be chosen; desktop picks by
   position in the combobox. Proposal: let `list_profiles` return an index and `set_profile` accept it
   (a contract change, so not done here).
10. **Not exercised anywhere**: the arm64-v8a library only builds (no arm64 device or emulator runs it);
   the emulator runs the G0 app, which uses only the old subset of calls through JNI, so the new API
   (`modules()` tree, `param_set` paths, actions, undo, read-only, export options, thread pinning) has
   run on Linux x86_64 only; webp export on CI (plugin not built on the runner; built and written locally);
   raf and arw on the 2-core workspace (memory, see above); any phone.

## 6. Zero changes to darktable

Held: nothing under `upstream/` changed, no `patches/`. The bridge includes upstream's
`external/cie_colorimetric_tables.c` (read only) for the white balance conversion.

## 7. Notes

- The first commit (8e87811) carries the author name "dt-android agent" instead of the repository's usual
  one; rewriting it would need a force push, which I did not do.
- Measurements are x86_64 (2 cores locally, 4 on CI runners), not phone numbers.
- Commit 317f449 has its attribution lines in the subject paragraph (a formatting slip); not rewritten.
- X1's "preview vs darktable-cli 1024 px export" line (max 255) is information only, as in S3: the preview
  and a downscaled export take different scaling paths; it is not a test.
- During the local runs I stopped only my own test processes after the out-of-memory kills.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

# P1b part 1: presets and styles in the engine, and the open engine points (pull request description)

Branch `p1b-engine-presets-styles` from main 5b43bc8. Brief: `docs/briefs/P1b-engine-presets-styles.md`. Running log:
`docs/experiments/P1b-engine-presets-styles.md`. Evidence: `docs/experiments/P1b-results/`. The calls are specified in
`docs/design/engine-api.md` section 5 (complete for everything built). Last code commit 0c1fde1d; run ids in section 6.

Measurements: **workspace** = build workspace, x86_64, 2 cores; **CI** = GitHub runners, x86_64, 4 cores; **emulator** =
CI's x86_64 Android emulator. None of them is a phone; nothing here says anything about arm64 yet.

## 1. Failures and limits (read first)

1. **The default-edit difference (deliverable 4) is not closed, and cannot be closed in the bridge.** Its cause is
   found (section 2): how darktable's `src/iop/sigmoid.c` is compiled. The phone's engine (clang, NDK) and the Linux
   reference (gcc with `-ffast-math`) compute sigmoid's curve fit differently. The edit (XMP) is identical on both;
   only the pixels of sigmoid differ, by up to 0.039 (45 dB) on darktable-tests' sigmoid case and 0.0074 (64.6 dB) on
   the default edit of the test RAW, float output. Whether a PC shows the phone's picture depends on how that PC's
   darktable was compiled (section 2.4). A fix needs either an upstream change or a change of the engine's build
   flags: proposals only, nothing changed (open questions 1, 2).
2. **Style append vs darktable-cli `--style` is not exact: max 2 levels** (cr2; raf and arw 1), mean 0.17-0.19, 55 dB.
   Two upstream paths build the history differently (section 3, item 3). The bridge follows desktop's path; its
   render equals darktable-cli of the bridge's XMP exactly (max 0). The test for `--style` uses tolerance 2 and mean
   0.5, chosen from these measurements and said so in `run_tests.sh`. darktable-cli's `--style-overwrite` is not
   desktop's overwrite (it drops the default edit; 19-23 dB) and is reported, not compared.
3. **Thread count when the process starts with few CPUs (deliverable 6, point 3) is not verified where it matters.**
   The bridge sets `KMP_AFFINITY=disabled` before darktable starts OpenMP, so LLVM's libomp (Android) counts the
   online CPUs. That works only if the OpenMP runtime has not started yet in the process, and no test starts the app
   in a small cpuset: not shown on the emulator or a phone. On Linux (GCC's libgomp, which ignores the variable) the
   count stays the start-up mask's: measured `threads 1/1` under `taskset -c 0` (C15 INFO line).
4. **Not built:** editing a preset's filter and auto-apply settings (only listed), exporting a style to a file
   (`.dtstyle` export was not asked). Built-in preset names are returned as darktable stores them (with its
   `_builtin_` prefix; desktop shows them translated without it): the app has to strip or translate them.
5. **Workspace only:** T5 `cr2 on-defringe region 0` mean 0.2209 against the listed bound 0.2194, the same with
   main's bridge (thread-count dependent, as P1c found for the workspace); CI (4 cores) passes it.
6. **Changes outside the brief's areas, with the reviewer's agreement:** `app/darkroom/.../DarkroomController.kt`
   (E1: an undecodable file from `open` is "no picture"), emulator tests f7 and f8 (section 3, item 1). The probe
   branch `p1b-defedit-probe` (two `emulator-test` runs) is not merged into this branch and holds nothing this
   branch needs: **it can be deleted** (my attempt to delete it was refused by the workspace's permission check).
7. darktable's shipped styles: `dt_init` imports them from the folder next to its executable, which is not
   darktable's in the app's process; the bridge now imports them from the given data folder (534 styles; 0.29 s the
   first time, 13 ms after, workspace). This is a change in start-up behaviour the brief did not name (open question 4).

## 2. Deliverable 4: the default-edit difference

**2.1 Not the XMP, not the session.** Through the bridge on Linux: open the public test RAW without an XMP, write the
XMP, render; darktable-cli of that XMP: **max 0** on cr2, raf and arw (C13, CI 287 PASS); JPEG 95 export vs
darktable-cli JPEG 95: max 0 (workspace); the bridge's own reopened XMP equals its default edit (max 0, modules()
equal). Nothing is applied in the session that the XMP does not carry.

**2.2 The module: sigmoid.** darktable-cli of the x86_64 Android engine in the emulator against the Linux build, float
output (`emulator-test` workflow on the probe branch, runs 44721469949 and 44723951808):

| case | PSNR | max abs | px > 1/255 |
|---|---|---|---|
| 0001-exposure, 0035, 0086 diffuse, 0096 lensfun, 0146, 0165, agx (0174, 0175), 0085 channelmixerrgb | 95-122 dB | ≤ 2.4e-2 | ≤ 0.001 % |
| **0097-sigmoid** | **45.3** | 3.9e-2 | 84.4 % |
| **0151-sigmoid-no-hue-preservation** | **45.2** | 3.7e-2 | 85.6 % |
| fresh default edit of mire1.cr2 | 64.6 | 7.4e-3 | 0.024 % |
| same, sigmoid off | 109.0 | 7.4e-3 | 0.000 % |
| same, color calibration off | 64.5 | 7.7e-3 | 0.067 % |
| same, sigmoid contrast 1.0 / 3.0 | 50.4 / 66.2 | | 20.0 / 0.019 % |
| same, sigmoid skew 0.5 | 51.0 | | 24.9 % |
| same, sigmoid rgb ratio | 65.3 | | 0.001 % |

Sigmoid is in every scene-referred default edit and not in G0's test XMP (75 dB), which matches P2j's observation.

**2.3 The cause: the compiler's treatment of `powf` in sigmoid's curve fit.** Only `libsigmoid.so` rebuilt on Linux
with other compilers and flags, swapped into a copy of the module folder, darktable-cli on 0097-sigmoid and on the
default edit (`P1b-results/sigmoid-compilers.sh`, output `sigmoid-compilers.txt`, workspace):

| sigmoid built with | vs Linux gcc build, 0097 | default edit | calls in `_compute_curve` (the fit) |
|---|---|---|---|
| gcc `-O3 -ffast-math -fno-finite-math-only` (darktable's Release flags; the Linux reference) | identical | identical | 4 × `expf`, 7 × `powf` |
| gcc, no fast-math | 45.3 dB, 84.414 % | 52.5 dB | 13 × `powf` |
| gcc, `-fno-math-errno -funsafe-math-optimizations` only | identical | identical | 4 × `expf`, 7 × `powf` |
| gcc, `-funsafe-math-optimizations` only | 45.3 dB | 52.5 dB | 13 × `powf` |
| **clang-18, same Release flags (as the NDK)** | **45.3 dB, 84.414 %** | **64.6 dB, 0.024 %** | 11 × `powf` |
| clang-18, no fast-math | 45.3 dB | 52.5 dB | 13 × `powf` |

The clang build on Linux reproduces the emulator's numbers to the digit (45.3 dB / 84.414 %, 64.6 dB / 0.024 %): the
difference is the compiler, not Android's libm. GCC, with `-fno-math-errno` and `-funsafe-math-optimizations`
together (both part of `-ffast-math`), rewrites four `powf(constant, y)` calls of `_compute_curve` (src/iop/sigmoid.c,
the fit of the curve to contrast, skew and targets at commit time) as `expf(y * log(constant))`. Those values differ in
the last bits; the fit (`film_fog` from a difference of two powers, then `film_power`, `paper_exp`) amplifies that into
a different curve: up to 4 % of the output value on 0097 (contrast and skew there make the fit sensitive), less on the
default edit. Clang does not do this rewrite, so the phone's sigmoid matches a build without fast-math in the fit;
the remaining default-edit difference between clang fast-math and strict builds (54.9 dB) comes from the per-pixel
loops. Not looked at further (the reviewer's one-hour bound).

**2.4 What it means for "edited on the phone, the same on the PC".** The XMP is the same; the PC's picture depends on
the PC's darktable build: gcc builds with darktable's Release flags (Linux distributions and, as far as I know, the
Windows builds made with MSYS2 gcc) render sigmoid as the Linux reference; clang builds (the NDK here; macOS builds use
clang) render it as the phone. Up to 45 dB (one level on many pixels) on a contrasty sigmoid setting, 64.6 dB on the
default edit. Not checked against an actual Windows or macOS darktable build.

**2.5 Proposals (nothing changed):** (a) upstream: make sigmoid's fit independent of this rewrite (for example compute
`_compute_curve` in double precision, or mark the file `-fno-unsafe-math-optimizations` as darktable already does for
spektrafilm with `-ffp-contract=off`, src/CMakeLists.txt); an upstream issue with the table above; (b) for the CI
comparisons only, build the Linux reference with clang so that it matches the engine (it then stops being "what a
typical Linux PC shows"). The engine test C13 (bridge vs darktable-cli on Linux, max 0) is added to the engine tests.

## 3. Brief item by item

| # | Item | State | Evidence |
|---|---|---|---|
| 1 | E1: undecodable file refused at `dtb_open`; no render on an empty input; tests; truncated RAW; Linux sanitizer | **done** | C10 (3 images), contract `openRefusesAnEmptyFile`, valgrind log |
| 2 | Presets: list, apply (one item), store, rename, delete, import, export | **done** (filter/auto-apply editing not built) | C11 (3 images, parity with darktable-cli max 0), contract tests on both engines |
| 3 | Styles: list, apply append/overwrite, import (one and several), delete, create (all or chosen); refusal; tests with public styles; parity with darktable-cli `--style` | **done, `--style` within 2 levels** (section 1.2) | C12, contract `stylesImportCreateApplyDelete` |
| 4 | Default-edit difference: reproduce, find module and cause, fix in the bridge if there, engine test | **partly**: reproduced, module and cause found; not in the bridge; not fixed | section 2, C13, probe runs |
| 5 | P2j's calls: colorin profiles, denoise current, WB preset position and tuning, out-of-range values, lens apertures | **done** | C14 (3 images, parity max 0), contract `moduleActionsForControlsOutsideTheParameters` |
| 6 | Threads and CPUs: three points | **points 1, 2 done; point 3 built, not verified on Android** | C15, section 1.3 |
| 7 | E2: per-module times as a call; the timings page uses it | **done** | C16, contract `renderTimesListTheLastRender`, `Timings.kt` |
| 8 | FakeEngine and contract suite; Linux engine tests; both ABIs; real-engine workflow with the contract suite | **done** (see section 6 for the last runs) | |

**Item 1 (E1).** `dtb_open` loads the full-size picture before it answers (`dt_mipmap_cache_get(DT_MIPMAP_FULL,
BLOCKING)`) and refuses a file whose loader failed: `DTB_E_IO`, message `cannot decode <path>: <reason> (load status
n)`; an empty file is refused by `dt_image_import` and answers `cannot decode <path>: darktable does not recognise it
as a picture (import refused)`. The prefix `cannot decode ` is part of the contract (engine-api.md 5.1); a missing file
(`cannot read`) and an unreadable XMP keep their own words (C10 checks both). Kotlin:
`EngineException.isUndecodableFile`; FakeEngine answers the same for an empty file; the contract pins it for both.
Second guard: `_pipe_ensure` refuses an image whose `load_status` is not OK, so no pipe ever starts on darktable's
placeholder picture. Results (workspace and CI, each of the three test RAWs):

| file | `dtb_open` answer |
|---|---|
| 2000 bytes of junk | IO, load status 3 ("no loader could decode it") |
| empty | IO, import refused |
| **test RAW cut at 0.1 %** | IO, load status 7 (read error) |
| **cut at 1, 10, 50, 90, 99, 99.9 %** | IO, load status 3: rawspeed reports "Buffer overflow: image file may be truncated", no other loader takes it |
| a PNG darktable-cli wrote | opens, image_info, preview and region render |

**What a truncated RAW does:** with this branch it is refused at `open` at every length tried (0.1 % to 99.9 %); it
never opens, so it is never rendered. **The P2f hang on Linux:** reproduced with main's bridge
(`P1b-results/e1_hang_repro.c`): open the junk file, ask for the preview three times. The first two answer ENGINE
(0 x 0); the third gets darktable's static placeholder picture as the downscaled input (`common/mipmap_cache.c`: a
zero-sized cached `DT_MIPMAP_F` entry is answered with `_dead_image_f` / `_unsupp_image_f` / `_error_image_f`), the
pipe runs on it as if it were sensor data, and the process aborts ("malloc(): unsorted double linked list
corrupted"). valgrind memcheck (`P1b-results/e1-valgrind-main-bridge.txt`): **invalid write of size 8 at
`src/iop/temperature.c:722` in `process()`** (the non-mosaiced loop, 4 floats per pixel) into a 1728-byte block that
`dt_dev_pixelpipe_cache_get` (`pixelpipe_cache.c:340`) allocated for 1-channel data: a heap overflow. That is the
emulator's "Scudo: corrupted chunk header"; the hang is what that corruption did there.
App side (reviewer's agreement): `DarkroomController` routes every `open` through one helper (`openOrRemember`): an
undecodable answer is remembered with the file's stamp for background passes and shows the NO_PICTURE sentence, as
P2h/P2i; the image_info / 0 x 0 path stays as the second guard; other IO errors keep their message and are not
remembered. New JVM test `P1bUndecodableTest` (3 tests). **Tests changed:** emulator `f7` now asserts what `open`
answers (IO, `cannot decode`) and that no session is left (before: it only recorded `open` OK and image_info ENGINE,
which is no longer true); `f8` additionally checks that the darkroom learnt it from `open`; everything else they
check (no hang, the good photo opens afterwards, LightPhotoLibrary preview of the good photo) is unchanged.

**Item 2 (presets).** `engine/src/presets.c` over darktable's own functions (`common/presets.c`, the interface-free
parts of `gui/presets.c`: `dt_gui_presets_apply_blend_params`, `dt_gui_presets_module_blend_blob`). List in desktop's
menu order with `fits` (desktop's filter query, `dt_presets_menu_rows_iop`), `matches`, `active`
(`dt_get_active_preset_name`'s rule), `usable` (version). Apply: parameters, switch, blending, label, one history
item, one undo step. Store: the edit dialog's defaults for a new preset; overwrite keeps filters and auto-apply.
Import refuses unknown modules, newer module or blend versions, built-in names (upstream's `INSERT OR REPLACE` would
otherwise turn a built-in preset into a user preset) and existing names unless asked. Export: `<dir>/<name>.dtpreset`
as desktop. Presets persist in `configdir/data.db` (C11 checks a second process).

**Item 3 (styles).** `engine/src/styles.c`. Apply follows `_styles_apply_to_image_ext` (common/styles.c) step for step,
but without its undo record: that record allocates ids in the same `memory.snapshot_*` tables as the bridge's undo
(`MAX(id)+1`), which would corrupt the bridge's undo states. Overwrite = the lighttable's job
(`dt_history_delete_on_image_ext` + the default edit again) + the style. A style with an item the engine cannot use
is refused before anything changes (`ARG`, "item 9 'nosuchmodule' unknown_operation ..."), but it is imported (listed
`usable: false`), as desktop imports it. Create: one entry per instance, the last chosen item, as desktop's dialog.
Public styles: darktable's example "day for twilight" (darktable, GPL-3.0, `data/styles`), renamed, and copies with an
unknown module and a newer module version made by the test. Parity: bridge render = darktable-cli of the bridge's XMP,
max 0 (append, overwrite, created, on the default edit; 3 images); vs darktable-cli `--style`: max 2 / 1 / 1 (the
export path in `imageio.c` puts the style's new exposure instance before the existing one, `exposure.1` at order 2600
and `exposure` at 2700 in `-d pipe`; desktop's path puts it after).

**Item 5 (module actions).** `colorin: list_profiles` (the image's own profiles as `update_profile_list` builds them,
then darktable's input and working profiles; `type` is the enum name `param_set` takes); `denoiseprofile:
list_profiles` gains `current` (gui_update's rule); `temperature: get_wb` gains `tuning` and `preset_index`
(`_match_camera_preset`'s search, exact then interpolated tunings); `borders: set_aspect` (0, -1 or 1..3) and `lens:
use_latest_md` (`md_version` 2, `scale_md_v1` 0). **Choice for P2j's question 1: module actions**, because the values
outside the limits are fixed values one callback writes; a flag on `param_set` would open every field to any value.
`lens: list_lenses` gains `min_aperture` and `max_aperture` (Lensfun's f-numbers; 288 of 302 lenses for the cr2's
camera have `min_aperture`).

**Item 6.** CPUs are checked against the process's set at the time of the call (its cpuset from the cgroup files,
intersected with the main thread's mask); requested ∩ allowed is applied; `[]` restores the allowed set now;
`engine_info` gains `cpus_allowed` and `cpus_configured`. C15 runs the engine on its own thread while the main thread
narrows and widens its own mask: allowed 4 → 1 → 4 CPUs followed (CI). The error text still names the refused CPU
("CPU 4 is not available to this process ..."), which the emulator test t1b reads.

**Item 7 (E2).** darktable measures per-module times only with `-d perf`. Without changing upstream the bridge wraps
each module library's `process_plain` once after `dt_init` (`engine/src/times.c`: instances copy it when loaded;
`default_process` calls it for whole buffers and tiles). `dtb_render_times` → `{pipe, total_ms, modules: [op,
priority, instance_name, ms, cpu_ms, tiles]}` for the last `render_preview` or `render_region`. C16: cr2 preview
51.5 ms total, 7 modules, 49.8 ms summed; after an exposure change 3 modules, the first exposure (workspace). The
timings page (`app-engine/.../Timings.kt`) reads it instead of darktable's log.

## 4. `dtbridge.h` changes

- `dtb_open`: now loads the picture and refuses undecodable files with `DTB_E_IO`, message prefix `cannot decode `.
- `dtb_engine_info`: adds `cpus_allowed`, `cpus_configured`.
- `dtb_set_thread_cpus`: checks against the process's allowed set now; applies requested ∩ allowed; `n == 0` restores the allowed set now.
- `dtb_module_action`: new actions `colorin list_profiles`, `borders set_aspect`, `lens use_latest_md`; new fields in `denoiseprofile list_profiles` (`current`), `temperature get_wb` (`tuning`, `preset_index`), `lens list_lenses` (`min_aperture`, `max_aperture`).
- New: `dtb_presets_list`, `dtb_preset_apply`, `dtb_preset_store`, `dtb_preset_rename`, `dtb_preset_delete`, `dtb_preset_import`, `dtb_preset_export`.
- New: `dtb_styles_list`, `dtb_style_apply`, `dtb_styles_import`, `dtb_style_delete`, `dtb_style_create`.
- New: `dtb_render_times`.
- `dtb_init` (behaviour, no signature change): imports darktable's shipped styles from `datadir/styles`; sets `KMP_AFFINITY=disabled` unless set.

## 5. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p1b-engine-presets-styles
tools/build-engine-linux.sh --deps-only
tools/fetch-darktable.sh /tmp/dt-src
DTA_WORK=/tmp/dta-work tools/build-engine-linux.sh /tmp/dt-src /tmp/dt-linux
tools/engine-test/fetch-paths.sh /tmp/dtt images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw \
  0001-exposure/exposure.xmp 0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp \
  0179-spektrafilm/spektrafilm.xmp
engine/tests/run_tests.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/results C10 C11 C12 C13 C14 C15 C16
# E1 on main's bridge (the heap overflow): build main's engine/ the same way, then
gcc docs/experiments/P1b-results/e1_hang_repro.c -I<main>/engine/include -L<main build> -ldtbridge -o hang
DT_PREFIX=/tmp/dt-linux valgrind ./hang /tmp/w junk.CR2      # junk.CR2: bytes 0..255 repeated, 2000 bytes
# deliverable 4: sigmoid built by gcc and clang (needs clang-18, libomp-18-dev; 0097-sigmoid's XMP from darktable-tests)
docs/experiments/P1b-results/sigmoid-compilers.sh /tmp/dta-work/engine-build-linux /tmp/dt-linux /tmp/dtt \
  <0097-sigmoid.xmp> docs/experiments/P1b-results/default-edit-mire1.xmp /tmp/sig
# app: JVM tests (fake engine, contract suite)
cd app && $GRADLE --no-daemon -Pdta.screenshots=false :engine-api:test :darkroom:testDebugUnitTest
```
CI: pushes to this branch run `g0-app` (engines, Linux bridge tests), `p2b-real-engine` (contract suite and scenarios
on the emulator, comparisons) and `p2a-app`.

## 6. Results

- Linux bridge tests, CI, g0-app 44723663988 (85f7e23): **287 PASS, 0 FAIL**; engines for both ABIs built.
- Contract suite on the emulator (p2b-real-engine 44723664038, 85f7e23): every contract test passed, the 6 new ones
  included; that run failed in `f8` and `t1b` only (the E1 behaviour change and the CPU error text, both addressed
  in 0c1fde1d).
- JVM: engine-api 52 tests, 0 failed (workspace); p2a-app 44730585350 (0c1fde1d): success, `P1bUndecodableTest` 3 of 3.
- **Last code commit 0c1fde1d**, all three workflows green:
  - g0-app **44730585389: success**: Linux bridge tests **290 PASS, 0 FAIL** (C10 to C16 on three images, all earlier
    tests); engines for arm64-v8a and x86_64 built; G0 emulator test passed.
  - p2b-real-engine **44730585226: success**: emulator **53 of 53** (contract suite with the 6 new tests, f7, f8, t1b
    included); comparisons: test XMP 75.73 dB, app-written XMP 74.44 dB, spektrafilm 60.42 dB, P2j XMPs 44.3-47.0 dB
    (sigmoid, section 2).
  - p2a-app **44730585350: success** (`P1bUndecodableTest` 3 of 3).
- Later commits on the branch change only `docs/` (no workflow runs on them).

## 7. Upstream surprises

- `common/mipmap_cache.c` `dt_mipmap_cache_get`: a failed image's `DT_MIPMAP_F` is a small static placeholder picture
  the second time it is asked for; a pipe built on it overflows the heap in `iop/temperature.c` `process()`.
- `common/darktable.c` `dt_init`: shipped styles are imported from `dt_loc_get_sharedir()` (next to the executable),
  not from `--datadir`.
- `common/presets.c` `dt_presets_import_from_file`: `INSERT OR REPLACE ... writeprotect 0`: importing a file named like
  a built-in preset replaces the built-in one with a user preset.
- `common/styles.c` `_styles_apply_to_image_ext`: its undo record allocates snapshot ids as `MAX(id)+1` in
  `memory.snapshot_history`, shared with any other user of those tables.
- `imageio/imageio.c` (darktable-cli `--style`) and `common/styles.c` (desktop) place a style's new instance at
  different pipe positions; `--style-overwrite` drops the default edit, unlike desktop's overwrite. The resulting
  iop order list holds `exposure,1` twice (`style_default_append.xmp`), harmless in renders.
- `src/iop/sigmoid.c` `_compute_curve`: the curve fit amplifies last-bit differences of `powf`; gcc's fast-math
  rewrite of constant-base `powf` changes the picture by up to 4 % (section 2).
- `src/iop/temperature.c`/`gui_update` and `denoiseprofile.c` `gui_update`: the "current" preset and profile are
  derived in interface code only; the bridge repeats those searches.

## 8. Open questions for the reviewer

1. Sigmoid (section 2.5): propose an upstream change (fit in double precision, or `-fno-unsafe-math-optimizations` for
   sigmoid.c), or build the engine or the Linux reference differently? Which PC build should be the reference for the
   owner's "same on the PC"?
2. Should C13 also compare a fresh default edit app export against darktable-cli in the emulator comparison (it is
   64.6 dB today, below nothing that fails)?
3. Is tolerance 2 / mean 0.5 for bridge append vs darktable-cli `--style` acceptable, given the exact XMP parity?
4. Shipped styles imported at `dtb_init` (534 entries in `styles()`): keep, or let the app choose?
5. The probe branch `p1b-defedit-probe` can be deleted; I could not delete it from the workspace.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKqnbz6rKrRZTvLsBxk

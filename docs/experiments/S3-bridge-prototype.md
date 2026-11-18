# S3: bridge prototype on Linux (Phase 0)

**Upstream patches: none.** Nothing under `upstream/` was changed and `patches/` does not exist. Every desktop-interface assumption met so far was worked around inside `engine/`.

**One acceptance test fails: T5 (region equals crop of the full render).** It fails on all three test images: 14 of 18 comparisons, max difference 182 levels on the CR2 (mean 0.11). The causes are in upstream modules, by design or close to it; see [T5](#t5-region-renders-why-they-differ). All other tests (T1 to T4, T6 to T9) pass on all three images. T8 passes its check, but the cancel latency on the 25 MP X-Trans file reaches 2.8 s (x86_64, 2 cores), which is not "prompt" for interactive use.

**Verdict: go, with conditions** (end of this file).

Measurements are from the build workspace: **x86_64, 2 cores, about 7 GB RAM**. They are not phone numbers.

## 1. Upstream pin (task 1)

`upstream/darktable` is a shallow submodule at `04477d32a00cc5abaea36d5bee084eb40c6eabb2` (development branch, 18 Nov 2026).

darktable's own submodules needed for this build (fetched by `engine/tests/build-darktable-linux.sh` with `git -C upstream/darktable submodule update --init --depth 1 <path>`):

| Submodule | Needed because |
|---|---|
| `src/external/rawspeed` | raw decoder, always built |
| `src/external/LibRaw` | `USE_LIBRAW` is on by default (CR3 and other formats) |
| `src/external/OpenCL` | headers only, needed **even with `USE_OPENCL=OFF`**: `src/common/iop_profile.h:31` includes `<CL/cl.h>` unconditionally |
| `src/external/libxcf` | `USE_XCF` is on by default |
| `src/external/whereami` | always (locates the installation at run time) |

Not needed: `src/external/lua-scripts` (Lua off) and `src/tests/integration` (the test images are cloned separately). Whether the build fails without each of the five was not tested one by one; the reasons above come from the CMake files and headers.

## 2. darktable build (task 2)

Ubuntu 24.04 packages are listed at the top of `engine/tests/build-darktable-linux.sh`. No optional library had to be built from source. Exact configure command (Ninja generator, source = the submodule):

```
cmake -G Ninja -S upstream/darktable -B <build> \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=<prefix> \
  -DUSE_LUA=OFF -DUSE_AI=OFF -DUSE_GMIC=OFF -DUSE_CAMERA_SUPPORT=OFF \
  -DUSE_MAP=OFF -DBUILD_PRINT=OFF -DUSE_COLORD=OFF -DUSE_LIBSECRET=OFF \
  -DUSE_KWALLET=OFF -DUSE_PORTMIDI=OFF -DUSE_SDL2=OFF \
  -DUSE_OPENMP=ON -DUSE_OPENCL=OFF
ninja -C <build> -j2 install
```

Configure found OpenMP 4.5, GTK 3.24.41, exiv2, lensfun, JXL, WebP, AVIF, HEIF, OpenEXR, ICU; OpenCV was not found (HDR alignment off). Options left at their defaults: `USE_MCP` (builds `darktable-mcp`), `USE_WAYLAND`, `USE_GRAPHICSMAGICK`. Build plus install took 7 min 20 s wall time with `-j2`.

During the experiment the build ran from a separate checkout at `/home/claude/dt/src` at the same commit. The script builds from `upstream/darktable`; the commit is the same.

`darktable-cli` from this build exports `mire1.cr2` with `0001-exposure/exposure.xmp` (3908 × 2602 PNG, 3.3 s). It is also the reference for every pixel comparison below.

## 3. The bridge (task 3)

- `engine/include/dtbridge.h`: the API, with its threading model and error codes documented in the header.
- `engine/src/dtbridge.c`: the implementation.
- `engine/src/xmp_check.c`: the version guard.
- `engine/CMakeLists.txt`: builds `libdtbridge.so` out of tree against the installed `libdarktable.so` and the upstream headers.

Functions, all as the brief lists them: `dtb_init`, `dtb_shutdown`, `dtb_xmp_check`, `dtb_open`, `dtb_close`, `dtb_is_read_only`, `dtb_modules_json`, `dtb_param_set_float/_int/_bool`, `dtb_set_enabled`, `dtb_history_seal`, `dtb_instance_new/_duplicate/_delete/_move`, `dtb_history_json`, `dtb_history_goto`, `dtb_history_compress`, `dtb_render_size`, `dtb_render`, `dtb_render_preview`, `dtb_render_cancel`, `dtb_xmp_write`, `dtb_export_jpeg`. There is also `dtb_mask_add_circle` (for T9 only).

Design points:

- **Start-up as `darktable-cli`:** `dt_init(..., init_gui = FALSE, load_data = TRUE, ...)` with `--library`, `--configdir`, `--cachedir`, `--datadir`, `--moduledir` and `--tmpdir`. `--conf write_sidecar_files=never` keeps the engine from ever writing an XMP itself; only `dtb_xmp_write` does, after the guard.
- **Renders use the export pipeline's behaviour with a darkroom-sized cache.** `dtb_render` builds a pipe with `dt_dev_pixelpipe_init` (64 cache lines), sets its `type` to `DT_DEV_PIXELPIPE_EXPORT`, feeds it the full-size input like `dt_imageio_export_with_flags`, and converts with `dt_display_to_8bit_pixels`. That is why T1 is bit-identical to `darktable-cli`. `dtb_render_preview` runs a second pipe on the downscaled `DT_MIPMAP_F` input, which is what the darkroom's preview pipe uses.
- **History merging:** consecutive changes to the same instance merge into one history item, as a slider drag does on desktop. The first change after open, after `goto`/`compress` or after an instance operation, and any change after `dtb_history_seal()`, starts a new item (`dt_dev_history_seal`).
- **Version guard:** XMP parsed with libxml2, no image load. It reports `unknown_operation`, `newer_module_version`, `newer_blend_version` and `newer_mask_version`. A session opened from a failing XMP is read-only (`dtb_xmp_write` returns `DTB_E_READ_ONLY`), and no session writes over an existing file that fails the guard.
- **Compile flags:** darktable installs no headers, and its struct layouts depend on compile definitions (`HAVE_OPENCL`, `HAVE_GPHOTO2`, `USE_LUA` and others). `engine/CMakeLists.txt` therefore reads the exact `-D`, `-I` and `-isystem` flags used for `src/common/darktable.c` from darktable's own build (`ninja -t commands lib_darktable`). A mismatch would corrupt memory silently. This needs a stable mechanism for the Android build (see conditions).

## 4. Tests (task 4)

One command, after building darktable and the bridge:

```
engine/tests/run_tests.sh <bridge build> <darktable prefix> <darktable-tests clone> <output dir> [T1 ... T9 X1]
```

Images (darktable-tests at `010281f`):

| Case | RAW | XMP | Pipeline highlights |
|---|---|---|---|
| cr2 | `mire1.cr2` (Canon Bayer, 3908 × 2602) | `0001-exposure/exposure.xmp` | exposure; old XMP (exposure v5, engine v7) |
| raf | `xtransIV.raf` (Fujifilm X-Trans, 6250 × 4168) | `0146-…-modversion-7.xmp` | lens, color calibration, filmic rgb |
| arw | `hlrecovery.arw` (Sony, 5492 × 3672) | `0179-spektrafilm/spektrafilm.xmp` | two spektrafilm and two exposure instances |

The full logs are in `docs/experiments/S3-results/`: `summary.txt` (T1 to T9) and `summary-X1.txt`, one directory per image, and the T5 diagnosis files. All "equal" comparisons below are on 8-bit RGB against `darktable-cli` of the same build. PSNR is infinite where max = 0.

| Test | cr2 | raf | arw | Result |
|---|---|---|---|---|
| T1 render parity | max 0, mean 0 | max 0, mean 0 | max 0, mean 0 | **pass**: bit-identical; modules and history JSON parse |
| T2 exposure +1 EV | history 9→10; render = cli of written XMP (max 0) | 12→13; max 0 | 14→15; max 0 | **pass** |
| T3 instances | duplicate → priority 1, name "1"; copy −0.5 EV, off, on; fresh session lists identical instances; fresh render max 0; cli max 0 | same, priority 1 | priority 2, name "2"; existing names kept | **pass**; darktable-cli reads back 11 / 15 / 17 history items, as the bridge holds |
| T4 history | goto 8 of 9 differs from top (max 68) and equals cli of the XMP written at step 8 (max 0); back to top max 0; compress 9→9 items, result max 0 | goto 11 (max 0 vs cli); compress 12→12 | goto 13 (max 0 vs cli); compress 14→12 items, result max 0 | **pass** (after the fix below) |
| T5 region = crop | 3 regions: max 182 / 75 / 64, mean ≤ 0.11 | max 40 / 12 / 17, mean ≤ 0.10 | max 21 (centre), 0, 0 | **fail**, see below |
| T6 version guard | exposure v8 (engine v7) and unknown op both reported; all writes refused (−7); files unchanged | same | same | **pass** |
| T7 timing | table below | | | measured |
| T8 cancel | 3 cancels, all `DTB_E_CANCELLED`, worst 0.20 s; next render max 0 | worst **2.78 s** (render 12.7 s) | worst 0.47 s (render 4.0 s) | **pass**; latency is bounded by the longest single module (see below) |
| T9 drawn mask (stretch) | new exposure instance +2 EV with a circle mask: change inside 74.8 levels, corners 0.000; cli of the XMP max 0 | inside 74.5, corners 0.000; max 0 | inside 76.3, corners 0.000; max 0 | **pass** |
| safety | RAW md5 unchanged | | | pass |

**X1 (beyond the brief):** T1 to T9 do not exercise `dtb_render_preview`, `dtb_export_jpeg`, `dtb_instance_move` or `dtb_instance_delete`, so X1 does. On all three images:
- After a move, and after new, duplicate, delete and deleting priority 0, the XMP renders in darktable-cli equal to the bridge (max 0).
- A fresh session lists the same instances.
- The JPEG decodes identically to darktable-cli's at quality 95.
- Deleting the last instance is refused.
- The preview is *not* equal to darktable-cli's 1024 px export: PSNR 26.4 dB on cr2 and 32.1 dB on arw; on raf the sizes differ by one row (683 vs 682). It runs on the downscaled input by design, as the darkroom preview does. Its accuracy has not been judged further.

### T5: region renders, why they differ

`engine/tests/t5_bisect.sh` switches each operation off in turn. demosaic cannot be switched off, so the driver can also set demosaic to its passthrough method (`DTB_TEST_DEMOSAIC_METHOD`). The region test also measures, for information only, a region rendered with a 16, 64 or 256 px margin and cropped. Logs: `S3-results/t5-*.txt`.

1. **demosaic: no border around the region.** `modify_roi_in()` in `src/iop/demosaic.c` (line 622) requests exactly the output region, so photosites at the region's edge are interpolated without their outside neighbours.
   - cr2: every differing pixel is within 3 px of the region's edge; raf (X-Trans): within 12 px.
   - With passthrough demosaic all cr2 regions are identical, and raf is identical apart from one value of 1 (0 with any margin).
   - With a 16 px margin, the regular demosaic gives identical cr2 regions and two of three raf regions. The raf region at the image corner improves only slowly (max 10 / 8 / 4 at 16 / 64 / 256 px) and is identical with passthrough. RCD (`src/iop/demosaicing/rcd.c`, `RCD_BORDER`) and the X-Trans demosaicers work in internal tiles. That is a plausible reason, **not verified**.
2. **spektrafilm** (arw, centre region only, max 21, differences spread over the whole region).
   - Switching spektrafilm off removes this pattern. A margin of up to 256 px changes nothing.
   - Its `modify_roi_in()` (`src/iop/spektrafilm.c` line 2142) widens the input by its halo, clipped to the image. With this edit the halo appears to cover the whole image, so both renders see the same input, and the difference comes from spektrafilm's own processing of a smaller output window.
   - The comments at `_sf_work_window()` / `_sf_shrink_to_window()` say that window is exact. My **unverified hypothesis** is that the reach computed for it is too small for this edit. This may be worth an upstream issue after confirmation.
   - With spektrafilm off, the arw shows demosaic differences again (max 124 without margin, 68 with 64 px), which disappear with passthrough demosaic. spektrafilm's full-image input had hidden them.

The darkroom renders its visible region the same way, so a desktop user sees these edge effects at the viewport's edge too. For the app this matters if a view is ever stitched from several region renders.

### T7: timing (x86_64, 2 cores; seconds)

| | cr2 (10 MP) | raf (26 MP) | arw (20 MP) |
|---|---|---|---|
| open (import, XMP, history) | 0.20 | 0.95 | 0.13 |
| first full render | 0.90 | 11.12 | 3.89 |
| full render, nothing changed (cache) | 0.08 | 0.20 | 0.21 |
| full render after a **late** change (vignette on) | 0.31 | 12.96 | 4.47 |
| full render after an **early** change (exposure) | 1.24 | 12.93 | 4.51 |
| 1920 × 1080 region after a late change | 0.34 | 1.38 | 1.79 |
| preview 1024 px, first | 0.16 | 0.64 | 0.22 |
| preview after a late change | 0.05 | 0.08 | 0.09 |
| preview after an early change | 0.08 | 0.32 | 0.14 |

**Does the pipeline cache help?** For the preview, yes: 0.08 s instead of 0.64 s after a late change on the raf. It also helps a full render of the 10 MP cr2 (0.31 s vs 0.90 s). For full-resolution renders of the 20 and 26 MP files it does **not**: a late change costs as much as a first render. A likely reason, **not verified**, is that full-size intermediate buffers (about 400 MB each at 26 MP) do not fit the cache budget of the default resource level (`resourcelevel=default`). This should be measured on the phone with its memory, and with a screen-sized region rather than full renders.

On the raf, the longest single module steps of a full export are filmic rgb 3.06 s and lens 2.56 s (`darktable-cli -d perf`). The pixelpipe only checks for a stop between modules, so they set the worst-case cancel latency of T8.

## 5. Upstream functions the bridge relies on

| Area | File: functions |
|---|---|
| start, stop | `common/darktable.c`: `dt_init`, `dt_cleanup` |
| import | `common/film.c`: `dt_film_init`, `dt_film_new`, `dt_film_cleanup`; `common/image.c`: `dt_image_get_id`, `dt_image_import`, `dt_image_set_history_end`; `common/image_cache.c`: `dt_image_cache_get`, `dt_image_cache_write_release` |
| XMP | `common/exif.cc`: `dt_exif_xmp_read`, `dt_exif_xmp_write` |
| develop | `develop/develop.c`: `dt_dev_init`, `dt_dev_load_image`, `dt_dev_cleanup`, `dt_dev_pop_history_items_ext`, `dt_dev_add_history_item_ext`, `dt_dev_add_masks_history_item_ext`, `dt_dev_history_seal`, `dt_dev_write_history`, `dt_dev_module_duplicate_ext`, `dt_dev_module_remove`, `dt_dev_free_history_item` |
| history | `common/history.c`: `dt_history_compress_on_image` |
| module order | `common/iop_order.c`: `dt_ioppr_resync_modules_order`, `dt_ioppr_resync_iop_list`, `dt_ioppr_get_iop_order_link`, `dt_ioppr_check_can_move_before_iop`, `dt_ioppr_check_can_move_after_iop`, `dt_ioppr_move_iop_before`, `dt_ioppr_move_iop_after`, `dt_sort_iop_by_order` |
| modules | `develop/imageop.c`: `dt_iop_get_module_so`, `dt_iop_is_hidden`, `dt_iop_reload_defaults`, `dt_iop_commit_blend_params`, `dt_iop_update_multi_priority`; per module `version()`, `flags()`, `get_f()`, `get_introspection_linear()` (`common/introspection.h`) |
| pipeline | `develop/pixelpipe_hb.c`: `dt_dev_pixelpipe_init`, `_set_icc`, `_set_input`, `_create_nodes`, `_synch_all`, `_get_dimensions`, `_change`, `_process_no_gamma`, `_set_shutdown`, `_shutdown_to_str`, `_cleanup_nodes`, `_cleanup`; `common/mipmap_cache.c`: `dt_mipmap_cache_get`, `dt_mipmap_cache_release`; `common/colorspaces.c`: `dt_display_to_8bit_pixels` |
| masks | `develop/masks/masks.c`: `dt_masks_create`, `dt_masks_assign_unique_name`, `dt_masks_group_insert_point`, `dt_masks_iop_use_same_as`, `dt_masks_version`; `develop/blend.c`: `dt_develop_blend_version` |
| export | `imageio/imageio.c`: `dt_imageio_export`, `dt_imageio_get_format_by_name`; `common/metadata_export.c`: `dt_lib_export_metadata_default_flags`; `control/conf.c`: `dt_conf_set_int` |

Every one of these is a place where an upstream bump can break the bridge at compile time, which is the intended failure mode (PLAN.md section 5, step 5).

## 6. What assumed the desktop interface, and how the bridge gets around it

1. **`dt_dev_add_history_item()`** (develop.c, `_dev_add_history_item`) returns at once without `darktable.gui`. The bridge calls `dt_dev_add_history_item_ext(..., no_image = TRUE)`. `no_image` must be TRUE: with `dt_dev_init(dev, FALSE)` the develop object has no pipes (`dev->full.pipe` is NULL), and the `_ext` path would otherwise flag them. The bridge flags its own pipes instead.
2. **`dt_dev_load_image()`** reads the history but does not apply it to the modules; the darkroom does that later. The bridge calls `dt_dev_pop_history_items_ext(dev, history_end)` after loading, as upstream's `darktable-mcp` does.
3. **Compress:** `dt_dev_history_truncate()` reloads through `dt_dev_reload_history_items()`, which dereferences `dev->full.pipe` and updates widgets. The bridge writes the history, calls `dt_history_compress_on_image()`, reloads its develop object, and resets `history_end` to the new item count. Without that reset the arw kept `history_end` 14 with 12 items; T4 found this, and the fix is commit "Bridge: set history_end to the item count after compressing".
4. **Duplicate and new instance:** `dt_iop_gui_duplicate()` (imageop.c) mixes the logic with widget code. The bridge repeats its non-widget steps: the base item, `dt_dev_module_duplicate_ext`, `dt_iop_reload_defaults`, copying params, blend params and shared masks, and the new item.
5. **Delete:** `_gui_delete_callback()` relies on `dt_dev_module_remove()`, which removes the module's history items only when `gui_attached`. The bridge removes them itself and renumbers priority 0 as desktop does. Two differences from desktop, both deliberate:
   - `history_end` only moves for items below it (`dt_dev_module_remove` decrements it for every removed item).
   - The iop order list is updated with the renumbering, so the next instance keeps its own place in the pipe when the XMP is read again.
6. **Move:** the desktop callbacks move past the next *visible* module in the panel. The bridge moves past the next non-hidden module, applies the same pipeline rules (`dt_ioppr_check_can_move_*`), and, like desktop, switches the moved module on.
7. **Masks:** `dt_masks_gui_form_save_creation()` and `dt_masks_group_insert_member()` record history through `dt_dev_add_masks_history_item()`, which does nothing without the interface. The bridge calls the `_ext` variant and sets `mask_mode |= DEVELOP_MASK_ENABLED | DEVELOP_MASK_FLEXI`, which is what the mask switch does (`_blendop_mask_enable` in blend_gui.c). Note that the masks code uses the global `darktable.develop` (for example `_check_id`, `dt_masks_replace_current_forms`). Without the interface it still exists (darktable.c allocates it in `dt_init`), so this works, but form ids are checked against the wrong list.
8. **Pipe type and shutdown:** see section 3. After each run the bridge resets `pipe->shutdown` with an atomic exchange, as `dt_dev_process_image_job()` does.

## 7. Things in upstream that surprised me

- darktable installs **no headers**, and the bridge has to match its compile definitions exactly (section 3).
- `src/common/metadata_export.h` has no include guard. Including it after `imageio_module.h` fails to compile.
- `src/common/iop_profile.h` includes `<CL/cl.h>` unconditionally (section 1).
- A history entry with a **newer module version** is not refused: `dt_dev_read_history_ext()` (develop.c) uses the module's default parameters with only a log message, and an **unknown operation** is dropped ("illegal iop_order"). Without the guard, writing the XMP back would destroy that part of the edit.
- `dt_history_compress_on_image()` leaves `images.history_end` pointing past the shortened history.
- temperature keeps **NaN** in an unused parameter (`various`); json-glib then writes invalid JSON (`nan.0`). Fixed in the bridge: non-finite numbers become `null`.
- `darktable-cli` loads `data.db` (presets, styles) by default (`custom_presets = TRUE` in cli/main.c); the bridge does the same.
- Upstream now ships **`darktable-mcp`** (`src/mcp/dt_bridge.c`), a headless driver that also sets parameters by introspection name and renders through the export path. It was a useful reference, and it is worth watching as possible common ground.
- The T5 causes above (demosaic's margin-less region, spektrafilm's window).

## 8. Deviations from the brief

- **Desktop darktable was not started.** PLAN.md's pass condition for S3 says "desktop darktable opens the XMP with the same history". There is no display on the workspace. The stand-in is `darktable-cli`, which loads the written XMP through the same `dt_exif_xmp_read()` the desktop uses on import, plus `--verbose` listing that history (T3: 11 / 15 / 17 items, as the bridge holds) and pixel equality (T2, T3, T4, T9, X1). Opening the written XMPs in desktop darktable 5.7 remains to be done by hand.
- **T2** sets exposure to *current value + 1 EV* (1.0 → 2.0, 0.7 → 1.7), reading "+1 EV" as a change of one stop.
- **T4** checks "renders as that step did" against `darktable-cli` rendering the XMP the bridge writes at that step (`history_end` = step). darktable-cli cannot select a step itself.
- **T6:** the "newer" XMP uses the engine's version + 1, not the XMP's own version + 1. The cr2 test XMP is older than the engine, so +1 on its own version is still not newer; my first version of the test made exactly that mistake.
- **T8:** each cancel attempt first changes exposure, so that a real render is in flight rather than a cached result that ends before the cancel arrives.
- Added beyond the brief: X1 (section 4), `dtb_history_seal`, `dtb_render_size`, `dtb_is_read_only`, `dtb_engine_version`, `dtb_last_error`, `dtb_free`, and `dtb_mask_add_circle` (needed for T9). The test runner uses python3 with numpy and PIL for image comparison (Ubuntu packages); nothing else was added.

## 9. Verdict: go, with conditions

Upstream darktable, started without its interface, can be driven through a small C API with **no source change**. Edits made through the bridge produce XMPs that darktable reads back with the same history and renders pixel for pixel the same. That covers parameters, on/off, new, duplicate, move and delete instances, history go-to and compress, and a drawn mask. The version guard works. Everything the brief asks for exists. The one failing test (T5) is caused by upstream modules behaving as the darkroom also behaves, not by the bridge.

Conditions:

1. **Region renders are not crops of the full render.** Render regions with a margin of at least 16 px and crop, which removes the demosaic edge band in most cases. Accept small residual differences from tiled demosaicers and spektrafilm, or avoid stitching views from several regions. Decide this in Phase 1, with the test kept as a measurement.
2. **Cancel latency equals the longest module step**, up to 3 s at 26 MP full resolution on this machine. Interactive renders must stay screen-sized (regions, previews), and the phone's latency must be measured at G0.
3. **The pipeline cache did not help full-resolution renders of 20 to 26 MP files** with the default resource level. Measure on the phone with screen-sized regions and the resource level the app will use.
4. **The bridge must be compiled with darktable's exact compile definitions.** The Android build (S1) needs a reliable way to export them from the darktable build. A mismatch is a silent memory error, not a build error.
5. **The bridge re-implements desktop logic for instances, delete, compress and masks** (section 6). These copies must be compared with upstream at every version bump. Listing them in `docs/UPDATE.md` is proposed.
6. **The desktop read-back of written XMPs has to be confirmed by hand once** (section 8).

## 10. Reproduce from a clean machine

```
# Ubuntu 24.04; packages: see the header of engine/tests/build-darktable-linux.sh
git clone https://github.com/nannolo45/darktable-android && cd darktable-android
git checkout s3-bridge-prototype && git submodule update --init --depth 1 upstream/darktable
engine/tests/build-darktable-linux.sh /tmp/dt-build /tmp/dt-install           # about 7 min on 2 cores
engine/tests/fetch-test-images.sh /tmp/darktable-tests
cmake -S engine -B /tmp/bridge-build -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DDT_SOURCE_DIR=$PWD/upstream/darktable -DDT_BUILD_DIR=/tmp/dt-build -DDT_INSTALL_PREFIX=/tmp/dt-install
ninja -C /tmp/bridge-build
engine/tests/run_tests.sh /tmp/bridge-build /tmp/dt-install /tmp/darktable-tests /tmp/s3-results          # T1 to T9, X1: about 12 min
engine/tests/t5_bisect.sh /tmp/bridge-build /tmp/dt-install /tmp/darktable-tests/images/mire1.cr2 \
  /tmp/darktable-tests/0001-exposure/exposure.xmp /tmp/t5-cr2                                             # T5 diagnosis
```

`run_tests.sh` exits 1 while T5 fails; `summary.txt` in the output directory lists every check.

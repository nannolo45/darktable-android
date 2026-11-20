# P1b part 1: presets and styles in the engine, and the open engine points (running log)

Brief: `docs/briefs/P1b-engine-presets-styles.md`. Report: `P1b-engine-presets-styles-REPORT.md`.
Workspace numbers are x86_64, 2 cores; CI numbers are GitHub runners (x86_64, 4 cores). Neither are phone numbers.

## 2026-11-20

- 10:20 Branch from main 5b43bc8. Read CLAUDE.md, the brief, engine-api.md, PLAN 3.2/3.9, P2f 7, P2g 5, P2h 0,
  P2j 5/6/9, P1c.
- **Build decision: rebuild darktable for Linux locally** (`tools/build-engine-linux.sh`, Ubuntu packages, pinned
  source 04477d3 fetched with `tools/fetch-darktable.sh` into a work folder outside the repository). Reason: this
  package needs many short engine iterations (presets and styles against darktable's database, the default-edit
  difference needs a bisection, E1 needs a sanitizer run), which CI cannot give at one Linux rebuild per push. CI
  (`g0-app` for the Linux bridge tests, `p2b-real-engine` for both ABIs and the emulator) is used for the final
  proof, with batched pushes. Started 10:24.
- 10:55 engine-api.md section 5 written and pushed (exact signatures and JSON of every new or changed call).
- 10:35 Linux build done (darktable 04477d3 + bridge, about 10 minutes of compile time on the workspace with the
  packages installed; workspace x86_64, 2 cores). Test images fetched with `tools/engine-test/fetch-paths.sh`.
- **E1.** `dtb_open` now loads the full-size picture (`dt_mipmap_cache_get(DT_MIPMAP_FULL, BLOCKING)`) and refuses a
  file whose loader failed with `DTB_E_IO` and the loader's status; `_pipe_ensure` also refuses an image whose
  `load_status` is not OK. Results on mire1.cr2 (workspace): junk → IO, status 3; empty → IO (dt_image_import
  refuses it); the RAW cut at 0.1 % → IO status 7 (read error), at 1, 10, 50, 90, 99, 99.9 % → IO status 3
  (rawspeed "Buffer overflow: image file may be truncated", then no other loader takes it); a PNG darktable-cli wrote
  still opens and renders.
- **The P2f hang reproduced on Linux with main's bridge** (`P1b-results/e1_hang_repro.c`): open the junk file,
  `render_preview` three times. The first two refuse (0 x 0); the third gets darktable's static placeholder picture
  as the downscaled input (`common/mipmap_cache.c`: a zero-sized cached DT_MIPMAP_F entry is answered with
  `_dead_image_f` / `_unsupp_image_f` / `_error_image_f`), the pipe runs rawprepare on it ("wrong crop parameters
  ... for size=0x0") and the process aborts with "malloc(): unsorted double linked list corrupted". Under valgrind
  memcheck (`P1b-results/e1-valgrind-main-bridge.txt`): **invalid write of size 8 in `process()` of
  `src/iop/temperature.c:722`** (the non-mosaiced loop, 4 floats per pixel) into a block of 1728 bytes allocated by
  `dt_dev_pixelpipe_cache_get` (`pixelpipe_cache.c:340`) for 1-channel data: a heap overflow. The emulator's hang
  and its "Scudo: corrupted chunk header" are the same fault. With this branch the file never opens.
- Presets (`presets.c`), styles (`styles.c`), render times (`times.c`), new module actions (`actions2.c`) built;
  tests C10 to C16 added to `dtb_test` and `run_tests.sh`.
- **Shipped styles.** dt_init imports share/darktable/styles from the folder next to its executable
  (`dt_loc_get_sharedir`), which is not darktable's in the app's process (or in dtb_test): 0 styles. The bridge now
  imports them from the data folder it is given: 534 styles; 290 ms the first time, 13 ms afterwards (workspace).
- **Deliverable 4 (default edit).** On Linux through the bridge: default edit → XMP → darktable-cli: **max 0** (cr2,
  raf, arw; PNG and JPEG 95). The bridge's reopened XMP equals its default edit: max 0. So the XMP carries the whole
  edit. The difference is between the Android build and the Linux build of the engine: probe run 44721469949
  (emulator-test workflow, branch `p1b-defedit-probe`, darktable-cli of the x86_64 Android engine in the emulator vs
  the Linux build, float output): every module tested 95 to 122 dB except **sigmoid: 0097-sigmoid 45.3 dB (84 % of
  pixels off by more than 1/255), 0151-sigmoid-no-hue-preservation 45.2 dB**; channelmixerrgb 112 dB, agx 110-112 dB;
  the fresh default edit 64.6 dB. Sigmoid is in every scene-referred default edit and not in G0's test XMP (75 dB).
  Cause inside sigmoid not found (no Android build on the workspace).
- Style append vs darktable-cli `--style`: max 2 / 1 / 1 levels (cr2 / raf / arw), mean 0.17-0.19. darktable-cli
  builds the style's history in the export path and puts the new exposure instance before the existing one
  (`exposure.1` at order 2600, `exposure` 2700, `-d pipe`); desktop's path puts it after. `--style-overwrite`
  replaces the history with the style alone (18-23 dB from desktop's overwrite): not desktop's overwrite.
- 10:58 Pushed 85f7e23 (Kotlin interface, JNI, FakeEngine, contract tests, timings page): CI g0-app 44723663988,
  p2b-real-engine 44723664038, p2a-app 44723663915.

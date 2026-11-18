# Pull request: S1 Android build of the engine (with S2 OpenMP, S6 GTK broadway)

Branch `s1-android-build` (not merged; `gh` is not signed in on the workspace,
so this file is the pull request description). Technical write-up:
`docs/experiments/S1-android-build.md`.

**Acceptance status in one line:** everything the brief asks for is
delivered and green in CI, with one limit stated first: the **arm64-v8a
engine is built and statically checked but has not been executed**; only the
x86_64 engine ran (in the emulator), as the brief specifies. Zero patches to
darktable; two Android build flags instead (below).

## 1. Brief item by item

| item | status | evidence |
|---|---|---|
| `deps/`: one recipe per library, pinned version + checksum, NDK cross setup, one entry script per ABI | **done** (31 recipes, 30 libraries + NDK zlib) | `deps/recipes/*.sh`, `deps/build-sysroot.sh`, `deps/lib/common.sh` (meson cross file generated per ABI, NDK CMake toolchain) |
| current LTS NDK, version recorded; API 29; 16 KB alignment | **done**: NDK r30 30.0.16248370, API 29, `max-page-size=16384`; 0 misaligned ELF files in both sysroots and both engines | `deps/config.sh`; `alignment` columns in `summary.tsv`; engine reports |
| set kept small, list of what was switched off and why | **done** | write-up, "Switched off in darktable" |
| Termux as reference, origin and licence of taken patches | **done**: 2 patches taken (GTK broadway, no `shm_open`), headers say origin, commit, licence | `deps/recipes/gtk3/*.patch` |
| S6: GTK3 broadway only | **done**: builds and links; no X11/Wayland libraries. X11 fallback not needed, so its size cost was not measured | `deps/recipes/gtk3.sh`; GTK 8.0 MB stripped on arm64 |
| S2: OpenMP on | **done**: OpenMP 5.1 via NDK libomp; 2.0x to 3.1x speed-up on 4 emulator cores, 1-thread and 4-thread output bit-identical | emulator run 44419865243 |
| workflow `deps` (per ABI, release named by hash of `deps/`, skip if present) | **done**: release `deps-0a9f7c84ba9d` | `.github/workflows/deps.yml` |
| workflow `engine` (darktable lib, plugins, cli, data per ABI, archive) | **done**: release `engine-04477d32-d2369345ff68` | `.github/workflows/engine.yml`, `tools/build-engine-android.sh` |
| workflow `emulator-test` (API 33 x86_64, ≥ 2 public images with XMP, compare with Linux build of same commit, max/mean/PSNR, 1 vs all threads) | **done**: 7 tests, 4 images (CR2, ARW, two RAF incl. X-Trans IV) | `.github/workflows/emulator-test.yml`, `tools/engine-test/`, `tools/render-compare.py` |
| logs to `ci-logs`, also on failure; check of unauthenticated API | **done**; API finding below | `tools/ci/push-ci-logs.sh`, branch `ci-logs` |
| write-up with libraries, versions, sizes, patches, zero-patch status, build times, emulator results, uncertainties, verdict | **done** | `docs/experiments/S1-android-build.md` |

Workflows are chained by `.github/workflows/pipeline.yml` (`s1-pipeline`,
on pushes to this branch and on manual dispatch); each one can also be started
alone. Only the default `GITHUB_TOKEN` is used (`permissions: contents:
write`), actions are pinned by commit.

## 2. How to reproduce

In CI: push to `s1-android-build` or dispatch `s1-pipeline`. Locally on Ubuntu 24.04 (x86_64):

```sh
sudo mkdir -p /opt/dta && sudo chown "$USER" /opt/dta
deps/host-setup.sh "$HOME/dta-host" > /tmp/env.sh && . /tmp/env.sh   # NDK r30, meson, Rust, cargo-c
deps/build-sysroot.sh arm64-v8a            # -> /opt/dta/sysroot-arm64-v8a, logs in ./_dta-work/logs
tools/fetch-darktable.sh "$HOME/darktable" # pinned 04477d3 + submodules, refuses a modified tree
tools/build-engine-android.sh arm64-v8a "$HOME/darktable" out   # -> out/engine-arm64-v8a.tar.gz + report
# render test on a device or emulator with adb:
tools/engine-test/fetch-tests.sh tests
tools/engine-test/emulator-run.sh out/engine-x86_64.tar.gz tests emu
tools/build-engine-linux.sh "$HOME/darktable" "$HOME/dt-linux"
for t in 1 all; do sh tools/engine-test/run-renders.sh "$HOME/dt-linux" tests ref/t$t $t; done
python3 tools/render-compare.py ref/tall emu/tall
```

## 3. Results (numbers)

- **Libraries** (versions; stripped `.so` sizes per ABI in the write-up):
  zlib (NDK), proxy-libintl 0.5, libffi 3.8.1, pcre2 10.49, glib 2.90.1,
  expat 2.10.0, libpng 1.6.59, freetype 2.15.0, fontconfig 2.18.4, pixman
  0.46.4, cairo 1.18.6, fribidi 1.0.18, harfbuzz 15.1.0, pango 1.58.2,
  libjpeg-turbo 3.2.1, libtiff 4.7.2, gdk-pixbuf 2.44.8, libepoxy 1.5.10, atk
  2.38.0, gtk3 3.24.52, libxml2 2.15.4, json-glib 1.10.8, sqlite 3.54.1,
  lcms2 2.20, lensfun 0.3.4, potrace 1.16, pugixml 1.16, exiv2 0.28.9, curl
  8.23.0 (no TLS), libarchive 3.8.11, librsvg 2.63.4. All 31 build for both
  ABIs (run 44407305110). Sum of stripped `.so`: 31.5 MB arm64, 32.2 MB x86_64.
- **Engine size, stripped**: arm64-v8a 61.3 MB (darktable lib + 153 plugins
  11.8 MB, dependencies incl. libc++/libomp 34.9 MB, darktable data 10.4 MB,
  lens database 4.2 MB), `tar.gz` 21.2 MB. x86_64 63.4 MB, `tar.gz` 22.3 MB.
- **Build times** (4-core runner): sysroot from scratch 351 s (arm64) / 512 s
  (x86_64); darktable 130 s / 124 s; whole pipeline with cached sysroot about
  40 min, dominated by the Linux reference job.
- **Emulator vs Linux** (API 33 x86_64, all threads; 1 thread gives identical
  images): max abs diff 9.7e-4 to 2.4e-2, mean abs diff 7.7e-8 to 4.3e-4,
  PSNR 66.8 to 121.6 dB, at most 0.001 % of pixels off by more than 1/255.
  Lowest PSNR: X-Trans Markesteijn demosaic (66.8 dB).
- **1 thread vs 4 threads, emulator** (sum of 7 renders): 98.9 s vs 38.7 s
  (2.6x; per test 2.0x to 3.1x). x86_64 cloud numbers, **not phone numbers**.
- **GitHub API from the workspace**: run lists, job lists and even job log
  downloads answer 200, because the workspace proxy authenticates for this
  repository (15000/h rate limit; other repositories are refused). This is not
  anonymous access. Logs of running jobs are not available. `ci-logs` is
  written regardless.

CI runs: 44419865243 (latest full pipeline, all jobs green; the numbers above),
44414667453 (first full green pipeline), 44407305110 (sysroots built from
scratch, release `deps-0a9f7c84ba9d`). Earlier runs failed and are described in
the write-up's running log.

## 4. Surprises in upstream and elsewhere

- darktable compiles with `-Werror -Wfatal-errors` unless `SOURCE_PACKAGE`
  (`src/CMakeLists.txt`); bionic's deprecation of `strtok()` breaks
  `src/common/utility.c`, `src/gui/accelerators.c`, `src/gui/presets.c`.
  Worked around with `-Wno-error=deprecated-declarations`, no patch.
- `src/control/jobs.c` `_control_work_res()` calls `pthread_setcancelstate()`,
  which bionic lacks. Worked around with a force-included header that makes
  it a no-op (exact, since Android has no thread cancellation).
- `darktable-cli` builds and links the GUI views and lighttable modules too
  (they are part of the plugin set); harmless, but 153 plugin files ship.
- darktable fetches spektrafilm data over HTTPS at run time
  (`src/common/spektra_fetch.c`, GitHub raw). With our TLS-less curl that
  download cannot work; the app should ship the data.
- meson builds for `host_machine.system = 'android'` produce unversioned
  sonames, which suits APK packaging; sqlite's autosetup build gives
  `libsqlite3.so.0`.
- My own first driver reported failed recipes as ok (errexit is ignored in a
  subshell inside `if`); one bad release file, `deps-983e02852ccd`, came from
  that run.

## 5. Open questions for the reviewer

1. Please delete release `deps-983e02852ccd` (wrong, see above) and, if you
   like, the superseded `engine-04477d32-3d802276c4ef`. The workspace has no
   token for that.
2. Are the two build flags acceptable as "zero patches", or should the
   `pthread_setcancelstate` shim become a file in `patches/` instead (it would
   then be a source change to `jobs.c`)?
3. curl without TLS: ship spektrafilm data with the app (my recommendation), or
   add OpenSSL (a few MB more)?
4. Should the engine archive drop the GUI-only plugins (views, lighttable
   modules), or wait until the bridge (S3) shows which ones the engine needs?
5. Commits carry `Co-Authored-By: Claude Opus 5.5` following the session's
   attribution instruction, not the model name given in the task message.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh

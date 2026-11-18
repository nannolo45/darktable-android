# S1: building the darktable engine for Android (with S2 OpenMP and S6 GTK broadway)

Brief: `docs/briefs/S1-android-build.md`. Branch `s1-android-build`.
Results below come from GitHub Actions runs on standard `ubuntu-24.04` runners
(4 cores); their logs are on the `ci-logs` branch under `runs/<run id>-a<attempt>/`.

## Read this first: changes to darktable

**Zero patches to darktable held.** `patches/` is empty and `upstream/` is not
touched (the CI fetches commit `04477d32a00cc5abaea36d5bee084eb40c6eabb2` and
`tools/fetch-darktable.sh` refuses a modified tree). Two Android-specific
*build settings* were needed instead, both in `tools/build-engine-android.sh`;
the reviewer should know about them because they are the nearest thing to a
patch:

1. `-include tools/android-compat/dt_android_compat.h` on every compile. It
   defines `pthread_setcancelstate()` as a no-op, because bionic has no thread
   cancellation and `src/control/jobs.c` (`_control_work_res`) calls it around
   a condition wait. Without cancellation the no-op is exact. Termux patches
   `jobs.c` for the same reason (`x11-packages/darktable/0002-jobs-pthread.patch`).
2. `-Wno-error=deprecated-declarations`. darktable builds with `-Werror`
   (`src/CMakeLists.txt`, `if(NOT SOURCE_PACKAGE)`), and bionic marks
   `strtok()` deprecated; `common/utility.c`, `gui/accelerators.c` and
   `gui/presets.c` use it. The warning stays visible in the build log.

Termux's other darktable patch (`0001-cmake-libm.patch`, LibRaw's math
library lookup) was not needed with the NDK's CMake toolchain file.

## Answer to the brief's question

Yes. Our own recipes build darktable `04477d3` and all 30 libraries it needs
for `arm64-v8a` and `x86_64` in GitHub Actions, with OpenMP on and GTK3 with
only the broadway backend, without changing darktable's source. The x86_64
engine runs `darktable-cli` inside an Android 13 (API 33) x86_64 emulator on 7
darktable-tests images/XMPs and matches a Linux build of the same commit
closely (PSNR 67 to 122 dB, see "Emulator results"). OpenMP gives a 2.0x to
3.1x speed-up on 4 emulator cores, and 1-thread and 4-thread renders are
identical. The arm64-v8a engine is **built and checked statically only**
(ELF alignment, all `NEEDED` libraries present); it has not been executed
anywhere yet. Running it on the phone is the next step (gate G0).

## What was built

### Toolchain
- Android NDK r30 (`30.0.16248370`), the 2026 LTS release; SHA-1 checked
  (`deps/config.sh`). Minimum API level 29. `libc++_shared`.
- Every shared object is linked with `-Wl,-z,max-page-size=16384`; the build
  scripts check every `PT_LOAD` of every `.so` and executable: 0 misaligned
  files in the sysroots and in both engine trees.
- Host tools: Ubuntu 24.04 packages, meson 1.12.1 (pip, pinned), Rust 1.100.0
  plus cargo-c 0.10.26 (pinned prebuilt, SHA-256 checked) for librsvg. Rust
  and cargo-c are tools the brief did not name; current librsvg is written in
  Rust and darktable requires librsvg.

### Libraries (deps/)
One recipe per library in `deps/recipes/`, built in the order of
`deps/packages.list` by `deps/build-sysroot.sh <abi>`. Each recipe pins a
version and a SHA-256 of the release archive (or a git commit). Most
checksums are the ones the Termux recipes use for the same archives; ATK's is
the one conan-center-index lists; the NDK's is Google's SHA-1; cargo-c's was
computed here. Sizes are stripped shared objects as installed (run
44407305110, the run that built both sysroots from scratch); build seconds are
wall time on 4 cores.

| library | version | licence | source host | stripped .so arm64-v8a (KB) | stripped .so x86_64 (KB) | build s arm64 / x86_64 |
|---|---|---|---|---|---|---|
| ndk-zlib | system | Zlib | NDK | 0 | 0 | 0 / 0 |
| proxy-libintl | 0.5 | LGPL-2.1-or-later | github.com | 6 | 5 | 1 / 2 |
| libffi | 3.8.1 | MIT | github.com | 34 | 37 | 4 / 5 |
| pcre2 | 10.49 | BSD-3-Clause | github.com | 395 | 435 | 4 / 6 |
| glib | 2.90.1 | LGPL-2.1-or-later | download.gnome.org | 3,926 | 3,805 | 24 / 38 |
| expat | 2.10.0 | MIT | github.com | 171 | 186 | 2 / 3 |
| libpng | 1.6.59 | libpng-2.0 | github.com | 232 | 247 | 1 / 2 |
| freetype | 2.15.0 | FTL | downloads.sourceforge.net | 695 | 743 | 5 / 6 |
| fontconfig | 2.18.4 | HPND | gitlab.freedesktop.org | 454 | 453 | 20 / 25 |
| pixman | 0.46.4 | MIT | cairographics.org | 648 | 763 | 5 / 7 |
| cairo | 1.18.6 | LGPL-2.1-only OR MPL-1.1 | gitlab.freedesktop.org | 1,138 | 1,203 | 18 / 21 |
| fribidi | 1.0.18 | LGPL-2.1-or-later | github.com | 112 | 116 | 3 / 4 |
| harfbuzz | 15.1.0 | MIT | github.com | 1,389 | 1,413 | 32 / 48 |
| pango | 1.58.2 | LGPL-2.1-or-later | download.gnome.org | 580 | 580 | 4 / 7 |
| libjpeg-turbo | 3.2.1 | IJG AND BSD-3-Clause AND Zlib | github.com | 611 | 846 | 4 / 5 |
| libtiff | 4.7.2 | libtiff | download.osgeo.org | 558 | 586 | 4 / 7 |
| gdk-pixbuf | 2.44.8 | LGPL-2.1-or-later | download.gnome.org | 161 | 162 | 3 / 5 |
| libepoxy | 1.5.10 | MIT | github.com | 1,074 | 955 | 4 / 5 |
| atk | 2.38.0 | LGPL-2.0-or-later | download.gnome.org | 163 | 156 | 2 / 3 |
| gtk3 | 3.24.52 | LGPL-2.0-or-later | gitlab.gnome.org | 7,816 | 7,597 | 45 / 78 |
| libxml2 | 2.15.4 | MIT | download.gnome.org | 1,192 | 1,200 | 5 / 7 |
| json-glib | 1.10.8 | LGPL-2.1-or-later | download.gnome.org | 157 | 151 | 2 / 3 |
| sqlite | 3.54.1 | blessing | www.sqlite.org | 1,423 | 1,444 | 22 / 28 |
| lcms2 | 2.20 | MIT | github.com | 382 | 401 | 3 / 4 |
| lensfun | 0.3.4 | LGPL-3.0-or-later | github.com | 142 | 148 | 2 / 2 |
| potrace | 1.16 | GPL-2.0-or-later | distfiles.macports.org | 20 | 28 | 2 / 4 |
| pugixml | 1.16 | MIT | github.com | 242 | 242 | 3 / 4 |
| exiv2 | 0.28.9 | GPL-2.0-or-later | github.com | 2,497 | 2,532 | 26 / 39 |
| curl | 8.23.0 | curl | github.com | 665 | 672 | 15 / 23 |
| libarchive | 3.8.11 | BSD-2-Clause | github.com | 767 | 787 | 19 / 29 |
| librsvg | 2.63.4 | LGPL-2.1-or-later | download.gnome.org | 4,648 | 5,106 | 64 / 90 |
| **total** | | | | **31.5 MB** | **32.2 MB** | |


Release files: `deps-0a9f7c84ba9d` (`sysroot-arm64-v8a.tar.zst` 16.6 MB,
`sysroot-x86_64.tar.zst` 17.2 MB, with headers and pkg-config files). The tag is
the first 12 hex digits of the git tree of `deps/`; the workflow skips an ABI
whose file already exists.

Notes on the set:
- **zlib** is Android's own `libz.so`; the recipe only writes a `zlib.pc`.
- **libintl**: bionic has no gettext. `proxy-libintl` (what GLib itself falls
  back to) provides `libintl.so`; messages stay untranslated. The engine has no
  user-visible text; translations for the app come from the control schema (S5).
- **iconv** is bionic's (API 28+). No GNU libiconv.
- **curl** is built **without TLS** to keep the set small. darktable requires
  libcurl at build time but only uses it for downloads: spektrafilm data from
  GitHub (`src/common/spektra_fetch.c`), AI models (off), location search
  (off). Consequence: the app must ship the spektrafilm data pack (better for
  offline use anyway) or curl needs OpenSSL later.
- **GTK3 3.24.52 with only the broadway backend (S6): builds and links.** No
  X11 or Wayland libraries are in the set. GTK (libgtk-3 + libgdk-3) is 8.0 MB
  stripped on arm64. The X11 fallback was not needed, so its size cost was
  not measured. Two patches, from Termux (below). GTK also pulls in ATK,
  libepoxy (built without GLX, never used), gdk-pixbuf (PNG/JPEG loaders built
  in), pango, cairo (image surfaces only), harfbuzz, fribidi, fontconfig,
  freetype, pixman.
- **librsvg** is the largest single library after GTK (4.6 MB); it is only used
  for logos and the watermark module. Same for fontconfig/freetype/pango text.
- meson builds for Android produce unversioned sonames (`libglib-2.0.so`); the
  only versioned one is `libsqlite3.so.0` (autosetup). This matters for S4
  (APK `lib/` naming) and is noted there.

Switched off in darktable (`tools/build-engine-android.sh`), as the brief asks
or because the library is costly: Lua, AI, G'MIC, camera support (libgphoto2),
map (osm-gps-map), print (CUPS), colord, libsecret, kwallet, PortMidi, SDL2,
Wayland presenter, OpenCL (later, PLAN 3.1), GraphicsMagick, ImageMagick,
OpenJPEG, JPEG XL, WebP, AVIF, HEIF, OpenEXR, ICU, OpenCV (HDR alignment),
the MCP server, cmstest, rs-identify, xmllint check. Still on: LibRaw
(in-tree), XCF export (in-tree), ISOBMFF via exiv2. Export formats left: JPEG,
PNG, TIFF, PFM, PPM, PDF, XCF, copy.

### Patches to dependencies
| library | patch | why |
|---|---|---|
| gtk3 | `deps/recipes/gtk3/01-broadway-server.patch`, `02-gdkbroadway-server.patch` | bionic has no `shm_open()`/`shm_unlink()`; the broadway backend uses its existing tmp-file fallback instead. Unchanged from termux-packages (commit 5f68afb), licence as GTK's (termux-packages LICENSE.md). The port never starts GTK, so this code does not run. |

Recipe-level adjustments that are not patches: GLib's installed `.pc` files
name the host's `glib-compile-resources` & co. instead of the target binaries
(otherwise later meson builds try to run ARM executables); lensfun's optional
Python helper package is not built; libarchive and others are configured
minimal (see each recipe).

### Engine (tools/build-engine-android.sh)
darktable `04477d3` cross-compiled with the NDK CMake toolchain against the
sysroot: `libdarktable.so`, 153 plugin libraries (IOPs, lib and view modules,
formats, storages), `darktable-cli`, `share/darktable`. The packed tree holds
only libraries in the `DT_NEEDED` closure of those files (8 sysroot libraries
such as libgailutil and libgirepository are left out), plus `libc++_shared.so`
and the NDK's `libomp.so`, the lensfun database, and no OpenCL kernels.

| | arm64-v8a | x86_64 |
|---|---|---|
| whole engine tree, stripped | 61.3 MB | 63.4 MB |
| `libdarktable.so` + 153 plugins | 11.8 MB | 13.2 MB |
| `darktable-cli` | 24 KB | 25 KB |
| dependency libraries (incl. libc++_shared, libomp) | 34.9 MB | 35.6 MB |
| `share/darktable` (without OpenCL kernels) | 10.4 MB | 10.4 MB |
| `share/lensfun` (lens database) | 4.2 MB | 4.2 MB |
| `engine-<abi>.tar.gz` | 21.2 MB | 22.3 MB |

Release `engine-04477d32-d2369345ff68` (run 44419865243) holds both archives and
their reports (`engine-<abi>-report.txt`: sizes, OpenMP check, ELF alignment
check, missing-`NEEDED` check; all checks clean for both ABIs).

`libdarktable.so` has `libomp.so` as `NEEDED`; CMake found OpenMP 5.1 with
`-fopenmp=libomp` (S2).

## Emulator results

Run 44419865243, job `emulator-test / emulator`: Android 13 (API 33) x86_64
`google_apis` emulator, 4 cores, 6 GB, 4 KB pages, KVM. `darktable-cli` from
`engine-x86_64.tar.gz`, unpacked in `/data/local/tmp`, renders 7
darktable-tests cases (commit `4404dec`, the one darktable `04477d3` pins as
its integration-test submodule) at 2048x2048 max, as float PFM, with the
options darktable-tests itself uses (`resourcelevel=reference`,
`host_memory_limit=8192`, lanczos3). The reference is darktable `04477d3`
built on the Linux runner (Ubuntu 24.04, gcc, same feature switches,
`tools/build-engine-linux.sh`) and rendered with the same script.
Differences are over all channels, values unclipped; PSNR uses values clipped
to [0, 1] with peak 1.0.

| test | image | max abs diff | mean abs diff | PSNR dB | pixels off by > 1/255 |
|---|---|---|---|---|---|
| 0001-exposure | mire1.cr2 (Bayer) | 2.42e-02 | 8.99e-08 | 95.3 | 0.001 % |
| 0035-multiple-modules | mire1.cr2 | 1.11e-02 | 6.53e-07 | 102.7 | 0.000 % |
| 0086-diffuse | mire1.cr2 | 5.49e-03 | 2.68e-07 | 109.3 | 0.000 % |
| 0096-lensfun | hlrecovery.arw | 9.74e-04 | 7.65e-08 | 121.6 | 0.000 % |
| 0146-lens-metadata-xtransIV-modversion-7 | xtransIV.raf (X-Trans IV) | 5.65e-03 | 1.93e-07 | 102.9 | 0.000 % |
| 0165-demosaic-markesteijn-vng | mire1-xtrans.raf (X-Trans) | 4.14e-03 | 4.27e-04 | 66.8 | 0.000 % |
| 0175-agx | mire1.cr2 | 6.96e-03 | 1.57e-07 | 110.0 | 0.000 % |

All 7 renders have the same size as the reference. The largest single
difference (0.024 in 0001) touches 0.001 % of the pixels; the X-Trans
Markesteijn case has the largest mean difference (4e-4, still below 1/255
everywhere). These are compiler and library-version differences (clang and
newer libraries on Android, gcc and Ubuntu's libraries on Linux), not
failures; darktable-tests itself accepts a delta E up to 2.3. The lensfun case
matches the Linux build, which has a system lens database, so the engine
found the lensfun database shipped in `share/lensfun`.

**OpenMP (S2).** Each test was rendered with `-t 1` and with all 4 cores.
1-thread and 4-thread outputs are bit-identical, on the emulator and on Linux.
Wall time per `darktable-cli` call (start-up, RAW decode, pipeline, export),
milliseconds, x86_64 on cloud runners, **not phone numbers**:

| test | emulator 1 thread | emulator 4 threads | speed-up | Linux 1 thread | Linux 4 threads |
|---|---|---|---|---|---|
| 0001-exposure | 3290 | 1512 | 2.2x | 1801 | 1198 |
| 0035-multiple-modules | 28153 | 9174 | 3.1x | 27626 | 9988 |
| 0086-diffuse | 14747 | 6099 | 2.4x | 8529 | 3813 |
| 0096-lensfun | 12838 | 4635 | 2.8x | 9576 | 4297 |
| 0146-lens-metadata-xtransIV-modversion-7 | 18705 | 7731 | 2.4x | 16422 | 6822 |
| 0165-demosaic-markesteijn-vng | 16066 | 7022 | 2.3x | 17323 | 7374 |
| 0175-agx | 5128 | 2561 | 2.0x | 4588 | 2197 |
| **sum** | **98927** | **38734** | **2.6x** | **85865** | **35689** |

The previous run (44414667453, same engine except the test scripts) gave the
same differences to all printed digits; single timings differed by up to 25 % between the two runs (shared cloud runners).
No crashes in logcat (`crashes.txt` empty). The emulator and Linux runs both
print one fontconfig warning (no `fonts.conf` shipped on Android).

## Build times (GitHub standard runner, 4 cores)

| step | arm64-v8a | x86_64 | source |
|---|---|---|---|
| all 31 recipes from scratch (`build-sysroot.sh`) | 351 s | 512 s | run 44407305110 |
| whole `deps` job incl. NDK, Rust, packing, upload | 557 s | 586 s | run 44407305110 |
| `deps` job when the release file exists | 14 s | 18 s | run 44419865243 |
| darktable configure + build | 6 s + 130 s | 6 s + 124 s | run 44414667453 |
| whole `engine` job | 524-535 s | 219-519 s | runs 44414667453, 44419865243 |
| Linux reference build (gcc, incl. apt) | | 1124 s | run 44407305110 |
| emulator job (boot, 2 x 7 renders, compare) | | 291-317 s | runs 44414667453, 44419865243 |

A pipeline run with unchanged `deps/` takes about 40 minutes, most of it the
Linux reference job (about 12 minutes of it is installing Ubuntu's -dev
packages even when the reference build comes from the cache).

## What remains uncertain for the real phone (Snapdragon 865, Android 13)

- **The arm64 binaries have not run.** The emulator test uses x86_64. On
  arm64 darktable takes other code paths (NEON intrinsics in some modules,
  no `target_clones`), and rawspeed/LibRaw are compiled for aarch64 for the
  first time in this project. G0 must repeat the render comparison on the
  phone (the same `tools/engine-test/run-renders.sh` works over `adb shell`).
- **Speed.** Emulator numbers are x86_64 on a cloud runner and say nothing
  about the phone. The Snapdragon 865 has 1+3+4 heterogeneous cores;
  darktable's OpenMP loops use static scheduling, so the little cores may set
  the pace. Measure 1, 4 and 8 threads on the phone.
- **App context.** Here `darktable-cli` runs as the `shell` user from
  `/data/local/tmp`. An app process loads libraries from its own storage under
  stricter SELinux rules (S4), and Android may kill it under memory pressure;
  `host_memory_limit` / resource level need tuning there.
- **16 KB pages.** Everything is linked for 16 KB pages, but the emulator ran
  with 4 KB pages; a 16 KB page device or emulator image was not tried. The
  target phone uses 4 KB pages.
- **Fonts.** fontconfig prints "Cannot load default config file" because no
  `fonts.conf` is shipped; harmless for rendering, but the watermark module
  needs a minimal config and a font.
- **Library versions differ from the desktop.** The Linux reference used
  Ubuntu 24.04's packaged libraries (older exiv2, lcms2, GTK) and gcc; the
  Android build uses newer libraries and clang. The differences in the table
  include both effects.
- **No TLS in curl** (see above).
- **Translations** are absent from the engine (proxy-libintl).

## Verdict

**Go, with conditions.**

Reasons for go: the whole stack builds reproducibly from pinned sources in
about 10 minutes per ABI, darktable needs no source patch (two build flags,
documented above), GTK3 builds with only the broadway backend, OpenMP works
and gives a real speed-up with identical results, and the x86_64 engine
renders RAW files on Android (Bayer and X-Trans, including lens correction,
AgX and diffuse or sharpen) essentially identically to a Linux build of the
same commit.

Conditions before Phase 1 relies on it:
1. Run the arm64 engine on the target phone (G0): the same 7 renders compared
   with the Linux reference, and 1/4/8-thread timings. Nothing in this
   experiment executed arm64 code.
2. S4: load the engine from app storage inside an APK (library naming:
   `libsqlite3.so.0` is the only versioned soname).
3. Decide on curl: ship the spektrafilm data with the app (recommended) or
   add TLS (OpenSSL) to curl.
4. Ship a minimal `fonts.conf` and one font if the watermark module is to work.

## Running log

### 2026-11-18
- Branch `s1-android-build` created from `main`.
- GitHub API from the build workspace without a token of our own: `GET /repos/nannolo45/darktable-android/actions/runs`,
  `.../runs/<id>/jobs` and even `.../actions/jobs/<id>/logs` answer 200. The workspace's proxy evidently
  authenticates these requests itself (rate limit shown as 15000/h, other repositories are refused with
  "GitHub access to this repository is not enabled for this session"), so this is not anonymous access;
  an anonymous client gets job lists for a public repository but not job logs. Logs of a job that is still
  running are not available. The `ci-logs` branch is written by every job anyway, as the brief asks.
- NDK: r30 (30.0.16248370) is the 2026 LTS release according to the NDK wiki; pinned in `deps/config.sh`.
- First version of `deps/` (31 recipes), engine and emulator workflows pushed. Local smoke test on the
  workspace (x86_64 host, 2 cores, arm64-v8a target): ndk-zlib, proxy-libintl, libffi, pcre2, expat,
  libjpeg-turbo, curl, libarchive build; other sources cannot be downloaded from the workspace
  (GitHub archive URLs, gnome.org, sqlite.org and others are blocked here), so the CI run is the test.

- Run 44403959470: all 31 recipes reported "ok" in 6 minutes, which was wrong: `build-sysroot.sh` ran each
  recipe in a subshell inside an `if`, where bash ignores `set -e`. Fixed; a recipe that installs nothing
  now fails too.
- Run 44405594139: everything except gtk3 builds for both ABIs (glib tool paths fixed, sqlite targets,
  lensfun Python helper, cargo-c for librsvg). gtk3 broadway needs `shm_open` (absent in bionic).
- Run 44407305110: gtk3 builds with the two Termux broadway patches; **both sysroots complete** (31/31),
  release `deps-0a9f7c84ba9d`. darktable configures (OpenMP found) and compiles up to three files that use
  `strtok()`, which bionic marks deprecated while darktable builds with `-Werror`.

- Run 44413677255: both engines build (strtok fix); publishing failed because `gh` ran outside the
  checkout. Fixed with `-R`.
- Run 44414667453: first complete pipeline. Both engines published, emulator renders all 7 tests.
- Run 44419865243: same with an extra emulator 1-thread vs 4-thread comparison; numbers in the
  sections above come from this run unless stated.

### Stale release files (for the reviewer)
- `deps-983e02852ccd` holds an arm64 sysroot from run 44403959470, published while failing recipes
  were still reported as ok (no GTK, no librsvg). It is wrong and should be deleted; the workspace has
  no token to do it.
- `engine-04477d32-3d802276c4ef` is superseded by `engine-04477d32-d2369345ff68` (only test scripts
  changed in between).

### Next steps
- Done for S1. Follow-ups are the conditions in the verdict.

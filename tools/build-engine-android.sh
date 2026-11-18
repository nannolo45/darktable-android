#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Cross-compile darktable (library, module plugins, darktable-cli, data) for
# one Android ABI against the sysroot from deps/build-sysroot.sh, then pack
# a stripped, self-contained engine tree (brief S1, workflow "engine").
#
#   tools/build-engine-android.sh <abi> <darktable source> <output dir>
#
# Output: <output dir>/engine-<abi>.tar.gz and <output dir>/engine-<abi>-report.txt
# The darktable source is not modified. Android-only build settings are all
# here: CMake options, and one force-included compatibility header
# (tools/android-compat/dt_android_compat.h).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
. "$REPO/deps/lib/common.sh"

ABI="${1:?usage: build-engine-android.sh <abi> <darktable src> <out dir>}"
DT_SRC="$(cd "${2:?}" && pwd)"
OUT="${3:?}"
dta_setup_env "$ABI"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
BUILD="$DTA_WORK/engine-build-$ABI"
STAGE="$DTA_WORK/engine-stage-$ABI"
ENGINE_PREFIX=/engine          # install prefix inside the stage; darktable finds
                               # its data relative to the executable (whereami)
COMPAT="$HERE/android-compat/dt_android_compat.h"

[ -f "$DTA_PREFIX/lib/pkgconfig/gtk+-3.0.pc" ] || dta_die "sysroot $DTA_PREFIX is missing gtk+-3.0"

mapfile -t CM < <(dta_cmake_args)
OPTS=(
  -DCMAKE_INSTALL_PREFIX=$ENGINE_PREFIX
  -DCMAKE_C_FLAGS="$CPPFLAGS $CFLAGS -include $COMPAT"
  -DCMAKE_CXX_FLAGS="$CPPFLAGS $CXXFLAGS -include $COMPAT"
  -DBINARY_PACKAGE_BUILD=ON          # no -march=native
  -DUSE_OPENMP=ON                    # brief S2
  -DUSE_OPENCL=OFF                   # PLAN 3.1: later, opt-in
  -DUSE_LUA=OFF -DUSE_AI=OFF -DUSE_GMIC=OFF -DUSE_CAMERA_SUPPORT=OFF -DUSE_MAP=OFF
  -DBUILD_PRINT=OFF -DUSE_COLORD=OFF -DUSE_LIBSECRET=OFF -DUSE_KWALLET=OFF
  -DUSE_PORTMIDI=OFF -DUSE_SDL2=OFF -DUSE_WAYLAND=OFF -DUSE_UNITY=OFF
  -DUSE_GRAPHICSMAGICK=OFF -DUSE_IMAGEMAGICK=OFF -DUSE_OPENJPEG=OFF -DUSE_JXL=OFF
  -DUSE_WEBP=OFF -DUSE_AVIF=OFF -DUSE_HEIF=OFF -DUSE_OPENEXR=OFF -DUSE_ICU=OFF
  -DUSE_OPENCV=OFF -DUSE_MCP=OFF -DBUILD_CMSTEST=OFF -DBUILD_RS_IDENTIFY=OFF
  -DUSE_XMLLINT=OFF -DBUILD_TESTING=OFF -DBUILD_NOISE_TOOLS=OFF -DBUILD_CURVE_TOOLS=OFF
  -DDONT_USE_INTERNAL_LIBRAW=OFF -DUSE_LIBRAW=ON
  -DTESTBUILD_OPENCL_PROGRAMS=OFF
  -DRAWSPEED_ENABLE_LTO=OFF -DRAWSPEED_ENABLE_WERROR=OFF
)

t0=$(date +%s)
cmake -S "$DT_SRC" -B "$BUILD" -G Ninja "${CM[@]}" "${OPTS[@]}" 2>&1 | tee "$OUT/engine-$ABI-cmake.log"
t1=$(date +%s)
rc=0
cmake --build "$BUILD" -j "$DTA_JOBS" -- -k 0 > "$OUT/engine-$ABI-build.log" 2>&1 || rc=$?
grep -E "error:|FAILED:|undefined" "$OUT/engine-$ABI-build.log" | head -60 || true
tail -n 5 "$OUT/engine-$ABI-build.log"
[ $rc = 0 ] || dta_die "darktable build failed (see engine-$ABI-build.log)"
t2=$(date +%s)
rm -rf "$STAGE"
DESTDIR="$STAGE" cmake --install "$BUILD" > "$OUT/engine-$ABI-install.log" 2>&1

# ---- assemble a self-contained engine tree ---------------------------------
E="$DTA_WORK/engine-$ABI"
rm -rf "$E"; mkdir -p "$E/bin" "$E/lib" "$E/share"
cp -a "$STAGE$ENGINE_PREFIX/bin/darktable-cli" "$E/bin/"
cp -a "$STAGE$ENGINE_PREFIX/lib/darktable" "$E/lib/"
cp -a "$STAGE$ENGINE_PREFIX/share/darktable" "$E/share/"
# OpenCL kernels are unused (OpenCL off); translations are not needed by the engine
rm -rf "$E/share/darktable/kernels"
# runtime libraries from the sysroot (shared objects only)
( cd "$DTA_PREFIX/lib" && find . -maxdepth 1 \( -name '*.so' -o -name '*.so.*' \) -print0 | xargs -0 cp -a -t "$E/lib/" )
cp -a "$DTA_PREFIX/share/lensfun" "$E/share/" 2>/dev/null || dta_log "warning: no lensfun database in sysroot"
# C++ runtime and OpenMP runtime from the NDK
cp "$DTA_NDK_SYSROOT/usr/lib/$DTA_TRIPLE/libc++_shared.so" "$E/lib/"
OMP=$(find "$DTA_TOOLCHAIN/lib/clang" -path "*/lib/linux/$DTA_LLVM_ARCH/libomp.so" | head -1)
[ -n "$OMP" ] || dta_die "libomp.so not found in NDK"
cp "$OMP" "$E/lib/"
# strip
find "$E" -type f \( -name '*.so' -o -name '*.so.*' -o -path '*/bin/*' \) -exec "$STRIP" --strip-unneeded {} \; 2>/dev/null

# ---- checks and report -----------------------------------------------------
R="$OUT/engine-$ABI-report.txt"
{
  echo "engine for $ABI, darktable $(git -C "$DT_SRC" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "NDK $DTA_NDK_VERSION, API $DTA_API"
  echo "configure_seconds $((t1 - t0))"
  echo "build_seconds $((t2 - t1))"
  echo "jobs $DTA_JOBS"
  echo
  echo "== sizes (stripped, bytes)"
  printf 'total\t%s\n' "$(du -sb "$E" | cut -f1)"
  printf 'darktable_lib_and_plugins\t%s\n' "$(du -sb "$E/lib/darktable" | cut -f1)"
  printf 'darktable_plugins_count\t%s\n' "$(find "$E/lib/darktable" -name '*.so' | wc -l)"
  printf 'darktable-cli\t%s\n' "$(stat -c %s "$E/bin/darktable-cli")"
  printf 'dependency_libs\t%s\n' "$(find "$E/lib" -maxdepth 1 -type f -printf '%s\n' | awk '{s+=$1} END{print s}')"
  printf 'share_darktable\t%s\n' "$(du -sb "$E/share/darktable" | cut -f1)"
  printf 'share_lensfun\t%s\n' "$(du -sb "$E/share/lensfun" 2>/dev/null | cut -f1)"
  echo
  echo "== OpenMP"
  if "$READELF" -dW "$E/lib/darktable/libdarktable.so" | grep -q 'libomp.so'; then echo "libdarktable.so NEEDED libomp.so: yes"; else echo "libdarktable.so NEEDED libomp.so: NO"; fi
  grep -E "OpenMP|openmp" "$OUT/engine-$ABI-cmake.log" | head -5 || true
  echo
  echo "== ELF load alignment (every PT_LOAD must be >= 0x4000)"
  bad=0
  while IFS= read -r f; do
    for a in $("$READELF" -lW "$f" | awk '$1=="LOAD"{print $NF}'); do
      if [ $((a)) -lt 16384 ]; then echo "misaligned: ${f#"$E"/} ($a)"; bad=$((bad+1)); break; fi
    done
  done < <(find "$E" -type f \( -name '*.so' -o -name '*.so.*' -o -path '*/bin/*' \))
  echo "misaligned_files $bad"
  echo
  echo "== unresolved NEEDED entries"
  missing=0
  while IFS= read -r f; do
    for n in $("$READELF" -dW "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do
      case "$n" in libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libz.so|libEGL.so|libGLESv2.so|libGLESv3.so|libjnigraphics.so) continue ;; esac
      if [ ! -e "$E/lib/$n" ] && [ ! -e "$E/lib/darktable/$n" ]; then echo "missing $n (needed by ${f#"$E"/})"; missing=$((missing+1)); fi
    done
  done < <(find "$E" -type f \( -name '*.so' -o -name '*.so.*' -o -path '*/bin/*' \))
  echo "missing_needed $missing"
} > "$R"
cat "$R"

tar -czf "$OUT/engine-$ABI.tar.gz" -C "$DTA_WORK" "engine-$ABI"
ls -l "$OUT/engine-$ABI.tar.gz"

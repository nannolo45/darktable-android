#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Linux x86_64 reference build of the same darktable commit, with the same
# feature switches as the Android build (tools/build-engine-android.sh), for
# the render comparison in the emulator test. Ubuntu 24.04 system libraries.
#
#   tools/build-engine-linux.sh <darktable source> <install prefix>
#   tools/build-engine-linux.sh --deps-only     (install the packages only)
set -euo pipefail
SUDO=""; [ "$(id -u)" = 0 ] || SUDO=sudo
if [ "${NO_APT:-0}" != 1 ]; then
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq --no-install-recommends \
    build-essential cmake ninja-build pkgconf git perl xsltproc intltool gettext \
    libgtk-3-dev libxml2-dev liblensfun-dev libsqlite3-dev librsvg2-dev libpng-dev \
    libjpeg-dev libtiff-dev liblcms2-dev libjson-glib-dev libcurl4-openssl-dev \
    libarchive-dev libexiv2-dev libpugixml-dev libpotrace-dev zlib1g-dev >/dev/null
fi
[ "${1:-}" = --deps-only ] && exit 0
DT_SRC="$(cd "${1:?usage: build-engine-linux.sh <darktable src> <prefix>}" && pwd)"
PREFIX="${2:?}"
BUILD="${DTA_WORK:-$PWD/_dta-work}/engine-build-linux"
OPTS=(
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX"
  -DBINARY_PACKAGE_BUILD=ON -DUSE_OPENMP=ON -DUSE_OPENCL=OFF
  -DUSE_LUA=OFF -DUSE_AI=OFF -DUSE_GMIC=OFF -DUSE_CAMERA_SUPPORT=OFF -DUSE_MAP=OFF
  -DBUILD_PRINT=OFF -DUSE_COLORD=OFF -DUSE_LIBSECRET=OFF -DUSE_KWALLET=OFF
  -DUSE_PORTMIDI=OFF -DUSE_SDL2=OFF -DUSE_WAYLAND=OFF -DUSE_UNITY=OFF
  -DUSE_GRAPHICSMAGICK=OFF -DUSE_IMAGEMAGICK=OFF -DUSE_OPENJPEG=OFF -DUSE_JXL=OFF
  -DUSE_WEBP=OFF -DUSE_AVIF=OFF -DUSE_HEIF=OFF -DUSE_OPENEXR=OFF -DUSE_ICU=OFF
  -DUSE_OPENCV=OFF -DUSE_MCP=OFF -DBUILD_CMSTEST=OFF -DBUILD_RS_IDENTIFY=OFF
  -DUSE_XMLLINT=OFF -DBUILD_TESTING=OFF -DUSE_LIBRAW=ON -DDONT_USE_INTERNAL_LIBRAW=OFF
  -DTESTBUILD_OPENCL_PROGRAMS=OFF
)
cmake -S "$DT_SRC" -B "$BUILD" -G Ninja "${OPTS[@]}"
cmake --build "$BUILD" -j "$(nproc)"
cmake --install "$BUILD" > /dev/null
"$PREFIX/bin/darktable-cli" --version | head -3

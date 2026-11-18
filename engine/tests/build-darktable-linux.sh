#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build upstream darktable for the Linux engine tests (brief S3, task 2).
#
#   engine/tests/build-darktable-linux.sh <build dir> <install prefix> [<darktable source>]
#
# The source defaults to the repository's upstream/darktable submodule. Only
# darktable's own git submodules that the build needs are fetched, shallowly;
# no file of darktable is changed. Build and install directories must be
# outside the repository.
#
# Ubuntu 24.04 packages used (apt-get install -y ...):
#   build-essential cmake ninja-build pkg-config intltool gettext xsltproc
#   libxml2-utils llvm clang libgtk-3-dev libglib2.0-dev libxml2-dev
#   libcurl4-openssl-dev libexiv2-dev liblensfun-dev liblcms2-dev
#   libjson-glib-dev libsqlite3-dev libpugixml-dev librsvg2-dev libpng-dev
#   libjpeg-dev libtiff-dev libopenexr-dev libwebp-dev libheif-dev libavif-dev
#   libjxl-dev libopenjp2-7-dev libgraphicsmagick1-dev libcairo2-dev
#   libpango1.0-dev zlib1g-dev libimath-dev libpotrace-dev libarchive-dev
#   libicu-dev iso-codes libomp-dev
# and for the tests: python3-numpy python3-pil
set -eu

BUILD=${1:?build dir}
PREFIX=${2:?install prefix}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${3:-$HERE/../../upstream/darktable}
SRC=$(cd "$SRC" && pwd)

# darktable's submodules needed with the options below:
#   rawspeed  - raw decoder, always built
#   LibRaw    - USE_LIBRAW (default on; CR3 and others)
#   OpenCL    - headers only: common/iop_profile.h includes <CL/cl.h>
#               unconditionally, even with USE_OPENCL=OFF
#   libxcf    - USE_XCF (default on)
#   whereami  - always (locates the install at run time)
# not needed: lua-scripts (USE_LUA=OFF), tests/integration (fetched separately)
for m in src/external/rawspeed src/external/LibRaw src/external/OpenCL \
         src/external/libxcf src/external/whereami; do
  git -C "$SRC" submodule update --init --depth 1 "$m"
done

mkdir -p "$BUILD" "$PREFIX"
# the exact configure command of the S3 experiment
cmake -G Ninja -S "$SRC" -B "$BUILD" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DUSE_LUA=OFF -DUSE_AI=OFF -DUSE_GMIC=OFF -DUSE_CAMERA_SUPPORT=OFF \
  -DUSE_MAP=OFF -DBUILD_PRINT=OFF -DUSE_COLORD=OFF -DUSE_LIBSECRET=OFF \
  -DUSE_KWALLET=OFF -DUSE_PORTMIDI=OFF -DUSE_SDL2=OFF \
  -DUSE_OPENMP=ON -DUSE_OPENCL=OFF
ninja -C "$BUILD" -j2 install

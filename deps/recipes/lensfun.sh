# SPDX-License-Identifier: GPL-3.0-or-later
# Installs the lens database (share/lensfun/version_1) that darktable's lens
# module reads.
NAME=lensfun
VERSION=0.3.4
LICENSE=LGPL-3.0-or-later
URL=https://github.com/lensfun/lensfun/archive/refs/tags/v$VERSION.tar.gz
FILE=lensfun-$VERSION.tar.gz
SHA256=dafb39c08ef24a0e2abd00d05d7341b1bf1f0c38bfcd5a4c69cf5f0ecb6db112
DEPENDS="glib libpng"
build() {
  dta_cmake -DBUILD_STATIC=OFF -DBUILD_TESTS=OFF -DBUILD_LENSTOOL=OFF -DBUILD_DOC=OFF \
    -DINSTALL_PYTHON_MODULE=OFF -DINSTALL_HELPER_SCRIPTS=OFF -DBUILD_FOR_SSE=OFF -DBUILD_FOR_SSE2=OFF
}

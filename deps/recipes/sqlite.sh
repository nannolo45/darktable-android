# SPDX-License-Identifier: GPL-3.0-or-later
# Canonical source tree; its autosetup configure needs tclsh on the build host.
NAME=sqlite
VERSION=3.54.1
LICENSE=blessing
URL=https://www.sqlite.org/2026/sqlite-src-3540100.zip
SHA256=387e7f6cad4797b17848c07d453dc6606917bb356a6cfe5a3abafd38c240c71b
DEPENDS="ndk-zlib"
build() {
  cd "$SRC"
  ./configure --host="$DTA_TRIPLE" --prefix="$DTA_PREFIX" --libdir="$DTA_PREFIX/lib" \
    --disable-static --enable-shared --disable-tcl --disable-readline --disable-editline \
    --enable-fts5 --enable-rtree --soname=legacy
  make -j "$DTA_JOBS" lib
  make install-lib install-headers install-pc DESTDIR="$STAGE"
}

# SPDX-License-Identifier: GPL-3.0-or-later
# zlib comes with Android (libz.so in the NDK sysroot, API >= 28 gives 1.2.11
# or newer). Nothing is built; we only add a zlib.pc so pkg-config based
# builds (meson, autotools) find the system library.
NAME=ndk-zlib
VERSION=system
LICENSE=Zlib
DEPENDS=""
build() {
  mkdir -p "$STAGE$DTA_PREFIX/lib/pkgconfig"
  cat > "$STAGE$DTA_PREFIX/lib/pkgconfig/zlib.pc" <<PC
Name: zlib
Description: zlib from the Android NDK sysroot (libz.so is part of Android)
Version: 1.2.11
Libs: -lz
Cflags:
PC
}

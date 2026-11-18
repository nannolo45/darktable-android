# SPDX-License-Identifier: GPL-3.0-or-later
# Image surfaces only: no X11, no XCB.
NAME=cairo
VERSION=1.18.6
LICENSE="LGPL-2.1-only OR MPL-1.1"
URL=https://gitlab.freedesktop.org/cairo/cairo/-/archive/$VERSION/cairo-$VERSION.tar.bz2
SHA256=42b785e00ef97a687c9e32ad26dde55b4371215c0ee20b39cab63294a9fe8f0c
DEPENDS="ndk-zlib libpng freetype fontconfig pixman glib"
build() {
  dta_meson -Dxlib=disabled -Dxcb=disabled -Dquartz=disabled -Dtee=disabled \
    -Dpng=enabled -Dfreetype=enabled -Dfontconfig=enabled -Dglib=enabled -Dzlib=enabled \
    -Dtests=disabled -Dgtk2-utils=disabled -Dspectre=disabled -Dsymbol-lookup=disabled \
    -Dlzo=disabled -Dgtk_doc=false
}

# SPDX-License-Identifier: GPL-3.0-or-later
# Current librsvg is written in Rust; cargo builds it for the Android target
# (deps/host-setup.sh installs the pinned Rust toolchain and targets).
# darktable uses it for SVG logos and the watermark module.
NAME=librsvg
VERSION=2.63.4
LICENSE=LGPL-2.1-or-later
URL=https://download.gnome.org/sources/librsvg/2.63/librsvg-$VERSION.tar.xz
SHA256=31f07d9350dad3f8964fd613c31309ac160fdef13dfac59df6fd082de78d6e8f
DEPENDS="glib cairo pango gdk-pixbuf freetype harfbuzz fontconfig libxml2 libpng"
build() {
  dta_meson -Dtriplet="$DTA_RUST_TARGET" -Dintrospection=disabled -Dvala=disabled \
    -Dpixbuf=disabled -Dpixbuf-loader=disabled -Drsvg-convert=disabled -Ddocs=disabled \
    -Dtests=false -Davif=disabled
}

# SPDX-License-Identifier: GPL-3.0-or-later
NAME=pango
VERSION=1.58.2
LICENSE=LGPL-2.1-or-later
URL=https://download.gnome.org/sources/pango/1.58/pango-$VERSION.tar.xz
SHA256=342385b6ca3b7c73455d7c80a13b7dbe4489e00bc3bd4c5bd6ed4dce421e374a
DEPENDS="glib harfbuzz fribidi fontconfig freetype cairo"
build() {
  dta_meson -Dintrospection=disabled -Dfontconfig=enabled -Dfreetype=enabled -Dcairo=enabled \
    -Dxft=disabled -Dlibthai=disabled -Dsysprof=disabled -Ddocumentation=false \
    -Dman-pages=false -Dbuild-testsuite=false -Dbuild-examples=false
}

# SPDX-License-Identifier: GPL-3.0-or-later
NAME=json-glib
VERSION=1.10.8
LICENSE=LGPL-2.1-or-later
URL=https://download.gnome.org/sources/json-glib/1.10/json-glib-$VERSION.tar.xz
SHA256=55c5c141a564245b8f8fbe7698663c87a45a7333c2a2c56f06f811ab73b212dd
DEPENDS="glib"
build() {
  dta_meson -Dintrospection=disabled -Ddocumentation=disabled -Dgtk_doc=disabled -Dman=false \
    -Dtests=false -Dconformance=false -Dnls=disabled -Dinstalled_tests=false
}

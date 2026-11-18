# SPDX-License-Identifier: GPL-3.0-or-later
# Host tools (glib-compile-resources, glib-mkenums, ...) come from the build
# machine (deps/host-setup.sh); meson's gnome module looks them up natively.
NAME=glib
VERSION=2.90.1
LICENSE=LGPL-2.1-or-later
URL=https://download.gnome.org/sources/glib/2.90/glib-$VERSION.tar.xz
SHA256=93c941aa17d5eb1d53fe838365f29a8b4e539c222a256d974ec8f30fc413e396
DEPENDS="ndk-zlib proxy-libintl libffi pcre2"
build() {
  dta_meson -Dtests=false -Dintrospection=disabled -Dlibmount=disabled -Dselinux=disabled \
    -Dxattr=false -Dman-pages=disabled -Ddocumentation=false -Dsysprof=disabled \
    -Dnls=disabled -Ddtrace=disabled -Dsystemtap=disabled -Dlibelf=disabled
}

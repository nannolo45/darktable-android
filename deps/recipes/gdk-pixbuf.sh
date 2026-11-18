# SPDX-License-Identifier: GPL-3.0-or-later
# PNG and JPEG loaders are built into the library, so no loaders.cache is
# needed at run time.
NAME=gdk-pixbuf
VERSION=2.44.8
LICENSE=LGPL-2.1-or-later
URL=https://download.gnome.org/sources/gdk-pixbuf/2.44/gdk-pixbuf-$VERSION.tar.xz
SHA256=919f529512961a12e81cd4b4b466a48c3933469e7f9a310c6513cd4fb252ba3c
DEPENDS="glib libpng libjpeg-turbo"
build() {
  dta_meson -Dpng=enabled -Djpeg=enabled -Dtiff=disabled -Dgif=disabled -Dglycin=disabled \
    -Dandroid=disabled -Dothers=disabled -Dbuiltin_loaders=png,jpeg -Dintrospection=disabled \
    -Dman=false -Dtests=false -Dinstalled_tests=false -Dgio_sniffing=false \
    -Dthumbnailer=disabled -Ddocumentation=false
}

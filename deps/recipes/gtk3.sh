# SPDX-License-Identifier: GPL-3.0-or-later
# GTK3 with only the broadway backend (brief S1 / S6): no X11, no Wayland.
# darktable links GTK but the port never initialises it.
NAME=gtk3
VERSION=3.24.52
LICENSE=LGPL-2.0-or-later
URL=https://gitlab.gnome.org/GNOME/gtk/-/archive/$VERSION/gtk-$VERSION.tar.gz
SHA256=e62514019679f831fcb37f3d294a761c3a6c14f1d346745ad11d70c2be17146e
DEPENDS="glib cairo pango fribidi harfbuzz gdk-pixbuf libepoxy atk"
build() {
  dta_meson -Dx11_backend=false -Dwayland_backend=false -Dbroadway_backend=true \
    -Dprint_backends=file -Dcolord=no -Dintrospection=false -Ddemos=false -Dexamples=false \
    -Dtests=false -Dinstalled_tests=false -Dman=false -Dgtk_doc=false -Dxinerama=no \
    -Dcloudproviders=false -Dtracker3=false -Dprofiler=false
}

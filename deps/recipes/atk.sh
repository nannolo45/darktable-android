# SPDX-License-Identifier: GPL-3.0-or-later
# Standalone ATK (GTK 3.24 requires atk >= 2.35.1). SHA-256 as listed by
# conan-center-index (recipes/atk/all/conandata.yml).
NAME=atk
VERSION=2.38.0
LICENSE=LGPL-2.0-or-later
URL=https://download.gnome.org/sources/atk/2.38/atk-$VERSION.tar.xz
SHA256=ac4de2a4ef4bd5665052952fe169657e65e895c5057dffb3c2a810f6191a0c36
DEPENDS="glib"
build() { dta_meson -Dintrospection=false -Ddocs=false; }

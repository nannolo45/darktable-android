# SPDX-License-Identifier: GPL-3.0-or-later
NAME=fribidi
VERSION=1.0.18
LICENSE=LGPL-2.1-or-later
URL=https://github.com/fribidi/fribidi/releases/download/v$VERSION/fribidi-$VERSION.tar.xz
SHA256=1de5e8073d035388b71d0b2d452b1da1f8caeea8a7702997b401fe1b6250727f
DEPENDS=""
build() { dta_meson -Ddocs=false -Dbin=false -Dtests=false; }

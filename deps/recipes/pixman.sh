# SPDX-License-Identifier: GPL-3.0-or-later
NAME=pixman
VERSION=0.46.4
LICENSE=MIT
URL=https://cairographics.org/releases/pixman-$VERSION.tar.gz
SHA256=d09c44ebc3bd5bee7021c79f922fe8fb2fb57f7320f55e97ff9914d2346a591c
DEPENDS=""
build() { dta_meson -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled -Dopenmp=disabled; }

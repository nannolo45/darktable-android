# SPDX-License-Identifier: GPL-3.0-or-later
# GTK3 needs epoxy at link time; the engine never creates a GL context.
NAME=libepoxy
VERSION=1.5.10
LICENSE=MIT
URL=https://github.com/anholt/libepoxy/archive/refs/tags/$VERSION.tar.gz
FILE=libepoxy-$VERSION.tar.gz
SHA256=a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
DEPENDS=""
build() { dta_meson -Dglx=no -Degl=yes -Dx11=false -Dtests=false -Ddocs=false; }

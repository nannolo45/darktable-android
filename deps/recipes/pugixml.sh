# SPDX-License-Identifier: GPL-3.0-or-later
NAME=pugixml
VERSION=1.16
LICENSE=MIT
URL=https://github.com/zeux/pugixml/archive/refs/tags/v$VERSION.tar.gz
FILE=pugixml-$VERSION.tar.gz
SHA256=357bcab8877dc9943f355d3a72daba1b053238ba955f50fa81586afb65090219
DEPENDS=""
build() { dta_cmake -DPUGIXML_BUILD_TESTS=OFF; }

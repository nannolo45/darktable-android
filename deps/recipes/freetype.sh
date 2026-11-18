# SPDX-License-Identifier: GPL-3.0-or-later
NAME=freetype
VERSION=2.15.0
LICENSE=FTL
URL=https://downloads.sourceforge.net/freetype/freetype-$VERSION.tar.xz
SHA256=93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5
DEPENDS="ndk-zlib libpng"
build() {
  dta_meson -Dbrotli=disabled -Dbzip2=disabled -Dharfbuzz=disabled -Dpng=enabled \
    -Dzlib=system -Dtests=disabled
}

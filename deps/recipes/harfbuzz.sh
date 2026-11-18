# SPDX-License-Identifier: GPL-3.0-or-later
NAME=harfbuzz
VERSION=15.1.0
LICENSE=MIT
URL=https://github.com/harfbuzz/harfbuzz/archive/refs/tags/$VERSION.tar.gz
FILE=harfbuzz-$VERSION.tar.gz
SHA256=5c057929413b8664592fea67edea5c2c47b4ffb0bf5f4accb5164bc6acc69d94
DEPENDS="freetype glib"
build() {
  dta_meson -Dglib=enabled -Dgobject=disabled -Dfreetype=enabled -Dcairo=disabled \
    -Dicu=disabled -Dgraphite2=disabled -Dtests=disabled -Ddocs=disabled \
    -Dutilities=disabled -Dintrospection=disabled -Dbenchmark=disabled \
    -Dchafa=disabled -Dpng=disabled -Dzlib=disabled -Draster=disabled -Dvector=disabled \
    -Dgpu=disabled -Dsubset=disabled
}

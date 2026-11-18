# SPDX-License-Identifier: GPL-3.0-or-later
# Only needed at run time for text drawing (watermark module); the engine
# test does not draw text.
NAME=fontconfig
VERSION=2.18.4
LICENSE=HPND
URL=https://gitlab.freedesktop.org/fontconfig/fontconfig/-/archive/$VERSION/fontconfig-$VERSION.tar.gz
SHA256=cecf4125622ed674d5630b6deaeff43e3695afe197bdcc560363a1820d858112
DEPENDS="freetype expat"
build() {
  dta_meson -Ddoc=disabled -Dtests=disabled -Dtools=disabled -Dcache-build=disabled \
    -Dnls=disabled -Dxml-backend=expat
}

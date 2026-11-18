# SPDX-License-Identifier: GPL-3.0-or-later
NAME=libpng
VERSION=1.6.59
LICENSE=libpng-2.0
URL=https://github.com/pnggroup/libpng/archive/refs/tags/v$VERSION.tar.gz
FILE=libpng-$VERSION.tar.gz
SHA256=2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5
DEPENDS="ndk-zlib"
build() { dta_cmake -DPNG_SHARED=ON -DPNG_STATIC=OFF -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_FRAMEWORK=OFF; }

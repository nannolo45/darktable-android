# SPDX-License-Identifier: GPL-3.0-or-later
NAME=libjpeg-turbo
VERSION=3.2.1
LICENSE="IJG AND BSD-3-Clause AND Zlib"
URL=https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$VERSION/libjpeg-turbo-$VERSION.tar.gz
SHA256=c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f
DEPENDS=""
build() { dta_cmake -DENABLE_STATIC=OFF -DENABLE_SHARED=ON -DWITH_TURBOJPEG=OFF -DWITH_TESTS=OFF -DWITH_TOOLS=OFF; }

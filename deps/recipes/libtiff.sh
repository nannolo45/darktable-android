# SPDX-License-Identifier: GPL-3.0-or-later
NAME=libtiff
VERSION=4.7.2
LICENSE=libtiff
URL=https://download.osgeo.org/libtiff/tiff-$VERSION.tar.xz
SHA256=4996f0c4f93094719b1ca5c6279b20e588773ba8a247533e486416fb662ddb88
DEPENDS="ndk-zlib libjpeg-turbo"
build() {
  dta_cmake -Dtiff-tools=OFF -Dtiff-tests=OFF -Dtiff-contrib=OFF -Dtiff-docs=OFF \
    -Dzstd=OFF -Dlzma=OFF -Dwebp=OFF -Djbig=OFF -Dlerc=OFF -Dlibdeflate=OFF \
    -Djpeg=ON -Dzlib=ON -Dcxx=OFF -Dpixarlog=ON
}

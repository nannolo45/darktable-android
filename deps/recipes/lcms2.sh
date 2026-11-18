# SPDX-License-Identifier: GPL-3.0-or-later
NAME=lcms2
VERSION=2.20
LICENSE=MIT
URL=https://github.com/mm2/Little-CMS/archive/refs/tags/lcms$VERSION.tar.gz
FILE=lcms$VERSION.tar.gz
SHA256=49908a4492fe7fc886517d636474bd953ebe698d38ecd3ee5862dfa308dd43ae
DEPENDS=""
build() { dta_meson -Dutils=false -Djpeg=disabled -Dtiff=disabled -Dfastfloat=false -Dthreaded=false; }

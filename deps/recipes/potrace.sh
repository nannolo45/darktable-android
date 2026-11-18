# SPDX-License-Identifier: GPL-3.0-or-later
NAME=potrace
VERSION=1.16
LICENSE=GPL-2.0-or-later
URL=https://distfiles.macports.org/potrace/potrace-$VERSION.tar.gz
SHA256=be8248a17dedd6ccbaab2fcc45835bb0502d062e40fbded3bc56028ce5eb7acc
DEPENDS="ndk-zlib"
build() { dta_autotools --with-libpotrace --disable-zlib; }

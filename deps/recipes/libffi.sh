# SPDX-License-Identifier: GPL-3.0-or-later
NAME=libffi
VERSION=3.8.1
LICENSE=MIT
URL=https://github.com/libffi/libffi/releases/download/v$VERSION/libffi-$VERSION.tar.gz
SHA256=81e879c094cc5ec3186c48510c6385ac6b3e866192e8bc71a35245703757ee40
DEPENDS=""
build() { dta_autotools --disable-docs --disable-multi-os-directory --disable-exec-static-tramp; }

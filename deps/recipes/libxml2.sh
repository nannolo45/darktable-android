# SPDX-License-Identifier: GPL-3.0-or-later
NAME=libxml2
VERSION=2.15.4
LICENSE=MIT
URL=https://download.gnome.org/sources/libxml2/2.15/libxml2-$VERSION.tar.xz
SHA256=98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821
DEPENDS="ndk-zlib"
build() { dta_meson -Dpython=disabled -Ddocs=disabled -Dicu=disabled -Dreadline=disabled -Dhistory=disabled -Dhttp=disabled -Dzlib=enabled -Diconv=enabled; }

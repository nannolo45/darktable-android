# SPDX-License-Identifier: GPL-3.0-or-later
# Bionic has no gettext. proxy-libintl is the small libintl stand-in that
# GLib itself falls back to (glib/subprojects/proxy-libintl.wrap). Messages
# stay untranslated unless a real libintl is found at run time; the engine
# has no user-visible text, so this is enough.
NAME=proxy-libintl
VERSION=0.5
LICENSE=LGPL-2.1-or-later
GIT_URL=https://github.com/frida/proxy-libintl
GIT_COMMIT=33934de09af6a6627eb44e310a8079df009abdbb   # tag 0.5
DEPENDS=""
build() { dta_meson; }

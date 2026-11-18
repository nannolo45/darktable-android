# SPDX-License-Identifier: GPL-3.0-or-later
# darktable requires libcurl at build time. It is used for downloads only
# (spektrafilm data, AI models, location search). Built without TLS to keep
# the set small; see docs/experiments/S1-android-build.md.
NAME=curl
VERSION=8.23.0
LICENSE=curl
URL=https://github.com/curl/curl/releases/download/curl-8_23_0/curl-$VERSION.tar.xz
SHA256=8dadd7d2536a314f982ad05a651b713f426bf7d02cc2c76cffb9796f2218e6c5
DEPENDS="ndk-zlib"
build() {
  dta_autotools --without-ssl --disable-ldap --disable-ldaps --without-libpsl --without-brotli \
    --without-zstd --without-nghttp2 --without-libidn2 --without-libssh2 --disable-manual \
    --disable-docs --with-zlib --disable-ntlm --without-ca-bundle --without-ca-path
}

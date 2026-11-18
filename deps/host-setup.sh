#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Install the build-host tools for deps/build-sysroot.sh and the engine build
# on Ubuntu 24.04 (GitHub's ubuntu-24.04 runner or the build workspace).
#
#   deps/host-setup.sh [--no-apt] [--no-rust] <ndk install dir>
#
# Prints the shell lines to export afterwards (ANDROID_NDK_HOME, PATH).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/config.sh"

APT=1 RUST=1
while [ $# -gt 1 ]; do
  case "$1" in --no-apt) APT=0 ;; --no-rust) RUST=0 ;; *) break ;; esac; shift
done
NDK_PARENT="${1:?usage: host-setup.sh [--no-apt] [--no-rust] <ndk install dir>}"
SUDO=""; [ "$(id -u)" = 0 ] || SUDO=sudo

if [ $APT = 1 ]; then
  $SUDO apt-get update -qq
  # gperf: fontconfig; tcl: sqlite configure; libglib2.0-dev-bin: glib host
  # tools for GTK & co.; perl, xsltproc, intltool, gettext: darktable's
  # build-time generators; python3-venv: meson pin.
  $SUDO apt-get install -y -qq --no-install-recommends \
    ninja-build cmake pkgconf gperf tcl unzip zstd patch python3-venv python3-pip \
    libglib2.0-dev-bin perl xsltproc intltool gettext git curl ca-certificates file >/dev/null
fi

# meson, pinned
VENV="$NDK_PARENT/dta-venv"
if [ ! -x "$VENV/bin/meson" ] || [ "$("$VENV/bin/meson" --version)" != "$DTA_MESON_VERSION" ]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q "meson==$DTA_MESON_VERSION" packaging
fi

# NDK, pinned and checked
NDK_DIR="$NDK_PARENT/android-ndk-$DTA_NDK_RELEASE"
if [ ! -f "$NDK_DIR/source.properties" ]; then
  mkdir -p "$NDK_PARENT"
  zip="$NDK_PARENT/ndk-$DTA_NDK_RELEASE.zip"
  curl -fsSL --retry 4 -o "$zip" "$DTA_NDK_URL"
  echo "$DTA_NDK_SHA1  $zip" | sha1sum -c --quiet
  unzip -q "$zip" -d "$NDK_PARENT"
  rm -f "$zip"
fi

# Rust for librsvg, pinned
if [ $RUST = 1 ]; then
  if ! command -v rustup >/dev/null; then
    curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain none >/dev/null
    export PATH="$HOME/.cargo/bin:$PATH"
  fi
  rustup toolchain install "$DTA_RUST_TOOLCHAIN" --profile minimal \
    --target aarch64-linux-android --target x86_64-linux-android >/dev/null
  rustup default "$DTA_RUST_TOOLCHAIN" >/dev/null
fi

echo "export ANDROID_NDK_HOME=$NDK_DIR"
echo "export PATH=$VENV/bin:\$HOME/.cargo/bin:\$PATH"

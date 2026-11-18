# SPDX-License-Identifier: GPL-3.0-or-later
# Toolchain pins for the Android dependency build (brief S1).
# Changing anything in deps/ changes the sysroot hash and forces a rebuild.

# Android NDK: r30 is the 2026 LTS release (github.com/android/ndk/wiki).
# Google publishes SHA-1 only for NDK archives.
DTA_NDK_RELEASE=r30
DTA_NDK_VERSION=30.0.16248370
DTA_NDK_URL=https://dl.google.com/android/repository/android-ndk-r30-linux.zip
DTA_NDK_SHA1=5107f898313790e449e87eee2183d9a20602dee9

# Minimum Android API level (brief S1: 29 = Android 10).
DTA_API=29

# Fixed install prefix used while building, per ABI: <base>/sysroot-<abi>.
# pkg-config files are rewritten to be relative (${pcfiledir}) so the
# archive can also be unpacked elsewhere.
DTA_PREFIX_BASE=/opt/dta

# Host tools (installed by the workflow, see deps/host-setup.sh).
DTA_MESON_VERSION=1.12.1
DTA_RUST_TOOLCHAIN=1.100.0

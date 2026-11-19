#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Download the Gradle version pinned in app/gradle-version.properties into
# <dir>, check its SHA-256, and print the path of the gradle executable.
#   tools/app/setup-gradle.sh <dir>
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
P="$REPO/app/gradle-version.properties"
get() { sed -n "s/^$1=//p" "$P"; }
V=$(get version); URL=$(get url); SHA=$(get sha256)
DIR="${1:?usage: setup-gradle.sh <dir>}"
mkdir -p "$DIR"
if [ ! -x "$DIR/gradle-$V/bin/gradle" ]; then
  curl -fsSL --retry 4 -o "$DIR/gradle-$V-bin.zip" "$URL"
  echo "$SHA  $DIR/gradle-$V-bin.zip" | sha256sum -c --quiet
  unzip -q -o "$DIR/gradle-$V-bin.zip" -d "$DIR"
  rm -f "$DIR/gradle-$V-bin.zip"
fi
echo "$DIR/gradle-$V/bin/gradle"

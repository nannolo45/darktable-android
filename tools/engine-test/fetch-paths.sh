#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copy single files from darktable-tests at the pinned commit
# (darktable-tests.pin) into <dir>, keeping their paths, without cloning the
# whole repository. For the Linux bridge tests and the G0 emulator test.
#   tools/engine-test/fetch-paths.sh <dir> <path> [<path> ...]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/darktable-tests.pin"
DEST="${1:?usage: fetch-paths.sh <dir> <path>...}"; shift
G="$DEST/.git-dtt"
mkdir -p "$DEST"
if [ ! -d "$G" ]; then
  git init -q --bare "$G"
  git -C "$G" fetch -q --depth 1 --filter=blob:none "$DTT_REPO" "$DTT_COMMIT"
fi
for p in "$@"; do
  mkdir -p "$DEST/$(dirname "$p")"
  [ -s "$DEST/$p" ] || git -C "$G" show "$DTT_COMMIT:$p" > "$DEST/$p"
  ls -l "$DEST/$p"
done

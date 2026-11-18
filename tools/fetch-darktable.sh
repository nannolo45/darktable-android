#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Put the pinned darktable source (with the submodules the build needs) in
# <dir>. Uses upstream/darktable when the submodule is checked out, otherwise
# fetches the commit from tools/darktable-pin.sh. Never modifies the source.
#   tools/fetch-darktable.sh <dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
. "$HERE/darktable-pin.sh"
DEST="${1:?usage: fetch-darktable.sh <dir>}"
NEEDED="src/external/rawspeed src/external/LibRaw src/external/whereami src/external/libxcf src/external/OpenCL"

if [ -f "$REPO/upstream/darktable/CMakeLists.txt" ]; then
  COMMIT=$(git -C "$REPO/upstream/darktable" rev-parse HEAD)
  echo "using upstream/darktable submodule at $COMMIT" >&2
  git -C "$REPO/upstream/darktable" submodule update --init --depth 1 $NEEDED >&2
  rm -rf "$DEST"; cp -a "$REPO/upstream/darktable" "$DEST"
else
  COMMIT=$DTA_DARKTABLE_COMMIT
  if [ ! -f "$DEST/CMakeLists.txt" ] || [ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null)" != "$COMMIT" ]; then
    rm -rf "$DEST"; mkdir -p "$DEST"
    git -C "$DEST" init -q
    git -C "$DEST" remote add origin "$DTA_DARKTABLE_REPO"
    git -C "$DEST" fetch -q --depth 1 origin "$COMMIT"
    git -C "$DEST" -c advice.detachedHead=false checkout -q FETCH_HEAD
  fi
  git -C "$DEST" submodule update --init --depth 1 $NEEDED >&2
fi
# refuse to build a modified tree
if [ -n "$(git -C "$DEST" status --porcelain --ignore-submodules=none)" ]; then
  git -C "$DEST" status --short >&2
  echo "darktable source tree is not clean" >&2; exit 1
fi
echo "$COMMIT"

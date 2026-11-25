#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Put the pinned darktable source (with the submodules the build needs) in
# <dir>. The commit is the one the upstream/darktable submodule is pinned to
# (read by tools/darktable-pin.sh). Uses the submodule's checkout when it is
# present and at that commit, otherwise fetches the commit. Checks that the
# pinned source is clean, then applies the patch series in patches/ (PLAN.md
# 5; brief P4e) to the copy in <dir>. The submodule itself is never modified.
#   tools/fetch-darktable.sh <dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
. "$HERE/darktable-pin.sh"
DEST="${1:?usage: fetch-darktable.sh <dir>}"
NEEDED="src/external/rawspeed src/external/LibRaw src/external/whereami src/external/libxcf src/external/OpenCL"

COMMIT=$DTA_DARKTABLE_COMMIT
if [ -f "$REPO/upstream/darktable/CMakeLists.txt" ]; then
  HAVE=$(git -C "$REPO/upstream/darktable" rev-parse HEAD)
  if [ "$HAVE" != "$COMMIT" ]; then
    echo "upstream/darktable is checked out at $HAVE, but the repository pins $COMMIT;" >&2
    echo "run: git submodule update upstream/darktable" >&2
    exit 1
  fi
  echo "using upstream/darktable submodule at $COMMIT" >&2
  git -C "$REPO/upstream/darktable" submodule update --init --depth 1 $NEEDED >&2
  CHECK="$REPO/upstream/darktable"
  # the copy's .git is a link into the parent repository, so check the
  # submodule itself (below) rather than the copy
  rm -rf "$DEST"; cp -a "$REPO/upstream/darktable" "$DEST"
else
  if [ ! -f "$DEST/CMakeLists.txt" ] || [ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null)" != "$COMMIT" ]; then
    rm -rf "$DEST"; mkdir -p "$DEST"
    git -C "$DEST" init -q
    git -C "$DEST" remote add origin "$DTA_DARKTABLE_REPO"
    git -C "$DEST" fetch -q --depth 1 origin "$COMMIT"
    git -C "$DEST" -c advice.detachedHead=false checkout -q FETCH_HEAD
  else
    # a copy from an earlier run: back to the pinned commit before patching again
    git -C "$DEST" checkout -q -- .
  fi
  git -C "$DEST" submodule update --init --depth 1 $NEEDED >&2
  CHECK="$DEST"
fi
# refuse to build a modified tree
ST=$(git -C "$CHECK" status --porcelain --ignore-submodules=none) || { echo "cannot check $CHECK" >&2; exit 1; }
if [ -n "$ST" ]; then
  echo "$ST" >&2
  echo "darktable source tree is not clean" >&2; exit 1
fi
"$HERE/apply-patches.sh" "$DEST" >&2
echo "$COMMIT"

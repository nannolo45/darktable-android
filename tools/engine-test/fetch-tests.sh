#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copy the tests listed in tests.txt (XMP only) and the images they use from
# darktable-tests at the pinned commit into <dir>/<test>/ and <dir>/images/.
#   tools/engine-test/fetch-tests.sh <dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/darktable-tests.pin"
DEST="${1:?usage: fetch-tests.sh <dir>}"
G="$DEST/.git-dtt"
mkdir -p "$DEST/images"
if [ ! -d "$G" ]; then
  git init -q --bare "$G"
  git -C "$G" fetch -q --depth 1 --filter=blob:none "$DTT_REPO" "$DTT_COMMIT"
fi
for t in $(grep -v '^#' "$HERE/tests.txt"); do
  x=$(git -C "$G" ls-tree --name-only "$DTT_COMMIT" "$t/" | grep '\.xmp$' | head -1)
  [ -n "$x" ] || { echo "no xmp for $t" >&2; exit 1; }
  mkdir -p "$DEST/$t"
  git -C "$G" show "$DTT_COMMIT:$x" > "$DEST/$t/test.xmp"
  img=$(sed -n 's/.*xmpMM:DerivedFrom="\([^"]*\)".*/\1/p' "$DEST/$t/test.xmp" | head -1)
  echo "$img" > "$DEST/$t/image"
  [ -s "$DEST/images/$img" ] || git -C "$G" show "$DTT_COMMIT:images/$img" > "$DEST/images/$img"
done
# P1b probe: local tests (an XMP of a public test image), same layout
for d in "$HERE"/local/*/; do
  [ -f "$d/test.xmp" ] || continue
  t=$(basename "$d"); mkdir -p "$DEST/$t"; cp "$d/test.xmp" "$DEST/$t/test.xmp"
  img=$(sed -n 's/.*xmpMM:DerivedFrom="\([^"]*\)".*/\1/p' "$DEST/$t/test.xmp" | head -1)
  echo "$img" > "$DEST/$t/image"
  [ -s "$DEST/images/$img" ] || git -C "$G" show "$DTT_COMMIT:images/$img" > "$DEST/images/$img"
done
ls -l "$DEST/images"

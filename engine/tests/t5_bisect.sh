#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Diagnose test T5: which module makes a region render differ from the same
# crop of the full render. For each operation in the XMP's history, switch
# every entry of it off (in a copy of the XMP) and rerun the region test.
#
#   engine/tests/t5_bisect.sh <bridge build dir> <darktable prefix> <raw> <xmp> <output dir>
set -u
BRIDGE_BUILD=$(cd "${1:?}" && pwd)
export DT_PREFIX=$(cd "${2:?}" && pwd)
RAW=${3:?}; XMP=${4:?}; OUT=${5:?}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
DTB="$BRIDGE_BUILD/tests/dtb_test"

run() { # label xmp
  d="$OUT/$1"; mkdir -p "$d"
  "$DTB" "$OUT/work" region "$RAW" "$2" "$d" > "$d/T5.log" 2>&1
  echo "$1: $(grep '^RESULT region ' "$d/T5.log" | sed 's/RESULT region //; s/ render_s.*//' | tr '\n' ';')"
}

run all-on "$XMP"
# demosaic cannot be switched off; try its passthrough method instead
# (DT_IOP_DEMOSAIC_PASSTHROUGH_MONOCHROME for Bayer,
# DT_IOP_DEMOSAIC_PASSTHR_MONOX for X-Trans), which has no neighbourhood
DTB_TEST_DEMOSAIC_METHOD=${DEMOSAIC_PASSTHROUGH:-DT_IOP_DEMOSAIC_PASSTHROUGH_MONOCHROME} run demosaic-passthrough "$XMP"
# the internal margin: none, and larger than the default
for m in 0 16 64 256; do DTB_REGION_MARGIN=$m run "margin-$m" "$XMP"; done
for op in $(grep -o 'darktable:operation="[^"]*"' "$XMP" | cut -d'"' -f2 | sort -u); do
  python3 -I - "$XMP" "$OUT/off-$op.xmp" "$op" <<'EOF'
import re, sys
src, dst, op = sys.argv[1:4]
text = open(src, encoding="utf-8").read()
def off(m):
    e = m.group(0)
    if f'darktable:operation="{op}"' in e:
        e = e.replace('darktable:enabled="1"', 'darktable:enabled="0"')
    return e
open(dst, "w", encoding="utf-8").write(re.sub(r"<rdf:li\b[^>]*?/>", off, text, flags=re.S))
EOF
  run "off-$op" "$OUT/off-$op.xmp"
done

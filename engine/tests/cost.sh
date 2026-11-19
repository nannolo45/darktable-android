#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# P1c item 5: where the time of a small preview render after a change goes.
# Runs `dtb_test cost` on each test image, before (DTB_FORCE_SYNCH_ALL=1: every
# change resynchronises the whole pipe, as the bridge did before P1c) and after,
# alternating, with the bridge's step timers (DTB_TIMING) and darktable's
# per-module times (-d perf), and summarises with cost_summary.py.
#
#   engine/tests/cost.sh <bridge build dir> <darktable prefix> <darktable-tests dir> <output dir> [rounds] [cr2 raf arw]
set -u
BRIDGE_BUILD=$(cd "${1:?}" && pwd)
export DT_PREFIX=$(cd "${2:?}" && pwd)
TESTS=$(cd "${3:?}" && pwd)
OUT=${4:?}; ROUNDS=${5:-2}
shift 4; [ $# -gt 0 ] && shift
IMAGES=${*:-cr2 raf arw}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
DTB="$BRIDGE_BUILD/tests/dtb_test"
for c in $IMAGES; do
  case $c in
    cr2) RAW=images/mire1.cr2; XMP=0001-exposure/exposure.xmp ;;
    raf) RAW=images/xtransIV.raf; XMP=0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp ;;
    arw) RAW=images/hlrecovery.arw; XMP=0179-spektrafilm/spektrafilm.xmp ;;
  esac
  r=1
  while [ $r -le "$ROUNDS" ]; do
    for mode in before after; do
      if [ $mode = before ]; then f=1; else f=; fi
      env ${f:+DTB_FORCE_SYNCH_ALL=1} DTB_TIMING=1 DTB_DT_DEBUG=perf "$DTB" "$OUT/work" cost "$TESTS/$RAW" "$TESTS/$XMP" "$OUT" \
        > "$OUT/$c-$mode-$r.out" 2> "$OUT/$c-$mode-$r.err"
    done
    r=$((r + 1))
  done
done
python3 -I "$HERE/cost_summary.py" "$OUT" $IMAGES | tee "$OUT/summary.md"

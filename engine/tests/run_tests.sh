#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Bridge prototype tests T1 to T9 (brief S3), on three test images.
#
#   engine/tests/run_tests.sh <bridge build dir> <darktable prefix> <darktable-tests dir> <output dir> [T1 T2 ...]
#
# Pixel comparisons are made against darktable-cli of the same darktable
# build. Writes <output dir>/summary.txt and one log per test and image.
# Exit status: 0 if every test passed.
set -u

BRIDGE_BUILD=$(cd "${1:?bridge build dir}" && pwd)
DT_PREFIX=$(cd "${2:?darktable prefix}" && pwd)
TESTS=$(cd "${3:?darktable-tests dir}" && pwd)
OUT=${4:?output dir}
shift 4
ONLY="$*"
HERE=$(cd "$(dirname "$0")" && pwd)
export DT_PREFIX

DTB="$BRIDGE_BUILD/tests/dtb_test"
CLI="$DT_PREFIX/bin/darktable-cli"
CMP="python3 -I $HERE/compare.py"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"

# name | raw | xmp
CASES="
cr2|images/mire1.cr2|0001-exposure/exposure.xmp
raf|images/xtransIV.raf|0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp
arw|images/hlrecovery.arw|0179-spektrafilm/spektrafilm.xmp
"

FAILED=0
note() { echo "$*" | tee -a "$SUMMARY"; }
pass() { note "PASS $1 $2: $3"; }
fail() { note "FAIL $1 $2: $3"; FAILED=1; }
want() { [ -z "$ONLY" ] || echo " $ONLY " | grep -q " $1 "; }

# darktable-cli export to PNG, with a configuration of its own
cli() { # raw xmp out.png log
  rm -f "$3"
  "$CLI" "$1" "$2" "$3" --core --configdir "$OUT/cli-config" --cachedir "$OUT/cli-cache" \
    >> "$4" 2>&1
}

# compare and record: test case a b [extra compare args]
cmp_rec() {
  t=$1; c=$2; a=$3; b=$4; shift 4
  r=$($CMP "$a" "$b" "$@" 2>&1); s=$?
  echo "$t $c $(basename "$a") vs $(basename "$b") $*: $r" >> "$OUT/comparisons.txt"
  if [ $s -eq 0 ]; then pass "$t" "$c" "$r"; else fail "$t" "$c" "$r"; fi
}

result() { grep "^RESULT $2" "$1" | head -1 | cut -d' ' -f3-; }

md5_before=$(cd "$TESTS" && md5sum images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw)

echo "$CASES" | while IFS='|' read -r C RAW XMP; do
  [ -n "$C" ] || continue
  RAW="$TESTS/$RAW"; XMP="$TESTS/$XMP"
  D="$OUT/$C"; mkdir -p "$D"
  W="$D/work"
  note "== $C: $(basename "$RAW") with $(basename "$XMP")"

  cli "$RAW" "$XMP" "$D/cli.png" "$D/cli.log"

  if want T1; then
    "$DTB" "$W" render "$RAW" "$XMP" "$D/bridge.ppm" > "$D/T1.log" 2>&1 \
      || fail T1 "$C" "driver failed, see $D/T1.log"
    cmp_rec T1 "$C" "$D/bridge.ppm" "$D/cli.png"
    # the JSON the app will parse: modules and history must be valid JSON
    "$DTB" "$W" modules "$RAW" "$XMP" > "$D/modules.log" 2>&1
    if grep -E '^[[{]' "$D/modules.log" | python3 -I -c '
import json, sys
docs = [json.loads(l) for l in sys.stdin]
assert len(docs) == 2 and isinstance(docs[0], list) and "items" in docs[1]
print(len(docs[0]), "module instances,", len(docs[1]["items"]), "history items")' > "$D/json.txt" 2>&1
    then pass T1 "$C" "modules and history JSON parse: $(cat "$D/json.txt")"
    else fail T1 "$C" "invalid JSON from the bridge: $(tail -1 "$D/json.txt")"; fi
  fi

  if want T2; then
    if "$DTB" "$W" param "$RAW" "$XMP" "$D" > "$D/T2.log" 2>&1; then
      pass T2 "$C" "history $(result "$D/T2.log" history_before) -> $(result "$D/T2.log" history_after), exposure $(result "$D/T2.log" exposure_before) -> $(result "$D/T2.log" exposure_after)"
    else
      fail T2 "$C" "driver checks failed, see $D/T2.log"
    fi
    cli "$RAW" "$D/param.xmp" "$D/param_cli.png" "$D/cli.log"
    cmp_rec T2 "$C" "$D/param_bridge.ppm" "$D/param_cli.png"
  fi

  if want T3; then
    if "$DTB" "$W" instances "$RAW" "$XMP" "$D" > "$D/T3.log" 2>&1; then
      pass T3 "$C" "duplicate/change/off/on in session (new priority $(result "$D/T3.log" new_priority))"
    else
      fail T3 "$C" "driver checks failed, see $D/T3.log"
    fi
    # a fresh session reproduces the instances and the image
    "$DTB" "$W" exposures "$RAW" "$D/instances.xmp" > "$D/T3-reopen.log" 2>&1
    grep '^RESULT after exposure' "$D/T3.log" | sed 's/^RESULT after //' > "$D/T3-a.txt"
    grep '^RESULT listed exposure' "$D/T3-reopen.log" | sed 's/^RESULT listed //' > "$D/T3-b.txt"
    if [ -s "$D/T3-a.txt" ] && cmp -s "$D/T3-a.txt" "$D/T3-b.txt"; then
      pass T3 "$C" "fresh session lists the same exposure instances: $(tr '\n' ';' < "$D/T3-b.txt")"
    else
      fail T3 "$C" "instances differ after reopening: $(tr '\n' ';' < "$D/T3-a.txt") vs $(tr '\n' ';' < "$D/T3-b.txt")"
    fi
    "$DTB" "$W" render "$RAW" "$D/instances.xmp" "$D/instances_reopen.ppm" > "$D/T3-render.log" 2>&1
    cmp_rec T3 "$C" "$D/instances_reopen.ppm" "$D/instances_bridge.ppm" --tolerance 0
    cli "$RAW" "$D/instances.xmp" "$D/instances_cli.png" "$D/cli.log"
    cmp_rec T3 "$C" "$D/instances_bridge.ppm" "$D/instances_cli.png"
    # what darktable itself reads from the XMP: darktable-cli --verbose prints
    # the history it loaded into its library (the desktop's read path)
    "$CLI" "$RAW" "$D/instances.xmp" "$D/instances_cli_v.png" --verbose --core \
      --configdir "$OUT/cli-config" --cachedir "$OUT/cli-cache" > "$D/T3-cli-history.txt" 2>&1
    rm -f "$D/instances_cli_v.png"
  fi

  if want T4; then
    if "$DTB" "$W" history "$RAW" "$XMP" "$D" > "$D/T4.log" 2>&1; then
      pass T4 "$C" "$(result "$D/T4.log" history); goto $(result "$D/T4.log" goto); back to top: $(result "$D/T4.log" goto_back_to_top); $(result "$D/T4.log" compressed); compress vs top: $(result "$D/T4.log" compress_vs_top)"
    else
      fail T4 "$C" "driver checks failed, see $D/T4.log"
    fi
    cli "$RAW" "$D/history_back.xmp" "$D/history_back_cli.png" "$D/cli.log"
    cmp_rec T4 "$C" "$D/history_back.ppm" "$D/history_back_cli.png"
    cli "$RAW" "$D/history_compressed.xmp" "$D/history_compressed_cli.png" "$D/cli.log"
    cmp_rec T4 "$C" "$D/history_compressed.ppm" "$D/history_compressed_cli.png"
  fi

  if want T5; then
    "$DTB" "$W" region "$RAW" "$XMP" "$D" > "$D/T5.log" 2>&1 || fail T5 "$C" "driver failed, see $D/T5.log"
    for i in 0 1 2; do
      set -- $(result "$D/T5.log" "region${i}_origin")
      cmp_rec T5 "$C" "$D/region$i.ppm" "$D/region${i}_crop.ppm" --tolerance 0
      cmp_rec T5 "$C" "$D/region$i.ppm" "$D/cli.png" --crop "$1" "$2" 512 512
    done
  fi

  if want T6; then
    # the engine's own exposure version, read through the bridge
    ev=$("$DTB" "$W" modules "$RAW" "$XMP" 2>/dev/null | grep '^\[' | python3 -I -c '
import json, sys
print([m["version"] for m in json.loads(sys.stdin.readline()) if m["operation"] == "exposure"][0])')
    python3 -I "$HERE/make_guard_xmps.py" "$XMP" "$D" exposure "$ev" > "$D/T6-make.log" 2>&1
    sum_before=$(md5sum "$D/newer.xmp" "$D/unknown.xmp" "$XMP" | cut -d' ' -f1 | tr '\n' ' ')
    if "$DTB" "$W" guard "$RAW" "$XMP" "$D" > "$D/T6.log" 2>&1; then
      pass T6 "$C" "$(tr '\n' ' ' < "$D/T6-make.log")reported and refused (see T6.log)"
    else
      fail T6 "$C" "driver checks failed, see $D/T6.log"
    fi
    sum_after=$(md5sum "$D/newer.xmp" "$D/unknown.xmp" "$XMP" | cut -d' ' -f1 | tr '\n' ' ')
    if [ "$sum_before" = "$sum_after" ]; then pass T6 "$C" "guarded files unchanged"
    else fail T6 "$C" "a guarded file changed"; fi
  fi

  if want T7; then
    if "$DTB" "$W" timing "$RAW" "$XMP" "$D" > "$D/T7.log" 2>&1; then
      note "INFO T7 $C (x86_64, 2 cores): $(grep '^RESULT' "$D/T7.log" | grep -v 'engine\|exit' | cut -d' ' -f2- | tr '\n' ';')"
    else
      fail T7 "$C" "driver failed, see $D/T7.log"
    fi
  fi

  if want T8; then
    if "$DTB" "$W" cancel "$RAW" "$XMP" "$D" > "$D/T8.log" 2>&1; then
      pass T8 "$C" "worst latency $(result "$D/T8.log" cancel_worst_latency_s) s; after cancels: $(result "$D/T8.log" after_cancel_vs_reference)"
    else
      fail T8 "$C" "driver checks failed, see $D/T8.log"
    fi
  fi

  if want T9; then
    if "$DTB" "$W" mask "$RAW" "$XMP" "$D" > "$D/T9.log" 2>&1; then
      pass T9 "$C" "local: inside $(result "$D/T9.log" mask_change_inside_mean), corners $(result "$D/T9.log" mask_change_corners_mean)"
    else
      fail T9 "$C" "driver checks failed, see $D/T9.log"
    fi
    cli "$RAW" "$D/mask.xmp" "$D/mask_cli.png" "$D/cli.log"
    cmp_rec T9 "$C" "$D/mask_bridge.ppm" "$D/mask_cli.png"
  fi
done

md5_after=$(cd "$TESTS" && md5sum images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw)
if [ "$md5_before" = "$md5_after" ]; then note "PASS safety: RAW files unchanged"
else note "FAIL safety: a RAW file changed"; fi

grep -q '^FAIL' "$SUMMARY" && exit 1
exit 0

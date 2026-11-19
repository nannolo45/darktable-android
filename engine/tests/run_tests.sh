#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Bridge tests on three test images: T1 to T9 and X1 (brief S3), C1 to C7
# (brief P1a, Engine API v1 section 4).
#
#   engine/tests/run_tests.sh <bridge build dir> <darktable prefix> <darktable-tests dir> <output dir> [T1 C2 ...]
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

# name | raw | xmp | image class
CASES="
cr2|images/mire1.cr2|0001-exposure/exposure.xmp|bayer
raf|images/xtransIV.raf|0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp|xtrans
arw|images/hlrecovery.arw|0179-spektrafilm/spektrafilm.xmp|bayer
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

echo "$CASES" | while IFS='|' read -r C RAW XMP CLASS; do
  [ -n "$C" ] || continue
  RAW="$TESTS/$RAW"; XMP="$TESTS/$XMP"
  D="$OUT/$C"; mkdir -p "$D"
  W="$D/work"
  note "== $C: $(basename "$RAW") with $(basename "$XMP")"

  cli "$RAW" "$XMP" "$D/cli.png" "$D/cli.log"

  if want T1; then
    rm -f "$D/bridge.ppm"
    "$DTB" "$W" render "$RAW" "$XMP" "$D/bridge.ppm" > "$D/T1.log" 2>&1 \
      || fail T1 "$C" "driver failed, see $D/T1.log"
    cmp_rec T1 "$C" "$D/bridge.ppm" "$D/cli.png"
    # the JSON the app will parse: modules and history must be valid JSON
    "$DTB" "$W" modules "$RAW" "$XMP" > "$D/modules.log" 2>&1
    if grep -E '^[[{]' "$D/modules.log" | python3 -I -c '
import json, sys
docs = [json.loads(l) for l in sys.stdin]
assert len(docs) == 2 and isinstance(docs[0], list) and "items" in docs[1] and "end" in docs[1]
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
    # render_region renders with an internal margin: the region must equal
    # the crop of the full render exactly (Engine API 2.7), and the crop of
    # darktable-cli's export
    for i in 0 1 2; do
      set -- $(result "$D/T5.log" "region${i}_origin")
      note "INFO T5 $C region $i: $(grep "^RESULT region $i at" "$D/T5.log" | cut -d' ' -f3-)"
      cmp_rec T5 "$C" "$D/region$i.ppm" "$D/region${i}_crop.ppm" --tolerance 0
      cmp_rec T5 "$C" "$D/region$i.ppm" "$D/cli.png" --crop "$1" "$2" 512 512 --tolerance 0
    done
  fi

  if want T6 || want C6; then
    # the engine's own exposure version, read through the bridge
    ev=$("$DTB" "$W" modules "$RAW" "$XMP" 2>/dev/null | grep '^\[' | python3 -I -c '
import json, sys
print([m["version"] for m in json.loads(sys.stdin.readline()) if m["op"] == "exposure"][0])')
    python3 -I "$HERE/make_guard_xmps.py" "$XMP" "$D" exposure "$ev" > "$D/T6-make.log" 2>&1
  fi

  if want T6; then
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

  # X1, beyond T1 to T9: preview, JPEG export, instance move and delete
  if want X1; then
    if "$DTB" "$W" extras "$RAW" "$XMP" "$D" > "$D/X1.log" 2>&1; then
      pass X1 "$C" "move/new/duplicate/delete in session: $(grep '^RESULT after' "$D/X1.log" | cut -d' ' -f3- | tr '\n' ';')"
    else
      fail X1 "$C" "driver checks failed, see $D/X1.log"
    fi
    cli "$RAW" "$D/x_move.xmp" "$D/x_move_cli.png" "$D/cli.log"
    cmp_rec X1 "$C" "$D/x_move_bridge.ppm" "$D/x_move_cli.png"
    cli "$RAW" "$D/x_delete.xmp" "$D/x_delete_cli.png" "$D/cli.log"
    cmp_rec X1 "$C" "$D/x_delete_bridge.ppm" "$D/x_delete_cli.png"
    "$DTB" "$W" exposures "$RAW" "$D/x_delete.xmp" > "$D/X1-reopen.log" 2>&1
    grep '^RESULT after exposure' "$D/X1.log" | sed 's/^RESULT after //' > "$D/X1-a.txt"
    grep '^RESULT listed exposure' "$D/X1-reopen.log" | sed 's/^RESULT listed //' > "$D/X1-b.txt"
    if [ -s "$D/X1-a.txt" ] && cmp -s "$D/X1-a.txt" "$D/X1-b.txt"; then
      pass X1 "$C" "fresh session lists the same instances after delete"
    else
      fail X1 "$C" "instances differ after reopening: $(tr '\n' ';' < "$D/X1-a.txt") vs $(tr '\n' ';' < "$D/X1-b.txt")"
    fi
    # JPEG: the same encoder and quality, so the decoded pixels must match
    rm -f "$D/x_export_cli.jpg"
    "$CLI" "$RAW" "$XMP" "$D/x_export_cli.jpg" --core --configdir "$OUT/cli-config" \
      --cachedir "$OUT/cli-cache" --conf plugins/imageio/format/jpeg/quality=95 >> "$D/cli.log" 2>&1
    cmp_rec X1 "$C" "$D/x_export.jpg" "$D/x_export_cli.jpg"
    # preview: a different input (the downscaled mipmap) by design, so this is
    # a measurement against darktable-cli's own downscaled export, not a check
    rm -f "$D/x_preview_cli.png"
    "$CLI" "$RAW" "$XMP" "$D/x_preview_cli.png" --width 1024 --height 1024 --hq false --core \
      --configdir "$OUT/cli-config" --cachedir "$OUT/cli-cache" >> "$D/cli.log" 2>&1
    note "INFO X1 $C preview vs darktable-cli 1024 px export: $($CMP "$D/x_preview.ppm" "$D/x_preview_cli.png" --tolerance 255 2>&1)"
  fi
  # ---- P1a: Engine API v1 section 4 ----
  # driver checks, then darktable-cli renders every XMP the driver wrote and
  # must equal the bridge's own render of that state
  drv() { # test command title
    if DTB_TEST_CLASS="$CLASS" "$DTB" "$W" "$2" "$RAW" "$XMP" "$D" > "$D/$1.log" 2>&1; then
      pass "$1" "$C" "$3: $(grep '^RESULT' "$D/$1.log" | grep -v 'RESULT engine\|RESULT exit' | cut -d' ' -f2- | cut -c1-160 | tr '\n' ';')"
    else
      fail "$1" "$C" "$3: $(grep '^FAIL' "$D/$1.log" | head -5 | tr '\n' ';') see $D/$1.log"
    fi
  }
  parity() { # test name
    if [ -f "$D/$2.xmp" ]; then
      cli "$RAW" "$D/$2.xmp" "$D/${2}_cli.png" "$D/cli.log"
      cmp_rec "$1" "$C" "$D/${2}_bridge.ppm" "$D/${2}_cli.png"
    else
      fail "$1" "$C" "$2.xmp was not written"
    fi
  }
  if want C1; then drv C1 api "engine_info, image_info, modules JSON"; fi
  if want C2; then
    drv C2 params "paths, types, ranges, params_set, grouping, reset"
    parity C2 params; parity C2 reset
  fi
  if want C3; then drv C3 undo "undo/redo"; parity C3 undo; fi
  if want C4; then drv C4 actions "module actions"; parity C4 actions; fi
  if want C5; then drv C5 threads "set_threads, set_thread_cpus"; fi
  if want C6; then drv C6 readonly "read-only refusal"; fi
  if want C7; then
    drv C7 output "xmp_write, export, metadata_reload"
    # the default export (JPEG 95) and the 8-bit PNG decode as darktable-cli's
    rm -f "$D/c7_cli.png" "$D/c7_cli.jpg"
    if [ -f "$D/out.xmp" ]; then
      cli "$RAW" "$D/out.xmp" "$D/c7_cli.png" "$D/cli.log"
      cmp_rec C7 "$C" "$D/e_8.png" "$D/c7_cli.png" --tolerance 0
      "$CLI" "$RAW" "$D/out.xmp" "$D/c7_cli.jpg" --core --configdir "$OUT/cli-config" \
        --cachedir "$OUT/cli-cache" --conf plugins/imageio/format/jpeg/quality=95 >> "$D/cli.log" 2>&1
      cmp_rec C7 "$C" "$D/e_default.jpg" "$D/c7_cli.jpg" --tolerance 0
    fi
  fi
done

md5_after=$(cd "$TESTS" && md5sum images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw)
if [ "$md5_before" = "$md5_after" ]; then note "PASS safety: RAW files unchanged"
else note "FAIL safety: a RAW file changed"; fi

grep -q '^FAIL' "$SUMMARY" && exit 1
exit 0

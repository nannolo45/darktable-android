#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Bridge tests on three test images: T1 to T9 and X1 (brief S3), C1 to C7
# (brief P1a, Engine API v1 section 4), C8, C9 (brief P1c), C10 to C16
# (brief P1b part 1, engine-api.md section 5), C17 and later (P1b part 2,
# section 6), C25 (brief P3c: the picker actions against darktable's own
# callbacks, tests/dtb_pickref).
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
# DTB_TEST_RESOURCELEVEL (for example "small"): darktable's memory budget, for
# the bridge and darktable-cli alike, on a machine shared with other work.
# Unset = darktable's default (as in CI).
CLI_LEVEL=""
[ -n "${DTB_TEST_RESOURCELEVEL:-}" ] && CLI_LEVEL="--conf resourcelevel=$DTB_TEST_RESOURCELEVEL"
[ -n "${DTB_TEST_RESOURCELEVEL:-}" ] && export DTB_TEST_RESOURCELEVEL
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
  "$CLI" "$1" "$2" "$3" --core --configdir "$OUT/cli-config" --cachedir "$OUT/cli-cache" $CLI_LEVEL \
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
    "$CLI" "$RAW" "$D/instances.xmp" "$D/instances_cli_v.png" --verbose --core $CLI_LEVEL \
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
    # render_region renders with an internal margin: the region must equal
    # the crop of the full render exactly (Engine API 2.7), and the crop of
    # darktable-cli's export. P1c: the only sanctioned exceptions are the
    # lines of t5_expected.txt (image, case, region, max, mean): a listed
    # difference passes within its bounds; anything not listed must be exact.
    t5_case() { # case log-dir [env...]
      tc=$1; td=$2; shift 2
      mkdir -p "$td"
      env "$@" "$DTB" "$W" region "$RAW" "$XMP" "$td" > "$td/T5.log" 2>&1 || fail T5 "$C" "$tc: driver failed, see $td/T5.log"
      for i in 0 1 2; do
        note "INFO T5 $C $tc region $i: $(grep "^RESULT region $i at" "$td/T5.log" | cut -d' ' -f3-)"
        bound=$(awk -v a="$C" -v b="$tc" -v r="$i" '$1 == a && $2 == b && $3 == r { print $4, $5 }' "$HERE/t5_expected.txt")
        if [ -n "$bound" ]; then
          set -- $bound
          cmp_rec T5 "$C/$tc/region$i" "$td/region$i.ppm" "$td/region${i}_crop.ppm" --tolerance "$1" --mean-tolerance "$2"
          note "INFO T5 $C $tc region $i is a listed difference (t5_expected.txt): bounds max $1, mean $2"
        else
          cmp_rec T5 "$C/$tc/region$i" "$td/region$i.ppm" "$td/region${i}_crop.ppm" --tolerance 0
        fi
        # regions in reverse order, after the others: the same pixels
        again=$(grep "^RESULT region $i again" "$td/T5.log" | sed 's/.*max \([0-9]*\) mean \([0-9.]*\).*/\1 \2/')
        set -- $again
        if [ -n "$bound" ]; then lim=$(echo "$bound" | cut -d' ' -f1); else lim=0; fi
        if [ -n "${1:-}" ] && [ "$1" -le "$lim" ]; then pass T5 "$C" "$tc region $i rendered again after the others: max $1 mean $2"
        else fail T5 "$C" "$tc region $i rendered again after the others: max ${1:-?} mean ${2:-?}"; fi
      done
      note "INFO T5 $C $tc at scale 0.5: $(grep '^RESULT half_scale' "$td/T5.log" | cut -d' ' -f3- | tr '\n' ';')"
    }
    t5_case base "$D"
    for i in 0 1 2; do
      set -- $(result "$D/T5.log" "region${i}_origin")
      bound=$(awk -v a="$C" -v r="$i" '$1 == a && $2 == "base" && $3 == r { print $4, $5 }' "$HERE/t5_expected.txt")
      if [ -n "$bound" ]; then
        b1=$(echo "$bound" | cut -d' ' -f1); b2=$(echo "$bound" | cut -d' ' -f2)
        cmp_rec T5 "$C/base/region$i-vs-cli" "$D/region$i.ppm" "$D/cli.png" --crop "$1" "$2" 512 512 --tolerance "$b1" --mean-tolerance "$b2"
      else
        cmp_rec T5 "$C/base/region$i-vs-cli" "$D/region$i.ppm" "$D/cli.png" --crop "$1" "$2" 512 512 --tolerance 0
      fi
    done
    # P1c: modules with a spatial reach, switched on with their defaults on
    # top of the test edit, on the 10 MP image (the bridge widens the margin
    # for the modules of render.c's _reach_modules)
    if [ "$C" = cr2 ]; then
      for op in shadhi lowpass soften bloom denoiseprofile monochrome bilat bilateral \
                sharpen diffuse filmicrgb atrous colorequal nlmeans defringe globaltonemap; do
        t5_case "on-$op" "$D/T5-$op" DTB_TEST_ON="$op"
      done
    fi
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
      note "INFO T7 $C ($(uname -m), $(nproc) cores): $(grep '^RESULT' "$D/T7.log" | grep -v 'engine\|exit' | cut -d' ' -f2- | tr '\n' ';')"
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
    "$CLI" "$RAW" "$XMP" "$D/x_export_cli.jpg" --core $CLI_LEVEL --configdir "$OUT/cli-config" \
      --cachedir "$OUT/cli-cache" --conf plugins/imageio/format/jpeg/quality=95 >> "$D/cli.log" 2>&1
    cmp_rec X1 "$C" "$D/x_export.jpg" "$D/x_export_cli.jpg"
    # preview: a different input (the downscaled mipmap) by design, so this is
    # a measurement against darktable-cli's own downscaled export, not a check
    rm -f "$D/x_preview_cli.png"
    "$CLI" "$RAW" "$XMP" "$D/x_preview_cli.png" --width 1024 --height 1024 --hq false --core $CLI_LEVEL \
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
  if want C4; then
    drv C4 actions "module actions"
    # darktable-cli must find the LUT the driver set, in the same root folder
    if [ -f "$D/actions.xmp" ]; then
      rm -f "$D/actions_cli.png"
      "$CLI" "$RAW" "$D/actions.xmp" "$D/actions_cli.png" --core $CLI_LEVEL --configdir "$OUT/cli-config" \
        --cachedir "$OUT/cli-cache" --conf "plugins/darkroom/lut3d/def_path=$D/luts" >> "$D/cli.log" 2>&1
      cmp_rec C4 "$C" "$D/actions_bridge.ppm" "$D/actions_cli.png"
    else
      fail C4 "$C" "actions.xmp was not written"
    fi
  fi
  if want C5; then drv C5 threads "set_threads, set_thread_cpus"; fi
  if want C6; then drv C6 readonly "read-only refusal"; fi
  if want C9; then drv C9 drag "a drag resynchronises one item; picture = fresh session"; parity C9 drag; fi
  if want C8; then
    drv C8 reopen "open(raw, NULL) is the default edit, open(raw, xmp) that XMP's edit"
    # the default edit as darktable-cli makes it from the RAW alone
    rm -f "$D/reopen_default_cli.png"
    "$CLI" "$RAW" "$D/reopen_default_cli.png" --core --configdir "$OUT/cli-config" --cachedir "$OUT/cli-cache" \
      $CLI_LEVEL >> "$D/cli.log" 2>&1
    cmp_rec C8 "$C/default-edit-vs-cli" "$D/reopen_default_bridge.ppm" "$D/reopen_default_cli.png"
  fi
  if want C7; then
    drv C7 output "xmp_write, export, metadata_reload"
    # the default export (JPEG 95) and the 8-bit PNG decode as darktable-cli's
    rm -f "$D/c7_cli.png" "$D/c7_cli.jpg"
    if [ -f "$D/out.xmp" ]; then
      cli "$RAW" "$D/out.xmp" "$D/c7_cli.png" "$D/cli.log"
      cmp_rec C7 "$C" "$D/e_8.png" "$D/c7_cli.png" --tolerance 0
      "$CLI" "$RAW" "$D/out.xmp" "$D/c7_cli.jpg" --core $CLI_LEVEL --configdir "$OUT/cli-config" \
        --cachedir "$OUT/cli-cache" --conf plugins/imageio/format/jpeg/quality=95 >> "$D/cli.log" 2>&1
      cmp_rec C7 "$C" "$D/e_default.jpg" "$D/c7_cli.jpg" --tolerance 0
    fi
  fi
  # ---- P1b part 1 (engine-api.md section 5) ----
  if want C10; then
    # a non-raw picture darktable reads: darktable-cli's own PNG of this image
    cp "$D/cli.png" "$D/nonraw.png" 2>/dev/null
    drv C10 e1 "undecodable files refused at open; truncated RAW; non-raw opens"
  fi
  if want C11; then
    drv C11 presets "presets: list, apply, store, rename, delete, import, export"
    parity C11 preset_builtin; parity C11 preset_user
    if "$DTB" "$W" persist "$RAW" presets "$D" > "$D/C11-persist.log" 2>&1; then
      pass C11 "$C" "presets kept in the configuration folder across processes"
    else
      fail C11 "$C" "presets lost across processes, see $D/C11-persist.log"
    fi
  fi
  if want C12; then
    # a public style: one of darktable's example styles (GPL-3.0, shipped with darktable)
    DTB_TEST_STYLE="${DTB_TEST_STYLE:-$DT_PREFIX/share/darktable/styles/examples_effects_day for twilight.dtstyle}" \
      drv C12 styles "styles: list, import, refuse, apply append/overwrite, create, delete"
    parity C12 style_append; parity C12 style_overwrite; parity C12 style_created; parity C12 style_default_append
    # darktable-cli applying the same style from the same database (a copy of the bridge's configuration
    # folder) during its export: --style is the append mode. It builds the history in the export path
    # (imageio.c dt_imageio_export_with_flags), the bridge as desktop does (common/styles.c
    # _styles_apply_to_image_ext); the two place a new instance at a different pipe position (exposure.1
    # before exposure in darktable-cli, after it on desktop), which changes the picture by at most 2
    # levels on the test images (P1b report). The exact check is the parity of the bridge's XMP above.
    rm -rf "$OUT/cli-config-style-$C"; cp -r "$W/config" "$OUT/cli-config-style-$C"
    rm -f "$D/style_cli_append.png" "$D/style_cli_overwrite.png"
    "$CLI" "$RAW" "$D/style_cli_append.png" --style "p1b public" --core $CLI_LEVEL \
      --configdir "$OUT/cli-config-style-$C" --cachedir "$OUT/cli-cache" >> "$D/cli.log" 2>&1
    cmp_rec C12 "$C/bridge-append-vs-cli-style" "$D/style_default_append_bridge.ppm" "$D/style_cli_append.png" --tolerance 2 --mean-tolerance 0.5
    # --style-overwrite replaces the history with the style alone (no default edit): not desktop's overwrite
    "$CLI" "$RAW" "$D/style_cli_overwrite.png" --style "p1b public" --style-overwrite --core $CLI_LEVEL \
      --configdir "$OUT/cli-config-style-$C" --cachedir "$OUT/cli-cache" >> "$D/cli.log" 2>&1
    note "INFO C12 $C bridge overwrite (default edit + style) vs darktable-cli --style-overwrite (style only): $($CMP "$D/style_default_append_bridge.ppm" "$D/style_cli_overwrite.png" --tolerance 255 2>&1)"
    if "$DTB" "$W" persist "$RAW" styles "$D" > "$D/C12-persist.log" 2>&1; then
      pass C12 "$C" "styles kept in the configuration folder across processes"
    else
      fail C12 "$C" "styles lost across processes, see $D/C12-persist.log"
    fi
  fi
  if want C13; then
    drv C13 defedit "the default edit equals the bridge's reopened XMP of it"
    if [ -f "$D/fresh.xmp" ]; then
      rm -f "$D/fresh_cli.png"
      cli "$RAW" "$D/fresh.xmp" "$D/fresh_cli.png" "$D/cli.log"
      cmp_rec C13 "$C/default-edit-xmp-vs-cli" "$D/fresh_bridge.ppm" "$D/fresh_cli.png" --tolerance 0
    else
      fail C13 "$C" "fresh.xmp was not written"
    fi
  fi
  if want C14; then drv C14 actions2 "colorin profiles, denoise current, WB tuning, framing aspect, lens md"; parity C14 actions2; fi
  if want C16; then drv C16 times "per-module times of the last render"; fi
  # P1b part 2 (engine-api.md section 6)
  if want C17; then drv C17 bypass "a render that leaves one instance out"; fi
  if want C18; then drv C18 undohash "undo and redo keep the XMP's history hashes"; fi
  if want C19; then drv C19 pickers "colour pickers, histogram, picker actions"; fi
  if want C20; then drv C20 coords "coordinates between the original and the picture"; fi
  if want C21; then
    drv C21 blend "blending: get, set, validation, grouping"
    parity C21 blend_uniform; parity C21 blend_parametric; parity C21 blend_lab
  fi
  if want C22; then
    drv C22 masks "drawn masks: create, attach, share, edit, delete, reopen"
    parity C22 masks_drawn; parity C22 masks_duplicate; parity C22 masks_shared; parity C22 masks_edited
  fi
  if want C24; then drv C24 maskshow "mask display overlay"; fi
  # brief P3d stage 2a (engine-api.md 6.9): one item per shape and brush session, the mask tree, parametric elements
  if want C26; then
    drv C26 masktree "mask tree: one-step shapes, history groups, tree editing, parametric elements, reopen"
    parity C26 masktree_difference; parity C26 masktree_groups; parity C26 masktree_parametric
  fi
  # brief P3g (engine-api.md 6.10): export progress events and cancel; the export equals darktable-cli's
  if want C27; then
    drv C27 exportprog "export progress in order to 100 %, cancel leaves no file"
    if [ -f "$D/p3g_export.jpg" ]; then
      rm -f "$D/p3g_export_cli.jpg"
      "$CLI" "$RAW" "$XMP" "$D/p3g_export_cli.jpg" --core $CLI_LEVEL --configdir "$OUT/cli-config" \
        --cachedir "$OUT/cli-cache" --conf plugins/imageio/format/jpeg/quality=95 >> "$D/cli.log" 2>&1
      cmp_rec C27 "$C" "$D/p3g_export.jpg" "$D/p3g_export_cli.jpg"
      # the same pixels: a second export, the export after the cancels, dtb_export without progress
      for f in p3g_control p3g_again p3g_plain; do
        if [ -f "$D/$f.jpg" ]; then cmp_rec C27 "$C" "$D/$f.jpg" "$D/p3g_export.jpg"; else fail C27 "$C" "$f.jpg was not written"; fi
      done
    else
      fail C27 "$C" "p3g_export.jpg was not written"
    fi
  fi
  # brief P3h (engine-api.md 8): render option history_end equals goto + render + goto back
  if want C28; then
    drv C28 snaphist "render another history position without moving the history end"
    # which live picture is right: before or after a reload, against darktable-cli of the XMP of that state
    parity C28 p3h_first_before_reload; parity C28 p3h_first_after_reload
    # the session after history_goto(1) and back against darktable-cli of the state before it
    [ -f "$D/p3h_first_before_reload_cli.png" ] && cmp_rec C28 "$C" "$D/p3h_first_after_reload_bridge.ppm" "$D/p3h_first_before_reload_cli.png"
    parity C28 p3h_before_reload; parity C28 p3h_after_reload
    # T1: masks above the history end across undo, redo and compress: the XMP written after each against darktable-cli
    drv C28b undomask "a drawn mask above the history end survives undo, redo and compress"
    for f in t1_undo_after t1_redo_after t1_compress_below t1_compress_first; do parity C28b $f; done
  fi
  # brief P3j (engine-api.md 9): copy and selective paste of the history
  if want C29; then
    drv C29 copypaste "copy all/parts, paste append/overwrite onto the open photo and onto XMP files"
    # targets are byte copies of the RAW, so darktable-cli renders their XMP with the test RAW
    parity C29 p3j_b_overwrite; parity C29 p3j_c_append
    # every item copied and pasted with overwrite: the same pixels as the source
    [ -f "$D/p3j_b2_parts_overwrite_bridge.ppm" ] && cmp_rec C29 "$C" "$D/p3j_b2_parts_overwrite_bridge.ppm" "$D/p3j_a_bridge.ppm"
    # for information: a plain copy leaves the unsafe modules (white balance ...) at the target's own
    [ -f "$D/p3j_b_overwrite_bridge.ppm" ] && echo "C29 $C info plain copy vs source: $($CMP "$D/p3j_b_overwrite_bridge.ppm" "$D/p3j_a_bridge.ppm" 2>&1)" >> "$OUT/comparisons.txt"
    # the reference: desktop's own dt_history_copy_and_paste_on_image on plain library images (dtb_p3jref), the
    # same pastes as the bridge's step 3 (selective copy of the new instance, append onto C) and step 4 (plain copy
    # of A, append onto the C of step 3); the bridge's XMP must equal it (p3j_xmp_compare.py)
    PR=$(grep '^RESULT items' "$D/C29.log" | sed -n 's/.*priority \([0-9]*\).*/\1/p')
    EXT="${RAW##*.}"
    if [ -n "$PR" ] && [ -f "$D/p3j_a.xmp" ] && [ -f "$D/p3j_c_append.xmp" ] && [ -f "$D/p3j_c_pasted.xmp" ]; then
      cp "$RAW" "$D/p3j_cref1.$EXT"; cp "$RAW" "$D/p3j_cref2.$EXT"
      if timeout 900 "$BRIDGE_BUILD/tests/dtb_p3jref" "$W-p3jref1" "$RAW" "$D/p3j_a.xmp" "$D/p3j_cref1.$EXT" "$XMP" \
           "$D/p3j_c_append_ref.xmp" exposure "$PR" > "$D/C29-ref-parts.log" 2>&1; then
        cmp_xmp() { r=$(python3 -I "$HERE/p3j_xmp_compare.py" "$1" "$2" 2>&1); s=$?
          echo "C29 $C $(basename "$1") vs $(basename "$2"): $r" >> "$OUT/comparisons.txt"
          if [ $s -eq 0 ]; then pass C29 "$C" "$3: $r"; else fail C29 "$C" "$3: $r"; fi; }
        cmp_xmp "$D/p3j_c_pasted.xmp" "$D/p3j_c_append_ref.xmp" "selective append onto C (XMP written) vs desktop's path"
        cmp_xmp "$D/p3j_c_append.xmp" "$D/p3j_c_append_ref-reloaded.xmp" "C opened from it and saved vs desktop's path read back"
        if timeout 900 "$BRIDGE_BUILD/tests/dtb_p3jref" "$W-p3jref2" "$RAW" "$D/p3j_a.xmp" "$D/p3j_cref2.$EXT" "$D/p3j_c_append.xmp" \
             "$D/p3j_c_open_paste_ref.xmp" all > "$D/C29-ref-all.log" 2>&1; then
          # the open photo is read back after the paste, as desktop's darkroom does (dt_dev_reload_history_items)
          cmp_xmp "$D/p3j_c_open_paste.xmp" "$D/p3j_c_open_paste_ref-reloaded.xmp" "paste onto the open photo vs desktop's path read back"
        else
          fail C29 "$C" "reference (plain copy) failed: $(grep '^FAIL' "$D/C29-ref-all.log" | head -2 | tr '\n' ';')"
        fi
      else
        fail C29 "$C" "reference (selective copy) failed: $(grep '^FAIL' "$D/C29-ref-parts.log" | head -2 | tr '\n' ';')"
      fi
    else
      fail C29 "$C" "no reference: the bridge test wrote no p3j_a.xmp / p3j_c_append.xmp or no priority"
    fi
    rm -f "$D"/p3j_b.* "$D"/p3j_b2.* "$D"/p3j_c.* "$D"/p3j_d.* "$D"/p3j_cref1.* "$D"/p3j_cref2.* 2>/dev/null
  fi
  # brief P3l: styles and presets shared through LightPhotoLibrary (PLAN.md 4.5); once, on the first image
  if want C31 && [ -z "${C31_DONE:-}" ]; then
    C31_DONE=1
    DTB_TEST_R11="$HERE/../../docs/design/R11-hash-vectors.json" drv C31 shared "R11: hash vectors, snapshot, apply, refuse, delete"
  fi
  # brief P3c deliverable 6: the picker actions' arithmetic against darktable's own callbacks, compiled verbatim
  if want C25; then
    if timeout 900 "$BRIDGE_BUILD/tests/dtb_pickref" "$W-pickref" "$RAW" "$XMP" "$D" > "$D/C25.log" 2>&1; then
      pass C25 "$C" "picker actions vs darktable's callbacks: $(grep '^RESULT all' "$D/C25.log" | cut -d' ' -f4-)"
    else
      fail C25 "$C" "$(grep '^FAIL' "$D/C25.log" | head -5 | cut -c1-300 | tr '\n' ';') see $D/C25.log"
    fi
  fi
done

# C15 once: the engine on its own thread, the process's CPU set changing under it
if want C15; then
  if "$DTB" "$OUT/cpus-work" cpus > "$OUT/C15.log" 2>&1; then
    pass C15 all "$(grep '^RESULT' "$OUT/C15.log" | cut -d' ' -f2- | tr '\n' ';')"
  else
    fail C15 all "$(grep '^FAIL' "$OUT/C15.log" | head -3 | tr '\n' ';') see $OUT/C15.log"
  fi
  # point 3: started with one CPU allowed (GCC's libgomp on Linux counts the mask at load)
  if command -v taskset > /dev/null; then
    note "INFO C15 started with CPU 0 only (Linux, libgomp): $(taskset -c 0 "$DTB" "$OUT/cpus-work1" api "$TESTS/images/mire1.cr2" "$TESTS/0001-exposure/exposure.xmp" "$OUT" 2>/dev/null | grep '^RESULT engine darktable')"
  fi
fi

# C23 once: XMPs with masks made by desktop darktable (darktable-tests, on the
# cr2): listed and rendered through the bridge as darktable-cli renders them,
# and the XMP the bridge writes again renders the same
if want C23; then
  for X in 0004-masks/masks.xmp 0081-mask-groups/mask-groups.xmp 0090-mask-combine/mask-combine.xmp \
           0144-masks-combine-sum/masks-combine-sum.xmp; do
    N=$(dirname "$X"); D="$OUT/C23-$N"; mkdir -p "$D"
    if [ ! -f "$TESTS/$X" ]; then fail C23 "$N" "$X missing (fetch it from darktable-tests)"; continue; fi
    if "$DTB" "$OUT/C23-work" masks_desktop "$TESTS/images/mire1.cr2" "$TESTS/$X" "$D" > "$D/C23.log" 2>&1; then
      pass C23 "$N" "$(grep '^RESULT' "$D/C23.log" | grep -v 'RESULT engine\|RESULT exit' | cut -d' ' -f3- | tr '\n' ';')"
      cli "$TESTS/images/mire1.cr2" "$TESTS/$X" "$D/desktop_cli.png" "$D/cli.log"
      cmp_rec C23 "$N" "$D/desktop_masks_bridge.ppm" "$D/desktop_cli.png"
      cli "$TESTS/images/mire1.cr2" "$D/desktop_masks.xmp" "$D/bridge_xmp_cli.png" "$D/cli.log"
      cmp_rec C23 "$N/bridge-xmp" "$D/desktop_masks_bridge.ppm" "$D/bridge_xmp_cli.png"
    else
      fail C23 "$N" "$(grep '^FAIL' "$D/C23.log" | head -3 | tr '\n' ';') see $D/C23.log"
    fi
  done
fi

# C30: a data.db.lock left behind by a process Android ended. Its pid now
# belongs to another live process; darktable takes over the lock only when
# the pid is dead or (Linux) its command line lacks "darktable", so the
# foreign process is named darktable-stale here (on Android, /proc hides
# other apps and kill() says EPERM: the same verdict "alive"). The bridge
# removes the stale lock and the engine starts; an empty lock likewise.
if want C30; then
  D="$OUT/C30"; W30="$OUT/C30-work"; mkdir -p "$D" "$W30/config"
  X30="$TESTS/0001-exposure/exposure.xmp"
  "$DTB" "$W30" check "$X30" > "$D/first.log" 2>&1   # creates data.db
  bash -c 'exec -a darktable-stale sleep 60' & SP=$!
  sleep 0.3
  printf '%s\0' "$SP" > "$W30/config/data.db.lock"
  if "$DTB" "$W30" check "$X30" > "$D/stale-pid.log" 2>&1 && [ ! -f "$W30/config/data.db.lock" ]; then
    pass C30 live-pid "stale lock with a reused live pid ($SP) removed, engine started"
  else
    fail C30 live-pid "engine did not start over a stale lock: $(grep -m1 'FAIL\|dt_init' "$D/stale-pid.log") see $D/stale-pid.log"
  fi
  kill "$SP" 2>/dev/null
  : > "$W30/config/data.db.lock"
  if "$DTB" "$W30" check "$X30" > "$D/empty.log" 2>&1; then
    pass C30 empty "empty lock removed, engine started"
  else
    fail C30 empty "engine did not start over an empty lock, see $D/empty.log"
  fi
fi

md5_after=$(cd "$TESTS" && md5sum images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw)
if [ "$md5_before" = "$md5_after" ]; then note "PASS safety: RAW files unchanged"
else note "FAIL safety: a RAW file changed"; fi

grep -q '^FAIL' "$SUMMARY" && exit 1
exit 0

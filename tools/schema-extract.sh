#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Regenerate schema/ui-schema.json (brief S5).
#
#   tools/schema-extract.sh <darktable source> <darktable build (Ninja)> <darktable prefix> \
#                           <image> <work dir> [output json]
#
# Builds the extractor plugin (schema/extractor) against the given darktable,
# makes a private module directory (symlinks to the installed modules plus the
# plugin), runs the real darktable under a virtual display with the image in
# the darkroom, and merges the per-module output into one schema file.
# Nothing in the darktable source, build or prefix is written to.
#
# If darktable crashes inside a module, the module is recorded as crashed and
# the run continues with the remaining modules (the plugin writes the module it
# is working on to <work dir>/raw/progress).
#
# Environment passed through to the plugin: DT_SCHEMA_MAX_STATES, DT_SCHEMA_NO_SWEEP,
# DT_SCHEMA_ONLY.
set -euo pipefail

if [ $# -lt 5 ]; then
  sed -n '4,20p' "$0"
  exit 2
fi
DT_SRC=$(realpath "$1"); DT_BUILD=$(realpath "$2"); DT_PREFIX=$(realpath "$3")
IMAGE=$(realpath "$4"); WORK=$(realpath -m "$5")
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=${6:-$REPO/schema/ui-schema.json}

mkdir -p "$WORK"
echo "== build extractor"
cmake -S "$REPO/schema/extractor" -B "$WORK/ext-build" -G Ninja \
  -DDT_SOURCE_DIR="$DT_SRC" -DDT_BUILD_DIR="$DT_BUILD" -DDT_INSTALL_PREFIX="$DT_PREFIX" >"$WORK/cmake.log"
ninja -C "$WORK/ext-build" >"$WORK/ninja.log"

echo "== private module directory"
MODDIR="$WORK/moduledir"
rm -rf "$MODDIR"
LIBDIR="$DT_PREFIX/lib/darktable"
[ -d "$LIBDIR" ] || LIBDIR="$DT_PREFIX/lib64/darktable"
cp -as "$LIBDIR" "$MODDIR"
cp "$WORK/ext-build/libschema_extractor.so" "$MODDIR/plugins/lighttable/"

RAW="$WORK/raw"
rm -rf "$RAW"
mkdir -p "$RAW"
LOG="$WORK/darktable.log"
: >"$LOG"
skip=""
for attempt in $(seq 1 40); do
  rm -rf "$WORK/config" "$WORK/cache"
  mkdir -p "$WORK/config" "$WORK/cache"
  rm -f "$RAW/progress"
  echo "== darktable run $attempt${skip:+ (skipping $skip)}"
  DT_SCHEMA_OUT="$RAW" DT_SCHEMA_SKIP="$skip" LANG=C.UTF-8 LANGUAGE=C LC_ALL=C.UTF-8 \
    xvfb-run -a -s "-screen 0 1920x1080x24" \
    "$DT_PREFIX/bin/darktable" --moduledir "$MODDIR" --configdir "$WORK/config" \
      --cachedir "$WORK/cache" --library :memory: \
      --conf write_sidecar_files=never --conf ui/show_welcome_screen=false \
      "$IMAGE" >>"$LOG" 2>&1 &
  pid=$!
  # watchdog: once the extractor reports it is done, give darktable 60 s to
  # shut down by itself, then stop it (the output is already written)
  # also stop it if one module takes longer than MODULE_TIMEOUT seconds
  done_since=""
  rc=0
  last=""; last_change=$SECONDS
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    cur=$(cat "$RAW/progress" 2>/dev/null || true)
    if [ "$cur" != "$last" ]; then last=$cur; last_change=$SECONDS; fi
    if [ -n "$cur" ] && [ "$cur" != "__done__" ] && [ $((SECONDS - last_change)) -gt "${MODULE_TIMEOUT:-900}" ]; then
      echo "   module '$cur' took more than ${MODULE_TIMEOUT:-900} s; stopping darktable"
      pkill -KILL -P "$pid" 2>/dev/null || true
      kill -KILL "$pid" 2>/dev/null || true
    fi
    if [ "$cur" = "__done__" ]; then
      done_since=${done_since:-$SECONDS}
      if [ $((SECONDS - done_since)) -gt 60 ]; then
        echo "   darktable did not exit after finishing; stopping it"
        pkill -TERM -P "$pid" 2>/dev/null || true
        kill -TERM "$pid" 2>/dev/null || true
      fi
    fi
  done
  wait "$pid" || rc=$?
  progress=$(cat "$RAW/progress" 2>/dev/null || true)
  if [ "$progress" = "__done__" ]; then
    echo "   finished (exit $rc)"
    break
  fi
  if [ -z "$progress" ]; then
    echo "   darktable stopped before the extractor started (exit $rc); see $LOG" >&2
    exit 1
  fi
  echo "   darktable stopped in module '$progress' (exit $rc); recording it as crashed"
  printf '{"op":"%s","status":"crashed","exit_code":%d}\n' "$progress" "$rc" >"$RAW/$progress.json"
  skip="${skip:+$skip,}$progress"
done

echo "== merge"
python3 -I "$REPO/schema/extractor/merge.py" "$RAW" "$OUT"
echo "schema written to $OUT"

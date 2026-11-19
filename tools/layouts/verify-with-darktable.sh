#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Compare schema/builtin-layouts.json with what a real, unmodified darktable stores
# (brief P2d), and produce a .dtpreset file through darktable's own export code.
#
#   tools/layouts/verify-with-darktable.sh <darktable source> <darktable build (Ninja)> \
#       <darktable prefix> <work dir> [<layout file to export> [<.dtpreset to import>...]]
#
# Builds tools/layouts/probe against the given darktable, makes a private module
# directory (symlinks to the installed modules plus the probe), and runs darktable
# under a virtual display once per pixel workflow, each time with a fresh config and
# cache directory under <work dir> and an in-memory library. Nothing in the darktable
# source, build or prefix is written to. The probe reads darktable's presets table and
# quits. The last run also exports <layout file> (a layout string) as a user layout
# named "probe layout" with dt_presets_save_to_file, and imports the given .dtpreset
# files with dt_presets_import_from_file.
#
# Output: <work dir>/runs/<n>/ (presets.jsonl, darktable log), <work dir>/report.txt;
# exit 1 if any stored built-in layout differs from the data file.
set -euo pipefail
if [ $# -lt 4 ]; then sed -n '4,20p' "$0"; exit 2; fi
DT_SRC=$(realpath "$1"); DT_BUILD=$(realpath "$2"); DT_PREFIX=$(realpath "$3"); WORK=$(realpath -m "$4")
EXPORT_FILE=${5:+$(realpath "$5")}
shift $(( $# < 5 ? $# : 5 ))
IMPORTS=""
for f in "$@"; do IMPORTS="${IMPORTS:+$IMPORTS:}$(realpath "$f")"; done
REPO=$(cd "$(dirname "$0")/../.." && pwd)

mkdir -p "$WORK"
echo "== build probe"
cmake -S "$REPO/tools/layouts/probe" -B "$WORK/probe-build" -G Ninja \
  -DDT_SOURCE_DIR="$DT_SRC" -DDT_BUILD_DIR="$DT_BUILD" -DDT_INSTALL_PREFIX="$DT_PREFIX" >"$WORK/cmake.log"
ninja -C "$WORK/probe-build" >"$WORK/ninja.log"

MODDIR="$WORK/moduledir"
rm -rf "$MODDIR"
LIBDIR="$DT_PREFIX/lib/darktable"
[ -d "$LIBDIR" ] || LIBDIR="$DT_PREFIX/lib64/darktable"
cp -as "$LIBDIR" "$MODDIR"
cp "$WORK/probe-build/liblayout_probe.so" "$MODDIR/plugins/lighttable/"

mapfile -t WORKFLOWS < <(python3 -I -c 'import json,sys; [print(w) for w in json.load(open(sys.argv[1]))["workflows"]]' "$REPO/schema/builtin-layouts.json")
rm -rf "$WORK/runs"
n=0
for wf in "${WORKFLOWS[@]}"; do
  n=$((n + 1))
  R="$WORK/runs/$n"; mkdir -p "$R/config" "$R/cache" "$R/out"
  printf '%s\n' "$wf" > "$R/workflow.txt"
  extra=()
  if [ "$n" -eq "${#WORKFLOWS[@]}" ]; then
    [ -n "$EXPORT_FILE" ] && extra+=(DT_LAYOUT_PROBE_EXPORT="probe layout:$EXPORT_FILE")
    [ -n "$IMPORTS" ] && extra+=(DT_LAYOUT_PROBE_IMPORT="$IMPORTS")
  fi
  echo "== darktable run $n: workflow '$wf'"
  env DT_LAYOUT_PROBE_OUT="$R/out" "${extra[@]}" LANG=C.UTF-8 LANGUAGE=C LC_ALL=C.UTF-8 \
    timeout 300 xvfb-run -a -s "-screen 0 1280x800x24" \
    "$DT_PREFIX/bin/darktable" --moduledir "$MODDIR" --configdir "$R/config" --cachedir "$R/cache" \
      --library :memory: --conf ui/show_welcome_screen=false --conf write_sidecar_files=never \
      --conf "plugins/darkroom/workflow=$wf" >"$R/darktable.log" 2>&1 || true
  [ -f "$R/out/done" ] || { echo "darktable run $n did not finish; see $R/darktable.log" >&2; exit 1; }
done
python3 -I "$REPO/tools/layouts/compare-with-darktable.py" "$REPO/schema/builtin-layouts.json" "$WORK/runs" | tee "$WORK/report.txt"

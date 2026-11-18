#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Render every test in <tests dir> with darktable-cli and time each run.
# POSIX sh: runs on Linux and in Android's /system/bin/sh (adb shell).
#
#   run-renders.sh <engine root> <tests dir> <out dir> <threads>
#
# <engine root> has bin/darktable-cli, lib/darktable, share/darktable.
# <threads> is passed as darktable's -t (OpenMP threads); "all" = all cores.
# Writes <out dir>/<test>.pfm, <test>.log and results.tsv
# (test, threads, exit code, wall milliseconds).
# Options match darktable-tests' own driver (darktable-tests/run).
set -u
ROOT="$1"; TESTS="$2"; OUT="$3"; THREADS="$4"
mkdir -p "$OUT" "$OUT/home" "$OUT/config" "$OUT/cache" "$OUT/tmp"
export HOME="$OUT/home" TMPDIR="$OUT/tmp"
export XDG_CONFIG_HOME="$OUT/home/.config" XDG_CACHE_HOME="$OUT/home/.cache" XDG_DATA_HOME="$OUT/home/.local/share"
# the Android engine tree carries all its libraries; harmless on Linux
export LD_LIBRARY_PATH="$ROOT/lib:$ROOT/lib/darktable${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
NPROC=$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc)
[ "$THREADS" = all ] && THREADS=$NPROC
export OMP_THREAD_LIMIT="$THREADS"

# seconds with fraction; mksh (Android) has 32-bit shell arithmetic, so
# differences are computed with awk
now() {
  s=$(date +%s.%N 2>/dev/null)
  case "$s" in *N|"") date +%s ;; *) echo "$s" ;; esac
}

printf 'test\tthreads\texit\tms\n' > "$OUT/results.tsv"
for dir in "$TESTS"/*/; do
  t=$(basename "$dir")
  [ -f "$dir/test.xmp" ] || continue
  img="$TESTS/images/$(cat "$dir/image")"
  rm -f "$OUT/$t.pfm"
  start=$(now)
  "$ROOT/bin/darktable-cli" --width 2048 --height 2048 --hq true \
    --apply-custom-presets false "$img" "$dir/test.xmp" "$OUT/$t.pfm" \
    --core --disable-opencl \
    --configdir "$OUT/config" --cachedir "$OUT/cache" --library :memory: \
    --datadir "$ROOT/share/darktable" --moduledir "$ROOT/lib/darktable" \
    -t "$THREADS" \
    --conf host_memory_limit=8192 --conf resourcelevel=reference \
    --conf worker_threads=4 \
    --conf plugins/lighttable/export/force_lcms2=FALSE \
    --conf plugins/lighttable/export/pixel_interpolator=lanczos3 \
    --conf plugins/lighttable/export/pixel_interpolator_warp=bicubic \
    --conf plugins/lighttable/export/iccintent=0 \
    > "$OUT/$t.log" 2>&1
  rc=$?
  end=$(now)
  ms=$(awk "BEGIN{printf \"%d\", ($end - $start) * 1000}")
  printf '%s\t%s\t%s\t%s\n' "$t" "$THREADS" "$rc" "$ms" >> "$OUT/results.tsv"
  echo "$t threads=$THREADS exit=$rc ms=$ms"
done

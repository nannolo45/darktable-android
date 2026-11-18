#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Run the engine render tests on a connected Android device or emulator
# (adb) and pull the results (brief S1, workflow "emulator-test").
#
#   tools/engine-test/emulator-run.sh <engine-<abi>.tar.gz> <tests dir> <out dir>
#
# Renders every test twice: with 1 OpenMP thread and with all cores.
# Results: <out dir>/t1/ and <out dir>/tall/ (PFM, logs, results.tsv) and
# <out dir>/device.txt.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ARCHIVE="${1:?}"; TESTS="${2:?}"; OUT="${3:?}"
D=/data/local/tmp/dta
mkdir -p "$OUT"
adb wait-for-device
{
  echo "sdk: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "release: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "page_size: $(adb shell getconf PAGE_SIZE | tr -d '\r')"
  echo "cores: $(adb shell nproc | tr -d '\r')"
  adb shell cat /proc/meminfo | head -3 | tr -d '\r'
  adb shell uname -a | tr -d '\r'
} | tee "$OUT/device.txt"

adb shell "rm -rf $D && mkdir -p $D"
adb push "$ARCHIVE" "$D/engine.tar.gz" >/dev/null
adb shell "cd $D && tar -xzf engine.tar.gz && rm engine.tar.gz && ls"
ROOT=$(adb shell "ls -d $D/engine-*" | tr -d '\r' | head -1)
adb push "$TESTS" "$D/tests" >/dev/null
adb push "$HERE/run-renders.sh" "$D/run-renders.sh" >/dev/null
adb shell "LD_LIBRARY_PATH=$ROOT/lib:$ROOT/lib/darktable $ROOT/bin/darktable-cli --version" 2>&1 | tr -d '\r' | tee "$OUT/version.txt"

rc=0
for th in 1 all; do
  adb shell "sh $D/run-renders.sh $ROOT $D/tests $D/out-t$th $th" 2>&1 | tr -d '\r' | tee "$OUT/run-t$th.txt"
  mkdir -p "$OUT/t$th"
  adb shell "cd $D/out-t$th && ls *.pfm *.log results.tsv" 2>/dev/null | tr -d '\r' | while read -r f; do
    adb pull "$D/out-t$th/$f" "$OUT/t$th/$f" >/dev/null </dev/null
  done
  [ -s "$OUT/t$th/results.tsv" ] || rc=1
done
adb logcat -d -t 400 > "$OUT/logcat-tail.txt" 2>/dev/null || true
grep -E "F DEBUG|Fatal signal|libc  *:" "$OUT/logcat-tail.txt" > "$OUT/crashes.txt" || true
exit $rc

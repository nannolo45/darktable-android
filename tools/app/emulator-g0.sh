#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brief G0: install the x86_64 test APK and its instrumented test on a
# connected emulator (adb), push one public test RAW and its XMP, run the
# test (EngineOnDeviceTest) and pull its results.
#
#   tools/app/emulator-g0.sh <app apk> <test apk> <raw> <xmp> <out dir>
#
# <out dir> receives device.txt, instrument.txt (the test runner's output),
# g0-test/ (results.txt, start-report.txt, plugins.json, export.jpg,
# saved.xmp, benchmark.txt, log.txt), logcat.txt, crashes.txt and
# timings.txt. Exit status 0 only if every test passed.
set -uo pipefail
APK="${1:?}"; TAPK="${2:?}"; RAW="${3:?}"; XMP="${4:?}"; OUT="${5:?}"
PKG=app.dtandroid.g0   # application id of the G0 app since P2a (app/g0/build.gradle.kts)
D=/data/local/tmp/g0
mkdir -p "$OUT"
now() { date +%s.%N; }
el() { awk "BEGIN{printf \"%.1f\", $2 - $1}"; }

adb wait-for-device
{
  echo "sdk: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "release: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "page_size: $(adb shell getconf PAGE_SIZE | tr -d '\r')"
  echo "cores: $(adb shell nproc | tr -d '\r')"
  adb shell cat /proc/meminfo | head -3 | tr -d '\r'
} | tee "$OUT/device.txt"

t0=$(now)
adb install -r -t "$APK" 2>&1 | tail -1
adb install -r -t "$TAPK" 2>&1 | tail -1
t1=$(now)
adb shell "rm -rf $D && mkdir -p $D"
adb push "$RAW" "$D/$(basename "$RAW")" >/dev/null
adb push "$XMP" "$D/test.xmp" >/dev/null
adb logcat -c || true

t2=$(now)
adb shell am instrument -w -r \
  -e raw "$D/$(basename "$RAW")" -e xmp "$D/test.xmp" \
  -e class app.dtandroid.g0.EngineOnDeviceTest \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' > "$OUT/instrument.txt"
t3=$(now)
grep -E "INSTRUMENTATION_STATUS: (test|stream)=|INSTRUMENTATION_STATUS_CODE|INSTRUMENTATION_CODE|^OK |FAILURES|Tests run" "$OUT/instrument.txt" | tail -40

# results from the app's private files (debuggable build: run-as works)
mkdir -p "$OUT/g0-test"
for f in $(adb shell run-as $PKG ls files/g0-test 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as $PKG cat "files/g0-test/$f" > "$OUT/g0-test/$f"
done
adb shell run-as $PKG du -sk files/engine 2>/dev/null | tr -d '\r' > "$OUT/g0-test/unpacked-du-kb.txt"
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
grep -E " F DEBUG|Fatal signal|FATAL EXCEPTION| E AndroidRuntime" "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
{
  echo "install_s $(el "$t0" "$t1")"
  echo "instrumentation_s $(el "$t2" "$t3")"
} | tee "$OUT/timings.txt"

grep -q "^OK (4 tests)" "$OUT/instrument.txt"

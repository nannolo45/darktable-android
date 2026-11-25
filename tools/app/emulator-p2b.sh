#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brief P2b: install the x86_64 app on the real engine and its instrumented tests on a connected
# emulator (adb), push one public test RAW and its XMP, run the engine contract suite
# (JniEngineContractTest) and the scenario tests (RealEngineScenarioTest), and (brief P2e) the
# LightPhotoLibrary scenarios against the stub server (LplScenarioTest), pull the results.
#
#   tools/app/emulator-p2b.sh <app apk> <test apk> <raw> <xmp> <out dir> [<spektrafilm raw> <spektrafilm xmp>]
#
# Brief P2f: the optional pair is darktable-tests' spektrafilm case; P2fScenarioTest.f5 exports it
# (spektrafilm.jpg), and the workflow compares that with darktable-cli.
#
# <out dir> receives device.txt, instrument.txt, instrument-summary.txt, p2b-test/ (results.txt,
# export.jpg, app-edit.jpg, app-edit.xmp, timings.txt, log.txt), logcat.txt, crashes.txt,
# timings.txt. Exit status 0 only if every test passed.
set -uo pipefail
APK="${1:?}"; TAPK="${2:?}"; RAW="${3:?}"; XMP="${4:?}"; OUT="${5:?}"; SFRAW="${6:-}"; SFXMP="${7:-}"
PKG=app.dtandroid   # app/app-engine/build.gradle.kts (app.dtandroid.real before brief P2e)
D=/data/local/tmp/p2b
mkdir -p "$OUT"
now() { date +%s.%N; }
el() { awk "BEGIN{printf \"%.1f\", $2 - $1}"; }

adb wait-for-device
{
  echo "sdk: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "cores: $(adb shell nproc | tr -d '\r')"
  adb shell cat /proc/meminfo | head -2 | tr -d '\r'
} | tee "$OUT/device.txt"

t0=$(now)
adb install -r -t "$APK" 2>&1 | tail -1
adb install -r -t "$TAPK" 2>&1 | tail -1
t1=$(now)
adb shell "rm -rf $D && mkdir -p $D"
adb push "$RAW" "$D/$(basename "$RAW")" >/dev/null
adb push "$XMP" "$D/test.xmp" >/dev/null
SFARGS=()
if [ -n "$SFRAW" ] && [ -n "$SFXMP" ]; then
  adb push "$SFRAW" "$D/$(basename "$SFRAW")" >/dev/null
  adb push "$SFXMP" "$D/spektrafilm.xmp" >/dev/null
  SFARGS=(-e sfraw "$D/$(basename "$SFRAW")" -e sfxmp "$D/spektrafilm.xmp")
fi
adb logcat -c || true

t2=$(now)
# brief P2f: bounded (a test that waits for ever must not hold the job for hours; each test has its own limit too),
# and written line by line, so a cut-off run still shows which test was running
timeout 3000 adb shell am instrument -w -r \
  -e raw "$D/$(basename "$RAW")" -e xmp "$D/test.xmp" "${SFARGS[@]}" \
  -e class app.dtandroid.real.RealEngineScenarioTest,app.dtandroid.real.JniEngineContractTest,app.dtandroid.real.LplScenarioTest,app.dtandroid.real.P2fScenarioTest,app.dtandroid.real.P2jScenarioTest,app.dtandroid.real.P3aScenarioTest,app.dtandroid.real.P3bScenarioTest,app.dtandroid.real.P3cScenarioTest,app.dtandroid.real.P3dScenarioTest,app.dtandroid.real.P3dParametricScenarioTest,app.dtandroid.real.P3dEditScenarioTest,app.dtandroid.real.P3eScenarioTest,app.dtandroid.real.P3eBandsScenarioTest,app.dtandroid.real.P3fScenarioTest,app.dtandroid.real.P3gScenarioTest,app.dtandroid.real.P3hScenarioTest,app.dtandroid.real.P3iScenarioTest,app.dtandroid.real.P3jScenarioTest,app.dtandroid.real.P3kScenarioTest,app.dtandroid.real.P3lScenarioTest,app.dtandroid.real.P3mScenarioTest,app.dtandroid.real.P3nScenarioTest \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | sed -u 's/\r$//' > "$OUT/instrument.txt"
t3=$(now)
python3 -I "$(dirname "$0")/instrument-summary.py" "$OUT/instrument.txt" > "$OUT/instrument-summary.txt" 2>&1 || true
cat "$OUT/instrument-summary.txt"
grep -E "^OK |FAILURES|Tests run" "$OUT/instrument.txt" | tail -5

mkdir -p "$OUT/p2b-test"
for f in $(adb shell run-as $PKG ls files/p2b-test 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as $PKG cat "files/p2b-test/$f" > "$OUT/p2b-test/$f"
done
adb shell run-as $PKG du -sk files/engine 2>/dev/null | tr -d '\r' > "$OUT/p2b-test/unpacked-du-kb.txt"
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
# brief P2f: the app's own lines without darktable's per-module "-d perf" lines, which fill the log
grep -vE "dev_pixelpipe\]|DTB_TIMING" "$OUT/logcat.txt" | grep -E " (dt-android|dt-android-test|darktable|TestRunner|AndroidRuntime|DEBUG|libc) *:" > "$OUT/logcat-app.txt" || true
grep -E " F DEBUG|Fatal signal|FATAL EXCEPTION| E AndroidRuntime| E dt-android" "$OUT/logcat.txt" > "$OUT/crashes.txt" || true
{
  echo "install_s $(el "$t0" "$t1")"
  echo "instrumentation_s $(el "$t2" "$t3")"
} | tee "$OUT/timings.txt"

grep -q "^OK (" "$OUT/instrument.txt"

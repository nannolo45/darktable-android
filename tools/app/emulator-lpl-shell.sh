#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brief P2c: install the LightPhotoLibrary shell and its instrumented tests on a
# connected emulator (adb), run every test (they start their own stub server
# inside the emulator), pull the screenshots the tests take, then install the
# darktable app's other APKs next to it, if given, to show they coexist.
#
#   tools/app/emulator-lpl-shell.sh <shell apk> <test apk> <out dir> [other apk ...]
#
# <out dir> receives device.txt, instrument.txt (raw runner output),
# results.txt (one line per test), shots/, logcat.txt, crashes.txt,
# coexist.txt. Exit status 0 only if every test passed.
set -uo pipefail
APK="${1:?}"; TAPK="${2:?}"; OUT="${3:?}"; shift 3
PKG=app.dtandroid.lplshell
mkdir -p "$OUT"

adb wait-for-device
{
  echo "sdk: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "release: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "webview: $(adb shell dumpsys webviewupdate | grep -m1 'Current WebView package' | tr -d '\r')"
} | tee "$OUT/device.txt"

adb install -r -t "$APK" 2>&1 | tail -1
adb install -r -t "$TAPK" 2>&1 | tail -1
adb logcat -c || true

adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' > "$OUT/instrument.txt"
python3 -I "$(dirname "$0")/instrument-summary.py" "$OUT/instrument.txt" | tee "$OUT/results.txt"

mkdir -p "$OUT/shots"
adb pull "/sdcard/Android/data/$PKG/files/shots/." "$OUT/shots/" > /dev/null 2>&1 || true
ls "$OUT/shots"
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "Fatal signal|FATAL EXCEPTION| E AndroidRuntime" "$OUT/logcat.txt" > "$OUT/crashes.txt" || true

# the shell next to the darktable app's other builds: different application ids, all installed
{
  for a in "$@"; do
    echo "install $(basename "$a"): $(adb install -r -t "$a" 2>&1 | tail -1 | tr -d '\r')"
  done
  echo "installed packages:"
  adb shell pm list packages app.dtandroid | tr -d '\r' | sort | sed 's/^/  /'
  adb shell am start -W -n "$PKG/.ShellActivity" 2>&1 | tr -d '\r' | grep -E "Status|Activity" | sed 's/^/  shell start: /'
} | tee "$OUT/coexist.txt"

grep -q "^RESULT: OK" "$OUT/results.txt"

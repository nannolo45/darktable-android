#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Brief P5a, test C40: called by `dtb_test modaudit` after each module it switched on.
#   p5a_hook.sh <op> <xmp> <bridge ppm>
# Renders the XMP with darktable-cli (same options as run_tests.sh's cli()), compares it with the bridge's
# picture (compare.py, the suite's default threshold: max difference 1), appends one line to $P5A_RES:
#   <op> <status> <compare.py output>     status 0 = same, 1 = differs, 2 = darktable-cli wrote no picture
# and deletes both pictures. Environment from run_tests.sh: P5A_CLI P5A_RAW P5A_RES P5A_CMP P5A_LOG
# P5A_CONFIG P5A_CACHE P5A_LEVEL.
op=$1; xmp=$2; ppm=$3
png="${ppm%_bridge.ppm}_cli.png"
rm -f "$png"
t0=$(date +%s)
"$P5A_CLI" "$P5A_RAW" "$xmp" "$png" --core --disable-opencl --configdir "$P5A_CONFIG" --cachedir "$P5A_CACHE" $P5A_LEVEL \
  >> "$P5A_LOG" 2>&1
t1=$(date +%s)
if [ -f "$png" ]; then
  r=$(python3 -I "$P5A_CMP" "$ppm" "$png" 2>&1); s=$?
else
  r="darktable-cli wrote no picture (see $(basename "$P5A_LOG"))"; s=2
fi
echo "$op $s $r cli_seconds $((t1 - t0))" >> "$P5A_RES"
rm -f "$ppm" "$png"
exit 0

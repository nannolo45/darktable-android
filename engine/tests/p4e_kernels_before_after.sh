#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Brief P4e: the same edits rendered through OpenCL (PoCL on the CI runner) with darktable's kernels as
# patched by patches/ and as upstream ships them, compared picture against picture (PSNR, largest
# difference of 255 on the 1024 px preview). Also each OpenCL render against the CPU render (C36's check).
#   engine/tests/p4e_kernels_before_after.sh <bridge build> <patched darktable prefix> <test images dir> <out dir>
# The "before" prefix is the patched one with share/darktable copied and patches/ reversed on its kernels.
# Exit status 1 if a render fails, an OpenCL render did not run on the device, or before and after differ by
# more than 1 of 255 in any channel (expected: equal; the patch keeps the order of every sum).
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
BB="$(cd "${1:?bridge build}" && pwd)"; P="$(cd "${2:?prefix}" && pwd)"; T="${3:?test images}"; OUT="${4:?out dir}"
DTB="$BB/tests/dtb_test"
rm -rf "$OUT"; mkdir -p "$OUT"
U="$OUT/prefix-upstream-kernels"
mkdir -p "$U/share"
for e in "$P"/*; do [ "$(basename "$e")" = share ] || ln -s "$e" "$U/"; done
for e in "$P"/share/*; do [ "$(basename "$e")" = darktable ] || ln -s "$e" "$U/share/"; done
cp -a "$P/share/darktable" "$U/share/"
for p in $(ls "$REPO"/patches/*.patch | sort -r); do
  patch -R -p3 -d "$U/share/darktable/kernels" --no-backup-if-mismatch < "$p" || { echo "FAIL cannot reverse $p"; exit 1; }
done
diff -r "$P/share/darktable/kernels" "$U/share/darktable/kernels" > "$OUT/kernels.diff"
[ -s "$OUT/kernels.diff" ] || { echo "FAIL the two kernel folders are equal"; exit 1; }

# edit list: raw, xmp, name (heavy edits first)
EDITS="images/hlrecovery.arw 0179-spektrafilm/spektrafilm.xmp spektrafilm
images/mire1.cr2 0081-mask-groups/mask-groups.xmp mask-groups
images/mire1.cr2 0001-exposure/exposure.xmp exposure"
rc=0
{
  echo "# P4e: OpenCL renders with patched vs upstream kernels (PoCL, x86_64 Linux CI runner, not phone numbers)"
  echo "| edit | after vs before: PSNR dB | largest diff /255 | after vs CPU | before vs CPU |"
  echo "|---|---|---|---|---|"
} > "$OUT/summary.md"
while read -r raw xmp name; do
  for k in after before; do
    pre=$P; [ $k = before ] && pre=$U
    d="$OUT/$name/$k"; mkdir -p "$d"
    DT_PREFIX="$pre" DTB_DT_DEBUG=opencl DTB_TEST_SAVE_PPM=1 timeout 2400 "$DTB" "$d/work" opencl "$T/$raw" "$T/$xmp" "$d" > "$d/run.log" 2>&1
    r=$?
    grep -q '^RESULT opencl last gpu' "$d/run.log" || { echo "FAIL $name $k: exit $r, no OpenCL render (see $d/run.log)"; rc=1; }
  done
  line=$(python3 -I - "$OUT/$name/after/p4b_gpu.ppm" "$OUT/$name/before/p4b_gpu.ppm" <<'EOF'
import math, sys
def rd(p):
    b = open(p, "rb").read()
    parts = b.split(maxsplit=4)
    return int(parts[1]), int(parts[2]), parts[4]
try:
    wa, ha, a = rd(sys.argv[1]); wb, hb, b = rd(sys.argv[2])
except Exception as e:
    print(f"missing {e}"); sys.exit(1)
if (wa, ha) != (wb, hb): print("sizes differ"); sys.exit(1)
mx = max(abs(x - y) for x, y in zip(a, b)); se = sum((x - y) ** 2 for x, y in zip(a, b))
psnr = "inf" if se == 0 else f"{10 * math.log10(255 * 255 * len(a) / se):.2f}"
print(f"{psnr} | {mx}"); sys.exit(0 if mx <= 1 else 1)
EOF
)
  [ $? = 0 ] || { echo "FAIL $name: before and after differ: $line"; rc=1; }
  va=$(grep '^RESULT opencl last' "$OUT/$name/after/run.log" | awk '{print $6 " dB, " $8}')
  vb=$(grep '^RESULT opencl last' "$OUT/$name/before/run.log" | awk '{print $6 " dB, " $8}')
  echo "| $name | $line | $va | $vb |" >> "$OUT/summary.md"
done <<< "$EDITS"
cat "$OUT/summary.md"
exit $rc

#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# P1b deliverable 4: rebuild only darktable's sigmoid plugin with other compilers or flags, swap it into a copy
# of the Linux build's module folder, and render darktable-tests' 0097-sigmoid case and the app's default edit
# of mire1.cr2 with darktable-cli; compare float output with the unchanged gcc build.
#   sigmoid-compilers.sh <darktable build dir (Ninja)> <install prefix> <dtt dir with images/mire1.cr2> \
#                        <0097-sigmoid xmp> <default-edit xmp> <work dir>
# Needs clang-18 and libomp-18-dev for the clang variants.
set -euo pipefail
B=$1; P=$2; T=$3; X0097=$4; XDE=$5; W=$6
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$W"
C=$(ninja -C "$B" -t commands lib/darktable/plugins/libsigmoid.so | grep "introspection_sigmoid.c.o -c")
L=$(ninja -C "$B" -t commands lib/darktable/plugins/libsigmoid.so | tail -1)
build() { # name compiler "drop flags" "add flags"
  python3 -I - "$1" "$2" "$3" "$4" "$C" "$L" "$W" <<'PY'
import sys, shlex, subprocess, os
n, cc, drop, add, c, l, w = sys.argv[1:8]
drop = set(drop.split()); add = add.split()
a = shlex.split(c.split('&&')[-1]); a[0] = cc
a = [x for x in a if x not in drop and not x.startswith('-W')]
i = a.index('-o'); a[i+1] = f'{w}/{n}.o'; j = a.index('-c'); a[j:j] = add
b = shlex.split(l.split('&&')[1]); b[0] = cc
b = [x for x in b if x not in drop and not x.startswith('-W') and x != '-fexpensive-optimizations']
k = b.index('-o'); os.makedirs(f'{w}/{n}', exist_ok=True); b[k+1] = f'{w}/{n}/libsigmoid.so'
b = [f'{w}/{n}.o' if x.endswith('introspection_sigmoid.c.o') else x for x in b]
if cc.startswith('clang'):
    b = [x.replace('/usr/lib/gcc/x86_64-linux-gnu/13/libgomp.so', '/usr/lib/llvm-18/lib/libomp.so') for x in b]
subprocess.run(a, check=True)
subprocess.run(b, check=True)
PY
}
cd "$B"
build gcc_ref /usr/bin/cc "" ""
build gcc_nofast /usr/bin/cc "-ffast-math -fno-finite-math-only" ""
build gcc_noerrno_unsafe /usr/bin/cc "-ffast-math -fno-finite-math-only" "-fno-math-errno -funsafe-math-optimizations"
build gcc_unsafe_only /usr/bin/cc "-ffast-math -fno-finite-math-only" "-funsafe-math-optimizations"
build clang_fast clang-18 "-fexpensive-optimizations" "-fopenmp=libomp"
build clang_nofast clang-18 "-fexpensive-optimizations -ffast-math -fno-finite-math-only" "-fopenmp=libomp"
for v in gcc_ref gcc_nofast gcc_noerrno_unsafe gcc_unsafe_only clang_fast clang_nofast; do
  rm -rf "$W/mod-$v"; cp -rs "$P/lib/darktable" "$W/mod-$v"; rm "$W/mod-$v/plugins/libsigmoid.so"
  cp "$W/$v/libsigmoid.so" "$W/mod-$v/plugins/"
  for t in 0097:$X0097 de:$XDE; do
    rm -f "$W/$v-${t%%:*}.pfm"
    "$P/bin/darktable-cli" "$T/images/mire1.cr2" "${t#*:}" "$W/$v-${t%%:*}.pfm" --width 2048 --height 2048 --hq true \
      --apply-custom-presets false --core --configdir "$W/conf-$v" --cachedir "$W/cache" --moduledir "$W/mod-$v" \
      --conf resourcelevel=reference > /dev/null 2>&1
  done
  echo "$v: calls in the curve fit: $(objdump -d --no-show-raw-insn "$W/$v/libsigmoid.so" | awk '/^[0-9a-f]+ <.*>:/{f=$2} /call.*(expf|powf)@/{print f"->"$NF}' | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
done
for v in gcc_nofast gcc_noerrno_unsafe gcc_unsafe_only clang_fast clang_nofast; do
  for n in 0097 de; do echo "$v vs gcc_ref $n: $(python3 -I "$HERE/pfmcmp.py" "$W/gcc_ref-$n.pfm" "$W/$v-$n.pfm")"; done
done

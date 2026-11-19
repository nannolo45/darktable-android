#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Print the shared libraries of an engine tree in load order, dependencies
# first (brief G0). An Android app cannot add its own storage to the dynamic
# linker's search path, so it loads these by absolute path in this order;
# every later NEEDED entry, including those of darktable's plugins, is then
# found by soname among the libraries already loaded.
#
#   tools/engine-load-order.sh <engine root> [readelf]
#
# Covers lib/*.so* and lib/darktable/libdarktable.so. Output: one path per
# line, relative to <engine root>. Fails on a dependency cycle.
set -euo pipefail
ROOT="${1:?usage: engine-load-order.sh <engine root> [readelf]}"
READELF="${2:-readelf}"
cd "$ROOT"
declare -A PATH_OF=() STATE=()
for f in lib/*.so lib/*.so.* lib/darktable/libdarktable.so; do
  [ -f "$f" ] && PATH_OF[$(basename "$f")]="$f"
done
visit() {
  local n="$1" d
  case "${STATE[$n]:-}" in
    done) return 0 ;;
    active) echo "dependency cycle at $n" >&2; exit 1 ;;
  esac
  STATE[$n]=active
  for d in $("$READELF" -dW "${PATH_OF[$n]}" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort); do
    [ -n "${PATH_OF[$d]:-}" ] && visit "$d"
  done
  STATE[$n]=done
  echo "${PATH_OF[$n]}"
}
for n in $(printf '%s\n' "${!PATH_OF[@]}" | sort); do visit "$n"; done

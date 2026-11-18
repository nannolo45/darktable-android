#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build every library in deps/packages.list for one Android ABI into
# $DTA_PREFIX_BASE/sysroot-<abi> (brief S1, deliverable 1).
#
#   deps/build-sysroot.sh <arm64-v8a|x86_64> [package ...]
#
# Environment:
#   ANDROID_NDK_HOME  NDK r30 (see deps/config.sh; deps/host-setup.sh installs it)
#   DTA_WORK          scratch dir (default ./_dta-work)
#   DTA_PKGCACHE      per-package archive cache (default $DTA_WORK/pkgcache)
#   DTA_LOGDIR        logs and summary (default $DTA_WORK/logs/<abi>)
#
# Each package is installed with DESTDIR into a stage, archived as
# <name>-<stamp>.tar.gz in DTA_PKGCACHE and unpacked into the sysroot. The
# stamp hashes the recipe, its patches, the shared helpers and the stamps of
# its dependencies, so a cached archive is reused only when nothing it was
# built from changed. A failing package does not stop the run: packages that
# depend on it are skipped, all others are still attempted. The exit status is
# non-zero if any package failed or was skipped.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib/common.sh"

ABI="${1:?usage: build-sysroot.sh <abi> [package ...]}"; shift
ONLY=("$@")
dta_setup_env "$ABI"
: "${DTA_PKGCACHE:=$DTA_WORK/pkgcache}"
: "${DTA_LOGDIR:=$DTA_WORK/logs/$ABI}"
mkdir -p "$DTA_PKGCACHE/$ABI" "$DTA_LOGDIR" "$DTA_PREFIX"
[ -w "$DTA_PREFIX" ] || dta_die "$DTA_PREFIX not writable"

SUMMARY="$DTA_LOGDIR/summary.tsv"
printf 'package\tversion\tstatus\tseconds\tso_files\tso_bytes_stripped\tnot_16k_aligned\tlicense\n' > "$SUMMARY"

declare -A STAMP STATUS
COMMON_HASH=$(cat "$HERE/lib/common.sh" "$HERE/config.sh" "$0" | sha256sum | cut -c1-16)
TOOLCHAIN_ID="ndk-$DTA_NDK_VERSION-api$DTA_API-meson$(meson --version 2>/dev/null)-cmake$(cmake --version | head -1 | awk '{print $3}')"

recipe_field() { # recipe_field <file> <VAR>
  ( unset NAME VERSION DEPENDS LICENSE; . "$1"; eval "printf '%s' \"\${$2:-}\"" )
}

size_report() { # <stage root> -> "<count>\t<bytes>\t<not aligned>"
  local root="$1" n=0 bytes=0 bad=0 f tmp
  tmp=$(mktemp)
  while IFS= read -r f; do
    n=$((n+1))
    cp "$f" "$tmp"; "$STRIP" --strip-unneeded "$tmp" 2>/dev/null || true
    bytes=$((bytes + $(stat -c %s "$tmp")))
    # every PT_LOAD segment must be aligned to at least 16 KB (0x4000)
    local a misaligned=0
    for a in $("$READELF" -lW "$f" | awk '$1=="LOAD"{print $NF}'); do
      [ $((a)) -lt 16384 ] && misaligned=1
    done
    if [ $misaligned = 1 ]; then
      bad=$((bad+1)); echo "not 16k aligned: ${f#"$root"/}" >> "$DTA_LOGDIR/alignment.txt"
    fi
  done < <(find "$root" -type f \( -name '*.so' -o -name '*.so.*' \) 2>/dev/null)
  rm -f "$tmp"
  printf '%s\t%s\t%s' "$n" "$bytes" "$bad"
}

build_one() { # runs in a subshell; stdout/stderr go to the package log
  set -e
  local recipe="$1"
  unset NAME VERSION URL SHA256 FILE GIT_URL GIT_COMMIT DEPENDS LICENSE CMAKE_SRC CONFIGURE
  . "$recipe"
  dta_setup_env "$ABI"            # re-read: flags depend on what is installed
  STAGE="$DTA_WORK/stage/$ABI/$NAME"
  rm -rf "$STAGE"; mkdir -p "$STAGE"
  dta_fetch
  cd "$SRC"
  build
  dta_tidy_stage
}

failed=0
mapfile -t PKGS < <(grep -v '^\s*#' "$HERE/packages.list" | awk 'NF{print $1}')
for name in "${PKGS[@]}"; do
  recipe="$HERE/recipes/$name.sh"
  [ -f "$recipe" ] || dta_die "missing recipe $recipe"
  version=$(recipe_field "$recipe" VERSION)
  license=$(recipe_field "$recipe" LICENSE)
  deps=$(recipe_field "$recipe" DEPENDS)
  # stamp
  h=$( { echo "$COMMON_HASH $TOOLCHAIN_ID $ABI"; cat "$recipe";
         cat "$HERE/recipes/$name"/*.patch 2>/dev/null || true;
         for d in $deps; do echo "$d=${STAMP[$d]:-missing}"; done; } | sha256sum | cut -c1-16)
  STAMP[$name]=$h
  if [ ${#ONLY[@]} -gt 0 ] && [[ ! " ${ONLY[*]} " =~ " $name " ]]; then
    continue
  fi
  blocked=""
  for d in $deps; do
    case "${STATUS[$d]:-}" in ok|cached) ;; *) blocked="$blocked $d" ;; esac
  done
  log="$DTA_LOGDIR/$name.log"
  pkg="$DTA_PKGCACHE/$ABI/$name-$h.tar.gz"
  start=$(date +%s)
  if [ -n "$blocked" ] && [ ${#ONLY[@]} -eq 0 ]; then
    STATUS[$name]="skipped(needs:${blocked# })"
    echo "skipped: dependency not built:$blocked" > "$log"
  elif [ -s "$pkg" ]; then
    tar -xzf "$pkg" -C "$DTA_PREFIX"
    STATUS[$name]=cached
  else
    dta_log "building $name $version ($ABI)"
    # not inside "if": errexit is ignored in a subshell that is part of a condition
    ( build_one "$recipe" ) > "$log" 2>&1
    rc=$?
    stage="$DTA_WORK/stage/$ABI/$name$DTA_PREFIX"
    if [ $rc = 0 ] && [ -z "$(find "$stage" -type f 2>/dev/null | head -1)" ]; then
      echo "recipe finished but installed nothing" >> "$log"; rc=1
    fi
    if [ $rc = 0 ]; then
      tar -czf "$pkg.part" -C "$stage" . && mv "$pkg.part" "$pkg"
      tar -xzf "$pkg" -C "$DTA_PREFIX"
      STATUS[$name]=ok
    else
      STATUS[$name]=failed
      dta_log "FAILED $name, see $log"
      tail -n 40 "$log" >&2
    fi
  fi
  secs=$(( $(date +%s) - start ))
  sizes="-\t-\t-"
  if [ -s "$pkg" ]; then
    tmpd=$(mktemp -d); tar -xzf "$pkg" -C "$tmpd"
    sizes=$(size_report "$tmpd"); rm -rf "$tmpd"
    tar -tzf "$pkg" | sed 's|^\./||' | grep -v '/$' > "$DTA_LOGDIR/$name.files" || true
  fi
  printf '%s\t%s\t%s\t%s\t%b\t%s\n' "$name" "$version" "${STATUS[$name]}" "$secs" "$sizes" "$license" >> "$SUMMARY"
  case "${STATUS[$name]}" in ok|cached) ;; *) failed=$((failed+1)) ;; esac
done

# keep only the archives used by this run, so the cache does not grow forever
for f in "$DTA_PKGCACHE/$ABI"/*.tar.gz; do
  [ -e "$f" ] || continue
  b=$(basename "$f" .tar.gz); n=${b%-*}; s=${b##*-}
  [ "${STAMP[$n]:-}" = "$s" ] || rm -f "$f"
done

cat "$SUMMARY" >&2
if [ $failed -gt 0 ]; then
  dta_log "$failed package(s) failed or were skipped"
  exit 1
fi
dta_log "sysroot complete: $DTA_PREFIX"

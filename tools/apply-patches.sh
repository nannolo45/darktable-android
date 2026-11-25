#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Apply the patch series patches/*.patch, in name order, to a darktable source tree (PLAN.md 5).
# Every patch must apply exactly (no fuzz); a patch that no longer applies stops the build.
#   tools/apply-patches.sh <darktable source dir> [--check]
#   --check: only test that every patch applies (tools/bump-upstream), change nothing
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$(cd "${1:?usage: apply-patches.sh <darktable dir> [--check]}" && pwd)"
DRY=; [ "${2:-}" = --check ] && DRY=--dry-run
for p in "$REPO"/patches/*.patch; do
  [ -e "$p" ] || continue
  echo "patch: $(basename "$p")"
  patch -p1 -d "$DEST" --forward --fuzz=0 --no-backup-if-mismatch $DRY < "$p"
done

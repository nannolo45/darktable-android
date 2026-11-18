#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Print the release tags used by the workflows, as KEY=value lines:
#   DEPS_TAG    deps-<12 hex of the git tree of deps/>   (sysroot archives)
#   ENGINE_TAG  engine-<darktable 8>-<12 hex of deps/ + tools/ trees>
# The trees are taken from the committed HEAD, so the tag says exactly which
# recipes a release file was built from.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
. "$REPO/tools/darktable-pin.sh"
DT=$DTA_DARKTABLE_COMMIT
if [ -f "$REPO/upstream/darktable/CMakeLists.txt" ]; then DT=$(git -C "$REPO/upstream/darktable" rev-parse HEAD); fi
D=$(git -C "$REPO" rev-parse HEAD:deps)
T=$(git -C "$REPO" rev-parse HEAD:tools)
E=$(printf '%s %s %s' "$D" "$T" "$DT" | sha256sum | cut -c1-12)
echo "DEPS_TAG=deps-${D:0:12}"
echo "ENGINE_TAG=engine-${DT:0:8}-$E"
echo "DT_COMMIT=$DT"

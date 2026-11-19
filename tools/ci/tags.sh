#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Print the release tags used by the workflows, as KEY=value lines:
#   DEPS_TAG    deps-<12 hex of the git tree of deps/>   (sysroot archives)
#   ENGINE_TAG  engine-<darktable 8>-<12 hex of deps/ + tools/ + engine/ trees>
#   APP_TAG     g0-app-<12 hex of the commit>                (test APKs, brief G0)
#   DT_COMMIT   the darktable commit pinned by the upstream/darktable submodule
# The trees are taken from the committed HEAD, so the tag says exactly which
# recipes a release file was built from.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
. "$REPO/tools/darktable-pin.sh"
DT=$DTA_DARKTABLE_COMMIT
D=$(git -C "$REPO" rev-parse HEAD:deps)
T=$(git -C "$REPO" rev-parse HEAD:tools)
B=$(git -C "$REPO" rev-parse HEAD:engine)
# the engine archive also carries the bridge (engine/, brief G0)
E=$(printf '%s %s %s %s' "$D" "$T" "$DT" "$B" | sha256sum | cut -c1-12)
echo "DEPS_TAG=deps-${D:0:12}"
echo "ENGINE_TAG=engine-${DT:0:8}-$E"
echo "APP_TAG=g0-app-$(git -C "$REPO" rev-parse HEAD | cut -c1-12)"
echo "DT_COMMIT=$DT"

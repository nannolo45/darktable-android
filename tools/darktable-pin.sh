# SPDX-License-Identifier: GPL-3.0-or-later
# The darktable commit the engine is built from. The single source of truth is
# the git submodule upstream/darktable (its commit in this repository's HEAD):
# this file only reads it, so moving the submodule moves every build.
# Sourced by tools/fetch-darktable.sh and tools/ci/tags.sh. Works without the
# submodule being checked out (the commit is part of the parent's tree).
_dta_pin_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DTA_DARKTABLE_REPO=$(git -C "$_dta_pin_repo" config -f .gitmodules submodule.upstream/darktable.url)
DTA_DARKTABLE_COMMIT=$(git -C "$_dta_pin_repo" rev-parse HEAD:upstream/darktable)
if [ -z "$DTA_DARKTABLE_REPO" ] || [ "${#DTA_DARKTABLE_COMMIT}" != 40 ]; then
  echo "darktable-pin.sh: cannot read the upstream/darktable submodule from $_dta_pin_repo" >&2
  exit 1
fi
unset _dta_pin_repo

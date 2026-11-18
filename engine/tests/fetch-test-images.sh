#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetch darktable's public integration test set (RAW files, XMP sidecars):
#   engine/tests/fetch-test-images.sh <directory outside the repository>
# Nothing from it is committed here.
set -eu
DEST=${1:?destination}
if [ ! -d "$DEST/.git" ]; then
  git clone --depth 1 https://github.com/darktable-org/darktable-tests.git "$DEST"
fi
git -C "$DEST" log -1 --format='darktable-tests at %h (%cs)'

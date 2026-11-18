#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Copy a folder of logs to the ci-logs branch as runs/<run id>-a<attempt>/<name>/
# (brief S1: the build workspace cannot read Actions logs, so every workflow
# leaves its key logs here, also when it fails).
#
#   GITHUB_TOKEN=... tools/ci/push-ci-logs.sh <dir> <name> <status>
#
# Uses only the workflow's GITHUB_TOKEN (contents: write). Files over 512 KB
# are gzipped; the token is passed as an HTTP header, never written to disk.
set -uo pipefail
SRC="${1:?}"; NAME="${2:?}"; STATUS="${3:-unknown}"
: "${GITHUB_TOKEN:?}" "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}"
RUNDIR="runs/${GITHUB_RUN_ID}-a${GITHUB_RUN_ATTEMPT:-1}"
W=$(mktemp -d)
AUTH="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 -w0)"
URL="https://github.com/${GITHUB_REPOSITORY}.git"
g() { git -C "$W" -c "http.https://github.com/.extraheader=$AUTH" "$@"; }

git init -q "$W"
git -C "$W" config user.name "ci-logs bot"
git -C "$W" config user.email "ci-logs@users.noreply.github.com"
if g fetch -q --depth 1 "$URL" ci-logs 2>/dev/null; then
  git -C "$W" checkout -q -B ci-logs FETCH_HEAD
else
  git -C "$W" checkout -q --orphan ci-logs
  printf '# CI logs\n\nOne folder per workflow run: runs/<run id>-a<attempt>/<job>/. Written by tools/ci/push-ci-logs.sh.\n' > "$W/README.md"
  git -C "$W" add README.md
fi
D="$W/$RUNDIR/$NAME"
mkdir -p "$D"
cp -a "$SRC"/. "$D"/ 2>/dev/null || true
find "$D" -type f -size +512k ! -name '*.gz' -exec gzip -9 {} \;
{
  echo "workflow: ${GITHUB_WORKFLOW:-}"
  echo "job: $NAME"
  echo "status: $STATUS"
  echo "commit: ${GITHUB_SHA:-}"
  echo "ref: ${GITHUB_REF_NAME:-}"
  echo "run: ${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
  echo "finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$D/meta.txt"
git -C "$W" add -A
git -C "$W" commit -q -m "logs: run ${GITHUB_RUN_ID} ${NAME} (${STATUS})"
for i in 1 2 3 4 5 6; do
  if g push -q "$URL" ci-logs:ci-logs; then echo "pushed $RUNDIR/$NAME"; exit 0; fi
  sleep $((i * 3 + RANDOM % 5))
  g fetch -q --depth 1 "$URL" ci-logs && git -C "$W" rebase -q FETCH_HEAD || true
done
echo "could not push ci logs" >&2
exit 0

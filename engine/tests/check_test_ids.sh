#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brief P5c: checks run_tests.sh against the registry TEST-IDS.md. Fails when
#  - an id is used in run_tests.sh (want, drv, pass, fail, cmp_rec, parity) and is not in the registry,
#  - an id appears twice in the registry,
#  - a registry id is not used by any test,
#  - a test block with the same id appears twice (T6 and C6 share one block and may appear twice).
#   engine/tests/check_test_ids.sh        (run first by run_tests.sh; no arguments)
HERE=$(cd "$(dirname "$0")" && pwd)
REG="$HERE/TEST-IDS.md"
RUN="$HERE/run_tests.sh"
TMP=$(mktemp -d) || exit 1
trap 'rm -rf "$TMP"' EXIT
rc=0
grep -E '^\| [CTX][0-9]+[a-z]? \|' "$REG" | awk -F'|' '{gsub(/ /,"",$2); print $2}' > "$TMP/reg"
# code lines only: comment lines are not uses
grep -vE '^[[:space:]]*#' "$RUN" > "$TMP/code"
grep -oE '(want|drv|pass|fail|cmp_rec|parity) [CTX][0-9]+[a-z]?([^0-9a-z]|$)' "$TMP/code" | awk '{sub(/[^0-9a-z]$/,"",$2); print $2}' > "$TMP/used"
grep -oE 'want [CTX][0-9]+[a-z]?([^0-9a-z]|$)' "$TMP/code" | awk '{sub(/[^0-9a-z]$/,"",$2); print $2}' > "$TMP/wants"
[ -s "$TMP/reg" ] || { echo "check_test_ids: no ids found in $REG"; exit 1; }
for id in $(sort "$TMP/reg" | uniq -d); do echo "check_test_ids: $id appears twice in TEST-IDS.md"; rc=1; done
for id in $(sort -u "$TMP/used"); do
  grep -qx "$id" "$TMP/reg" || { echo "check_test_ids: $id is used in run_tests.sh but is not in TEST-IDS.md (take a new id from the registry)"; rc=1; }
done
for id in $(sort -u "$TMP/reg"); do
  grep -qx "$id" "$TMP/used" || { echo "check_test_ids: $id is in TEST-IDS.md but no test of run_tests.sh runs it"; rc=1; }
done
for id in $(sort "$TMP/wants" | uniq -d); do
  case "$id" in T6|C6) ;; *) echo "check_test_ids: $id has more than one test block in run_tests.sh"; rc=1 ;; esac
done
[ $rc -eq 0 ] && echo "check_test_ids: $(sort -u "$TMP/reg" | wc -l) ids, registry and run_tests.sh agree"
exit $rc

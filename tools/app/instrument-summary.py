#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarise the raw output of `adb shell am instrument -r`: one line per test
(PASS, FAIL, ERROR, SKIPPED), the first lines of each failure, and a last line
`RESULT: OK <n> tests` only when the runner reported success and no test failed.

    tools/app/instrument-summary.py <instrument.txt>
"""
import sys

CODES = {"0": "PASS", "-1": "ERROR", "-2": "FAIL", "-3": "SKIPPED", "-4": "SKIPPED"}


def main(path):
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    cur = {}
    results = []
    stack_key = None
    for line in lines:
        if line.startswith("INSTRUMENTATION_STATUS: "):
            k, _, v = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            cur[k] = v
            stack_key = k if k == "stack" else None
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.split(": ", 1)[1].strip()
            if code != "1" and "test" in cur:
                results.append((CODES.get(code, "CODE " + code), cur.get("class", "?") + "." + cur["test"], cur.get("stack", "")))
            cur = {}
            stack_key = None
        elif stack_key and not line.startswith("INSTRUMENTATION_"):
            cur["stack"] += "\n" + line
    ok_runner = any(l.startswith("OK (") for l in lines)
    for status, name, stack in results:
        print(f"{status:7s} {name}")
        if status in ("FAIL", "ERROR"):
            for s in stack.splitlines()[:8]:
                print("        " + s)
    failed = sum(1 for r in results if r[0] in ("FAIL", "ERROR"))
    passed = sum(1 for r in results if r[0] == "PASS")
    print(f"passed {passed}, failed {failed}, total {len(results)}")
    tail = [l for l in lines if l.startswith(("OK (", "FAILURES", "Tests run", "INSTRUMENTATION_RESULT", "INSTRUMENTATION_CODE"))]
    for t in tail[-4:]:
        print("runner: " + t)
    if ok_runner and failed == 0 and passed > 0:
        print(f"RESULT: OK {passed} tests")
    else:
        print("RESULT: NOT OK")


if __name__ == "__main__":
    main(sys.argv[1])

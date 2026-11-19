#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Count JUnit results of the app modules: tests, failures, errors, skipped, per module and class.

    tools/app/test-summary.py <app dir>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main(app):
    total = [0, 0, 0, 0]
    lines = []
    failures = []
    for path in sorted(glob.glob(os.path.join(app, "*", "build", "test-results", "*", "TEST-*.xml"))):
        module = path.split(os.sep)[-5]
        s = ET.parse(path).getroot()
        n = [int(s.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        total = [a + b for a, b in zip(total, n)]
        lines.append(f"{module:12s} {s.get('name'):70s} tests {n[0]:3d}  failed {n[1]:2d}  errors {n[2]:2d}  skipped {n[3]:2d}")
        for tc in s.iter("testcase"):
            for kind in ("failure", "error"):
                f = tc.find(kind)
                if f is not None:
                    failures.append(f"{module}: {tc.get('classname')}.{tc.get('name')}: {(f.get('message') or '')[:300]}")
    print("\n".join(lines))
    print(f"TOTAL tests {total[0]}  failed {total[1]}  errors {total[2]}  skipped {total[3]}")
    for f in failures:
        print("FAILED " + f)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "app"))

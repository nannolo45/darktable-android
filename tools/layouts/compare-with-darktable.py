#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare schema/builtin-layouts.json with the presets a darktable run stored
(tools/layouts/verify-with-darktable.sh). Usage: compare-with-darktable.py <data json> <runs dir>"""
import json
import os
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
runs = sys.argv[2]
bad = 0
for n in sorted(os.listdir(runs), key=int):
    d = os.path.join(runs, n)
    wf = open(os.path.join(d, "workflow.txt"), encoding="utf-8").read().rstrip("\n")
    stored = []
    for line in open(os.path.join(d, "out", "presets.jsonl"), encoding="utf-8"):
        o = json.loads(line)
        stored.append((bytes.fromhex(o["name_hex"]).decode(), o["writeprotect"], o["op_version"],
                       bytes.fromhex(o["params_hex"])))
    builtin = {name: params for name, wp, ver, params in stored if wp}
    expect = {p["name"]: p["params"].encode("utf-8") for p in data["variants"][wf]["presets"]}
    same = [k for k in expect if builtin.get(k) == expect[k]]
    diff = [k for k in expect if k in builtin and builtin[k] != expect[k]]
    missing = [k for k in expect if k not in builtin]
    extra = [k for k in builtin if k not in expect]
    users = [name for name, wp, ver, params in stored if not wp]
    print(f"workflow '{wf}': {len(same)} of {len(expect)} built-in layouts byte-identical"
          f"{'; differ: ' + ', '.join(diff) if diff else ''}{'; missing: ' + ', '.join(missing) if missing else ''}"
          f"{'; only in darktable: ' + ', '.join(extra) if extra else ''}; user layouts in the database: {users}")
    bad += len(diff) + len(missing) + len(extra)
    for extra_file in ("import-results.txt",):
        f = os.path.join(d, "out", extra_file)
        if os.path.exists(f):
            for line in open(f, encoding="utf-8"):
                print("  import:", line.split()[0], os.path.basename(line.split(None, 1)[1].strip()))
    f = os.path.join(d, "out", "presets-after-import.jsonl")
    if os.path.exists(f):
        for line in open(f, encoding="utf-8"):
            o = json.loads(line)
            if not o["writeprotect"]:
                print(f"  user layout after import: {bytes.fromhex(o['name_hex']).decode()!r} "
                      f"op_version {o['op_version']}: {bytes.fromhex(o['params_hex']).decode()!r}")
    for f in sorted(os.listdir(os.path.join(d, "out"))):
        if f.endswith(".dtpreset"):
            print(f"  exported by darktable: {f}")
print("RESULT:", "all built-in layouts identical" if bad == 0 else f"{bad} differences")
sys.exit(1 if bad else 0)

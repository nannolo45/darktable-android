#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Write schema/module-names.json from the control schema (brief P2f deliverable 7, request R15).

The file maps each processing module's operation name to darktable's English name for it, as the
control schema has it (schema/ui-schema.json, "modules" -> op -> "name"): {"exposure": "exposure",
"colorbalancergb": "color balance rgb", ...}. One flat JSON object, keys sorted, nothing else in it.
LightPhotoLibrary uses it to name modules in its edit record. Never edit the file by hand: run

    python3 -I tools/schema-module-names.py            # write it
    python3 -I tools/schema-module-names.py --check    # exit 1 if the file differs from what this writes
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "schema" / "ui-schema.json"
OUT = REPO / "schema" / "module-names.json"


def names(schema: dict) -> dict:
    out = {}
    for key, m in schema["modules"].items():
        op = m.get("op") or key
        name = (m.get("name") or "").strip()
        if op and name:
            out[op] = name
    return dict(sorted(out.items()))


def main(argv):
    text = json.dumps(names(json.loads(SCHEMA.read_text(encoding="utf-8"))), ensure_ascii=False, indent=1) + "\n"
    if "--check" in argv:
        if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.relative_to(REPO)} is not what {Path(__file__).name} writes from {SCHEMA.relative_to(REPO)}; run it")
            return 1
        print(f"{OUT.relative_to(REPO)}: up to date ({text.count(':')} modules)")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

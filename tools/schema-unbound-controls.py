#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""List the controls the app shows disabled because they are interface-only on desktop (brief P2f, item 10).

    python3 -I tools/schema-unbound-controls.py [workflow] [layout]

For every module in the groups of a built-in module layout (default: darktable's "workflow: scene-referred"
in the "scene-referred (sigmoid)" workflow, the app's default), every list, slider and switch of the control
schema that has neither a parameter (`param`) nor a value it is bound to (`bound_to`), and that no file in
schema/overrides/ makes usable. Those are drawn disabled by the app (no parameter to write). Prints one line
per control: module, control label, kind, section.
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
workflow = sys.argv[1] if len(sys.argv) > 1 else "scene-referred (sigmoid)"
layout = sys.argv[2] if len(sys.argv) > 2 else "workflow: scene-referred"

schema = json.loads((REPO / "schema/ui-schema.json").read_text(encoding="utf-8"))
layouts = json.loads((REPO / "schema/builtin-layouts.json").read_text(encoding="utf-8"))
params = next(p["params"] for p in layouts["variants"][workflow]["presets"] if p["name"] == layout)
groups = [chunk.split("|") for chunk in params.split("ꬹ")[2:]]
mods = []
for g in groups:
    for op in g[3:]:
        if op and op not in mods:
            mods.append(op)

# what the override files make usable: (op, node)
fixed = set()
for f in (REPO / "schema/overrides").glob("*.json"):
    o = json.loads(f.read_text(encoding="utf-8"))
    for kind in ("bind_action", "aspect_list", "replace_with_buttons"):
        for e in o.get(kind, []):
            fixed.add((o["op"], e["node"]))


def walk(cs, section, out):
    for c in cs:
        k = c.get("kind")
        if k == "section":
            section = c.get("label")
        if k in ("slider", "combobox", "toggle") and c.get("param") is None and c.get("bound_to") is None:
            out.append((c["id"], k, c.get("label") or "", section))
        walk(c.get("children", []) + c.get("pages", []), section, out)


print(f"# {layout} ({workflow}): {len(mods)} modules")
n = 0
for op in mods:
    m = schema["modules"].get(op)
    if not m:
        print(f"{op}: not in the control schema")
        continue
    found = []
    walk(m.get("controls", []), None, found)
    for node, kind, label, section in found:
        if (op, node) in fixed:
            continue
        n += 1
        print(f"{m['name']} ({op}) | {label} | {kind}" + (f" | section {section}" if section else ""))
print(f"# {n} controls shown disabled for want of a parameter binding")

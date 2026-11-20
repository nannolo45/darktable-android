#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""List the controls the app shows disabled because they are interface-only on desktop (brief P2f, item 10).

    python3 -I tools/schema-unbound-controls.py [workflow] [layout]

For every module in the groups of a built-in module layout (default: darktable's "workflow: scene-referred"
in the "scene-referred (sigmoid)" workflow, the app's default), every list, slider and switch of the control
schema that has neither a parameter (`param`) nor a value it is bound to (`bound_to`), and that no file in
schema/overrides/ makes usable. Those are drawn disabled by the app (no parameter to write). Prints one line
per control: module, control label, kind, section.

Brief P2j: only what is still open is listed, with its class from the override's `note` (b a list not built yet,
c needs a bridge call, d waits for a later tool); a control the overrides hide (`hide`, class e) or make usable
(`computed`, `value_list`, `stock_list`, `toggle_writes`, `bind_param`, `bind_action`, `aspect_list`,
`replace_with_buttons`) is not listed. A control no override mentions is listed with class "?": a schema update
that adds an interface-only control shows up there.

    python3 -I tools/schema-unbound-controls.py [workflow] [layout] [--check <file>]

--check compares the output with a committed file (docs/experiments/P2j-results/) and exits 1 if they differ.
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
args = [a for a in sys.argv[1:]]
check = None
if "--check" in args:
    i = args.index("--check")
    check = args[i + 1]
    del args[i:i + 2]
workflow = args[0] if len(args) > 0 else "scene-referred (sigmoid)"
layout = args[1] if len(args) > 1 else "workflow: scene-referred"

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
waiting = {}
for f in sorted((REPO / "schema/overrides").glob("*.json")):
    o = json.loads(f.read_text(encoding="utf-8"))
    for kind in ("bind_action", "aspect_list", "replace_with_buttons", "computed", "value_list", "toggle_writes",
                 "bind_param", "hide"):
        for e in o.get(kind, []):
            fixed.add((o["op"], e["node"]))
    sl = o.get("stock_list")
    if sl:
        for k in ("film_node", "paper_node", "upsampling_node"):
            fixed.add((o["op"], sl[k]))
    for e in o.get("note", []):
        waiting[(o["op"], e["node"])] = (e.get("class", "?"), e["text"])


def walk(cs, section, out):
    for c in cs:
        k = c.get("kind")
        if k == "section":
            section = c.get("label")
        if k in ("slider", "combobox", "toggle") and c.get("param") is None and c.get("bound_to") is None:
            out.append((c["id"], k, c.get("label") or "", section))
        walk(c.get("children", []) + c.get("pages", []), section, out)


out = [f"# {layout} ({workflow}): {len(mods)} modules"]
n = 0
classes = {}
for op in mods:
    m = schema["modules"].get(op)
    if not m:
        out.append(f"{op}: not in the control schema")
        continue
    found = []
    walk(m.get("controls", []), None, found)
    for node, kind, label, section in found:
        if (op, node) in fixed:
            continue
        n += 1
        cls, text = waiting.get((op, node), ("?", "no override says what it waits for"))
        classes[cls] = classes.get(cls, 0) + 1
        out.append(f"{m['name']} ({op}) | {label} | {kind}" + (f" | section {section}" if section else "") + f" | class {cls}: {text}")
out.append(f"# {n} controls still disabled for want of a parameter binding: "
           + ", ".join(f"{k} {v}" for k, v in sorted(classes.items())))
text = "\n".join(out) + "\n"
if check:
    want = Path(check).read_text(encoding="utf-8")
    if want != text:
        sys.stdout.write(text)
        sys.exit(f"the list differs from {check}: a control was added, bound or classified; update the file "
                 "(and the override that says what the control waits for)")
    print(f"unbound controls as in {check}: {n}")
else:
    sys.stdout.write(text)

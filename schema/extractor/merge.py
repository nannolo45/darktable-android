# SPDX-License-Identifier: GPL-3.0-or-later
"""Merge the extractor's per-module output into one schema file.

    merge.py <raw dir> <ui-schema.json> [--replace <path>=<token> ...]

--replace swaps machine-specific paths that darktable puts into some tooltips
(its config directory, its install prefix) for a fixed token, so the file does
not depend on where it was generated.

<raw dir> holds _run.json and one <op>.json per module, as written by the
extractor plugin. This script
  - copies module facts and the control tree,
  - turns the observed sweep states into visibility (and sensitivity) rules of
    the form "control X is visible when parameter P is one of {...}", joined
    with AND when several parameters matter, and checks every rule against
    every observed state,
  - adds per-module counts used by the coverage report.
The observed states themselves are not copied (they are large); rerun the
extractor to see them.
"""
import json
import os
import sys
from collections import defaultdict

CONTROL_KINDS = {"slider", "combobox", "toggle", "bauhaus_button", "button", "togglebutton",
                 "colorpicker_button", "graph", "other"}
BAUHAUS = {"slider", "combobox", "toggle", "bauhaus_button"}


def walk(nodes, parent=None, out=None):
    """flat list of (node, parent id) in visual order"""
    if out is None:
        out = []
    for n in nodes:
        out.append((n, parent))
        walk(n.get("children", []), n["id"], out)
    return out


def variables(flat, drivers):
    """condition variables: one per bound parameter (several widgets may share
    one), one per list or switch that holds interface state only"""
    node_by_id = {n["id"]: n for n, _ in flat}
    var_of, labels = {}, defaultdict(dict)
    for d in drivers:
        n = node_by_id[d]
        if n.get("bound_to") == "params" and n.get("param"):
            key = ("param", n["param"])
        else:
            key = ("control", d)
        var_of[d] = key
        if n["kind"] == "toggle":
            labels[key].update({0: "off", 1: "on"})
        else:
            for pos, e in enumerate(n.get("entries", [])):
                v = e["value"] if key[0] == "param" else pos
                labels[key].setdefault(v, e["label"])
    return var_of, labels


def derive_rules(states, flat, drivers, key):
    """rules from observed states; key is 'visible' or 'sensitive'"""
    if not states:
        return [], [], []
    node_by_id = {n["id"]: n for n, _ in flat}
    parent_of = {n["id"]: p for n, p in flat}
    var_of, labels = variables(flat, drivers)
    keys = sorted(set(var_of.values()), key=str)
    xs = []
    for s in states:
        val = {}
        for d in s["drivers"]:
            nid, pos, v = d[0], d[1], d[2]
            if nid in var_of:
                val[var_of[nid]] = v
        xs.append(tuple(val.get(k) for k in keys))
    vals = [s[key] for s in states]
    n_nodes = len(vals[0])

    def vec(i):
        return tuple(v[i] for v in vals)

    rules, unexplained, always_off = [], [], []
    for i in range(n_nodes):
        v = vec(i)
        if len(set(v)) == 1:
            if v[0] == "0":
                always_off.append(i)
            continue
        p = parent_of.get(i)
        if p is not None and vec(p) == v:
            continue  # follows its container; the container carries the rule
        # variables with direct evidence: two states that differ only in it
        evidence = set()
        for ki in range(len(keys)):
            groups = defaultdict(set)
            for x, vi in zip(xs, v):
                groups[x[:ki] + x[ki + 1:]].add((x[ki], vi))
            for g in groups.values():
                if len({vi for _, vi in g}) > 1:
                    evidence.add(ki)
                    break
        if not evidence:
            unexplained.append({"node": i, "label": node_by_id[i].get("label"),
                                "reason": "changes only between states that differ in two or more "
                                          "parameters or lists at once"})
            continue
        allowed = {ki: sorted({x[ki] for x, vi in zip(xs, v) if vi == "1"}, key=str) for ki in evidence}
        mismatches = 0
        for x, vi in zip(xs, v):
            pred = all(x[ki] in allowed[ki] for ki in evidence)
            if pred != (vi == "1"):
                mismatches += 1
        conds = []
        for ki in sorted(evidence):
            k = keys[ki]
            seen = sorted({x[ki] for x in xs if x[ki] is not None}, key=str)
            cond = {"in": allowed[ki], "in_labels": [labels[k].get(a) for a in allowed[ki]],
                    "values_observed": seen}
            if k[0] == "param":
                cond["param"] = k[1]
            else:
                cond["control"] = k[1]
                cond["control_label"] = node_by_id[k[1]].get("label")
            conds.append(cond)
        rule = {"node": i, "kind": node_by_id[i]["kind"], "label": node_by_id[i].get("label"),
                "form": "and", "when_all": conds, "states_checked": len(states),
                "mismatches": mismatches, "verified": mismatches == 0}
        if mismatches:
            # not a plain AND (for example "A or (B and C)"): fall back to the
            # table of observed combinations of the same variables. It is exact
            # for the observed combinations and says nothing about others.
            ev = sorted(evidence)
            pos = sorted({tuple(x[ki] for ki in ev) for x, vi in zip(xs, v) if vi == "1"}, key=str)
            neg = sorted({tuple(x[ki] for ki in ev) for x, vi in zip(xs, v) if vi == "0"}, key=str)
            both = set(pos) & set(neg)
            rule["form"] = "table"
            rule["table"] = {"variables": [c.get("param") or ("control %d" % c["control"]) for c in conds],
                             "visible_for": [list(t) for t in pos],
                             "hidden_for": [list(t) for t in neg]}
            rule["mismatches_of_and_form"] = mismatches
            rule["mismatches"] = sum(1 for x, vi in zip(xs, v) if tuple(x[ki] for ki in ev) in both)
            rule["verified"] = rule["mismatches"] == 0
        rules.append(rule)
    return rules, unexplained, always_off


def module_stats(m, flat):
    controls = [n for n, _ in flat if n["kind"] in CONTROL_KINDS]
    bound = [n for n in controls if n.get("bound_to") == "params"]
    fields = m.get("params", {}).get("fields", [])
    scalars = [f for f in fields if not f["in_array"]]
    array_tpl = [f for f in fields if f["in_array"]]
    bound_names = {n.get("param") for n in bound if n.get("param")}
    import re
    bound_templates = {re.sub(r"\[\d+\]", "[0]", b) for b in bound_names}
    scalar_bound = [f["name"] for f in scalars if f["name"] in bound_names]
    array_values = sum(f["count"] for f in array_tpl)
    array_bound = sum(1 for b in bound_names if "[" in b)
    return {
        "controls": len(controls),
        "bauhaus": sum(1 for n in controls if n["kind"] in BAUHAUS),
        "bound": len(bound),
        "bound_unique_params": len(bound_names),
        "bound_to_blend_params": sum(1 for n in controls if n.get("bound_to") == "blend_params"),
        "bound_unresolved": sum(1 for n in bound if not n.get("param")),
        "unbound_bauhaus": sum(1 for n in controls if n["kind"] in BAUHAUS and n.get("bound_to") != "params"),
        "buttons": sum(1 for n in controls if n["kind"] in ("button", "togglebutton")),
        "colorpicker_buttons": sum(1 for n in controls if n["kind"] == "colorpicker_button"),
        "colorpicker_quads": sum(1 for n in controls if n.get("quad", {}).get("colorpicker")),
        "graphs": sum(1 for n in controls if n["kind"] == "graph"),
        "other_widgets": sum(1 for n in controls if n["kind"] == "other"),
        "tabs": sum(1 for n, _ in flat if n["kind"] == "tabs"),
        "collapsibles": sum(1 for n, _ in flat if n["kind"] == "collapsible"),
        "sections": sum(1 for n, _ in flat if n["kind"] == "section"),
        "stacks": sum(1 for n, _ in flat if n["kind"] == "stack"),
        "scalar_params": len(scalars),
        "scalar_params_bound": len(scalar_bound),
        "scalar_params_unbound": [f["name"] for f in scalars if f["name"] not in bound_names],
        "array_param_values": array_values,
        "array_param_values_bound": array_bound,
        "array_templates_touched": sorted(t for t in bound_templates if "[" in t),
    }


def main():
    raw, out = sys.argv[1], sys.argv[2]
    replace = []
    args = sys.argv[3:]
    while args:
        if args[0] == "--replace" and len(args) > 1:
            path, token = args[1].rsplit("=", 1)
            replace.append((path, token))
            args = args[2:]
        else:
            sys.exit("unknown argument %s" % args[0])
    replace.sort(key=lambda pt: -len(pt[0]))
    run = json.load(open(os.path.join(raw, "_run.json")))
    modules = {}
    for fn in sorted(os.listdir(raw)):
        if not fn.endswith(".json") or fn.startswith("_"):
            continue
        m = json.load(open(os.path.join(raw, fn)))
        op = m["op"]
        if m.get("status") != "ok":
            modules[op] = m
            continue
        flat = walk(m["controls"])
        vis = m["visible_default"]
        sen = m["sensitive_default"]
        for n, _ in flat:
            if vis[n["id"]] == "0":
                n["hidden_by_default"] = True
            if sen[n["id"]] == "0":
                n["insensitive_by_default"] = True
        drivers = [n["id"] for n, _ in flat
                   if (n["kind"] == "toggle") or (n["kind"] == "combobox" and not n.get("editable")
                                                  and 2 <= len(n.get("entries", [])) <= 64)]
        sweep = m.get("sweep", {})
        states = sweep.get("states", [])
        vrules, vunexp, valways = derive_rules(states, flat, drivers, "visible")
        srules, sunexp, salways = derive_rules(states, flat, drivers, "sensitive")
        gui_update_diff = sum(1 for s in states if "visible_after_gui_update" in s)
        out_m = {k: m[k] for k in ("op", "name", "aliases", "version", "pipe_position", "default_group",
                                   "flags", "deprecated_msg", "default_enabled", "hide_enable_button",
                                   "on_image", "color_picker_apply", "has_gui_changed", "params",
                                   "controls", "status", "getter_mismatches", "nodes_destroyed") if k in m}
        out_m["visibility"] = {
            "drivers": drivers,
            "states_observed": len(states),
            "capped": sweep.get("capped", False),
            "swept": not sweep.get("skipped", False),
            "rules": vrules,
            "unexplained": vunexp,
            "hidden_in_every_state": valways,
            "states_where_gui_update_disagrees": gui_update_diff,
        }
        out_m["sensitivity"] = {"rules": srules, "unexplained": sunexp,
                                "insensitive_in_every_state": salways}
        out_m["stats"] = module_stats(m, flat)
        # timings stay in the raw output: they would make every regenerated file differ
        modules[op] = out_m
    schema = {
        "schema_format": 1,
        "generator": "schema/extractor (brief S5)",
        "darktable_version": run.get("darktable_version"),
        "image": {k: run.get(k) for k in ("image", "camera_maker", "camera_model", "raw")},
        "strings": "untranslated (C locale); labels have any gettext context removed",
        "modules": dict(sorted(modules.items(), key=lambda kv: kv[1].get("pipe_position", 0))),
    }
    text = json.dumps(schema, indent=1, ensure_ascii=False, sort_keys=False)
    for path, token in replace:
        text = text.replace(path, token)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
        f.write("\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Human-readable difference between two ui-schema.json files.

    tools/schema-diff.py <old ui-schema.json> <new ui-schema.json> [--all-labels]

Reports modules added, removed and changed (version, flags, group), and per
module the controls added, removed and changed, and visibility rules added,
removed and changed. Controls are matched by what identifies them across
versions: the bound parameter if there is one, else kind + section + label,
else kind + position among controls of that kind. Plain text labels (kind
"label") often show live values ("CCT: 3927 K"), so their text is compared only
with --all-labels.

Exit status: 0 if no difference, 1 if there are differences.
"""
import json
import sys

CONTROL_KINDS = {"slider", "combobox", "toggle", "bauhaus_button", "button", "togglebutton",
                 "colorpicker_button", "graph", "other", "tabs", "page", "collapsible", "section",
                 "stack", "label"}
COMPARED = {
    "slider": ["label", "section", "hard_min", "hard_max", "soft_min", "soft_max", "default", "step",
               "digits", "factor", "offset", "format", "linear_curve", "bound_to", "param"],
    "combobox": ["label", "section", "default_pos", "editable", "dynamic_entries", "bound_to", "param"],
    "toggle": ["label", "section", "default", "bound_to", "param"],
    "page": ["label"], "collapsible": ["label"], "section": ["label"],
}


def flatten(nodes, path=(), out=None):
    if out is None:
        out = []
    for n in nodes:
        here = path
        if n["kind"] in ("page", "collapsible"):
            here = path + ("%s:%s" % (n["kind"], n.get("label")),)
        out.append((n, path))
        flatten(n.get("children", []), here, out)
    return out


def keyed(module, all_labels):
    res = {}
    counters = {}
    for n, path in flatten(module.get("controls", [])):
        k = n["kind"]
        if k in ("row", "grid"):
            continue
        if k == "label" and not all_labels:
            continue
        if n.get("param"):
            key = "%s param=%s" % (k, n["param"])
        elif n.get("label"):
            key = "%s '%s%s'" % (k, (n.get("section") + "/") if n.get("section") else "", n["label"])
        else:
            i = counters.get(k, 0)
            counters[k] = i + 1
            key = "%s #%d" % (k, i)
        base, j = key, 2
        while key in res:
            key = "%s (%d)" % (base, j)
            j += 1
        res[key] = (n, "/".join(path))
    return res


def fmt(v):
    if isinstance(v, float):
        return "%.6g" % v
    return json.dumps(v, ensure_ascii=False)


def entries_of(n):
    return [(e.get("label"), e.get("value")) for e in n.get("entries", [])]


def rule_key(module, r):
    nodes = {n["id"]: n for n, _ in flatten(module.get("controls", []))}
    n = nodes.get(r["node"], {})
    return "%s %s" % (n.get("kind"), n.get("param") or repr(n.get("label")))


def rule_text(r):
    if r.get("form") == "table":
        t = r["table"]
        return "table over %s: visible for %s" % (", ".join(t["variables"]), t["visible_for"])
    parts = []
    for c in r["when_all"]:
        var = c.get("param") or "control '%s'" % c.get("control_label")
        parts.append("%s in %s" % (var, c.get("in_labels") or c.get("in")))
    return " and ".join(parts)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    all_labels = "--all-labels" in sys.argv
    if len(args) != 2:
        print(__doc__)
        return 2
    old, new = (json.load(open(a, encoding="utf-8")) for a in args)
    lines = []
    if old.get("darktable_version") != new.get("darktable_version"):
        lines.append("darktable %s -> %s" % (old.get("darktable_version"), new.get("darktable_version")))
    if old.get("image") != new.get("image"):
        lines.append("image %s -> %s" % (fmt(old.get("image")), fmt(new.get("image"))))
    om, nm = old["modules"], new["modules"]
    for op in sorted(set(nm) - set(om)):
        lines.append("+ module %s (%s), %d controls" % (op, nm[op].get("name"),
                                                        nm[op].get("stats", {}).get("controls", 0)))
    for op in sorted(set(om) - set(nm)):
        lines.append("- module %s (%s)" % (op, om[op].get("name")))
    for op in sorted(set(om) & set(nm)):
        a, b = om[op], nm[op]
        ml = []
        for k in ("name", "version", "status", "default_group", "flags", "on_image", "default_enabled"):
            if a.get(k) != b.get(k):
                ml.append("  ~ %s: %s -> %s" % (k, fmt(a.get(k)), fmt(b.get(k))))
        ka, kb = keyed(a, all_labels), keyed(b, all_labels)
        for k in sorted(set(kb) - set(ka)):
            ml.append("  + %s%s" % (k, ("  in " + kb[k][1]) if kb[k][1] else ""))
        for k in sorted(set(ka) - set(kb)):
            ml.append("  - %s%s" % (k, ("  in " + ka[k][1]) if ka[k][1] else ""))
        for k in sorted(set(ka) & set(kb)):
            na, nb = ka[k][0], kb[k][0]
            ch = []
            for f in COMPARED.get(na["kind"], ["label"]):
                if na.get(f) != nb.get(f):
                    ch.append("%s %s -> %s" % (f, fmt(na.get(f)), fmt(nb.get(f))))
            if entries_of(na) != entries_of(nb):
                ea, eb = entries_of(na), entries_of(nb)
                added = [e for e in eb if e not in ea]
                removed = [e for e in ea if e not in eb]
                ch.append("entries" + (" +%s" % added if added else "") + (" -%s" % removed if removed else "")
                          + (" (reordered)" if not added and not removed else ""))
            if ka[k][1] != kb[k][1]:
                ch.append("moved %s -> %s" % (ka[k][1] or "top", kb[k][1] or "top"))
            if bool(na.get("hidden_by_default")) != bool(nb.get("hidden_by_default")):
                ch.append("hidden by default %s -> %s" % (bool(na.get("hidden_by_default")),
                                                          bool(nb.get("hidden_by_default"))))
            if ch:
                ml.append("  ~ %s: %s" % (k, "; ".join(ch)))
        ra = {rule_key(a, r): rule_text(r) for r in a.get("visibility", {}).get("rules", [])
              if all_labels or r.get("kind") != "label"}
        rb = {rule_key(b, r): rule_text(r) for r in b.get("visibility", {}).get("rules", [])
              if all_labels or r.get("kind") != "label"}
        for k in sorted(set(rb) - set(ra)):
            ml.append("  + visibility of %s: %s" % (k, rb[k]))
        for k in sorted(set(ra) - set(rb)):
            ml.append("  - visibility of %s (was: %s)" % (k, ra[k]))
        for k in sorted(set(ra) & set(rb)):
            if ra[k] != rb[k]:
                ml.append("  ~ visibility of %s: %s -> %s" % (k, ra[k], rb[k]))
        ha = set(a.get("visibility", {}).get("hidden_in_every_state", []))
        hb = set(b.get("visibility", {}).get("hidden_in_every_state", []))
        if len(ha) != len(hb):
            ml.append("  ~ controls hidden in every observed state: %d -> %d" % (len(ha), len(hb)))
        if ml:
            lines.append("module %s (%s)" % (op, b.get("name")))
            lines.extend(ml)
    print("\n".join(lines) if lines else "no differences")
    return 1 if lines else 0


if __name__ == "__main__":
    sys.exit(main())

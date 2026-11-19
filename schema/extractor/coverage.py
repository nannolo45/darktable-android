# SPDX-License-Identifier: GPL-3.0-or-later
"""Coverage tables (markdown) from a ui-schema.json.

    coverage.py <ui-schema.json>            all modules + totals
    coverage.py <ui-schema.json> --priority the 29 priority modules (PLAN.md section 6)

Counts only; whether a usable panel can be generated is judged by hand in
docs/experiments/S5-control-schema.md.
"""
import json
import sys

PRIORITY = [
    ("color calibration", "channelmixerrgb"), ("crop", "crop"), ("orientation", "flip"),
    ("rotate and perspective", "ashift"), ("lens correction", "lens"), ("demosaic", "demosaic"),
    ("white balance", "temperature"), ("tone curve", "tonecurve"), ("spektrafilm", "spektrafilm"),
    ("AgX", "agx"), ("rgb levels", "rgblevels"), ("tone equalizer", "toneequal"),
    ("exposure", "exposure"), ("grain", "grain"), ("contrast equalizer", "atrous"),
    ("blurs", "blurs"), ("diffuse or sharpen", "diffuse"), ("retouch", "retouch"),
    ("chromatic aberrations", "cacorrectrgb"), ("denoise (profiled)", "denoiseprofile"),
    ("color zones", "colorzones"), ("LUT 3D", "lut3d"), ("rgb curve", "rgbcurve"),
    ("color balance rgb", "colorbalancergb"), ("color equalizer", "colorequal"),
    ("color balance", "colorbalance"), ("color harmonizer", "colorharmonizer"),
    ("color look up table", "colorchecker"), ("rgb primaries", "primaries"),
]


def on_image(m):
    o = m.get("on_image", {})
    return any(o.get(k) for k in ("draws", "mouse_moved", "button_pressed", "scrolled"))


def row(m):
    st = m["stats"]
    v = m["visibility"]
    n_rules = len(v["rules"])
    n_table = sum(1 for r in v["rules"] if r.get("form") == "table")
    return {
        "controls": st["controls"],
        "bound": st["bound"],
        "scalars": "%d/%d" % (st["scalar_params_bound"], st["scalar_params"]),
        "arrays": "%d/%d" % (st["array_param_values_bound"], st["array_param_values"]),
        "unbound_bh": st["unbound_bauhaus"],
        "buttons": st["buttons"],
        "pickers": st["colorpicker_buttons"] + st["colorpicker_quads"],
        "graphs": st["graphs"] + st["other_widgets"],
        "containers": ", ".join(x for x in (
            "%d tabs" % st["tabs"] if st["tabs"] else "",
            "%d collapsible" % st["collapsibles"] if st["collapsibles"] else "",
            "%d sections" % st["sections"] if st["sections"] else "",
            "%d stacks" % st["stacks"] if st["stacks"] else "") if x) or "-",
        "on_image": "yes" if on_image(m) else "-",
        "rules": "%d%s" % (n_rules, " (%d table)" % n_table if n_table else ""),
        "always_hidden": len(v["hidden_in_every_state"]),
        "unbound_params": ", ".join(st["scalar_params_unbound"]) or "-",
    }


HEAD = ("| module | op | controls | bound | scalar params bound | array values bound | unbound sliders/lists/switches "
        "| buttons | colour pickers | graphs/custom | containers | on image | visibility rules | hidden in every state "
        "| scalar params without a control |")
SEP = "|" + "---|" * 15


def line(name, op, m):
    if m is None:
        return "| %s | %s | missing |" % (name, op) + " |" * 12
    if m.get("status") != "ok":
        return "| %s | %s | %s |" % (name, op, m.get("status")) + " |" * 12
    r = row(m)
    dep = " (deprecated)" if m.get("flags", {}).get("deprecated") else ""
    return ("| %s%s | %s | %d | %d | %s | %s | %d | %d | %d | %d | %s | %s | %s | %d | %s |" % (
        name, dep, op, r["controls"], r["bound"], r["scalars"], r["arrays"], r["unbound_bh"], r["buttons"],
        r["pickers"], r["graphs"], r["containers"], r["on_image"], r["rules"], r["always_hidden"],
        r["unbound_params"]))


def totals(mods):
    ok = [m for m in mods.values() if m.get("status") == "ok"]
    t = {}
    for m in ok:
        for k, v in m["stats"].items():
            if isinstance(v, int) and not isinstance(v, bool):
                t[k] = t.get(k, 0) + v
    rules = [r for m in ok for r in m["visibility"]["rules"]]
    out = [
        "- modules in the darkroom: %d; with a panel walked: %d; without a panel (hidden): %d; other status: %d" % (
            len(mods), len(ok), sum(1 for m in mods.values() if m.get("status") == "hidden"),
            sum(1 for m in mods.values() if m.get("status") not in ("ok", "hidden"))),
        "- deprecated modules among those walked: %d" % sum(1 for m in ok if m["flags"].get("deprecated")),
        "- controls: %d (sliders, lists, switches and bauhaus buttons: %d; other buttons: %d; colour picker buttons: %d; "
        "graphs and other custom widgets: %d)" % (t["controls"], t["bauhaus"], t["buttons"], t["colorpicker_buttons"],
                                                  t["graphs"] + t["other_widgets"]),
        "- bound to a module parameter: %d controls (%.1f %% of all controls, %.1f %% of sliders/lists/switches), "
        "%d distinct parameters; offsets not resolved to a name: %d" % (
            t["bound"], 100.0 * t["bound"] / t["controls"], 100.0 * t["bound"] / t["bauhaus"],
            t["bound_unique_params"], t["bound_unresolved"]),
        "- sliders/lists/switches not bound to a parameter: %d" % t["unbound_bauhaus"],
        "- colour picker quads on sliders: %d" % t["colorpicker_quads"],
        "- scalar parameters (outside arrays) known to introspection: %d, of which bound to a control: %d (%.1f %%)" % (
            t["scalar_params"], t["scalar_params_bound"], 100.0 * t["scalar_params_bound"] / t["scalar_params"]),
        "- parameter values inside arrays (curves, nodes, bands): %d, of which bound to a control: %d" % (
            t["array_param_values"], t["array_param_values_bound"]),
        "- modules with graphs or other custom widgets: %d; modules that draw on or take input from the image: %d" % (
            sum(1 for m in ok if m["stats"]["graphs"] + m["stats"]["other_widgets"]),
            sum(1 for m in ok if on_image(m))),
        "- containers: %d tab sets, %d collapsible sections, %d section headings, %d stacks" % (
            t["tabs"], t["collapsibles"], t["sections"], t["stacks"]),
        "- visibility rules: %d (AND form, verified on every observed state: %d; table form: %d; failing: %d); "
        "rules on interface-only lists or switches: %d" % (
            len(rules), sum(1 for r in rules if r.get("form") == "and" and r["verified"]),
            sum(1 for r in rules if r.get("form") == "table"), sum(1 for r in rules if not r["verified"]),
            sum(1 for r in rules if any("control" in c for c in r["when_all"]))),
        "- controls whose visibility changed but could not be tied to one list or switch: %d" % sum(
            len(m["visibility"]["unexplained"]) for m in ok),
        "- controls hidden in every observed state: %d (in %d modules)" % (
            sum(len(m["visibility"]["hidden_in_every_state"]) for m in ok),
            sum(1 for m in ok if m["visibility"]["hidden_in_every_state"])),
        "- observed states: %d; modules where a rerun of gui_update changed visibility: %d; sweep capped: %d" % (
            sum(m["visibility"]["states_observed"] for m in ok),
            sum(1 for m in ok if m["visibility"]["states_where_gui_update_disagrees"]),
            sum(1 for m in ok if m["visibility"]["capped"])),
        "- sensitivity (greyed-out) rules: %d" % sum(len(m["sensitivity"]["rules"]) for m in ok),
        "- private-struct vs public-getter disagreements: %d" % sum(m.get("getter_mismatches", 0) for m in ok),
    ]
    return "\n".join(out)


def main():
    s = json.load(open(sys.argv[1], encoding="utf-8"))
    mods = s["modules"]
    print("<!-- generated by schema/extractor/coverage.py from %s -->" % sys.argv[1].split("/")[-1])
    if "--priority" in sys.argv:
        print(HEAD)
        print(SEP)
        for name, op in PRIORITY:
            print(line(name, op, mods.get(op)))
        return
    print(totals(mods))
    print()
    print(HEAD)
    print(SEP)
    for op, m in mods.items():
        print(line(m.get("name", op), op, m))


if __name__ == "__main__":
    main()

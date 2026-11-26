#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Brief P5a: module coverage audit. One record per processing module (iop) of the pinned darktable.

    python3 -I tools/module-audit.py <darktable source dir> [--results docs/experiments/P5a-results]
                                     [--report docs/experiments/P5a-module-audit.md] [--check]

Reads, cheapest first:
  - the module list from <darktable>/src/iop/CMakeLists.txt (add_iop) and each module's flags from its source
    (IOP_FLAGS_DEPRECATED / IOP_FLAGS_HIDDEN / IOP_FLAGS_ONE_INSTANCE in flags(); deprecated_msg()),
  - darktable's "modules: all" layout (schema/builtin-layouts.json, scene-referred (sigmoid)) for the groups,
  - schema/ui-schema.json and schema/overrides/*.json: controls bound, controls still unbound and what their
    override note says, colour pickers marked on desktop and those built, graphs, buttons, on-photo interaction,
  - the app's sources: which graph editors and on-photo tools exist (regular expressions on the Kotlin files;
    the script stops if one of them is not found, so a moved file is noticed),
  - the C40 results of the Linux bridge suite (engine/tests/run_tests.sh, copied from the CI logs into
    --results): bridge render vs darktable-cli per module with default parameters, Bayer (cr2) and X-Trans (raf).
The verdicts (works / partly / missing), the plain-language gap and its size are the auditor's, in FINDINGS below;
the script stops if a module has no finding or a finding names a module darktable does not have.

Writes <results>/modules.json. With --report, replaces the tables between the markers
<!-- p5a:tables:start --> and <!-- p5a:tables:end --> of the report. With --check, exits 1 if modules.json would
change (for P5b: run it after the engine bump and look at the diff).
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# ------------------------------------------------------------------------------------------------ findings (hand)
# op: (editing, size, what is missing and why, extra test ids that cover it)
# editing: works / partly / missing (PLAN 3.4 tiers reached on the phone); size: - / small / medium / large
FINDINGS = {
    # --- base
    "basecurve": ("partly", "medium", "the curve itself cannot be drawn: no graph editor for base curve (the tone curve editor could be reused); its presets and the other sliders work; \"scale for graph\" also waits for it", ""),
    "crop": ("works", "-", "", "P3a crop tool (emulator), C20"),
    "ashift": ("works", "-", "", "C33"),
    "colorreconstruct": ("works", "-", "", ""),
    "demosaic": ("works", "-", "", "C9"),
    "exposure": ("works", "-", "", "T2, C19"),
    "flip": ("works", "-", "", ""),
    "highlights": ("works", "-", "", ""),
    "negadoctor": ("partly", "medium", "the seven colour pickers are not built (film base colour, D max, scan exposure, shadows, illuminant, paper black, print exposure); scanning negatives starts with \"pick the film base\", so the module is hard to use without them", ""),
    "rawprepare": ("works", "-", "", ""),
    "shadhi": ("works", "-", "", ""),
    "temperature": ("works", "-", "", "C14, C41, C43"),
    "toneequal": ("works", "-", "", "C19"),
    # --- tone
    "agx": ("partly", "small", "the read-only curve display is not drawn; every slider, the five pickers and the three buttons work", "C43"),
    "bilat": ("works", "-", "", ""),
    "contrastntexture": ("works", "-", "", ""),
    "filmicrgb": ("works", "-", "the read-only curve display is not drawn", ""),
    "rgbcurve": ("works", "-", "", "C19, C38"),
    "rgblevels": ("works", "-", "", "C19, C43"),
    "sigmoid": ("works", "-", "", "C9"),
    "spektrafilm": ("works", "small", "two pickers (pre-compression boost, post-compression scale) are not built: desktop solves its film simulation for the picked highlight (_solve_boost_for_lightness, _solve_scale_for_lightness), which the engine cannot call without a copy of the simulation; \"download data pack\" is not needed (the app carries the pack)", ""),
    "tonecurve": ("works", "small", "\"scale for graph\" (a display zoom of the graph) is not built", ""),
    # --- color
    "channelmixerrgb": ("partly", "large", "the colour checker calibration (chart, optimize for, patch scale, compute/check/accept buttons) needs a chart frame drawn on the photo, not built; everything else, the illuminant picker included, works", "C19"),
    "colorbalancergb": ("works", "-", "", "C9"),
    "colorchecker": ("works", "-", "", "C32"),
    "colorcontrast": ("works", "-", "", ""),
    "colorcorrection": ("partly", "medium", "the colour grid where desktop drags the two split-toning points is not built, so the four colour parameters cannot be changed; only saturation works", ""),
    "colorin": ("works", "-", "your own ICC profile files cannot be added (desktop reads them from its color/in folder); the built-in and embedded profiles work", "C14"),
    "colorout": ("works", "-", "your own ICC profile files cannot be added (desktop's color/out folder); the built-in profiles work", ""),
    "colorzones": ("works", "-", "", "C43"),
    "colorequal": ("works", "-", "the node placement picker shows the picked values only (desktop marks the hue on its graph)", "C43"),
    "colorharmonizer": ("partly", "small", "the \"analyze\" and \"import\" buttons are not built (analyze reads a hue histogram that desktop's process() collects); the sliders, lists and hue pickers work", "C43"),
    "lut3d": ("missing", "medium", "no way to choose a LUT file: desktop lists the files of its LUT folder (a preference the phone does not have); needs a file chooser and an answer to where the .cube/.3dl/.png files come from (phone storage, or synced from the PC through LightPhotoLibrary); with no file the module does nothing", ""),
    "monochrome": ("partly", "medium", "the colour filter position (the round a/b graph you drag on desktop) is not built, so only the neutral filter and \"highlights\" are available", ""),
    "profile_gamma": ("works", "-", "desktop lists it in no module group (search only)", "C43"),
    "primaries": ("works", "-", "", ""),
    "satcurvergb": ("partly", "medium", "the saturation curve itself (graph) cannot be edited, nor its picker; the protect sliders work", ""),
    "velvia": ("works", "-", "", ""),
    "colorbalance": ("partly", "small", "desktop's HSL view of lift/gamma/gain (hue and saturation sliders) and the pickers are not built: \"optimize luma\" and \"neutralize colors\" use the patches its other pickers kept in the panel (left out by P6a); the RGB factor sliders work; desktop lists it in no module group (search only)", ""),
    # --- correct
    "atrous": ("works", "-", "", ""),
    "bilateral": ("works", "-", "", ""),
    "cacorrect": ("works", "-", "", ""),
    "cacorrectrgb": ("works", "-", "", ""),
    "denoiseprofile": ("partly", "medium", "the wavelet curves (graph of the \"wavelets\" mode) are not built; profiles, non-local means mode and all sliders work", "C14"),
    "dither": ("works", "-", "", ""),
    "hazeremoval": ("works", "-", "", ""),
    "hotpixels": ("works", "-", "", ""),
    "lens": ("partly", "medium", "camera and lens cannot be chosen by hand (desktop's searchable camera and lens lists; the bridge already sets a lens, C14) and the focal length, aperture and distance lists are not built; with a lens the lensfun database knows, automatic detection from Exif works; the lensfun database is in the APK", "C14"),
    "liquify": ("missing", "large", "liquify is drawn on the photo (points, lines, curves with strength and radius handles); the on-photo tool is not built, the panel has no controls of its own", ""),
    "nlmeans": ("works", "-", "", ""),
    "rasterfile": ("missing", "medium", "needs a PNG or PFM mask file chosen with a file chooser, not built; with no file the module does nothing", ""),
    "rawdenoise": ("partly", "medium", "the per-frequency curves (graph) are not built; the noise threshold works", ""),
    "retouch": ("works", "small", "wavelet decompose cannot be edited (the picture shows it); shapes have no rotation gesture (a slider instead)", "C32, C37"),
    "scalepixels": ("works", "-", "", ""),
    "sharpen": ("works", "-", "", ""),
    "defringe": ("works", "-", "desktop shows it as deprecated in its message but not in its flags (see the report's upstream notes); listed in no module group (search only)", ""),
    # --- effect
    "bloom": ("works", "-", "", ""),
    "borders": ("partly", "medium", "border and frame line colours cannot be chosen: desktop's colour buttons and their pickers are not built (needs a colour dialog); sizes, aspect and position work", "C14"),
    "colorize": ("works", "-", "", "C43"),
    "colormapping": ("missing", "medium", "needs \"acquire as source\" and \"acquire as target\" (two images' colours), not built; with nothing acquired the module does nothing", ""),
    "enlargecanvas": ("works", "-", "", ""),
    "graduatednd": ("works", "small", "the filter line is not drawn on the photo (its position is a slider, 0..100 %, where desktop drags the line); the hue picker is not built", "C42"),
    "grain": ("works", "-", "", ""),
    "highpass": ("works", "-", "", ""),
    "lowlight": ("partly", "medium", "the lowlight curve (graph), the module's main control, is not built; only blue shift works", ""),
    "lowpass": ("works", "-", "", ""),
    "overlay": ("missing", "large", "composite needs another photo of the library chosen as the overlay; no way to choose it, and the phone's engine library does not hold the other photos", ""),
    "soften": ("works", "-", "", ""),
    "splittoning": ("works", "-", "", "C43"),
    "vignette": ("works", "small", "no on-photo handles; every value has a slider", ""),
    "watermark": ("partly", "medium", "the watermark list (SVG files from darktable's watermarks folder, which the APK carries), the text font and the colour button are not built, nor the 3x3 alignment; the default watermark and the sliders work; fonts on Android not checked", ""),
    "censorize": ("works", "-", "", ""),
    "blurs": ("works", "-", "", ""),
    "diffuse": ("works", "-", "", "C39"),
    # --- deprecated (darktable shows them only when an edit already uses them)
    "invert": ("works", "-", "", "C43"),
    "clipping": ("partly", "medium", "sliders work; flip, keystone and aspect lists are not built (crop and perspective replace it)", ""),
    "spots": ("missing", "large", "shapes are drawn on the photo; retouch replaces it; an old edit renders and keeps its spots", ""),
    "tonemap": ("works", "-", "", ""),
    "equalizer": ("missing", "-", "no controls on desktop either (deprecated); renders and keeps its values", ""),
    "colortransfer": ("missing", "-", "no controls on desktop either (deprecated); renders and keeps its values", ""),
    "channelmixer": ("partly", "small", "the destination list and its red/green/blue sliders are interface-only on desktop and not built", ""),
    "basicadj": ("works", "small", "\"auto\" and its area picker are not built (auto exposure from a histogram of the module's input); the middle gray picker works", "C43"),
    "filmic": ("missing", "medium", "all its sliders are interface-only on desktop (none bound to a parameter): not built; renders and keeps its values", ""),
    "zonesystem": ("missing", "medium", "the zone bar (graph) is its only control: not built", ""),
    "globaltonemap": ("works", "-", "", ""),
    "relight": ("works", "-", "the picker shows the picked values only (desktop marks them on its center slider)", ""),
    "vibrance": ("works", "-", "", ""),
    "clahe": ("partly", "small", "radius and amount are interface-only on desktop: not built", ""),
    "colisa": ("works", "-", "", ""),
    "levels": ("partly", "small", "the levels graph and \"auto\" are not built (rgb levels replaces it); the three pickers work", "C43"),
}

# modules that do nothing until given input: the default render only shows that switching them on changes nothing
NEEDS_INPUT = {"retouch": "C32", "liquify": "", "spots": "", "colorchecker": "C32", "lut3d": "", "rasterfile": "",
               "overlay": "", "colormapping": "", "ashift": "C33", "crop": "C20"}

# controls the schema marks unbound that a hand-built panel of the app provides (or leaves out on purpose): the override
# notes of class f (brief P5c; before it a table here, because the notes still said "a later package")
APP_PANEL_PICKERS = {"colorchecker": None, "retouch": {"pick fill color from image"}}  # RetouchPanel, LutPanel

GROUP_ORDER = ["base", "tone", "color", "correct", "effect", "no group (search only)"]


def die(msg):
    sys.exit(f"module-audit.py: {msg}")


def iop_list(dt):
    cm = (dt / "src/iop/CMakeLists.txt").read_text()
    files = {}
    for op, src in re.findall(r'^\s*add_iop\((\w+)\s+"([^"]+)"', cm, re.M):
        files.setdefault(op, src)
    visible = set(re.findall(r'^\s*add_iop\((\w+)\s+"[^"]+"[^)]*DEFAULT_VISIBLE', cm, re.M))
    out = {}
    for op, src in files.items():
        t = (dt / "src/iop" / src).read_text(errors="replace")
        fm = re.search(r'\bint\s+flags\s*\(\s*\)\s*\{(.*?)\n\}', t, re.S)
        fl = fm.group(1) if fm else ""
        gm = re.search(r'\bint\s+default_group\s*\(\s*\)\s*\{(.*?)\n\}', t, re.S)
        out[op] = {
            "source": f"src/iop/{src}",
            "deprecated": "IOP_FLAGS_DEPRECATED" in fl,
            "hidden": "IOP_FLAGS_HIDDEN" in fl,
            "one_instance": "IOP_FLAGS_ONE_INSTANCE" in fl,
            "deprecated_msg": "deprecated_msg" in t,
            "deprecated_flag_in_default_group": bool(gm and "IOP_FLAGS_DEPRECATED" in gm.group(1)),
            "default_visible": op in visible,
        }
    return out


def layout_groups():
    lay = json.loads((REPO / "schema/builtin-layouts.json").read_text())
    p = next(p["params"] for p in lay["variants"]["scene-referred (sigmoid)"]["presets"] if p["name"] == "modules: all")
    groups = {}
    for chunk in p.split("ꬹ")[2:]:
        f = chunk.split("|")
        for op in f[3:]:
            if op:
                groups.setdefault(op, f[0])
    return groups


def overrides():
    fixed, notes, pickers, built = set(), {}, {}, set()
    for f in sorted((REPO / "schema/overrides").glob("*.json")):
        o = json.loads(f.read_text())
        for kind in ("bind_action", "aspect_list", "replace_with_buttons", "computed", "value_list", "toggle_writes",
                     "bind_param", "hide", "picker_setting", "picker", "action_list"):
            for e in o.get(kind, []):
                fixed.add((o["op"], e["node"]))
        sl = o.get("stock_list")
        if sl:
            for k in ("film_node", "paper_node", "upsampling_node"):
                fixed.add((o["op"], sl[k]))
        for e in o.get("note", []):
            notes[(o["op"], e["node"])] = e["text"]
            if e.get("class") == "f":
                built.add((o["op"], e["node"]))
        for e in o.get("picker", []):
            pickers.setdefault(o["op"], set()).add(e["node"])
    return fixed, notes, pickers, built


def app_tools():
    """graph editors and module panels/tools of the app, read from its sources"""
    def read(rel):
        p = REPO / rel
        if not p.exists():
            die(f"{rel} not found: the app moved; update app_tools()")
        return p.read_text()
    base = "app/darkroom/src/main/kotlin/app/dtandroid/darkroom/"
    curve = read(base + "ui/graph/CurveEditor.kt")
    m = re.search(r'fun CurveEditorHost.*?when \(ctx\.instance\.op\) \{(.*?)else ->', curve, re.S)
    if not m:
        die("CurveEditorHost's op list not found")
    graphs = {op: "curve editor (P3e)" for op in re.findall(r'"(\w+)"', m.group(1))}
    band = read(base + "ui/graph/BandModel.kt")
    m = re.search(r'val OPS = setOf\(([^)]*)\)', band)
    if not m:
        die("BandModels.OPS not found")
    graphs.update({op: "band editor (P3e-b)" for op in re.findall(r'"(\w+)"', m.group(1))})
    tools = {}
    for rel, const, what in [("Retouch.kt", "RETOUCH_OP", "retouch panel and on-photo shapes (P3m)"),
                             ("Lut.kt", "LUT_OP", "patch grid and picker (P3m)"),
                             ("Perspective.kt", "ASHIFT_OP", "on-photo guides (P3n)"),
                             ("DarkroomController.kt", "CROP_OP", "on-photo crop (P3a)"),
                             ("DarkroomController.kt", "TONEEQ_OP", "drag on the photo (P3e)")]:
        mm = re.search(const + r'\s*=\s*"(\w+)"', read(base + rel))
        if not mm:
            die(f"{const} not found in {rel}")
        tools[mm.group(1)] = what
    return graphs, tools


def walk(cs, out):
    for c in cs:
        out.append(c)
        walk(c.get("children", []) + c.get("pages", []), out)


def load_results(rdir):
    res = {}
    for cls, case in (("bayer", "cr2"), ("xtrans", "raf")):
        states, cmp_ = {}, {}
        f = rdir / f"results-{case}.txt"
        if f.exists():
            for line in f.read_text().splitlines():
                w = line.split()
                if len(w) >= 4 and w[0] == "RESULT" and w[1] == "module":
                    states[w[2]] = w[3]
        f = rdir / f"renders-{case}.txt"
        if f.exists():
            for line in f.read_text().splitlines():
                w = line.split(" ", 2)
                if len(w) == 3:
                    cmp_[w[0]] = (int(w[1]), w[2])
        res[cls] = (states, cmp_)
    return res


def render_cell(op, cls, res):
    states, cmp_ = res[cls]
    if not states:
        return "not checked", "no C40 results"
    st = states.get(op)
    if st is None:
        return "not checked", "not in the bridge's pipe"
    if st == "on_in_base":
        return "yes", "on in the test edit (T1)"
    if st == "no_switch":
        return "not checked", "no on/off switch for this image class"
    if op in cmp_:
        s, txt = cmp_[op]
        m = re.search(r'max (\d+) mean ([\d.]+) psnr ([\w.]+)', txt)
        detail = f"max {m.group(1)}, mean {m.group(2)}, PSNR {m.group(3)}" if m else txt[:80]
        return {0: "yes", 1: "no", 2: "not checked", 3: "no"}[s], (detail if s in (0, 1) else txt[:120])
    return "not checked", st


def main(argv):
    args = argv[1:]
    def opt(name, default=None):
        if name in args:
            i = args.index(name)
            v = args[i + 1]
            del args[i:i + 2]
            return v
        return default
    rdir = REPO / opt("--results", "docs/experiments/P5a-results")
    report = opt("--report")
    check = "--check" in args
    if check:
        args.remove("--check")
    if len(args) != 1:
        die("usage: module-audit.py <darktable source dir> [--results dir] [--report file] [--check]")
    dt = Path(args[0])
    iops = iop_list(dt)
    schema = json.loads((REPO / "schema/ui-schema.json").read_text())
    smods = schema["modules"]
    groups = layout_groups()
    fixed, notes, pickers, built = overrides()
    graphs_app, tools_app = app_tools()
    res = load_results(rdir)
    for op in FINDINGS:
        if op not in iops:
            die(f"finding for {op}, which darktable does not build")

    records = []
    for op in sorted(iops):
        src = iops[op]
        sm = smods.get(op, {})
        hidden = src["hidden"] or sm.get("status") == "hidden"
        ctrls = []
        walk(sm.get("controls", []), ctrls)
        val = [c for c in ctrls if c["kind"] in ("slider", "combobox", "toggle")]
        bound = [c for c in val if c.get("param") is not None or c.get("bound_to") is not None or (op, c["id"]) in fixed]
        def by_panel(table, label):
            return op in table and (table[op] is None or label in table[op])
        open_ = [{"label": (c.get("label") or "").strip(), "kind": c["kind"],
                  "note": notes.get((op, c["id"]), "no override says"),
                  "built_in_app_panel": (op, c["id"]) in built}
                 for c in val if c not in bound]
        marks = [c for c in ctrls if (c.get("quad") or {}).get("colorpicker") or c["kind"] == "colorpicker_button"]
        pk_missing = [(c.get("label") or c.get("tooltip") or "").strip()[:40] for c in marks
                      if c["id"] not in pickers.get(op, set())
                      and not by_panel(APP_PANEL_PICKERS, (c.get("label") or c.get("tooltip") or "").strip())]
        graphs = [(c.get("tooltip") or "").strip()[:40] for c in ctrls if c["kind"] == "graph"]
        buttons = [(c.get("label") or c.get("tooltip") or "").strip()[:40] for c in ctrls if c["kind"] == "button"]
        oi = sm.get("on_image") or {}
        rec = {
            "op": op,
            "name": sm.get("name", op),
            "source": src["source"],
            "version": sm.get("version"),
            "group": "hidden" if hidden else ("deprecated" if src["deprecated"] else groups.get(op, "no group (search only)")),
            "flags": {k: src[k] for k in ("deprecated", "hidden", "one_instance", "default_visible", "deprecated_msg",
                                         "deprecated_flag_in_default_group")},
            "default_enabled": sm.get("default_enabled"),
            "schema": {
                "status": sm.get("status"),
                "controls": len(val), "usable": len(bound), "unbound_open": open_,
                "pickers_marked": len(marks), "pickers_missing": pk_missing,
                "graphs": graphs, "buttons": buttons,
                "on_photo_on_desktop": bool(oi.get("button_pressed") or oi.get("scrolled") or oi.get("mouse_moved")),
                "scalar_params_unbound": (sm.get("stats") or {}).get("scalar_params_unbound", []),
                "array_values": (sm.get("stats") or {}).get("array_param_values", 0),
                "array_values_bound": (sm.get("stats") or {}).get("array_param_values_bound", 0),
            },
            "app": {"graph_editor": graphs_app.get(op), "tool": tools_app.get(op)},
        }
        for cls in ("bayer", "xtrans"):
            r, d = render_cell(op, cls, res)
            rec[f"render_{cls}"] = {"result": r, "detail": d}
        if op in NEEDS_INPUT:
            rec["needs_input"] = NEEDS_INPUT[op] or "no test"
        if hidden:
            rec.update(editing="n/a", size="-", gap="hidden on desktop too: darktable places and sets it itself", tests="")
        else:
            if op not in FINDINGS:
                die(f"no finding for {op}")
            e, s, g, t = FINDINGS[op]
            rec.update(editing=e, size=s, gap=g, tests=t)
        records.append(rec)

    out = {
        "brief": "P5a",
        "darktable_commit": schema.get("darktable_version"),
        "generator": "tools/module-audit.py",
        "modules": records,
        "counts": {
            "total": len(records),
            "hidden": sum(r["group"] == "hidden" for r in records),
            "deprecated": sum(r["group"] == "deprecated" for r in records),
            "editing": {k: sum(r["editing"] == k for r in records) for k in ("works", "partly", "missing", "n/a")},
            "editing_current": {k: sum(r["editing"] == k for r in records if r["group"] not in ("hidden", "deprecated"))
                                for k in ("works", "partly", "missing")},
            "renders": {cls: {k: sum(r[f"render_{cls}"]["result"] == k for r in records) for k in ("yes", "no", "not checked")}
                        for cls in ("bayer", "xtrans")},
        },
    }
    text = json.dumps(out, indent=1, ensure_ascii=False) + "\n"
    target = rdir / "modules.json"
    if check:
        if not target.exists() or target.read_text() != text:
            die(f"{target} differs from what the sources give now")
        print("modules.json unchanged")
        return 0
    rdir.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    print(json.dumps(out["counts"]))
    if report:
        write_tables(Path(report), records)
    return 0


def rcell(r):
    def one(c):
        x = r[f"render_{c}"]
        if x["result"] == "yes":
            return "T1" if "T1" in x["detail"] else ("C40, identical" if x["detail"].startswith("max 0,") else f"C40, {x['detail']}")
        return f"{x['result']}: {x['detail']}"
    b, x = r["render_bayer"], r["render_xtrans"]
    sb, sx = one("bayer"), one("xtrans")
    if b["result"] == x["result"] == "yes":
        s = f"yes ({sb})" if sb == sx else f"yes (Bayer {sb}; X-Trans {sx})"
    else:
        s = f"Bayer {'yes (' + sb + ')' if b['result'] == 'yes' else sb}; X-Trans {'yes (' + sx + ')' if x['result'] == 'yes' else sx}"
    if r.get("needs_input"):
        s += f"; with input: {'not checked' if r['needs_input'] == 'no test' else r['needs_input']}"
    return s


def write_tables(path, records):
    def row(r):
        gap = r["gap"] or "nothing"
        if r["tests"]:
            gap += f" (tests: {r['tests']})"
        return f"| {r['name']} (`{r['op']}`) | {rcell(r)} | {r['editing']} | {gap} | {r['size']} |"
    head = "| module | renders as desktop | editing on the phone | what is missing and why | size |\n|---|---|---|---|---|"
    lines = []
    for g in GROUP_ORDER:
        rs = sorted((r for r in records if r["group"] == g), key=lambda r: r["name"].lower())
        if rs:
            lines += [f"### {g}", "", head] + [row(r) for r in rs] + [""]
    rs = sorted((r for r in records if r["group"] == "deprecated"), key=lambda r: r["name"].lower())
    lines += ["### deprecated (darktable shows them only in an edit that already uses them)", "", head] + [row(r) for r in rs] + [""]
    rs = sorted((r for r in records if r["group"] == "hidden"), key=lambda r: r["name"].lower())
    lines += ["### hidden (no panel on desktop either; darktable sets them itself)", "",
              "| module | renders as desktop |", "|---|---|"] + [f"| {r['name']} (`{r['op']}`) | {rcell(r)} |" for r in rs] + [""]
    t = path.read_text()
    a, b = "<!-- p5a:tables:start -->", "<!-- p5a:tables:end -->"
    if a not in t or b not in t:
        die(f"{path} has no table markers")
    path.write_text(t[:t.index(a) + len(a)] + "\n" + "\n".join(lines) + t[t.index(b):])


if __name__ == "__main__":
    sys.exit(main(sys.argv))

# SPDX-License-Identifier: GPL-3.0-or-later
"""List every `[]` subscript on an OpenCL vector (e.g. float4 v; v[i]) in darktable's kernels.

Subscripting a vector is a Clang extension, not OpenCL C; the Adreno compiler refuses it
(brief P4e). The check compiles each kernels/*.cl with clang as OpenCL C 2.0, the way darktable
builds them on the device (include path = the kernel folder, -cl-fast-relaxed-math), dumps the
AST as JSON and reports each ArraySubscriptExpr whose base expression has a vector type.

Usage: python3 -I tools/check-vector-subscripts.py <darktable>/data/kernels [clang]
Prints one line per place (file:line:col  base type  source line) and exits 1 if any is found,
2 if a kernel file does not compile with clang.
"""
import json, os, subprocess, sys

def main():
    kdir = os.path.abspath(sys.argv[1])
    clang = sys.argv[2] if len(sys.argv) > 2 else "clang"
    # the build copies src/common/hdr-transfer.h next to the kernels (data/kernels/CMakeLists.txt)
    common = os.path.join(os.path.dirname(os.path.dirname(kdir)), "src", "common")
    found = {}
    failed = []
    for name in sorted(os.listdir(kdir)):
        if not name.endswith(".cl"):
            continue
        path = os.path.join(kdir, name)
        r = subprocess.run([clang, "-x", "cl", "-cl-std=CL2.0", "-cl-fast-relaxed-math", "-I", kdir, "-I", common,
                            "-fsyntax-only", "-Xclang", "-ast-dump=json", path],
                           capture_output=True, text=True)
        if r.returncode != 0:
            failed.append((name, r.stderr.strip().splitlines()[:5]))
            continue
        ast = json.loads(r.stdout)
        # clang's JSON omits a location's file when it equals the previous one: track it in walk order
        state = {"file": path, "line": 0}

        def loc_of(l):
            # a location: either plain, or split into spellingLoc/expansionLoc (macros), written in that order
            res = None
            for part in ([l["spellingLoc"], l.get("expansionLoc", {})] if "spellingLoc" in l else [l]):
                if "file" in part: state["file"] = part["file"]
                if "line" in part: state["line"] = part["line"]
                if res is None: res = (state["file"], state["line"], part.get("col", 0))
            return res

        def is_loc(d):
            return "spellingLoc" in d or "offset" in d

        def walk(n):
            # every location in serialization order, because clang leaves out a file or line equal to
            # the previous one it wrote
            if isinstance(n, list):
                for c in n: walk(c)
                return
            if not isinstance(n, dict):
                return
            if is_loc(n):
                loc_of(n)
                return
            here = None
            for k, v in n.items():
                if k == "range" and isinstance(v, dict) and "begin" in v:
                    here = loc_of(v["begin"])
                    if "end" in v: loc_of(v["end"])
                elif isinstance(v, (dict, list)) and k != "inner":
                    walk(v)
            if n.get("kind") == "ArraySubscriptExpr" and n.get("inner"):
                base = n["inner"][0]
                t = base.get("type", {})
                qt = t.get("desugaredQualType", t.get("qualType", ""))
                if "ext_vector_type" in qt and "*" not in qt and here:
                    found.setdefault(here, t.get("qualType", qt))
            walk(n.get("inner", []))
        walk(ast)
    for (f, line, col), t in sorted(found.items()):
        try:
            src = open(f).read().splitlines()[line - 1].strip()
        except Exception:
            src = ""
        print(f"{os.path.relpath(f, os.path.dirname(os.path.dirname(kdir)))}:{line}:{col}\t{t}\t{src}")
    print(f"# {len(found)} vector subscripts", file=sys.stderr)
    for name, err in failed:
        print(f"# clang failed on {name}: " + " | ".join(err), file=sys.stderr)
    sys.exit(2 if failed else (1 if found else 0))

main()

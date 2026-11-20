#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Color harmonizer's two hue tables (darktable UCS hue <-> RYB hue), computed by upstream's own code (brief P2j).

    python3 -I tools/colorharmonizer-hue-tables.py <darktable source dir>            # print the tables as JSON
    python3 -I tools/colorharmonizer-hue-tables.py <darktable source dir> --check    # compare with the override

src/iop/colorharmonizer.c builds s_ucs_to_ryb_lut and s_ryb_to_ucs_lut once at load (_build_hue_luts: a gamut
search in darktable UCS per entry, then a nearest-match inversion) and converts the anchor and custom hue sliders
through them (_ucs_to_ryb_fast, _ryb_to_ucs_fast). This script copies those functions verbatim, compiles them
against upstream's src/common/colorspaces_inline_conversions.h and color_ryb.h (with small stand-ins for the
headers they include: types, constants, SSE intrinsics) and prints the two 720-entry tables. The app
(app/schema Formulas.RybHue) does the table lookups; schema/overrides/colorharmonizer.json carries the tables.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DTTYPES = r'''#pragma once
#include <stdint.h>
#include <stddef.h>
#include <xmmintrin.h>
#include <emmintrin.h>
typedef int gboolean;
#define TRUE 1
#define FALSE 0
#define DT_ALIGNED_PIXEL __attribute__((aligned(16)))
#define DT_ALIGNED_ARRAY __attribute__((aligned(64)))
typedef DT_ALIGNED_PIXEL float dt_aligned_pixel_t[4];
typedef DT_ALIGNED_PIXEL float dt_colormatrix_t[4][4];
#define for_each_channel(_var, ...) for(size_t _var = 0; _var < 4; _var++)
#define for_four_channels(_var, ...) for(size_t _var = 0; _var < 4; _var++)
#define DT_OMP_DECLARE_SIMD(...)
#define DT_OMP_SIMD(...)
#define DT_OMP_FOR(...)
#define DT_OMP_FOR_SIMD(...)
#define DT_OMP_PRAGMA(...)
#define MAX(a,b) ((a)>(b)?(a):(b))
#define MIN(a,b) ((a)<(b)?(a):(b))
#define CLAMP(x,lo,hi) ((x)<(lo)?(lo):((x)>(hi)?(hi):(x)))
'''
COLORSPACES = r'''#pragma once
#include "common/dttypes.h"
typedef struct { double x, y, Y; } cmsCIExyY;
static const cmsCIExyY D50xyY = {0.34567, 0.35850, 1.0};
static const cmsCIExyY D65xyY = {0.31271, 0.32902, 1.0};
'''


def func(text, name, prefix=r"static (?:inline )?"):
    """The definition (not a declaration) of a static function: its text up to the matching brace."""
    m = None
    for c in re.finditer(prefix + r"[^\n;{]*\b%s\(" % re.escape(name), text):
        close = text.index(")", c.end())
        nxt = text[close + 1:].lstrip()
        if nxt.startswith("{"):
            m = c
            break
    if not m:
        sys.exit("function %s not found" % name)
    i = text.index("{", m.end())
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[m.start():j + 1] + "\n"
    sys.exit("unbalanced %s" % name)


def tables(src):
    d = tempfile.mkdtemp()
    os.makedirs(os.path.join(d, "stub/common"))
    open(os.path.join(d, "stub/common/dttypes.h"), "w").write(DTTYPES)
    open(os.path.join(d, "stub/common/colorspaces.h"), "w").write(COLORSPACES)
    mh = open(os.path.join(src, "src/common/math.h"), encoding="utf-8").read()
    dt = open(os.path.join(src, "src/common/dttypes.h"), encoding="utf-8").read()
    out = ["#pragma once", "#include <math.h>", "#include <float.h>", '#include "common/dttypes.h"']
    for n in ("min3f", "max3f"):
        out.append(func(dt, n, r"static inline "))
    out += [l for l in re.findall(r"^#define [^\n]*$", mh, re.M) if not l.endswith("\\")]
    for n in ("sqf", "fcube", "sqrf", "dt_fast_hypotf", "scalar_product", "dot_product", "euclidean_norm",
              "downscale_vector", "upscale_vector", "mat3mulv", "feqf", "dt_vector_max", "dt_vector_min", "dt_vector_round", "dt_vector_log2", "dt_vector_exp2", "dt_vector_powf",
              "dt_vector_clipneg"):
        try:
            out.append(func(mh, n, r"static inline "))
        except SystemExit:
            pass
    open(os.path.join(d, "stub/common/math.h"), "w").write("\n".join(out) + "\n")
    ch = open(os.path.join(src, "src/iop/colorharmonizer.c"), encoding="utf-8").read()
    steps = re.search(r"#define COLORHARMONIZER_RYB_INVERSE_STEPS (\d+)", ch).group(1)
    code = ['#include <stdio.h>', '#include "common/colorspaces_inline_conversions.h"', '#include "common/color_ryb.h"',
            "#define COLORHARMONIZER_RYB_INVERSE_STEPS %s" % steps,
            "static float s_ucs_to_ryb_lut[COLORHARMONIZER_RYB_INVERSE_STEPS];",
            "static float s_ryb_to_ucs_lut[COLORHARMONIZER_RYB_INVERSE_STEPS];"]
    for n in ("_find_max_chroma", "_hue_to_srgb", "_ucs_hue_to_ryb_hue", "_hue_lerp", "_ucs_to_ryb_fast", "_ryb_to_ucs_fast",
              "_build_hue_luts"):
        code.append(func(ch, n))
    code.append(r'''
int main(void)
{
  _build_hue_luts();
  printf("{\"steps\": %d,\n \"ucs_to_ryb\": [", COLORHARMONIZER_RYB_INVERSE_STEPS);
  for(int i = 0; i < COLORHARMONIZER_RYB_INVERSE_STEPS; i++) printf("%s%.9g", i ? ", " : "", s_ucs_to_ryb_lut[i]);
  printf("],\n \"ryb_to_ucs\": [");
  for(int i = 0; i < COLORHARMONIZER_RYB_INVERSE_STEPS; i++) printf("%s%.9g", i ? ", " : "", s_ryb_to_ucs_lut[i]);
  printf("],\n \"samples\": [");
  const float v[] = { 0.f, 0.1f, 0.25f, 0.333f, 0.5f, 0.61f, 0.75f, 0.9f, 0.999f };
  for(int i = 0; i < 9; i++)
    printf("%s{\"x\": %.9g, \"ucs_to_ryb\": %.9g, \"ryb_to_ucs\": %.9g}", i ? ", " : "", v[i], _ucs_to_ryb_fast(v[i]), _ryb_to_ucs_fast(v[i]));
  printf("]}\n");
  return 0;
}
''')
    c = os.path.join(d, "t.c")
    open(c, "w").write("\n".join(code))
    exe = os.path.join(d, "t")
    subprocess.check_call(["gcc", "-O0", "-std=gnu11", "-I" + os.path.join(d, "stub"), "-I" + os.path.join(src, "src"),
                           "-o", exe, c, "-lm"])
    return json.loads(subprocess.check_output([exe]).decode())


def main():
    t = tables(sys.argv[1])
    samples = t.pop("samples")
    if "--samples" in sys.argv:
        print(json.dumps(samples))
        return
    if "--check" in sys.argv:
        o = json.load(open(os.path.join(REPO, "schema/overrides/colorharmonizer.json"), encoding="utf-8"))["hue_tables"]
        if o != t:
            sys.exit("schema/overrides/colorharmonizer.json's hue_tables differ from upstream's")
        print("color harmonizer hue tables match upstream (%d entries)" % t["steps"])
        return
    print(json.dumps(t))


if __name__ == "__main__":
    main()

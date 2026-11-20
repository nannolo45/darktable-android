#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference values for brief P2j's round-trip tests, computed by upstream darktable's own C functions.

    python3 -I tools/p2j-reference-vectors.py <darktable source dir> > app/schema/src/test/resources/p2j-reference.json

Copies the named static inline functions verbatim out of the upstream sources (src/common/colorspaces_inline_conversions.h,
src/common/illuminants.h, src/iop/satcurvergb.c, src/iop/toneequal.c's two expressions), compiles them with a small
driver (gcc -O0, no fast-math) and prints JSON: for each case the inputs and what desktop's callback writes and what
its gui_update shows. Nothing of upstream is changed; the source dir is only read. The JVM tests compare
app/schema Formulas against these numbers.
"""
import json
import os
import re
import subprocess
import sys
import tempfile


def func(text, name):
    """The full text of `static inline <type> name(...) { ... }` (brace matched)."""
    m = re.search(r"static inline [^\n;{]*\b%s\(" % re.escape(name), text)
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


def main():
    src = sys.argv[1]
    conv = open(os.path.join(src, "src/common/colorspaces_inline_conversions.h"), encoding="utf-8").read()
    ill = open(os.path.join(src, "src/common/illuminants.h"), encoding="utf-8").read()
    sat = open(os.path.join(src, "src/iop/satcurvergb.c"), encoding="utf-8").read()
    dth = open(os.path.join(src, "src/common/darktable.h"), encoding="utf-8").read()
    code = ["#include <math.h>", "#include <stdio.h>", "#include <float.h>", "#include <stddef.h>", "#include <stdint.h>", "#include <string.h>",
            "#define DT_INITHASH 5381", "typedef uint64_t dt_hash_t;",
            "typedef float dt_aligned_pixel_t[4];", "#define DT_ALIGNED_PIXEL", "#define DT_OMP_DECLARE_SIMD(...)",
            "#define DT_OMP_FOR(...)", "#define DT_2PI_F 6.2831853071795864769252867665590057683943388f",
            "#define M_SQRT2_F 1.41421356237309504880f", "#define MAX(a,b) ((a)>(b)?(a):(b))",
            "#define CLAMP(x,lo,hi) ((x)<(lo)?(lo):((x)>(hi)?(hi):(x)))",
            "static inline float dt_fast_hypotf(float x, float y) { return sqrtf(x * x + y * y); }"]
    for n in ("cbf", "dt_xyY_to_uvY", "dt_xyY_to_Luv", "dt_Luv_to_Lch", "dt_xyY_to_Lch", "dt_uvY_to_xyY",
              "dt_Luv_to_xyY", "dt_Lch_to_Luv", "dt_Lch_to_xyY"):
        code.append(func(conv, n))
    for n in ("xy_to_CCT", "CCT_to_xy_daylight", "CCT_to_xy_blackbody"):
        code.append(func(ill, n))
    # CCT_reverse_lookup uses an OpenMP reduction on a struct; its loop is copied with the reduction removed
    rl = func(ill, "CCT_reverse_lookup")
    rl = re.sub(r"#if !\(defined.*?#endif\n", "", rl, flags=re.S)
    code.append("typedef struct pair { float radius; float temperature; } pair;\n"
                "static struct pair pair_min(struct pair r, struct pair n) { if(n.radius < r.radius) return n; else return r; }\n")
    code.append(rl)
    code.append(func(dth, "dt_hash"))
    code.append(func(sat, "protect_center_width_to_from_to"))
    code.append(func(sat, "protect_from_to_to_center_width"))
    code.append(r'''
static void illum(float hue_deg, float chroma, int first)
{
  /* _illum_xy_callback, src/iop/channelmixerrgb.c */
  dt_aligned_pixel_t Lch = { 100.f, chroma, hue_deg * (float)M_PI / 180.f, 0.f };
  dt_aligned_pixel_t xyY = { 0 };
  dt_Lch_to_xyY(Lch, xyY);
  float t = xy_to_CCT(xyY[0], xyY[1]);
  if(t < 3000.f) t = CCT_reverse_lookup(xyY[0], xyY[1]);
  /* gui_changed: back to hue and chroma */
  const dt_aligned_pixel_t back = { xyY[0], xyY[1], 1.f };
  dt_aligned_pixel_t L2;
  dt_xyY_to_Lch(back, L2);
  printf("%s{\"hue\": %.9g, \"chroma\": %.9g, \"x\": %.9g, \"y\": %.9g, \"temperature\": %.9g, \"hue_back\": %.9g, \"chroma_back\": %.9g}",
         first ? "" : ",\n    ", hue_deg, chroma, xyY[0], xyY[1], t, L2[2] * 180.f / (float)M_PI, L2[1]);
}
static void xyshow(float x, float y, int first)
{
  const dt_aligned_pixel_t xyY = { x, y, 1.f };
  dt_aligned_pixel_t L;
  dt_xyY_to_Lch(xyY, L);
  printf("%s{\"x\": %.9g, \"y\": %.9g, \"hue\": %.9g, \"chroma\": %.9g}", first ? "" : ",\n    ", x, y, L[2] * 180.f / (float)M_PI, L[1]);
}
static void protect(float c, float w, int first)
{
  float f, t, c2, w2;
  protect_center_width_to_from_to(c, w, &f, &t);
  protect_from_to_to_center_width(f, t, &c2, &w2);
  printf("%s{\"center\": %.9g, \"width\": %.9g, \"from\": %.9g, \"to\": %.9g, \"center_back\": %.9g, \"width_back\": %.9g}",
         first ? "" : ",\n    ", c, w, f, t, c2, w2);
}
static void smooth(float v, int first)
{
  /* smoothing_callback and gui_update, src/iop/toneequal.c */
  const float s = powf(M_SQRT2_F, 1.0f + v);
  printf("%s{\"slider\": %.9g, \"smoothing\": %.9g, \"slider_back\": %.9g}", first ? "" : ",\n    ", v, s,
         logf(s) / logf(M_SQRT2_F) - 1.0f);
}
static void name_hash(const char *s, int first)
{
  /* _name_hash, src/iop/spektrafilm.c */
  const dt_hash_t h = dt_hash(DT_INITHASH, s, strlen(s));
  uint32_t h32 = (uint32_t)(h ^ (h >> 32));
  if(!h32) h32 = 1;
  printf("%s{\"stock\": \"%s\", \"hash\": %u}", first ? "" : ",\n    ", s, h32);
}
int main(void)
{
  const float hues[] = { 0.f, 28.5f, 45.f, 90.f, 135.f, 200.f, 270.f, 330.f, 359.f };
  const float chromas[] = { 5.f, 20.f, 29.16f, 60.f };
  printf("{\n  \"illuminant\": [\n    ");
  int first = 1;
  for(int i = 0; i < 9; i++) for(int j = 0; j < 4; j++) { illum(hues[i], chromas[j], first); first = 0; }
  printf("\n  ],\n  \"illuminant_show\": [\n    ");
  xyshow(0.3457f, 0.3585f, 1); xyshow(0.333f, 0.333f, 0); xyshow(0.4476f, 0.4074f, 0); xyshow(0.25f, 0.27f, 0);
  printf("\n  ],\n  \"protect\": [\n    ");
  protect(0.f, 0.f, 1); protect(0.1f, 0.05f, 0); protect(0.25f, 0.3f, 0); protect(0.45f, 0.2f, 0); protect(0.02f, 0.1f, 0);
  printf("\n  ],\n  \"smoothing\": [\n    ");
  smooth(0.f, 1); smooth(-1.f, 0); smooth(1.f, 0); smooth(-2.33f, 0); smooth(0.37f, 0);
  printf("\n  ],\n  \"name_hash\": [\n    ");
  const char *stocks[] = { "kodak_gold_200", "kodak_portra_400", "kodak_doublex", "kodak_2302", "fujifilm_provia_100f",
                           "kodak_endura_premier", "kodak_vision3_250d", "kodak_portra_endura" };
  for(int i = 0; i < 8; i++) name_hash(stocks[i], i == 0);
  printf("\n  ]\n}\n");
  return 0;
}
''')
    d = tempfile.mkdtemp()
    c = os.path.join(d, "ref.c")
    open(c, "w").write("\n".join(code))
    exe = os.path.join(d, "ref")
    subprocess.check_call(["gcc", "-O0", "-std=gnu11", "-o", exe, c, "-lm"])
    sys.stdout.write(subprocess.check_output([exe]).decode())


if __name__ == "__main__":
    main()

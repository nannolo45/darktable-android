#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference values for brief P3c deliverable 3 (pickers that set sliders), computed by upstream darktable's own code.

    python3 -I tools/p3c-picker-reference.py <darktable source dir> > app/schema/src/test/resources/p3c-picker-reference.json

Two programs. The first copies, verbatim, out of src/iop/filmicrgb.c the enums, the parameter structure, `pixel_rgb_norm_power`,
`get_pixel_norm`, `_compute_output_power`, `apply_auto_grey`, `apply_auto_black`, `apply_auto_white_point_source`
and `apply_autotune` (and `sqf` out of src/common/math.h, `max3f` out of src/common/dttypes.h). They are compiled with a small driver (gcc -O0,
no fast-math) whose stubs stand for the desktop interface (sliders, history, redraws) and for the module's
introspection (`output_power`'s limits 1 and 10, as the parameter structure declares them) and working profile
(linear Rec2020's luminance row, as app/schema PickerFormulas.REC2020_Y). The driver runs every picker on a set of
picked values (mean, min, max) and parameters and prints JSON: the inputs and the four parameters desktop writes.
The second copies out of src/iop/colorbalancergb.c the parameter structure, `pipe_RGB_to_Ych` and
`color_picker_apply` (the hue pickers of offset, lift, power and gain, white fulcrum, contrast gray fulcrum), with the
colour conversions they call out of src/common/colorspaces_inline_conversions.h and chromatic_adaptation.h, the
working profile linear Rec2020 (app/schema PickerFormulas.REC2020) standing for `dt_ioppr_rgb_matrix_to_xyz`.
Nothing of upstream is changed; the source dir is only read. The JVM test P3cPickerTest compares app/schema
PickerFormulas against these numbers.
"""
import json
import os
import re
import subprocess
import sys
import tempfile


def block(text, start_pat, what):
    m = next((x for x in re.finditer(start_pat, text, flags=re.M)
              if ";" not in text[x.start():text.index("{", x.start())]), None)
    if not m:
        sys.exit("%s not found" % what)
    i = text.index("{", m.start())
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                end = j + 1
                if text[m.start():m.start() + 7] == "typedef":
                    end = text.index(";", end) + 1
                return text[m.start():end] + "\n"
    sys.exit("unbalanced %s" % what)


def func(text, name):
    return block(text, r"^(static )?(inline )?[a-zA-Z_][a-zA-Z0-9_ \*]*\b%s\s*\(" % re.escape(name), name)


def const(text, name):
    """`static const <type> name = { ... };` as it is"""
    m = re.search(r"^static const [a-zA-Z_]+ %s\b" % re.escape(name), text, flags=re.M)
    if not m:
        sys.exit("%s not found" % name)
    return text[m.start():text.index(";", m.start()) + 1] + "\n"


def typedef(text, name):
    return block(text, r"^typedef (struct|enum) %s\b" % re.escape(name), name)


STUBS = r"""
#include <math.h>
#include <stdio.h>
#include <string.h>
typedef int gboolean;
typedef float dt_aligned_pixel_t[4];
#define TRUE 1
#define FALSE 0
#define DT_OMP_DECLARE_SIMD(...)
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
#define CLAMP(x,lo,hi) (((x)>(hi))?(hi):(((x)<(lo))?(lo):(x)))
#define CLAMPF(a, mn, mx) ((a) >= (mn) ? ((a) <= (mx) ? (a) : (mx)) : (mn))
#define NORM_MIN 1.52587890625e-05f
#define INVERSE_SQRT_3 0.5773502691896258f
#define DT_NOISE_UNIFORM 0
#define DT_NOISE_GAUSSIAN 1
#define DT_NOISE_POISSONIAN 2
/* the desktop interface: no-ops (their arguments are never evaluated) */
#define DT_TRY_GUI_UPDATE(...) do {} while(0)
#define DT_GUARD_GUI_UPDATE(...) do {} while(0)
#define DT_ENTER_GUI_UPDATE() do {} while(0)
#define DT_LEAVE_GUI_UPDATE() do {} while(0)
#define dt_bauhaus_slider_set(...) do {} while(0)
#define gtk_widget_queue_draw(...) do {} while(0)
#define dt_dev_add_history_item(...) do {} while(0)
typedef struct { float matrix_in[4][4]; void *lut_in; void *unbounded_coeffs_in; int lutsize, nonlinearlut; } dt_iop_order_iccprofile_info_t;
/* linear Rec2020's luminance row (D50), as app/schema PickerFormulas.REC2020_Y */
static const dt_iop_order_iccprofile_info_t rec2020 = { { { 0 }, { 0.2790374756f, 0.6753540039f, 0.0456085205f, 0 }, { 0 }, { 0 } }, 0, 0, 0, 0 };
static float dt_ioppr_get_rgb_matrix_luminance(const float *rgb, const float matrix_in[4][4], const void *lut,
                                               const void *coeffs, int lutsize, int nonlinear)
{
  (void)lut; (void)coeffs; (void)lutsize; (void)nonlinear;
  return matrix_in[1][0] * rgb[0] + matrix_in[1][1] * rgb[1] + matrix_in[1][2] * rgb[2];
}
static float dt_camera_rgb_luminance(const float *rgb) { return dt_ioppr_get_rgb_matrix_luminance(rgb, rec2020.matrix_in, 0, 0, 0, 0); }
/* the module: its parameters, the picked values, its interface and the introspection of output_power */
typedef struct { struct { float Min, Max; } Float; } dt_introspection_field_t;
static dt_introspection_field_t output_power_field = { { 1.0f, 10.0f } };
static dt_introspection_field_t *get_f(const char *name) { (void)name; return &output_power_field; }
typedef struct { dt_introspection_field_t *(*get_f)(const char *); } dt_iop_module_so_t;
typedef struct { void *iop; } dt_develop_t;
typedef struct dt_iop_module_t
{
  void *params, *gui_data;
  dt_aligned_pixel_t picked_color, picked_color_min, picked_color_max;
  dt_develop_t *dev;
  dt_iop_module_so_t *so;
} dt_iop_module_t;
static const dt_iop_order_iccprofile_info_t *dt_ioppr_get_iop_work_profile_info(dt_iop_module_t *m, void *iop)
{
  (void)m; (void)iop; return &rec2020;
}
typedef struct { void *grey_point_source, *black_point_source, *white_point_source, *output_power, *area; } dt_iop_filmicrgb_gui_data_t;
#define GTK_WIDGET(x) (x)
"""

DRIVER = r"""
static void run(const char *formula, const float mean[3], const float mn[3], const float mx[3], float grey, float black,
                float white, float security, float target, dt_iop_filmicrgb_methods_type_t norm, gboolean custom_grey, int first)
{
  dt_iop_filmicrgb_params_t p;
  memset(&p, 0, sizeof(p));
  p.grey_point_source = grey; p.black_point_source = black; p.white_point_source = white;
  p.security_factor = security; p.grey_point_target = target; p.preserve_color = norm; p.custom_grey = custom_grey;
  p.output_power = 4.0f;
  dt_iop_filmicrgb_gui_data_t g = { 0 };
  dt_develop_t dev = { 0 };
  dt_iop_module_so_t so = { get_f };
  dt_iop_module_t m;
  memset(&m, 0, sizeof(m));
  m.params = &p; m.gui_data = &g; m.dev = &dev; m.so = &so;
  for(int c = 0; c < 3; c++) { m.picked_color[c] = mean[c]; m.picked_color_min[c] = mn[c]; m.picked_color_max[c] = mx[c]; }
  if(!strcmp(formula, "filmic_grey")) apply_auto_grey(&m);
  else if(!strcmp(formula, "filmic_black")) apply_auto_black(&m);
  else if(!strcmp(formula, "filmic_white")) apply_auto_white_point_source(&m);
  else apply_autotune(&m);
  printf("%s{\"formula\":\"%s\",\"mean\":[%.9g,%.9g,%.9g],\"min\":[%.9g,%.9g,%.9g],\"max\":[%.9g,%.9g,%.9g],"
         "\"grey\":%.9g,\"black\":%.9g,\"white\":%.9g,\"security\":%.9g,\"target\":%.9g,\"norm\":%d,\"custom_grey\":%d,"
         "\"out\":{\"grey\":%.9g,\"black\":%.9g,\"white\":%.9g,\"power\":%.9g}}\n",
         first ? "" : ",", formula, mean[0], mean[1], mean[2], mn[0], mn[1], mn[2], mx[0], mx[1], mx[2],
         grey, black, white, security, target, (int)norm, custom_grey,
         p.grey_point_source, p.black_point_source, p.white_point_source, p.output_power);
}

int main(void)
{
  static const float picks[][9] = {
    { 0.18f, 0.20f, 0.15f, 0.010f, 0.012f, 0.008f, 1.8f, 2.1f, 1.5f },
    { 0.05f, 0.04f, 0.06f, 0.0004f, 0.0003f, 0.0005f, 0.6f, 0.5f, 0.7f },
    { 0.40f, 0.35f, 0.30f, 0.12f, 0.10f, 0.09f, 9.0f, 8.0f, 7.5f },
    { 0.01f, 0.012f, 0.009f, 0.00001f, 0.00002f, 0.00001f, 0.05f, 0.06f, 0.04f },
  };
  static const struct { float grey, black, white, security, target; } params[] = {
    { 18.45f, -8.0f, 4.0f, 0.0f, 18.45f },
    { 9.0f, -10.0f, 6.5f, 25.0f, 18.45f },
    { 30.0f, -6.0f, 3.0f, -20.0f, 12.0f },
  };
  const char *formulas[] = { "filmic_grey", "filmic_black", "filmic_white", "filmic_autotune" };
  const dt_iop_filmicrgb_methods_type_t norms[] = { DT_FILMIC_METHOD_POWER_NORM, DT_FILMIC_METHOD_MAX_RGB,
    DT_FILMIC_METHOD_LUMINANCE, DT_FILMIC_METHOD_EUCLIDEAN_NORM_V1, DT_FILMIC_METHOD_EUCLIDEAN_NORM_V2, DT_FILMIC_METHOD_NONE };
  int first = 1;
  printf("[\n");
  for(size_t i = 0; i < sizeof(picks) / sizeof(picks[0]); i++)
    for(size_t k = 0; k < sizeof(params) / sizeof(params[0]); k++)
      for(int f = 0; f < 4; f++)
        for(size_t n = 0; n < sizeof(norms) / sizeof(norms[0]); n++)
          for(int cg = 0; cg < 2; cg++)
          {
            if(cg && f != 3) continue;
            run(formulas[f], picks[i], picks[i] + 3, picks[i] + 6, params[k].grey, params[k].black, params[k].white,
                params[k].security, params[k].target, norms[n], cg, first);
            first = 0;
          }
  printf("]\n");
  return 0;
}
"""

CB_STUBS = r"""
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <stddef.h>
typedef int gboolean;
typedef float dt_aligned_pixel_t[4];
typedef float dt_colormatrix_t[4][4];
typedef int GdkModifierType;
typedef void GtkWidget;
#define GDK_CONTROL_MASK 4
#define M_PI_F 3.14159265358979323846f
#define DT_OMP_DECLARE_SIMD(...)
#define for_each_channel(_var, ...) for(size_t _var = 0; _var < 4; _var++)
#define for_four_channels(_var, ...) for(size_t _var = 0; _var < 4; _var++)
#define for_three_channels(_var, ...) for(size_t _var = 0; _var < 3; _var++)
#define DT_ENTER_GUI_UPDATE() do {} while(0)
#define DT_LEAVE_GUI_UPDATE() do {} while(0)
#define dt_bauhaus_slider_set(...) do {} while(0)
#define dt_dev_add_history_item(...) do {} while(0)
#define dt_print(...) do {} while(0)
#define dt_key_modifier_state() 0
typedef struct { dt_colormatrix_t matrix_in_transposed; void *lut_in, *unbounded_coeffs_in; int lutsize, nonlinearlut; } dt_iop_order_iccprofile_info_t;
/* linear Rec2020 to XYZ D50, transposed (as app/schema PickerFormulas.REC2020) */
static const dt_iop_order_iccprofile_info_t rec2020 = {
  { { 0.6734771729f, 0.2790374756f, -0.0019378662f, 0.f },
    { 0.1656799316f, 0.6753540039f, 0.0299835205f, 0.f },
    { 0.1250457764f, 0.0456085205f, 0.7968444824f, 0.f } }, 0, 0, 0, 0 };
typedef struct dt_iop_module_t { void *params, *gui_data; dt_aligned_pixel_t picked_color, picked_color_min, picked_color_max; } dt_iop_module_t;
typedef struct dt_dev_pixelpipe_t dt_dev_pixelpipe_t;
static const dt_iop_order_iccprofile_info_t *dt_ioppr_get_pipe_current_profile_info(dt_iop_module_t *m, dt_dev_pixelpipe_t *p)
{
  (void)m; (void)p; return &rec2020;
}
static void dt_ioppr_rgb_matrix_to_xyz(const dt_aligned_pixel_t rgb, dt_aligned_pixel_t xyz, const dt_colormatrix_t t,
                                       void *lut, void *coeffs, int lutsize, int nonlinear)
{
  (void)lut; (void)coeffs; (void)lutsize; (void)nonlinear;
  for(int c = 0; c < 3; c++) xyz[c] = t[0][c] * rgb[0] + t[1][c] * rgb[1] + t[2][c] * rgb[2];
}
typedef struct { GtkWidget *global_H, *global_C, *shadows_H, *shadows_C, *midtones_H, *midtones_C, *highlights_H,
                 *highlights_C, *white_fulcrum, *grey_fulcrum; } dt_iop_colorbalancergb_gui_data_t;
static void gui_changed(dt_iop_module_t *self, GtkWidget *w, void *previous) { (void)self; (void)w; (void)previous; }
"""

CB_DRIVER = r"""
int main(void)
{
  static const float picks[][6] = {
    { 0.18f, 0.20f, 0.15f, 1.8f, 2.1f, 1.5f },
    { 0.05f, 0.04f, 0.06f, 0.6f, 0.5f, 0.7f },
    { 0.40f, 0.35f, 0.30f, 9.0f, 8.0f, 7.5f },
    { 0.02f, 0.05f, 0.30f, 0.05f, 0.06f, 0.9f },
    { 0.30f, 0.10f, 0.05f, 0.9f, 0.2f, 0.1f },
    { 0.20f, 0.20f, 0.20f, 0.4f, 0.4f, 0.4f },
  };
  int widget[10];
  dt_iop_colorbalancergb_gui_data_t g;
  GtkWidget **w = (GtkWidget **)&g;
  for(int i = 0; i < 10; i++) w[i] = &widget[i];
  const char *names[] = { "global_H", "shadows_H", "midtones_H", "highlights_H", "white_fulcrum", "grey_fulcrum" };
  GtkWidget *pickers[] = { g.global_H, g.shadows_H, g.midtones_H, g.highlights_H, g.white_fulcrum, g.grey_fulcrum };
  int first = 1;
  printf("[\n");
  for(size_t i = 0; i < sizeof(picks) / sizeof(picks[0]); i++)
    for(int k = 0; k < 6; k++)
    {
      dt_iop_colorbalancergb_params_t p;
      memset(&p, 0, sizeof(p));
      dt_iop_module_t m;
      memset(&m, 0, sizeof(m));
      m.params = &p; m.gui_data = &g;
      for(int c = 0; c < 3; c++) { m.picked_color[c] = picks[i][c]; m.picked_color_max[c] = picks[i][3 + c]; }
      color_picker_apply(&m, pickers[k], NULL);
      printf("%s{\"picker\":\"%s\",\"mean\":[%.9g,%.9g,%.9g],\"max\":[%.9g,%.9g,%.9g],\"out\":{\"global_H\":%.9g,\"global_C\":%.9g,"
             "\"shadows_H\":%.9g,\"shadows_C\":%.9g,\"midtones_H\":%.9g,\"midtones_C\":%.9g,\"highlights_H\":%.9g,"
             "\"highlights_C\":%.9g,\"white_fulcrum\":%.9g,\"grey_fulcrum\":%.9g}}\n", first ? "" : ",", names[k],
             picks[i][0], picks[i][1], picks[i][2], picks[i][3], picks[i][4], picks[i][5], p.global_H, p.global_C,
             p.shadows_H, p.shadows_C, p.midtones_H, p.midtones_C, p.highlights_H, p.highlights_C, p.white_fulcrum,
             p.grey_fulcrum);
      first = 0;
    }
  printf("]\n");
  return 0;
}
"""


def compile_run(code):
    with tempfile.TemporaryDirectory() as d:
        c = os.path.join(d, "ref.c")
        exe = os.path.join(d, "ref")
        open(c, "w", encoding="utf-8").write("\n".join(code))
        subprocess.check_call(["gcc", "-O0", "-std=gnu11", "-w", "-o", exe, c, "-lm"])
        return json.loads(subprocess.check_output([exe]).decode())


def colorbalance(src):
    cb = open(os.path.join(src, "src/iop/colorbalancergb.c"), encoding="utf-8").read()
    conv = open(os.path.join(src, "src/common/colorspaces_inline_conversions.h"), encoding="utf-8").read()
    cat = open(os.path.join(src, "src/common/chromatic_adaptation.h"), encoding="utf-8").read()
    mathh = open(os.path.join(src, "src/common/math.h"), encoding="utf-8").read()
    code = [CB_STUBS, func(mathh, "rad2degf"), func(conv, "dt_apply_transposed_color_matrix"),
            const(cat, "XYZ_D50_to_D65_CAT16_trans"), func(cat, "XYZ_D50_to_D65"),
            const(conv, "XYZ_D65_to_LMS_2006_D65_trans"), const(conv, "LMS_D65_to_filmlightRGB_D65_trans")]
    for f in ("XYZ_to_LMS", "LMS_to_gradingRGB", "LMS_to_Yrg", "Yrg_to_Ych", "XYZ_to_Ych", "get_hue_angle_from_Ych"):
        code.append(func(conv, f))
    code += [m.group(0) + "\n" for m in re.finditer(r"^#define (ANGLE_SHIFT|YRG_RAD_TO_CONVENTIONAL_DEG)\b.*$", cb, flags=re.M)]
    for t in ("dt_iop_colorbalancrgb_saturation_t", "dt_iop_colorbalancergb_params_t"):
        code.append(typedef(cb, t))
    for f in ("pipe_RGB_to_Ych", "color_picker_apply"):
        code.append(func(cb, f))
    code.append(CB_DRIVER)
    return compile_run(code)


def main():
    src = sys.argv[1]
    filmic = open(os.path.join(src, "src/iop/filmicrgb.c"), encoding="utf-8").read()
    mathh = open(os.path.join(src, "src/common/math.h"), encoding="utf-8").read()
    types = open(os.path.join(src, "src/common/dttypes.h"), encoding="utf-8").read()
    code = [STUBS, func(mathh, "sqf"), func(types, "max3f")]
    for t in ("dt_iop_filmicrgb_methods_type_t", "dt_iop_filmicrgb_curve_type_t", "dt_iop_filmicrgb_colorscience_type_t",
              "dt_iop_filmicrgb_spline_version_type_t", "dt_iop_filmicrgb_reconstruction_type_t",
              "dt_iop_filmic_noise_distribution_t", "dt_iop_filmicrgb_params_t"):
        code.append(typedef(filmic, t))
    for f in ("pixel_rgb_norm_power", "get_pixel_norm", "_compute_output_power", "apply_auto_grey", "apply_auto_black",
              "apply_auto_white_point_source", "apply_autotune"):
        code.append(func(filmic, f))
    code.append(DRIVER)
    cases = compile_run(code)
    print(json.dumps({"source": "darktable src/iop/filmicrgb.c and src/iop/colorbalancergb.c (verbatim)", "cases": cases,
                      "colorbalancergb": colorbalance(src)}, indent=1))


if __name__ == "__main__":
    main()

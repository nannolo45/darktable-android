#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference values for brief P3a's crop tool, computed by upstream darktable's own crop code.

    python3 -I tools/p3a-crop-reference.py <darktable source dir> > app/schema/src/test/resources/p3a-crop-reference.json

Copies, verbatim, out of src/iop/crop.c (and the CLIP and CLAMPF macros out of src/common/math.h): the grab constants, the parameter and interface structures,
`_reduce_aligners`, `_aspect_ratio_get`, `_aspect_apply`, `_update_sliders_and_limit`, `_gui_get_grab`,
`mouse_moved`, `button_pressed` and the ratio block of `_commit_box`. They are compiled with a small driver
(gcc -O0, no fast-math) whose stubs stand for the desktop interface (cursor, hints, sliders, history), and
whose pipes have no distortion after crop (clip_max = 0..1, `_set_max_clip` returns TRUE). The driver runs:

- "apply":  `_aspect_apply` for every grab, ratio, frame and image orientation;
- "drag":   `button_pressed` then a series of `mouse_moved` with the first button down, as a mouse drag on
            desktop: the grab desktop picked, and the frame after every move;
- "commit": the ratio block of `_commit_box`: the frame in pixels rounded to whole multiples of the ratio
            and clamped, as written to the parameters when the module loses the focus;
- "straighten": src/iop/ashift.c's `_calculate_straightening` (the angle a line drawn on the image adds to the
            rotation), with no module after rotate and perspective changing the line (the back transform is the
            identity), for lines in every direction, short ones included.

Nothing of upstream is changed; the source dir is only read. The JVM tests compare app/schema CropTool
against these numbers.
"""
import json
import os
import re
import subprocess
import sys
import tempfile


def block(text, start_pat, what):
    """From the match of start_pat to its brace-matched end."""
    m = re.search(start_pat, text, flags=re.M)
    if not m:
        sys.exit("%s not found" % what)
    i = text.index("{", m.end() - 1 if text[m.end() - 1] == "{" else m.end())
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                end = j + 1
                # a typedef keeps its name and semicolon
                semi = text.find(";", end)
                if text[m.start():m.start() + 7] == "typedef":
                    end = semi + 1
                return text[m.start():end] + "\n"
    sys.exit("unbalanced %s" % what)


def func(text, name):
    return block(text, r"^(static )?[a-zA-Z_][a-zA-Z0-9_ \*]*\b%s\(" % re.escape(name), name)


STUBS = r"""
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
typedef int gboolean;
typedef void GtkWidget;
typedef void GList;
typedef int dt_gui_collapsible_section_t;
typedef uint64_t dt_hash_t;
#define TRUE 1
#define FALSE 0
#define _(s) (s)
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))
#define CLAMP(x,lo,hi) (((x)>(hi))?(hi):(((x)<(lo))?(lo):(x)))
#define DT_PIXEL_APPLY_DPI(v) (v)
#define DT_ENTER_GUI_UPDATE()
#define DT_LEAVE_GUI_UPDATE()
#define DT_DEBUG_PIPE 0
#define dt_print(...) do {} while(0)
#define GDK_BUTTON_PRIMARY 1
#define GDK_BUTTON_SECONDARY 3
#define GDK_2BUTTON_PRESS 5
#define GDK_SHIFT_MASK 1
#define GDK_CONTROL_MASK 4
#define dt_modifiers_include(state, mask) (((state) & (mask)) == (mask))
#define dt_control_change_cursor(c) do {} while(0)
#define dt_control_hinter_message(m) do {} while(0)
#define dt_control_queue_redraw_center() do {} while(0)
#define dt_bauhaus_slider_set(w, v) do {} while(0)
#define dt_bauhaus_slider_set_soft_max(w, v) do {} while(0)
#define dt_bauhaus_slider_set_soft_min(w, v) do {} while(0)
#define dt_dev_add_history_item(d, m, e) do {} while(0)
static int g_strcmp0(const char *a, const char *b) { return strcmp(a, b); }

typedef struct { int width, height; } dt_iop_roi_t;
typedef struct { dt_iop_roi_t buf_in, buf_out; } dt_dev_pixelpipe_iop_t;
typedef struct { int loading; } dt_dev_pixelpipe_t;
typedef struct { float p_width, p_height; } dt_image_t;
typedef struct { dt_image_t image_storage; dt_dev_pixelpipe_t *preview_pipe; } dt_develop_t;
typedef struct { void *gui_data; void *params; dt_develop_t *dev; int enabled; } dt_iop_module_t;
typedef struct { int button_down, button_down_which; } dt_control_t;
static struct { int full; dt_develop_t *develop; dt_control_t *control; } darktable;

/* the image as the pipes see it: crop's input (iwd x iht), the preview's size on screen (pwd x pht) */
static int ref_iwd, ref_iht;
static float ref_pwd, ref_pht;
static const char *ref_combo_text = "";
static dt_dev_pixelpipe_iop_t ref_piece;
#define dt_dev_get_processed_size(pipe, w, h) do { *(w) = ref_iwd; *(h) = ref_iht; } while(0)
#define dt_dev_get_preview_size(dev, w, h) do { *(w) = ref_pwd; *(h) = ref_pht; } while(0)
#define dt_bauhaus_combobox_get_text(c) (ref_combo_text)
#define dt_dev_distort_get_iop_pipe(dev, pipe, self) (&ref_piece)
"""

AFTER_TYPES = r"""
/* no module after crop changes the geometry here: the maximum frame is the whole image */
static gboolean _set_max_clip(dt_iop_module_t *self) { return TRUE; }
static void gui_changed(dt_iop_module_t *self, GtkWidget *w, void *previous) { }
"""

DRIVER = r"""
static dt_iop_crop_gui_data_t G;
static dt_iop_crop_params_t P;
static dt_dev_pixelpipe_t PP;
static dt_develop_t DEV;
static dt_iop_module_t SELF;
static dt_control_t CTRL;

static void setup(int iwd, int iht, int d, int n)
{
  memset(&G, 0, sizeof(G)); memset(&P, 0, sizeof(P));
  ref_iwd = iwd; ref_iht = iht;
  ref_pwd = iwd >= iht ? 1000.0f : 1000.0f * iwd / iht;
  ref_pht = iwd >= iht ? 1000.0f * iht / iwd : 1000.0f;
  ref_piece.buf_in.width = ref_piece.buf_out.width = iwd;
  ref_piece.buf_in.height = ref_piece.buf_out.height = iht;
  DEV.image_storage.p_width = iwd; DEV.image_storage.p_height = iht;
  DEV.preview_pipe = &PP; PP.loading = 0;
  SELF.gui_data = &G; SELF.params = &P; SELF.dev = &DEV; SELF.enabled = 1;
  darktable.develop = &DEV; darktable.control = &CTRL;
  P.ratio_d = d; P.ratio_n = n;
  ref_combo_text = (n == 0 && abs(d) == 1) ? "original image" : "other";
  G.clip_max_x = G.clip_max_y = 0.0f; G.clip_max_w = G.clip_max_h = 1.0f;
  G.preview_ready = TRUE;
}

static void set_clip(float x, float y, float w, float h) { G.clip_x = x; G.clip_y = y; G.clip_w = w; G.clip_h = h; }
static void print_clip(void) { printf("[%.9g, %.9g, %.9g, %.9g]", G.clip_x, G.clip_y, G.clip_w, G.clip_h); }

static void ref_commit_ratio(dt_iop_crop_params_t *p, dt_dev_pixelpipe_iop_t *piece, float *points)
{
COMMIT_BLOCK
}

int main(void)
{
  const int sizes[][2] = { { 6000, 4000 }, { 4000, 6000 }, { 5000, 5000 } };
  const int ratios[][2] = { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 1, 1 }, { 3, 2 }, { -3, 2 }, { 16, 9 }, { -16, 9 },
                            { 14142136, 10000000 }, { 2445, 2032 }, { 65, 24 } };
  const float frames[][4] = { { 0.1f, 0.2f, 0.5f, 0.5f }, { 0.0f, 0.0f, 1.0f, 1.0f }, { 0.6f, 0.7f, 0.39f, 0.29f },
                              { 0.05f, 0.0f, 0.9f, 0.4f }, { 0.0f, 0.5f, 0.2f, 0.5f } };
  const int grabs[] = { 1, 2, 4, 8, 3, 6, 12, 9, 5, 10, 15 };
  const int ns = 3, nr = sizeof(ratios) / sizeof(ratios[0]), nf = sizeof(frames) / sizeof(frames[0]), ng = sizeof(grabs) / sizeof(grabs[0]);
  printf("{\n\"apply\": [\n");
  int first = 1;
  for(int s = 0; s < ns; s++) for(int r = 0; r < nr; r++) for(int f = 0; f < nf; f++) for(int k = 0; k < ng; k++)
  {
    setup(sizes[s][0], sizes[s][1], ratios[r][0], ratios[r][1]);
    set_clip(frames[f][0], frames[f][1], frames[f][2], frames[f][3]);
    _aspect_apply(&SELF, grabs[k]);
    printf("%s{\"w\": %d, \"h\": %d, \"d\": %d, \"n\": %d, \"frame\": [%.9g, %.9g, %.9g, %.9g], \"grab\": %d, \"aspect\": %.9g, \"out\": ",
           first ? "" : ",\n", sizes[s][0], sizes[s][1], ratios[r][0], ratios[r][1], frames[f][0], frames[f][1], frames[f][2], frames[f][3],
           grabs[k], _aspect_ratio_get(&SELF, NULL));
    print_clip(); printf("}");
    first = 0;
  }
  /* drags: press at a point of the frame (fractions of the image), then moves with the first button down */
  struct { float px, py; float moves[4][2]; } paths[] = {
    { 0.105f, 0.205f, { { 0.0f, 0.1f }, { -0.2f, -0.3f }, { 0.3f, 0.35f }, { 0.25f, 0.3f } } },   /* top left corner */
    { 0.595f, 0.205f, { { 0.7f, 0.1f }, { 1.3f, -0.2f }, { 0.5f, 0.4f }, { 0.8f, 0.25f } } },    /* top right corner */
    { 0.595f, 0.695f, { { 0.7f, 0.8f }, { 1.2f, 1.4f }, { 0.4f, 0.5f }, { 0.65f, 0.75f } } },    /* bottom right corner */
    { 0.105f, 0.695f, { { 0.0f, 0.9f }, { -0.5f, 1.5f }, { 0.3f, 0.6f }, { 0.15f, 0.72f } } },   /* bottom left corner */
    { 0.105f, 0.45f, { { 0.05f, 0.45f }, { -0.3f, 0.5f }, { 0.4f, 0.45f }, { 0.2f, 0.45f } } },  /* left edge */
    { 0.35f, 0.205f, { { 0.35f, 0.1f }, { 0.35f, -0.4f }, { 0.35f, 0.5f }, { 0.35f, 0.3f } } },  /* top edge */
    { 0.595f, 0.45f, { { 0.7f, 0.45f }, { 1.4f, 0.5f }, { 0.3f, 0.45f }, { 0.65f, 0.45f } } },   /* right edge */
    { 0.35f, 0.695f, { { 0.35f, 0.8f }, { 0.35f, 1.6f }, { 0.35f, 0.4f }, { 0.35f, 0.75f } } },  /* bottom edge */
    { 0.35f, 0.45f, { { 0.4f, 0.5f }, { 0.9f, 0.95f }, { -0.5f, -0.4f }, { 0.3f, 0.4f } } },     /* inside: move */
  };
  const int npaths = sizeof(paths) / sizeof(paths[0]);
  const int dratios[][2] = { { 0, 0 }, { 3, 2 }, { -3, 2 }, { 1, 1 }, { 1, 0 }, { 16, 9 } };
  const int ndr = sizeof(dratios) / sizeof(dratios[0]);
  printf("\n],\n\"drag\": [\n");
  first = 1;
  for(int s = 0; s < ns; s++) for(int r = 0; r < ndr; r++) for(int q = 0; q < npaths; q++)
  {
    setup(sizes[s][0], sizes[s][1], dratios[r][0], dratios[r][1]);
    set_clip(0.1f, 0.2f, 0.5f, 0.5f);
    /* as desktop draws the frame before the first press (gui_post_expose) */
    _aspect_apply(&SELF, GRAB_HORIZONTAL | GRAB_VERTICAL);
    printf("%s{\"w\": %d, \"h\": %d, \"d\": %d, \"n\": %d, \"start\": ", first ? "" : ",\n", sizes[s][0], sizes[s][1], dratios[r][0], dratios[r][1]);
    print_clip();
    printf(", \"press\": [%.9g, %.9g]", paths[q].px, paths[q].py);
    button_pressed(&SELF, paths[q].px, paths[q].py, 1.0, GDK_BUTTON_PRIMARY, 0, 0, 1.0f);
    printf(", \"grab\": %d, \"steps\": [", G.cropping);
    CTRL.button_down = 1; CTRL.button_down_which = GDK_BUTTON_PRIMARY;
    for(int m = 0; m < 4; m++)
    {
      mouse_moved(&SELF, paths[q].moves[m][0], paths[q].moves[m][1], 1.0, 0, 1.0f);
      printf("%s{\"to\": [%.9g, %.9g], \"out\": ", m ? ", " : "", paths[q].moves[m][0], paths[q].moves[m][1]);
      print_clip(); printf("}");
    }
    CTRL.button_down = 0;
    printf("]}");
    first = 0;
  }
  /* the ratio block of _commit_box: frame in pixels of crop's input, rounded and clamped */
  printf("\n],\n\"commit\": [\n");
  first = 1;
  const float cframes[][4] = { { 0.1f, 0.2f, 0.5f, 0.5f }, { 0.0f, 0.0f, 1.0f, 1.0f }, { 0.123457f, 0.0f, 0.666667f, 1.0f },
                               { 0.333f, 0.111f, 0.3f, 0.45f }, { 0.0f, 0.0f, 0.004f, 0.003f } };
  const int ncf = sizeof(cframes) / sizeof(cframes[0]);
  for(int s = 0; s < ns; s++) for(int r = 0; r < nr; r++) for(int f = 0; f < ncf; f++)
  {
    setup(sizes[s][0], sizes[s][1], ratios[r][0], ratios[r][1]);
    const float wd = sizes[s][0], ht = sizes[s][1];
    float points[4] = { cframes[f][0] * wd, cframes[f][1] * ht, (cframes[f][0] + cframes[f][2]) * wd, (cframes[f][1] + cframes[f][3]) * ht };
    ref_commit_ratio(&P, &ref_piece, points);
    printf("%s{\"w\": %d, \"h\": %d, \"d\": %d, \"n\": %d, \"frame\": [%.9g, %.9g, %.9g, %.9g], \"params\": [%.9g, %.9g, %.9g, %.9g]}",
           first ? "" : ",\n", sizes[s][0], sizes[s][1], ratios[r][0], ratios[r][1], cframes[f][0], cframes[f][1], cframes[f][2], cframes[f][3],
           P.cx, P.cy, P.cw, P.ch);
    first = 0;
  }
  STRAIGHTEN_CASES
  printf("\n]\n}\n");
  return 0;
}
"""


STRAIGHTEN = r"""
  printf("\n],\n\"straighten\": [\n");
  first = 1;
  {
    /* lines from (0.5, 0.5) on a picture shown 1000 x 667 pixels at zoom 1 */
    const float wd = 1000.0f, ht = 667.0f;
    for(int deg = -180; deg < 180; deg += 7)
      for(int len = 0; len < 3; len++)
      {
        const float l = len == 0 ? 10.0f : len == 1 ? 60.0f : 400.0f;
        const float a = deg * 3.14159265358979323846f / 180.0f;
        const float pzx = 0.5f + l * cosf(a) / wd, pzy = 0.5f + l * sinf(a) / ht;
        const float r = _calculate_straightening(&SELF, pzx, pzy, 0.5f, 0.5f, wd, ht, 1.0f);
        printf("%s{\"from\": [0.5, 0.5], \"to\": [%.9g, %.9g], \"wd\": %g, \"ht\": %g, \"angle\": %.9g}", first ? "" : ",\n", pzx, pzy, wd, ht, r);
        first = 0;
      }
  }
"""


def main():
    src = sys.argv[1]
    crop = open(os.path.join(src, "src/iop/crop.c"), encoding="utf-8").read()
    m = re.search(r"#define MIN_CROP_SIZE [^\n]*\n", crop)
    if not m:
        sys.exit("MIN_CROP_SIZE not found")
    # CLIP and CLAMPF as darktable defines them (src/common/math.h); MIN, MAX and CLAMP are GLib's, written out above
    mh = open(os.path.join(src, "src/common/math.h"), encoding="utf-8").read()
    macros = []
    for name in ("CLIP", "CLAMPF"):
        d = re.search(r"^#define %s\(.*$" % name, mh, flags=re.M)
        if not d:
            sys.exit("%s not found in src/common/math.h" % name)
        macros.append(d.group(0) + "\n")
    code = [STUBS] + macros + [m.group(0)]
    code.append(block(crop, r"^typedef struct dt_iop_crop_params_t\s*\{", "dt_iop_crop_params_t"))
    code.append(block(crop, r"^typedef enum _grab_region_t\s*\{", "_grab_region_t"))
    code.append(block(crop, r"^typedef struct dt_iop_crop_gui_data_t\s*\{", "dt_iop_crop_gui_data_t"))
    code.append(AFTER_TYPES)
    for n in ("_reduce_aligners", "_aspect_ratio_get", "_aspect_apply", "_update_sliders_and_limit", "_gui_get_grab",
              "mouse_moved", "button_pressed"):
        code.append(func(crop, n))
    cb = func(crop, "_commit_box")
    a = cb.find("// Do we need to align on a given ratio?")
    b = cb.find("if(enforce_history")
    if a < 0 or b < 0:
        sys.exit("_commit_box's ratio block not found")
    # ashift.c's straightening and the math.h helpers it uses
    ashift = open(os.path.join(src, "src/iop/ashift.c"), encoding="utf-8").read()
    for name in ("M_PI_F", "M_PI_2f", "M_PI_4f"):
        d = re.search(r"^#define %s .*$" % name, mh, flags=re.M)
        if not d:
            sys.exit("%s not found in src/common/math.h" % name)
        code.append(d.group(0) + "\n")
    code.append(block(mh, r"^static inline float rad2degf\(", "rad2degf"))
    code.append("#define dt_dev_distort_backtransform_plus(dev, pipe, order, dir, pts, n) (TRUE)\n")
    code.append(func(ashift, "_calculate_straightening"))
    code.append(DRIVER.replace("COMMIT_BLOCK", cb[a:b]).replace("STRAIGHTEN_CASES", STRAIGHTEN))
    with tempfile.TemporaryDirectory() as tmp:
        c = os.path.join(tmp, "crop_ref.c")
        exe = os.path.join(tmp, "crop_ref")
        open(c, "w", encoding="utf-8").write("\n".join(code))
        subprocess.run(["gcc", "-O0", "-std=gnu11", "-w", "-o", exe, c, "-lm"], check=True)
        out = subprocess.run([exe], check=True, capture_output=True, text=True).stdout
    data = json.loads(out)
    data["source"] = "src/iop/crop.c at %s" % os.environ.get("DT_UPSTREAM_COMMIT", "(the given source dir)")
    print(json.dumps(data, indent=1, sort_keys=True))


if __name__ == "__main__":
    main()

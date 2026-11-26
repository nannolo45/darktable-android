#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Copy, verbatim, the picker arithmetic of five darktable modules for the engine test C25 (brief P3c deliverable 6).

    python3 -I engine/tests/pickref_extract.py <darktable source dir> <output dir>

For each module the bridge has a picker action for (engine-api.md 6.3: temperature wb_from_area, exposure spot,
rgblevels pick, rgbcurve set_nodes_from_area, channelmixerrgb illuminant_from_area / spot), writes two files:

  pickref_up_<op>_types.inc  the module's own #include lines (iop/iop_api.h left out: it declares the module API,
                             which the test renames), its #defines before its first function, and the typedefs the
                             functions need
  pickref_up_<op>_funcs.inc  the functions of desktop's picker callback, as they are in src/iop/<op>.c

Nothing of upstream is changed or edited: the text between the first line of each definition and its matching
brace is copied as it is. engine/tests/pickref_<op>.c includes the two files around engine/tests/pickref_stubs.h
(the desktop interface calls made no-ops or read from the test's own widget values) and calls the callback on a
copy of the module with the picked values the bridge's picker gives (dtb_pickref.c). A function that cannot be
found makes the script fail, so a darktable update that moves the code is noticed.
"""
import os
import re
import sys


def block(text, start_pat, what):
    """From the match of start_pat to the end of its brace-matched body (a typedef keeps its name and semicolon)."""
    # the definition, not a forward declaration: no ";" between the start and the opening brace
    m = next((x for x in re.finditer(start_pat, text, flags=re.M)
              if ";" not in text[x.start():text.index("{", x.start())]), None)
    if not m:
        sys.exit("pickref_extract: %s not found" % what)
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
    sys.exit("pickref_extract: unbalanced %s" % what)


def func(text, name):
    return block(text, r"^ ?(static )?(inline )?[a-zA-Z_][a-zA-Z0-9_ \*]*\b%s\s*\(" % re.escape(name), name)


def typedef(text, name):
    if name.startswith("line:"):
        # brief P6a: a one-line typedef (temperature.c's spd function type)
        m = re.search(r"^typedef .*\b%s\b.*;$" % re.escape(name[5:]), text, flags=re.M)
        if not m:
            sys.exit("pickref_extract: typedef %s not found" % name)
        return m.group(0) + "\n"
    if name.startswith("const:"):
        # brief P6a: a file-level constant (agx.c's _epsilon)
        m = re.search(r"^static const [a-zA-Z_ ]+ %s\b.*?;" % re.escape(name[6:]), text, flags=re.M | re.S)
        if not m:
            sys.exit("pickref_extract: constant %s not found" % name)
        return m.group(0) + "\n"
    return block(text, r"^typedef (struct|enum) %s\b" % re.escape(name), name)


def define(text, name):
    m = re.search(r"^#define %s\b.*$" % re.escape(name), text, flags=re.M)
    if not m:
        sys.exit("pickref_extract: #define %s not found" % name)
    return m.group(0) + "\n"


def top_defines(text):
    """Every #define before the module's first function (its constants and small macros), as they are."""
    first = re.search(r"^(static |void |int |float |gboolean |const )[^;{]*\(", text, flags=re.M)
    head = text[:first.start()] if first else text
    return "".join(m.group(0) + "\n" for m in re.finditer(r"^#define .*$", head, flags=re.M))


def includes(text):
    out = []
    for line in text.splitlines():
        m = re.match(r'^#include ([<"])([^>"]+)[>"]', line)
        if not m:
            continue
        name = m.group(2)
        if name == "iop/iop_api.h":
            continue
        if m.group(1) == '"' and "/" not in name:
            name = "iop/" + name  # a header beside the module (gaussian_elimination.h)
            out.append('#include "%s"' % name)
        else:
            out.append(line)
    return "\n".join(out) + "\n"


# what each module's callback needs: (defines, typedefs, functions)
MODULES = {
    "temperature": ([], ["dt_iop_temperature_params_t", "dt_iop_temperature_gui_data_t", "line:spd"],
                    ["color_picker_apply", "_temp_array_from_params", "_spd_blackbody", "_spd_daylight", "_spectrum_to_XYZ",
                     "_temperature_to_XYZ", "_XYZ_to_temperature", "_mul2xyz", "_mul2temp", "_prepare_matrices"]),
    # brief P6a (test C43)
    "levels": ([], ["dt_iop_levels_mode_t", "dt_iop_levels_params_t", "dt_iop_levels_gui_data_t"], ["color_picker_apply"]),
    "invert": ([], ["dt_iop_invert_params_t", "dt_iop_invert_gui_data_t"], ["color_picker_apply"]),
    "splittoning": ([], ["dt_iop_splittoning_params_t", "dt_iop_splittoning_gui_data_t"], ["color_picker_apply"]),
    "colorize": ([], ["dt_iop_colorize_params_t", "dt_iop_colorize_gui_data_t"], ["color_picker_apply"]),
    "profile_gamma": ([], ["dt_iop_profilegamma_mode_t", "dt_iop_profilegamma_params_t", "dt_iop_profilegamma_gui_data_t"],
                      ["apply_auto_grey", "apply_auto_black", "apply_auto_dynamic_range", "apply_autotune", "color_picker_apply"]),
    "basicadj": ([], ["dt_iop_basicadj_params_t", "dt_iop_basicadj_gui_data_t"], ["color_picker_apply"]),
    "colorequal": ([], ["dt_iop_colorequal_params_t", "dt_iop_colorequal_channel_t", "dt_iop_colorequal_gui_data_t"],
                   ["_pipe_RGB_to_Ych", "color_picker_apply"]),
    "agx": ([], ["dt_iop_agx_base_primaries_t", "dt_iop_agx_params_t", "dt_iop_basic_curve_controls_t", "dt_iop_agx_gui_data_t",
                 "hdr_params_t", "tone_mapping_params_t", "const:_epsilon", "const:_default_gamma"],
            ["_line", "_scale", "_calculate_slope_matching_power", "_calculate_fallback_curve_coefficient", "_sigmoid",
             "_scaled_sigmoid", "_fallback_toe", "_fallback_shoulder", "_apply_curve", "_calculate_pivot_y_at_gamma",
             "_calculate_auto_gamma", "_adjust_pivot", "_set_log_mapping_params", "_calculate_slope_gamma_compensation",
             "_calculate_tone_mapping_params", "_set_blenderlike_primaries", "_set_unmodified_primaries", "_set_smooth_primaries",
             "_luminance_from_profile", "_update_pivot_x", "_adjust_relative_exposure_from_exposure_params",
             "_apply_auto_black_exposure", "_apply_auto_white_exposure", "_apply_auto_tune_exposure",
             "_read_exposure_params_callback", "_apply_auto_pivot_xy", "_apply_auto_pivot_x", "color_picker_apply",
             "_apply_primaries_from_menu_callback", "_set_post_curve_primaries_from_pre_callback"]),
    "exposure": ([], ["dt_iop_exposure_mode_t", "dt_spot_mode_t", "dt_iop_exposure_params_t", "dt_iop_exposure_gui_data_t"],
                 ["_get_exposure_bias", "_get_highlight_bias", "_exposure_compensation_ev", "_total_adjustment_ev",
                  "_required_exposure_slider_ev", "_exposure_set_white", "_exposure_set_black", "_auto_set_exposure",
                  "color_picker_apply"]),
    "rgblevels": ([], ["dt_iop_rgblevels_channel_t", "dt_iop_rgblevels_autoscale_t", "dt_iop_rgblevels_params_t",
                       "dt_iop_rgblevels_gui_data_t"], ["color_picker_apply", "_get_selected_area", "_auto_levels"]),
    "rgbcurve": ([], ["rgbcurve_channel_t", "dt_iop_rgbcurve_autoscale_t", "dt_iop_rgbcurve_node_t",
                                                "dt_iop_rgbcurve_params_t", "dt_iop_rgbcurve_gui_data_t"],
                 ["_add_node", "_add_node_from_picker", "color_picker_apply"]),
    "channelmixerrgb": ([], ["dt_iop_channelmixer_rgb_version_t", "dt_iop_channelmixer_rgb_params_t", "dt_solving_strategy_t",
                             "dt_spot_mode_t", "dt_iop_channelmixer_rgb_gui_data_t"],
                        ["_check_if_close_to_daylight", "_auto_set_illuminant", "color_picker_apply"]),
}


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for op, (defs, types, funcs) in MODULES.items():
        path = os.path.join(src, "src/iop/%s.c" % op)
        text = open(path, encoding="utf-8").read()
        head = "/* GENERATED by engine/tests/pickref_extract.py from %s: verbatim, do not edit */\n" % ("src/iop/%s.c" % op)
        t = [head, includes(text)]
        t.append(top_defines(text))
        t += [define(text, d) for d in defs if not re.search(r"^#define %s\b" % re.escape(d), t[-1], flags=re.M)]
        t += [typedef(text, n) for n in types]
        with open(os.path.join(out, "pickref_up_%s_types.inc" % op), "w", encoding="utf-8") as f:
            f.write("\n".join(t))
        fs = [head]
        # forward declarations, as the module has them, so the copied functions may call each other in any order
        for n in funcs:
            body = func(text, n)
            fs.append(body[:body.index("{")].rstrip() + ";\n")
        fs += [func(text, n) for n in funcs]
        with open(os.path.join(out, "pickref_up_%s_funcs.inc" % op), "w", encoding="utf-8") as f:
            f.write("\n".join(fs))


if __name__ == "__main__":
    main()

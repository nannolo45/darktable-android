# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference implementation of the R11 item hash (PLAN.md 4.5). Writes docs/design/R11-hash-vectors.json."""
import hashlib, json, sys

PRESET_CORE = ["name", "description", "operation", "op_version", "op_params", "enabled", "blendop_params",
               "blendop_version", "multi_priority", "multi_name", "multi_name_hand_edited", "model", "maker", "lens",
               "iso_min", "iso_max", "exposure_min", "exposure_max", "aperture_min", "aperture_max",
               "focal_length_min", "focal_length_max", "autoapply", "filter", "def", "format"]
LAYOUT_CORE = ["name", "operation", "op_version", "op_params"]
STYLE_CORE = ["name", "description", "iop_list"]
ITEM_CORE = ["num", "module", "operation", "op_params", "enabled", "blendop_params", "blendop_version",
             "multi_priority", "multi_name", "multi_name_hand_edited"]


def canonical(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def core(body: dict) -> dict:
    k = body["kind"]
    if k == "style":
        return {"kind": k, "style": {c: body["style"].get(c) for c in STYLE_CORE},
                "items": [{c: it.get(c) for c in ITEM_CORE} for it in sorted(body["items"], key=lambda i: i["num"])]}
    cols = LAYOUT_CORE if k == "layout" else PRESET_CORE
    return {"kind": k, "row": {c: body["row"].get(c) for c in cols}}


def item_hash(body: dict) -> str:
    return hashlib.sha256(canonical(core(body))).hexdigest()


def real(x: float) -> str:
    return format(x, ".17g")


if __name__ == "__main__":
    preset = {"kind": "preset", "row": {
        "name": "mono red filter", "description": "", "operation": "channelmixerrgb", "op_version": 3,
        "op_params": "00000000cdcc4c3e0000803f", "enabled": 1, "blendop_params": "67676f6400000000",
        "blendop_version": 14, "multi_priority": 0, "multi_name": "", "multi_name_hand_edited": 0,
        "model": "%", "maker": "%", "lens": "%", "iso_min": real(0.0), "iso_max": real(340282346638528859811704183484516925440.0),
        "exposure_min": real(0.0), "exposure_max": real(100000000.0), "aperture_min": real(0.0),
        "aperture_max": real(100000000.0), "focal_length_min": real(0.0), "focal_length_max": real(1000.0),
        "autoapply": 0, "filter": 0, "def": 0, "format": 0, "extra_column_ignored": 7}}
    layout = {"kind": "layout", "row": {"name": "my workflow · 4 groups", "operation": "modulegroups",
              "op_version": 1, "op_params": "313031ea" + "61", "enabled": 1, "description": None,
              "writeprotect_absent": True}}
    style = {"kind": "style", "style": {"name": "bw | sélénium", "description": "toned B&N", "iop_list": None},
             "items": [
                 {"num": 1, "module": 1, "operation": "sigmoid", "op_params": "0000a040", "enabled": 1,
                  "blendop_params": None, "blendop_version": 14, "multi_priority": 0, "multi_name": "",
                  "multi_name_hand_edited": 0},
                 {"num": 0, "module": 3, "operation": "channelmixerrgb", "op_params": "deadbeef", "enabled": 1,
                  "blendop_params": "67676f64", "blendop_version": 14, "multi_priority": 1, "multi_name": "mono",
                  "multi_name_hand_edited": 1}]}
    out = {"format": 1, "note": "PLAN.md 4.5. canonical = the core object, JSON with sorted keys, separators , : and UTF-8; hash = sha256 hex.",
           "vectors": [{"body": b, "canonical": canonical(core(b)).decode("utf-8"), "hash": item_hash(b)}
                       for b in (preset, layout, style)]}
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)

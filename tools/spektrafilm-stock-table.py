#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Spektrafilm's stock table for schema/overrides/spektrafilm.json, read from a data pack (brief P2j).

    python3 -I tools/spektrafilm-stock-table.py <pack dir>            # print the "stock_list" object
    python3 -I tools/spektrafilm-stock-table.py <pack dir> --check    # compare with the override; exit 1 if it differs

<pack dir> is the folder holding pack.json, profiles/ and spectra_lut.f32 (darktable/spektrafilm/ as
schema/extractor/fetch_spektrafilm_pack.py leaves it). Follows src/iop/spektrafilm.c at the pinned upstream
commit: `_scan_profiles` (the info header of each profile, `_shorten_name`, sorted by `_nat_cmp`),
`sf_profile_dev_times` (src/common/spektra_sim.c: `development_time`, empty when there is at most one),
`_scan_tables` / `sf_pack_peek_tables` (one table per format 2 pack, its header hash; a format 3 pack declares its
tables in pack.json) and SF_FIRST_PUBLISHED_LUT_HASH / _NAME.
"""
import json
import os
import struct
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIRST_PUBLISHED_LUT_HASH = 0x565F4EC4
FIRST_PUBLISHED_LUT_NAME = "hanatos2025"
FILLER = (" Professional", " Negative Film", " Reversal Film")


def shorten(name):
    for f in FILLER:
        name = name.replace(f, "", 1)
    return name


def nat_key(s):
    """_nat_cmp: digits compare numerically and sort before letters; letters case-insensitively."""
    out = []
    i = 0
    while i < len(s):
        if s[i].isdigit():
            j = i
            while j < len(s) and s[j].isdigit():
                j += 1
            out.append((0, int(s[i:j]), ""))
            i = j
        else:
            out.append((1, 0, s[i].lower()))
            i += 1
    return out


def header(path):
    with open(path, "rb") as f:
        b = f.read(32)
        if len(b) < 32 or b[:4] != b"SFS2":
            return None
        version, d0, d1, d2, dtype, h, idlen = struct.unpack("<iiiiiIi", b[4:32])
        lut_id = f.read(idlen).decode()
    return h, lut_id


def table(pack):
    stocks = []
    profdir = os.path.join(pack, "profiles")
    for fn in os.listdir(profdir):
        if not fn.endswith(".json"):
            continue
        d = json.load(open(os.path.join(profdir, fn), encoding="utf-8"))
        info = d.get("info", {})
        stock = info.get("stock") or fn[:-5]
        dev = (d.get("data") or {}).get("development_time")
        dev = dev if isinstance(dev, list) and len(dev) > 1 else []
        stocks.append({
            "stock": stock,
            "name": shorten(info.get("name") or stock),
            "printing": info.get("stage") == "printing",
            "positive": info.get("type") == "positive",
            "bw": info.get("channel_model") == "bw",
            "dev_times": dev,
            "target_print": info.get("target_print"),
        })
    # g_list_sort is stable; directory order breaks ties, which no two names of one pack need
    stocks.sort(key=lambda e: nat_key(e["name"]))
    meta = json.load(open(os.path.join(pack, "pack.json"), encoding="utf-8"))
    tables = []
    if meta.get("pack_format", 0) < 3:
        hdr = header(os.path.join(pack, "spectra_lut.f32"))
        if hdr:
            tables.append({"label": hdr[1], "hash": hdr[0], "reflectance": False, "named": False})
    else:
        for e in meta.get("spectral_upsampling", []):
            hdr = header(os.path.join(pack, e["file"]))
            if hdr:
                tables.append({"label": e["identifier"], "hash": hdr[0], "reflectance": e.get("kind") == "reflectance",
                               "named": True})
    for t in tables:
        if not t["named"] and t["hash"] == FIRST_PUBLISHED_LUT_HASH:
            t["label"] = FIRST_PUBLISHED_LUT_NAME
        del t["named"]
    return {"pack_lut_hash": tables[0]["hash"] if tables else 0, "stocks": stocks, "tables": tables}


def main():
    pack = sys.argv[1]
    t = table(pack)
    if "--check" in sys.argv:
        o = json.load(open(os.path.join(REPO, "schema/overrides/spektrafilm.json"), encoding="utf-8"))["stock_list"]
        mine = {k: o.get(k) for k in t}
        if mine != t:
            print("schema/overrides/spektrafilm.json differs from the pack in %s:" % pack)
            for k in t:
                if mine[k] != t[k]:
                    print("  %s: override %s\n  %s: pack     %s" % (k, json.dumps(mine[k])[:400], k, json.dumps(t[k])[:400]))
            sys.exit(1)
        print("spektrafilm stock table matches the pack (%d stocks, %d tables)" % (len(t["stocks"]), len(t["tables"])))
        return
    print(json.dumps(t, indent=2))


if __name__ == "__main__":
    main()

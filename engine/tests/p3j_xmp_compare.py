# SPDX-License-Identifier: GPL-3.0-or-later
"""C29 (brief P3j): compare the edit of two darktable XMP files: history end, every history entry (operation,
enabled, module version, parameters, instance name and priority, blending) and the drawn shapes (masks history,
without the shape ids). Prints "equal ..." and exits 0, or the first differences and exits 1.

    python3 -I p3j_xmp_compare.py <bridge.xmp> <reference.xmp>
"""
import re
import sys

ATTR = re.compile(r'([\w:]+)="([^"]*)"')
HIST_KEYS = ("darktable:operation", "darktable:enabled", "darktable:modversion", "darktable:params",
             "darktable:multi_name", "darktable:multi_priority", "darktable:blendop_version",
             "darktable:blendop_params")
MASK_KEYS = ("darktable:mask_num", "darktable:mask_type", "darktable:mask_name", "darktable:mask_version",
             "darktable:mask_points", "darktable:mask_nb", "darktable:mask_src")


def section(text, name):
    m = re.search(r"<%s>(.*?)</%s>" % (name, name), text, re.S)
    if not m:
        return []
    return [dict(ATTR.findall(li)) for li in re.findall(r"<rdf:li\b([^>]*)/?>", m.group(1))]


def edit(path):
    text = open(path, encoding="utf-8").read()
    end = re.search(r'darktable:history_end="(\d+)"', text)
    hist = [tuple(e.get(k, "") for k in HIST_KEYS) for e in section(text, "darktable:history")]
    masks = sorted(tuple(e.get(k, "") for k in MASK_KEYS) for e in section(text, "darktable:masks_history"))
    # an end above the number of entries is read as the number of entries (darktable clamps it when it reads the
    # XMP); reported, as it tells where an end and the entries disagree
    raw_end = int(end.group(1)) if end else None
    if raw_end is not None and raw_end > len(hist):
        print("note: %s has history_end %d above its %d entries" % (path, raw_end, len(hist)))
        raw_end = len(hist)
    return raw_end, hist, masks


def main():
    a, b = edit(sys.argv[1]), edit(sys.argv[2])
    diffs = []
    if a[0] != b[0]:
        diffs.append("history_end %s vs %s" % (a[0], b[0]))
    if len(a[1]) != len(b[1]):
        diffs.append("history entries %d vs %d" % (len(a[1]), len(b[1])))
    for i, (x, y) in enumerate(zip(a[1], b[1])):
        if x != y:
            keys = [HIST_KEYS[k].split(":")[1] for k in range(len(HIST_KEYS)) if x[k] != y[k]]
            diffs.append("entry %d %s/%s vs %s/%s differs in %s" % (i, x[0], x[5], y[0], y[5], ",".join(keys)))
    if a[2] != b[2]:
        diffs.append("masks history %d vs %d shapes, or their contents differ" % (len(a[2]), len(b[2])))
    if diffs:
        print("; ".join(diffs[:6]))
        return 1
    print("equal: history_end %s, %d entries, %d shapes" % (a[0], len(a[1]), len(a[2])))
    return 0


if __name__ == "__main__":
    sys.exit(main())

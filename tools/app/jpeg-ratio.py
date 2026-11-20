#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Print a JPEG's size and exit 0 when its width / height is within 1 % of the wanted ratio (brief P2f).

    tools/app/jpeg-ratio.py <file.jpg> <wanted width/height, e.g. 0.6667>
"""
import struct
import sys


def size(path):
    d = open(path, "rb").read()
    i = 2
    while i + 9 < len(d):
        if d[i] != 0xFF:
            i += 1
            continue
        marker = d[i + 1]
        length = struct.unpack(">H", d[i + 2:i + 4])[0]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", d[i + 5:i + 9])
            return w, h
        i += 2 + length
    raise SystemExit("no frame header in %s" % path)


if __name__ == "__main__":
    w, h = size(sys.argv[1])
    want = float(sys.argv[2])
    ok = abs(w / h / want - 1.0) < 0.01
    print("%s: %dx%d, width/height %.4f, wanted %.4f: %s" % (sys.argv[1], w, h, w / h, want, "ok" if ok else "bad"))
    sys.exit(0 if ok else 1)

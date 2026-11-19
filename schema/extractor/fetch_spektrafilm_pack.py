# SPDX-License-Identifier: GPL-3.0-or-later
"""Install the public spektrafilm data pack for the extractor run.

    fetch_spektrafilm_pack.py <xdg data home> [<git ref, default main>]

Without a data pack, darktable's spektrafilm module hides all of its controls
behind a "no data pack installed" notice, so its panel cannot be swept. This
downloads the newest pack listed in darktable-org/darktable-spektrafilm's
manifest.json, checks every file's SHA-256 against the manifest, and places it
in <xdg data home>/darktable/spektrafilm/ (the folder darktable reads a
hand-installed pack from, src/common/spektra_fetch.c). darktable is then
started with XDG_DATA_HOME=<xdg data home>, so nothing outside the work
directory is touched. Prints the pack id and the manifest's checksums.
"""
import hashlib
import json
import os
import sys
import urllib.request

REPO = "darktable-org/darktable-spektrafilm"
HOST = "https://raw.githubusercontent.com"


def get(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def main():
    dest_root = sys.argv[1]
    ref = sys.argv[2] if len(sys.argv) > 2 else "main"
    manifest = json.loads(get("%s/%s/%s/manifest.json" % (HOST, REPO, ref)))
    pack = manifest["packs"][-1]  # newest last
    dest = os.path.join(dest_root, "darktable", "spektrafilm")
    for f in pack["files"]:
        path = f["path"]
        if path.startswith("/") or ".." in path.split("/"):
            sys.exit("refusing path %r" % path)
        target = os.path.join(dest, path)
        if os.path.exists(target) and hashlib.sha256(open(target, "rb").read()).hexdigest() == f["sha256"]:
            continue
        data = get("%s/%s/%s/%s/%s" % (HOST, REPO, ref, pack["base"], path))
        if hashlib.sha256(data).hexdigest() != f["sha256"]:
            sys.exit("checksum mismatch for %s" % path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as out:
            out.write(data)
    print("spektrafilm pack %s (format %s, %d files) in %s" % (pack["lut_id"], pack["pack_format"],
                                                                len(pack["files"]), dest))
    sha = hashlib.sha256(json.dumps(pack["files"], sort_keys=True).encode()).hexdigest()
    print("sha256 of the manifest's file list: %s" % sha)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Writes the chunks the plugin should allow (require-full + ignore-empty), from the Python reference scan."""
import os
import re
import subprocess
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
FULL = {"full", "fullchunk", "postprocessed"}


def allowed(region_path):
    out = subprocess.run([sys.executable, TOOLS + "/reference_scan.py", region_path], capture_output=True, text=True, check=True).stdout
    for line in out.splitlines():
        idx, st, na, err = line.split()
        if err == "1":
            yield int(idx)
            continue
        if st != "-" and not st.startswith("TP") and st.split(":")[-1] not in FULL:
            continue
        if na == "0":
            continue
        yield int(idx)


for world, region_dir in (("old_world", sys.argv[1] + "/old_world/region"),
                          ("old_world_nether", sys.argv[1] + "/old_world_nether/DIM-1/region")):
    for name in sorted(os.listdir(region_dir)):
        m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca$", name)
        if not m:
            continue
        rx, rz = int(m.group(1)), int(m.group(2))
        for idx in allowed(os.path.join(region_dir, name)):
            print(world, rx * 32 + (idx & 31), rz * 32 + (idx >> 5))

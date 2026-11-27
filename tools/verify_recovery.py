#!/usr/bin/env python3
"""Checks the repaired region file against the simulation's ground truth, chunk by chunk."""
import io
import json
import struct
import sys
import zlib

import nbtlib

SIM, RUN = sys.argv[1], sys.argv[2]
truth = json.load(open(SIM + "/truth.json"))


def chunks(path):
    d = open(path, "rb").read()
    out = {}
    for i in range(1024):
        (e,) = struct.unpack_from(">I", d, i * 4)
        if not e:
            continue
        off = (e >> 8) * 4096
        (l,) = struct.unpack_from(">i", d, off)
        assert d[off + 4] == 2, "compression"
        assert off + 4 + l <= len(d), "length"
        out[i] = nbtlib.File.parse(io.BytesIO(zlib.decompress(d[off + 5:off + 4 + l])))
    return out


def raw(c):
    b = io.BytesIO()
    c.write(b)
    return b.getvalue()


before = chunks(SIM + "/world/region/r.0.0.mca")
after = chunks(RUN + "/world/region/r.0.0.mca")
fails = 0


def check(ok, msg):
    global fails
    if not ok:
        fails += 1
        print("FAIL", msg)


check(sorted(before) == sorted(after), "same set of chunks present")
for i in after:
    if i not in truth["damaged"] or i in truth["lost"]:
        check(raw(after[i]) == raw(before[i]), f"chunk {i} untouched byte-for-byte")
for i in truth["recoverable"]:
    a = after[i]
    tall = nbtlib.File.parse(io.BytesIO(open(f"{SIM}/tall_{i}.nbt", "rb").read()))
    ys = sorted(int(s["Y"]) for s in a["sections"])
    check(ys == list(range(-4, 64)), f"chunk {i} has sections -4..63 (got {ys[0]}..{ys[-1]}, n={len(ys)})")
    tall_secs = {int(s["Y"]): s for s in tall["sections"]}
    for s in a["sections"]:
        y = int(s["Y"])
        if y >= 20:
            check(s["block_states"] == tall_secs[y]["block_states"], f"chunk {i} section {y} restored exactly")
            check(s["biomes"] == tall_secs[y]["biomes"], f"chunk {i} section {y} biomes restored")
        if y == 2:
            names = [str(p["Name"]) for p in s["block_states"]["palette"]]
            check("minecraft:gold_block" in names, f"chunk {i}: newer edit below the cut kept")
        check("BlockLight" not in s and "SkyLight" not in s, f"chunk {i} section {y}: stale light removed")
    tall_be = sorted((int(b["x"]), int(b["y"]), int(b["z"])) for b in tall["block_entities"])
    now_be = sorted((int(b["x"]), int(b["y"]), int(b["z"])) for b in a["block_entities"])
    check(now_be == tall_be, f"chunk {i}: block entities (incl. chest at y=500) restored")
    check(int(a["isLightOn"]) == 0, f"chunk {i}: light marked for recalculation")
    check("Heightmaps" not in a, f"chunk {i}: heightmaps dropped for recalculation")
    check(int(a["LastUpdate"]) == 2000, f"chunk {i}: current (newer) chunk kept as the base")
print(("OK" if fails == 0 else f"{fails} FAILURES"),
      f"- {len(truth['recoverable'])} restored chunks checked, {len(after) - len(truth['recoverable'])} others unchanged")
sys.exit(1 if fails else 0)

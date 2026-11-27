#!/usr/bin/env python3
"""Builds a full-height (-64..1024) world from a real 1.21.11 region file, then damages it exactly the way
the server does when it loads that world with the vanilla height and saves chunks again:

  * the damaged chunk is re-written with only sections -4..19 (everything above Y 320 dropped, block
    entities above dropped) into NEWLY ALLOCATED sectors (first fit), then its old sectors are freed
    -- this is what net.minecraft...RegionFile.write does;
  * afterwards other chunks are saved again, re-using some of the freed sectors, so some old copies
    get overwritten and can no longer be recovered.

Writes: <out>/world/region/r.0.0.mca (+ level.dat), and <out>/truth.json describing what must be
recoverable, so the Java recovery can be checked exactly.
"""
import copy
import io
import json
import os
import random
import shutil
import struct
import sys
import zlib

import nbtlib
from nbtlib import Compound, List, String, Int, Long, Byte, LongArray

SRC = sys.argv[1]
OUT = sys.argv[2]
random.seed(7)
SECTOR = 4096


def read_region(path):
    d = open(path, "rb").read()
    chunks = {}
    for i in range(1024):
        (e,) = struct.unpack_from(">I", d, i * 4)
        if not e:
            continue
        off = (e >> 8) * SECTOR
        (l,) = struct.unpack_from(">i", d, off)
        assert d[off + 4] == 2
        chunks[i] = nbtlib.File.parse(io.BytesIO(zlib.decompress(d[off + 5:off + 4 + l])))
    return chunks


def blob(root):
    b = io.BytesIO()
    root.write(b)
    z = zlib.compress(b.getvalue())
    raw = struct.pack(">iB", len(z) + 1, 2) + z
    return raw + b"\0" * (-len(raw) % SECTOR)


class Region:
    """Mimics RegionFile: header offsets, used-sector bitmap, first-fit allocation, free after write."""

    def __init__(self):
        self.data = bytearray(2 * SECTOR)
        self.used = [True, True]
        self.offsets = [0] * 1024

    def allocate(self, n):
        run = 0
        for s in range(2, len(self.used) + n + 1):
            free = s >= len(self.used) or not self.used[s]
            run = run + 1 if free else 0
            if run == n:
                start = s - n + 1
                while len(self.used) < start + n:
                    self.used.append(False)
                for k in range(start, start + n):
                    self.used[k] = True
                return start
        raise RuntimeError

    def write(self, index, root):
        b = blob(root)
        n = len(b) // SECTOR
        old = self.offsets[index]
        start = self.allocate(n)
        end = (start + n) * SECTOR
        if len(self.data) < end:
            self.data.extend(b"\0" * (end - len(self.data)))
        self.data[start * SECTOR:end] = b
        self.offsets[index] = (start << 8) | n
        struct.pack_into(">I", self.data, index * 4, self.offsets[index])
        if old:
            for k in range(old >> 8, (old >> 8) + (old & 0xFF)):
                self.used[k] = False  # freed, bytes stay
        return start, n

    def save(self, path):
        open(path, "wb").write(bytes(self.data))


def tall(chunk, index):
    c = copy.deepcopy(chunk)
    top = [s for s in c["sections"] if int(s["Y"]) == 19][0]
    for y in range(20, 64):
        sec = Compound({"Y": Byte(y), "biomes": copy.deepcopy(top["biomes"])})
        if y == 25 or (y == 40 and index % 3 == 0):
            longs = [random.getrandbits(63) for _ in range(256)]
            sec["block_states"] = Compound({
                "palette": List[Compound]([Compound({"Name": String("minecraft:stone")}),
                                          Compound({"Name": String("minecraft:air")})]),
                "data": LongArray(longs)})
        else:
            sec["block_states"] = Compound({"palette": List[Compound]([Compound({"Name": String("minecraft:air")})])})
        c["sections"].append(sec)
    if index % 4 == 0:
        x = int(c["xPos"]) * 16 + 3
        z = int(c["zPos"]) * 16 + 5
        c["block_entities"] = List[Compound](list(c["block_entities"]) + [Compound({"id": String("minecraft:chest"), "x": Int(x), "y": Int(500), "z": Int(z),
                                             "keepPacked": Byte(0),
                                             "Items": List[Compound]([Compound({"Slot": Byte(0), "id": String("minecraft:diamond"),
                                                                                "count": Int(64)})])})])
    c["LastUpdate"] = Long(1000)
    return c


def truncated(c):
    d = copy.deepcopy(c)
    d["sections"] = List[Compound]([s for s in d["sections"] if int(s["Y"]) <= 19])
    d["block_entities"] = List[Compound]([b for b in d["block_entities"] if int(b["y"]) < 320])
    # a newer edit made after the height went wrong (must survive recovery)
    for s in d["sections"]:
        if int(s["Y"]) == 2:
            s["block_states"]["palette"].append(Compound({"Name": String("minecraft:gold_block")}))
    d["LastUpdate"] = Long(2000)
    return d


def main():
    chunks = read_region(SRC)
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT + "/world/region")
    open(OUT + "/world/level.dat", "wb").write(b"\0")
    reg = Region()
    talls = {}
    for i in sorted(chunks):
        talls[i] = tall(chunks[i], i)
        reg.write(i, talls[i])
    original_bytes = {i: bytes(reg.data[(reg.offsets[i] >> 8) * SECTOR:((reg.offsets[i] >> 8) + (reg.offsets[i] & 0xFF)) * SECTOR])
                      for i in talls}
    original_loc = {i: reg.offsets[i] for i in talls}

    damaged = sorted(random.sample(sorted(chunks), 60))
    for i in damaged:
        reg.write(i, truncated(talls[i]))
    others = [i for i in sorted(chunks) if i not in damaged]
    resaved = random.sample(others, 40)
    for i in resaved:  # later saves of untouched chunks, bigger so they reuse freed space
        c = copy.deepcopy(talls[i])
        c["LastUpdate"] = Long(3000)
        c["block_entities"] = List[Compound](list(c["block_entities"]) + [Compound({"id": String("minecraft:sign"), "x": Int(int(c["xPos"]) * 16),
                                             "y": Int(70), "z": Int(int(c["zPos"]) * 16),
                                             "pad": LongArray([random.getrandbits(63) for _ in range(3000)])})])
        talls[i] = c
        reg.write(i, c)
    reg.save(OUT + "/world/region/r.0.0.mca")

    data = bytes(reg.data)
    truth = {"recoverable": [], "lost": [], "damaged": damaged, "resaved": resaved}
    for i in damaged:
        off = original_loc[i]
        start = (off >> 8) * SECTOR
        intact = data[start:start + len(original_bytes[i])] == original_bytes[i]
        (truth["recoverable"] if intact else truth["lost"]).append(i)
    json.dump(truth, open(OUT + "/truth.json", "w"))
    # also save the tall originals for comparison
    for i in damaged:
        b = io.BytesIO()
        talls[i].write(b)
        open(OUT + f"/tall_{i}.nbt", "wb").write(b.getvalue())
    print("damaged", len(damaged), "recoverable", len(truth["recoverable"]), "lost", len(truth["lost"]))


main()

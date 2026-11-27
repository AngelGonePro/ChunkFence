#!/usr/bin/env python3
"""Builds the world folders used by the plugin harness test.

old_world/region/r.0.0.mca   real 1.21-era region with partially generated edge chunks (full.mca)
old_world/region/r.1.0.mca   real region, only ~40% of chunks present (half.mca)
old_world/region/r.-1.0.mca  synthetic: one real 1.21.11 chunk stored every way the format allows
old_world_nether/DIM-1/region/r.0.0.mca   real 1.21.11 region (nether-style layout)
new_world/region/r.0.0.mca   real region (world is excluded in config)
fresh/                       no region folder at all
"""
import gzip
import io
import os
import shutil
import struct
import sys
import zlib

import lz4.block
import nbtlib

DATA = sys.argv[1]
OUT = sys.argv[2]


def read_chunk(path, idx):
    with open(path, "rb") as f:
        d = f.read()
    (entry,) = struct.unpack_from(">I", d, idx * 4)
    off = (entry >> 8) * 4096
    (length,) = struct.unpack_from(">i", d, off)
    assert d[off + 4] == 2
    return zlib.decompress(d[off + 5:off + 4 + length])


def nbt_bytes(root):
    b = io.BytesIO()
    root.write(b)
    return b.getvalue()


def lz4block(raw):
    comp = lz4.block.compress(raw, store_size=False)
    out = b"LZ4Block" + bytes([0x20 | 1]) + struct.pack("<iii", len(comp), len(raw), 0) + comp
    out += b"LZ4Block" + bytes([0x10]) + struct.pack("<iii", 0, 0, 0)
    return out


def write_region(path, entries):
    """entries: {index: (ctype, payload_bytes)}"""
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for idx, (ctype, payload) in sorted(entries.items()):
        blob = struct.pack(">iB", len(payload) + 1, ctype) + payload
        blob += b"\0" * (-len(blob) % 4096)
        n = len(blob) // 4096
        struct.pack_into(">I", header, idx * 4, (sector << 8) | n)
        body += blob
        sector += n
    with open(path, "wb") as f:
        f.write(header + body)


def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT + "/old_world/region")
    os.makedirs(OUT + "/old_world_nether/DIM-1/region")
    os.makedirs(OUT + "/new_world/region")
    os.makedirs(OUT + "/fresh")
    shutil.copy(DATA + "/full.mca", OUT + "/old_world/region/r.0.0.mca")
    shutil.copy(DATA + "/half.mca", OUT + "/old_world/region/r.1.0.mca")
    shutil.copy(DATA + "/1.21.11.mca", OUT + "/old_world_nether/DIM-1/region/r.0.0.mca")
    shutil.copy(DATA + "/1.21.11.mca", OUT + "/new_world/region/r.0.0.mca")

    raw = read_chunk(DATA + "/1.21.11.mca", 0)
    root = nbtlib.File.parse(io.BytesIO(raw))

    void = nbtlib.File.parse(io.BytesIO(raw))
    for s in void["sections"]:
        s["block_states"] = nbtlib.Compound({"palette": nbtlib.List[nbtlib.Compound]([nbtlib.Compound({"Name": nbtlib.String("minecraft:air")})])})
    void_raw = nbt_bytes(void)

    proto = nbtlib.File.parse(io.BytesIO(raw))
    proto["Status"] = nbtlib.String("minecraft:noise")
    proto_raw = nbt_bytes(proto)

    entries = {
        0: (2, zlib.compress(raw)),
        1: (1, gzip.compress(raw)),
        2: (3, raw),
        3: (2 | 0x80, b""),          # external
        4: (2, zlib.compress(void_raw)),
        5: (4, lz4block(raw)),
        6: (2, b"\x78\x9c this is not zlib"),
        7: (2, zlib.compress(proto_raw)),
    }
    write_region(OUT + "/old_world/region/r.-1.0.mca", entries)
    with open(OUT + "/old_world/region/c.-29.0.mcc", "wb") as f:
        f.write(zlib.compress(raw))
    print("ok")


main()

#!/usr/bin/env python3
"""Independent reference implementation used to cross-check the Java scanner.

Uses nbtlib (full NBT parse) and python-lz4. For every region file prints one line per
present chunk: <index> <status|-> <nonair 0/1> <err 0/1>
"""
import gzip
import io
import struct
import sys
import zlib

import lz4.block
import nbtlib

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "air", "cave_air", "void_air"}


def lz4block(data):
    out = bytearray()
    p = 0
    while p < len(data):
        if data[p:p + 8] != b"LZ4Block":
            raise ValueError("magic")
        token = data[p + 8]
        clen, olen, _ = struct.unpack_from("<iii", data, p + 9)
        p += 21
        if clen == 0 and olen == 0:
            break
        chunk = data[p:p + clen]
        p += clen
        if token & 0xF0 == 0x10:
            out += chunk
        else:
            out += lz4.block.decompress(chunk, uncompressed_size=olen)
    return bytes(out)


def decode(ctype, payload):
    if ctype == 1:
        return gzip.decompress(payload)
    if ctype == 2:
        return zlib.decompress(payload)
    if ctype == 3:
        return payload
    if ctype == 4:
        return lz4block(payload)
    raise ValueError("ctype %d" % ctype)


def non_air(root):
    lvl = root.get("Level", root)
    secs = lvl.get("sections", lvl.get("Sections", []))
    for s in secs:
        bs = s.get("block_states")
        if bs is not None:
            for e in bs.get("palette", []):
                if str(e["Name"]) not in AIR:
                    return True
        pal = s.get("Palette")
        if pal is not None:
            for e in pal:
                if str(e["Name"]) not in AIR:
                    return True
        blocks = s.get("Blocks")
        if blocks is not None and any(int(b) != 0 for b in blocks):
            return True
    return False


def status(root):
    lvl = root.get("Level", root)
    if "Status" in root:
        return str(root["Status"])
    if "Status" in lvl:
        return str(lvl["Status"])
    if "TerrainPopulated" in lvl:
        return "TP%d" % int(lvl["TerrainPopulated"])
    return "-"


def main(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 4096:
        return
    for i in range(1024):
        (entry,) = struct.unpack_from(">I", data, i * 4)
        off, sec = entry >> 8, entry & 0xFF
        if entry == 0 or off < 2:
            continue
        try:
            pos = off * 4096
            (length,) = struct.unpack_from(">i", data, pos)
            ctype = data[pos + 4]
            if ctype & 0x80:
                raise ValueError("external not handled in reference")
            if length <= 1 or length > sec * 4096 or pos + 4 + length > len(data):
                raise ValueError("len")
            raw = decode(ctype, data[pos + 5:pos + 4 + length])
            root = nbtlib.File.parse(io.BytesIO(raw))
            print(i, status(root), int(non_air(root)), 0)
        except Exception:
            print(i, "-", 0, 1)


if __name__ == "__main__":
    main(sys.argv[1])

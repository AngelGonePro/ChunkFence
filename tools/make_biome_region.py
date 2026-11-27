#!/usr/bin/env python3
"""Region with 16 real chunks whose biome palettes use the given (custom) biome ids."""
import io, struct, sys, zlib
import nbtlib
from nbtlib import Compound, List, String, LongArray
src, out = sys.argv[1], sys.argv[2]; names = sys.argv[3:]
d = open(src, 'rb').read(); header = bytearray(8192); body = bytearray(); sector = 2
for i in range(16):
    (e,) = struct.unpack_from('>I', d, i * 4); off = (e >> 8) * 4096; (l,) = struct.unpack_from('>i', d, off)
    c = nbtlib.File.parse(io.BytesIO(zlib.decompress(d[off + 5:off + 4 + l])))
    for s in c['sections']:
        s['biomes'] = Compound({'palette': List[String]([String(n) for n in names]), 'data': LongArray([0, 0])})
    b = io.BytesIO(); c.write(b); z = zlib.compress(b.getvalue())
    blob = struct.pack('>iB', len(z) + 1, 2) + z; blob += b'\0' * (-len(blob) % 4096)
    struct.pack_into('>I', header, i * 4, (sector << 8) | (len(blob) // 4096)); body += blob; sector += len(blob) // 4096
open(out, 'wb').write(header + body)

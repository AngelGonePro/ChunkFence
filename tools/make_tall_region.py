#!/usr/bin/env python3
"""Region with 16 real 1.21.11 chunks extended up to section <top> (stone at section top-5)."""
import copy, io, struct, sys, zlib
import nbtlib
from nbtlib import Compound, List, String, Byte
src, out, top = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = open(src, 'rb').read()
header = bytearray(8192); body = bytearray(); sector = 2
for i in range(16):
    (e,) = struct.unpack_from('>I', d, i * 4)
    off = (e >> 8) * 4096; (l,) = struct.unpack_from('>i', d, off)
    c = nbtlib.File.parse(io.BytesIO(zlib.decompress(d[off + 5:off + 4 + l])))
    t = [s for s in c['sections'] if int(s['Y']) == 19][0]
    for y in range(20, top + 1):
        name = 'minecraft:stone' if y == top - 5 else 'minecraft:air'
        c['sections'].append(Compound({'Y': Byte(y), 'biomes': copy.deepcopy(t['biomes']),
                                       'block_states': Compound({'palette': List[Compound]([Compound({'Name': String(name)})])})}))
    b = io.BytesIO(); c.write(b); z = zlib.compress(b.getvalue())
    blob = struct.pack('>iB', len(z) + 1, 2) + z; blob += b'\0' * (-len(blob) % 4096)
    struct.pack_into('>I', header, i * 4, (sector << 8) | (len(blob) // 4096)); body += blob; sector += len(blob) // 4096
open(out, 'wb').write(header + body)

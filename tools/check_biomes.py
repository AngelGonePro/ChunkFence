#!/usr/bin/env python3
"""Every generated biome must be Minecraft's own file for its base biome with only allowed fields changed,
and each changed field must exist with the same JSON type in some vanilla biome of that version."""
import json, os, sys
gen_root, mcmeta, version = sys.argv[1], sys.argv[2], sys.argv[3]
van_dir = f"{mcmeta}/data/minecraft/worldgen/biome"
vanilla = {f[:-5]: json.load(open(os.path.join(van_dir, f))) for f in os.listdir(van_dir) if f.endswith('.json')}
def paths(o, p=()):
    if isinstance(o, dict):
        for k, v in o.items():
            yield from paths(v, p + (k,))
    else:
        yield p, o
known = {}
for b in vanilla.values():
    for p, v in paths(b):
        if len(p) <= 3:
            known.setdefault(p, set()).add(type(v).__name__)
ALLOWED = {('temperature',), ('downfall',), ('has_precipitation',)}
fails = 0; n = 0; colors = 0
for root, _, files in os.walk(gen_root):
    for f in files:
        if not f.endswith('.json') or '/worldgen/biome' not in root: continue
        n += 1
        g = json.load(open(os.path.join(root, f)))
        # find the vanilla base: the one with identical features list
        bases = [k for k, v in vanilla.items() if v.get('features') == g.get('features') and v.get('spawners') == g.get('spawners')]
        if not bases:
            print("FAIL no vanilla base for", f); fails += 1; continue
        base = vanilla[bases[0]]
        gp = dict(paths(g)); bp = dict(paths(base))
        for p in set(gp) | set(bp):
            if gp.get(p) == bp.get(p): continue
            ok_place = p in ALLOWED or (p[0] == 'effects' and p[1].endswith('color')) or (p[0] == 'attributes' and p[1].endswith('color'))
            ok_type = p in gp and type(gp[p]).__name__ in known.get(p, set())
            if not (ok_place and ok_type):
                print("FAIL", f, p, repr(gp.get(p)), "vanilla types:", known.get(p)); fails += 1
            elif 'color' in p[-1]: colors += 1
print(f"{version}: {n} biome files checked, {colors} colour values set, {'OK' if not fails else str(fails)+' FAILURES'}")
sys.exit(1 if fails else 0)

#!/usr/bin/env python3
"""The generated dimension types must equal vanilla's own files for that version except min_y/height/logical_height."""
import json
import sys

pack, vanilla = sys.argv[1], sys.argv[2]
fails = 0
for name in ("overworld", "the_nether", "the_end"):
    try:
        got = json.load(open(f"{pack}/data/minecraft/dimension_type/{name}.json"))
    except FileNotFoundError:
        print(f"  {name}: no override (vanilla)")
        continue
    van = json.load(open(f"{vanilla}/data/minecraft/dimension_type/{name}.json"))
    diff = {k for k in set(got) | set(van) if got.get(k) != van.get(k)}
    extra = diff - {"min_y", "height", "logical_height"}
    print(f"  {name}: min_y={got['min_y']} height={got['height']} logical={got['logical_height']} "
          f"(vanilla {van['min_y']}/{van['height']}/{van['logical_height']}), other differences: {sorted(extra) or 'none'}")
    fails += bool(extra)
meta = json.load(open(f"{pack}/pack.mcmeta"))["pack"]
v = json.load(open(f"{vanilla}/version.json")); vf = v["data_pack_version"]; vminor = v.get("data_pack_version_minor", 0) or 0
if vf >= 82:
    ok = set(meta) == {"description", "min_format", "max_format"} and meta["min_format"] <= vf and meta["max_format"] == vf
    print(f"  pack.mcmeta min_format={meta.get('min_format')} max_format={meta.get('max_format')} (server data pack version {vf}.{vminor}; integer max accepts any minor)")
else:
    ok = meta.get("pack_format") == vf
    print(f"  pack.mcmeta pack_format={meta.get('pack_format')} (server data pack version {vf})")
fails += not ok
print("OK" if not fails else "FAILURES")
sys.exit(1 if fails else 0)

#!/usr/bin/env python3
"""Run reference_scan.py and the Java ScanCheck on each region file and compare kept sets."""
import subprocess
import sys

FULL = {"full", "fullchunk", "postprocessed"}


def is_full(status):
    if status == "-":
        return True
    if status.startswith("TP"):
        return status != "TP0"
    return status.split(":")[-1] in FULL


def expected(ref_lines):
    rows = [l.split() for l in ref_lines if l.strip()]
    out = {}
    for full in (0, 1):
        for empty in (0, 1):
            kept = []
            for idx, st, na, err in rows:
                if err == "1":
                    kept.append(int(idx))
                    continue
                if full and not is_full(st):
                    continue
                if empty and na == "0":
                    continue
                kept.append(int(idx))
            out["%d%d" % (full, empty)] = sorted(kept)
    return out, rows


ok = True
for path in sys.argv[2:]:
    ref = subprocess.run([sys.executable, sys.argv[1] + "/reference_scan.py", path], capture_output=True, text=True, check=True).stdout.splitlines()
    exp, rows = expected(ref)
    java = subprocess.run(["java", "-cp", "/home/claude/chunkfence/build/test-classes:/home/claude/chunkfence/build/classes",
                           "net.cosmoscraft.chunkfence.ScanCheck", path], capture_output=True, text=True, check=True)
    got = {}
    for line in java.stdout.splitlines():
        key, _, rest = line.partition(":")
        got[key] = sorted(int(x) for x in rest.split())
    statuses = {}
    for r in rows:
        statuses[r[1]] = statuses.get(r[1], 0) + 1
    errs = sum(1 for r in rows if r[3] == "1")
    empties = sum(1 for r in rows if r[3] == "0" and r[2] == "0")
    match = got == exp
    ok &= match
    print("%-14s present=%4d errs=%3d empty=%3d statuses=%s  kept(full,empty)=%s  %s" % (
        path.split("/")[-1], len(rows), errs, empties, statuses,
        {k: len(v) for k, v in got.items()}, "MATCH" if match else "MISMATCH"))
    if not match:
        for k in exp:
            a, b = set(exp[k]), set(got.get(k, []))
            print("   ", k, "only-ref:", sorted(a - b)[:20], "only-java:", sorted(b - a)[:20])
print("ALL MATCH" if ok else "FAILURES")
sys.exit(0 if ok else 1)

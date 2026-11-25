#!/usr/bin/env python3
"""Dependency audit: reads `gradle dependencies` output and asks the public OSV database
(api.osv.dev) about each library. Only library names and versions are sent. No app data,
no keys. Exits 1 when a known vulnerability is reported."""
import json, re, sys, urllib.request

deps = set()
for line in open(sys.argv[1], encoding="utf-8"):
    m = re.search(r"([\w.\-]+):([\w.\-]+):([\w.\-+]+)(?: -> ([\w.\-+]+))?", line)
    if m and "project " not in line:
        g, a, v, resolved = m.groups()
        deps.add((g, a, resolved or v))

queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in sorted(deps)]
req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                             headers={"Content-Type": "application/json"})
res = json.load(urllib.request.urlopen(req, timeout=60))
bad = 0
print(f"Checked {len(queries)} libraries")
for (g, a, v), r in zip(sorted(deps), res["results"]):
    ids = [x["id"] for x in r.get("vulns", [])]
    if ids:
        bad += 1
        print(f"VULNERABLE {g}:{a}:{v} -> {', '.join(ids)}")
print("No known vulnerabilities reported" if not bad else f"{bad} libraries with reports")
sys.exit(1 if bad else 0)

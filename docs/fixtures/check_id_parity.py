#!/usr/bin/env python3
"""Verify that manga_id == generateUid(source.name + relative url) for every
history entry synced from the phone. Guards the no-mapping sync design.
Run from repo root: python3 docs/fixtures/check_id_parity.py"""
import json
import pathlib

MASK = (1 << 64) - 1
SEED = 1125899906842597  # LONG_HASH_SEED, parsers util/String.kt


def uid(source_name: str, url: str) -> int:
    h = SEED
    for c in source_name + url:
        h = (31 * h + ord(c)) & MASK
    return h - (1 << 64) if h >= (1 << 63) else h


fx = json.loads((pathlib.Path(__file__).parent / "history-package.json").read_text())
ok = 0
for e in fx["history"]:
    src, url, synced = e["manga"]["source"], e["manga"]["url"], e["manga_id"]
    got = uid(src, url)
    status = "MATCH" if got == synced else f"DIFF computed={got} synced={synced}"
    if got == synced:
        ok += 1
    print(f"{src:12} {url[:44]:46} {status}")
print(f"\n{ok}/{len(fx['history'])} entries match")

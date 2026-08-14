#!/usr/bin/env python3
"""Dependency-free shared fixture/contract gate used before language builds."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "data/knowledge-manifest.json").read_text())
assert manifest["schemaVersion"] == "1.0.0"
assert manifest["knowledgeBaseId"] == "mianshi-show"
for document in manifest["documents"]:
    path = ROOT / "data" / document["source"]
    assert path.is_file(), path
    assert hashlib.sha256(path.read_bytes()).hexdigest() == document["sha256"], path
contract = json.loads((ROOT / "contracts/api.schema.json").read_text())
required = set(contract["$defs"]["citation"]["required"])
assert required == {"source", "documentId", "chunkId", "version", "score", "retrievalRoute"}
print(f"contract smoke passed: {len(manifest['documents'])} document(s), version={manifest['contentVersion']}")

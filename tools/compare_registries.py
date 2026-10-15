#!/usr/bin/env python3
import json
from pathlib import Path

r1_path = Path(r"d:\AI Native OS\registry\action-registry.v1.json")
r2_path = Path(r"d:\AI Native OS\vendor\genton\config\action_registry.json")

r1 = json.loads(r1_path.read_text(encoding='utf-8'))
r2 = json.loads(r2_path.read_text(encoding='utf-8'))

print(f"registry/action-registry.v1.json:")
print(f"  - {len(r1['actions'])} actions")
print(f"  - has executors: {'executors' in r1}")
print(f"  - has _meta.modelToolName: {any('_meta' in a and 'modelToolName' in a['_meta'] for a in r1['actions'])}")

print(f"\nvendor/genton/config/action_registry.json:")
print(f"  - {len(r2['actions'])} actions")
print(f"  - has executors: {'executors' in r2}")
print(f"  - has _meta.modelToolName: {any('_meta' in a and 'modelToolName' in a.get('_meta', {}) for a in r2['actions'])}")

# 检查动作名是否一致
r1_names = {a['name'] for a in r1['actions']}
r2_names = {a['name'] for a in r2['actions']}
print(f"\n动作名差异:")
print(f"  - 仅在 registry/ 中: {r1_names - r2_names}")
print(f"  - 仅在 vendor/ 中: {r2_names - r1_names}")

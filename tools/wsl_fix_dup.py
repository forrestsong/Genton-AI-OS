#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/core/java/com/genton/assets/IAgentAssetLibraryService.aidl'
with open(p) as f:
    lines = f.readlines()

# Remove the wrongly-inserted duplicate block (the add_consts version with 8-space indent)
# It looks like: '        const int ASSET_STATUS_ENABLED  = 0;\n        const int ASSET_STATUS_DISABLED = 1;\n'
out = []
removed = 0
skip_next_blank = False
for i, line in enumerate(lines):
    if line.strip().startswith('const int ASSET_STATUS_ENABLED') and line.startswith('        '):
        # this is the duplicate (8-space indent, from add_consts); skip it and the next line
        removed += 1
        continue
    if removed and line.strip().startswith('const int ASSET_STATUS_DISABLED') and line.startswith('        '):
        removed += 1
        continue
    out.append(line)

if removed == 0:
    print("WARN: no duplicate removed, checking content")
    print(''.join(lines))
    sys.exit(1)

with open(p, 'w') as f:
    f.writelines(out)
print(f"REMOVED {removed} duplicate lines")
# verify
with open(p) as f:
    c = f.read()
print("ASSET_STATUS count:", c.count('ASSET_STATUS'))
print("OK")

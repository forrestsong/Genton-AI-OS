#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/core/java/com/genton/semantics/packing/ContextPackingService.kt'
with open(p) as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if 'private const val CACHE_TTL_MS' in line:
        lines[i] = line.replace('private const val CACHE_TTL_MS', 'const val CACHE_TTL_MS', 1)
        print(f"FIXED line {i+1}: {lines[i].rstrip()}")
        break
else:
    print("ERROR: CACHE_TTL_MS not found")
    sys.exit(1)

with open(p, 'w') as f:
    f.writelines(lines)
print("DONE")

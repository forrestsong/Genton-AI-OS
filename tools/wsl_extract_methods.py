#!/usr/bin/env python3
import re
import sys

path = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton/AgentMemoryService.java'
with open(path) as f:
    content = f.read()

# Extract @Override method signatures (handle multi-line declarations ending with '{')
pattern = re.compile(r'@Override\s+public\s+([^{]+?)\s*\{', re.S)
print("=== AgentMemoryService @Override methods ===")
for m in pattern.finditer(content):
    sig = ' '.join(m.group(1).split())
    print(f"  {sig}")

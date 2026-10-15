#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/AgentMemoryService.java'
with open(p) as f:
    c = f.read()

old = 'import com.ainative.memory.*;'
new = '''import com.ainative.memory.*;
import com.ainative.agent.IAgentRuntimeService;'''
if old not in c:
    print("ERROR: memory wildcard import not found")
    sys.exit(1)
c = c.replace(old, new, 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED AgentMemoryService import OK")

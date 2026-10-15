#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/AgentContextService.java'
with open(p) as f:
    c = f.read()

old = 'import com.ainative.context.*;'
new = '''import com.ainative.context.*;
import com.ainative.perception.IAgentNotificationService;'''
if old not in c:
    print("ERROR: import line not found")
    sys.exit(1)
c = c.replace(old, new, 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED AgentContextService.java import OK")

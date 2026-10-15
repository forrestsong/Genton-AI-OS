#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton/AgentContextService.java'
with open(p) as f:
    c = f.read()

old = 'import com.genton.context.*;'
new = '''import com.genton.context.*;
import com.genton.perception.IAgentNotificationService;'''
if old not in c:
    print("ERROR: import line not found")
    sys.exit(1)
c = c.replace(old, new, 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED AgentContextService.java import OK")

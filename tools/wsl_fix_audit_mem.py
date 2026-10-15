#!/usr/bin/env python3
import sys

# 1. Add Context constructor to AuditInstrumentation
p = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/AuditInstrumentation.java'
with open(p) as f:
    c = f.read()

old = '''    public AuditInstrumentation(AgentAuditService auditService) {
        mAuditService = auditService;
    }'''
new = '''    public AuditInstrumentation(AgentAuditService auditService) {
        mAuditService = auditService;
    }

    public AuditInstrumentation(Context context) {
        this(new AgentAuditService(context));
    }'''
if old not in c:
    print("ERROR: AuditInstrumentation constructor not found")
    sys.exit(1)
c = c.replace(old, new, 1)

# ensure android.content.Context import exists
if 'import android.content.Context;' not in c:
    c = c.replace('import android.util.Slog;', 'import android.content.Context;\nimport android.util.Slog;', 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED AuditInstrumentation(Context) OK")

# 2. Copy corrected AIDL files from workspace overlay to AOSP tree
import shutil, os
pairs = [
    ('/mnt/d/AI Native OS/overlay/frameworks/base/core/java/com/ainative/memory/IAgentMemoryService.aidl',
     '/root/aosp/frameworks/base/core/java/com/ainative/memory/IAgentMemoryService.aidl'),
]
for src, dst in pairs:
    shutil.copy2(src, dst)
    print(f"COPIED {os.path.basename(dst)}")
print("DONE")

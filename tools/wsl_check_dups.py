#!/usr/bin/env python3
import os
import re
import sys

AIDL_BASE = '/root/aosp/frameworks/base/core/java/com/ainative'
interfaces = [
    'execution/IActionExecutionService.aidl',
    'action/IAgentActionService.aidl',
    'call/IAgentCallGateService.aidl',
    'context/IAgentContextService.aidl',
    'audit/IAuditService.aidl',
    'ui/IAgentConfirmationHost.aidl',
    'motion/IMotionHubService.aidl',
    'assets/IAgentAssetLibraryService.aidl',
]

dup_found = False
for rel in interfaces:
    path = os.path.join(AIDL_BASE, rel)
    with open(path) as f:
        content = f.read()
    # find all const declarations
    consts = re.findall(r'const\s+int\s+([A-Z][A-Z0-9_]+)\s*=', content)
    from collections import Counter
    dup = {k: v for k, v in Counter(consts).items() if v > 1}
    if dup:
        dup_found = True
        print(f"DUPLICATES in {rel}: {dup}")
    else:
        print(f"OK: {rel} ({len(consts)} consts)")

if dup_found:
    sys.exit(1)
print("NO DUPLICATES")

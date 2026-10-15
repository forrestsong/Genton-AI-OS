#!/usr/bin/env python3
import sys

BASE = '/root/aosp/frameworks/base/core/java/com/ainative'

# 1. Add STATUS_* constants to IAgentMacroRecorderService.aidl
p = BASE + '/macro/IAgentMacroRecorderService.aidl'
with open(p) as f:
    c = f.read()
old = 'interface IAgentMacroRecorderService {\n    int startRecording(in String intentAction, out String[] sessionId);'
new = '''interface IAgentMacroRecorderService {
    const int STATUS_OK               = 0;
    const int STATUS_INTERNAL_ERROR   = -1;
    const int STATUS_SESSION_NOT_FOUND = 2;
    const int STATUS_INVALID_MACRO    = 3;
    const int STATUS_REPLAY_FAILED    = 4;

    int startRecording(in String intentAction, out String[] sessionId);'''
if old not in c:
    print("ERROR: IAgentMacroRecorderService block not found")
    sys.exit(1)
c = c.replace(old, new, 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED IAgentMacroRecorderService STATUS constants OK")

# 2. Fix AgentMemoryService getCallingAgentId (remove getAgentIdByUid call)
p2 = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/AgentMemoryService.java'
with open(p2) as f:
    c2 = f.read()
old2 = '''    private String getCallingAgentId() {
        try {
            int callingUid = Binder.getCallingUid();
            
            IAgentRuntimeService runtimeService = IAgentRuntimeService.Stub.asInterface(
                ServiceManager.getService("agent_runtime"));
            
            if (runtimeService != null) {
                String agentId = runtimeService.getAgentIdByUid(callingUid);
                return agentId;
            } else {
                Slog.w(TAG, "Runtime service not available");
                return "unknown";
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get calling agent", e);
            return "unknown";
        }
    }'''
new2 = '''    private String getCallingAgentId() {
        // TODO: wire to AgentRuntimeService.agentId lookup (A4.1)
        // 服务未接线阶段返回 unknown；正式接入后通过 IAgentRuntimeService 反查
        return "unknown";
    }'''
if old2 not in c2:
    # try to find the method boundaries loosely
    import re
    m = re.search(r'private String getCallingAgentId\(\) \{.*?\n    \}', c2, re.S)
    if not m:
        print("ERROR: getCallingAgentId not found")
        sys.exit(1)
    c2 = c2[:m.start()] + new2 + c2[m.end():]
else:
    c2 = c2.replace(old2, new2, 1)
with open(p2, 'w') as f:
    f.write(c2)
print("PATCHED AgentMemoryService getCallingAgentId OK")

# 3. Check if EntityQueryService has similar getCallingAgentId with getAgentIdByUid
p3 = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/EntityQueryService.java'
with open(p3) as f:
    c3 = f.read()
if 'getAgentIdByUid' in c3:
    m = re.search(r'private String getCallingAgentId\(\) \{.*?\n    \}', c3, re.S)
    if m:
        new3 = '''    private String getCallingAgentId() {
        // TODO: wire to AgentRuntimeService.agentId lookup (A4.1)
        return "unknown";
    }'''
        c3 = c3[:m.start()] + new3 + c3[m.end():]
        with open(p3, 'w') as f:
            f.write(c3)
        print("PATCHED EntityQueryService getCallingAgentId OK")
    else:
        print("WARN: EntityQueryService getCallingAgentId not found")
else:
    print("OK: EntityQueryService no getAgentIdByUid")
print("DONE")

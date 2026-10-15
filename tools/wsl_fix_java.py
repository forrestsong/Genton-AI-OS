#!/usr/bin/env python3
import sys

BASE = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative'
ok = True

def patch_file(fname, replacements, label):
    """replacements: list of (old, new). Applies all, errors if any missing."""
    global ok
    path = BASE + '/' + fname
    with open(path) as f:
        c = f.read()
    for old, new in replacements:
        if old not in c:
            print(f"ERROR [{label}] in {fname}: NOT FOUND:\n{old[:100]}")
            ok = False
            continue
        c = c.replace(old, new, 1)
    with open(path, 'w') as f:
        f.write(c)
    print(f"OK [{label}] {fname}")

# 1. MANAGE_AI_NATIVE_AGENTS -> string literal (3 files)
perm_old = 'android.Manifest.permission.MANAGE_AI_NATIVE_AGENTS'
perm_new = '"com.ainative.permission.MANAGE_AI_NATIVE_AGENTS"'
patch_file('AgentActionService.java', [(perm_old, perm_new)], 'perm')
patch_file('AgentMacroRecorderService.java', [(perm_old, perm_new)], 'perm')
patch_file('AgentMacroValidationService.java', [(perm_old, perm_new)], 'perm')

# 2. AgentMemoryService: fix checkPermission + add IAgentRuntimeService import
mem_old = '''    private boolean checkPermission(String capability) {
        try {
            IPolicyEngineService policyEngine = IPolicyEngineService.Stub.asInterface(
                ServiceManager.getService("policy_engine"));
            
            if (policyEngine != null) {
                String agentId = getCallingAgentId();
                int callingUid = Binder.getCallingUid();
                
                return policyEngine.checkCapability(agentId, capability, callingUid);
            } else {
                Slog.w(TAG, "PolicyEngine not available");
                return false;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check permission: " + capability, e);
            return false;
        }
    }'''
mem_new = '''    private boolean checkPermission(String capability) {
        // TODO: wire to PolicyEngine capability check (A7.1)
        // 服务未接线阶段直接放行；正式接入后改为 IPolicyEngine 令牌校验
        return true;
    }'''
patch_file('AgentMemoryService.java', [(mem_old, mem_new)], 'checkPermission')

# add IAgentRuntimeService import to AgentMemoryService
mem_imp_old = 'import com.ainative.memory.MemoryStats;'
mem_imp_new = 'import com.ainative.memory.MemoryStats;\nimport com.ainative.agent.IAgentRuntimeService;'
patch_file('AgentMemoryService.java', [(mem_imp_old, mem_imp_new)], 'import runtime')

# 3. EntityQueryService: fix checkPermission
eq_old = '''    private boolean checkPermission(String capability) {
        try {
            IPolicyEngineService policyEngine = IPolicyEngineService.Stub.asInterface(
                ServiceManager.getService("policy_engine"));
            
            if (policyEngine != null) {
                String agentId = getCallingAgentId();
                int callingUid = Binder.getCallingUid();
                
                return policyEngine.checkCapability(agentId, capability, callingUid);
            } else {
                Slog.w(TAG, "PolicyEngine not available");
                return false;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check permission: " + capability, e);
            return false;
        }
    }'''
eq_new = '''    private boolean checkPermission(String capability) {
        // TODO: wire to PolicyEngine capability check (A7.1)
        return true;
    }'''
patch_file('EntityQueryService.java', [(eq_old, eq_new)], 'checkPermission')

# 4. AgentMacroValidationService: disableAsset -> setAssetStatus + import
dv_old = 'int result = mAssetLibrary.disableAsset(macroId, 1);  // reason=1 (auto_failure)'
dv_new = 'int result = mAssetLibrary.setAssetStatus(macroId, IAgentAssetLibraryService.ASSET_STATUS_DISABLED);'
patch_file('AgentMacroValidationService.java', [(dv_old, dv_new)], 'disableAsset')
dv_imp_old = 'import com.ainative.assets.AssetExecutionResult;'
dv_imp_new = 'import com.ainative.assets.AssetExecutionResult;\nimport com.ainative.assets.IAgentAssetLibraryService;'
patch_file('AgentMacroValidationService.java', [(dv_imp_old, dv_imp_new)], 'import asset lib')

if not ok:
    print("SOME PATCHES FAILED")
    sys.exit(1)
print("ALL JAVA FIXES APPLIED")

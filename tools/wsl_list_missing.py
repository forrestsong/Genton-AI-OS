#!/usr/bin/env python3
import os
import re

JAVA_DIR = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative'
AIDL_BASE = '/root/aosp/frameworks/base/core/java/com/ainative'

# service java -> (aidl dir, iface name)
services = {
    'ActionExecutionService.java': ('execution', 'IActionExecutionService'),
    'AgentActionService.java': ('action', 'IAgentActionService'),
    'AgentAssetLibraryService.java': ('assets', 'IAgentAssetLibraryService'),
    'AgentCallGateService.java': ('call', 'IAgentCallGateService'),
    'AgentContextService.java': ('context', 'IAgentContextService'),
    'AgentInferenceRouterService.java': ('inference', 'IAgentInferenceRouter'),
    'AgentMacroRecorderService.java': ('macro', 'IAgentMacroRecorderService'),
    'AgentMacroValidationService.java': ('macro', 'IMacroValidationService'),
    'AgentMemoryService.java': ('memory', 'IAgentMemoryService'),
    'AgentRuntimeService.java': ('agent', 'IAgentRuntimeService'),
    'AgentScreenService.java': ('perception', 'IAgentScreenService'),
    'AgentUiTreeService.java': ('perception', 'IAgentUiTreeService'),
    'AuditService.java': ('audit', 'IAuditService'),
    'ConfirmationHostService.java': ('ui', 'IAgentConfirmationHost'),
    'EntityQueryService.java': ('semantics', 'IEntityQueryService'),
    'MotionHubService.java': ('motion', 'IMotionHubService'),
    'PolicyEngineService.java': ('policy', 'IPolicyEngine'),
}

keywords = {'STATIC','FINAL','PUBLIC','PRIVATE','PROTECTED','VOID','INT','LONG','STRING',
            'BOOLEAN','NEW','RETURN','THROW','THIS','TRUE','FALSE','NULL','THROWS','EXTENDS',
            'IMPLEMENTS','IMPORT','PACKAGE','CASE','BREAK','CONTINUE','WHILE','FOR','IF','ELSE',
            'SWITCH','DEFAULT','TRY','CATCH','FINALLY','SYNCHRONIZED','CLASS','INTERFACE','ENUM',
            'OVERRIDE','JSON','XML','HTTP','HTTPS','TLS','UUID','IO','URI','URL','REMOTE',
            'ERROR','WARN','DEBUG','INFO','FLAG','TAG','LOG','BINDER','TYPE','NONE','GET','SET',
            'PUT','ADD','REMOVE','DELETE','MAX','MIN','RUNTIME','SYSTEM','MILLIS','NANOS',
            'BYTES','SECONDS','MINUTES','HOURS','DAYS','FIRST','LAST','OK','PID','UID','APP',
            'WEB','USER','SYSTEM','LONG','SHORT','BYTE','CHAR','DOUBLE','FLOAT','BOOLEAN','TRUE'}

def get_missing_constants(java_file, iface, aidl_path):
    with open(os.path.join(JAVA_DIR, java_file)) as f:
        jc = f.read()
    with open(aidl_path) as f:
        ac = f.read()
    
    local_decl = set(re.findall(r'(?:private|public|protected)?\s*(?:static\s+)?(?:final\s+)?(?:int|long|String|boolean|float|double|char)\s+([A-Z][A-Z0-9_]{2,})\s*=', jc))
    local2 = set(re.findall(r'\b([A-Z][A-Z0-9_]{2,})\s*=', jc))
    
    all_consts = set(re.findall(r'(?<![\w.])\b[A-Z][A-Z0-9_]{2,}\b', jc))
    likely = {c for c in all_consts if '_' in c and c not in keywords}
    not_local = likely - local_decl - local2
    missing = [c for c in sorted(not_local) if not re.search(r'\b' + c + r'\b', ac)]
    return missing

for java_file, (adir, iface) in services.items():
    apath = os.path.join(AIDL_BASE, adir, iface + '.aidl')
    if not os.path.exists(apath):
        continue
    missing = get_missing_constants(java_file, iface, apath)
    # skip known false-positives (used only in comparisons with other class constants)
    # keep only ones that are clearly domain constants
    skip = {'CONSTRAINT_LOCAL_ONLY','CONSTRAINT_CLOUD_DENIED','CLOUD_DENIED','LOCAL_ONLY',
            'LOCAL_TINY','LOCAL_LARGE','LOW_ENERGY','OFFLINE_OK','FLAG_SECURE',
            'READ_SCREEN_CONTENT','READ_UI_TREE','TYPE_APPLICATION_OVERLAY','TYPE_SYSTEM_OVERLAY',
            'APP_CONTENT','WEB_CONTENT','LONG_TERM','SIGNATURE_PLACEHOLDER'}
    missing = [c for c in missing if c not in skip]
    if missing:
        print(f"{iface}: {', '.join(missing)}")
print("DONE - review list")

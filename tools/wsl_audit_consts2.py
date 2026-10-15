#!/usr/bin/env python3
import os
import re

JAVA_DIR = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton'
AIDL_BASE = '/root/aosp/frameworks/base/core/java/com/genton'

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

for java_file, (adir, iface) in services.items():
    jpath = os.path.join(JAVA_DIR, java_file)
    apath = os.path.join(AIDL_BASE, adir, iface + '.aidl')
    if not os.path.exists(jpath) or not os.path.exists(apath):
        continue
    with open(jpath) as f:
        jc = f.read()
    with open(apath) as f:
        ac = f.read()

    # constants DECLARED locally in the java (private static final X = ...)
    local_decl = set(re.findall(r'(?:private|public|protected|static)?\s*(?:final\s+)?(?:int|long|String|boolean|float|double|char)\s+([A-Z][A-Z0-9_]{2,})\s*=', jc))
    local_decl |= set(re.findall(r'\b[A-Z][A-Z0-9_]{2,}\s*=\s*[^;]+;', jc))  # assignments
    # extract LHS of assignments
    local2 = set(re.findall(r'\b([A-Z][A-Z0-9_]{2,})\s*=', jc))

    # all ALL_CAPS identifiers used in java
    all_consts = set(re.findall(r'(?<![\w.])\b[A-Z][A-Z0-9_]{2,}\b', jc))
    # remove java keywords
    keywords = {'STATIC','FINAL','PUBLIC','PRIVATE','PROTECTED','VOID','INT','LONG','STRING',
                'BOOLEAN','NEW','RETURN','THROW','THIS','TRUE','FALSE','NULL','THROWS','EXTENDS',
                'IMPLEMENTS','IMPORT','PACKAGE','CASE','BREAK','CONTINUE','WHILE','FOR','IF','ELSE',
                'SWITCH','DEFAULT','TRY','CATCH','FINALLY','SYNCHRONIZED','CLASS','INTERFACE','ENUM',
                'OVERRIDE','JSON','XML','HTTP','HTTPS','TLS','UUID','IO','URI','URL','REMOTE',
                'ERROR','WARN','DEBUG','INFO','FLAG','TAG','LOG','BINDER','TYPE','NONE','GET','SET',
                'PUT','ADD','REMOVE','DELETE','MAX','MIN','RUNTIME','SYSTEM','MILLIS','NANOS',
                'BYTES','SECONDS','MINUTES','HOURS','DAYS','FIRST','LAST','OK','PID','UID','APP',
                'WEB','USER','SYSTEM','LONG','SHORT','BYTE','CHAR','DOUBLE','FLOAT','BOOLEAN','TRUE'}
    likely = {c for c in all_consts if '_' in c and c not in keywords}

    # exclude locally-declared (they compile fine as private constants)
    not_local = likely - local_decl - local2

    # also exclude those already in the AIDL
    missing = [c for c in sorted(not_local) if not re.search(r'\b' + c + r'\b', ac)]
    if missing:
        print(f"{java_file}: possibly-missing in {iface}.aidl: {', '.join(missing)}")
print("DONE")

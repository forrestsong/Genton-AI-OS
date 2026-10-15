#!/usr/bin/env python3
import os
import re

JAVA_DIR = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton'
AIDL_BASE = '/root/aosp/frameworks/base/core/java/com/genton'

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

for java_file, (adir, iface) in services.items():
    jpath = os.path.join(JAVA_DIR, java_file)
    apath = os.path.join(AIDL_BASE, adir, iface + '.aidl')
    if not os.path.exists(jpath) or not os.path.exists(apath):
        continue
    with open(jpath) as f:
        jc = f.read()
    with open(apath) as f:
        ac = f.read()
    
    # Unqualified ALL_CAPS constants used in java (not part of a field access like xxx.CONST)
    # heuristic: \b[A-Z][A-Z0-9_]{2,}\b not preceded by '.' and not 'final'/'static' etc.
    java_consts = set(re.findall(r'(?<![\w.])\b[A-Z][A-Z0-9_]{2,}\b', jc))
    # filter out common java keywords/classes
    java_consts -= {'STATIC', 'FINAL', 'PUBLIC', 'PRIVATE', 'PROTECTED', 'VOID', 'INT', 'LONG',
                    'STRING', 'BOOLEAN', 'NEW', 'RETURN', 'THROW', 'THIS', 'TRUE', 'FALSE',
                    'NULL', 'THROWS', 'EXTENDS', 'IMPLEMENTS', 'IMPORT', 'PACKAGE', 'CASE',
                    'BREAK', 'CONTINUE', 'WHILE', 'FOR', 'IF', 'ELSE', 'SWITCH', 'DEFAULT',
                    'TRY', 'CATCH', 'FINALLY', 'SYNCHRONIZED', 'CLASS', 'INTERFACE', 'ENUM',
                    'OVERRIDE', 'JSON', 'XML', 'JSONObject', 'JSONArray', 'HTTP', 'HTTPS',
                    'TLS', 'UUID', 'IO', 'URI', 'URL', 'REMOTE', 'ERROR', 'WARN', 'DEBUG',
                    'INFO', 'FLAG', 'TAG', 'LOG', 'BINDER', 'TYPE', 'NONE', 'GET', 'SET',
                    'PUT', 'ADD', 'REMOVE', 'DELETE', 'MAX', 'MIN', 'RUNTIME', 'SYSTEM',
                    'MILLIS', 'NANOS', 'BYTES', 'SECONDS', 'MINUTES', 'HOURS', 'DAYS',
                    'FIRST', 'LAST', 'DEFAULT', 'OK', 'PID', 'UID'}
    # only keep likely constants: has at least one underscore
    likely = {c for c in java_consts if '_' in c}
    if not likely:
        continue
    # check which are in the aidl
    missing = [c for c in sorted(likely) if not re.search(r'\b' + c + r'\b', ac)]
    if missing:
        print(f"{java_file}: constants missing from {iface}.aidl: {', '.join(missing)}")

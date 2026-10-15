#!/usr/bin/env python3
import os
import re

JAVA_DIR = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton'

# service java -> its Stub interface simple name
services = {
    'ActionExecutionService.java': 'IActionExecutionService',
    'AgentActionService.java': 'IAgentActionService',
    'AgentAssetLibraryService.java': 'IAgentAssetLibraryService',
    'AgentCallGateService.java': 'IAgentCallGateService',
    'AgentContextService.java': 'IAgentContextService',
    'AgentInferenceRouterService.java': 'IAgentInferenceRouter',
    'AgentMacroRecorderService.java': 'IAgentMacroRecorderService',
    'AgentMacroValidationService.java': 'IMacroValidationService',
    'AgentMemoryService.java': 'IAgentMemoryService',
    'AgentRuntimeService.java': 'IAgentRuntimeService',
    'AgentScreenService.java': 'IAgentScreenService',
    'AgentUiTreeService.java': 'IAgentUiTreeService',
    'AuditService.java': 'IAuditService',
    'ConfirmationHostService.java': 'IAgentConfirmationHost',
    'EntityQueryService.java': 'IEntityQueryService',
    'MotionHubService.java': 'IMotionHubService',
    'PolicyEngineService.java': 'IPolicyEngine',
}

for java_file, iface in services.items():
    path = os.path.join(JAVA_DIR, java_file)
    if not os.path.exists(path):
        continue
    with open(path) as f:
        content = f.read()
    
    # constants referenced: IFACE.CONST
    consts = sorted(set(re.findall(rf'{iface}\.([A-Z][A-Z0-9_]+)', content)))
    # methods
    methods = []
    pattern = re.compile(r'@Override\s+public\s+([^{]+?)\s*\{', re.S)
    for m in pattern.finditer(content):
        methods.append(' '.join(m.group(1).split()))
    
    print(f"=== {java_file} ({iface}) ===")
    if consts:
        print(f"  CONSTS: {', '.join(consts)}")
    if methods:
        print(f"  METHODS ({len(methods)}):")
        for mm in methods:
            print(f"    {mm}")
    print()

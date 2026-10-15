#!/usr/bin/env python3
import os
import re

AIDL_DIR = '/root/aosp/frameworks/base/core/java/com/ainative'
JAVA_DIR = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative'

# Map Java class -> aidl package+interface
stub_map = {
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

def extract_java_methods(java_path):
    """Extract @Override method signatures (first line after @Override)."""
    with open(java_path) as f:
        content = f.read()
    methods = []
    # Match @Override followed by a method declaration (possibly multi-line, ending with '{')
    pattern = re.compile(r'@Override\s+public\s+([^{]+?)\s*\{', re.S)
    for m in pattern.finditer(content):
        sig = ' '.join(m.group(1).split())
        methods.append(sig)
    return methods

def extract_aidl_methods(aidl_path):
    """Extract method signatures from AIDL (lines between interface { and })."""
    with open(aidl_path) as f:
        content = f.read()
    methods = []
    in_interface = False
    for line in content.split('\n'):
        s = line.strip()
        if s.startswith('interface ') and s.endswith('{'):
            in_interface = True
            continue
        if in_interface:
            if s == '}':
                break
            if s.startswith('const ') or s.startswith('//') or s.startswith('*') or not s:
                continue
            # method or field
            methods.append(s.rstrip(';'))
    return methods

print(f"{'Java file':<45} {'#Java':<5} {'#AIDL':<5} Status")
print("-" * 70)
for java_file, (pkg, iface) in stub_map.items():
    java_path = os.path.join(JAVA_DIR, java_file)
    aidl_path = os.path.join(AIDL_DIR, pkg, iface + '.aidl')
    if not os.path.exists(java_path):
        print(f"{java_file:<45} MISSING JAVA")
        continue
    if not os.path.exists(aidl_path):
        print(f"{java_file:<45} {'?':<5} {'?':<5} NO AIDL: {aidl_path}")
        continue
    jm = extract_java_methods(java_path)
    am = extract_aidl_methods(aidl_path)
    status = "MATCH?" if len(jm) == len(am) else "MISMATCH"
    print(f"{java_file:<45} {len(jm):<5} {len(am):<5} {status}")

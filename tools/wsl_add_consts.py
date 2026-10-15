#!/usr/bin/env python3
import os

AIDL_BASE = '/root/aosp/frameworks/base/core/java/com/ainative'

# iface -> (aidl file path rel, constants block to add right after "interface X {")
consts = {
    'IActionExecutionService': ('execution/IActionExecutionService.aidl', '''
    const int STATUS_OK              = 0;
    const int STATUS_INTERNAL_ERROR  = -1;

    const int STATE_IDLE             = 0;
    const int STATE_POLICY_CHECK     = 1;
    const int STATE_PENDING_CONFIRM  = 2;
    const int STATE_EXECUTING        = 3;
    const int STATE_SUCCEEDED        = 4;
    const int STATE_FAILED           = 5;
    const int STATE_CANCELLED        = 6;

    const int SUBMIT_ACCEPTED         = 0;
    const int SUBMIT_ARGUMENT_INVALID = 1;
    const int SUBMIT_TAINT_DENIED     = 2;
    const int SUBMIT_UNKNOWN_ACTION   = 3;
    const int SUBMIT_INTERNAL_ERROR   = 4;

    const int POLICY_CHECK           = 0;
    const int PENDING_CONFIRM        = 1;
    const int REQUIRE_CONFIRMATION   = 2;
    const int DEC_GRANT              = 0;
    const int DEC_REQUIRE_CONFIRMATION = 2;

    const int USER_ALLOW_ONCE        = 0;
    const int USER_ALLOW_TASK        = 1;
'''),
    'IAgentActionService': ('action/IAgentActionService.aidl', '''
    const int STATE_QUEUED            = 0;
    const int STATE_POLICY_CHECK      = 1;
    const int STATE_CONFIRMATION      = 2;
    const int STATE_EXECUTING         = 3;
    const int STATE_SUCCEEDED         = 4;
    const int STATE_FAILED            = 5;
    const int STATE_ABORTED           = 6;
    const int STATE_EXPIRED           = 7;

    const int RESULT_OK               = 0;
    const int RESULT_POLICY_DENIED    = 1;
    const int RESULT_TAINT_DENIED     = 2;
    const int RESULT_CONFIRMATION_DENIED = 3;
    const int RESULT_EXECUTION_ERROR  = 4;
    const int RESULT_TIMEOUT          = 5;
    const int RESULT_CANCELLED        = 6;
    const int RESULT_NOT_FOUND        = 7;

    const int SUBMIT_ACCEPTED               = 0;
    const int SUBMIT_ARGUMENT_INVALID       = 1;
    const int SUBMIT_TAINT_DENIED           = 2;
    const int SUBMIT_POLICY_DENIED          = 3;
    const int SUBMIT_CONFIRMATION_REQUIRED  = 4;
    const int SUBMIT_UNKNOWN_ACTION         = 5;

    const int DEC_GRANT                = 0;
    const int DEC_DENY                 = 1;
    const int DEC_REQUIRE_CONFIRMATION = 2;
    const int DEC_REQUIRE_MITIGATION   = 3;
    const int POLICY_CHECK             = 0;
    const int USER_DENY                = 2;
    const int CAT_POLICY_DECISION      = 4;
'''),
    'IAgentCallGateService': ('call/IAgentCallGateService.aidl', '''
    const int RING_NATIVE  = 0;
    const int RECORD_ONLY  = 1;
'''),
    'IAgentContextService': ('context/IAgentContextService.aidl', '''
    const int TOP_UI_SUMMARY = 1;
'''),
    'IAuditService': ('audit/IAuditService.aidl', '''
    const int STATUS_OK           = 0;
    const int STATUS_DENIED       = 1;
    const int STATUS_INVALID_EVENT = 2;
    const int STATUS_INTERNAL_ERROR = -1;

    const int CAT_PERCEPTION_READ = 1;
    const int CAT_ACTION_EXECUTION = 2;
    const int CAT_SYSTEM          = 7;
    const int ADMIN_BRAIN         = 1;
'''),
    'IAgentConfirmationHost': ('ui/IAgentConfirmationHost.aidl', '''
    const int RESULT_OK             = 0;
    const int RESULT_INVALID        = 1;
    const int RESULT_DUPLICATE      = 2;
    const int STATE_EXPIRED         = 0;

    const int USER_ALLOW_ONCE       = 0;
    const int USER_ALLOW_TASK       = 1;
    const int USER_DENY             = 2;
    const int AGENT_TERMINATED      = 3;

    const int DISMISS_CANCELLED         = 0;
    const int DISMISS_TIMEOUT           = 1;
    const int DISMISS_AGENT_TERMINATED  = 2;
'''),
    'IMotionHubService': ('motion/IMotionHubService.aidl', '''
    const int T1_FOREGROUND       = 1;
    const int T2_DEEP_SLEEP       = 2;
    const int T3_SENSITIVE_STOP   = 3;
'''),
    'IAgentAssetLibraryService': ('assets/IAgentAssetLibraryService.aidl', '''
    const int ASSET_STATUS_ENABLED  = 0;
    const int ASSET_STATUS_DISABLED = 1;
'''),
}

for iface, (relpath, block) in consts.items():
    path = os.path.join(AIDL_BASE, relpath)
    with open(path) as f:
        c = f.read()
    anchor = f'interface {iface} {{'
    if anchor not in c:
        print(f"ERROR: {iface} anchor not found")
        continue
    # insert constants after "interface X {"
    # find the line after the anchor
    lines = c.split('\n')
    for i, line in enumerate(lines):
        if line.strip() == anchor:
            # insert block after this line
            indent_block = ''
            for bl in block.strip('\n').split('\n'):
                indent_block += '    ' + bl + '\n'
            lines = lines[:i+1] + [indent_block] + lines[i+1:]
            break
    else:
        print(f"ERROR: {iface} anchor not found (line match)")
        continue
    with open(path, 'w') as f:
        f.write('\n'.join(lines))
    print(f"ADDED constants to {iface}")
print("DONE")

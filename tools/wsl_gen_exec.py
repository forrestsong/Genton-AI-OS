#!/usr/bin/env python3
import os

BASE = '/root/aosp/frameworks/base/core/java/com/ainative'

path = os.path.join(BASE, 'execution', 'IActionExecutionService.aidl')
content = '''package com.ainative.execution;

import com.ainative.execution.ActionRequest;
import com.ainative.execution.IActionCallback;
import com.ainative.execution.ActionExecutionStatus;
import com.ainative.execution.ActionInfo;

interface IActionExecutionService {
    int submitAction(in ActionRequest request, in IActionCallback callback, out String[] outRequestId);

    int cancelAction(in String actionRequestId);

    ActionExecutionStatus getActionStatus(in String actionRequestId);

    ActionInfo describeAction(in String actionId);

    String[] listAvailableActions();

    int reportUserConfirmation(in String confirmId, in int userDecision);
}
'''
with open(path, 'w') as f:
    f.write(content)
print("WROTE execution/IActionExecutionService.aidl")

# verify semantics IEntityQueryService has getStats (needed by EntityQueryService)
spath = os.path.join(BASE, 'semantics', 'IEntityQueryService.aidl')
with open(spath) as f:
    sc = f.read()
print("semantics getStats present:", 'getStats' in sc)
print("semantics registerChangeListener present:", 'registerChangeListener' in sc)
print("DONE")

#!/usr/bin/env python3
import os

BASE = '/root/aosp/frameworks/base/core/java/com/genton'

def write_aidl(pkg_dir, fname, content):
    path = os.path.join(BASE, pkg_dir, fname)
    with open(path, 'w') as f:
        f.write(content)
    print(f"WROTE {pkg_dir}/{fname}")

# ============ IAgentMemoryService (already done, skip) ============

# ============ IAgentAssetLibraryService (assets) ============
write_aidl('assets', 'IAgentAssetLibraryService.aidl', '''package com.genton.assets;

import com.genton.assets.AssetDescriptor;
import com.genton.assets.AssetQuery;
import com.genton.assets.ResolveRequest;
import com.genton.assets.ResolvedAsset;
import com.genton.assets.AssetManifest;
import com.genton.assets.RegressionJob;
import com.genton.assets.ExecutionReport;
import com.genton.assets.ToolSchema;
import com.genton.assets.IAgentAssetListener;
import com.genton.assets.CompatibilityReport;

interface IAgentAssetLibraryService {
    const int STATUS_OK          = 0;
    const int STATUS_DENIED      = 1;
    const int STATUS_NOT_FOUND   = 2;
    const int STATUS_DISABLED    = 3;
    const int STATUS_UNSIGNED    = 4;
    const int STATUS_INCOMPATIBLE = 5;

    const int ASSET_STATUS_ENABLED  = 0;
    const int ASSET_STATUS_DISABLED = 1;
    const int ASSET_STATUS_STAGED   = 2;

    const int KIND_DMI    = 0;
    const int KIND_MACRO  = 1;
    const int KIND_SKILL  = 2;

    AssetDescriptor[] queryAssets(in AssetQuery query);

    AssetDescriptor getAsset(in String assetId);

    int resolveForUse(in ResolveRequest request, inout ResolvedAsset resolved);

    int registerAsset(in AssetManifest manifest, in byte[] signedPayload, out String[] assetId);

    int setAssetStatus(in String assetId, in int status);

    int runRegression(in String assetId, inout RegressionJob job);

    int reportExecution(in ExecutionReport report);

    ToolSchema[] getToolDescriptors(in String agentId);

    int registerListener(in IAgentAssetListener listener);

    int unregisterListener(in IAgentAssetListener listener);

    CompatibilityReport getCompatibilityReport(in String assetId);
}
''')

# ============ IAgentCallGateService (call) ============
write_aidl('call', 'IAgentCallGateService.aidl', '''package com.genton.call;

import com.genton.call.CallDescriptor;
import com.genton.call.CallVerdict;
import com.genton.call.WhitelistEntry;
import com.genton.call.GateMode;
import com.genton.call.IAgentCallGateListener;
import com.genton.call.CallSessionHandle;

interface IAgentCallGateService {
    const int STATUS_OK            = 0;
    const int STATUS_DENIED        = 1;
    const int STATUS_NOT_ENABLED   = 2;
    const int STATUS_SESSION_BUSY  = 3;
    const int STATUS_INTERNAL_ERROR = -1;

    const int ROUTE_RING_NATIVE    = 0;
    const int ROUTE_RECORD_ONLY    = 1;
    const int ROUTE_AGENT_HANDLE   = 2;

    const int HANDLE_MODE_L3_RECORD_ONLY = 0;

    int classifyIncoming(in CallDescriptor descriptor, inout CallVerdict verdict);

    boolean isWhitelisted(in String numberE164);

    int addWhitelistEntry(in WhitelistEntry entry);

    int removeWhitelistEntry(in String numberE164);

    WhitelistEntry[] listWhitelist();

    GateMode getGateMode();

    int setGateMode(in GateMode mode);

    int startHandleSession(in CallDescriptor descriptor, in CallVerdict verdict,
            in IAgentCallGateListener listener, inout CallSessionHandle session);

    int endHandleSession(in CallSessionHandle session);

    int getSessionState(in CallSessionHandle session);
}
''')

# ============ IAgentContextService (context) ============
write_aidl('context', 'IAgentContextService.aidl', '''package com.genton.context;

import com.genton.context.ContextFrame;
import com.genton.context.ContextSubscription;
import com.genton.context.IAgentContextListener;
import com.genton.context.ContextSubscriptionHandle;
import com.genton.context.SubscriptionInfo;

interface IAgentContextService {
    const int STATUS_OK        = 0;
    const int STATUS_DENIED    = 1;
    const int STATUS_INVALID_REQ = 2;

    const int FIELD_TOP_UI_SUMMARY     = 1;
    const int FIELD_FOREGROUND_APP     = 2;
    const int FIELD_LOCATION           = 4;
    const int FIELD_MEDIA              = 8;
    const int FIELD_SENSORS            = 16;
    const int FIELD_NOTIFICATIONS_DIGEST = 32;
    const int FIELD_AGENT_TASKS        = 64;
    const int FIELD_ACTIVE_INPUT       = 128;

    int getCurrentFrame(in int fields, inout ContextFrame frame);

    int subscribe(in ContextSubscription subscription, in IAgentContextListener listener,
            inout ContextSubscriptionHandle handle);

    int unsubscribe(in ContextSubscriptionHandle handle);

    ContextFrame[] getHistory(in int fields, in long sinceRealtimeNanos);

    SubscriptionInfo[] listSubscriptions();
}
''')

# ============ IAgentInferenceRouter (inference) ============
write_aidl('inference', 'IAgentInferenceRouter.aidl', '''package com.genton.inference;

import com.genton.inference.InferenceCapabilities;
import com.genton.inference.InferenceRequest;
import com.genton.inference.IInferenceCallback;
import com.genton.inference.InferenceStats;
import com.genton.inference.EgressPlan;
import com.genton.inference.AttestationReport;

interface IAgentInferenceRouter {
    InferenceCapabilities getCapabilities();

    int infer(in InferenceRequest request, in IInferenceCallback callback, out String[] out_requestId);

    int cancel(in String requestId);

    InferenceStats getStats();

    int preflightEgress(in String requestId, out EgressPlan[] out_plan);

    AttestationReport attestDevice();
}
''')

# ============ IAgentMacroRecorderService (macro) ============
write_aidl('macro', 'IAgentMacroRecorderService.aidl', '''package com.genton.macro;

import com.genton.macro.MacroRecordSession;
import com.genton.macro.MacroScript;
import com.genton.macro.IMacroRecordListener;

interface IAgentMacroRecorderService {
    int startRecording(in String intentAction, out String[] sessionId);

    int stopRecording(in String sessionId);

    MacroRecordSession getSession(in String sessionId);

    int generateMacro(in String sessionId, in String macroName, in String macroDescription, out String[] macroId);

    MacroScript getMacro(in String macroId);

    MacroScript[] queryMacros(in String intentAction);

    int replayMacro(in String macroId, in boolean dryRun);

    int deleteMacro(in String macroId);

    void subscribeRecordEvents(in IMacroRecordListener listener);

    void unsubscribeRecordEvents(in IMacroRecordListener listener);

    String exportMacro(in String macroId);

    int importMacro(in String macroJson, out String[] macroId);
}
''')

# ============ IMacroValidationService (macro) ============
write_aidl('macro', 'IMacroValidationService.aidl', '''package com.genton.macro;

import com.genton.macro.MacroTestCase;
import com.genton.macro.MacroTestResult;
import com.genton.macro.IMacroTestListener;

interface IMacroValidationService {
    int registerTestCase(in MacroTestCase testCase, out String[] testCaseId);

    MacroTestCase getTestCase(in String testCaseId);

    MacroTestCase[] queryTestCases(in String macroId);

    MacroTestResult runTestCase(in String testCaseId);

    MacroTestResult[] runAllTestsForMacro(in String macroId);

    String runCiPipeline();

    int deleteTestCase(in String testCaseId);

    void subscribeTestEvents(in IMacroTestListener listener);

    void unsubscribeTestEvents(in IMacroTestListener listener);

    String getTestStats(in String macroId);

    String exportTestReport(in String format);
}
''')

# ============ IAgentScreenService (perception) ============
# (3 methods - likely matches existing, but ensure constants not needed)
write_aidl('perception', 'IAgentScreenService.aidl', '''package com.genton.perception;

import com.genton.perception.ScreenFrame;
import com.genton.perception.CaptureRequest;
import com.genton.perception.ScreenStreamConfig;
import com.genton.perception.ScreenStreamHandle;
import com.genton.perception.IAgentFrameListener;

interface IAgentScreenService {
    ScreenFrame captureScreen(in CaptureRequest request);

    ScreenStreamHandle startScreenStream(in ScreenStreamConfig config, in IAgentFrameListener listener);

    void stopScreenStream(in ScreenStreamHandle handle);
}
''')

# ============ IAgentUiTreeService (perception) ============
write_aidl('perception', 'IAgentUiTreeService.aidl', '''package com.genton.perception;

import com.genton.perception.UiSnapshot;
import com.genton.perception.UiTreeRequest;
import com.genton.perception.IAgentUiTreeListener;
import com.genton.perception.UiNode;

interface IAgentUiTreeService {
    UiSnapshot captureUiTree(in UiTreeRequest request);

    void subscribeUiChanges(in IAgentUiTreeListener listener);

    void unsubscribeUiChanges(in IAgentUiTreeListener listener);

    UiNode getNodeById(in String nodeId);

    long getCurrentSequence();
}
''')

# ============ IAuditService (audit) ============
write_aidl('audit', 'IAuditService.aidl', '''package com.genton.audit;

import com.genton.audit.AuditEvent;
import com.genton.audit.AuditQuery;
import com.genton.audit.ChainStatus;
import com.genton.audit.IAuditListener;

interface IAuditService {
    int record(in AuditEvent event, out String[] outEventId);

    AuditEvent[] query(in AuditQuery query);

    ChainStatus verifyChain(in long fromSeq, in long toSeq);

    ChainStatus getChainAnchor();

    int registerActivityListener(in IAuditListener listener);

    int unregisterActivityListener(in IAuditListener listener);
}
''')

# ============ IAgentConfirmationHost (ui) ============
write_aidl('ui', 'IAgentConfirmationHost.aidl', '''package com.genton.ui;

import com.genton.ui.ConfirmationUiRequest;

interface IAgentConfirmationHost {
    int showConfirmation(in ConfirmationUiRequest request);

    void dismissConfirmation(in String confirmId, in int reason);
}
''')

# ============ IMotionHubService (motion) ============
write_aidl('motion', 'IMotionHubService.aidl', '''package com.genton.motion;

import com.genton.motion.IMotionGestureListener;
import com.genton.motion.MotionTierState;

interface IMotionHubService {
    long registerGestureListener(in IMotionGestureListener listener, in int gestureMask);

    void unregisterGestureListener(in long handleId);

    MotionTierState getCurrentTierState();

    int setGestureEnabled(in int gestureType, in boolean enabled);

    boolean isGestureEnabled(in int gestureType);

    void enterSensitiveMode(in String reason);

    void exitSensitiveMode(in String reason);

    int getDeviceCapabilities();

    String getGestureStatistics();
}
''')

# ============ IPolicyEngine (policy) ============
write_aidl('policy', 'IPolicyEngine.aidl', '''package com.genton.policy;

import com.genton.policy.IntentEvaluationRequest;
import com.genton.policy.PolicyDecision;
import com.genton.policy.CapabilityRequest;
import com.genton.policy.CapabilityToken;
import com.genton.policy.PolicyRuleView;
import com.genton.policy.IPolicyListener;
import com.genton.policy.PolicyStats;

interface IPolicyEngine {
    int evaluateIntent(in IntentEvaluationRequest request, inout PolicyDecision decision);

    int requestCapability(in CapabilityRequest request, inout PolicyDecision decision);

    int reportUserDecision(in String confirmId, in int userDecision, in CapabilityToken token);

    boolean validateToken(in CapabilityToken token, in String scopeIntentId, in String capability);

    int revokeTokens(in String agentId, in String intentId);

    PolicyRuleView[] listApplicableRules(in String agentId);

    int reloadPolicySet(in String signedPolicyBundle, out String[] bundleVersion);

    PolicyRuleView[] listActiveRules(in String domain);

    int registerListener(in IPolicyListener listener);

    int unregisterListener(in IPolicyListener listener);

    PolicyStats getStats();
}
''')

# ============ IAgentActionService (action) ============
write_aidl('action', 'IAgentActionService.aidl', '''package com.genton.action;

import com.genton.action.ActionRequest;
import com.genton.action.IAgentActionCallback;
import com.genton.action.ActionExecutionStatus;
import com.genton.action.ActionInfo;

interface IAgentActionService {
    int submitAction(in ActionRequest request, in IAgentActionCallback callback, out String[] actionRequestId);

    int cancelAction(in String actionRequestId);

    ActionExecutionStatus getActionStatus(in String actionRequestId);

    ActionInfo describeAction(in String actionName);

    ActionInfo[] listActions();
}
''')

print("DONE - all AIDL interfaces written")

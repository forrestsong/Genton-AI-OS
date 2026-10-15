package com.android.server.genton;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import com.genton.action.ActionExecutionStatus;
import com.genton.action.ActionInfo;
import com.genton.action.ActionRequest;
import com.genton.action.ActionResult;
import com.genton.action.IAgentActionCallback;
import com.genton.action.IAgentActionService;
import com.genton.policy.CapabilityRequest;
import com.genton.policy.CapabilityToken;
import com.genton.policy.PolicyDecision;
import com.genton.policy.TaintMapEntry;
import com.genton.ui.ConfirmationUiRequest;
import com.genton.ui.IAgentConfirmationHost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 动作执行中介服务端（A3.2 / D2 草案 §3）
 *
 * 本服务是副作用动作的<b>唯一入口</b>（对应 sepolicy service_contexts
 * com.genton.action.IAgentActionService）。每次动作强制走：
 *   注册表校验 → 参数 schema 校验 → 策略引擎 requestCapability
 *   →（DEC_REQUIRE_CONFIRMATION 时经确认宿主展示并等用户直回）
 *   → 确定性执行器（ActionExecutorDispatcher.dispatch）
 *   → 审计落链 → 8 态状态机 + 终态回调解绑。
 *
 * 状态机（对应 IAgentActionService.STATE_*）：
 *   QUEUED(1) → POLICY_CHECK(2) → CONFIRMATION(3) / EXECUTING(4) → 终态
 *   终态 = SUCCEEDED(5) / FAILED(6) / ABORTED(7) / EXPIRED(8)
 *
 * 策略裁决映射：
 *   - DEC_GRANT                → 持令牌进 EXECUTING（异步执行器分发）
 *   - DEC_REQUIRE_CONFIRMATION → 落 CONFIRMATION，生成确认 UI，等待用户直回
 *   - DEC_DENY                 → 终态 FAILED(RESULT_POLICY_DENIED)，fail-secure
 *   - DEC_REQUIRE_MITIGATION   → 终态 FAILED(RESULT_TAINT_DENIED)，fail-secure
 *     （完整 mitigation 编排属 A3.4，本层只拒绝不执行）
 *
 * 回调契约：见 IAgentActionCallback.onStatusChanged（oneway）。
 * 终态回调后自动解绑（unlinkToDeath + 清除回调），不再有后续回调。
 *
 * 确认链路：确认宿主（ConfirmationHostService）在用户决策/超时后调用
 * IPolicyEngine.reportUserDecision 取补签令牌，并经
 * ConfirmationHostService.ConfirmDecisionListener 直回本服务继续状态机
 * （A11.2：确认结果直回中介，不经大脑）。
 *
 * 动作注册表：优先装载 A3.1 动作注册表 JSON（构建期产物拷贝至设备
 * /vendor/genton/config/action_registry.json），找不到则注册内置动作兜底。
 *
 * 说明（骨架期）：异步执行采用进程内单线程工作队列 + 独立定时线程；
 * 并发/低延迟由 A3.3 确定性执行器承接后演进。
 */
public class AgentActionService extends IAgentActionService.Stub
        implements ConfirmationHostService.ConfirmDecisionListener {
    private static final String TAG = "AgentActionService";

    // ==================== 常量 ====================

    /** A3.1 动作注册表 JSON 候选路径（设备侧装配） */
    private static final String[] REGISTRY_JSON_PATHS = {
        "/vendor/genton/config/action_registry.json",
        "/system/etc/genton/action_registry.json",
    };

    /** 默认执行超时（毫秒）：超时 → FAILED(RESULT_TIMEOUT) */
    private static final long DEFAULT_EXECUTION_TIMEOUT_MS = 30_000;

    /** 默认确认等待超时（毫秒）：超时 → EXPIRED（E1.1 超时不得执行） */
    private static final long DEFAULT_CONFIRM_TIMEOUT_MS = 60_000;

    /** 终态保留时长（毫秒）：供 getActionStatus 查询后自动清理 */
    private static final long TERMINAL_RETENTION_MS = 5 * 60_000L;

    /** taint 打标默认信任档：模型标 tainted 的键一律视为非 USER 来源（APP 内容） */
    private static final int TAINT_TRUST_APP_CONTENT = PolicyEngineService.TRUST_APP_CONTENT;

    // ==================== 状态管理 ====================

    private final Context mContext;
    private final PolicyEngineService mPolicyEngine;
    private final AgentAuditService mAuditService;
    private final AuditInstrumentation mAudit;
    private final ActionExecutorDispatcher mDispatcher;
    private final ConfirmationHostService mConfirmationHost;

    /** 动作注册表：actionId(=注册表 actionName) → ActionInfo */
    private final Map<String, ActionInfo> mRegistry = new ConcurrentHashMap<>();

    /** 请求上下文：actionRequestId → RequestContext */
    private final Map<String, RequestContext> mRequests = new ConcurrentHashMap<>();

    /** 确认待决路由：confirmId → actionRequestId */
    private final Map<String, String> mConfirmToRequest = new ConcurrentHashMap<>();

    /** 异步执行工作线程（v1：单线程；A3.3 起由确定性执行器承载） */
    private final ExecutorService mWorkers = Executors.newSingleThreadExecutor();

    /** 定时器（执行超时 / 确认超时 / 终态清理） */
    private final ScheduledExecutorService mTimer =
        Executors.newSingleThreadScheduledExecutor();

    public AgentActionService(Context context, PolicyEngineService policyEngine,
                              ConfirmationHostService confirmationHost,
                              AgentAuditService auditService) {
        mContext = context;
        mPolicyEngine = policyEngine;
        mConfirmationHost = confirmationHost;
        mAuditService = auditService;
        mAudit = new AuditInstrumentation(auditService);
        mDispatcher = new ActionExecutorDispatcher(context);

        // 装配 A3.1 动作注册表（JSON 优先，内置兜底）
        loadRegistry();

        // 注册确认决策回灌路由（A11.2：确认结果直回中介）
        if (mConfirmationHost != null) {
            mConfirmationHost.setConfirmDecisionListener(this);
        }

        Slog.i(TAG, "AgentActionService (execution mediator) initialized, registry="
            + mRegistry.size());
    }

    // ==================== IAgentActionService 实现 ====================

    @Override
    public int submitAction(ActionRequest request, IAgentActionCallback callback,
                            String[] actionRequestId) {
        enforcePermission();

        // —— 1. 基础校验 ——
        if (request == null || request.actionName == null || request.agentId == null) {
            Slog.w(TAG, "Invalid action request (null fields)");
            return SUBMIT_ARGUMENT_INVALID;
        }

        ActionInfo info = mRegistry.get(request.actionName);
        if (info == null || !info.enabled) {
            Slog.w(TAG, "Unknown or disabled action: " + request.actionName);
            return SUBMIT_UNKNOWN_ACTION;
        }

        String argsJson = bundleToJson(request.arguments);
        if (!validateArguments(info.parametersSchema, argsJson)) {
            Slog.w(TAG, "Argument validation failed: " + request.actionName);
            return SUBMIT_ARGUMENT_INVALID;
        }

        // —— 2. 创建请求上下文（QUEUED）——
        String requestId = UUID.randomUUID().toString();
        RequestContext ctx = new RequestContext(requestId, request, info);
        ctx.argsJson = argsJson;
        ctx.callback = callback;
        mRequests.put(requestId, ctx);
        if (actionRequestId != null && actionRequestId.length > 0) {
            actionRequestId[0] = requestId;
        }

        // 客户端销毁时自动中止请求并解绑
        if (callback != null && callback.asBinder().isBinderAlive()) {
            ctx.deathRecipient = new ClientDeathRecipient(requestId);
            try {
                callback.asBinder().linkToDeath(ctx.deathRecipient, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Callback already dead on submit: " + requestId);
            }
        }

        setState(ctx, STATE_QUEUED);
        mAudit.recordActionExecution(request.actionName, request.agentId,
            request.sourceIntentId, "submitted", "动作提交", null);

        // —— 3. 策略门（POLICY_CHECK）——
        return runPolicyGate(ctx);
    }

    @Override
    public int cancelAction(String actionRequestId) {
        enforcePermission();

        if (actionRequestId == null) {
            return RESULT_NOT_FOUND;
        }
        RequestContext ctx = mRequests.get(actionRequestId);
        if (ctx == null) {
            return RESULT_NOT_FOUND;
        }

        synchronized (ctx) {
            if (isTerminal(ctx.state)) {
                // 已终态：无可取消
                return RESULT_OK;
            }
            ctx.cancelled = true;
            cancelWatchdogLocked(ctx);
            // EXECUTING 的底层执行器可能仍在跑（真实中断归 A3.3）；
            // 此处直接终态 ABORTED，晚到的执行结果不再覆盖终态。
            ctx.state = STATE_ABORTED;
            ctx.result = buildResult(RESULT_CANCELLED, "cancelled_by_caller", null);
            ctx.updatedAtRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        }

        // 若正等确认，撤掉策略引擎挂起的确认并关闭确认 UI（防泄漏/防重放）
        revokePendingConfirmationIfAny(ctx);

        pushAndUnbind(ctx, STATE_ABORTED);
        scheduleCleanup(ctx.requestId);
        mAudit.recordActionExecution(ctx.request.actionName, ctx.request.agentId,
            ctx.request.sourceIntentId, "aborted", "调用方取消", null);
        return RESULT_OK;
    }

    @Override
    public ActionExecutionStatus getActionStatus(String actionRequestId) {
        enforcePermission();
        if (actionRequestId == null) {
            return null;
        }
        RequestContext ctx = mRequests.get(actionRequestId);
        return ctx == null ? null : buildStatus(ctx);
    }

    @Override
    public ActionInfo describeAction(String actionName) {
        enforcePermission();
        if (actionName == null) {
            return null;
        }
        ActionInfo info = mRegistry.get(actionName);
        return info == null ? null : copyActionInfo(info);
    }

    @Override
    public ActionInfo[] listActions() {
        enforcePermission();
        List<ActionInfo> result = new ArrayList<>();
        for (ActionInfo info : mRegistry.values()) {
            if (info.enabled) {
                result.add(copyActionInfo(info));
            }
        }
        return result.toArray(new ActionInfo[0]);
    }

    // ==================== 确认决策直回（ConfirmationHostService.ConfirmDecisionListener）====

    @Override
    public void onConfirmDecision(String confirmId, int userDecision, CapabilityToken token,
                                  boolean timedOut) {
        if (confirmId == null) {
            return;
        }
        String requestId = mConfirmToRequest.remove(confirmId);
        if (requestId == null) {
            Slog.w(TAG, "Confirm decision for unknown request, confirmId=" + confirmId);
            return;
        }
        RequestContext ctx = mRequests.get(requestId);
        if (ctx == null) {
            return;
        }

        synchronized (ctx) {
            if (isTerminal(ctx.state) || ctx.confirmId == null
                    || !ctx.confirmId.equals(confirmId)) {
                return; // 已终态/已被取消/过期，丢弃晚到决策
            }
            ctx.confirmId = null;
            cancelWatchdogLocked(ctx);
        }

        if (timedOut) {
            terminalize(ctx, STATE_EXPIRED, RESULT_TIMEOUT, "confirmation_timeout");
            return;
        }
        if (token == null) {
            // 用户拒绝 / 补签失败
            terminalize(ctx, STATE_FAILED, RESULT_CONFIRMATION_DENIED, "user_denied");
            return;
        }

        // 用户允许：持补签令牌进入执行
        mAudit.recordConfirmation("approved", confirmId, ctx.request.agentId,
            ctx.request.sourceIntentId, "用户批准确认，动作进入执行");
        dispatchAsync(ctx, token);
    }

    // ==================== 策略门 ====================

    /**
     * 执行策略闸门（POLICY_CHECK），同步返回 SUBMIT_*。
     * 决出 DEC_GRANT 后置入执行队列（异步），其余分支同步落终态。
     */
    private int runPolicyGate(RequestContext ctx) {
        setState(ctx, STATE_POLICY_CHECK);

        ActionRequest request = ctx.request;
        ActionInfo info = ctx.actionInfo;
        String capability = capabilityOf(info);

        if (capability == null) {
            terminalize(ctx, STATE_FAILED, RESULT_POLICY_DENIED,
                "no_required_capability_registered");
            return SUBMIT_POLICY_DENIED;
        }

        // 构造策略能力请求（scope 锚定 sourceIntentId，与令牌语义一致）
        CapabilityRequest capReq = new CapabilityRequest();
        capReq.agentId = request.agentId;
        capReq.intentId = request.sourceIntentId != null ? request.sourceIntentId : "";
        capReq.capability = capability;
        capReq.actionId = info.actionId;
        capReq.targetSummary = buildTargetSummary(request, info);
        capReq.argTaints = buildArgTaints(request.taintedArgumentKeys);
        capReq.riskEstimate = 1; // 自估仅供参考，实际以策略裁决为准

        PolicyDecision decision = new PolicyDecision();
        int status = mPolicyEngine.requestCapability(capReq, decision);

        // —— 分支：GRANT → 执行 ——
        if (status == PolicyEngineService.STATUS_OK
                && decision.decision == PolicyEngineService.DEC_GRANT) {
            ctx.token = decision.token;
            auditPolicyDecision(ctx, capability, decision);
            mAudit.recordActionExecution(request.actionName, request.agentId,
                request.sourceIntentId, "policy_grant", "策略放行", null);
            dispatchAsync(ctx, decision.token);
            return SUBMIT_ACCEPTED;
        }

        // —— 分支：需用户确认 → CONFIRMATION ——
        if (status == PolicyEngineService.STATUS_CONFIRM_PENDING
                || decision.decision == PolicyEngineService.DEC_REQUIRE_CONFIRMATION) {
            String confirmId = decision.confirmId;
            ctx.confirmId = confirmId;
            mConfirmToRequest.put(confirmId, ctx.requestId);
            setState(ctx, STATE_CONFIRMATION);
            scheduleConfirmWatchdog(ctx, confirmId);

            mAudit.recordActionExecution(request.actionName, request.agentId,
                request.sourceIntentId, "confirmation_required", "等待用户确认", null);
            requestConfirmationUi(ctx, decision, capability);
            return SUBMIT_CONFIRMATION_REQUIRED;
        }

        // —— 分支：taint 防线拒绝 → 终态 FAILED（fail-secure，mitigation 编排属 A3.4）——
        if (decision.decision == PolicyEngineService.DEC_REQUIRE_MITIGATION) {
            String reason = join(decision.requiredMitigations);
            auditPolicyDecision(ctx, capability, decision);
            mAudit.recordActionExecution(request.actionName, request.agentId,
                request.sourceIntentId, "taint_denied", reason, null);
            terminalize(ctx, STATE_FAILED, RESULT_TAINT_DENIED,
                reason != null ? reason : "taint_violation");
            return SUBMIT_TAINT_DENIED;
        }

        // —— 分支：DENY / 未知能力 / 其余 → 终态 FAILED（fail-secure）——
        String reason = join(decision.reasons);
        auditPolicyDecision(ctx, capability, decision);
        mAudit.recordActionExecution(request.actionName, request.agentId,
            request.sourceIntentId, "policy_denied", reason, null);
        terminalize(ctx, STATE_FAILED, RESULT_POLICY_DENIED,
            reason != null ? reason : "policy_denied");
        return SUBMIT_POLICY_DENIED;
    }

    /**
     * 请求确认宿主展示确认 UI（无宿主时仅推进状态机，靠确认看门狗兜底过期）
     */
    private void requestConfirmationUi(RequestContext ctx, PolicyDecision decision,
                                       String capability) {
        if (mConfirmationHost == null) {
            Slog.w(TAG, "No confirmation host, request waits in CONFIRMATION: "
                + ctx.requestId);
            return;
        }
        try {
            ConfirmationUiRequest uiReq = new ConfirmationUiRequest();
            uiReq.confirmId = ctx.confirmId;
            uiReq.capability = capability;
            uiReq.targetSummaryMasked = ctx.request.reason != null
                ? ctx.request.reason : ctx.actionInfo.name;
            uiReq.sourceIntentSummary = ctx.request.reason != null
                ? ctx.request.reason : ctx.request.sourceIntentId;
            uiReq.riskLabel = "高风险（策略要求用户确认）";
            uiReq.ruleVersion = decision.ruleVersion;
            uiReq.exclusive = true;
            mConfirmationHost.showConfirmation(uiReq);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to show confirmation UI", e);
        }
    }

    // ==================== 异步执行 ====================

    private void dispatchAsync(final RequestContext ctx, final CapabilityToken token) {
        ctx.token = token;
        setState(ctx, STATE_EXECUTING);
        scheduleExecutionWatchdog(ctx);
        mAudit.recordActionExecution(ctx.request.actionName, ctx.request.agentId,
            ctx.request.sourceIntentId, "executing", "执行器分发中", null);

        mWorkers.execute(new Runnable() {
            @Override
            public void run() {
                String outJson = null;
                try {
                    outJson = mDispatcher.dispatch(
                        ctx.actionInfo.executorType,
                        ctx.actionInfo.actionId,
                        ctx.argsJson,
                        token);
                } catch (IllegalArgumentException e) {
                    finishExecution(ctx, RESULT_EXECUTION_ERROR,
                        "unknown_executor_or_action: " + ctx.actionInfo.executorType, null);
                    return;
                } catch (Exception e) {
                    finishExecution(ctx, RESULT_EXECUTION_ERROR,
                        "executor_error: " + e.getMessage(), null);
                    return;
                }

                Bundle data = new Bundle();
                if (outJson != null) {
                    data.putString("resultJson", outJson);
                }
                finishExecution(ctx, RESULT_OK, null, data);
            }
        });
    }

    /**
     * 执行结束落终态（成功/失败）；若期间被取消，保留 ABORTED 不被覆盖。
     */
    private void finishExecution(RequestContext ctx, int resultCode, String error,
                                 Bundle data) {
        synchronized (ctx) {
            if (isTerminal(ctx.state)) {
                return; // 已被取消/超时终态，不覆盖
            }
            ctx.state = STATE_SUCCEEDED;
            if (resultCode != RESULT_OK) {
                ctx.state = STATE_FAILED;
            }
            ctx.result = buildResult(resultCode, error, data);
            ctx.updatedAtRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            cancelWatchdogLocked(ctx);
        }
        int finalState = resultCode == RESULT_OK ? STATE_SUCCEEDED : STATE_FAILED;
        pushAndUnbind(ctx, finalState);
        scheduleCleanup(ctx.requestId);
        String phase = resultCode == RESULT_OK ? "succeeded" : "failed";
        mAudit.recordActionExecution(ctx.request.actionName, ctx.request.agentId,
            ctx.request.sourceIntentId, phase,
            resultCode == RESULT_OK ? "动作执行成功" : error, null);
    }

    // ==================== 状态机辅助 ====================

    /** 落终态（EXPIRED/FAILED/ABORTED 等）：幂等，仅首次生效 */
    private void terminalize(RequestContext ctx, int state, int resultCode, String error) {
        synchronized (ctx) {
            if (isTerminal(ctx.state)) {
                return;
            }
            ctx.state = state;
            ctx.result = buildResult(resultCode, error, null);
            ctx.updatedAtRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            cancelWatchdogLocked(ctx);
        }
        pushAndUnbind(ctx, state);
        scheduleCleanup(ctx.requestId);
    }

    /** 非终态状态推进 + 回调 */
    private void setState(RequestContext ctx, int state) {
        synchronized (ctx) {
            if (isTerminal(ctx.state)) {
                return;
            }
            ctx.state = state;
            ctx.updatedAtRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        }
        pushState(ctx);
    }

    /** 推送状态并（终态时）解绑回调 */
    private void pushState(RequestContext ctx) {
        ActionExecutionStatus status = buildStatus(ctx);
        IAgentActionCallback cb = ctx.callback;
        if (cb == null) {
            return;
        }
        try {
            cb.onStatusChanged(ctx.requestId, status);
        } catch (RemoteException e) {
            Slog.w(TAG, "Callback died, detaching request: " + ctx.requestId);
            detachCallback(ctx);
            return;
        }
        if (isTerminal(status.state)) {
            detachCallback(ctx);
        }
    }

    private void pushAndUnbind(RequestContext ctx, int state) {
        // 终态推送（保持与 pushState 一致：终态后自动解绑）
        pushState(ctx);
    }

    /** 解绑回调（终态后服务自动解绑；客户端死亡同样解绑） */
    private void detachCallback(RequestContext ctx) {
        IAgentActionCallback cb = ctx.callback;
        ctx.callback = null;
        if (cb != null && ctx.deathRecipient != null) {
            try {
                cb.asBinder().unlinkToDeath(ctx.deathRecipient, 0);
            } catch (Exception e) {
                // ignore
            }
            ctx.deathRecipient = null;
        }
    }

    private void scheduleCleanup(final String requestId) {
        mTimer.schedule(new Runnable() {
            @Override
            public void run() {
                RequestContext ctx = mRequests.remove(requestId);
                if (ctx != null) {
                    if (ctx.confirmId != null) {
                        mConfirmToRequest.remove(ctx.confirmId);
                    }
                }
            }
        }, TERMINAL_RETENTION_MS, TimeUnit.MILLISECONDS);
    }

    private void scheduleExecutionWatchdog(final RequestContext ctx) {
        synchronized (ctx) {
            ctx.watchdog = mTimer.schedule(new Runnable() {
                @Override
                public void run() {
                    boolean shouldTimeout;
                    synchronized (ctx) {
                        shouldTimeout = ctx.state == STATE_EXECUTING;
                    }
                    if (shouldTimeout) {
                        terminalize(ctx, STATE_FAILED, RESULT_TIMEOUT, "execution_timeout");
                        mAudit.recordActionExecution(ctx.request.actionName,
                            ctx.request.agentId, ctx.request.sourceIntentId,
                            "failed", "execution_timeout", null);
                    }
                }
            }, DEFAULT_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleConfirmWatchdog(final RequestContext ctx, final String confirmId) {
        synchronized (ctx) {
            ctx.watchdog = mTimer.schedule(new Runnable() {
                @Override
                public void run() {
                    boolean shouldExpire;
                    synchronized (ctx) {
                        shouldExpire = ctx.state == STATE_CONFIRMATION
                            && confirmId.equals(ctx.confirmId);
                    }
                    if (!shouldExpire) {
                        return;
                    }
                    // E1.1：确认超时不得执行 → EXPIRED（先撤策略挂起，防重放）
                    revokePendingConfirmationIfAny(ctx);
                    terminalize(ctx, STATE_EXPIRED, RESULT_TIMEOUT, "confirmation_timeout");
                    mAudit.recordActionExecution(ctx.request.actionName,
                        ctx.request.agentId, ctx.request.sourceIntentId,
                        "expired", "confirmation_timeout", null);
                }
            }, DEFAULT_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelWatchdogLocked(RequestContext ctx) {
        if (ctx.watchdog != null) {
            ctx.watchdog.cancel(false);
            ctx.watchdog = null;
        }
    }

    /** 若请求正挂在确认上：撤策略引擎挂起项、关确认 UI、清中介路由（cancel/超时/客户端死亡时调用） */
    private void revokePendingConfirmationIfAny(RequestContext ctx) {
        String confirmId;
        synchronized (ctx) {
            confirmId = ctx.confirmId;
            ctx.confirmId = null;
        }
        if (confirmId == null) {
            return;
        }
        mConfirmToRequest.remove(confirmId);
        // 以 USER_DENY 撤掉策略引擎挂起的确认（一次性取走，防止后续重放/泄漏）
        try {
            CapabilityToken dummy = new CapabilityToken();
            mPolicyEngine.reportUserDecision(confirmId, PolicyEngineService.USER_DENY, dummy);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to revoke pending confirmation: " + confirmId);
        }
        // 同步关闭确认宿主侧 UI/队列项，避免僵尸确认面板
        if (mConfirmationHost != null) {
            try {
                mConfirmationHost.dismissConfirmation(
                    confirmId, IAgentConfirmationHost.DISMISS_CANCELLED);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to dismiss confirmation UI: " + confirmId);
            }
        }
    }

    // ==================== 客户端死亡 ====================

    private final class ClientDeathRecipient implements IBinder.DeathRecipient {
        private final String mRequestId;

        ClientDeathRecipient(String requestId) {
            mRequestId = requestId;
        }

        @Override
        public void binderDied() {
            RequestContext ctx = mRequests.get(mRequestId);
            if (ctx == null) {
                return;
            }
            Slog.w(TAG, "Client died, aborting request: " + mRequestId);
            revokePendingConfirmationIfAny(ctx);
            terminalize(ctx, STATE_ABORTED, RESULT_CANCELLED, "client_died");
            mAudit.recordActionExecution(ctx.request.actionName, ctx.request.agentId,
                ctx.request.sourceIntentId, "aborted", "客户端进程终止", null);
        }
    }

    // ==================== 请求上下文 ====================

    private static final class RequestContext {
        final String requestId;
        final ActionRequest request;
        final ActionInfo actionInfo;
        final long submittedAtRealtimeNanos;

        volatile String argsJson;
        volatile IAgentActionCallback callback;
        IBinder.DeathRecipient deathRecipient;
        volatile int state;
        volatile ActionResult result;
        volatile long updatedAtRealtimeNanos;
        volatile String confirmId;
        volatile CapabilityToken token;
        volatile ScheduledFuture<?> watchdog;
        volatile boolean cancelled;

        RequestContext(String requestId, ActionRequest request, ActionInfo actionInfo) {
            this.requestId = requestId;
            this.request = request;
            this.actionInfo = actionInfo;
            this.submittedAtRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        }
    }

    private ActionExecutionStatus buildStatus(RequestContext ctx) {
        ActionExecutionStatus st = new ActionExecutionStatus();
        st.actionRequestId = ctx.requestId;
        st.state = ctx.state;
        st.updatedAtRealtimeNanos = ctx.updatedAtRealtimeNanos;
        ActionResult r = ctx.result;
        if (r != null) {
            ActionResult copy = new ActionResult();
            copy.resultCode = r.resultCode;
            copy.error = r.error;
            copy.data = r.data;
            copy.auditEventId = r.auditEventId;
            copy.evidenceRef = r.evidenceRef;
            st.result = copy;
        }
        return st;
    }

    private ActionResult buildResult(int resultCode, String error, Bundle data) {
        ActionResult r = new ActionResult();
        r.resultCode = resultCode;
        r.error = error;
        r.data = data;
        // TODO(A5.2/5.3): 审计事件 id 由 AgentAuditService.recordEvent 返回后回填
        r.auditEventId = null;
        r.evidenceRef = null;
        return r;
    }

    private static boolean isTerminal(int state) {
        return state == STATE_SUCCEEDED || state == STATE_FAILED
            || state == STATE_ABORTED || state == STATE_EXPIRED;
    }

    // ==================== 审计 ====================

    /** 记录策略裁决（CAT_POLICY_DECISION），decisionLabel = grant/deny/… */
    private void auditPolicyDecision(RequestContext ctx, String capability,
                                     PolicyDecision decision) {
        mAudit.recordPolicyDecision(decisionLabel(decision.decision), capability,
            ctx.request.agentId, ctx.request.sourceIntentId, join(decision.reasons));
    }

    private static String decisionLabel(int decision) {
        switch (decision) {
            case PolicyEngineService.DEC_GRANT: return "grant";
            case PolicyEngineService.DEC_DENY: return "deny";
            case PolicyEngineService.DEC_REQUIRE_CONFIRMATION: return "requires_confirmation";
            case PolicyEngineService.DEC_REQUIRE_MITIGATION: return "requires_mitigation";
            default: return "unknown";
        }
    }

    // ==================== 参数与注册表辅助 ====================

    /** 序列化 ActionRequest.arguments(Bundle) 为 JSON（执行器以 JSON 消费） */
    private static String bundleToJson(Bundle arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        JSONObject obj = new JSONObject();
        for (String key : arguments.keySet()) {
            try {
                obj.put(key, toJsonable(arguments.get(key)));
            } catch (Exception e) {
                // 单键失败不阻断整体
            }
        }
        return obj.toString();
    }

    private static Object toJsonable(Object value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof Bundle) {
            Bundle b = (Bundle) value;
            JSONObject obj = new JSONObject();
            for (String key : b.keySet()) {
                try {
                    obj.put(key, toJsonable(b.get(key)));
                } catch (Exception e) {
                    // ignore
                }
            }
            return obj;
        }
        if (value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof Float) {
            return value;
        }
        if (value instanceof String[]) {
            JSONArray arr = new JSONArray();
            for (String s : (String[]) value) {
                arr.put(s);
            }
            return arr;
        }
        if (value instanceof int[]) {
            JSONArray arr = new JSONArray();
            for (int v : (int[]) value) {
                arr.put(v);
            }
            return arr;
        }
        if (value instanceof long[]) {
            JSONArray arr = new JSONArray();
            for (long v : (long[]) value) {
                arr.put(v);
            }
            return arr;
        }
        if (value instanceof double[]) {
            JSONArray arr = new JSONArray();
            for (double v : (double[]) value) {
                arr.put(v);
            }
            return arr;
        }
        if (value instanceof boolean[]) {
            JSONArray arr = new JSONArray();
            for (boolean v : (boolean[]) value) {
                arr.put(v);
            }
            return arr;
        }
        return String.valueOf(value);
    }

    /**
     * 参数 schema 校验（轻量）：schema 可解析且声明 required 时，
     * 校验入参 JSON 是否包含全部必填键。schema 为空/无 required 视为通过。
     */
    private static boolean validateArguments(String schemaJson, String argsJson) {
        if (schemaJson == null || schemaJson.isEmpty() || "{}".equals(schemaJson)) {
            return true;
        }
        try {
            JSONObject args = new JSONObject(argsJson);
            JSONObject schema = new JSONObject(schemaJson);
            if (!schema.has("required")) {
                return true;
            }
            JSONArray required = schema.getJSONArray("required");
            for (int i = 0; i < required.length(); i++) {
                String key = required.getString(i);
                if (!args.has(key)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // schema/参数异常 → fail-secure：拒绝执行
            return false;
        }
    }

    /** taintedArgumentKeys → TaintMapEntry[]（非 USER 来源打标，供策略 taint 检查） */
    private static TaintMapEntry[] buildArgTaints(String[] taintedArgumentKeys) {
        if (taintedArgumentKeys == null || taintedArgumentKeys.length == 0) {
            return new TaintMapEntry[0];
        }
        TaintMapEntry[] entries = new TaintMapEntry[taintedArgumentKeys.length];
        for (int i = 0; i < taintedArgumentKeys.length; i++) {
            TaintMapEntry e = new TaintMapEntry();
            e.argKey = taintedArgumentKeys[i];
            e.trust = TAINT_TRUST_APP_CONTENT;
            entries[i] = e;
        }
        return entries;
    }

    private String buildTargetSummary(ActionRequest request, ActionInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(info.name != null ? info.name : info.actionId);
        if (request.reason != null && !request.reason.isEmpty()) {
            sb.append(" · ").append(request.reason);
        }
        String s = sb.toString();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String capabilityOf(ActionInfo info) {
        if (info.requiredCapabilities != null && info.requiredCapabilities.length > 0) {
            return info.requiredCapabilities[0];
        }
        return null;
    }

    private static String join(String[] arr) {
        if (arr == null || arr.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static ActionInfo copyActionInfo(ActionInfo src) {
        ActionInfo dst = new ActionInfo();
        dst.actionId = src.actionId;
        dst.name = src.name;
        dst.description = src.description;
        dst.actionType = src.actionType;
        dst.executorType = src.executorType;
        dst.executorConfig = src.executorConfig;
        dst.assetId = src.assetId;
        dst.parametersSchema = src.parametersSchema;
        dst.requiredPermissions = src.requiredPermissions;
        dst.requiredCapabilities = src.requiredCapabilities;
        dst.category = src.category;
        dst.tags = src.tags;
        dst.enabled = src.enabled;
        dst.avgLatencyMs = src.avgLatencyMs;
        dst.successRate = src.successRate;
        dst.registeredAt = src.registeredAt;
        return dst;
    }

    // ==================== 动作注册表装配（A3.1 JSON / 内置兜底） ====================

    private void loadRegistry() {
        for (String path : REGISTRY_JSON_PATHS) {
            if (loadRegistryFromJson(path)) {
                Slog.i(TAG, "Registry loaded from " + path);
                return;
            }
        }
        Slog.w(TAG, "No action_registry.json found, using builtin fallback actions");
        registerBuiltinActions();
    }

    /** 解析 A3.1 注册表 JSON；结构性失败返回 false（触发兜底） */
    private boolean loadRegistryFromJson(String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            String text = new String(buf, 0, off, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray actions = root.getJSONArray("actions");
            int count = 0;
            for (int i = 0; i < actions.length(); i++) {
                ActionInfo info = fromRegistryJson(actions.getJSONObject(i));
                if (info != null) {
                    mRegistry.put(info.actionId, info);
                    count++;
                }
            }
            return count > 0;
        } catch (IOException | org.json.JSONException e) {
            Slog.w(TAG, "Failed to parse registry JSON: " + path, e);
            mRegistry.clear();
            return false;
        }
    }

    /**
     * A3.1 注册表条目 → ActionInfo。
     * 字段映射：name→actionId（1:1 工具名）、executorId→executorType、
     * requiredCapability→requiredCapabilities[0]、inputSchema→parametersSchema。
     * （riskLevel/inputSource 由注册表 schema 承载，本结构不展开——A3.4 taint 裁决读源）
     */
    private static ActionInfo fromRegistryJson(JSONObject o) {
        try {
            String actionId = o.getString("name");
            String displayName = o.optString("displayName", actionId);
            ActionInfo info = new ActionInfo();
            info.actionId = actionId;
            info.name = displayName;
            info.description = o.optString("description", displayName);
            info.actionType = "system";
            info.executorType = o.optString("executorId", "system");
            info.executorConfig = "{}";
            info.parametersSchema = o.has("inputSchema")
                ? o.getJSONObject("inputSchema").toString() : "{}";
            String capability = o.optString("requiredCapability", null);
            info.requiredPermissions = new String[0];
            info.requiredCapabilities = capability != null
                ? new String[]{capability} : new String[0];
            info.category = info.executorType;
            String name = actionId;
            int dot = name.indexOf('.');
            info.tags = new String[]{dot > 0 ? name.substring(0, dot) : name};
            info.enabled = true;
            info.registeredAt = System.currentTimeMillis();
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** 内置动作兜底（与 ActionExecutorDispatcher 已注册执行器对齐） */
    private void registerBuiltinActions() {
        addBuiltin("ui.tap", "点击界面元素", "点击 UI 树中的节点",
            "input", "agent.input_inject",
            "{\"type\":\"object\",\"required\":[\"nodeId\"],\"properties\":{\"nodeId\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"}}}");
        addBuiltin("ui.type", "输入文本", "输入文本到输入框",
            "input", "agent.input_inject",
            "{\"type\":\"object\",\"required\":[\"nodeId\",\"text\"],\"properties\":{\"nodeId\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}");
        addBuiltin("ui.swipe", "滑动手势", "滑动手势",
            "input", "agent.input_inject",
            "{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\"},\"distance\":{\"type\":\"integer\"}}}");
        addBuiltin("ui.key", "按键操作", "按键（back/home/recents）",
            "input", "agent.input_inject",
            "{\"type\":\"object\",\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}");
        addBuiltin("app.launch", "启动应用界面", "启动应用",
            "activity", "agent.launch_activity",
            "{\"type\":\"object\",\"required\":[\"packageName\"],\"properties\":{\"packageName\":{\"type\":\"string\"},\"activityName\":{\"type\":\"string\"}}}");
        addBuiltin("app.foreground", "切换应用前台", "切换应用到前台",
            "activity", "agent.launch_activity",
            "{\"type\":\"object\",\"required\":[\"packageName\"],\"properties\":{\"packageName\":{\"type\":\"string\"}}}");
        addBuiltin("device.volume", "调节音量", "调节音量",
            "device_control", "agent.device_control",
            "{\"type\":\"object\",\"required\":[\"direction\"],\"properties\":{\"direction\":{\"type\":\"string\"},\"stream\":{\"type\":\"string\"}}}");
        addBuiltin("device.brightness", "调节亮度", "调节亮度",
            "device_control", "agent.device_control",
            "{\"type\":\"object\",\"required\":[\"level\"],\"properties\":{\"level\":{\"type\":\"integer\"}}}");
        addBuiltin("notification.clear", "清除通知", "清除通知",
            "notification", "agent.manage_notifications",
            "{\"type\":\"object\",\"required\":[\"notificationId\"],\"properties\":{\"notificationId\":{\"type\":\"string\"}}}");
        addBuiltin("notification.reply", "回复通知", "回复通知",
            "notification", "agent.manage_notifications",
            "{\"type\":\"object\",\"required\":[\"notificationId\",\"text\"],\"properties\":{\"notificationId\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}");
        addBuiltin("query.apps", "查询应用列表", "只读查询应用列表",
            "query", "agent.query_content",
            "{\"type\":\"object\",\"properties\":{\"filter\":{\"type\":\"string\"}}}");
        addBuiltin("query.intent", "解析 Intent", "只读解析 Intent 候选",
            "query", "agent.query_content",
            "{\"type\":\"object\",\"required\":[\"action\"],\"properties\":{\"action\":{\"type\":\"string\"}}}");
    }

    private void addBuiltin(String actionId, String displayName, String description,
                            String executorType, String capability, String schemaJson) {
        ActionInfo info = new ActionInfo();
        info.actionId = actionId;
        info.name = displayName;
        info.description = description;
        info.actionType = "system";
        info.executorType = executorType;
        info.executorConfig = "{}";
        info.parametersSchema = schemaJson;
        info.requiredPermissions = new String[0];
        info.requiredCapabilities = new String[]{capability};
        info.category = executorType;
        info.tags = new String[]{actionId.split("\\.")[0]};
        info.enabled = true;
        info.avgLatencyMs = 0;
        info.successRate = 1.0f;
        info.registeredAt = System.currentTimeMillis();
        mRegistry.put(actionId, info);
    }

    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
            android.Manifest.permission.MANAGE_AI_NATIVE_AGENTS,
            "AgentActionService");
    }
}

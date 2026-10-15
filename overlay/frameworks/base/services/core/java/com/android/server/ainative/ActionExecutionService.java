package com.android.server.ainative;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import com.ainative.execution.IActionExecutionService;
import com.ainative.execution.IActionCallback;
import com.ainative.execution.ActionRequest;
import com.ainative.execution.ActionInfo;
import com.ainative.execution.ActionExecutionStatus;
import com.ainative.policy.IPolicyEngine;
import com.ainative.policy.CapabilityRequest;
import com.ainative.policy.PolicyDecision;
import com.ainative.policy.CapabilityToken;
import com.ainative.audit.IAuditService;
import com.ainative.audit.AuditEvent;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.Map;

/**
 * 执行中介服务（Action Execution Service）
 * 
 * 核心职责：
 * 1. 动作执行的唯一入口（副作用动作强制走此服务）
 * 2. 8 态状态机管理（IDLE → POLICY_CHECK → PENDING_CONFIRM → EXECUTING → 终态）
 * 3. 策略引擎集成（requestCapability 令牌签发）
 * 4. 用户确认流程（高风险动作）
 * 5. 执行器调度（根据 actionId 选择执行器）
 * 6. 审计落链（全部动作写审计）
 * 
 * 状态机转换：
 * IDLE → POLICY_CHECK → [PENDING_CONFIRM] → EXECUTING → SUCCEEDED/FAILED
 *                                          ↓
 *                                      CANCELLED/REVOKED
 * 
 * 安全约束：
 * - Taint 来源校验（防提示注入）
 * - 策略强制预检（无令牌 = DENY）
 * - 高风险强制确认
 * - Fail-secure（异常 = DENY）
 * 
 * 性能要求：
 * - 提交延迟 P50 < 5ms
 * - 状态查询 P50 < 1ms
 * 
 * @see IActionExecutionService
 * @see PolicyEngineService
 */
public class ActionExecutionService extends IActionExecutionService.Stub {
    private static final String TAG = "ActionExecutionService";
    
    // 默认超时
    private static final long DEFAULT_TIMEOUT_MS = 30000;  // 30 秒
    private static final long CONFIRM_TIMEOUT_MS = 60000;  // 确认超时 60 秒
    
    private final Context mContext;
    private final PolicyEngineService mPolicyEngine;
    private final ActionRegistry mActionRegistry;
    private final ExecutorService mExecutorPool;
    private final AuditService mAuditService;
    private final ActionExecutorDispatcher mDispatcher;
    private final TaintValidator mTaintValidator;
    
    // 内部动作白名单（唯一权威来源；禁止用 actionId 前缀隐式判定内部动作）
    private final InternalActionRegistry mInternalActionRegistry;
    
    // 意图一致性检查器（动作偏离用户意图时告警/中断）
    private final IntentConsistencyChecker mConsistencyChecker;
    
    // 确认超时调度器（fail-secure：超时未决策即拒绝）
    private final ScheduledExecutorService mTimeoutScheduler;
    
    // 审计埋点（A5.3）
    private final AuditInstrumentation mAuditInstrumentation;
    
    // 活跃请求（actionRequestId → ExecutionContext）
    private final ConcurrentHashMap<String, ExecutionContext> mActiveRequests;
    
    // 挂起确认（confirmId → ExecutionContext）
    private final ConcurrentHashMap<String, ExecutionContext> mPendingConfirms;
    
    // 统计
    private final AtomicLong mTotalSubmissions = new AtomicLong(0);
    private final AtomicLong mSuccessCount = new AtomicLong(0);
    private final AtomicLong mFailureCount = new AtomicLong(0);
    private final AtomicLong mCancelledCount = new AtomicLong(0);
    
    public ActionExecutionService(Context context, PolicyEngineService policyEngine, AuditService auditService) {
        mContext = context;
        mPolicyEngine = policyEngine;
        mActionRegistry = new ActionRegistry(context);
        mTaintValidator = new TaintValidator();
        mInternalActionRegistry = new InternalActionRegistry();
        mConsistencyChecker = new IntentConsistencyChecker();
        mTimeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        mAuditService = auditService;
        mDispatcher = new ActionExecutorDispatcher(context);
        mExecutorPool = Executors.newFixedThreadPool(4);  // 并发执行池
        
        // 初始化审计埋点（A5.3）
        mAuditInstrumentation = new AuditInstrumentation(context);
        
        mActiveRequests = new ConcurrentHashMap<>();
        mPendingConfirms = new ConcurrentHashMap<>();
        
        Slog.i(TAG, "ActionExecutionService initialized");
    }
    
    /**
     * 提交动作执行请求
     * 
     * 流程：
     * 1. 输入校验
     * 2. 注册表校验（动作存在、参数 schema）
     * 3. Taint 来源校验
     * 4. 策略引擎预检（requestCapability）
     * 5. GRANT → 异步执行
     * 6. REQUIRE_CONFIRMATION → 挂起等待用户
     * 7. DENY → 拒绝
     */
    @Override
    public int submitAction(ActionRequest request, IActionCallback callback, String[] outRequestId) {
        long startNanos = System.nanoTime();
        mTotalSubmissions.incrementAndGet();
        
        try {
            // 1. 输入校验
            if (request == null || request.actionId == null || request.agentId == null) {
                Slog.w(TAG, "Invalid action request: null fields");
                return SUBMIT_ARGUMENT_INVALID;
            }
            
            // 2. 内部动作白名单与调用方信任校验（安全红线）
            //    - 内部动作身份只能来自 InternalActionRegistry 白名单，
            //      绝不按 actionId 前缀（sys./cmd.）隐式放行：前缀由调用方自填，
            //      等价于把"内部身份"交由调用方自称；
            //    - 且只接受平台签名调用方（system / 平台签名应用）。
            InternalActionRegistry.InternalActionDefinition internalDef =
                    mInternalActionRegistry.get(request.actionId);
            if (internalDef != null && !isTrustedCallerForInternalAction()) {
                Slog.w(TAG, "Rejecting internal action from untrusted caller: action="
                        + request.actionId + " uid=" + Binder.getCallingUid());
                return SUBMIT_POLICY_DENIED;
            }
            
            // 生成请求 ID
            String actionRequestId = generateRequestId();
            outRequestId[0] = actionRequestId;
            
            // 3. 注册表校验（普通动作查 ActionRegistry；白名单内部动作查 InternalActionRegistry）
            ActionInfo actionInfo = mActionRegistry.getActionInfo(request.actionId);
            if (actionInfo == null && internalDef != null) {
                actionInfo = buildInternalActionInfo(internalDef);
            }
            if (actionInfo == null) {
                Slog.w(TAG, "Unknown action: " + request.actionId);
                return SUBMIT_UNKNOWN_ACTION;
            }
            
            // 3. 参数 schema 校验
            if (!validateArguments(request.argumentsJson, actionInfo.parametersSchema)) {
                Slog.w(TAG, "Invalid arguments for action: " + request.actionId);
                return SUBMIT_ARGUMENT_INVALID;
            }
            
            // 4. Taint 来源校验
            if (!validateTaintSources(request.argTaints, actionInfo.allowedSources)) {
                Slog.w(TAG, "Taint source violation for action: " + request.actionId);
                return SUBMIT_TAINT_DENIED;
            }
            
            // 创建执行上下文
            ExecutionContext context = new ExecutionContext(
                actionRequestId,
                request,
                actionInfo,
                callback
            );
            
            mActiveRequests.put(actionRequestId, context);
            
            // 5. 状态转换：IDLE → POLICY_CHECK
            context.transitionTo(STATE_POLICY_CHECK);
            notifyStatusChange(context);
            
            // 6. 异步策略检查
            mExecutorPool.submit(() -> performPolicyCheck(context));
            
            long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
            Slog.d(TAG, String.format("Action submitted: %s (%dµs)", actionRequestId, elapsedMicros));
            
            return SUBMIT_ACCEPTED;
            
        } catch (Exception e) {
            Slog.e(TAG, "submitAction failed", e);
            return SUBMIT_INTERNAL_ERROR;
        }
    }
    
    /**
     * 取消动作执行
     */
    @Override
    public int cancelAction(String actionRequestId) {
        try {
            ExecutionContext context = mActiveRequests.get(actionRequestId);
            if (context == null) {
                return 1;  // 请求不存在
            }
            
            int currentState = context.status.state;
            
            // 只有 POLICY_CHECK/PENDING_CONFIRM/EXECUTING 可以取消
            if (currentState == STATE_POLICY_CHECK ||
                currentState == STATE_PENDING_CONFIRM ||
                currentState == STATE_EXECUTING) {
                
                context.transitionTo(STATE_CANCELLED);
                notifyStatusChange(context);
                notifyCancelled(context);
                
                mCancelledCount.incrementAndGet();
                mActiveRequests.remove(actionRequestId);
                
                Slog.i(TAG, "Action cancelled: " + actionRequestId);
                return 0;
            }
            
            return 2;  // 状态不可取消
            
        } catch (Exception e) {
            Slog.e(TAG, "cancelAction failed", e);
            return -1;
        }
    }
    
    /**
     * 查询动作执行状态
     */
    @Override
    public ActionExecutionStatus getActionStatus(String actionRequestId) {
        try {
            ExecutionContext context = mActiveRequests.get(actionRequestId);
            if (context != null) {
                return context.status;
            }
            
            // 返回空状态
            ActionExecutionStatus status = new ActionExecutionStatus();
            status.actionRequestId = actionRequestId;
            status.state = STATE_IDLE;
            return status;
            
        } catch (Exception e) {
            Slog.e(TAG, "getActionStatus failed", e);
            return null;
        }
    }
    
    /**
     * 获取动作注册信息
     */
    @Override
    public ActionInfo describeAction(String actionId) {
        return mActionRegistry.getActionInfo(actionId);
    }
    
    /**
     * 列出全部可用动作
     */
    @Override
    public String[] listAvailableActions() {
        return mActionRegistry.listActionIds();
    }
    
    /**
     * 用户确认决策回灌
     */
    @Override
    public int reportUserConfirmation(String confirmId, int userDecision) {
        try {
            // WBS 1.5：确认回灌的两道硬门禁（此前本方法只做 map.remove()，
            // 既不验调用方也不验 HMAC —— 而请求方能从状态回调里拿到 confirmId，
            // 因此 Agent 可以自批自己的高风险动作）。
            //
            // 门禁 1：调用方必须持有 SUBMIT_USER_DECISION（专供确认 UI，大脑不持有）
            if (!AgentCallerAuth.holdsPermission(mContext, AgentCallerAuth.PERM_SUBMIT_DECISION)) {
                Slog.w(TAG, "Denied reportUserConfirmation: uid=" + Binder.getCallingUid()
                        + " lacks " + AgentCallerAuth.PERM_SUBMIT_DECISION);
                return SUBMIT_POLICY_DENIED;
            }
            // 门禁 2：confirmId 必须由策略引擎 HMAC 签发、未过期、且仍处于挂起状态
            if (mPolicyEngine == null || !mPolicyEngine.isGenuinePendingConfirmation(confirmId)) {
                Slog.w(TAG, "Rejected forged or expired confirmId: " + confirmId);
                return SUBMIT_POLICY_DENIED;
            }
            
            ExecutionContext context = mPendingConfirms.remove(confirmId);
            if (context == null) {
                Slog.w(TAG, "Unknown confirmId: " + confirmId);
                return 1;
            }
            
            // 决策已到达：撤销超时任务（此前不撤销，决策后仍会触发一次超时回调）
            if (context.timeoutFuture != null) {
                context.timeoutFuture.cancel(false);
            }
            
            if (userDecision == 0 || userDecision == 1) {  // USER_ALLOW_ONCE / USER_ALLOW_TASK
                // 用户允许 → 继续执行
                context.transitionTo(STATE_EXECUTING);
                notifyStatusChange(context);
                
                mExecutorPool.submit(() -> performExecution(context));
                
                Slog.i(TAG, "User confirmed action: " + context.actionRequestId);
            } else {
                // 用户拒绝 → 取消
                context.transitionTo(STATE_CANCELLED);
                context.status.errorMessage = "User denied";
                notifyStatusChange(context);
                notifyCancelled(context);
                
                mCancelledCount.incrementAndGet();
                mActiveRequests.remove(context.actionRequestId);
                
                Slog.i(TAG, "User denied action: " + context.actionRequestId);
            }
            
            return 0;
            
        } catch (Exception e) {
            Slog.e(TAG, "reportUserConfirmation failed", e);
            return -1;
        }
    }
    
    // ========== 内部执行流程 ==========
    
    /**
     * 执行策略检查
     */
    private void performPolicyCheck(ExecutionContext context) {
        try {
            // 1. 内部动作通道：仅白名单动作可走，且必须留审计。
            //    豁免范围被收窄为"能力令牌"一项（内部优化动作不消耗 Agent 预算）；
            //    调用方信任已在 submitAction 阶段强制校验；参数 schema 与 taint 校验照常执行。
            if (isInternalAction(context.request.actionId)) {
                Slog.d(TAG, "Whitelisted internal action, capability token waived: "
                        + context.request.actionId);
                auditInternalAction(context);
                
                context.transitionTo(STATE_EXECUTING);
                notifyStatusChange(context);
                
                mExecutorPool.submit(() -> performExecution(context));
                return;
            }
            
            // 2. Taint 验证（防提示注入）
            List<TaintViolation> taintViolations = mTaintValidator.validate(context.request);
            
            if (!taintViolations.isEmpty()) {
                // Taint 冲突检测到
                String violationSummary = mTaintValidator.generateViolationSummary(taintViolations);
                Slog.w(TAG, "Taint violations detected for action " + context.request.actionId + ":\n" + violationSummary);
                
                // 检查缓解措施
                boolean requiresConfirmation = false;
                boolean shouldDeny = false;
                
                for (TaintViolation v : taintViolations) {
                    if ("deny_execution".equals(v.suggestedMitigation)) {
                        shouldDeny = true;
                        break;
                    } else if (v.suggestedMitigation.startsWith("require_user_confirmation")) {
                        requiresConfirmation = true;
                    }
                }
                
                if (shouldDeny) {
                    // 信任差距过大，直接拒绝
                    context.transitionTo(STATE_FAILED);
                    context.status.errorMessage = "Taint violation: " + taintViolations.get(0).message;
                    context.status.errorCode = SUBMIT_TAINT_DENIED;
                    notifyStatusChange(context);
                    notifyFailed(context);
                    
                    mFailureCount.incrementAndGet();
                    mActiveRequests.remove(context.actionRequestId);
                    
                    Slog.e(TAG, "Action denied due to taint violation: " + context.actionRequestId);
                    return;
                }
                
                if (requiresConfirmation) {
                    // 需要用户确认来缓解 Taint 风险
                    context.request.requireConfirmation = true;
                    Slog.i(TAG, "Forcing user confirmation due to taint risk");
                }
            }
            
            // 3. 构造能力请求
            CapabilityRequest capRequest = new CapabilityRequest();
            capRequest.agentId = context.request.agentId;
            capRequest.intentId = context.request.intentId;
            capRequest.capability = context.actionInfo.requiredCapability;
            capRequest.actionId = context.request.actionId;
            capRequest.targetSummary = context.request.targetSummary;
            capRequest.argTaints = context.request.argTaints;
            capRequest.riskEstimate = context.request.riskEstimate;
            
            // 调用策略引擎
            PolicyDecision decision = new PolicyDecision();
            int status = mPolicyEngine.requestCapability(capRequest, decision);
            
            if (status == 0 && decision.decision == 0) {  // STATUS_OK && DEC_GRANT
                // 授权 → 保存令牌，转执行状态
                context.token = decision.token;
                context.transitionTo(STATE_EXECUTING);
                notifyStatusChange(context);
                
                mExecutorPool.submit(() -> performExecution(context));
                
            } else if (decision.decision == 2) {  // DEC_REQUIRE_CONFIRMATION
                // 需确认 → 挂起
                context.status.confirmId = decision.confirmId;
                context.status.confirmMessage = "Action requires confirmation: " + context.actionInfo.description;
                context.transitionTo(STATE_PENDING_CONFIRM);
                notifyStatusChange(context);
                
                mPendingConfirms.put(decision.confirmId, context);
                
                // 启动确认超时
                scheduleConfirmTimeout(context);
                
                Slog.i(TAG, "Action requires confirmation: " + context.actionRequestId);
                
            } else {
                // 拒绝
                context.transitionTo(STATE_FAILED);
                context.status.errorMessage = "Policy denied: " + String.join(", ", decision.reasons);
                context.status.errorCode = status;
                notifyStatusChange(context);
                notifyFailed(context);
                
                mFailureCount.incrementAndGet();
                mActiveRequests.remove(context.actionRequestId);
                
                Slog.w(TAG, "Policy denied action: " + context.actionRequestId);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "performPolicyCheck failed", e);
            context.transitionTo(STATE_FAILED);
            context.status.errorMessage = "Policy check error: " + e.getMessage();
            notifyFailed(context);
            mFailureCount.incrementAndGet();
            mActiveRequests.remove(context.actionRequestId);
        }
    }
    
    /**
     * 执行动作
     */
    private void performExecution(ExecutionContext context) {
        try {
            // 调用执行器
            String executorType = context.actionInfo.executorType;
            String resultJson = mDispatcher.dispatch(
                executorType,
                context.request.actionId,
                context.request.argumentsJson,
                context.token
            );
            
            context.transitionTo(STATE_SUCCEEDED);
            context.status.success = true;
            context.status.resultJson = resultJson;
            context.status.completedAtMs = System.currentTimeMillis();
            
            // 审计埋点（A5.3）：全部动作留痕，内部动作不再豁免
            String summary = String.format("动作执行成功 action=%s executor=%s",
                context.request.actionId,
                executorType);
            
            mAuditInstrumentation.recordActionExecution(
                context.request.actionId,
                context.request.agentId,
                context.request.intentId,
                "succeeded",
                summary,
                "trace_" + context.actionRequestId
            );
            
            notifyStatusChange(context);
            notifyCompleted(context);
            
            mSuccessCount.incrementAndGet();
            mActiveRequests.remove(context.actionRequestId);
            
            Slog.i(TAG, "Action succeeded: " + context.actionRequestId);
            
        } catch (Exception e) {
            Slog.e(TAG, "performExecution failed", e);
            
            context.transitionTo(STATE_FAILED);
            context.status.errorMessage = "Execution error: " + e.getMessage();
            context.status.completedAtMs = System.currentTimeMillis();
            
            // 审计埋点（A5.3）：记录失败（内部动作同样留痕）
            String summary = String.format("动作执行失败 action=%s error=%s",
                context.request.actionId,
                e.getMessage());
            
            mAuditInstrumentation.recordActionExecution(
                context.request.actionId,
                context.request.agentId,
                context.request.intentId,
                "failed",
                summary,
                "trace_" + context.actionRequestId
            );
            
            notifyFailed(context);
            mFailureCount.incrementAndGet();
            mActiveRequests.remove(context.actionRequestId);
        }
    }
    
    /**
     * 判断是否为内部动作
     *
     * 白名单唯一来源：InternalActionRegistry。
     * 安全红线：不得按 actionId 前缀（sys./cmd.）隐式判定——前缀由调用方完全控制，
     * 等价于把"内部身份"交给调用方自称（WBS 1.1）。
     */
    private boolean isInternalAction(String actionId) {
        return mInternalActionRegistry.get(actionId) != null;
    }
    
    /**
     * 内部动作调用方信任校验：仅接受平台签名调用方。
     *
     * 这是替代"按前缀隐式信任"的身份边界：system / 本进程 / 与 system 同签名的
     * 平台应用可提交内部动作，其余（含任意三方应用）一律拒绝。
     */
    private boolean isTrustedCallerForInternalAction() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) {
            return true;
        }
        try {
            return mContext.getPackageManager().checkSignatures(uid, Process.SYSTEM_UID)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Slog.e(TAG, "checkSignatures failed for uid " + uid, e);
            return false;   // fail-secure
        }
    }
    
    /**
     * 由内部动作定义构造 ActionInfo，使内部动作与普通动作共用同一执行路径。
     */
    private ActionInfo buildInternalActionInfo(InternalActionRegistry.InternalActionDefinition def) {
        ActionInfo info = new ActionInfo();
        info.actionId = def.actionId;
        info.name = def.actionId;
        info.description = def.description;
        info.requiredCapability = null;   // 内部动作豁免能力令牌（仍强制审计）
        info.parametersSchema = def.argumentsSchema != null ? def.argumentsSchema.toString() : null;
        info.riskLevel = 0;
        info.executorType = def.executorType != null ? def.executorType : "internal";
        info.requiresUserConfirmation = false;
        info.allowedSources = new String[]{"SYSTEM"};
        return info;
    }
    
    /**
     * 内部动作审计：白名单放行也必须留痕。
     * （修正此前"内部动作跳过审计"的缺陷——那使 sys.* 动作成为无痕通道。）
     */
    private void auditInternalAction(ExecutionContext context) {
        try {
            mAuditInstrumentation.recordActionExecution(
                context.request.actionId,
                context.request.agentId,
                context.request.intentId,
                "internal_whitelisted",
                "内部动作白名单放行（豁免能力令牌）",
                "trace_" + context.actionRequestId);
        } catch (Exception e) {
            Slog.e(TAG, "auditInternalAction failed", e);
        }
    }
    
    /**
     * 确认超时（fail-secure）：超过 CONFIRM_TIMEOUT_MS 未决策即取消，并留审计。
     * 修复此前 scheduleConfirmTimeout 未定义（编译不过）的问题。
     */
    private void scheduleConfirmTimeout(ExecutionContext context) {
        final String confirmId = context.status.confirmId;
        if (confirmId == null) {
            return;
        }
        try {
            context.timeoutFuture = mTimeoutScheduler.schedule(() -> {
                ExecutionContext pending = mPendingConfirms.remove(confirmId);
                if (pending == null) {
                    return;   // 已被用户决策或已取消
                }
                Slog.w(TAG, "Confirmation timed out, fail-secure deny: " + confirmId);
                pending.transitionTo(STATE_CANCELLED);
                pending.status.errorMessage = "Confirmation timed out (fail-secure)";
                pending.status.errorCode = SUBMIT_POLICY_DENIED;
                pending.status.completedAtMs = System.currentTimeMillis();
                notifyStatusChange(pending);
                notifyCancelled(pending);
                mCancelledCount.incrementAndGet();
                mActiveRequests.remove(pending.actionRequestId);
                auditActionExecution(pending, false, "confirmation_timeout");
            }, CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "scheduleConfirmTimeout failed", e);
        }
    }
    
    // ========== 辅助方法 ==========
    
    private String generateRequestId() {
        return "action_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    private boolean validateArguments(String argumentsJson, String parametersSchema) {
        // TODO(1.2b): 完整 JSON Schema 校验（当前为结构性校验）
        if (parametersSchema == null || parametersSchema.isEmpty()) {
            return true;   // 该动作未声明 schema 约束
        }
        return argumentsJson != null && !argumentsJson.isEmpty();
    }
    
    /**
     * Taint 来源校验（防提示注入），fail-closed 实现（WBS 1.2a）。
     *
     * 规则：
     * - 未申报 taint（null/空数组）→ 放行。注意：污点目前仍由调用方申报，
     *   "平台侧重新推导"是 1.2b 的剩余缺口，不能靠本函数弥补；
     * - 申报了 taint 但动作未声明 allowedSources → 拒绝；
     * - trust 等级无法映射，或不在 allowedSources 内 → 拒绝。
     */
    private boolean validateTaintSources(com.ainative.policy.TaintMapEntry[] argTaints, String[] allowedSources) {
        if (argTaints == null || argTaints.length == 0) {
            return true;
        }
        if (allowedSources == null || allowedSources.length == 0) {
            Slog.w(TAG, "Taint declared but action allows no sources; denying");
            return false;
        }
        for (com.ainative.policy.TaintMapEntry entry : argTaints) {
            if (entry == null || entry.argKey == null) {
                return false;
            }
            String[] aliases = trustAliases(entry.trust);
            if (aliases == null) {
                Slog.w(TAG, "Unknown taint trust level " + entry.trust + " for arg " + entry.argKey);
                return false;
            }
            if (!matchesAny(aliases, allowedSources)) {
                Slog.w(TAG, "Taint source not allowed for arg " + entry.argKey
                        + " (trust=" + entry.trust + ")");
                return false;
            }
        }
        return true;
    }
    
    /** trust 等级 → 允许来源标签（0=SYSTEM / 1=USER / 2=APP / 3=WEB）；未知返回 null（→ 拒绝）。 */
    private static String[] trustAliases(int trust) {
        switch (trust) {
            case 0: return new String[]{"SYSTEM", "TRUSTED_SYSTEM"};
            case 1: return new String[]{"USER"};
            case 2: return new String[]{"APP", "APP_CONTENT", "AGENT"};
            case 3: return new String[]{"WEB", "WEB_CONTENT", "UNTRUSTED"};
            default: return null;
        }
    }
    
    private static boolean matchesAny(String[] aliases, String[] allowedSources) {
        for (String alias : aliases) {
            for (String allowed : allowedSources) {
                if (allowed != null && allowed.equalsIgnoreCase(alias)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private void notifyStatusChange(ExecutionContext context) {
        if (context.callback != null) {
            try {
                context.callback.onStatusChanged(context.status);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify status change", e);
            }
        }
    }
    
    private void notifyCompleted(ExecutionContext context) {
        if (context.callback != null) {
            try {
                context.callback.onCompleted(context.status);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify completion", e);
            }
        }
    }
    
    private void notifyFailed(ExecutionContext context) {
        if (context.callback != null) {
            try {
                context.callback.onFailed(context.status);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify failure", e);
            }
        }
    }
    
    private void notifyCancelled(ExecutionContext context) {
        if (context.callback != null) {
            try {
                context.callback.onCancelled(context.status);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify cancellation", e);
            }
        }
    }
    
    // ========== 内部类 ==========
    
    /**
     * 执行上下文
     */
    private static class ExecutionContext {
        final String actionRequestId;
        final ActionRequest request;
        final ActionInfo actionInfo;
        final IActionCallback callback;
        final ActionExecutionStatus status;
        
        CapabilityToken token;  // 策略引擎签发的令牌
        java.util.concurrent.ScheduledFuture<?> timeoutFuture;  // 超时任务
        
        ExecutionContext(String actionRequestId, ActionRequest request, 
                        ActionInfo actionInfo, IActionCallback callback) {
            this.actionRequestId = actionRequestId;
            this.request = request;
            this.actionInfo = actionInfo;
            this.callback = callback;
            
            this.status = new ActionExecutionStatus();
            this.status.actionRequestId = actionRequestId;
            this.status.actionId = request.actionId;
            this.status.state = STATE_IDLE;
            this.status.submittedAtMs = System.currentTimeMillis();
        }
        
        void transitionTo(int newState) {
            status.state = newState;
        }
    }
    
    /**
     * 动作注册表（临时实现）
     */
    private static class ActionRegistry {
        private final Map<String, ActionInfo> mActions = new ConcurrentHashMap<>();
        
        ActionRegistry(Context context) {
            // 加载动作注册表
            // TODO: 从 /vendor/ainative/config/action_registry.json 加载
            loadDefaultActions();
        }
        
        private void loadDefaultActions() {
            // 临时：硬编码几个示例动作
            ActionInfo sendMessage = new ActionInfo();
            sendMessage.actionId = "send.message";
            sendMessage.name = "发送消息";
            sendMessage.description = "发送文本消息给指定联系人";
            sendMessage.requiredCapability = "agent.send_message";
            sendMessage.parametersSchema = "{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"}}}";
            sendMessage.riskLevel = 1;  // medium
            sendMessage.executorType = "messaging";
            sendMessage.requiresUserConfirmation = false;
            sendMessage.allowedSources = new String[]{"USER", "AGENT"};
            mActions.put(sendMessage.actionId, sendMessage);
            
            ActionInfo makeCall = new ActionInfo();
            makeCall.actionId = "make.call";
            makeCall.name = "拨打电话";
            makeCall.description = "拨打电话给指定联系人";
            makeCall.requiredCapability = "agent.make_call";
            makeCall.parametersSchema = "{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"}}}";
            makeCall.riskLevel = 2;  // high
            makeCall.executorType = "telecom";
            makeCall.requiresUserConfirmation = true;
            makeCall.allowedSources = new String[]{"USER"};
            mActions.put(makeCall.actionId, makeCall);
            
            Slog.i(TAG, "Loaded " + mActions.size() + " default actions");
        }
        
        ActionInfo getActionInfo(String actionId) {
            return mActions.get(actionId);
        }
        
        String[] listActionIds() {
            return mActions.keySet().toArray(new String[0]);
        }
    }
    
    // ========== 审计集成 ==========
    
    /**
     * 审计动作执行
     */
    private void auditActionExecution(ExecutionContext context, boolean success, String errorDetail) {
        if (mAuditService == null) {
            Slog.w(TAG, "AuditService not available, skipping audit");
            return;
        }
        
        try {
            AuditEvent event = new AuditEvent();
            event.category = IAuditService.CAT_ACTION_EXECUTION;
            event.agentId = context.request.agentId;
            event.intentId = context.request.intentId;
            
            // 构建摘要（脱敏）
            if (success) {
                event.summary = String.format("执行动作成功: %s", context.actionInfo.name);
            } else {
                event.summary = String.format("执行动作失败: %s (%s)",
                    context.actionInfo.name,
                    errorDetail != null ? errorDetail : "未知错误");
            }
            
            // 负载哈希（动作 ID + 参数）
            event.payloadHash = computePayloadHash(
                context.request.actionId,
                context.request.argumentsJson
            );
            
            // 证据引用（执行轨迹）
            event.evidenceRefs = new String[] {
                "trace_" + context.actionRequestId
            };
            
            // 写入审计链
            String[] outEventId = new String[1];
            int result = mAuditService.record(event, outEventId);
            
            if (result == IAuditService.STATUS_OK) {
                Slog.d(TAG, String.format("Audited: action=%s eventId=%s",
                    context.request.actionId,
                    outEventId[0].substring(0, 8)));
            } else {
                Slog.w(TAG, "Failed to audit action: result=" + result);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to audit action", e);
        }
    }
    
    /**
     * 计算负载哈希
     */
    private String computePayloadHash(String actionId, String argumentsJson) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String input = actionId + "|" + (argumentsJson != null ? argumentsJson : "");
            byte[] hashBytes = digest.digest(input.getBytes());
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "error";
        }
    }
}


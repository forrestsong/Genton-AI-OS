package com.android.server.ainative;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;

import com.ainative.policy.CapabilityToken;
import com.ainative.ui.IAgentConfirmationHost;
import com.ainative.ui.IAgentConfirmationUi;
import com.ainative.ui.IConfirmationDecisionCallback;
import com.ainative.ui.ConfirmationUiRequest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent 确认宿主服务端（A11.2 确认宿主服务端 / A3.2 执行中介确认路由）
 * 
 * 核心职责：
 * 1. 接收确认请求（来自执行中介 AgentActionService）
 * 2. 展示全屏确认 UI（FLAG_SECURE，防截屏）
 * 3. 收集用户决策（批准/拒绝/永不）
 * 4. 回灌决策到策略引擎（直回，不经大脑），并取得补签令牌
 * 5. 把决策 + 补签令牌路由回执行中介（确认结果直回中介，不经大脑）
 * 6. 防重放检测（confirmId 去重）
 * 
 * 实现方：SystemUI（com.android.systemui 包内，本文件仅为服务端骨架）
 * 
 * 安全约束：
 * - confirmId 必须由策略引擎 HMAC 签发（本服务验证签名）
 * - 用户决策直接调用 PolicyEngine.reportUserDecision()
 * - 并发请求入队展示（exclusive=true 时全屏独占）
 * - 确认超时自动拒绝（Fail-secure）
 * 
 * 确认流程：
 * 1. AgentActionService（执行中介）调用 showConfirmation(request)
 * 2. 本服务验证 confirmId 签名
 * 3. 展示确认 UI（SystemUI 全屏面板）
 * 4. 用户决策后调用 mPolicyEngine.reportUserDecision(confirmId, decision, out token)
 * 5. PolicyEngine 验证并签发令牌；本服务经 ConfirmDecisionListener 把
 *    decision + token + 是否超时 路由回执行中介继续状态机
 * 
 * @see IAgentConfirmationHost
 * @see AgentActionService
 * @see PolicyEngineService
 */
public class ConfirmationHostService extends IAgentConfirmationHost.Stub {
    private static final String TAG = "ConfirmationHostService";
    
    // 确认超时（默认 60 秒）
    private static final long CONFIRMATION_TIMEOUT_MS = 60000;
    
    private final Context mContext;
    private final PolicyEngineService mPolicyEngine;
    
    // 审计埋点（A5.3）
    private final AuditInstrumentation mAuditInstrumentation;
    
    // 活跃确认请求（confirmId → ConfirmationContext）
    private final ConcurrentHashMap<String, ConfirmationContext> mActiveConfirmations;
    
    // 已处理的 confirmId 集合（防重放）
    private final Set<String> mProcessedConfirmIds;
    
    // 确认队列（exclusive=true 时排队）
    private final java.util.concurrent.LinkedBlockingQueue<ConfirmationUiRequest> mConfirmQueue;
    
    // 当前展示的确认 ID
    private volatile String mCurrentConfirmId;
    
    // 确认决策路由目标（执行中介注册；决策+补签令牌直回，不经大脑）
    private volatile ConfirmDecisionListener mConfirmListener;

    // [P0] SystemUI 侧可视化提供方（反向注册）
    // 平台约束：SystemUI 为应用 UID，无法用 ServiceManager.addService 注册服务，
    // 故由本服务（system_server 侧）注册服务名，SystemUI 交出实现 binder。
    private volatile IAgentConfirmationUi mUiProvider;

    // [P0] 主线程 Handler：用于确认超时（fail-secure）
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // [P0] 交回给 SystemUI 的决策回调（本进程内直调，保证决策不经大脑）
    private final IConfirmationDecisionCallback mDecisionCallback =
            new IConfirmationDecisionCallback.Stub() {
                @Override
                public void onUserDecision(String confirmId, int userDecision) {
                    Slog.i(TAG, "[P0] user decision from SystemUI: " + confirmId
                            + " decision=" + userDecision);
                    ConfirmationHostService.this.onUserDecision(confirmId, userDecision);
                }
            };
    
    public ConfirmationHostService(Context context, PolicyEngineService policyEngine,
                                   AgentAuditService auditService) {
        mContext = context;
        mPolicyEngine = policyEngine;
        mActiveConfirmations = new ConcurrentHashMap<>();
        mProcessedConfirmIds = new HashSet<>();
        mConfirmQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        
        // 初始化审计埋点（A5.3）
        mAuditInstrumentation = new AuditInstrumentation(auditService);
        
        Slog.i(TAG, "ConfirmationHostService initialized");
    }
    
    // ==================== [P0] UI 提供方反向注册 ====================

    /**
     * SystemUI 交出可视化实现（IAgentConfirmationUi）
     *
     * 平台约束：com.android.systemui 运行在应用 UID（>= AID_APP=10000），
     * servicemanager 会以 "App UIDs cannot add services." 拒绝其注册系统服务，
     * 因此改由 system_server 侧注册服务名，SystemUI 反向交出实现 binder。
     */
    @Override
    public void registerUiProvider(IBinder provider) {
        if (provider == null) {
            Slog.w(TAG, "registerUiProvider: null provider");
            return;
        }
        mUiProvider = IAgentConfirmationUi.Stub.asInterface(provider);
        Slog.i(TAG, "[P0] SystemUI confirmation UI provider registered");

        // 把决策回调交给 SystemUI：用户选择后经此回调回到本服务，
        // 再由本服务回灌策略引擎并路由回执行中介（不经大脑）。
        try {
            mUiProvider.setDecisionCallback(mDecisionCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "setDecisionCallback failed", e);
        }

        // UI 晚于确认请求就绪时，补展示当前挂起项
        if (mCurrentConfirmId == null) {
            processNextConfirmation();
        }
    }

    @Override
    public boolean isUiReady() {
        return mUiProvider != null;
    }

    /**
     * 注册确认决策路由目标（执行中介）
     */
    public void setConfirmDecisionListener(ConfirmDecisionListener listener) {
        mConfirmListener = listener;
    }
    
    /**
     * 确认决策路由监听器：用户对 confirmId 做出决策后，
     * 由确认宿主把 decision + 策略补签令牌 + 是否超时 直回中介。
     */
    public interface ConfirmDecisionListener {
        /**
         * @param confirmId 确认 ID
         * @param userDecision USER_ALLOW_ONCE/USER_ALLOW_TASK/USER_DENY
         * @param token 补签令牌（allow 时非空；deny/超时为空）
         * @param timedOut 是否由确认超时触达（true 时中介应转 STATE_EXPIRED）
         */
        void onConfirmDecision(String confirmId, int userDecision, CapabilityToken token,
                               boolean timedOut);
    }
    
    /**
     * 展示确认请求
     * 
     * @param request 确认请求数据
     * @return RESULT_OK 成功；RESULT_INVALID confirmId 无效；RESULT_DUPLICATE 重复请求
     */
    @Override
    public int showConfirmation(ConfirmationUiRequest request) {
        if (request == null || request.confirmId == null) {
            Slog.w(TAG, "Invalid confirmation request: null fields");
            return RESULT_INVALID;
        }
        
        // 1. 防重放检测
        synchronized (mProcessedConfirmIds) {
            if (mProcessedConfirmIds.contains(request.confirmId)) {
                Slog.w(TAG, "Duplicate confirmId: " + request.confirmId);
                return RESULT_DUPLICATE;
            }
            // 标记为已处理（即使后续失败，也不允许重试相同 confirmId）
            mProcessedConfirmIds.add(request.confirmId);
        }
        
        // 2. 验证 confirmId 签名（委托给 PolicyEngine）
        if (!verifyConfirmId(request)) {
            Slog.e(TAG, "Invalid confirmId signature: " + request.confirmId);
            return RESULT_INVALID;
        }
        
        // 3. 创建确认上下文
        ConfirmationContext context = new ConfirmationContext(request);
        mActiveConfirmations.put(request.confirmId, context);
        
        // 4. 审计埋点（A5.3）：确认展示
        String summary = String.format("展示用户确认 capability=%s target=%s exclusive=%b",
            request.capability != null ? request.capability : "?",
            request.targetSummaryMasked != null ? request.targetSummaryMasked : "",
            request.exclusive);
        
        // TODO(A5.3): agentId/intentId 由中介在 submit 上下文落审计时承载，此处事件
        //  仅记录确认 UI 阶段；若需同一确认关联 agent 上下文，后续把 agentId/intentId
        //  并入 ConfirmationUiRequest 结构或经中介补充记录。
        mAuditInstrumentation.recordConfirmation(
            "show",
            request.confirmId,
            null,
            null,
            summary
        );
        
        // 5. 排队或立即展示
        if (request.exclusive) {
            // 独占模式：入队，串行展示
            try {
                mConfirmQueue.put(request);
                Slog.i(TAG, "Confirmation queued: " + request.confirmId);
                
                // 如果当前无确认展示，立即处理队列
                if (mCurrentConfirmId == null) {
                    processNextConfirmation();
                }
            } catch (InterruptedException e) {
                Slog.e(TAG, "Failed to queue confirmation", e);
                return RESULT_INVALID;
            }
        } else {
            // 非独占模式：立即展示（未来扩展）
            showConfirmationUi(request);
        }
        
        // 6. 启动超时计时器
        scheduleTimeout(request.confirmId);
        
        return RESULT_OK;
    }
    
    /**
     * 服务侧取消确认
     * 
     * @param confirmId 确认 ID
     * @param reason 取消原因
     */
    @Override
    public void dismissConfirmation(String confirmId, int reason) {
        if (confirmId == null) {
            return;
        }
        
        ConfirmationContext context = mActiveConfirmations.remove(confirmId);
        if (context == null) {
            Slog.w(TAG, "Confirmation not found: " + confirmId);
            return;
        }
        
        // 取消超时计时器
        context.cancelTimeout();
        
        // 关闭 UI
        if (confirmId.equals(mCurrentConfirmId)) {
            dismissConfirmationUi(reason);
            mCurrentConfirmId = null;
            
            // 处理下一个排队的确认
            processNextConfirmation();
        }
        
        String reasonStr = dismissReasonToString(reason);
        Slog.i(TAG, "Confirmation dismissed: " + confirmId + ", reason: " + reasonStr);
    }
    
    /**
     * 用户决策回调（由 SystemUI 确认面板调用）
     * 
     * @param confirmId 确认 ID
     * @param decision 用户决策（0=批准 1=拒绝 2=永不）
     */
    public void onUserDecision(String confirmId, int decision) {
        ConfirmationContext context = mActiveConfirmations.remove(confirmId);
        if (context == null) {
            Slog.w(TAG, "Confirmation not found for decision: " + confirmId);
            return;
        }
        
        // 取消超时
        context.cancelTimeout();
        
        // 审计埋点（A5.3）：用户决策（agentId/intentId 由中介在 submit 上下文落审计，
        // 本事件仅承载确认阶段；如需同一确认关联 agent 上下文见 showConfirmation 处 TODO）
        String decisionStr = decision == 0 ? "批准" : (decision == 1 ? "拒绝" : "永不");
        String summary = String.format("用户确认决策 decision=%s", decisionStr);

        mAuditInstrumentation.recordConfirmation(
            "user_decision",
            confirmId,
            null,
            null,
            summary
        );
        
        // 回灌决策到策略引擎（直回，不经大脑），补签令牌由 out 带回并路由回中介
        reportDecisionAndNotify(confirmId, decision, false);
        
        // 关闭 UI
        if (confirmId.equals(mCurrentConfirmId)) {
            dismissConfirmationUi(DISMISS_CANCELLED);
            mCurrentConfirmId = null;
            
            // 处理下一个排队的确认
            processNextConfirmation();
        }
    }
    
    /**
     * 把决策回灌策略引擎并路由回执行中介（A11.2 确认结果直回中介）
     */
    private void reportDecisionAndNotify(String confirmId, int userDecision, boolean timedOut) {
        CapabilityToken issuedToken = new CapabilityToken();
        try {
            // 进程内直调策略引擎（AIDL out 令牌由对象字段回填）
            mPolicyEngine.reportUserDecision(confirmId, userDecision, issuedToken);
            Slog.i(TAG, "User decision reported: " + confirmId + ", decision: " + userDecision
                + ", timedOut=" + timedOut);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to report user decision", e);
            issuedToken = null;
        }
        
        ConfirmDecisionListener listener = mConfirmListener;
        if (listener != null) {
            try {
                listener.onConfirmDecision(confirmId, userDecision, issuedToken, timedOut);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to route decision to mediator", e);
            }
        }
    }
    
    /**
     * 验证 confirmId 签名（委托给 PolicyEngine）
     */
    private boolean verifyConfirmId(ConfirmationUiRequest request) {
        // TODO: 调用 PolicyEngine.verifyConfirmId(confirmId, capability, targetSummary)
        // 暂时简单校验非空
        return request.confirmId != null && !request.confirmId.isEmpty();
    }
    
    /**
     * 处理下一个排队的确认
     */
    private void processNextConfirmation() {
        if (mCurrentConfirmId != null) {
            // 当前有确认在展示，等待其完成
            return;
        }
        
        ConfirmationUiRequest nextRequest = mConfirmQueue.poll();
        if (nextRequest != null) {
            showConfirmationUi(nextRequest);
        }
    }
    
    /**
     * 展示确认 UI（实际渲染逻辑在 SystemUI 中）
     */
    private void showConfirmationUi(ConfirmationUiRequest request) {
        mCurrentConfirmId = request.confirmId;

        // [P0] 转发到 SystemUI 侧确认面板
        IAgentConfirmationUi ui = mUiProvider;
        if (ui == null) {
            // UI 未就绪：fail-secure，不放行（等待 registerUiProvider 后补展示）
            Slog.w(TAG, "[P0] confirmation UI not ready, holding request: " + request.confirmId);
            mConfirmQueue.offer(request);
            mCurrentConfirmId = null;
            return;
        }
        try {
            ui.show(request);
            Slog.i(TAG, "[P0] confirmation UI dispatched: " + request.confirmId
                    + " capability=" + request.capability + " risk=" + request.riskLabel);
        } catch (RemoteException e) {
            Slog.e(TAG, "[P0] show confirmation failed", e);
            // 通道故障同样 fail-secure
            handleTimeout(request.confirmId);
        }
    }
    
    /**
     * 关闭确认 UI
     */
    private void dismissConfirmationUi(int reason) {
        IAgentConfirmationUi ui = mUiProvider;
        String id = mCurrentConfirmId;
        if (ui != null && id != null) {
            try {
                ui.dismiss(id, reason);
            } catch (RemoteException e) {
                Slog.w(TAG, "[P0] dismiss confirmation failed", e);
            }
        }
        Slog.i(TAG, "[P0] confirmation UI dismissed: " + id
                + ", reason: " + dismissReasonToString(reason));
    }
    
    /**
     * 安排确认超时
     */
    private void scheduleTimeout(String confirmId) {
        ConfirmationContext context = mActiveConfirmations.get(confirmId);
        if (context == null) {
            return;
        }
        
        context.timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                handleTimeout(confirmId);
            }
        };
        
        // [P0] 真实超时（fail-secure：超时即拒绝）
        mHandler.postDelayed(context.timeoutRunnable, CONFIRMATION_TIMEOUT_MS);
    }
    
    /**
     * 处理确认超时
     */
    private void handleTimeout(String confirmId) {
        ConfirmationContext context = mActiveConfirmations.remove(confirmId);
        if (context == null) {
            return;
        }
        
        Slog.w(TAG, "Confirmation timeout: " + confirmId);
        
        // 超时自动拒绝（Fail-secure），并通知中介转 STATE_EXPIRED
        reportDecisionAndNotify(confirmId, PolicyEngineService.USER_DENY, true);
        
        // 关闭 UI
        if (confirmId.equals(mCurrentConfirmId)) {
            dismissConfirmationUi(DISMISS_TIMEOUT);
            mCurrentConfirmId = null;
            
            // 处理下一个排队的确认
            processNextConfirmation();
        }
    }
    
    /**
     * 取消原因转字符串
     */
    private String dismissReasonToString(int reason) {
        switch (reason) {
            case DISMISS_TIMEOUT: return "TIMEOUT";
            case DISMISS_CANCELLED: return "CANCELLED";
            case DISMISS_AGENT_TERMINATED: return "AGENT_TERMINATED";
            default: return "UNKNOWN";
        }
    }
    
    /**
     * 确认上下文（内部类）
     */
    private static class ConfirmationContext {
        final ConfirmationUiRequest request;
        final long startTimeNanos;
        Runnable timeoutRunnable;
        
        ConfirmationContext(ConfirmationUiRequest request) {
            this.request = request;
            this.startTimeNanos = System.nanoTime();
        }
        
        void cancelTimeout() {
            // TODO: mHandler.removeCallbacks(timeoutRunnable);
        }
    }
}

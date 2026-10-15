package com.android.server.ainative;

import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.ainative.agent.IAgentRuntimeService;
import com.ainative.agent.IAgentRuntimeListener;
import com.ainative.agent.AgentRegistration;
import com.ainative.agent.AgentHandle;
import com.ainative.agent.AgentRuntimeStatus;
import com.ainative.agent.CapabilityToken;
import com.ainative.agent.Goal;
import com.ainative.agent.AgentBudget;
import com.ainative.agent.AgentBudgetUsage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行时服务（ACB - Agent Control Block）
 * 
 * 核心职责：
 * 1. Agent 注册与生命周期管理（注册/终止/挂起/恢复）
 * 2. 状态机管理（6 态：SPAWNING/PLANNING/ACTING/WAITING/SUSPENDED/TERMINATED）
 * 3. 能力令牌签发与撤销（最小权限核心）
 * 4. 预算记账（token/energy/time/cost）
 * 5. Checkpoint 持久化（加密存储）
 * 6. 审计锚定（每个 ACB 关联审计链）
 * 
 * 设计原则：
 * - 所有 Agent 必须先注册才能调用系统服务
 * - 能力令牌绑定 agentId + scopeIntentId（单次意图上下文）
 * - 预算超限自动挂起
 * - Checkpoint 加密持久化，支持热/冷会话切换
 * 
 * @see IAgentRuntimeService
 * @see AgentControlBlock
 */
public class AgentRuntimeService extends IAgentRuntimeService.Stub {
    private static final String TAG = "AgentRuntimeService";
    
    // Agent 状态常量（对齐 AIDL）
    public static final int AGENT_STATE_SPAWNING = 0;
    public static final int AGENT_STATE_PLANNING = 1;
    public static final int AGENT_STATE_ACTING = 2;
    public static final int AGENT_STATE_WAITING = 3;
    public static final int AGENT_STATE_SUSPENDED = 4;
    public static final int AGENT_STATE_TERMINATED = 5;
    
    private final Context mContext;
    private final PolicyEngineService mPolicyEngine;
    
    // 审计埋点（A5.3）
    private final AuditInstrumentation mAuditInstrumentation;
    
    // 活跃 ACB（agentId → AgentControlBlock）
    private final ConcurrentHashMap<String, AgentControlBlock> mAgents;
    
    // 运行时监听器
    private final RemoteCallbackList<IAgentRuntimeListener> mListeners;
    
    // ID 生成器
    private final AtomicLong mAgentIdCounter = new AtomicLong(1);
    
    public AgentRuntimeService(Context context, PolicyEngineService policyEngine) {
        mContext = context;
        mPolicyEngine = policyEngine;
        mAgents = new ConcurrentHashMap<>();
        mListeners = new RemoteCallbackList<>();
        
        // 初始化审计埋点（A5.3）
        mAuditInstrumentation = new AuditInstrumentation(context);
        
        Slog.i(TAG, "AgentRuntimeService initialized");
    }
    
    /**
     * 注册一个 Agent
     * 
     * @param registration 注册信息（agentName、personaRef、能力等）
     * @param handle 输出参数，返回 AgentHandle（agentId + certificateRef）
     * @return STATUS_OK 成功；STATUS_DENIED 注册信息不合规
     */
    @Override
    public int registerAgent(AgentRegistration registration, AgentHandle handle) {
        if (registration == null || registration.agentName == null) {
            Slog.w(TAG, "Invalid registration: null fields");
            return STATUS_DENIED;
        }
        
        // 1. 生成 agentId
        String agentId = generateAgentId();
        
        // 2. 创建 ACB
        AgentControlBlock acb = new AgentControlBlock(agentId, registration);
        
        // 3. 验证注册信息（personaRef、ownerPackage）
        if (!validateRegistration(registration)) {
            Slog.w(TAG, "Registration validation failed: " + registration.agentName);
            return STATUS_DENIED;
        }
        
        // 4. 注册到活跃表
        mAgents.put(agentId, acb);
        
        // 5. 初始化 AgentHandle
        handle.agentId = agentId;
        handle.certificateRef = generateCertificate(agentId, registration);
        handle.createdAtRealtimeNanos = System.nanoTime();
        
        // 6. 审计埋点（A5.3）：Agent 注册
        String summary = String.format("Agent 注册 name=%s persona=%s",
            registration.agentName,
            registration.personaRef != null ? registration.personaRef : "default");
        
        mAuditInstrumentation.recordSystemEvent(
            "agent_runtime",
            "agent_registered",
            summary
        );
        
        // 7. 触发状态变更事件
        notifyAgentStateChanged(agentId, acb.getStatus());
        
        Slog.i(TAG, "Agent registered: " + agentId + " (" + registration.agentName + ")");
        return STATUS_OK;
    }
    
    /**
     * 终止 Agent
     * 
     * @param agentId Agent ID
     * @param reason 终止原因
     * @return STATUS_OK 成功；STATUS_NOT_FOUND Agent 不存在
     */
    @Override
    public int terminateAgent(String agentId, String reason) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            Slog.w(TAG, "Agent not found: " + agentId);
            return STATUS_NOT_FOUND;
        }
        
        // 1. 撤销所有能力令牌
        acb.revokeAllTokens();
        
        // 2. 状态迁移到 TERMINATED
        acb.setState(AGENT_STATE_TERMINATED, reason);
        
        // 3. 审计埋点（A5.3）：Agent 终止
        String summary = String.format("Agent 终止 agentId=%s reason=%s",
            agentId,
            reason != null ? reason : "未指定原因");
        
        mAuditInstrumentation.recordSystemEvent(
            "agent_runtime",
            "agent_terminated",
            summary
        );
        
        // 4. 移除 ACB（可选：保留 checkpoint）
        mAgents.remove(agentId);
        
        // 5. 触发终止事件
        notifyAgentTerminated(agentId, reason);
        
        Slog.i(TAG, "Agent terminated: " + agentId + ", reason: " + reason);
        return STATUS_OK;
    }
    
    /**
     * 挂起 Agent（持久化 checkpoint）
     * 
     * @param agentId Agent ID
     * @param reason 挂起原因
     * @return STATUS_OK 成功；STATUS_NOT_FOUND Agent 不存在
     */
    @Override
    public int suspendAgent(String agentId, String reason) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        // 1. 创建 checkpoint
        String snapshotId = createCheckpointInternal(acb);
        if (snapshotId == null) {
            Slog.e(TAG, "Failed to create checkpoint for: " + agentId);
            return STATUS_INTERNAL_ERROR;
        }
        
        // 2. 状态迁移到 SUSPENDED
        acb.setState(AGENT_STATE_SUSPENDED, reason);
        acb.lastCheckpointId = snapshotId;
        acb.lastCheckpointAtNanos = System.nanoTime();
        
        // 3. 触发状态变更事件
        notifyAgentStateChanged(agentId, acb.getStatus());
        
        Slog.i(TAG, "Agent suspended: " + agentId + ", checkpoint: " + snapshotId);
        return STATUS_OK;
    }
    
    /**
     * 恢复挂起的 Agent
     * 
     * @param agentId Agent ID
     * @param snapshotId Checkpoint ID
     * @return STATUS_OK 成功；STATUS_NOT_FOUND Agent/Checkpoint 不存在
     */
    @Override
    public int resumeAgent(String agentId, String snapshotId) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        if (acb.state != AGENT_STATE_SUSPENDED) {
            Slog.w(TAG, "Agent not suspended: " + agentId);
            return STATUS_STATE_CONFLICT;
        }
        
        // 1. 恢复 checkpoint
        if (!restoreCheckpointInternal(acb, snapshotId)) {
            Slog.e(TAG, "Failed to restore checkpoint: " + snapshotId);
            return STATUS_INTERNAL_ERROR;
        }
        
        // 2. 状态迁移到 WAITING（等待大脑恢复执行）
        acb.setState(AGENT_STATE_WAITING, "Restored from checkpoint");
        
        // 3. 触发状态变更事件
        notifyAgentStateChanged(agentId, acb.getStatus());
        
        Slog.i(TAG, "Agent resumed: " + agentId + ", from checkpoint: " + snapshotId);
        return STATUS_OK;
    }
    
    /**
     * 更新 Agent 状态（大脑汇报）
     * 
     * @param agentId Agent ID
     * @param newState 新状态
     * @param detail 状态详情
     * @return STATUS_OK 成功；STATUS_NOT_FOUND Agent 不存在
     */
    @Override
    public int updateAgentState(String agentId, int newState, String detail) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        // 验证状态迁移合法性
        if (!isValidStateTransition(acb.state, newState)) {
            Slog.w(TAG, "Invalid state transition: " + acb.state + " -> " + newState);
            return STATUS_STATE_CONFLICT;
        }
        
        acb.setState(newState, detail);
        notifyAgentStateChanged(agentId, acb.getStatus());
        
        return STATUS_OK;
    }
    
    /**
     * 获取 Agent 状态
     * 
     * @param handle Agent 句柄
     * @return AgentRuntimeStatus，如果 Agent 不存在返回 null
     */
    @Override
    public AgentRuntimeStatus getAgentStatus(AgentHandle handle) {
        if (handle == null || handle.agentId == null) {
            return null;
        }
        AgentControlBlock acb = mAgents.get(handle.agentId);
        return (acb != null) ? acb.getStatus() : null;
    }
    
    /**
     * 更新目标栈
     * 
     * @param agentId Agent ID
     * @param goal 目标
     * @return STATUS_OK 成功
     */
    @Override
    public int updateGoal(String agentId, Goal goal) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        acb.updateGoal(goal);
        return STATUS_OK;
    }
    
    /**
     * 请求能力令牌
     * 
     * @param agentId Agent ID
     * @param capability 能力名
     * @param scopeIntentId 意图上下文 ID
     * @param token 输出参数，返回能力令牌
     * @return STATUS_OK 成功；STATUS_DENIED 策略拒绝
     */
    @Override
    public int acquireCapabilityToken(String agentId, String capability, 
                                      String scopeIntentId, CapabilityToken token) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        // 委托给策略引擎签发令牌
        // TODO: 调用 mPolicyEngine.issueCapabilityToken()
        
        String tokenId = UUID.randomUUID().toString();
        token.tokenId = tokenId;
        token.agentId = agentId;
        token.capability = capability;
        token.scopeIntentId = scopeIntentId;
        token.expiresAtRealtimeNanos = System.nanoTime() + 300_000_000_000L; // 5 分钟
        token.issuerSignature = "SIGNATURE_PLACEHOLDER";
        
        acb.addToken(token);
        
        Slog.i(TAG, "Capability token acquired: " + agentId + ", " + capability);
        return STATUS_OK;
    }
    
    /**
     * 释放能力令牌
     * 
     * @param agentId Agent ID
     * @param tokenId 令牌 ID
     * @return STATUS_OK 成功
     */
    @Override
    public int releaseCapabilityToken(String agentId, String tokenId) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        acb.removeToken(tokenId);
        
        Slog.i(TAG, "Capability token released: " + agentId + ", " + tokenId);
        return STATUS_OK;
    }
    
    /**
     * 获取预算配置
     */
    @Override
    public AgentBudget getBudget(String agentId) {
        AgentControlBlock acb = mAgents.get(agentId);
        return (acb != null) ? acb.budget : null;
    }
    
    /**
     * 设置预算配置（仅用户/系统可调）
     */
    @Override
    public int setBudget(String agentId, AgentBudget budget) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        acb.budget = budget;
        Slog.i(TAG, "Budget updated: " + agentId);
        return STATUS_OK;
    }
    
    /**
     * 获取预算使用情况
     */
    @Override
    public AgentBudgetUsage getBudgetUsage(String agentId) {
        AgentControlBlock acb = mAgents.get(agentId);
        return (acb != null) ? acb.budgetUsage : null;
    }
    
    /**
     * 创建 checkpoint
     */
    @Override
    public int createCheckpoint(String agentId, String[] snapshotId) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        String checkpointId = createCheckpointInternal(acb);
        if (checkpointId == null) {
            return STATUS_INTERNAL_ERROR;
        }
        
        snapshotId[0] = checkpointId;
        acb.lastCheckpointId = checkpointId;
        acb.lastCheckpointAtNanos = System.nanoTime();
        
        return STATUS_OK;
    }
    
    /**
     * 恢复 checkpoint
     */
    @Override
    public int restoreCheckpoint(String agentId, String snapshotId) {
        AgentControlBlock acb = mAgents.get(agentId);
        if (acb == null) {
            return STATUS_NOT_FOUND;
        }
        
        if (!restoreCheckpointInternal(acb, snapshotId)) {
            return STATUS_INTERNAL_ERROR;
        }
        
        return STATUS_OK;
    }
    
    /**
     * 列出所有活跃 Agent
     */
    @Override
    public AgentHandle[] listAgents() {
        List<AgentHandle> handles = new ArrayList<>();
        
        for (AgentControlBlock acb : mAgents.values()) {
            AgentHandle handle = new AgentHandle();
            handle.agentId = acb.agentId;
            handle.certificateRef = acb.certificateRef;
            handle.createdAtRealtimeNanos = acb.createdAtNanos;
            handles.add(handle);
        }
        
        return handles.toArray(new AgentHandle[0]);
    }
    
    /**
     * 注册运行时监听器
     */
    @Override
    public int registerListener(IAgentRuntimeListener listener) {
        if (listener == null) {
            return STATUS_DENIED;
        }
        
        mListeners.register(listener);
        Slog.i(TAG, "Listener registered");
        return STATUS_OK;
    }
    
    /**
     * 注销运行时监听器
     */
    @Override
    public int unregisterListener(IAgentRuntimeListener listener) {
        if (listener == null) {
            return STATUS_DENIED;
        }
        
        mListeners.unregister(listener);
        Slog.i(TAG, "Listener unregistered");
        return STATUS_OK;
    }
    
    // ========== 内部辅助方法 ==========
    
    private String generateAgentId() {
        return "agent-" + mAgentIdCounter.getAndIncrement();
    }
    
    private boolean validateRegistration(AgentRegistration registration) {
        // TODO: 验证 personaRef、ownerPackage、调用方 UID
        return registration.agentName != null && !registration.agentName.isEmpty();
    }
    
    private String generateCertificate(String agentId, AgentRegistration registration) {
        // TODO: 生成身份证书（绑定 personaRef + ownerPackage）
        return "cert-" + agentId;
    }
    
    private boolean isValidStateTransition(int from, int to) {
        // 简化版状态迁移验证
        if (from == AGENT_STATE_TERMINATED) {
            return false; // 终止状态不可逆
        }
        return true;
    }
    
    private String createCheckpointInternal(AgentControlBlock acb) {
        // TODO: 持久化 ACB 状态到加密存储
        // 包括：goals、activeCapabilities、budgetUsage、auditRef
        String checkpointId = "checkpoint-" + UUID.randomUUID().toString();
        Slog.i(TAG, "Checkpoint created: " + checkpointId + " for " + acb.agentId);
        return checkpointId;
    }
    
    private boolean restoreCheckpointInternal(AgentControlBlock acb, String snapshotId) {
        // TODO: 从加密存储恢复 ACB 状态
        Slog.i(TAG, "Checkpoint restored: " + snapshotId + " for " + acb.agentId);
        return true;
    }
    
    private void notifyAgentStateChanged(String agentId, AgentRuntimeStatus status) {
        int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onAgentStateChanged(agentId, status);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify listener", e);
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyAgentTerminated(String agentId, String reason) {
        int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onAgentTerminated(agentId, reason);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify listener", e);
            }
        }
        mListeners.finishBroadcast();
    }
    
    /**
     * Agent 控制块（ACB）内部类
     */
    private static class AgentControlBlock {
        final String agentId;
        final AgentRegistration registration;
        final long createdAtNanos;
        String certificateRef;
        
        int state = AGENT_STATE_SPAWNING;
        String stateDetail = "";
        
        final List<Goal> goals = new ArrayList<>();
        final List<CapabilityToken> activeTokens = new ArrayList<>();
        
        AgentBudget budget = new AgentBudget();
        AgentBudgetUsage budgetUsage = new AgentBudgetUsage();
        
        String lastCheckpointId;
        long lastCheckpointAtNanos;
        String auditRef;
        
        AgentControlBlock(String agentId, AgentRegistration registration) {
            this.agentId = agentId;
            this.registration = registration;
            this.createdAtNanos = System.nanoTime();
            
            // 初始化默认预算
            budget.maxTokens = 1000000;  // 100 万 token
            budget.maxEnergyJoules = 10000;
            budget.maxWallTimeMs = 3600000;  // 1 小时
            budget.maxCostMicros = 0;  // 默认禁止云端
        }
        
        void setState(int newState, String detail) {
            this.state = newState;
            this.stateDetail = detail;
        }
        
        void updateGoal(Goal goal) {
            // 简化实现：直接添加（实际应处理父子关系、状态更新）
            goals.add(goal);
        }
        
        void addToken(CapabilityToken token) {
            activeTokens.add(token);
        }
        
        void removeToken(String tokenId) {
            activeTokens.removeIf(t -> t.tokenId.equals(tokenId));
        }
        
        void revokeAllTokens() {
            activeTokens.clear();
        }
        
        AgentRuntimeStatus getStatus() {
            AgentRuntimeStatus status = new AgentRuntimeStatus();
            status.agentId = agentId;
            status.state = state;
            status.detail = stateDetail;
            status.goals = goals.toArray(new Goal[0]);
            status.activeCapabilities = activeTokens.stream()
                .map(t -> t.capability)
                .toArray(String[]::new);
            status.lastCheckpointAtRealtimeNanos = lastCheckpointAtNanos;
            status.auditRef = auditRef;
            return status;
        }
    }
}

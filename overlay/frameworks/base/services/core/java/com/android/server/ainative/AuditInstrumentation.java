package com.android.server.ainative;

import android.util.Slog;

/**
 * 审计埋点辅助类（Audit Instrumentation Helper）
 * 
 * 职责：
 * 1. 为各域服务提供统一的审计埋点接口
 * 2. 封装 AgentAuditService 调用，简化埋点代码
 * 3. 提供便捷方法记录各类审计事件
 * 
 * 使用场景：
 * - 感知层：captureUiTree/captureScreen/readNotification 后调用
 * - 执行层：submitAction/executeAction 前后调用
 * - 策略层：requestCapability/令牌发放/拒绝 时调用
 * - 确认层：showConfirmation/onUserResponse 时调用
 * - 系统层：服务启动/挂起/恢复 时调用
 * 
 * 性能要求：
 * - 埋点开销 < 2ms（不阻塞业务）
 * - 异步记录（不影响主流程）
 * 
 * @see AgentAuditService
 */
public class AuditInstrumentation {
    private static final String TAG = "AuditInstrumentation";
    
    private final AgentAuditService mAuditService;
    
    public AuditInstrumentation(AgentAuditService auditService) {
        mAuditService = auditService;
    }
    
    /**
     * 记录感知读事件
     * 
     * @param serviceName 服务名称（ui_tree/screen/notification）
     * @param agentId Agent ID
     * @param intentId 意图 ID
     * @param summary 摘要
     * @param evidenceRef 证据引用
     */
    public void recordPerceptionRead(String serviceName, String agentId, String intentId, 
                                      String summary, String evidenceRef) {
        try {
            AgentAuditService.AuditEvent event = new AgentAuditService.AuditEvent();
            event.category = AgentAuditService.CAT_PERCEPTION_READ;
            event.agentId = agentId;
            event.intentId = intentId;
            event.summary = String.format("[%s] %s", serviceName, summary);
            
            if (evidenceRef != null) {
                event.evidenceRefs = new String[]{evidenceRef};
            }
            
            mAuditService.recordEvent(event);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record perception read", e);
        }
    }
    
    /**
     * 记录动作执行事件
     * 
     * @param actionId 动作 ID
     * @param agentId Agent ID
     * @param intentId 意图 ID
     * @param phase 阶段（submitted/executing/succeeded/failed）
     * @param summary 摘要
     * @param evidenceRef 证据引用
     */
    public void recordActionExecution(String actionId, String agentId, String intentId,
                                       String phase, String summary, String evidenceRef) {
        try {
            AgentAuditService.AuditEvent event = new AgentAuditService.AuditEvent();
            event.category = AgentAuditService.CAT_ACTION_EXECUTE;
            event.agentId = agentId;
            event.intentId = intentId;
            event.summary = String.format("[%s] %s: %s", phase, actionId, summary);
            
            if (evidenceRef != null) {
                event.evidenceRefs = new String[]{evidenceRef};
            }
            
            mAuditService.recordEvent(event);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record action execution", e);
        }
    }
    
    /**
     * 记录策略决定事件
     * 
     * @param decision 决定（granted/denied/requires_confirmation）
     * @param capability 能力标识
     * @param agentId Agent ID
     * @param intentId 意图 ID
     * @param reason 原因
     */
    public void recordPolicyDecision(String decision, String capability, String agentId,
                                      String intentId, String reason) {
        try {
            AgentAuditService.AuditEvent event = new AgentAuditService.AuditEvent();
            event.category = AgentAuditService.CAT_POLICY_DECISION;
            event.agentId = agentId;
            event.intentId = intentId;
            event.summary = String.format("[%s] %s: %s", decision, capability, reason);
            
            mAuditService.recordEvent(event);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record policy decision", e);
        }
    }
    
    /**
     * 记录能力授予事件
     * 
     * @param capability 能力标识
     * @param agentId Agent ID
     * @param intentId 意图 ID
     * @param tokenId 令牌 ID
     * @param expiresAtMs 过期时间
     */
    public void recordCapabilityGrant(String capability, String agentId, String intentId,
                                       String tokenId, long expiresAtMs) {
        try {
            AgentAuditService.AuditEvent event = new AgentAuditService.AuditEvent();
            event.category = AgentAuditService.CAT_CAPABILITY_GRANT;
            event.agentId = agentId;
            event.intentId = intentId;
            event.summary = String.format("授予能力: %s (token=%s, expires=%d)", 
                capability, tokenId, expiresAtMs);
            
            mAuditService.recordEvent(event);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record capability grant", e);
        }
    }
    
    /**
     * 记录用户确认事件
     * 
     * @param phase 阶段（shown/approved/denied/dismissed）
     * @param confirmationId 确认 ID
     * @param agentId Agent ID
     * @param intentId 意图 ID
     * @param summary 摘要
     */
    public void recordConfirmation(String phase, String confirmationId, String agentId,
                                    String intentId, String summary) {
        try {
            AgentAuditService.AuditEvent event = new AgentAuditService.AuditEvent();
            event.category = AgentAuditService.CAT_CONFIRMATION;
            event.agentId = agentId;
            event.intentId = intentId;
            event.summary = String.format("[%s] %s: %s", phase, confirmationId, summary);
            
            mAuditService.recordEvent(event);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record confirmation", e);
        }
    }
    
    /**
     * 记录系统事件
     * 
     * @param serviceName 服务名称
     * @param event 事件（started/stopped/suspended/resumed/upgraded）
     * @param summary 摘要
     */
    public void recordSystemEvent(String serviceName, String event, String summary) {
        try {
            AgentAuditService.AuditEvent auditEvent = new AgentAuditService.AuditEvent();
            auditEvent.category = AgentAuditService.CAT_SYSTEM;
            auditEvent.agentId = null;  // 系统事件无 agentId
            auditEvent.intentId = null;
            auditEvent.summary = String.format("[%s] %s: %s", serviceName, event, summary);
            
            mAuditService.recordEvent(auditEvent);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record system event", e);
        }
    }
    
    /**
     * 记录记忆访问事件（预留接口，待记忆服务实现）
     * 
     * @param operation 操作（read/write/forget）
     * @param agentId Agent ID
     * @param intentId 意图 ID
     * @param summary 摘要
     */
    public void recordMemoryAccess(String operation, String agentId, String intentId, 
                                    String summary) {
        try {
            AgentAuditService.AuditEvent event = new AgentAuditService.AuditEvent();
            event.category = AgentAuditService.CAT_MEMORY_ACCESS;
            event.agentId = agentId;
            event.intentId = intentId;
            event.summary = String.format("[%s] %s", operation, summary);
            
            mAuditService.recordEvent(event);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record memory access", e);
        }
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return mAuditService.getStats();
    }
}

// TokenRevocationInfo.aidl
package com.genton.policy;

/**
 * 令牌撤销信息（在线撤销列表条目）
 * 
 * 撤销场景：
 * 1. Agent 注销（terminateAgent）
 * 2. 意图任务结束（revokeTokens by intentId）
 * 3. 用户一键停止 Agent（仪表盘"停止授权"）
 * 4. 策略热更新后强制撤销旧版令牌
 * 5. 安全事件响应（异常行为检测）
 * 
 * 撤销列表管理：
 * - 内存广播 + 持久化
 * - 过期令牌自动清理（避免列表无限增长）
 * - TEE 周期锚定（防篡改）
 */
parcelable TokenRevocationInfo {
    /** 被撤销的令牌 ID */
    String tokenId;
    
    /** 撤销时间戳（realtime nanos） */
    long revokedAtRealtimeNanos;
    
    /** 撤销原因（审计用，枚举值见 REASON_* 常量） */
    String reason;
    
    /** 撤销发起者（agent_terminated / user_action / policy_update / security_event） */
    String initiator;
    
    // 撤销原因常量
    const String REASON_AGENT_TERMINATED = "agent_terminated";
    const String REASON_INTENT_COMPLETED = "intent_completed";
    const String REASON_USER_REVOKED = "user_revoked";
    const String REASON_POLICY_UPDATED = "policy_updated";
    const String REASON_SECURITY_EVENT = "security_event";
    const String REASON_TOKEN_EXPIRED = "token_expired";
}

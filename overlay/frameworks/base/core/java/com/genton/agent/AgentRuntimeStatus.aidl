// AgentRuntimeStatus.aidl
// Agent 运行时状态
package com.genton.agent;

import com.genton.agent.Goal;

/**
 * Agent 运行时状态
 * 
 * 包含 Agent 的当前状态、目标栈、活跃能力、预算使用等信息
 */
parcelable AgentRuntimeStatus {
    /**
     * Agent ID
     */
    String agentId;
    
    /**
     * 当前状态
     * 
     * 0 = SPAWNING（初始化中）
     * 1 = PLANNING（规划中）
     * 2 = ACTING（执行中）
     * 3 = WAITING（等待 IO/用户）
     * 4 = SUSPENDED（已挂起）
     * 5 = TERMINATED（已终止）
     */
    int state;
    
    /**
     * 状态详情（人类可读）
     */
    String detail;
    
    /**
     * 当前目标栈（脱敏视图）
     */
    Goal[] goals;
    
    /**
     * 当前持有的能力列表
     */
    String[] activeCapabilities;
    
    /**
     * 最后一次 checkpoint 时间戳
     */
    long lastCheckpointAtRealtimeNanos;
    
    /**
     * 审计链引用（防篡改锚点）
     */
    String auditRef;
}

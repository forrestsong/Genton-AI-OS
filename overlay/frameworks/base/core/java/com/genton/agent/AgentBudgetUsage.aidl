// AgentBudgetUsage.aidl
// Agent 预算使用情况
package com.genton.agent;

/**
 * Agent 预算使用情况
 * 
 * 实时记录 Agent 的资源消耗
 */
parcelable AgentBudgetUsage {
    /**
     * 已使用 LLM token 数
     */
    long tokensUsed;
    
    /**
     * 已消耗能量（焦耳）
     */
    long energyUsedJoules;
    
    /**
     * 已使用墙钟时间（毫秒）
     */
    long wallTimeUsedMs;
    
    /**
     * 已消耗云模型费用（微元）
     */
    long costUsedMicros;
}

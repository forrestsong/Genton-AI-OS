// AgentBudget.aidl
// Agent 预算配置
package com.genton.agent;

/**
 * Agent 预算配置
 * 
 * 用户/系统设定的 Agent 资源上限
 */
parcelable AgentBudget {
    /**
     * LLM token 预算（计费核心）
     */
    long maxTokens;
    
    /**
     * 能耗预算（焦耳）
     */
    long maxEnergyJoules;
    
    /**
     * 墙钟时间预算（毫秒）
     */
    long maxWallTimeMs;
    
    /**
     * 云模型费用上限（微元）
     * 
     * 默认 0 = 禁止云端
     */
    long maxCostMicros;
}

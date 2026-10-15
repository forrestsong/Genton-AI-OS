// IAgentRuntimeListener.aidl
// Agent 运行时监听器
package com.ainative.agent;

import com.ainative.agent.AgentRuntimeStatus;

/**
 * Agent 运行时监听器
 * 
 * 大脑/SystemUI 订阅 Agent 运行时事件
 */
interface IAgentRuntimeListener {
    /**
     * Agent 状态变更
     */
    oneway void onAgentStateChanged(in String agentId, in AgentRuntimeStatus status);
    
    /**
     * 预算超限
     * 
     * @param budgetKind token/energy/time/cost
     */
    oneway void onBudgetExceeded(in String agentId, in String budgetKind);
    
    /**
     * 策略违规
     */
    oneway void onPolicyViolation(in String agentId, in String detail);
    
    /**
     * Agent 终止
     */
    oneway void onAgentTerminated(in String agentId, in String reason);
}

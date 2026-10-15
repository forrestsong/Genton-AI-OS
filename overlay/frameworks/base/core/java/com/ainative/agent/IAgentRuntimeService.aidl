package com.ainative.agent;

import com.ainative.agent.AgentHandle;
import com.ainative.agent.AgentRegistration;
import com.ainative.agent.IAgentRuntimeListener;
import com.ainative.agent.AgentRuntimeStatus;
import com.ainative.agent.CapabilityToken;
import com.ainative.agent.Goal;
import com.ainative.agent.AgentBudget;
import com.ainative.agent.AgentBudgetUsage;

/**
 * IAgentRuntimeService
 * AI Native OS Agent 运行时服务接口
 *
 * 对齐 services/core/java/com/android/server/ainative/AgentRuntimeService.java 实现
 * （Agent 注册/生命周期管理、状态机、能力令牌、预算、Checkpoint、审计锚定）
 */
interface IAgentRuntimeService {
    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;
    const int STATUS_NOT_FOUND       = 2;
    const int STATUS_STATE_CONFLICT  = 3;
    const int STATUS_INTERNAL_ERROR  = -1;

    int registerAgent(in AgentRegistration registration, in AgentHandle handle);

    int terminateAgent(in String agentId, in String reason);

    int suspendAgent(in String agentId, in String reason);

    int resumeAgent(in String agentId, in String snapshotId);

    int updateAgentState(in String agentId, in int newState, in String detail);

    AgentRuntimeStatus getAgentStatus(in AgentHandle handle);

    int updateGoal(in String agentId, in Goal goal);

    int acquireCapabilityToken(in String agentId, in String capability,
            in String scopeIntentId, inout CapabilityToken token);

    int releaseCapabilityToken(in String agentId, in String tokenId);

    AgentBudget getBudget(in String agentId);

    int setBudget(in String agentId, in AgentBudget budget);

    AgentBudgetUsage getBudgetUsage(in String agentId);

    int createCheckpoint(in String agentId, out String[] snapshotId);

    int restoreCheckpoint(in String agentId, in String snapshotId);

    AgentHandle[] listAgents();

    int registerListener(in IAgentRuntimeListener listener);

    int unregisterListener(in IAgentRuntimeListener listener);
}

package com.ainative.brain.session

import android.util.Log
import com.ainative.brain.intent.TurnManager

/**
 * Turn 推送适配器
 * 
 * 职责：
 * 1. 监听 TurnManager 状态变化
 * 2. 将 Turn 状态转换为任务卡片推送
 * 3. 对齐 ACB 状态机（IDLE/PLANNING/EXECUTING/SUSPENDED/TERMINATED）
 * 
 * B3.2 任务：任务卡片状态推进
 */
class TurnPushAdapter(
    private val streamingPushManager: StreamingPushManager
) {
    
    companion object {
        private const val TAG = "TurnPushAdapter"
    }
    
    /**
     * 推送 Turn 状态变化为任务卡片
     * 
     * @param sessionId 会话 ID
     * @param turn Turn 信息
     */
    fun pushTurnStateAsTaskCard(sessionId: String, turn: TurnManager.TurnInfo) {
        val taskId = turn.turnId
        val agentId = extractAgentId(sessionId)
        val goalSummary = extractGoalSummary(turn)
        
        // 将 TurnState 映射到 TaskCardData.state
        val taskState = mapTurnStateToTaskState(turn.state)
        
        // 计算进度
        val progress = calculateProgress(turn)
        
        // 预算警告（暂时为 false，后续集成能力令牌）
        val budgetWarning = false
        
        Log.d(TAG, "Pushing turn state as task card: turnId=${turn.turnId}, state=${turn.state} -> taskState=$taskState")
        
        streamingPushManager.pushTaskCardChanged(
            sessionId = sessionId,
            taskId = taskId,
            agentId = agentId,
            state = taskState,
            goalSummaryMasked = goalSummary,
            progress = progress,
            budgetWarning = budgetWarning
        )
    }
    
    /**
     * 映射 TurnState 到 TaskCardData.state（对齐 ACB 状态机）
     */
    private fun mapTurnStateToTaskState(turnState: TurnManager.TurnState): Int {
        return when (turnState) {
            TurnManager.TurnState.PENDING -> StreamingPushManager.TASK_STATE_IDLE
            TurnManager.TurnState.PLANNING -> StreamingPushManager.TASK_STATE_PLANNING
            TurnManager.TurnState.EXECUTING -> StreamingPushManager.TASK_STATE_EXECUTING
            TurnManager.TurnState.COMPLETED -> StreamingPushManager.TASK_STATE_TERMINATED
            TurnManager.TurnState.FAILED -> StreamingPushManager.TASK_STATE_TERMINATED
            TurnManager.TurnState.CANCELLED -> StreamingPushManager.TASK_STATE_TERMINATED
        }
    }
    
    /**
     * 提取 Agent ID（从 sessionId 解析）
     */
    private fun extractAgentId(sessionId: String): String {
        // 简化实现：从 sessionId 中提取 agentId
        // 实际应该从 SessionInfo 获取
        return "default_assistant"
    }
    
    /**
     * 提取目标摘要（脱敏）
     */
    private fun extractGoalSummary(turn: TurnManager.TurnInfo): String {
        // 从 sourceData 提取用户输入（脱敏）
        val userInput = turn.sourceData["user_input"] as? String
        
        if (!userInput.isNullOrEmpty()) {
            // 截断并脱敏
            return maskSensitiveData(userInput.take(50))
        }
        
        // 事件触发
        val eventType = turn.sourceData["event_type"] as? String
        if (eventType != null) {
            return "Event: $eventType"
        }
        
        // 系统触发
        val trigger = turn.sourceData["trigger"] as? String
        if (trigger != null) {
            return "System: $trigger"
        }
        
        return "Processing..."
    }
    
    /**
     * 计算进度百分比
     */
    private fun calculateProgress(turn: TurnManager.TurnInfo): Int {
        return when (turn.state) {
            TurnManager.TurnState.PENDING -> 0
            TurnManager.TurnState.PLANNING -> 20
            TurnManager.TurnState.EXECUTING -> 50  // 不确定进度，使用 -1 更合适
            TurnManager.TurnState.COMPLETED -> 100
            TurnManager.TurnState.FAILED -> 100
            TurnManager.TurnState.CANCELLED -> 100
        }
    }
    
    /**
     * 脱敏敏感数据
     */
    private fun maskSensitiveData(text: String): String {
        // 简化实现：保留前 10 个字符，其余用 "..." 替代
        if (text.length <= 10) {
            return text
        }
        
        return text.take(10) + "..."
    }
}

// CardStateManager.kt
// B3.2 卡片状态管理器：任务卡片/动作卡片状态推进
package com.ainative.brain.session

import android.util.Log
import com.ainative.agent.IAgentSessionListener
import com.ainative.agent.SessionHandle
import com.ainative.agent.TaskCardData
import com.ainative.agent.ActionCardData
import com.ainative.agent.FormPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * 卡片状态管理器
 * 
 * 核心职责：
 * 1. 任务卡片状态推进：对齐 ACB 状态机（IDLE/PLANNING/EXECUTING/SUSPENDED/TERMINATED）
 * 2. 动作卡片状态推进：progress → done/needs_attention/undo_available
 * 3. 表单卡片状态推进：form → form（状态更新）
 * 4. 卡片去重与合并：同 cardId 推送表示状态更新
 * 
 * 设计原则：
 * - 任务卡片：与 ACB 状态机同步，一个 agentId 对应一张任务卡
 * - 动作卡片：与 ToolLoop 执行节点对应，一个 goal/tool 对应一张动作卡
 * - 表单卡片：L0.5 语义直连，cardId 固定时推送表示状态更新
 */
class CardStateManager {
    companion object {
        private const val TAG = "CardStateManager"
        
        // 任务卡片状态（对齐 ACB 状态机）
        const val TASK_STATE_IDLE = 0        // 空闲
        const val TASK_STATE_PLANNING = 1    // 规划中
        const val TASK_STATE_EXECUTING = 2   // 执行中
        const val TASK_STATE_SUSPENDED = 3   // 挂起（等待确认）
        const val TASK_STATE_TERMINATED = 4  // 已终止
        
        // 动作卡片类型
        const val ACTION_TYPE_PROGRESS = 0         // 执行中
        const val ACTION_TYPE_DONE = 1             // 已完成
        const val ACTION_TYPE_NEEDS_ATTENTION = 2  // 需要关注
        const val ACTION_TYPE_UNDO_AVAILABLE = 3   // 可撤销
        const val ACTION_TYPE_FORM = 4             // 内联表单
        
        // D5.2 表单卡片状态（扩展）
        const val STATE_FORM_EDITING = 100        // 编辑中
        const val STATE_FORM_VALIDATING = 101     // 校验中
        const val STATE_FORM_SUBMITTING = 102     // 提交中
        const val STATE_FORM_SUBMITTED = 103      // 已提交
        const val STATE_FORM_ERROR = 104          // 错误
        const val STATE_FORM_CANCELLED = 105      // 已取消
    }
    
    // 活跃任务卡片（agentId -> TaskCardData）
    private val activeTaskCards = ConcurrentHashMap<String, TaskCardData>()
    
    // 活跃动作卡片（cardId -> ActionCardData）
    private val activeActionCards = ConcurrentHashMap<String, ActionCardData>()
    
    /**
     * 推送任务卡片（增/更新）
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param agentId Agent ID
     * @param state 任务状态
     * @param goalSummary 目标摘要（脱敏）
     * @param progress 进度（-1=不确定，0~100=确定）
     * @param budgetWarning 预算警告
     */
    fun pushTaskCard(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        agentId: String,
        state: Int,
        goalSummary: String,
        progress: Int = -1,
        budgetWarning: Boolean = false
    ) {
        val taskId = "task_$agentId"
        
        val card = TaskCardData().apply {
            this.taskId = taskId
            this.agentId = agentId
            this.state = state
            this.goalSummaryMasked = goalSummary
            this.progress = progress
            this.budgetWarning = budgetWarning
        }
        
        // 更新本地缓存
        activeTaskCards[agentId] = card
        
        // 推送到 UI
        try {
            listener.onTaskCardChanged(handle, card)
            Log.d(TAG, "Pushed task card: agentId=$agentId, state=$state, progress=$progress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push task card: agentId=$agentId", e)
        }
    }
    
    /**
     * 推送动作卡片（进度中）
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param cardId 卡片 ID
     * @param turnId 归属轮次 ID
     * @param title 标题
     * @param summary 摘要（脱敏）
     * @return 卡片 ID
     */
    fun pushActionProgress(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        cardId: String,
        turnId: String,
        title: String,
        summary: String
    ): String {
        val card = ActionCardData().apply {
            this.cardId = cardId
            this.turnId = turnId
            this.type = ACTION_TYPE_PROGRESS
            this.title = title
            this.summaryMasked = summary
            this.auditRef = null
            this.canUndo = false
            this.form = null
        }
        
        activeActionCards[cardId] = card
        
        try {
            listener.onActionCard(handle, card)
            Log.d(TAG, "Pushed action progress: cardId=$cardId, title=$title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push action progress: cardId=$cardId", e)
        }
        
        return cardId
    }
    
    /**
     * 推送动作卡片（已完成）
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param cardId 卡片 ID
     * @param turnId 归属轮次 ID
     * @param title 标题
     * @param summary 摘要（脱敏）
     * @param auditRef 审计引用
     * @param canUndo 是否可撤销
     * @return 卡片 ID
     */
    fun pushActionDone(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        cardId: String,
        turnId: String,
        title: String,
        summary: String,
        auditRef: String? = null,
        canUndo: Boolean = false
    ): String {
        val card = ActionCardData().apply {
            this.cardId = cardId
            this.turnId = turnId
            this.type = ACTION_TYPE_DONE
            this.title = title
            this.summaryMasked = summary
            this.auditRef = auditRef
            this.canUndo = canUndo
            this.form = null
        }
        
        activeActionCards[cardId] = card
        
        try {
            listener.onActionCard(handle, card)
            Log.d(TAG, "Pushed action done: cardId=$cardId, title=$title, canUndo=$canUndo")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push action done: cardId=$cardId", e)
        }
        
        return cardId
    }
    
    /**
     * 推送动作卡片（需要关注）
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param cardId 卡片 ID
     * @param turnId 归属轮次 ID
     * @param title 标题
     * @param summary 摘要（脱敏）
     * @param auditRef 审计引用
     * @return 卡片 ID
     */
    fun pushActionNeedsAttention(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        cardId: String,
        turnId: String,
        title: String,
        summary: String,
        auditRef: String? = null
    ): String {
        val card = ActionCardData().apply {
            this.cardId = cardId
            this.turnId = turnId
            this.type = ACTION_TYPE_NEEDS_ATTENTION
            this.title = title
            this.summaryMasked = summary
            this.auditRef = auditRef
            this.canUndo = false
            this.form = null
        }
        
        activeActionCards[cardId] = card
        
        try {
            listener.onActionCard(handle, card)
            Log.d(TAG, "Pushed action needs attention: cardId=$cardId, title=$title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push action needs attention: cardId=$cardId", e)
        }
        
        return cardId
    }
    
    /**
     * 推送表单卡片（L0.5 语义直连）
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param cardId 卡片 ID（固定时推送表示状态更新）
     * @param turnId 归属轮次 ID
     * @param title 标题
     * @param summary 摘要（脱敏）
     * @param form 表单负载
     * @return 卡片 ID
     */
    fun pushFormCard(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        cardId: String,
        turnId: String,
        title: String,
        summary: String,
        form: FormPayload
    ): String {
        val card = ActionCardData().apply {
            this.cardId = cardId
            this.turnId = turnId
            this.type = ACTION_TYPE_FORM
            this.title = title
            this.summaryMasked = summary
            this.auditRef = null
            this.canUndo = false
            this.form = form
        }
        
        activeActionCards[cardId] = card
        
        try {
            listener.onActionCard(handle, card)
            Log.d(TAG, "Pushed form card: cardId=$cardId, title=$title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push form card: cardId=$cardId", e)
        }
        
        return cardId
    }
    
    /**
     * D5.2 更新卡片状态（用于表单状态管理）
     * 
     * @param cardId 卡片ID
     * @param state 新状态
     */
    fun updateCardState(cardId: String, state: Int) {
        val card = activeActionCards[cardId]
        if (card != null) {
            // 表单卡片状态可以存储在扩展字段中
            // 当前简化实现：记录日志
            Log.d(TAG, "Updated card state: cardId=$cardId, state=$state")
        } else {
            Log.w(TAG, "Card not found for state update: cardId=$cardId")
        }
    }
    
    /**
     * 更新任务进度
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param agentId Agent ID
     * @param progress 进度（0~100）
     */
    fun updateTaskProgress(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        agentId: String,
        progress: Int
    ) {
        val existingCard = activeTaskCards[agentId]
        if (existingCard == null) {
            Log.w(TAG, "Task card not found for agentId: $agentId")
            return
        }
        
        pushTaskCard(
            listener,
            handle,
            agentId,
            existingCard.state,
            existingCard.goalSummaryMasked,
            progress,
            existingCard.budgetWarning
        )
    }
    
    /**
     * 更新任务状态
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param agentId Agent ID
     * @param state 新状态
     */
    fun updateTaskState(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        agentId: String,
        state: Int
    ) {
        val existingCard = activeTaskCards[agentId]
        if (existingCard == null) {
            Log.w(TAG, "Task card not found for agentId: $agentId")
            return
        }
        
        pushTaskCard(
            listener,
            handle,
            agentId,
            state,
            existingCard.goalSummaryMasked,
            existingCard.progress,
            existingCard.budgetWarning
        )
    }
    
    /**
     * 清理会话卡片
     * 
     * @param agentId Agent ID
     */
    fun clearSession(agentId: String) {
        activeTaskCards.remove(agentId)
        
        // 清理归属于该 agent 的动作卡片
        val iterator = activeActionCards.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // 如果 turnId 包含 agentId 前缀，则认为归属于该 agent
            if (entry.value.turnId.startsWith("turn_${agentId}_")) {
                iterator.remove()
            }
        }
        
        Log.d(TAG, "Cleared cards for agentId: $agentId")
    }
}

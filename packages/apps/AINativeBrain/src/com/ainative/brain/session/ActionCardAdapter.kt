package com.ainative.brain.session

import android.util.Log
import com.ainative.brain.toolloop.ToolExecutionResult
import java.util.UUID

/**
 * 动作卡片推送适配器
 * 
 * 职责：
 * 1. 将工具执行结果转换为动作卡片
 * 2. 推送进度/完成/需要关注/可撤销卡片
 * 3. 管理卡片生命周期
 * 
 * B3.2 任务：动作卡片推送
 */
class ActionCardAdapter(
    private val streamingPushManager: StreamingPushManager
) {
    
    companion object {
        private const val TAG = "ActionCardAdapter"
    }
    
    /**
     * 推送工具执行进度卡片
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID
     * @param toolName 工具名称
     * @param progress 进度描述
     */
    fun pushToolProgress(
        sessionId: String,
        turnId: String,
        toolName: String,
        progress: String
    ) {
        val cardId = generateCardId(turnId, toolName)
        
        Log.d(TAG, "Pushing tool progress card: toolName=$toolName")
        
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = cardId,
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_PROGRESS,
            title = "Executing: $toolName",
            summaryMasked = maskProgress(progress),
            auditRef = null,
            canUndo = false
        )
    }
    
    /**
     * 推送工具执行完成卡片
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID
     * @param toolName 工具名称
     * @param result 执行结果
     * @param auditRef 审计引用
     * @param canUndo 是否可撤销
     */
    fun pushToolCompleted(
        sessionId: String,
        turnId: String,
        toolName: String,
        result: String,
        auditRef: String? = null,
        canUndo: Boolean = false
    ) {
        val cardId = generateCardId(turnId, toolName)
        
        Log.d(TAG, "Pushing tool completed card: toolName=$toolName, canUndo=$canUndo")
        
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = cardId,
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_DONE,
            title = "Completed: $toolName",
            summaryMasked = maskResult(result),
            auditRef = auditRef,
            canUndo = canUndo
        )
    }
    
    /**
     * 推送需要用户关注卡片
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID
     * @param reason 原因
     * @param details 详情（脱敏）
     */
    fun pushNeedsAttention(
        sessionId: String,
        turnId: String,
        reason: String,
        details: String
    ) {
        val cardId = generateCardId(turnId, "attention_${System.currentTimeMillis()}")
        
        Log.d(TAG, "Pushing needs attention card: reason=$reason")
        
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = cardId,
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_NEEDS_ATTENTION,
            title = "Needs Attention",
            summaryMasked = maskDetails(details),
            auditRef = null,
            canUndo = false
        )
    }
    
    /**
     * 推送可撤销操作卡片
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID
     * @param actionName 操作名称
     * @param actionSummary 操作摘要
     * @param auditRef 审计引用
     */
    fun pushUndoAvailable(
        sessionId: String,
        turnId: String,
        actionName: String,
        actionSummary: String,
        auditRef: String
    ) {
        val cardId = generateCardId(turnId, actionName)
        
        Log.d(TAG, "Pushing undo available card: actionName=$actionName")
        
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = cardId,
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_UNDO_AVAILABLE,
            title = "Undo Available: $actionName",
            summaryMasked = maskSummary(actionSummary),
            auditRef = auditRef,
            canUndo = true
        )
    }
    
    /**
     * 从工具执行结果推送动作卡片
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID
     * @param toolName 工具名称
     * @param result 工具执行结果
     */
    fun pushFromToolResult(
        sessionId: String,
        turnId: String,
        toolName: String,
        result: ToolExecutionResult
    ) {
        when {
            result.success -> {
                pushToolCompleted(
                    sessionId = sessionId,
                    turnId = turnId,
                    toolName = toolName,
                    result = result.output ?: "Completed",
                    auditRef = result.auditRef,
                    canUndo = result.canUndo
                )
            }
            result.needsUserConfirmation -> {
                pushNeedsAttention(
                    sessionId = sessionId,
                    turnId = turnId,
                    reason = "Confirmation Required",
                    details = result.confirmationPrompt ?: "Please confirm this action"
                )
            }
            else -> {
                // 失败情况由错误处理流程处理
                Log.w(TAG, "Tool execution failed: $toolName")
            }
        }
    }
    
    /**
     * 生成卡片 ID
     */
    private fun generateCardId(turnId: String, identifier: String): String {
        return "${turnId}_${identifier.hashCode()}"
    }
    
    /**
     * 脱敏进度描述
     */
    private fun maskProgress(progress: String): String {
        // 简化实现：限制长度
        return progress.take(100)
    }
    
    /**
     * 脱敏执行结果
     */
    private fun maskResult(result: String): String {
        // 简化实现：限制长度，移除敏感信息
        val masked = result.take(200)
        
        // TODO: 高级脱敏逻辑
        // - 移除密码、密钥、令牌
        // - 移除 PII（姓名、邮箱、电话）
        // - 移除文件路径中的敏感部分
        
        return masked
    }
    
    /**
     * 脱敏详情
     */
    private fun maskDetails(details: String): String {
        return details.take(150)
    }
    
    /**
     * 脱敏摘要
     */
    private fun maskSummary(summary: String): String {
        return summary.take(100)
    }
}

/**
 * 工具执行结果数据类（临时定义，实际应该从 ToolLoop 导入）
 */
data class ToolExecutionResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val auditRef: String? = null,
    val canUndo: Boolean = false,
    val needsUserConfirmation: Boolean = false,
    val confirmationPrompt: String? = null
)

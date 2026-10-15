// StreamingPushManager.kt
// B3.2 流式推送管理器：任务卡/动作卡状态推送
package com.ainative.brain.session

import android.util.Log
import com.ainative.agent.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 流式推送管理器
 * 
 * 核心职责：
 * 1. 推送任务卡状态变化（type=0）
 * 2. 推送动作卡状态变化（type=1–4）
 * 3. 对齐 D12 卡片类型定义
 * 4. 管理监听器回调
 * 
 * 设计原则：
 * - 状态推送由规划器/工具循环触发
 * - 卡片类型对齐 D12：0=任务卡、1=动作卡进行中、2=动作卡成功、3=动作卡失败、4=动作卡取消
 * - 任务状态对齐 ACB：IDLE/PLANNING/EXECUTING/SUSPENDED/TERMINATED
 */
class StreamingPushManager {
    
    companion object {
        private const val TAG = "StreamingPushManager"
        
        // 任务卡状态（对齐 ACB 状态机）
        const val TASK_STATE_IDLE = 0         // 空闲
        const val TASK_STATE_PLANNING = 1     // 规划中
        const val TASK_STATE_EXECUTING = 2    // 执行中
        const val TASK_STATE_SUSPENDED = 3    // 挂起
        const val TASK_STATE_TERMINATED = 4   // 终止
        
        // 动作卡状态
        const val ACTION_STATE_PENDING = 0     // 待执行
        const val ACTION_STATE_RUNNING = 1     // 执行中
        const val ACTION_STATE_COMPLETED = 2   // 成功
        const val ACTION_STATE_FAILED = 3      // 失败
        const val ACTION_STATE_CANCELLED = 4   // 取消
    }
    
    // 监听器表（sessionId -> listener）
    private val listeners = ConcurrentHashMap<String, IAgentSessionListener>()
    
    // 会话句柄缓存（sessionId -> SessionHandle）
    private val sessionHandles = ConcurrentHashMap<String, SessionHandle>()
    
    /**
     * 注册会话监听器
     * 
     * @param sessionId 会话 ID
     * @param handle 会话句柄
     * @param listener 监听器
     */
    fun registerListener(
        sessionId: String,
        handle: SessionHandle,
        listener: IAgentSessionListener
    ) {
        listeners[sessionId] = listener
        sessionHandles[sessionId] = handle
        Log.d(TAG, "Listener registered for session: $sessionId")
    }
    
    /**
     * 注销会话监听器
     * 
     * @param sessionId 会话 ID
     */
    fun unregisterListener(sessionId: String) {
        listeners.remove(sessionId)
        sessionHandles.remove(sessionId)
        Log.d(TAG, "Listener unregistered for session: $sessionId")
    }
    
    /**
     * 推送任务卡状态变化（type=0）
     * 
     * @param sessionId 会话 ID
     * @param taskId 任务 ID
     * @param agentId Agent ID
     * @param state 任务状态（TASK_STATE_*）
     * @param goalSummaryMasked 目标摘要（已脱敏）
     * @param progress 进度百分比（0–100，-1 表示不确定）
     * @param budgetWarning 预算警告
     */
    fun pushTaskCardChanged(
        sessionId: String,
        taskId: String,
        agentId: String,
        state: Int,
        goalSummaryMasked: String,
        progress: Int,
        budgetWarning: Boolean
    ) {
        val listener = listeners[sessionId]
        val handle = sessionHandles[sessionId]
        
        if (listener == null || handle == null) {
            Log.w(TAG, "No listener/handle for session: $sessionId")
            return
        }
        
        try {
            val taskCard = TaskCardData().apply {
                this.taskId = taskId
                this.agentId = agentId
                this.state = state
                this.goalSummaryMasked = goalSummaryMasked
                this.progress = progress
                this.budgetWarning = budgetWarning
                this.timestampMs = System.currentTimeMillis()
            }
            
            listener.onTaskCardChanged(handle, taskCard)
            
            Log.d(TAG, "Task card pushed: sessionId=$sessionId, taskId=$taskId, state=$state, progress=$progress")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push task card for session: $sessionId", e)
        }
    }
    
    /**
     * 推送动作卡状态变化（type=1–4）
     * 
     * @param sessionId 会话 ID
     * @param actionId 动作 ID
     * @param taskId 所属任务 ID
     * @param state 动作状态（ACTION_STATE_*）
     * @param actionName 动作名称
     * @param targetAppLabel 目标应用标签（可选）
     * @param errorMessage 错误信息（失败时）
     */
    fun pushActionCardChanged(
        sessionId: String,
        actionId: String,
        taskId: String,
        state: Int,
        actionName: String,
        targetAppLabel: String? = null,
        errorMessage: String? = null
    ) {
        val listener = listeners[sessionId]
        val handle = sessionHandles[sessionId]
        
        if (listener == null || handle == null) {
            Log.w(TAG, "No listener/handle for session: $sessionId")
            return
        }
        
        try {
            val actionCard = ActionCardData().apply {
                this.actionId = actionId
                this.taskId = taskId
                this.state = state
                this.actionName = actionName
                this.targetAppLabel = targetAppLabel
                this.errorMessage = errorMessage
                this.timestampMs = System.currentTimeMillis()
            }
            
            listener.onActionCardChanged(handle, actionCard)
            
            Log.d(TAG, "Action card pushed: sessionId=$sessionId, actionId=$actionId, state=$state, actionName=$actionName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push action card for session: $sessionId", e)
        }
    }
    
    /**
     * 推送文本流式增量（通过 StreamCoordinator）
     * 
     * @param sessionId 会话 ID
     * @param turnId 轮次 ID
     * @param delta 增量数据
     */
    fun pushTurnDelta(
        sessionId: String,
        turnId: String,
        delta: TurnDelta,
        streamCoordinator: StreamCoordinator
    ) {
        val listener = listeners[sessionId]
        val handle = sessionHandles[sessionId]
        
        if (listener == null || handle == null) {
            Log.w(TAG, "No listener/handle for session: $sessionId")
            return
        }
        
        // 委托给 StreamCoordinator 处理合帧节流
        streamCoordinator.pushDelta(listener, handle, turnId, delta)
    }
}

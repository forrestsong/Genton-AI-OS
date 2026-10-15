package com.ainative.action.service

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A3.2 执行中介 - 8态状态机
 * 
 * 职责：
 * 1. 管理动作执行的状态转换
 * 2. 校验状态转换合法性
 * 3. 记录状态转换历史
 * 4. 支持状态查询和监听
 * 
 * 状态转换规则：
 * QUEUED → POLICY_CHECK → CONFIRMATION → EXECUTING → SUCCEEDED/FAILED
 *    ↓         ↓              ↓             ↓
 * ABORTED   ABORTED        ABORTED       ABORTED
 *                            ↓
 *                         EXPIRED
 */
class ActionStateMachine {
    
    companion object {
        private const val TAG = "ActionStateMachine"
        
        // 确认超时时间（毫秒）
        private const val CONFIRMATION_TIMEOUT_MS = 30000L  // 30秒
    }
    
    // 状态存储（actionRequestId -> StateContext）
    private val states = ConcurrentHashMap<String, StateContext>()
    
    // 请求ID计数器
    private val requestIdCounter = AtomicInteger(0)
    
    // 状态监听器
    private val listeners = mutableListOf<StateTransitionListener>()
    
    /**
     * 创建新的动作执行实例
     */
    fun createExecution(request: ActionRequest): String {
        val requestId = generateRequestId()
        
        val context = StateContext(
            actionRequestId = requestId,
            actionName = request.actionName,
            agentId = request.agentId,
            currentState = ActionState.STATE_QUEUED,
            submitTime = System.currentTimeMillis(),
            lastUpdateTime = System.currentTimeMillis(),
            stateHistory = mutableListOf(
                StateTransition(
                    fromState = 0,
                    toState = ActionState.STATE_QUEUED,
                    timestamp = System.currentTimeMillis(),
                    reason = "Initial submission"
                )
            )
        )
        
        states[requestId] = context
        
        Log.i(TAG, "Created execution: $requestId for action ${request.actionName}")
        
        return requestId
    }
    
    /**
     * 状态转换
     */
    fun transition(
        actionRequestId: String,
        toState: Int,
        reason: String? = null,
        result: ActionResult? = null
    ): Boolean {
        val context = states[actionRequestId]
        if (context == null) {
            Log.e(TAG, "Action request not found: $actionRequestId")
            return false
        }
        
        synchronized(context) {
            val fromState = context.currentState
            
            // 校验转换合法性
            if (!isValidTransition(fromState, toState)) {
                Log.e(TAG, "Invalid state transition: $actionRequestId from ${getStateName(fromState)} to ${getStateName(toState)}")
                return false
            }
            
            // 执行转换
            context.currentState = toState
            context.lastUpdateTime = System.currentTimeMillis()
            
            // 记录历史
            context.stateHistory.add(
                StateTransition(
                    fromState = fromState,
                    toState = toState,
                    timestamp = context.lastUpdateTime,
                    reason = reason
                )
            )
            
            // 保存结果（终态）
            if (toState in arrayOf(
                    ActionState.STATE_SUCCEEDED,
                    ActionState.STATE_FAILED,
                    ActionState.STATE_ABORTED,
                    ActionState.STATE_EXPIRED
                )) {
                context.result = result
            }
            
            Log.i(TAG, "State transition: $actionRequestId ${getStateName(fromState)} → ${getStateName(toState)}" +
                    (reason?.let { " ($it)" } ?: ""))
            
            // 通知监听器
            notifyListeners(actionRequestId, fromState, toState, context)
            
            return true
        }
    }
    
    /**
     * 校验状态转换合法性
     */
    private fun isValidTransition(fromState: Int, toState: Int): Boolean {
        // 允许的转换规则
        return when (fromState) {
            ActionState.STATE_QUEUED -> {
                toState in arrayOf(
                    ActionState.STATE_POLICY_CHECK,
                    ActionState.STATE_ABORTED
                )
            }
            
            ActionState.STATE_POLICY_CHECK -> {
                toState in arrayOf(
                    ActionState.STATE_CONFIRMATION,
                    ActionState.STATE_EXECUTING,
                    ActionState.STATE_FAILED,
                    ActionState.STATE_ABORTED
                )
            }
            
            ActionState.STATE_CONFIRMATION -> {
                toState in arrayOf(
                    ActionState.STATE_EXECUTING,
                    ActionState.STATE_ABORTED,
                    ActionState.STATE_EXPIRED,
                    ActionState.STATE_FAILED
                )
            }
            
            ActionState.STATE_EXECUTING -> {
                toState in arrayOf(
                    ActionState.STATE_SUCCEEDED,
                    ActionState.STATE_FAILED,
                    ActionState.STATE_ABORTED
                )
            }
            
            // 终态不允许转换
            ActionState.STATE_SUCCEEDED,
            ActionState.STATE_FAILED,
            ActionState.STATE_ABORTED,
            ActionState.STATE_EXPIRED -> false
            
            else -> false
        }
    }
    
    /**
     * 获取执行状态
     */
    fun getStatus(actionRequestId: String): ActionExecutionStatus? {
        val context = states[actionRequestId] ?: return null
        
        synchronized(context) {
            return ActionExecutionStatus(
                actionRequestId = context.actionRequestId,
                currentState = context.currentState,
                actionName = context.actionName,
                submitTime = context.submitTime,
                lastUpdateTime = context.lastUpdateTime,
                result = context.result,
                progressMessage = context.progressMessage
            )
        }
    }
    
    /**
     * 检查是否可以取消
     */
    fun isCancellable(actionRequestId: String): Boolean {
        val context = states[actionRequestId] ?: return false
        return context.currentState in arrayOf(
            ActionState.STATE_QUEUED,
            ActionState.STATE_POLICY_CHECK,
            ActionState.STATE_CONFIRMATION,
            ActionState.STATE_EXECUTING
        )
    }
    
    /**
     * 取消执行
     */
    fun cancel(actionRequestId: String, reason: String = "User cancelled"): Boolean {
        val context = states[actionRequestId]
        if (context == null) {
            Log.w(TAG, "Cannot cancel: action request not found: $actionRequestId")
            return false
        }
        
        if (!isCancellable(actionRequestId)) {
            Log.w(TAG, "Cannot cancel: action in state ${getStateName(context.currentState)}")
            return false
        }
        
        return transition(
            actionRequestId = actionRequestId,
            toState = ActionState.STATE_ABORTED,
            reason = reason,
            result = ActionResult.failure("Cancelled: $reason")
        )
    }
    
    /**
     * 检查确认超时
     */
    fun checkConfirmationTimeout(actionRequestId: String): Boolean {
        val context = states[actionRequestId] ?: return false
        
        if (context.currentState != ActionState.STATE_CONFIRMATION) {
            return false
        }
        
        val elapsed = System.currentTimeMillis() - context.lastUpdateTime
        if (elapsed > CONFIRMATION_TIMEOUT_MS) {
            transition(
                actionRequestId = actionRequestId,
                toState = ActionState.STATE_EXPIRED,
                reason = "Confirmation timeout after ${elapsed}ms",
                result = ActionResult.failure("Confirmation timeout")
            )
            return true
        }
        
        return false
    }
    
    /**
     * 设置进度信息
     */
    fun setProgress(actionRequestId: String, message: String) {
        val context = states[actionRequestId] ?: return
        synchronized(context) {
            context.progressMessage = message
            context.lastUpdateTime = System.currentTimeMillis()
        }
    }
    
    /**
     * 清理已完成的执行记录
     */
    fun cleanup(maxAge: Long = 3600000L) {  // 默认1小时
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        states.forEach { (requestId, context) ->
            if (context.isTerminal() && (now - context.lastUpdateTime) > maxAge) {
                toRemove.add(requestId)
            }
        }
        
        toRemove.forEach { requestId ->
            states.remove(requestId)
            Log.d(TAG, "Cleaned up execution: $requestId")
        }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toRemove.size} execution records")
        }
    }
    
    /**
     * 注册状态监听器
     */
    fun registerListener(listener: StateTransitionListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }
    
    /**
     * 移除状态监听器
     */
    fun unregisterListener(listener: StateTransitionListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    /**
     * 通知监听器
     */
    private fun notifyListeners(
        actionRequestId: String,
        fromState: Int,
        toState: Int,
        context: StateContext
    ) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onStateTransition(actionRequestId, fromState, toState, context)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error", e)
                }
            }
        }
    }
    
    /**
     * 生成请求ID
     */
    private fun generateRequestId(): String {
        val count = requestIdCounter.incrementAndGet()
        val timestamp = System.currentTimeMillis()
        return "action_${timestamp}_${count}"
    }
    
    /**
     * 获取状态名称
     */
    private fun getStateName(state: Int): String {
        return ActionState.getStateDescription(state)
    }
    
    /**
     * 获取统计信息
     */
    fun getStats(): StateMachineStats {
        val stateCount = mutableMapOf<Int, Int>()
        var totalExecutions = 0
        
        states.values.forEach { context ->
            val state = context.currentState
            stateCount[state] = stateCount.getOrDefault(state, 0) + 1
            totalExecutions++
        }
        
        return StateMachineStats(
            totalExecutions = totalExecutions,
            stateDistribution = stateCount
        )
    }
}

/**
 * 状态上下文
 */
data class StateContext(
    val actionRequestId: String,
    val actionName: String,
    val agentId: String,
    var currentState: Int,
    val submitTime: Long,
    var lastUpdateTime: Long,
    var result: ActionResult? = null,
    var progressMessage: String? = null,
    val stateHistory: MutableList<StateTransition>
) {
    fun isTerminal(): Boolean {
        return currentState in arrayOf(
            ActionState.STATE_SUCCEEDED,
            ActionState.STATE_FAILED,
            ActionState.STATE_ABORTED,
            ActionState.STATE_EXPIRED
        )
    }
}

/**
 * 状态转换记录
 */
data class StateTransition(
    val fromState: Int,
    val toState: Int,
    val timestamp: Long,
    val reason: String?
)

/**
 * 状态转换监听器
 */
interface StateTransitionListener {
    fun onStateTransition(
        actionRequestId: String,
        fromState: Int,
        toState: Int,
        context: StateContext
    )
}

/**
 * 状态机统计信息
 */
data class StateMachineStats(
    val totalExecutions: Int,
    val stateDistribution: Map<Int, Int>
)

package com.ainative.brain.ui.form

import android.util.Log
import com.ainative.brain.session.CardStateManager
import java.util.concurrent.ConcurrentHashMap

/**
 * D5.2 表单提交与卡片状态机 - 表单卡片状态机
 * 
 * 职责：
 * 1. 管理表单卡片的完整生命周期状态
 * 2. 定义状态转换规则和约束
 * 3. 触发状态变化时的副作用（UI更新、通知等）
 * 4. 提供状态查询和历史记录
 * 
 * 状态流转：
 * EDITING → VALIDATING → SUBMITTING → SUBMITTED
 *    ↓          ↓            ↓
 * CANCELLED   ERROR        ERROR
 * 
 * 约束：
 * - EDITING 只能转到 VALIDATING 或 CANCELLED
 * - VALIDATING 只能转到 SUBMITTING 或 ERROR
 * - SUBMITTING 只能转到 SUBMITTED 或 ERROR
 * - SUBMITTED 是终态，不可转换
 * - ERROR 可以转回 EDITING（重试）
 */
class FormCardStateMachine(
    private val cardStateManager: CardStateManager
) {
    
    companion object {
        private const val TAG = "FormCardStateMachine"
        
        // 使用 CardStateManager 中定义的状态常量
        const val STATE_EDITING = CardStateManager.STATE_FORM_EDITING
        const val STATE_VALIDATING = CardStateManager.STATE_FORM_VALIDATING
        const val STATE_SUBMITTING = CardStateManager.STATE_FORM_SUBMITTING
        const val STATE_SUBMITTED = CardStateManager.STATE_FORM_SUBMITTED
        const val STATE_ERROR = CardStateManager.STATE_FORM_ERROR
        const val STATE_CANCELLED = CardStateManager.STATE_FORM_CANCELLED
    }
    
    // 卡片状态缓存
    private val cardStates = ConcurrentHashMap<String, FormCardState>()
    
    // 状态监听器
    private val stateListeners = mutableListOf<FormCardStateListener>()
    
    /**
     * 初始化表单卡片状态
     */
    fun initializeCard(cardId: String, formId: String) {
        Log.d(TAG, "Initializing form card: cardId=$cardId, formId=$formId")
        
        val state = FormCardState(
            cardId = cardId,
            formId = formId,
            currentState = STATE_EDITING,
            createdTime = System.currentTimeMillis()
        )
        
        cardStates[cardId] = state
        
        // 更新卡片状态管理器
        cardStateManager.updateCardState(cardId, STATE_EDITING)
        
        // 通知监听器
        notifyListeners { it.onStateChanged(cardId, STATE_EDITING, null) }
    }
    
    /**
     * 转换状态
     */
    fun transition(cardId: String, targetState: Int, reason: String? = null): Boolean {
        val state = cardStates[cardId]
        if (state == null) {
            Log.w(TAG, "Card not found: $cardId")
            return false
        }
        
        val currentState = state.currentState
        
        // 检查状态转换是否合法
        if (!isValidTransition(currentState, targetState)) {
            Log.w(TAG, "Invalid state transition: $cardId, ${getStateName(currentState)} → ${getStateName(targetState)}")
            return false
        }
        
        Log.i(TAG, "State transition: $cardId, ${getStateName(currentState)} → ${getStateName(targetState)}")
        
        // 记录历史
        state.stateHistory.add(StateTransition(
            fromState = currentState,
            toState = targetState,
            timestamp = System.currentTimeMillis(),
            reason = reason
        ))
        
        // 更新状态
        val previousState = state.currentState
        state.currentState = targetState
        state.lastUpdateTime = System.currentTimeMillis()
        
        // 更新卡片状态管理器
        cardStateManager.updateCardState(cardId, targetState)
        
        // 执行状态进入副作用
        executeStateEnterSideEffects(cardId, targetState)
        
        // 通知监听器
        notifyListeners { it.onStateChanged(cardId, targetState, previousState) }
        
        return true
    }
    
    /**
     * 检查状态转换是否合法
     */
    private fun isValidTransition(fromState: Int, toState: Int): Boolean {
        return when (fromState) {
            STATE_EDITING -> {
                toState in setOf(STATE_VALIDATING, STATE_CANCELLED)
            }
            
            STATE_VALIDATING -> {
                toState in setOf(STATE_SUBMITTING, STATE_ERROR, STATE_EDITING)
            }
            
            STATE_SUBMITTING -> {
                toState in setOf(STATE_SUBMITTED, STATE_ERROR)
            }
            
            STATE_ERROR -> {
                toState in setOf(STATE_EDITING, STATE_CANCELLED)
            }
            
            STATE_SUBMITTED -> {
                false  // 终态，不可转换
            }
            
            STATE_CANCELLED -> {
                toState == STATE_EDITING  // 可以重新编辑
            }
            
            else -> false
        }
    }
    
    /**
     * 执行状态进入副作用
     */
    private fun executeStateEnterSideEffects(cardId: String, state: Int) {
        when (state) {
            STATE_VALIDATING -> {
                Log.d(TAG, "Side effect: Validating form $cardId")
                // 可以触发 UI 显示加载动画
            }
            
            STATE_SUBMITTING -> {
                Log.d(TAG, "Side effect: Submitting form $cardId")
                // 禁用表单输入，显示提交进度
            }
            
            STATE_SUBMITTED -> {
                Log.d(TAG, "Side effect: Form submitted $cardId")
                // 显示成功提示，可能自动关闭表单
            }
            
            STATE_ERROR -> {
                Log.d(TAG, "Side effect: Form error $cardId")
                // 显示错误提示，启用重试按钮
            }
            
            STATE_CANCELLED -> {
                Log.d(TAG, "Side effect: Form cancelled $cardId")
                // 清空表单数据或关闭卡片
            }
        }
    }
    
    /**
     * 获取当前状态
     */
    fun getCurrentState(cardId: String): Int? {
        return cardStates[cardId]?.currentState
    }
    
    /**
     * 是否处于终态
     */
    fun isTerminalState(cardId: String): Boolean {
        val state = getCurrentState(cardId)
        return state == STATE_SUBMITTED || state == STATE_CANCELLED
    }
    
    /**
     * 是否可以编辑
     */
    fun canEdit(cardId: String): Boolean {
        val state = getCurrentState(cardId)
        return state == STATE_EDITING || state == STATE_ERROR
    }
    
    /**
     * 是否可以提交
     */
    fun canSubmit(cardId: String): Boolean {
        val state = getCurrentState(cardId)
        return state == STATE_EDITING
    }
    
    /**
     * 是否可以取消
     */
    fun canCancel(cardId: String): Boolean {
        val state = getCurrentState(cardId)
        return state in setOf(STATE_EDITING, STATE_ERROR)
    }
    
    /**
     * 是否可以重试
     */
    fun canRetry(cardId: String): Boolean {
        val state = getCurrentState(cardId)
        return state == STATE_ERROR
    }
    
    /**
     * 获取状态历史
     */
    fun getStateHistory(cardId: String): List<StateTransition> {
        return cardStates[cardId]?.stateHistory?.toList() ?: emptyList()
    }
    
    /**
     * 获取状态持续时间（毫秒）
     */
    fun getStateDuration(cardId: String): Long {
        val state = cardStates[cardId] ?: return 0
        return System.currentTimeMillis() - state.lastUpdateTime
    }
    
    /**
     * 移除卡片状态
     */
    fun removeCard(cardId: String) {
        cardStates.remove(cardId)
        Log.d(TAG, "Removed card state: $cardId")
    }
    
    /**
     * 注册状态监听器
     */
    fun registerListener(listener: FormCardStateListener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener)
        }
    }
    
    /**
     * 注销状态监听器
     */
    fun unregisterListener(listener: FormCardStateListener) {
        stateListeners.remove(listener)
    }
    
    /**
     * 通知监听器
     */
    private fun notifyListeners(action: (FormCardStateListener) -> Unit) {
        stateListeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying state listener", e)
            }
        }
    }
    
    /**
     * 获取状态名称
     */
    fun getStateName(state: Int): String {
        return when (state) {
            STATE_EDITING -> "EDITING"
            STATE_VALIDATING -> "VALIDATING"
            STATE_SUBMITTING -> "SUBMITTING"
            STATE_SUBMITTED -> "SUBMITTED"
            STATE_ERROR -> "ERROR"
            STATE_CANCELLED -> "CANCELLED"
            else -> "UNKNOWN"
        }
    }
    
    /**
     * 获取所有活跃卡片
     */
    fun getActiveCards(): List<FormCardState> {
        return cardStates.values.filter { !isTerminalState(it.cardId) }
    }
    
    /**
     * 清理过期的终态卡片
     */
    fun cleanupTerminalCards(maxAgeMs: Long = 3600000L) {  // 默认1小时
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        cardStates.forEach { (cardId, state) ->
            if (isTerminalState(cardId) && (now - state.lastUpdateTime) > maxAgeMs) {
                toRemove.add(cardId)
            }
        }
        
        toRemove.forEach { cardId ->
            removeCard(cardId)
        }
        
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${toRemove.size} terminal cards")
        }
    }
}

/**
 * 表单卡片状态
 */
data class FormCardState(
    val cardId: String,
    val formId: String,
    var currentState: Int,
    val createdTime: Long,
    var lastUpdateTime: Long = createdTime,
    val stateHistory: MutableList<StateTransition> = mutableListOf()
)

/**
 * 状态转换记录
 */
data class StateTransition(
    val fromState: Int,
    val toState: Int,
    val timestamp: Long,
    val reason: String? = null
)

/**
 * 表单卡片状态监听器
 */
interface FormCardStateListener {
    /**
     * 状态变化
     * 
     * @param cardId 卡片ID
     * @param newState 新状态
     * @param oldState 旧状态（可能为null）
     */
    fun onStateChanged(cardId: String, newState: Int, oldState: Int?)
}

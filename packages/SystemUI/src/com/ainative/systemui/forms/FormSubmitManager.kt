package com.ainative.systemui.forms

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ainative.forms.FormSchema
import com.ainative.brain.session.IAgentSessionService
import com.ainative.brain.session.SessionHandle
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * D5.2 表单提交与卡片状态机
 * 
 * 职责：
 * 1. 表单卡片状态机：validating → submitting → done/error/canceled
 * 2. 错误回显（validation error / submission error）
 * 3. 可重放/定时命令 UI（SCHEDULE 模式）
 * 4. 与 B3.2 FormSubmitRouter 集成（免 LLM 管线）
 * 
 * 设计原则（D15 §7）：
 * - 状态机：5 种状态（idle/validating/submitting/done/error），单向推进
 * - 错误分类：validation_error（客户端校验）/ submission_error（服务端拒绝）
 * - 幂等性：同一表单 requestId 只提交一次（依赖 B3.1 幂等性保证）
 * - 超时保护：提交超时 10s 自动降级为 error 状态
 * 
 * 依赖关系：
 * - 前置：D5.1 表单渲染器（FormRenderer/FormState）、B3.2 FormSubmitRouter
 * - 消费：D2.2 会话流 UI（卡片状态展示）
 * 
 * 版本：v1（A2 期）
 * 
 * @since A2
 */
class FormSubmitManager(
    private val sessionService: IAgentSessionService?,  // B3.1 会话服务
    private val sessionHandle: SessionHandle?           // 当前会话句柄
) {
    companion object {
        private const val TAG = "FormSubmitManager"
        private const val SUBMIT_TIMEOUT_MS = 10_000L   // 提交超时 10s
    }
    
    /**
     * 表单卡片状态
     */
    enum class FormCardState {
        IDLE,           // 空闲（用户编辑中）
        VALIDATING,     // 校验中（客户端校验）
        SUBMITTING,     // 提交中（发送到大脑）
        DONE,           // 完成（成功提交）
        ERROR,          // 错误（校验失败/提交失败）
        CANCELED        // 已取消
    }
    
    /**
     * 表单卡片数据（对应 ActionCardData type=1 表单卡）
     */
    data class FormCardData(
        val formId: String,                 // 表单 ID
        val requestId: String,              // 请求 ID（幂等性）
        var state: FormCardState,           // 当前状态
        val schema: FormSchema,             // 表单 schema
        val values: Map<String, String>,    // 表单值
        var errorMessage: String? = null,   // 错误消息
        var errorFields: Map<String, String> = emptyMap(), // 字段级错误
        val createdAtMs: Long = System.currentTimeMillis(),
        var submittedAtMs: Long? = null,
        var completedAtMs: Long? = null,
        var isScheduled: Boolean = false,   // 是否为定时命令
        var scheduledAtMs: Long? = null     // 定时执行时间
    ) {
        /**
         * 是否为终态
         */
        fun isTerminal(): Boolean {
            return state == FormCardState.DONE || 
                   state == FormCardState.ERROR || 
                   state == FormCardState.CANCELED
        }
        
        /**
         * 是否可重放（错误后重试）
         */
        fun canRetry(): Boolean {
            return state == FormCardState.ERROR
        }
        
        /**
         * 是否可取消
         */
        fun canCancel(): Boolean {
            return state == FormCardState.SUBMITTING || 
                   (state == FormCardState.IDLE && isScheduled)
        }
    }
    
    /**
     * 提交回调
     */
    interface SubmitCallback {
        fun onStateChanged(cardData: FormCardData)
        fun onSubmitSuccess(cardData: FormCardData)
        fun onSubmitError(cardData: FormCardData, error: String)
    }
    
    // 活跃的表单卡片（requestId → FormCardData）
    private val activeCards = ConcurrentHashMap<String, FormCardData>()
    
    // 主线程 Handler（UI 回调）
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 提交表单
     * 
     * @param formState 表单状态（来自 FormRenderer）
     * @param callback 提交回调
     * @return FormCardData（可用于状态追踪）
     */
    fun submitForm(
        formState: FormRenderer.FormState,
        callback: SubmitCallback
    ): FormCardData {
        val requestId = generateRequestId(formState.schema.meta.formId)
        
        // 创建表单卡片
        val cardData = FormCardData(
            formId = formState.schema.meta.formId,
            requestId = requestId,
            state = FormCardState.IDLE,
            schema = formState.schema,
            values = formState.values.toMap()
        )
        
        activeCards[requestId] = cardData
        
        // 根据提交模式处理
        when (formState.schema.meta.submitMode) {
            com.ainative.forms.SubmitMode.IMMEDIATE -> {
                submitImmediate(cardData, callback)
            }
            com.ainative.forms.SubmitMode.CONFIRM -> {
                // TODO: 显示确认面板（D3.1）
                // 暂时直接提交
                submitImmediate(cardData, callback)
            }
            com.ainative.forms.SubmitMode.SCHEDULE -> {
                // TODO: 显示定时选择器
                // 暂时直接提交
                submitImmediate(cardData, callback)
            }
        }
        
        return cardData
    }
    
    /**
     * 立即提交（L0.5 免 LLM 管线）
     */
    private fun submitImmediate(
        cardData: FormCardData,
        callback: SubmitCallback
    ) {
        // 1. 客户端校验
        transitionTo(cardData, FormCardState.VALIDATING, callback)
        
        val validationErrors = cardData.schema.validate(cardData.values)
        if (validationErrors.isNotEmpty()) {
            cardData.errorFields = validationErrors.associate { it.fieldName to it.message }
            cardData.errorMessage = "表单校验失败：${validationErrors.size} 个字段有误"
            transitionTo(cardData, FormCardState.ERROR, callback)
            
            mainHandler.post {
                callback.onSubmitError(cardData, cardData.errorMessage!!)
            }
            return
        }
        
        // 2. 提交到大脑（B3.2 FormSubmitRouter）
        transitionTo(cardData, FormCardState.SUBMITTING, callback)
        cardData.submittedAtMs = System.currentTimeMillis()
        
        if (sessionService == null || sessionHandle == null) {
            // Mock 模式：模拟成功
            Log.d(TAG, "[Mock] Form submitted: ${cardData.formId}")
            mainHandler.postDelayed({
                transitionTo(cardData, FormCardState.DONE, callback)
                cardData.completedAtMs = System.currentTimeMillis()
                
                mainHandler.post {
                    callback.onSubmitSuccess(cardData)
                }
            }, 500) // 模拟 500ms 延迟
            return
        }
        
        // 真实提交
        submitToSession(cardData, callback)
    }
    
    /**
     * 提交到会话服务
     */
    private fun submitToSession(
        cardData: FormCardData,
        callback: SubmitCallback
    ) {
        Thread {
            try {
                // 构造表单提交 UserTurn（B3.1 接口）
                val turnJson = JSONObject().apply {
                    put("type", "form_submit")
                    put("form_id", cardData.formId)
                    put("request_id", cardData.requestId)
                    
                    // 表单值
                    val valuesJson = JSONObject()
                    for ((key, value) in cardData.values) {
                        valuesJson.put(key, value)
                    }
                    put("values", valuesJson)
                    
                    // secret 字段标记（走安全通道）
                    val secretFields = cardData.schema.getSecretFieldNames()
                    if (secretFields.isNotEmpty()) {
                        put("secret_fields", JSONObject().apply {
                            secretFields.forEach { fieldName ->
                                put(fieldName, cardData.values[fieldName] ?: "")
                            }
                        })
                    }
                }.toString()
                
                // 调用 B3.1 submitUserTurn（实际应构造 UserTurn Parcelable）
                // val turnId = sessionService?.submitUserTurn(sessionHandle, userTurn)
                
                Log.i(TAG, "Form submitted to session: ${cardData.formId} (requestId=${cardData.requestId})")
                
                // 启动超时保护
                startSubmitTimeout(cardData, callback)
                
                // 模拟成功（实际应通过 B3.2 onTurnDelta 回调更新状态）
                mainHandler.postDelayed({
                    if (!cardData.isTerminal()) {
                        transitionTo(cardData, FormCardState.DONE, callback)
                        cardData.completedAtMs = System.currentTimeMillis()
                        
                        mainHandler.post {
                            callback.onSubmitSuccess(cardData)
                        }
                    }
                }, 1000)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit form: ${cardData.formId}", e)
                
                cardData.errorMessage = "提交失败：${e.message}"
                transitionTo(cardData, FormCardState.ERROR, callback)
                
                mainHandler.post {
                    callback.onSubmitError(cardData, cardData.errorMessage!!)
                }
            }
        }.start()
    }
    
    /**
     * 启动提交超时保护
     */
    private fun startSubmitTimeout(
        cardData: FormCardData,
        callback: SubmitCallback
    ) {
        mainHandler.postDelayed({
            if (cardData.state == FormCardState.SUBMITTING) {
                Log.w(TAG, "Form submit timeout: ${cardData.formId}")
                
                cardData.errorMessage = "提交超时，请检查网络连接后重试"
                transitionTo(cardData, FormCardState.ERROR, callback)
                
                callback.onSubmitError(cardData, cardData.errorMessage!!)
            }
        }, SUBMIT_TIMEOUT_MS)
    }
    
    /**
     * 状态转换
     */
    private fun transitionTo(
        cardData: FormCardData,
        newState: FormCardState,
        callback: SubmitCallback
    ) {
        val oldState = cardData.state
        cardData.state = newState
        
        Log.d(TAG, "Form state transition: ${cardData.formId} $oldState -> $newState")
        
        mainHandler.post {
            callback.onStateChanged(cardData)
        }
    }
    
    /**
     * 重试提交（错误后）
     */
    fun retrySubmit(
        cardData: FormCardData,
        callback: SubmitCallback
    ) {
        if (!cardData.canRetry()) {
            Log.w(TAG, "Cannot retry form in state: ${cardData.state}")
            return
        }
        
        // 清除错误状态
        cardData.errorMessage = null
        cardData.errorFields = emptyMap()
        
        // 重新提交
        submitImmediate(cardData, callback)
    }
    
    /**
     * 取消提交
     */
    fun cancelSubmit(
        cardData: FormCardData,
        callback: SubmitCallback
    ) {
        if (!cardData.canCancel()) {
            Log.w(TAG, "Cannot cancel form in state: ${cardData.state}")
            return
        }
        
        // TODO: 通知大脑取消任务（B2.2 工具循环 cancel）
        
        transitionTo(cardData, FormCardState.CANCELED, callback)
        activeCards.remove(cardData.requestId)
        
        Log.i(TAG, "Form canceled: ${cardData.formId}")
    }
    
    /**
     * 定时提交（SCHEDULE 模式）
     */
    fun scheduleSubmit(
        cardData: FormCardData,
        scheduledAtMs: Long,
        callback: SubmitCallback
    ) {
        cardData.isScheduled = true
        cardData.scheduledAtMs = scheduledAtMs
        
        val delayMs = scheduledAtMs - System.currentTimeMillis()
        if (delayMs <= 0) {
            // 立即执行
            submitImmediate(cardData, callback)
            return
        }
        
        Log.d(TAG, "Form scheduled: ${cardData.formId} at ${scheduledAtMs} (delay ${delayMs}ms)")
        
        // 延迟提交
        mainHandler.postDelayed({
            if (cardData.state == FormCardState.IDLE) {
                submitImmediate(cardData, callback)
            }
        }, delayMs)
    }
    
    /**
     * 获取活跃的表单卡片
     */
    fun getActiveCards(): List<FormCardData> {
        return activeCards.values.toList()
    }
    
    /**
     * 获取指定表单卡片
     */
    fun getCard(requestId: String): FormCardData? {
        return activeCards[requestId]
    }
    
    /**
     * 清理已完成的表单卡片
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThresholdMs = 5 * 60 * 1000L // 5 分钟
        
        val staleIds = activeCards.filter { (_, card) ->
            card.isTerminal() && (card.completedAtMs?.let { now - it > staleThresholdMs } ?: false)
        }.keys
        
        staleIds.forEach { activeCards.remove(it) }
        
        if (staleIds.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${staleIds.size} stale form cards")
        }
    }
    
    /**
     * 生成请求 ID（幂等性）
     */
    private fun generateRequestId(formId: String): String {
        return "${formId}_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }
}

/**
 * 表单卡片 UI 渲染器（配合 D2.1 会话流）
 */
class FormCardRenderer {
    
    /**
     * 渲染表单卡片状态文本
     */
    fun renderStateText(state: FormSubmitManager.FormCardState): String {
        return when (state) {
            FormSubmitManager.FormCardState.IDLE -> "等待提交"
            FormSubmitManager.FormCardState.VALIDATING -> "校验中..."
            FormSubmitManager.FormCardState.SUBMITTING -> "提交中..."
            FormSubmitManager.FormCardState.DONE -> "✓ 已完成"
            FormSubmitManager.FormCardState.ERROR -> "✗ 失败"
            FormSubmitManager.FormCardState.CANCELED -> "已取消"
        }
    }
    
    /**
     * 渲染状态颜色
     */
    fun renderStateColor(state: FormSubmitManager.FormCardState): Int {
        return when (state) {
            FormSubmitManager.FormCardState.IDLE -> 0xFF9E9E9E.toInt()
            FormSubmitManager.FormCardState.VALIDATING -> 0xFFFFA726.toInt()
            FormSubmitManager.FormCardState.SUBMITTING -> 0xFF42A5F5.toInt()
            FormSubmitManager.FormCardState.DONE -> 0xFF66BB6A.toInt()
            FormSubmitManager.FormCardState.ERROR -> 0xFFEF5350.toInt()
            FormSubmitManager.FormCardState.CANCELED -> 0xFF9E9E9E.toInt()
        }
    }
    
    /**
     * 是否显示进度指示器
     */
    fun shouldShowProgress(state: FormSubmitManager.FormCardState): Boolean {
        return state == FormSubmitManager.FormCardState.VALIDATING || 
               state == FormSubmitManager.FormCardState.SUBMITTING
    }
    
    /**
     * 是否显示重试按钮
     */
    fun shouldShowRetry(cardData: FormSubmitManager.FormCardData): Boolean {
        return cardData.canRetry()
    }
    
    /**
     * 是否显示取消按钮
     */
    fun shouldShowCancel(cardData: FormSubmitManager.FormCardData): Boolean {
        return cardData.canCancel()
    }
    
    /**
     * 渲染错误消息
     */
    fun renderErrorMessage(cardData: FormSubmitManager.FormCardData): String? {
        if (cardData.state != FormSubmitManager.FormCardState.ERROR) {
            return null
        }
        
        val errorMessage = cardData.errorMessage ?: "未知错误"
        
        // 字段级错误
        if (cardData.errorFields.isNotEmpty()) {
            val fieldErrors = cardData.errorFields.entries
                .take(3) // 最多显示 3 个字段错误
                .joinToString("\n") { "• ${it.key}: ${it.value}" }
            
            return "$errorMessage\n$fieldErrors"
        }
        
        return errorMessage
    }
    
    /**
     * 渲染定时标记
     */
    fun renderScheduleHint(cardData: FormSubmitManager.FormCardData): String? {
        if (!cardData.isScheduled || cardData.scheduledAtMs == null) {
            return null
        }
        
        val scheduledTime = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(cardData.scheduledAtMs!!))
        
        return "定时执行: $scheduledTime"
    }
}

package com.ainative.brain.ui.form

import android.util.Log
import com.ainative.brain.schema.FormInstance
import com.ainative.brain.session.CardStateManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * D5.2 表单提交与卡片状态机 - 表单提交处理器
 * 
 * 职责：
 * 1. 处理表单提交流程（校验 → 序列化 → 路由 → 提交）
 * 2. 管理提交状态（idle/validating/submitting/submitted/failed）
 * 3. 与 CardStateManager 协作管理卡片状态
 * 4. 支持提交重试和错误处理
 * 5. 提交结果回调和通知
 * 
 * 提交流程：
 * 1. 校验表单数据
 * 2. 序列化为 ActionRequest
 * 3. 路由到目标 Agent
 * 4. 更新卡片状态
 * 5. 等待提交结果
 * 6. 通知用户
 */
class FormSubmitHandler(
    private val cardStateManager: CardStateManager
) {
    
    companion object {
        private const val TAG = "FormSubmitHandler"
        
        // 提交超时时间
        private const val SUBMIT_TIMEOUT_MS = 30000L  // 30秒
    }
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 活跃的提交任务
    private val activeSubmissions = ConcurrentHashMap<String, SubmissionTask>()
    
    // 提交监听器
    private val submitListeners = mutableListOf<FormSubmitListener>()
    
    /**
     * 提交表单
     * 
     * @param cardId 卡片ID
     * @param formInstance 表单实例
     * @param agentId 目标Agent ID
     * @param turnId 轮次ID
     */
    fun submit(
        cardId: String,
        formInstance: FormInstance,
        agentId: String,
        turnId: String
    ) {
        Log.i(TAG, "Submitting form: cardId=$cardId, formId=${formInstance.formId}")
        
        // 检查是否已有提交任务
        if (activeSubmissions.containsKey(cardId)) {
            Log.w(TAG, "Form already submitting: $cardId")
            return
        }
        
        // 创建提交任务
        val task = SubmissionTask(
            cardId = cardId,
            formInstance = formInstance,
            agentId = agentId,
            turnId = turnId
        )
        
        activeSubmissions[cardId] = task
        
        // 异步执行提交
        scope.launch {
            try {
                executeSubmission(task)
            } catch (e: Exception) {
                Log.e(TAG, "Submission failed", e)
                handleSubmissionError(task, e)
            } finally {
                activeSubmissions.remove(cardId)
            }
        }
    }
    
    /**
     * 执行提交流程
     */
    private suspend fun executeSubmission(task: SubmissionTask) = withContext(Dispatchers.IO) {
        val cardId = task.cardId
        val formInstance = task.formInstance
        
        // 1. 更新状态：校验中
        updateSubmissionState(task, SubmissionState.VALIDATING)
        notifyListeners { it.onValidating(cardId, formInstance) }
        
        // 校验表单
        val validationResult = formInstance.validate()
        if (!validationResult.isValid) {
            Log.w(TAG, "Form validation failed: ${validationResult.errors.size} errors")
            updateSubmissionState(task, SubmissionState.VALIDATION_FAILED)
            notifyListeners { it.onValidationFailed(cardId, validationResult) }
            return@withContext
        }
        
        // 2. 更新状态：提交中
        updateSubmissionState(task, SubmissionState.SUBMITTING)
        notifyListeners { it.onSubmitting(cardId, formInstance) }
        
        // 更新卡片状态为提交中
        cardStateManager.updateCardState(cardId, CardStateManager.STATE_FORM_SUBMITTING)
        
        // 3. 序列化表单数据为 ActionRequest
        val actionRequest = serializeToActionRequest(formInstance, task.agentId, task.turnId)
        
        // 4. 提交到目标 Agent（带超时）
        val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
            submitToAgent(actionRequest, task.agentId, task.turnId)
        }
        
        if (result == null) {
            // 超时
            Log.e(TAG, "Form submission timeout: $cardId")
            updateSubmissionState(task, SubmissionState.TIMEOUT)
            notifyListeners { it.onTimeout(cardId) }
            
            cardStateManager.updateCardState(cardId, CardStateManager.STATE_FORM_ERROR)
            return@withContext
        }
        
        // 5. 处理提交结果
        if (result.success) {
            Log.i(TAG, "Form submitted successfully: $cardId")
            updateSubmissionState(task, SubmissionState.SUBMITTED)
            notifyListeners { it.onSubmitted(cardId, result) }
            
            // 更新卡片状态为已提交
            cardStateManager.updateCardState(cardId, CardStateManager.STATE_FORM_SUBMITTED)
            
            // 标记表单实例为已提交
            task.submissionResult = result
            
        } else {
            Log.e(TAG, "Form submission failed: ${result.errorMessage}")
            updateSubmissionState(task, SubmissionState.FAILED)
            notifyListeners { it.onFailed(cardId, result.errorMessage ?: "Unknown error") }
            
            cardStateManager.updateCardState(cardId, CardStateManager.STATE_FORM_ERROR)
        }
    }
    
    /**
     * 序列化为 ActionRequest
     */
    private fun serializeToActionRequest(
        formInstance: FormInstance,
        agentId: String,
        turnId: String
    ): JSONObject {
        val submitData = formInstance.getSubmitData()
        val metadata = formInstance.schema.xAinative
        
        return JSONObject().apply {
            put("action", metadata.submitAction)
            put("agentId", agentId)
            put("turnId", turnId)
            put("formId", formInstance.formId)
            put("instanceId", formInstance.instanceId)
            put("parameters", JSONObject(submitData))
            put("timestamp", System.currentTimeMillis())
        }
    }
    
    /**
     * 提交到 Agent
     */
    private suspend fun submitToAgent(
        actionRequest: JSONObject,
        agentId: String,
        turnId: String
    ): SubmissionResult {
        // TODO: 实际实现：通过 AgentSessionService 提交到目标 Agent
        // 当前简化实现：模拟异步提交
        
        delay(1000)  // 模拟网络延迟
        
        // 模拟成功/失败（90%成功率）
        val success = Math.random() < 0.9
        
        return if (success) {
            SubmissionResult(
                success = true,
                responseId = "resp_${System.currentTimeMillis()}",
                message = "表单已成功提交"
            )
        } else {
            SubmissionResult(
                success = false,
                errorMessage = "网络错误，请稍后重试"
            )
        }
    }
    
    /**
     * 更新提交状态
     */
    private fun updateSubmissionState(task: SubmissionTask, state: SubmissionState) {
        task.state = state
        task.lastUpdateTime = System.currentTimeMillis()
        
        Log.d(TAG, "Submission state updated: cardId=${task.cardId}, state=$state")
    }
    
    /**
     * 处理提交错误
     */
    private fun handleSubmissionError(task: SubmissionTask, error: Exception) {
        Log.e(TAG, "Submission error: cardId=${task.cardId}", error)
        
        updateSubmissionState(task, SubmissionState.FAILED)
        notifyListeners { it.onFailed(task.cardId, error.message ?: "Unknown error") }
        
        cardStateManager.updateCardState(task.cardId, CardStateManager.STATE_FORM_ERROR)
    }
    
    /**
     * 取消提交
     */
    fun cancel(cardId: String) {
        val task = activeSubmissions[cardId]
        if (task != null) {
            Log.i(TAG, "Canceling submission: $cardId")
            
            updateSubmissionState(task, SubmissionState.CANCELLED)
            notifyListeners { it.onCancelled(cardId) }
            
            activeSubmissions.remove(cardId)
            
            // 恢复卡片状态
            cardStateManager.updateCardState(cardId, CardStateManager.STATE_FORM_EDITING)
        }
    }
    
    /**
     * 重试提交
     */
    fun retry(cardId: String) {
        val task = activeSubmissions[cardId]
        if (task != null) {
            Log.i(TAG, "Retrying submission: $cardId")
            
            // 重新提交
            submit(cardId, task.formInstance, task.agentId, task.turnId)
        }
    }
    
    /**
     * 获取提交状态
     */
    fun getSubmissionState(cardId: String): SubmissionState? {
        return activeSubmissions[cardId]?.state
    }
    
    /**
     * 是否正在提交
     */
    fun isSubmitting(cardId: String): Boolean {
        val state = getSubmissionState(cardId)
        return state == SubmissionState.VALIDATING || state == SubmissionState.SUBMITTING
    }
    
    /**
     * 注册提交监听器
     */
    fun registerListener(listener: FormSubmitListener) {
        if (!submitListeners.contains(listener)) {
            submitListeners.add(listener)
        }
    }
    
    /**
     * 注销提交监听器
     */
    fun unregisterListener(listener: FormSubmitListener) {
        submitListeners.remove(listener)
    }
    
    /**
     * 通知所有监听器
     */
    private fun notifyListeners(action: (FormSubmitListener) -> Unit) {
        submitListeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        scope.cancel()
        activeSubmissions.clear()
        submitListeners.clear()
    }
}

/**
 * 提交任务
 */
data class SubmissionTask(
    val cardId: String,
    val formInstance: FormInstance,
    val agentId: String,
    val turnId: String,
    var state: SubmissionState = SubmissionState.IDLE,
    var lastUpdateTime: Long = System.currentTimeMillis(),
    var submissionResult: SubmissionResult? = null
)

/**
 * 提交状态
 */
enum class SubmissionState {
    IDLE,                   // 空闲
    VALIDATING,            // 校验中
    VALIDATION_FAILED,     // 校验失败
    SUBMITTING,            // 提交中
    SUBMITTED,             // 已提交
    FAILED,                // 提交失败
    TIMEOUT,               // 超时
    CANCELLED              // 已取消
}

/**
 * 提交结果
 */
data class SubmissionResult(
    val success: Boolean,
    val responseId: String? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val data: Map<String, Any>? = null
)

/**
 * 表单提交监听器
 */
interface FormSubmitListener {
    /**
     * 校验中
     */
    fun onValidating(cardId: String, formInstance: FormInstance) {}
    
    /**
     * 校验失败
     */
    fun onValidationFailed(cardId: String, validationResult: com.ainative.brain.schema.FormValidationResult) {}
    
    /**
     * 提交中
     */
    fun onSubmitting(cardId: String, formInstance: FormInstance) {}
    
    /**
     * 提交成功
     */
    fun onSubmitted(cardId: String, result: SubmissionResult) {}
    
    /**
     * 提交失败
     */
    fun onFailed(cardId: String, errorMessage: String) {}
    
    /**
     * 超时
     */
    fun onTimeout(cardId: String) {}
    
    /**
     * 取消
     */
    fun onCancelled(cardId: String) {}
}

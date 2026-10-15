package com.ainative.brain.session

import android.util.Log
import com.ainative.brain.intent.IntentReceiver
import org.json.JSONObject

/**
 * L0.5 表单路由器
 * 
 * 职责：
 * 1. 识别 L0.5 级别的表单提交（kind=4, form_submit）
 * 2. 免 LLM 推理，直接调用工具执行
 * 3. 快速响应简单表单操作
 * 
 * B3.2 任务：L0.5 表单提交走免 LLM 管线路由
 * 
 * L0.5 定义：
 * - 用户已完成表单填写，无需意图理解
 * - 参数已结构化，无需 LLM 解析
 * - 直接映射到工具调用，无需规划
 */
class FormRouter(
    private val streamingPushManager: StreamingPushManager
) {
    
    companion object {
        private const val TAG = "FormRouter"
        
        // 输入类型
        const val KIND_TEXT = 0
        const val KIND_VOICE = 1
        const val KIND_ATTACHMENT = 2
        const val KIND_COMMAND = 3
        const val KIND_FORM_SUBMIT = 4
    }
    
    /**
     * 检查是否为 L0.5 表单提交
     * 
     * @param kind 输入类型
     * @param userInput 用户输入
     * @return true 如果是 L0.5 表单提交
     */
    fun isL05FormSubmit(kind: Int, userInput: String): Boolean {
        if (kind != KIND_FORM_SUBMIT) {
            return false
        }
        
        // 验证是否为有效的表单提交 JSON
        return try {
            val json = JSONObject(userInput)
            json.has("form_id") && json.has("fields")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 处理 L0.5 表单提交（免 LLM 管线）
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID
     * @param userInput 用户输入（JSON 格式的表单数据）
     * @return 处理结果
     */
    fun handleFormSubmit(
        sessionId: String,
        turnId: String,
        userInput: String
    ): FormSubmitResult {
        Log.i(TAG, "Handling L0.5 form submit: sessionId=$sessionId, turnId=$turnId")
        
        try {
            // 1. 解析表单数据
            val formData = parseFormData(userInput)
            
            Log.d(TAG, "Form parsed: formId=${formData.formId}, action=${formData.action}")
            
            // 2. 验证表单数据
            val validationResult = validateFormData(formData)
            if (!validationResult.isValid) {
                Log.w(TAG, "Form validation failed: ${validationResult.errors}")
                return FormSubmitResult(
                    success = false,
                    error = "Validation failed: ${validationResult.errors.joinToString(", ")}"
                )
            }
            
            // 3. 映射到工具调用
            val toolCall = mapFormToToolCall(formData)
            
            Log.i(TAG, "Form mapped to tool: ${toolCall.toolName}")
            
            // 4. 推送进度卡片
            pushProgressCard(sessionId, turnId, toolCall.toolName)
            
            // 5. 执行工具调用（免 LLM）
            val executionResult = executeToolDirectly(sessionId, turnId, toolCall)
            
            // 6. 推送完成卡片
            if (executionResult.success) {
                pushCompletionCard(sessionId, turnId, toolCall.toolName, executionResult)
            } else {
                pushErrorCard(sessionId, turnId, toolCall.toolName, executionResult.error)
            }
            
            Log.i(TAG, "Form submit completed: success=${executionResult.success}")
            
            return FormSubmitResult(
                success = executionResult.success,
                output = executionResult.output,
                error = executionResult.error
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle form submit", e)
            return FormSubmitResult(
                success = false,
                error = "Internal error: ${e.message}"
            )
        }
    }
    
    /**
     * 解析表单数据
     */
    private fun parseFormData(userInput: String): FormData {
        val json = JSONObject(userInput)
        
        val formId = json.getString("form_id")
        val action = json.getString("action")
        
        val fieldsJson = json.getJSONObject("fields")
        val fields = mutableMapOf<String, Any>()
        
        fieldsJson.keys().forEach { key ->
            fields[key] = fieldsJson.get(key)
        }
        
        return FormData(
            formId = formId,
            action = action,
            fields = fields
        )
    }
    
    /**
     * 验证表单数据
     */
    private fun validateFormData(formData: FormData): ValidationResult {
        val errors = mutableListOf<String>()
        
        // 验证 formId 非空
        if (formData.formId.isEmpty()) {
            errors.add("form_id is required")
        }
        
        // 验证 action 非空
        if (formData.action.isEmpty()) {
            errors.add("action is required")
        }
        
        // 验证 fields 非空
        if (formData.fields.isEmpty()) {
            errors.add("fields cannot be empty")
        }
        
        // TODO: 根据 formId 加载表单定义，验证必填字段
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
    
    /**
     * 映射表单到工具调用
     */
    private fun mapFormToToolCall(formData: FormData): ToolCall {
        // 简化实现：将 action 直接映射到工具名称
        val toolName = formData.action
        
        // 将表单字段作为工具参数
        val parameters = formData.fields
        
        return ToolCall(
            toolName = toolName,
            parameters = parameters
        )
    }
    
    /**
     * 直接执行工具调用（绕过规划器）
     */
    private fun executeToolDirectly(
        sessionId: String,
        turnId: String,
        toolCall: ToolCall
    ): ToolExecutionResult {
        Log.d(TAG, "Executing tool directly: ${toolCall.toolName}")
        
        // TODO: 调用 ToolLoop 执行工具
        // val result = toolLoop.executeTool(toolCall.toolName, toolCall.parameters)
        
        // 临时模拟执行
        return ToolExecutionResult(
            success = true,
            output = "Tool ${toolCall.toolName} executed successfully (simulated)",
            auditRef = "audit_${turnId}_${System.currentTimeMillis()}",
            canUndo = false
        )
    }
    
    /**
     * 推送进度卡片
     */
    private fun pushProgressCard(sessionId: String, turnId: String, toolName: String) {
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = "${turnId}_${toolName}",
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_PROGRESS,
            title = "Executing",
            summaryMasked = "Processing form: $toolName",
            auditRef = null,
            canUndo = false
        )
    }
    
    /**
     * 推送完成卡片
     */
    private fun pushCompletionCard(
        sessionId: String,
        turnId: String,
        toolName: String,
        result: ToolExecutionResult
    ) {
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = "${turnId}_${toolName}",
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_DONE,
            title = "Completed",
            summaryMasked = result.output?.take(100) ?: "Success",
            auditRef = result.auditRef,
            canUndo = result.canUndo
        )
    }
    
    /**
     * 推送错误卡片
     */
    private fun pushErrorCard(sessionId: String, turnId: String, toolName: String, error: String?) {
        streamingPushManager.pushActionCard(
            sessionId = sessionId,
            cardId = "${turnId}_${toolName}_error",
            turnId = turnId,
            type = StreamingPushManager.ACTION_TYPE_NEEDS_ATTENTION,
            title = "Error",
            summaryMasked = error?.take(100) ?: "Unknown error",
            auditRef = null,
            canUndo = false
        )
    }
}

/**
 * 表单数据
 */
data class FormData(
    val formId: String,
    val action: String,
    val fields: Map<String, Any>
)

/**
 * 验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

/**
 * 工具调用
 */
data class ToolCall(
    val toolName: String,
    val parameters: Map<String, Any>
)

/**
 * 工具执行结果
 */
data class ToolExecutionResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val auditRef: String? = null,
    val canUndo: Boolean = false
)

/**
 * 表单提交结果
 */
data class FormSubmitResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null
)

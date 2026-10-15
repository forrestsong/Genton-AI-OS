// FormSubmitRouter.kt
// B3.2 表单提交路由：L0.5 免 LLM 管线（语义直连执行层）
package com.genton.brain.session

import android.util.Log
import com.genton.agent.FormPayload
import com.genton.brain.BrainApplication
import com.genton.brain.toolloop.ToolLoop
import com.genton.brain.toolloop.ExecutionContext
import com.genton.brain.toolloop.GoalNode
import com.genton.brain.toolloop.ExecutionResult
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 表单提交路由器
 * 
 * 核心职责：
 * 1. L0.5 表单提交走免 LLM 管线：表单 → 工具参数 → 直接执行
 * 2. 语义直连执行层：跳过规划器（Planner），直接调用 ToolLoop
 * 3. 表单验证：字段完整性、类型校验、必填项检查
 * 4. 结果反馈：执行成功 → 更新表单状态 → 推送动作卡
 * 
 * 设计原则：
 * - L0.5 表单：用户填写的表单 → 确定性工具调用（无需 LLM 推理）
 * - 执行路径：FormPayload → ToolParameters → ToolLoop.execute()
 * - 失败处理：验证失败 → 表单错误提示；执行失败 → 重试机制/回退
 */
class FormSubmitRouter(
    private val application: BrainApplication
) {
    companion object {
        private const val TAG = "FormSubmitRouter"
        
        // 表单状态
        const val FORM_STATE_IDLE = 0        // 空闲（未提交）
        const val FORM_STATE_VALIDATING = 1  // 验证中
        const val FORM_STATE_SUBMITTING = 2  // 提交中
        const val FORM_STATE_SUCCESS = 3     // 提交成功
        const val FORM_STATE_ERROR = 4       // 提交失败
    }
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 提交表单（L0.5 免 LLM 直连）
     * 
     * @param sessionId 会话 ID
     * @param turnId 轮次 ID
     * @param form 表单负载
     * @param callback 提交结果回调
     */
    fun submitForm(
        sessionId: String,
        turnId: String,
        form: FormPayload,
        callback: FormSubmitCallback
    ) {
        scope.launch {
            try {
                // 1. 验证表单
                val validationResult = validateForm(form)
                if (!validationResult.isValid) {
                    callback.onValidationFailed(validationResult.errors)
                    return@launch
                }
                
                // 2. 转换为工具参数
                val toolParameters = convertFormToToolParameters(form)
                
                // 3. 构建执行上下文
                val context = ExecutionContext(
                    sessionId = sessionId,
                    turnId = turnId,
                    agentId = "system_form_submit",
                    maxDepth = 1,  // 单步执行
                    tokenBudget = 0,  // 无 token 预算（免 LLM）
                    energyBudget = 100,  // 仅执行预算
                    allowDmi = true
                )
                
                // 4. 构建目标节点（单个工具调用）
                val goalNode = GoalNode(
                    id = "form_submit_${form.formId}",
                    goalText = form.actionSemantic ?: "执行表单提交",
                    toolName = form.toolName,
                    toolParameters = toolParameters,
                    dependencies = emptyList()
                )
                
                // 5. 直接调用 ToolLoop 执行（跳过规划器）
                val toolLoop = application.getToolLoop()
                val result = toolLoop.executeGoal(context, goalNode)
                
                // 6. 处理执行结果
                if (result.status == ToolLoop.EXECUTION_SUCCESS) {
                    callback.onSubmitSuccess(result)
                } else {
                    callback.onSubmitFailed(result.failureReason ?: "执行失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit form: sessionId=$sessionId, formId=${form.formId}", e)
                callback.onSubmitFailed("系统错误：${e.message}")
            }
        }
    }
    
    /**
     * 取消表单提交
     * 
     * @param sessionId 会话 ID
     * @param turnId 轮次 ID
     */
    fun cancelFormSubmit(sessionId: String, turnId: String) {
        // 取消协程作用域中的任务
        // 注意：这里应该维护一个 turnId -> Job 的映射表，暂时简化处理
        Log.d(TAG, "Cancel form submit: sessionId=$sessionId, turnId=$turnId")
    }
    
    /**
     * 清理资源
     */
    fun destroy() {
        scope.cancel()
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 验证表单
     * 
     * @param form 表单负载
     * @return 验证结果
     */
    private fun validateForm(form: FormPayload): ValidationResult {
        val errors = mutableListOf<String>()
        
        // 1. 检查必填字段
        if (form.toolName.isNullOrEmpty()) {
            errors.add("toolName 为空")
        }
        
        if (form.fields == null || form.fields.isEmpty()) {
            errors.add("表单字段为空")
        }
        
        // 2. 检查是否可提交（前端已完成校验）
        if (!form.canSubmit) {
            errors.add("表单校验未通过，不可提交")
        }
        
        // 3. 检查必填字段（二次校验）
        form.fields?.forEach { field ->
            if (field.required && field.value.isNullOrEmpty()) {
                errors.add("必填字段 ${field.label} 未填写")
            }
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
    
    /**
     * 转换表单为工具参数
     * 
     * @param form 表单负载
     * @return 工具参数（JSON 字符串）
     */
    private fun convertFormToToolParameters(form: FormPayload): String {
        // 将表单字段转换为 JSON 对象
        val params = JSONObject()
        
        form.fields?.forEach { field ->
             val value = field.value
             
             // 根据字段类型转换值（对齐 FormField.widgetType）
             when (field.widgetType) {
                 0 -> params.put(field.key, value)  // text
                 1 -> params.put(field.key, value?.toIntOrNull() ?: 0)  // number
                 2 -> params.put(field.key, value)  // date
                 3 -> params.put(field.key, value)  // contact
                 4 -> params.put(field.key, value)  // file
                 5 -> params.put(field.key, value)  // enum
                 6 -> params.put(field.key, value?.toBoolean() ?: false)  // switch
                 7 -> params.put(field.key, value)  // secret（注意：value 已脱敏）
                 else -> params.put(field.key, value)
             }
         }
        
        return params.toString()
    }
    
    // ========== 数据类 ==========
    
    /**
     * 验证结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )
    
    /**
     * 表单提交回调
     */
    interface FormSubmitCallback {
        /**
         * 验证失败
         * 
         * @param errors 错误列表
         */
        fun onValidationFailed(errors: List<String>)
        
        /**
         * 提交成功
         * 
         * @param result 执行结果
         */
        fun onSubmitSuccess(result: ExecutionResult)
        
        /**
         * 提交失败
         * 
         * @param reason 失败原因
         */
        fun onSubmitFailed(reason: String)
    }
}

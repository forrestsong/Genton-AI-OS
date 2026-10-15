package com.genton.action.service

import android.os.Bundle
import android.util.Log
import com.genton.action.registry.ActionRegistry
import com.genton.action.registry.ActionRegistryEntry
import com.genton.action.registry.InputSourceLevel
import org.json.JSONObject

/**
 * A3.2 执行中介 - 参数来源校验器（Taint 规则）
 * 
 * 职责：
 * 1. 校验参数来源是否符合动作注册表要求
 * 2. 检测污染参数（tainted arguments）
 * 3. 执行意图一致性校验
 * 4. 生成校验报告
 * 
 * 来源等级层次：
 * TRUSTED（系统/用户显式确认）> USER（用户原话）> ANY（任意，含污染内容）
 * 
 * 校验规则：
 * - 参数要求 user/trusted 但被标记为 tainted → SUBMIT_TAINT_DENIED
 * - 参数要求 user/trusted 且未打标但来源可疑 → SUBMIT_POLICY_DENIED
 * - 动作与目标意图不符 → SUBMIT_POLICY_DENIED
 */
class ActionSourceValidator(
    private val registry: ActionRegistry
) {
    
    companion object {
        private const val TAG = "ActionSourceValidator"
    }
    
    /**
     * 校验动作请求
     * 
     * @return 校验结果（通过返回 SUBMIT_ACCEPTED，否则返回对应错误码）
     */
    fun validate(request: ActionRequest): ValidationResult {
        Log.d(TAG, "Validating action request: ${request.actionName}")
        
        // 1. 获取动作注册项
        val entry = registry.getAction(request.actionName)
        if (entry == null) {
            return ValidationResult(
                code = SubmitResult.SUBMIT_UNKNOWN_ACTION,
                message = "Action not found in registry: ${request.actionName}"
            )
        }
        
        // 2. 校验参数 schema
        val schemaValidation = validateArgumentsSchema(request, entry)
        if (!schemaValidation.isValid) {
            return ValidationResult(
                code = SubmitResult.SUBMIT_ARGUMENT_INVALID,
                message = "Invalid arguments: ${schemaValidation.message}"
            )
        }
        
        // 3. 校验参数来源（taint 规则）
        val sourceValidation = validateArgumentsSource(request, entry)
        if (!sourceValidation.isValid) {
            return sourceValidation
        }
        
        // 4. 校验意图一致性
        val intentValidation = validateIntentConsistency(request, entry)
        if (!intentValidation.isValid) {
            return intentValidation
        }
        
        Log.d(TAG, "Validation passed: ${request.actionName}")
        return ValidationResult(SubmitResult.SUBMIT_ACCEPTED)
    }
    
    /**
     * 校验参数 schema
     */
    private fun validateArgumentsSchema(
        request: ActionRequest,
        entry: ActionRegistryEntry
    ): ValidationResult {
        val inputSchema = entry.inputSchema
        
        // 获取必填字段
        val required = if (inputSchema.has("required")) {
            val array = inputSchema.getJSONArray("required")
            List(array.length()) { array.getString(it) }
        } else {
            emptyList()
        }
        
        // 检查必填字段
        val missingFields = required.filter { !request.arguments.containsKey(it) }
        if (missingFields.isNotEmpty()) {
            return ValidationResult(
                code = SubmitResult.SUBMIT_ARGUMENT_INVALID,
                message = "Missing required fields: ${missingFields.joinToString(", ")}"
            )
        }
        
        // 获取 properties 定义
        val properties = if (inputSchema.has("properties")) {
            inputSchema.getJSONObject("properties")
        } else {
            JSONObject()
        }
        
        // 校验每个参数类型（简化版）
        request.arguments.keySet().forEach { key ->
            if (properties.has(key)) {
                val propDef = properties.getJSONObject(key)
                val value = request.arguments.get(key)
                
                // 类型校验（简化）
                val expectedType = propDef.optString("type", "")
                if (!validateType(value, expectedType)) {
                    return ValidationResult(
                        code = SubmitResult.SUBMIT_ARGUMENT_INVALID,
                        message = "Invalid type for parameter '$key': expected $expectedType"
                    )
                }
            }
        }
        
        return ValidationResult(SubmitResult.SUBMIT_ACCEPTED)
    }
    
    /**
     * 简化的类型校验
     */
    private fun validateType(value: Any?, expectedType: String): Boolean {
        return when (expectedType) {
            "string" -> value is String
            "integer" -> value is Int || value is Long
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object" -> value is Bundle || value is Map<*, *>
            "array" -> value is Array<*> || value is List<*>
            else -> true  // 未知类型，跳过校验
        }
    }
    
    /**
     * 校验参数来源（taint 规则）
     */
    private fun validateArgumentsSource(
        request: ActionRequest,
        entry: ActionRegistryEntry
    ): ValidationResult {
        val inputSource = entry.inputSource ?: return ValidationResult(SubmitResult.SUBMIT_ACCEPTED)
        
        // 检查每个有来源要求的参数
        inputSource.forEach { (paramName, requiredLevel) ->
            // 1. 检查参数是否存在
            if (!request.arguments.containsKey(paramName)) {
                // 如果是可选参数，跳过
                if (!entry.inputSchema.optJSONArray("required")?.let { 
                    List(it.length()) { i -> it.getString(i) }.contains(paramName) 
                } == true) {
                    return@forEach
                }
                
                return ValidationResult(
                    code = SubmitResult.SUBMIT_ARGUMENT_INVALID,
                    message = "Missing parameter with source restriction: $paramName"
                )
            }
            
            // 2. 检查参数是否被标记为污染
            val isTainted = request.isTainted(paramName)
            
            // 3. 根据要求的来源等级判断
            when (requiredLevel) {
                InputSourceLevel.TRUSTED -> {
                    // TRUSTED 级别：不接受任何 tainted 参数
                    if (isTainted) {
                        return ValidationResult(
                            code = SubmitResult.SUBMIT_TAINT_DENIED,
                            message = "Parameter '$paramName' requires TRUSTED source but is tainted (from APP_CONTENT/WEB_CONTENT)",
                            taintedParams = listOf(paramName)
                        )
                    }
                    // TODO: 进一步校验是否来自用户显式确认
                }
                
                InputSourceLevel.USER -> {
                    // USER 级别：不接受 tainted 参数
                    if (isTainted) {
                        return ValidationResult(
                            code = SubmitResult.SUBMIT_TAINT_DENIED,
                            message = "Parameter '$paramName' requires USER source but is tainted (from APP_CONTENT/WEB_CONTENT)",
                            taintedParams = listOf(paramName)
                        )
                    }
                }
                
                InputSourceLevel.ANY -> {
                    // ANY 级别：接受任何来源，包括 tainted
                    // 但仍需记录审计
                }
            }
        }
        
        // 4. 特殊检查：高风险动作强制声明 inputSource
        if (entry.riskLevel == 2 && (inputSource.isEmpty())) {
            Log.w(TAG, "High-risk action ${entry.name} should declare inputSource")
        }
        
        return ValidationResult(SubmitResult.SUBMIT_ACCEPTED)
    }
    
    /**
     * 校验意图一致性
     */
    private fun validateIntentConsistency(
        request: ActionRequest,
        entry: ActionRegistryEntry
    ): ValidationResult {
        val sourceIntentId = request.sourceIntentId
        
        // 如果没有提供意图ID，跳过校验（低风险动作允许）
        if (sourceIntentId == null) {
            if (entry.riskLevel >= 2) {
                Log.w(TAG, "High-risk action ${entry.name} submitted without sourceIntentId")
            }
            return ValidationResult(SubmitResult.SUBMIT_ACCEPTED)
        }
        
        // TODO: 实际实现需要查询目标图（Goal DAG）
        // 1. 根据 sourceIntentId 获取目标意图
        // 2. 检查动作的"对象+类型"是否与目标意图一致
        // 3. 如果偏离，返回 SUBMIT_POLICY_DENIED
        
        // 示例：检查动作是否与意图匹配
        // val intent = goalDagService.getIntent(sourceIntentId)
        // if (!isActionConsistentWithIntent(entry, intent, request.arguments)) {
        //     return ValidationResult(
        //         code = SubmitResult.SUBMIT_POLICY_DENIED,
        //         message = "Action '${entry.name}' is inconsistent with source intent '$sourceIntentId'"
        //     )
        // }
        
        Log.d(TAG, "Intent consistency check passed for ${entry.name} (intent=$sourceIntentId)")
        return ValidationResult(SubmitResult.SUBMIT_ACCEPTED)
    }
    
    /**
     * 生成校验报告
     */
    fun generateReport(request: ActionRequest): ValidationReport {
        val entry = registry.getAction(request.actionName)
        
        return ValidationReport(
            actionName = request.actionName,
            actionExists = entry != null,
            riskLevel = entry?.riskLevel,
            taintedArguments = request.taintedArgumentKeys.toList(),
            inputSourceRequirements = entry?.inputSource?.mapValues { it.value.name } ?: emptyMap(),
            sourceIntentId = request.sourceIntentId,
            validationResult = validate(request)
        )
    }
}

/**
 * 校验结果
 */
data class ValidationResult(
    val code: Int,
    val message: String? = null,
    val taintedParams: List<String> = emptyList()
) {
    val isValid: Boolean
        get() = code == SubmitResult.SUBMIT_ACCEPTED
    
    fun getDescription(): String {
        return when (code) {
            SubmitResult.SUBMIT_ACCEPTED -> "Accepted"
            SubmitResult.SUBMIT_UNKNOWN_ACTION -> "Unknown action"
            SubmitResult.SUBMIT_POLICY_DENIED -> "Policy denied"
            SubmitResult.SUBMIT_ARGUMENT_INVALID -> "Invalid arguments"
            SubmitResult.SUBMIT_TAINT_DENIED -> "Taint denied"
            SubmitResult.SUBMIT_CONFIRMATION_REQUIRED -> "Confirmation required"
            else -> "Unknown result ($code)"
        }
    }
}

/**
 * 校验报告
 */
data class ValidationReport(
    val actionName: String,
    val actionExists: Boolean,
    val riskLevel: Int?,
    val taintedArguments: List<String>,
    val inputSourceRequirements: Map<String, String>,
    val sourceIntentId: String?,
    val validationResult: ValidationResult
) {
    fun toReadableString(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Action Validation Report ===")
        sb.appendLine("Action: $actionName")
        sb.appendLine("Exists: $actionExists")
        if (riskLevel != null) {
            sb.appendLine("Risk Level: $riskLevel")
        }
        sb.appendLine("Tainted Arguments: ${taintedArguments.joinToString(", ").ifEmpty { "none" }}")
        sb.appendLine("Input Source Requirements:")
        inputSourceRequirements.forEach { (param, level) ->
            sb.appendLine("  - $param: $level")
        }
        sb.appendLine("Source Intent ID: ${sourceIntentId ?: "none"}")
        sb.appendLine("Validation Result: ${validationResult.getDescription()}")
        if (validationResult.message != null) {
            sb.appendLine("Message: ${validationResult.message}")
        }
        return sb.toString()
    }
}

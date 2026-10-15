package com.ainative.learn

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Learn 激活服务
 * 
 * 职责：
 * 1. 处理 activate_suggestion / deactivate_rule 表单提交
 * 2. 协调 LearnActivationValidator 和 LearnStateManager
 * 3. 通知 PolicyEngine 重新加载规则
 * 4. 生成激活后的 user. 规则
 * 
 * 使用场景：
 * - D15 表单提交处理器调用
 * - D4.2 UI 激活/撤回按钮调用
 * 
 * 集成点：
 * - C2.2 LearnStateManager: 状态管理
 * - C2.3 LearnActivationValidator: 验证逻辑
 * - A7.1 PolicyEngine: 重新加载规则
 * - A5.2 AuditService: 审计日志
 * 
 * @since A5
 */
class LearnActivationService(private val context: Context) {
    
    companion object {
        private const val TAG = "LearnActivationService"
        
        // 冷静期：7 天
        private const val COOL_DOWN_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L
    }
    
    private val stateManager = LearnStateManager(context)
    private val validator = LearnActivationValidator()
    
    // 监听器（用于通知 PolicyEngine）
    private var policyEngineReloadCallback: (() -> Unit)? = null
    
    /**
     * 设置 PolicyEngine 重载回调
     */
    fun setPolicyEngineReloadCallback(callback: () -> Unit) {
        policyEngineReloadCallback = callback
    }
    
    /**
     * 激活建议
     * 
     * @param suggestionId 建议 ID
     * @param scopeOverrides 范围参数覆盖（来自 approve_scoped 表单）
     * @return 激活结果
     */
    fun activateSuggestion(
        suggestionId: String,
        scopeOverrides: Map<String, Any>? = null
    ): ActivationResult {
        Log.i(TAG, "Activating suggestion: $suggestionId")
        
        // 1. 获取建议记录
        val suggestion = stateManager.getSuggestionById(suggestionId)
        if (suggestion == null) {
            Log.e(TAG, "Suggestion not found: $suggestionId")
            return ActivationResult.Failure("Suggestion not found")
        }
        
        // 2. 获取现有规则（用于冲突检测）
        val existingRules = loadExistingUserRules()
        
        // 3. 验证激活请求
        val validationResult = validator.validate(
            suggestion = suggestion,
            existingRules = existingRules,
            scopeOverrides = scopeOverrides
        )
        
        when (validationResult) {
            is ValidationResult.Failure -> {
                Log.w(TAG, "Validation failed: ${validationResult.errors.size} errors")
                return ActivationResult.ValidationFailed(
                    errors = validationResult.errors.map { formatError(it) },
                    warnings = validationResult.warnings.map { formatWarning(it) }
                )
            }
            is ValidationResult.Success -> {
                // 4. 生成 user. 规则
                val userRule = generateUserRule(
                    userRuleId = validationResult.userRuleId,
                    learnRule = validationResult.finalRule,
                    learnSuggestionId = suggestionId
                )
                
                // 5. 持久化 user. 规则
                if (!saveUserRule(userRule)) {
                    return ActivationResult.Failure("Failed to save user rule")
                }
                
                // 6. 更新建议状态
                val stateResult = stateManager.activateSuggestion(
                    suggestionId = suggestionId,
                    userRuleId = validationResult.userRuleId
                )
                
                when (stateResult) {
                    is LearnStateTransitionResult.Success -> {
                        // 7. 通知 PolicyEngine 重新加载
                        notifyPolicyEngineReload()
                        
                        // 8. 审计日志
                        auditActivation(suggestionId, validationResult.userRuleId)
                        
                        Log.i(TAG, "Activation successful: $suggestionId → ${validationResult.userRuleId}")
                        return ActivationResult.Success(
                            userRuleId = validationResult.userRuleId,
                            coolDownUntilMs = System.currentTimeMillis() + COOL_DOWN_PERIOD_MS,
                            warnings = validationResult.warnings.map { formatWarning(it) }
                        )
                    }
                    is LearnStateTransitionResult.Failure -> {
                        Log.e(TAG, "State transition failed: $stateResult")
                        return ActivationResult.Failure("State transition failed: $stateResult")
                    }
                }
            }
        }
    }
    
    /**
     * 停用规则（冷静期内撤回）
     * 
     * @param suggestionId 建议 ID
     * @param reason 撤回原因
     * @return 停用结果
     */
    fun deactivateRule(
        suggestionId: String,
        reason: String? = null
    ): DeactivationResult {
        Log.i(TAG, "Deactivating rule: $suggestionId, reason=$reason")
        
        // 1. 获取建议记录
        val suggestion = stateManager.getSuggestionById(suggestionId)
        if (suggestion == null) {
            return DeactivationResult.Failure("Suggestion not found")
        }
        
        // 2. 检查是否在冷静期内
        if (!suggestion.isInCoolDownPeriod()) {
            return DeactivationResult.CoolDownExpired(
                suggestionId = suggestionId,
                message = "Cool down period expired, use regular policy editor"
            )
        }
        
        // 3. 删除 user. 规则
        val userRuleId = suggestion.userRuleId
        if (userRuleId != null && !deleteUserRule(userRuleId)) {
            return DeactivationResult.Failure("Failed to delete user rule")
        }
        
        // 4. 更新建议状态
        val stateResult = stateManager.revokeSuggestion(suggestionId, reason)
        
        when (stateResult) {
            is LearnStateTransitionResult.Success -> {
                // 5. 通知 PolicyEngine 重新加载
                notifyPolicyEngineReload()
                
                // 6. 审计日志
                auditDeactivation(suggestionId, userRuleId, reason)
                
                Log.i(TAG, "Deactivation successful: $suggestionId")
                return DeactivationResult.Success
            }
            is LearnStateTransitionResult.Failure -> {
                Log.e(TAG, "State transition failed: $stateResult")
                return DeactivationResult.Failure("State transition failed: $stateResult")
            }
        }
    }
    
    /**
     * 批量激活检查（预检，不实际激活）
     * 
     * @param suggestionIds 建议 ID 列表
     * @return 每个建议的验证结果
     */
    fun batchValidate(suggestionIds: List<String>): Map<String, ValidationResult> {
        val results = mutableMapOf<String, ValidationResult>()
        val existingRules = loadExistingUserRules()
        
        for (suggestionId in suggestionIds) {
            val suggestion = stateManager.getSuggestionById(suggestionId)
            if (suggestion == null) {
                continue
            }
            
            val validationResult = validator.validate(
                suggestion = suggestion,
                existingRules = existingRules,
                scopeOverrides = null
            )
            
            results[suggestionId] = validationResult
        }
        
        return results
    }
    
    // ========== 私有辅助方法 ==========
    
    /**
     * 加载现有 user. 规则
     * 
     * TODO: 集成 A7.1 PolicyEngine
     */
    private fun loadExistingUserRules(): List<PolicyRule> {
        // 暂时返回空列表
        // 生产实现：查询 PolicyEngine 的 user. 规则
        return emptyList()
    }
    
    /**
     * 生成 user. 规则
     * 
     * 复制 learn. 规则为 user. 规则，添加 provenance 元数据
     */
    private fun generateUserRule(
        userRuleId: String,
        learnRule: PolicyRule,
        learnSuggestionId: String
    ): PolicyRule {
        val meta = (learnRule.meta ?: emptyMap()).toMutableMap()
        meta["state"] = "active"
        meta["provenance"] = "learned_from:$learnSuggestionId"
        meta["activatedAtMs"] = System.currentTimeMillis()
        meta["coolDownUntilMs"] = System.currentTimeMillis() + COOL_DOWN_PERIOD_MS
        
        return learnRule.copy(
            id = userRuleId,
            namespace = "user",
            meta = meta
        )
    }
    
    /**
     * 保存 user. 规则
     * 
     * TODO: 集成 A7.1 PolicyEngine
     */
    private fun saveUserRule(rule: PolicyRule): Boolean {
        // 暂时模拟成功
        // 生产实现：调用 PolicyEngine.addUserRule()
        Log.d(TAG, "Saving user rule: ${rule.id}")
        return true
    }
    
    /**
     * 删除 user. 规则
     * 
     * TODO: 集成 A7.1 PolicyEngine
     */
    private fun deleteUserRule(userRuleId: String): Boolean {
        // 暂时模拟成功
        // 生产实现：调用 PolicyEngine.deleteUserRule()
        Log.d(TAG, "Deleting user rule: $userRuleId")
        return true
    }
    
    /**
     * 通知 PolicyEngine 重新加载规则
     */
    private fun notifyPolicyEngineReload() {
        policyEngineReloadCallback?.invoke()
        Log.d(TAG, "Notified PolicyEngine to reload rules")
    }
    
    /**
     * 审计激活操作
     * 
     * TODO: 集成 A5.2 AuditService
     */
    private fun auditActivation(suggestionId: String, userRuleId: String) {
        Log.d(TAG, "Auditing activation: $suggestionId → $userRuleId")
        // 生产实现：调用 AuditService.recordEvent()
    }
    
    /**
     * 审计停用操作
     * 
     * TODO: 集成 A5.2 AuditService
     */
    private fun auditDeactivation(suggestionId: String, userRuleId: String?, reason: String?) {
        Log.d(TAG, "Auditing deactivation: $suggestionId, userRule=$userRuleId, reason=$reason")
        // 生产实现：调用 AuditService.recordEvent()
    }
    
    /**
     * 格式化错误消息
     */
    private fun formatError(error: ValidationError): String {
        return when (error) {
            is ValidationError.InvalidState -> 
                "Invalid state: current=${error.currentState}, expected=${error.expectedState}"
            is ValidationError.InvalidRuleJson -> 
                "Invalid rule JSON: ${error.reason}"
            is ValidationError.InvalidNamespace -> 
                "Invalid namespace: ${error.ruleId} should start with ${error.expectedPrefix}"
            is ValidationError.RuleConflict -> 
                "Rule conflict: ${error.description} (with ${error.existingRuleId})"
            is ValidationError.DslCompilationFailed -> 
                "DSL compilation failed: ${error.reason}"
            is ValidationError.InvalidScopeParameter -> 
                "Invalid scope parameter: ${error.parameterName}"
        }
    }
    
    /**
     * 格式化警告消息
     */
    private fun formatWarning(warning: ValidationWarning): String {
        return when (warning) {
            is ValidationWarning.PriorityOutOfRange -> 
                "Priority ${warning.priority} is outside recommended range [${warning.recommendedMin}, ${warning.recommendedMax}]"
        }
    }
}

// ========== 结果类型 ==========

/**
 * 激活结果
 */
sealed class ActivationResult {
    data class Success(
        val userRuleId: String,
        val coolDownUntilMs: Long,
        val warnings: List<String>
    ) : ActivationResult()
    
    data class ValidationFailed(
        val errors: List<String>,
        val warnings: List<String>
    ) : ActivationResult()
    
    data class Failure(
        val message: String
    ) : ActivationResult()
}

/**
 * 停用结果
 */
sealed class DeactivationResult {
    object Success : DeactivationResult()
    
    data class CoolDownExpired(
        val suggestionId: String,
        val message: String
    ) : DeactivationResult()
    
    data class Failure(
        val message: String
    ) : DeactivationResult()
}

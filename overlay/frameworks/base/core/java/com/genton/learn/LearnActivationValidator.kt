package com.genton.learn

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learn 激活回归检查器
 * 
 * 职责：
 * 1. 激活前检查规则冲突（与现有 user. 规则）
 * 2. DSL 编译校验（调用 A7.2 编译器）
 * 3. 范围重生成（approve_scoped 表单字段）
 * 4. 生成详细的验证报告
 * 
 * 使用场景：
 * - D15 表单提交 activate_suggestion 前调用
 * - C2.2 LearnStateManager.activateSuggestion() 前置检查
 * 
 * 集成点：
 * - A7.2 PolicyDslCompiler: DSL 编译和冲突矩阵
 * - C2.2 LearnStateManager: 获取现有规则
 * - C1.1 规则目录: 优先级分带约定
 * 
 * @since A5
 */
class LearnActivationValidator {
    
    companion object {
        private const val TAG = "LearnActivationValidator"
        
        // 优先级分带（参考 DSL 规范 §7.4）
        private const val PRIORITY_BASELINE = 1000
        private const val PRIORITY_USER_NATIVE_MIN = 300
        private const val PRIORITY_USER_NATIVE_MAX = 450
        private const val PRIORITY_USER_DND_MIN = 150
        private const val PRIORITY_USER_DND_MAX = 250
        private const val PRIORITY_USER_DIGEST_MIN = 90
        private const val PRIORITY_USER_DIGEST_MAX = 140
        
        // 范围参数键（可在激活时修改）
        private val SCOPE_PARAMETERS = setOf(
            "senderDomain",           // notification: 发送方域
            "category",               // notification: 类别
            "callerNumber",           // call_gate: 来电号码
            "numberPrefix"            // call_gate: 号码前缀
        )
    }
    
    /**
     * 验证激活请求
     * 
     * @param suggestion 待激活的建议
     * @param existingRules 现有的 user. 规则列表
     * @param scopeOverrides 范围参数覆盖（来自 approve_scoped 表单）
     * @return 验证结果
     */
    fun validate(
        suggestion: LearnSuggestionRecord,
        existingRules: List<PolicyRule>,
        scopeOverrides: Map<String, Any>? = null
    ): ValidationResult {
        Log.d(TAG, "Validating activation: ${suggestion.suggestionId}")
        
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()
        
        // 1. 状态检查
        if (suggestion.state != LearnSuggestionState.SUGGESTION) {
            errors.add(ValidationError.InvalidState(
                suggestionId = suggestion.suggestionId,
                currentState = suggestion.state,
                expectedState = LearnSuggestionState.SUGGESTION
            ))
            return ValidationResult.Failure(errors, warnings)
        }
        
        // 2. 解析规则 JSON
        val learnRule = try {
            parseRule(suggestion.ruleJson)
        } catch (e: Exception) {
            errors.add(ValidationError.InvalidRuleJson(
                suggestionId = suggestion.suggestionId,
                reason = e.message ?: "JSON parse error"
            ))
            return ValidationResult.Failure(errors, warnings)
        }
        
        // 3. 应用范围覆盖（如果有）
        val finalRule = if (scopeOverrides != null && scopeOverrides.isNotEmpty()) {
            applyScopeOverrides(learnRule, scopeOverrides, errors)
        } else {
            learnRule
        }
        
        if (errors.isNotEmpty()) {
            return ValidationResult.Failure(errors, warnings)
        }
        
        // 4. 命名空间检查
        if (!finalRule.id.startsWith("learn.")) {
            errors.add(ValidationError.InvalidNamespace(
                ruleId = finalRule.id,
                expectedPrefix = "learn."
            ))
        }
        
        // 5. 优先级检查（learn 规则应在 user 摘要/折叠带内）
        if (finalRule.priority < PRIORITY_USER_DIGEST_MIN || 
            finalRule.priority > PRIORITY_USER_DND_MAX) {
            warnings.add(ValidationWarning.PriorityOutOfRange(
                ruleId = finalRule.id,
                priority = finalRule.priority,
                recommendedMin = PRIORITY_USER_DIGEST_MIN,
                recommendedMax = PRIORITY_USER_DND_MAX
            ))
        }
        
        // 6. 冲突检测
        val conflicts = detectConflicts(finalRule, existingRules)
        if (conflicts.isNotEmpty()) {
            for (conflict in conflicts) {
                errors.add(ValidationError.RuleConflict(
                    newRuleId = finalRule.id,
                    existingRuleId = conflict.existingRuleId,
                    conflictType = conflict.type,
                    description = conflict.description
                ))
            }
        }
        
        // 7. DSL 编译检查（模拟）
        // TODO: 集成 A7.2 PolicyDslCompiler
        val compilationResult = validateDslCompliance(finalRule)
        if (!compilationResult.success) {
            errors.add(ValidationError.DslCompilationFailed(
                ruleId = finalRule.id,
                reason = compilationResult.reason
            ))
        }
        
        // 8. 生成激活建议
        if (errors.isEmpty()) {
            val userRuleId = generateUserRuleId(suggestion.templateId, finalRule.id)
            return ValidationResult.Success(
                userRuleId = userRuleId,
                finalRule = finalRule,
                warnings = warnings
            )
        } else {
            return ValidationResult.Failure(errors, warnings)
        }
    }
    
    /**
     * 冲突检测
     * 
     * 检测类型：
     * 1. 完全覆盖：when 条件完全相同
     * 2. 部分覆盖：when 条件部分重叠
     * 3. 优先级冲突：相同 priority 但 effect 不同
     */
    private fun detectConflicts(
        newRule: PolicyRule,
        existingRules: List<PolicyRule>
    ): List<RuleConflict> {
        val conflicts = mutableListOf<RuleConflict>()
        
        for (existing in existingRules) {
            // 只检测同域规则
            if (existing.domain != newRule.domain) continue
            
            // 跳过 baseline 规则（baseline 优先级最高，不冲突）
            if (existing.namespace == "sys" && existing.priority >= PRIORITY_BASELINE) continue
            
            // 检查 when 条件重叠
            val overlap = calculateWhenOverlap(newRule.whenConditions, existing.whenConditions)
            
            when {
                overlap >= 1.0f -> {
                    // 完全覆盖
                    conflicts.add(RuleConflict(
                        existingRuleId = existing.id,
                        type = ConflictType.COMPLETE_OVERLAP,
                        description = "New rule completely overlaps with ${existing.id}",
                        severity = ConflictSeverity.ERROR
                    ))
                }
                overlap >= 0.5f -> {
                    // 部分覆盖（警告）
                    conflicts.add(RuleConflict(
                        existingRuleId = existing.id,
                        type = ConflictType.PARTIAL_OVERLAP,
                        description = "New rule partially overlaps with ${existing.id} (${(overlap * 100).toInt()}%)",
                        severity = ConflictSeverity.WARNING
                    ))
                }
                overlap > 0f && newRule.priority == existing.priority && newRule.effect != existing.effect -> {
                    // 优先级冲突
                    conflicts.add(RuleConflict(
                        existingRuleId = existing.id,
                        type = ConflictType.PRIORITY_CONFLICT,
                        description = "Same priority (${newRule.priority}) but different effects",
                        severity = ConflictSeverity.ERROR
                    ))
                }
            }
        }
        
        return conflicts
    }
    
    /**
     * 计算 when 条件重叠度
     * 
     * 算法：
     * 1. 计算公共键的数量
     * 2. 对于公共键，检查值是否相等或兼容
     * 3. 返回重叠比例 (0-1)
     */
    private fun calculateWhenOverlap(
        when1: Map<String, Any>,
        when2: Map<String, Any>
    ): Float {
        if (when1.isEmpty() || when2.isEmpty()) return 0f
        
        val commonKeys = when1.keys.intersect(when2.keys)
        if (commonKeys.isEmpty()) return 0f
        
        var matchingKeys = 0
        for (key in commonKeys) {
            val value1 = when1[key]
            val value2 = when2[key]
            
            if (valuesMatch(value1, value2)) {
                matchingKeys++
            }
        }
        
        // 重叠度 = 匹配键数 / 总键数（取两者最大）
        val totalKeys = maxOf(when1.size, when2.size)
        return matchingKeys.toFloat() / totalKeys
    }
    
    /**
     * 值匹配检查
     */
    private fun valuesMatch(value1: Any?, value2: Any?): Boolean {
        if (value1 == null || value2 == null) return value1 == value2
        
        // 完全相等
        if (value1 == value2) return true
        
        // 数组交集（OR 语义）
        if (value1 is List<*> && value2 is List<*>) {
            return value1.intersect(value2.toSet()).isNotEmpty()
        }
        
        // 字符串比较串（数值范围重叠检测）
        if (value1 is String && value2 is String) {
            return compareStringsOverlap(value1, value2)
        }
        
        return false
    }
    
    /**
     * 比较串重叠检测
     * 
     * 示例：
     * - ">= 0.8" 与 "<= 0.9" 重叠 → true
     * - "> 0.9" 与 "< 0.8" 不重叠 → false
     */
    private fun compareStringsOverlap(s1: String, s2: String): Boolean {
        // 简化实现：只检测常见模式
        // 生产实现需要完整的数值范围分析
        
        val regex = "(>=|<=|>|<|==)\\s*([0-9.]+)".toRegex()
        val match1 = regex.find(s1) ?: return s1 == s2
        val match2 = regex.find(s2) ?: return s1 == s2
        
        val (op1, val1) = match1.destructured
        val (op2, val2) = match2.destructured
        
        val num1 = val1.toFloatOrNull() ?: return false
        val num2 = val2.toFloatOrNull() ?: return false
        
        // 简单重叠判断（不精确，仅示例）
        return when {
            op1 == ">=" && op2 == "<=" -> num1 <= num2
            op1 == ">" && op2 == "<" -> num1 < num2
            op1 == "==" && op2 == "==" -> num1 == num2
            else -> true  // 保守返回 true（需人工审查）
        }
    }
    
    /**
     * 应用范围覆盖
     */
    private fun applyScopeOverrides(
        rule: PolicyRule,
        scopeOverrides: Map<String, Any>,
        errors: MutableList<ValidationError>
    ): PolicyRule {
        val newWhenConditions = rule.whenConditions.toMutableMap()
        
        for ((key, value) in scopeOverrides) {
            if (key !in SCOPE_PARAMETERS) {
                errors.add(ValidationError.InvalidScopeParameter(
                    parameterName = key,
                    allowedParameters = SCOPE_PARAMETERS.toList()
                ))
                continue
            }
            
            // 覆盖 when 条件
            newWhenConditions[key] = value
            Log.d(TAG, "Applied scope override: $key = $value")
        }
        
        return rule.copy(whenConditions = newWhenConditions)
    }
    
    /**
     * DSL 合规性校验
     */
    private fun validateDslCompliance(rule: PolicyRule): DslComplianceResult {
        // TODO: 集成 A7.2 PolicyDslCompiler
        // 当前为简化实现
        
        // 检查必填字段
        if (rule.id.isEmpty() || rule.domain.isEmpty() || rule.effect.isEmpty()) {
            return DslComplianceResult(false, "Missing required fields")
        }
        
        // 检查 when 条件非空
        if (rule.whenConditions.isEmpty()) {
            return DslComplianceResult(false, "when conditions cannot be empty")
        }
        
        // 检查 then 动作非空
        if (rule.thenActions.isEmpty()) {
            return DslComplianceResult(false, "then actions cannot be empty")
        }
        
        return DslComplianceResult(true, null)
    }
    
    /**
     * 生成 user. 规则 ID
     * 
     * 格式：user.<src>.learned-<hash>
     * 示例：user.notify.learned-abc123
     */
    private fun generateUserRuleId(templateId: String, learnRuleId: String): String {
        // 从模板 ID 提取域段
        val domain = when {
            templateId.contains("sender-frequent") || templateId.contains("category") || templateId.contains("ad-fold") -> "notify"
            templateId.contains("spam") || templateId.contains("service-prefix") -> "callgate"
            else -> "unknown"
        }
        
        // 生成短 hash
        val hash = learnRuleId.hashCode().toString(16).takeLast(6)
        
        return "user.$domain.learned-$hash"
    }
    
    /**
     * 解析规则 JSON
     */
    private fun parseRule(ruleJson: String): PolicyRule {
        val json = JSONObject(ruleJson)
        
        return PolicyRule(
            id = json.getString("id"),
            namespace = json.getString("namespace"),
            domain = json.getString("domain"),
            effect = json.getString("effect"),
            whenConditions = jsonObjectToMap(json.getJSONObject("whenConditions")),
            thenActions = jsonObjectToMap(json.getJSONObject("thenActions")),
            priority = json.getInt("priority"),
            meta = if (json.has("meta")) jsonObjectToMap(json.getJSONObject("meta")) else null
        )
    }
    
    private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = jsonObject.keys()
        
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            
            map[key] = when (value) {
                is JSONArray -> (0 until value.length()).map { value.get(it) }
                else -> value
            }
        }
        
        return map
    }
}

// ========== 数据结构 ==========

/**
 * 验证结果
 */
sealed class ValidationResult {
    data class Success(
        val userRuleId: String,
        val finalRule: PolicyRule,
        val warnings: List<ValidationWarning>
    ) : ValidationResult()
    
    data class Failure(
        val errors: List<ValidationError>,
        val warnings: List<ValidationWarning>
    ) : ValidationResult()
}

/**
 * 验证错误
 */
sealed class ValidationError {
    data class InvalidState(
        val suggestionId: String,
        val currentState: LearnSuggestionState,
        val expectedState: LearnSuggestionState
    ) : ValidationError()
    
    data class InvalidRuleJson(
        val suggestionId: String,
        val reason: String
    ) : ValidationError()
    
    data class InvalidNamespace(
        val ruleId: String,
        val expectedPrefix: String
    ) : ValidationError()
    
    data class RuleConflict(
        val newRuleId: String,
        val existingRuleId: String,
        val conflictType: ConflictType,
        val description: String
    ) : ValidationError()
    
    data class DslCompilationFailed(
        val ruleId: String,
        val reason: String
    ) : ValidationError()
    
    data class InvalidScopeParameter(
        val parameterName: String,
        val allowedParameters: List<String>
    ) : ValidationError()
}

/**
 * 验证警告
 */
sealed class ValidationWarning {
    data class PriorityOutOfRange(
        val ruleId: String,
        val priority: Int,
        val recommendedMin: Int,
        val recommendedMax: Int
    ) : ValidationWarning()
}

/**
 * 规则冲突
 */
data class RuleConflict(
    val existingRuleId: String,
    val type: ConflictType,
    val description: String,
    val severity: ConflictSeverity
)

/**
 * 冲突类型
 */
enum class ConflictType {
    COMPLETE_OVERLAP,    // 完全覆盖
    PARTIAL_OVERLAP,     // 部分覆盖
    PRIORITY_CONFLICT    // 优先级冲突
}

/**
 * 冲突严重程度
 */
enum class ConflictSeverity {
    ERROR,      // 阻止激活
    WARNING     // 允许激活但提示用户
}

/**
 * DSL 合规性结果
 */
data class DslComplianceResult(
    val success: Boolean,
    val reason: String?
)

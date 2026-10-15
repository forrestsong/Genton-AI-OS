package com.android.server.genton.action

import com.genton.action.service.ActionRequest
import com.genton.action.TaintModels.*
import com.genton.action.registry.ActionRegistry
import android.util.Log

/**
 * A3.4 参数来源校验器
 * 
 * 职责：
 * 1. 校验动作参数的来源是否满足taint规则
 * 2. 基于ActionRequest.taintedArgumentKeys判断参数来源
 * 3. 生成详细的违规报告
 * 4. 支持宽松模式（警告）和严格模式（拒绝）
 * 
 * 校验流程：
 * 1. 获取动作的taint规则定义
 * 2. 逐参数检查：在taintedArgumentKeys中=TAINTED，否则=CLEAN
 * 3. 记录违规项（强制/警告）
 * 4. 返回校验结果
 * 
 * 安全原则：
 * - 默认保守：taintedArgumentKeys中的参数=TAINTED（不可信）
 * - 明确要求：敏感操作必须显式声明来源要求
 * - 审计全覆盖：所有违规（包括警告）都记录审计
 */
class ActionSourceValidator(
    private val registry: ActionRegistry
) {
    companion object {
        private const val TAG = "ActionSourceValidator"
    }
    
    /**
     * 校验动作请求的参数来源
     * 
     * @param request 动作请求（包含arguments和taintedArgumentKeys）
     * @return 校验结果
     */
    fun validate(request: ActionRequest): ValidationResult {
        val actionName = request.actionName
        
        // 1. 获取动作的taint规则
        val taintRules = TaintRuleSet.getRules(actionName)
        
        // 如果没有taint规则，通过校验（默认允许）
        if (taintRules.isEmpty()) {
            return ValidationResult(
                code = ValidationResult.VALID,
                message = "No taint rules defined for action: $actionName"
            )
        }
        
        // 2. 逐规则检查
        val violations = mutableListOf<TaintViolation>()
        val warnings = mutableListOf<TaintViolation>()
        
        taintRules.forEach { rule ->
            val paramName = rule.parameterName
            
            // 检查参数是否存在
            if (!request.arguments.containsKey(paramName)) {
                // 参数不存在，跳过（由参数完整性检查器处理）
                return@forEach
            }
            
            // 判断实际来源：在taintedArgumentKeys中=TAINTED，否则=CLEAN
            val isTainted = request.isTainted(paramName)
            val actualSource = ArgumentSource.fromTaintStatus(isTainted)
            
            // 检查来源是否满足要求
            if (actualSource.level < rule.requiredSource.level) {
                val violation = TaintViolation(
                    parameterName = paramName,
                    actualSource = actualSource,
                    requiredSource = rule.requiredSource,
                    reason = rule.reason,
                    isWarning = !rule.enforce
                )
                
                if (rule.enforce) {
                    violations.add(violation)
                    Log.w(TAG, "Taint violation: $violation")
                } else {
                    warnings.add(violation)
                    Log.i(TAG, "Taint warning: $violation")
                }
            }
        }
        
        // 3. 返回结果
        if (violations.isNotEmpty()) {
            return ValidationResult(
                code = ValidationResult.TAINT_VIOLATION,
                message = buildViolationMessage(violations),
                taintResult = TaintValidationResult.failed(violations)
            )
        }
        
        if (warnings.isNotEmpty()) {
            return ValidationResult(
                code = ValidationResult.VALID,
                message = "Validation passed with warnings",
                taintResult = TaintValidationResult(
                    passed = true,
                    warnings = warnings
                )
            )
        }
        
        return ValidationResult(
            code = ValidationResult.VALID,
            message = "All taint rules satisfied",
            taintResult = TaintValidationResult.passed()
        )
    }
    
    /**
     * 校验参数来源与用户意图的一致性
     * 
     * @param request 动作请求
     * @param originalIntent 用户原始意图描述
     * @return 一致性检查结果
     */
    fun validateIntentConsistency(
        request: ActionRequest,
        originalIntent: String?
    ): ConsistencyCheckResult {
        return IntentConsistencyChecker.checkConsistency(
            actionName = request.actionName,
            arguments = extractArguments(request.arguments),
            originalIntent = originalIntent
        )
    }
    
    /**
     * 批量校验多个请求
     */
    fun validateBatch(requests: List<ActionRequest>): List<ValidationResult> {
        return requests.map { validate(it) }
    }
    
    /**
     * 构建违规消息
     */
    private fun buildViolationMessage(violations: List<TaintViolation>): String {
        val sb = StringBuilder("Taint rule violations detected:\n")
        violations.forEach { violation ->
            sb.append("  - ${violation}\n")
        }
        return sb.toString().trim()
    }
    
    /**
     * 从Bundle提取参数为Map
     */
    private fun extractArguments(bundle: android.os.Bundle): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        bundle.keySet().forEach { key ->
            bundle.get(key)?.let { result[key] = it }
        }
        return result
    }
}

/**
 * 校验结果
 */
data class ValidationResult(
    /**
     * 结果码
     */
    val code: Int,
    
    /**
     * 结果消息
     */
    val message: String,
    
    /**
     * Taint校验详细结果
     */
    val taintResult: TaintValidationResult? = null
) {
    companion object {
        const val VALID = 0                    // 校验通过
        const val TAINT_VIOLATION = 1          // Taint规则违规
        const val INTENT_INCONSISTENT = 2      // 意图不一致
        const val PARAMETER_MISSING = 3        // 参数缺失
        const val PARAMETER_TYPE_ERROR = 4     // 参数类型错误
    }
    
    val isValid: Boolean
        get() = code == VALID
}

/**
 * 参数来源标注器
 * 
 * 辅助工具，用于为ActionRequest标注参数来源
 */
object ArgumentSourceAnnotator {
    /**
     * 创建带来源标注的ActionRequest
     */
    fun createRequest(
        actionName: String,
        sessionId: String,
        arguments: Map<String, Any>,
        sources: Map<String, ArgumentSource>
    ): ActionRequest {
        val sourcesMap = sources.mapValues { it.value.name }
        return ActionRequest(
            actionName = actionName,
            sessionId = sessionId,
            arguments = arguments,
            argumentSources = sourcesMap
        )
    }
    
    /**
     * 为参数批量标注TRUSTED源
     */
    fun markAsTrusted(arguments: Map<String, Any>): Map<String, String> {
        return arguments.keys.associateWith { ArgumentSource.TRUSTED.name }
    }
    
    /**
     * 为参数批量标注USER源
     */
    fun markAsUser(arguments: Map<String, Any>): Map<String, String> {
        return arguments.keys.associateWith { ArgumentSource.USER.name }
    }
    
    /**
     * 混合标注（不同参数不同来源）
     */
    fun markMixed(
        trustedParams: Set<String> = emptySet(),
        userParams: Set<String> = emptySet(),
        anyParams: Set<String> = emptySet()
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        trustedParams.forEach { result[it] = ArgumentSource.TRUSTED.name }
        userParams.forEach { result[it] = ArgumentSource.USER.name }
        anyParams.forEach { result[it] = ArgumentSource.ANY.name }
        return result
    }
}

/**
 * Taint规则构建器
 * 
 * 辅助工具，用于声明式构建taint规则
 */
class TaintRuleBuilder(private val actionName: String) {
    private val rules = mutableListOf<TaintRule>()
    
    /**
     * 要求参数为TRUSTED源
     */
    fun requireTrusted(paramName: String, reason: String, enforce: Boolean = true): TaintRuleBuilder {
        rules.add(TaintRule(paramName, ArgumentSource.TRUSTED, reason, enforce))
        return this
    }
    
    /**
     * 要求参数为USER源
     */
    fun requireUser(paramName: String, reason: String, enforce: Boolean = true): TaintRuleBuilder {
        rules.add(TaintRule(paramName, ArgumentSource.USER, reason, enforce))
        return this
    }
    
    /**
     * 要求参数为ANY源（明确声明允许任意源）
     */
    fun allowAny(paramName: String, reason: String): TaintRuleBuilder {
        rules.add(TaintRule(paramName, ArgumentSource.ANY, reason, enforce = false))
        return this
    }
    
    /**
     * 构建并注册规则
     */
    fun build() {
        TaintRuleSet.registerRules(actionName, rules)
    }
    
    /**
     * 仅构建规则（不注册）
     */
    fun buildRules(): List<TaintRule> {
        return rules.toList()
    }
}

/**
 * Taint规则DSL扩展
 * 
 * 提供声明式语法糖
 */
object TaintRuleDsl {
    /**
     * 为动作定义taint规则
     * 
     * 示例：
     * ```
     * taintRules("notification.reply") {
     *     requireUser("reply_text", "Reply must come from user input")
     *     allowAny("notification_id", "ID can be from any source")
     * }
     * ```
     */
    fun taintRules(actionName: String, block: TaintRuleBuilder.() -> Unit) {
        val builder = TaintRuleBuilder(actionName)
        builder.block()
        builder.build()
    }
}

/**
 * Taint规则加载器
 * 
 * 从配置文件或注册表加载taint规则
 */
object TaintRuleLoader {
    /**
     * 从动作注册表加载taint规则
     * 
     * 注册表中的inputSource字段声明了参数来源要求：
     * ```json
     * {
     *   "name": "notification.reply",
     *   "parameters": [
     *     {
     *       "name": "reply_text",
     *       "type": "string",
     *       "inputSource": "USER"
     *     }
     *   ]
     * }
     * ```
     */
    fun loadFromRegistry(registry: ActionRegistry) {
        // 遍历注册表中的所有动作
        registry.listAllActions().forEach { entry ->
            val rules = mutableListOf<TaintRule>()
            
            // 提取参数的inputSource声明
            entry.parameters.forEach { param ->
                val inputSource = param.inputSource
                if (inputSource != null) {
                    val requiredSource = ArgumentSource.fromString(inputSource)
                    rules.add(
                        TaintRule(
                            parameterName = param.name,
                            requiredSource = requiredSource,
                            reason = "Parameter '${param.name}' requires $requiredSource source (from registry)",
                            enforce = true
                        )
                    )
                }
            }
            
            // 注册规则
            if (rules.isNotEmpty()) {
                TaintRuleSet.registerRules(entry.name, rules)
            }
        }
    }
    
    /**
     * 加载默认规则
     */
    fun loadDefaultRules() {
        TaintRuleSet.initializeDefaultRules()
    }
}

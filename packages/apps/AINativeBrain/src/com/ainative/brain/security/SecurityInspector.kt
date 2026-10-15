package com.ainative.brain.security

import android.util.Log
import org.json.JSONObject

/**
 * 安检与打标模块
 * 
 * 职责：
 * 1. TrustLevel 分流：根据感知内容的可信度分流给模型
 * 2. Taint 打标：将派生参数标记写入 ActionRequest.taintedArgumentKeys
 * 3. 安全策略检查：防止提示注入和意图篡改
 */
class SecurityInspector {
    
    companion object {
        private const val TAG = "SecurityInspector"
    }
    
    /**
     * TrustLevel 枚举（对应 A3.4）
     */
    enum class TrustLevel(val level: Int) {
        TRUSTED_SYSTEM(0),    // 系统感知（UI 树/通知元数据）
        USER_INPUT(1),        // 用户显式输入（语音/文字）
        AGENT_DERIVED(2),     // Agent 推理派生
        APP_CONTENT(3),       // App 内容（通知正文/页面文本）
        WEB_CONTENT(4);       // Web 内容（浏览器页面）
        
        companion object {
            fun fromLevel(level: Int): TrustLevel {
                return values().find { it.level == level } ?: WEB_CONTENT
            }
        }
    }
    
    /**
     * 执行安检
     * 
     * @param perceptionData 感知数据（包含 TrustLevel）
     * @param userIntent 用户原始意图
     * @param derivedParams Agent 派生的参数
     * @return 安检结果
     */
    fun inspect(
        perceptionData: Map<String, Any>,
        userIntent: String?,
        derivedParams: Map<String, Any>
    ): InspectionResult {
        Log.i(TAG, "Performing security inspection...")
        
        val result = InspectionResult()
        
        // 1. TrustLevel 分流
        val trustLevelDistribution = classifyByTrustLevel(perceptionData)
        result.trustLevelDistribution = trustLevelDistribution
        
        // 2. 决定是否需要降级喂模型
        val shouldDowngrade = shouldDowngradeForModel(trustLevelDistribution)
        result.shouldDowngradeContent = shouldDowngrade
        
        if (shouldDowngrade) {
            Log.w(TAG, "Content contains untrusted sources, downgrading for model input")
            result.downgradedContent = downgradeContent(perceptionData)
        }
        
        // 3. Taint 打标：标记派生参数的来源
        val taintMap = buildTaintMap(derivedParams, perceptionData, userIntent)
        result.taintMap = taintMap
        
        // 4. 安全策略检查
        val violations = checkSecurityPolicy(derivedParams, taintMap, userIntent)
        result.violations = violations
        
        // 5. 判定是否通过安检
        result.passed = violations.isEmpty()
        
        if (!result.passed) {
            Log.w(TAG, "Security inspection failed: ${violations.size} violations")
            for (violation in violations) {
                Log.w(TAG, "  - ${violation.field}: ${violation.reason}")
            }
        } else {
            Log.i(TAG, "Security inspection passed")
        }
        
        return result
    }
    
    /**
     * 按 TrustLevel 分类感知数据
     */
    private fun classifyByTrustLevel(
        perceptionData: Map<String, Any>
    ): Map<TrustLevel, Int> {
        val distribution = mutableMapOf<TrustLevel, Int>()
        
        for ((key, value) in perceptionData) {
            // 提取 TrustLevel（假设感知数据带 _trustLevel 后缀）
            val trustLevelKey = "${key}_trustLevel"
            val trustLevelValue = perceptionData[trustLevelKey]
            
            val trustLevel = when (trustLevelValue) {
                is Int -> TrustLevel.fromLevel(trustLevelValue)
                is String -> TrustLevel.valueOf(trustLevelValue)
                else -> TrustLevel.AGENT_DERIVED // 默认
            }
            
            distribution[trustLevel] = distribution.getOrDefault(trustLevel, 0) + 1
        }
        
        Log.d(TAG, "TrustLevel distribution: $distribution")
        return distribution
    }
    
    /**
     * 判断是否需要降级内容喂模型
     * 
     * 策略：
     * - 如果包含 APP_CONTENT 或 WEB_CONTENT，需要降级
     * - 降级：移除或标注不可信内容
     */
    private fun shouldDowngradeForModel(
        distribution: Map<TrustLevel, Int>
    ): Boolean {
        return distribution.containsKey(TrustLevel.APP_CONTENT) ||
               distribution.containsKey(TrustLevel.WEB_CONTENT)
    }
    
    /**
     * 降级内容（移除或标注不可信来源）
     */
    private fun downgradeContent(
        perceptionData: Map<String, Any>
    ): Map<String, Any> {
        val downgraded = mutableMapOf<String, Any>()
        
        for ((key, value) in perceptionData) {
            if (key.endsWith("_trustLevel")) {
                continue
            }
            
            val trustLevelKey = "${key}_trustLevel"
            val trustLevelValue = perceptionData[trustLevelKey]
            
            val trustLevel = when (trustLevelValue) {
                is Int -> TrustLevel.fromLevel(trustLevelValue)
                is String -> TrustLevel.valueOf(trustLevelValue)
                else -> TrustLevel.AGENT_DERIVED
            }
            
            // 策略：APP_CONTENT/WEB_CONTENT 标注来源
            if (trustLevel == TrustLevel.APP_CONTENT || trustLevel == TrustLevel.WEB_CONTENT) {
                downgraded[key] = "[UNTRUSTED: $trustLevel] $value"
            } else {
                downgraded[key] = value
            }
        }
        
        return downgraded
    }
    
    /**
     * 构建 Taint 映射
     * 
     * 为每个派生参数标记其来源的 TrustLevel
     */
    private fun buildTaintMap(
        derivedParams: Map<String, Any>,
        perceptionData: Map<String, Any>,
        userIntent: String?
    ): Map<String, TaintEntry> {
        val taintMap = mutableMapOf<String, TaintEntry>()
        
        for ((paramKey, paramValue) in derivedParams) {
            // 推断参数来源
            val sourceInfo = inferParameterSource(
                paramKey = paramKey,
                paramValue = paramValue,
                perceptionData = perceptionData,
                userIntent = userIntent
            )
            
            taintMap[paramKey] = TaintEntry(
                argumentKey = paramKey,
                sourceLevel = sourceInfo.trustLevel.level,
                sourceTrustLevel = sourceInfo.trustLevel,
                sourceDescription = sourceInfo.description
            )
        }
        
        Log.d(TAG, "Built taint map with ${taintMap.size} entries")
        return taintMap
    }
    
    /**
     * 推断参数来源
     */
    private fun inferParameterSource(
        paramKey: String,
        paramValue: Any,
        perceptionData: Map<String, Any>,
        userIntent: String?
    ): SourceInfo {
        // 1. 检查是否来自用户意图
        if (userIntent != null && paramValue.toString() in userIntent) {
            return SourceInfo(
                trustLevel = TrustLevel.USER_INPUT,
                description = "Derived from user intent"
            )
        }
        
        // 2. 检查是否来自感知数据
        for ((dataKey, dataValue) in perceptionData) {
            if (dataKey.endsWith("_trustLevel")) {
                continue
            }
            
            if (dataValue == paramValue || dataValue.toString() == paramValue.toString()) {
                // 找到来源数据，提取其 TrustLevel
                val trustLevelKey = "${dataKey}_trustLevel"
                val trustLevelValue = perceptionData[trustLevelKey]
                
                val trustLevel = when (trustLevelValue) {
                    is Int -> TrustLevel.fromLevel(trustLevelValue)
                    is String -> TrustLevel.valueOf(trustLevelValue)
                    else -> TrustLevel.AGENT_DERIVED
                }
                
                return SourceInfo(
                    trustLevel = trustLevel,
                    description = "Derived from perception data: $dataKey"
                )
            }
        }
        
        // 3. 默认：Agent 推理派生
        return SourceInfo(
            trustLevel = TrustLevel.AGENT_DERIVED,
            description = "Agent inference derived"
        )
    }
    
    /**
     * 检查安全策略
     */
    private fun checkSecurityPolicy(
        derivedParams: Map<String, Any>,
        taintMap: Map<String, TaintEntry>,
        userIntent: String?
    ): List<SecurityViolation> {
        val violations = mutableListOf<SecurityViolation>()
        
        // 策略 1: 高风险参数必须来自可信来源
        for ((paramKey, taintEntry) in taintMap) {
            if (isHighRiskParameter(paramKey)) {
                // 高风险参数要求 ≤ USER_INPUT 等级
                if (taintEntry.sourceLevel > TrustLevel.USER_INPUT.level) {
                    violations.add(
                        SecurityViolation(
                            field = paramKey,
                            reason = "High-risk parameter must come from USER_INPUT or higher trust level",
                            actualTrustLevel = taintEntry.sourceTrustLevel,
                            requiredTrustLevel = TrustLevel.USER_INPUT,
                            suggestedMitigation = "deny_execution"
                        )
                    )
                }
            }
        }
        
        // 策略 2: 敏感参数必须与用户意图一致
        if (userIntent != null) {
            for ((paramKey, paramValue) in derivedParams) {
                if (isSensitiveParameter(paramKey)) {
                    // 检查参数值是否在用户意图中出现
                    val paramStr = paramValue.toString()
                    if (paramStr.isNotEmpty() && paramStr !in userIntent) {
                        violations.add(
                            SecurityViolation(
                                field = paramKey,
                                reason = "Sensitive parameter not mentioned in user intent: '$paramStr'",
                                actualTrustLevel = taintMap[paramKey]?.sourceTrustLevel ?: TrustLevel.AGENT_DERIVED,
                                requiredTrustLevel = TrustLevel.USER_INPUT,
                                suggestedMitigation = "require_user_confirmation"
                            )
                        )
                    }
                }
            }
        }
        
        // 策略 3: APP_CONTENT/WEB_CONTENT 不得直接用于敏感操作
        for ((paramKey, taintEntry) in taintMap) {
            if (taintEntry.sourceLevel >= TrustLevel.APP_CONTENT.level) {
                if (isSensitiveParameter(paramKey)) {
                    violations.add(
                        SecurityViolation(
                            field = paramKey,
                            reason = "Sensitive parameter cannot come from APP_CONTENT or WEB_CONTENT",
                            actualTrustLevel = taintEntry.sourceTrustLevel,
                            requiredTrustLevel = TrustLevel.AGENT_DERIVED,
                            suggestedMitigation = "deny_execution"
                        )
                    )
                }
            }
        }
        
        return violations
    }
    
    /**
     * 判断是否为高风险参数
     */
    private fun isHighRiskParameter(paramKey: String): Boolean {
        val highRiskKeys = setOf(
            "packageName", "package",
            "amount", "price", "money",
            "recipient", "target", "contact",
            "permissions", "grant"
        )
        return paramKey.toLowerCase() in highRiskKeys
    }
    
    /**
     * 判断是否为敏感参数
     */
    private fun isSensitiveParameter(paramKey: String): Boolean {
        val sensitiveKeys = setOf(
            "recipient", "target", "contact", "to",
            "amount", "price", "money",
            "message", "text", "content",
            "url", "link"
        )
        return paramKey.toLowerCase() in sensitiveKeys
    }
    
    /**
     * 安检结果
     */
    data class InspectionResult(
        var passed: Boolean = true,
        var trustLevelDistribution: Map<TrustLevel, Int> = emptyMap(),
        var shouldDowngradeContent: Boolean = false,
        var downgradedContent: Map<String, Any>? = null,
        var taintMap: Map<String, TaintEntry> = emptyMap(),
        var violations: List<SecurityViolation> = emptyList()
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("passed", passed)
            json.put("should_downgrade_content", shouldDowngradeContent)
            json.put("violation_count", violations.size)
            return json.toString(2)
        }
    }
    
    /**
     * Taint 条目
     */
    data class TaintEntry(
        val argumentKey: String,
        val sourceLevel: Int,
        val sourceTrustLevel: TrustLevel,
        val sourceDescription: String
    )
    
    /**
     * 来源信息
     */
    private data class SourceInfo(
        val trustLevel: TrustLevel,
        val description: String
    )
    
    /**
     * 安全违规
     */
    data class SecurityViolation(
        val field: String,
        val reason: String,
        val actualTrustLevel: TrustLevel,
        val requiredTrustLevel: TrustLevel,
        val suggestedMitigation: String  // "deny_execution" | "require_user_confirmation"
    )
}

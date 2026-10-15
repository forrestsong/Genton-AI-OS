package com.genton.forms

import android.util.Log

/**
 * C6.3 预填源规则化 - 预填源校验器
 * 
 * 职责：
 * 1. 强制 secret 字段禁止预填（PrefillSource.NONE）
 * 2. 校验预填源优先级合法性
 * 3. 处理 A2/A3 阶段差异（S4_ENTITY 在 A3 期生效）
 * 4. 禁止 WEB/APP 预填 secret 字段
 * 
 * 预填源规则（D15 §6、C6.3）：
 * - USER_TYPED(10) - A2 期生效，用户本次会话手动输入
 * - USER_MEMORY(8) - A2 期生效，用户记忆层抽取
 * - S4_ENTITY(6) - A3 期生效（随 C3.3），置信度 ≥0.6
 * - WEB(4) - 禁用于 secret 字段
 * - APP(2) - 禁用于 secret 字段
 * - NONE(0) - secret 字段强制使用
 * 
 * 依赖关系：
 * - 前置：C6.1 FormSchemaSpec（PrefillSource 枚举定义）
 * - 供 FormPrefillManager 消费（预填时校验）
 * - 供 FormRenderer 消费（渲染时校验）
 * 
 * 版本：v1（A2 期基础版，A3 期增强 S4 实体预填）
 * 
 * @since A2
 */
object PrefillSourceValidator {
    
    private const val TAG = "PrefillSourceValidator"
    
    /**
     * 系统版本阶段（用于 S4_ENTITY 特性开关）
     */
    enum class SystemPhase {
        A1,  // 最小系统骨架
        A2,  // 读-写-界闭环（USER_TYPED/USER_MEMORY 生效）
        A3,  // 智能与端云（S4_ENTITY 生效）
        A4,  // 生态与主动服务
        A5   // 体验学习与加固
    }
    
    /**
     * 当前系统阶段（默认 A2，实际应从系统属性读取）
     */
    @Volatile
    private var currentPhase: SystemPhase = SystemPhase.A2
    
    /**
     * 设置当前系统阶段（供系统初始化调用）
     */
    fun setSystemPhase(phase: SystemPhase) {
        currentPhase = phase
        Log.i(TAG, "System phase updated to: $phase")
    }
    
    /**
     * 获取当前系统阶段
     */
    fun getCurrentPhase(): SystemPhase = currentPhase
    
    /**
     * 校验结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val effectiveSource: PrefillSource,  // 实际生效的预填源
        val reason: String? = null            // 校验失败原因
    ) {
        companion object {
            fun valid(source: PrefillSource) = ValidationResult(true, source, null)
            fun invalid(declaredSource: PrefillSource, reason: String) = 
                ValidationResult(false, declaredSource, reason)
        }
    }
    
    /**
     * 校验预填源合法性（核心方法）
     * 
     * @param field 字段定义
     * @param declaredSource 声明的预填源
     * @return 校验结果
     */
    fun validate(field: FieldSchema, declaredSource: PrefillSource): ValidationResult {
        // 规则 1: secret 字段强制 NONE（最高优先级）
        if (field.xSecret) {
            if (declaredSource != PrefillSource.NONE) {
                Log.w(TAG, "Secret field '${field.name}' declared prefill source $declaredSource, forcing NONE")
                return ValidationResult(
                    isValid = false,
                    effectiveSource = PrefillSource.NONE,
                    reason = "Secret fields cannot be prefilled (security constraint)"
                )
            }
            return ValidationResult.valid(PrefillSource.NONE)
        }
        
        // 规则 2: password 格式字段禁止预填
        if (field.format == "password") {
            if (declaredSource != PrefillSource.NONE) {
                Log.w(TAG, "Password field '${field.name}' declared prefill source $declaredSource, forcing NONE")
                return ValidationResult(
                    isValid = false,
                    effectiveSource = PrefillSource.NONE,
                    reason = "Password fields cannot be prefilled"
                )
            }
            return ValidationResult.valid(PrefillSource.NONE)
        }
        
        // 规则 3: S4_ENTITY 仅在 A3 期及以后生效
        if (declaredSource == PrefillSource.S4_ENTITY && currentPhase < SystemPhase.A3) {
            Log.w(TAG, "S4_ENTITY prefill for field '${field.name}' not available in phase $currentPhase, downgrading to NONE")
            return ValidationResult(
                isValid = false,
                effectiveSource = PrefillSource.NONE,
                reason = "S4_ENTITY prefill requires system phase A3 or later (current: $currentPhase)"
            )
        }
        
        // 规则 4: WEB/APP 禁用于敏感字段（防注入）
        if (declaredSource in setOf(PrefillSource.WEB, PrefillSource.APP)) {
            if (isSensitiveField(field)) {
                Log.w(TAG, "Sensitive field '${field.name}' cannot use WEB/APP prefill, downgrading to NONE")
                return ValidationResult(
                    isValid = false,
                    effectiveSource = PrefillSource.NONE,
                    reason = "WEB/APP prefill not allowed for sensitive fields"
                )
            }
        }
        
        // 规则 5: 校验优先级合法性（优先级必须在有效范围内）
        if (declaredSource.priority < 0 || declaredSource.priority > 10) {
            Log.e(TAG, "Invalid prefill source priority: ${declaredSource.priority} for field '${field.name}'")
            return ValidationResult(
                isValid = false,
                effectiveSource = PrefillSource.NONE,
                reason = "Invalid prefill source priority: ${declaredSource.priority}"
            )
        }
        
        // 通过所有规则
        return ValidationResult.valid(declaredSource)
    }
    
    /**
     * 判断字段是否为敏感字段（不允许 WEB/APP 预填）
     */
    private fun isSensitiveField(field: FieldSchema): Boolean {
        // 敏感字段特征
        val sensitiveNames = setOf(
            "password", "passwd", "pwd",
            "credit_card", "card_number", "cvv", "cvc",
            "ssn", "social_security",
            "pin", "otp", "verification_code",
            "api_key", "secret_key", "access_token"
        )
        
        val sensitiveFormats = setOf(
            "password", "secret", "credential"
        )
        
        // 检查字段名（不区分大小写）
        if (sensitiveNames.any { field.name.contains(it, ignoreCase = true) }) {
            return true
        }
        
        // 检查格式
        if (field.format in sensitiveFormats) {
            return true
        }
        
        // xSecret 已在规则 1 处理，此处不重复
        return false
    }
    
    /**
     * 批量校验表单所有字段
     * 
     * @param schema 表单 schema
     * @return 字段名 → 校验结果映射
     */
    fun validateForm(schema: FormSchema): Map<String, ValidationResult> {
        val results = mutableMapOf<String, ValidationResult>()
        
        schema.fields.forEach { field ->
            val declaredSource = PrefillSource.fromString(field.xPrefillSource)
            val result = validate(field, declaredSource)
            results[field.name] = result
            
            if (!result.isValid) {
                Log.w(TAG, "Field '${field.name}' prefill validation failed: ${result.reason}")
            }
        }
        
        return results
    }
    
    /**
     * 获取字段的有效预填源（考虑系统阶段和安全约束）
     * 
     * @param field 字段定义
     * @return 有效的预填源
     */
    fun getEffectivePrefillSource(field: FieldSchema): PrefillSource {
        val declaredSource = PrefillSource.fromString(field.xPrefillSource)
        val result = validate(field, declaredSource)
        return result.effectiveSource
    }
    
    /**
     * 检查预填源是否在当前系统阶段可用
     * 
     * @param source 预填源
     * @return true 如果可用
     */
    fun isPrefillSourceAvailable(source: PrefillSource): Boolean {
        return when (source) {
            PrefillSource.USER_TYPED, PrefillSource.USER_MEMORY -> {
                // A2 期及以后生效
                currentPhase >= SystemPhase.A2
            }
            PrefillSource.S4_ENTITY -> {
                // A3 期及以后生效（随 C3.3 并入）
                currentPhase >= SystemPhase.A3
            }
            PrefillSource.WEB, PrefillSource.APP -> {
                // A2 期生效（但受敏感字段约束）
                currentPhase >= SystemPhase.A2
            }
            PrefillSource.NONE -> {
                // 始终可用
                true
            }
        }
    }
    
    /**
     * 获取当前系统阶段可用的预填源列表
     * 
     * @return 可用的预填源列表（按优先级降序）
     */
    fun getAvailablePrefillSources(): List<PrefillSource> {
        return PrefillSource.values()
            .filter { isPrefillSourceAvailable(it) }
            .sortedByDescending { it.priority }
    }
    
    /**
     * 校验 S4 实体预填的置信度门槛
     * 
     * @param confidence S4 实体置信度
     * @return true 如果满足门槛（≥0.6）
     */
    fun validateS4Confidence(confidence: Float): Boolean {
        val threshold = 0.6f  // 对齐 C3.3 打包策略
        if (confidence < threshold) {
            Log.d(TAG, "S4 entity confidence $confidence below threshold $threshold, rejecting")
            return false
        }
        return true
    }
    
    /**
     * 获取预填源降级链（优先级降序）
     * 
     * 用于预填失败时的降级策略：按优先级依次尝试更低优先级的源
     * 
     * @param startSource 起始预填源
     * @param field 字段定义（用于约束检查）
     * @return 可尝试的预填源链
     */
    fun getPrefillDegradationChain(startSource: PrefillSource, field: FieldSchema): List<PrefillSource> {
        // 获取所有可用的预填源
        val availableSources = getAvailablePrefillSources()
            .filter { it.priority <= startSource.priority }  // 只降级，不升级
        
        // 过滤掉不满足约束的源
        return availableSources.filter { source ->
            val result = validate(field, source)
            result.isValid || result.effectiveSource == source
        }
    }
    
    /**
     * 生成预填源规则摘要（调试用）
     */
    fun getRulesSummary(): String {
        return """
            |预填源规则化摘要 (Phase: $currentPhase)
            |================================================
            |1. secret 字段强制 NONE（安全约束）
            |2. password 格式字段禁止预填
            |3. S4_ENTITY 仅在 A3 期及以后生效（需 C3.3）
            |4. WEB/APP 禁用于敏感字段（防注入）
            |5. S4 实体置信度门槛：≥0.6
            |
            |当前可用预填源：
            |${getAvailablePrefillSources().joinToString("\n") { "  - $it (priority=${it.priority})" }}
        """.trimMargin()
    }
}

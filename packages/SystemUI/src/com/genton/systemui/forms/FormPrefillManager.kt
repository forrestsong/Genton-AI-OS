package com.genton.systemui.forms

import android.util.Log
import com.genton.forms.FieldSchema
import com.genton.forms.PrefillSource
import org.json.JSONObject

/**
 * D5.3 表单预填展示
 * 
 * 职责：
 * 1. USER 来源预填（USER_TYPED/USER_MEMORY）展示和管理
 * 2. prefilled 标记（区分预填值和用户输入值）
 * 3. 一键清除预填（保留用户输入）
 * 4. S4 实体预填支持（A3 期增强，依赖 C3.2/C3.3）
 * 
 * 设计原则（D15 §6、C6.3）：
 * - 预填源优先级：USER_TYPED(10) > USER_MEMORY(8) > S4_ENTITY(6) > WEB(4) > APP(2)
 * - secret 字段禁止预填（安全约束）
 * - 预填值可见标记（蓝色高亮 + "已自动填充" 提示）
 * - 用户编辑后自动取消预填标记（转为用户输入）
 * 
 * 依赖关系：
 * - 前置：C6.3 预填源规则化（预填优先级定义）
 * - 消费：D5.1 FormRenderer（预填值展示）
 * - 增强：C3.2 实体抽取（S4 实体预填，A3 期）
 * 
 * 版本：v1（A2 期基础版，A3 期增强 S4 实体预填）
 * 
 * @since A2
 */
class FormPrefillManager {
    
    companion object {
        private const val TAG = "FormPrefillManager"
    }
    
    /**
     * 预填值数据
     */
    data class PrefillValue(
        val value: String,
        val source: PrefillSource,
        val confidence: Float,          // 置信度（S4 实体预填时使用）
        val sourceLabel: String,        // 来源标签（显示用）
        val retrievedAtMs: Long = System.currentTimeMillis()
    ) {
        /**
         * 是否应显示预填提示
         */
        fun shouldShowHint(): Boolean {
            // 只有非 NONE 来源才显示提示
            return source != PrefillSource.NONE
        }
        
        /**
         * 是否可被用户清除
         */
        fun isClearable(): Boolean {
            return source != PrefillSource.NONE
        }
    }
    
    /**
     * 预填结果
     */
    data class PrefillResult(
        val fieldValues: Map<String, PrefillValue>,
        val totalFields: Int,
        val prefilledCount: Int,
        val skippedSecretCount: Int
    )
    
    /**
     * 预填数据源接口
     */
    interface PrefillDataSource {
        /**
         * 查询用户记忆
         */
        fun queryUserMemory(key: String): String?
        
        /**
         * 查询 S4 实体（A3 期增强）
         */
        fun queryS4Entity(entityType: String, attributeKey: String): Pair<String, Float>?
        
        /**
         * 查询用户本次会话输入（USER_TYPED）
         */
        fun queryUserTyped(key: String): String?
    }
    
    /**
     * 为表单字段预填数据
     * 
     * @param fields 表单字段列表
     * @param dataSource 预填数据源
     * @return 预填结果
     */
    fun prefillFields(
        fields: List<FieldSchema>,
        dataSource: PrefillDataSource
    ): PrefillResult {
        val fieldValues = mutableMapOf<String, PrefillValue>()
        var prefilledCount = 0
        var skippedSecretCount = 0
        
        for (field in fields) {
            // 1. 获取声明的预填源
            val declaredSource = PrefillSource.fromString(field.xPrefillSource)
            
            // 2. 校验预填源合法性（C6.3 规则化）
            val validationResult = PrefillSourceValidator.validate(field, declaredSource)
            val effectiveSource = validationResult.effectiveSource
            
            // 3. 如果校验失败且有效源为 NONE，记录并跳过
            if (!validationResult.isValid) {
                if (effectiveSource == PrefillSource.NONE) {
                    if (field.xSecret || field.format == "password") {
                        skippedSecretCount++
                    }
                    Log.w(TAG, "Skipped prefill for field '${field.name}': ${validationResult.reason}")
                    continue
                }
            }
            
            // 4. 有效源为 NONE，跳过
            if (effectiveSource == PrefillSource.NONE) {
                continue
            }
            
            // 5. 根据有效预填源查询预填值
            val prefillValue = queryPrefillValue(field, effectiveSource, dataSource)
            
            if (prefillValue != null) {
                fieldValues[field.name] = prefillValue
                prefilledCount++
                Log.d(TAG, "Prefilled field: ${field.name} from ${prefillValue.source} (value=${prefillValue.value.take(20)}...)")
            }
        }
        
        return PrefillResult(
            fieldValues = fieldValues,
            totalFields = fields.size,
            prefilledCount = prefilledCount,
            skippedSecretCount = skippedSecretCount
        )
    }
    
    /**
     * 查询预填值（按优先级降级策略）
     * 
     * C6.3 规则化：使用 PrefillSourceValidator 获取降级链
     */
    private fun queryPrefillValue(
        field: FieldSchema,
        effectiveSource: PrefillSource,
        dataSource: PrefillDataSource
    ): PrefillValue? {
        val prefillKey = field.xPrefillKey ?: field.name
        
        // 使用 C6.3 规则化的降级链（考虑系统阶段和安全约束）
        val sources = PrefillSourceValidator.getPrefillDegradationChain(effectiveSource, field)
        
        for (source in sources) {
            // 检查源是否在当前系统阶段可用
            if (!PrefillSourceValidator.isPrefillSourceAvailable(source)) {
                Log.d(TAG, "Prefill source $source not available in current phase, skipping")
                continue
            }
            
            val result = when (source) {
                PrefillSource.USER_TYPED -> {
                    dataSource.queryUserTyped(prefillKey)?.let {
                        PrefillValue(it, source, 1.0f, "本次输入")
                    }
                }
                PrefillSource.USER_MEMORY -> {
                    dataSource.queryUserMemory(prefillKey)?.let {
                        PrefillValue(it, source, 0.9f, "记忆")
                    }
                }
                PrefillSource.S4_ENTITY -> {
                    // A3 期增强：从 C3.2 实体抽取 + C3.3 打包图查询
                    val entityType = inferEntityType(field)
                    dataSource.queryS4Entity(entityType, prefillKey)?.let { (value, confidence) ->
                        // C6.3 规则：置信度门槛 ≥0.6（对齐 C3.3 打包策略）
                        if (PrefillSourceValidator.validateS4Confidence(confidence)) {
                            PrefillValue(value, source, confidence, "智能推荐")
                        } else {
                            Log.d(TAG, "S4 entity confidence $confidence below threshold for field '${field.name}'")
                            null
                        }
                    }
                }
                PrefillSource.WEB -> {
                    // WEB 预填（需谨慎，已通过 C6.3 敏感字段检查）
                    // TODO A2: Web 爬取预填实现
                    Log.d(TAG, "WEB prefill not implemented yet for field '${field.name}'")
                    null
                }
                PrefillSource.APP -> {
                    // APP 内部状态预填（已通过 C6.3 敏感字段检查）
                    // TODO A2: App 内部状态预填实现
                    Log.d(TAG, "APP prefill not implemented yet for field '${field.name}'")
                    null
                }
                else -> null
            }
            
            if (result != null) {
                Log.d(TAG, "Successfully prefilled field '${field.name}' from source $source")
                return result
            }
        }
        
        Log.d(TAG, "No prefill value found for field '${field.name}' after trying ${sources.size} sources")
        return null
    }
    
    /**
     * 推断字段对应的实体类型（用于 S4 实体查询）
     */
    private fun inferEntityType(field: FieldSchema): String {
        // 简化推断规则（可扩展）
        return when {
            field.format == "email" -> "person"
            field.format == "phone" || field.format == "tel" -> "person"
            field.name.contains("name", ignoreCase = true) -> "person"
            field.name.contains("address", ignoreCase = true) -> "location"
            field.name.contains("company", ignoreCase = true) -> "organization"
            field.name.contains("event", ignoreCase = true) -> "event"
            else -> "entity" // 兜底
        }
    }
    
    /**
     * 构造预填数据映射（供 FormRenderer 使用）
     */
    fun buildPrefillDataMap(result: PrefillResult): Map<String, String> {
        return result.fieldValues.mapValues { it.value.value }
    }
    
    /**
     * 获取预填源标签（用户友好显示）
     */
    fun getPrefillSourceLabel(source: PrefillSource): String {
        return when (source) {
            PrefillSource.USER_TYPED -> "本次输入"
            PrefillSource.USER_MEMORY -> "历史记忆"
            PrefillSource.S4_ENTITY -> "智能推荐"
            PrefillSource.WEB -> "网页数据"
            PrefillSource.APP -> "应用数据"
            PrefillSource.NONE -> ""
        }
    }
    
    /**
     * 获取预填提示文本
     */
    fun getPrefillHintText(prefillValue: PrefillValue): String {
        return when (prefillValue.source) {
            PrefillSource.USER_TYPED -> "来自您本次输入"
            PrefillSource.USER_MEMORY -> "来自您的历史记忆"
            PrefillSource.S4_ENTITY -> {
                if (prefillValue.confidence >= 0.8f) {
                    "智能推荐（高置信度）"
                } else {
                    "智能推荐（中等置信度）"
                }
            }
            PrefillSource.WEB -> "来自网页数据"
            PrefillSource.APP -> "来自应用数据"
            PrefillSource.NONE -> ""
        }
    }
    
    /**
     * 清除指定字段的预填值
     */
    fun clearPrefill(
        fieldName: String,
        formState: FormRenderer.FormState
    ) {
        formState.clearPrefilled(fieldName)
        Log.d(TAG, "Cleared prefill for field: $fieldName")
    }
    
    /**
     * 批量清除所有预填值
     */
    fun clearAllPrefills(formState: FormRenderer.FormState) {
        val prefilledFields = formState.prefilledFields.toList()
        prefilledFields.forEach { fieldName ->
            formState.clearPrefilled(fieldName)
        }
        Log.d(TAG, "Cleared all prefills: ${prefilledFields.size} fields")
    }
    
    /**
     * 检测字段值变更（用户编辑后取消预填标记）
     */
    fun onFieldValueChanged(
        fieldName: String,
        newValue: String,
        formState: FormRenderer.FormState
    ) {
        // 如果是预填字段且值被修改，取消预填标记
        if (formState.prefilledFields.contains(fieldName)) {
            val originalPrefillValue = formState.getValue(fieldName)
            if (newValue != originalPrefillValue) {
                formState.prefilledFields.remove(fieldName)
                Log.d(TAG, "User edited prefilled field: $fieldName, removed prefill marker")
            }
        }
    }
}

/**
 * Mock 预填数据源（供测试使用）
 */
class MockPrefillDataSource : FormPrefillManager.PrefillDataSource {
    
    private val userMemoryStore = mutableMapOf<String, String>()
    private val userTypedStore = mutableMapOf<String, String>()
    private val s4EntityStore = mutableMapOf<String, Pair<String, Float>>()
    
    override fun queryUserMemory(key: String): String? {
        return userMemoryStore[key]
    }
    
    override fun queryS4Entity(entityType: String, attributeKey: String): Pair<String, Float>? {
        return s4EntityStore["$entityType:$attributeKey"]
    }
    
    override fun queryUserTyped(key: String): String? {
        return userTypedStore[key]
    }
    
    /**
     * 设置 mock 数据
     */
    fun setUserMemory(key: String, value: String) {
        userMemoryStore[key] = value
    }
    
    fun setUserTyped(key: String, value: String) {
        userTypedStore[key] = value
    }
    
    fun setS4Entity(entityType: String, attributeKey: String, value: String, confidence: Float) {
        s4EntityStore["$entityType:$attributeKey"] = value to confidence
    }
}

/**
 * 预填统计（用于调试/优化）
 */
data class PrefillStatistics(
    val totalAttempts: Int,
    val successCount: Int,
    val successRate: Float,
    val bySource: Map<PrefillSource, Int>,
    val avgConfidence: Float
) {
    companion object {
        fun calculate(results: List<FormPrefillManager.PrefillResult>): PrefillStatistics {
            val totalAttempts = results.sumOf { it.totalFields }
            val successCount = results.sumOf { it.prefilledCount }
            val successRate = if (totalAttempts > 0) successCount.toFloat() / totalAttempts else 0f
            
            val bySource = mutableMapOf<PrefillSource, Int>()
            var totalConfidence = 0f
            var confidenceCount = 0
            
            for (result in results) {
                for ((_, prefillValue) in result.fieldValues) {
                    bySource[prefillValue.source] = (bySource[prefillValue.source] ?: 0) + 1
                    totalConfidence += prefillValue.confidence
                    confidenceCount++
                }
            }
            
            val avgConfidence = if (confidenceCount > 0) totalConfidence / confidenceCount else 0f
            
            return PrefillStatistics(
                totalAttempts = totalAttempts,
                successCount = successCount,
                successRate = successRate,
                bySource = bySource,
                avgConfidence = avgConfidence
            )
        }
    }
}

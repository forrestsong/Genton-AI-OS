package com.ainative.brain.schema

import org.json.JSONObject

/**
 * C6.1 系统固定 schema 体系 - 预填规则与元数据
 * 
 * 职责：
 * 1. 定义预填数据来源和优先级规则
 * 2. 管理表单元数据（FormMeta）
 * 3. 支持多来源预填合并策略
 * 
 * 预填来源优先级（从高到低）：
 * 1. USER_TYPED - 用户语音/文字明确提供的值（优先级 1.0）
 * 2. USER_MEMORY - 用户记忆中的偏好（优先级 0.9）
 * 3. ENTITY - 对话中提取的实体（优先级 0.8）
 * 4. WEB - Web搜索结果（优先级 0.6）
 * 5. APP - 应用状态（当前位置、联系人等）（优先级 0.7）
 * 6. DEFAULT - Schema默认值（优先级 0.3）
 */

/**
 * 预填来源
 */
object PrefillSource {
    const val USER_TYPED = "USER_TYPED"  // 用户明确输入（语音/文字）
    const val USER_MEMORY = "USER_MEMORY"  // 用户记忆/偏好
    const val ENTITY = "ENTITY"  // 实体提取
    const val WEB = "WEB"  // Web搜索
    const val APP = "APP"  // 应用状态
    const val DEFAULT = "DEFAULT"  // Schema默认值
    const val UNKNOWN = "UNKNOWN"  // 未知来源
    
    /**
     * 获取来源默认优先级
     */
    fun getDefaultPriority(source: String): Float {
        return when (source) {
            USER_TYPED -> 1.0f
            USER_MEMORY -> 0.9f
            ENTITY -> 0.8f
            APP -> 0.7f
            WEB -> 0.6f
            DEFAULT -> 0.3f
            else -> 0.1f
        }
    }
}

/**
 * 预填值
 */
data class PrefillValue(
    val fieldName: String,  // 字段名
    val value: Any,  // 预填值
    val source: String,  // 来源
    val priority: Float,  // 优先级 0-1
    val confidence: Float = 1.0f,  // 置信度 0-1
    val reasoning: String? = null  // 推理说明（可选）
) {
    companion object {
        /**
         * 从 JSON 解析
         */
        fun fromJson(json: JSONObject): PrefillValue {
            return PrefillValue(
                fieldName = json.getString("fieldName"),
                value = json.get("value"),
                source = json.getString("source"),
                priority = json.optDouble("priority", 0.5).toFloat(),
                confidence = json.optDouble("confidence", 1.0).toFloat(),
                reasoning = json.optString("reasoning", null)
            )
        }
    }
    
    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("fieldName", fieldName)
        json.put("value", value)
        json.put("source", source)
        json.put("priority", priority.toDouble())
        json.put("confidence", confidence.toDouble())
        if (reasoning != null) {
            json.put("reasoning", reasoning)
        }
        return json
    }
    
    /**
     * 计算有效优先级（优先级 × 置信度）
     */
    fun getEffectivePriority(): Float {
        return priority * confidence
    }
}

/**
 * 预填策略
 */
object PrefillStrategy {
    
    /**
     * 合并多个预填值（同一字段的多个来源）
     * 
     * 策略：选择有效优先级最高的值
     */
    fun mergeValues(values: List<PrefillValue>): PrefillValue? {
        if (values.isEmpty()) return null
        
        return values.maxByOrNull { it.getEffectivePriority() }
    }
    
    /**
     * 应用预填值到表单数据
     * 
     * @param schema 表单schema
     * @param prefillValues 预填值列表
     * @param existingData 已有数据（用户已填写的字段）
     * @return 合并后的表单数据
     */
    fun applyPrefill(
        schema: FormSchema,
        prefillValues: List<PrefillValue>,
        existingData: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val result = existingData.toMutableMap()
        
        // 按字段分组预填值
        val valuesByField = prefillValues.groupBy { it.fieldName }
        
        valuesByField.forEach { (fieldName, values) ->
            // 跳过用户已填写的字段
            if (result.containsKey(fieldName)) {
                return@forEach
            }
            
            // 获取字段定义
            val field = schema.getField(fieldName)
            if (field == null) {
                return@forEach
            }
            
            // 检查字段是否允许预填
            if (field.xAinative?.secret == true) {
                // 敏感字段不预填
                return@forEach
            }
            
            // 合并多个来源的值
            val selectedValue = mergeValues(values)
            if (selectedValue != null) {
                result[fieldName] = selectedValue.value
            }
        }
        
        // 应用schema默认值（最低优先级）
        schema.properties.forEach { (fieldName, field) ->
            if (!result.containsKey(fieldName) && field.default != null) {
                result[fieldName] = field.default
            }
        }
        
        return result
    }
    
    /**
     * 生成预填说明文本
     */
    fun generateExplanation(prefillValues: List<PrefillValue>): String {
        if (prefillValues.isEmpty()) {
            return "表单已为您准备好"
        }
        
        val sourceGroups = prefillValues.groupBy { it.source }
        val parts = mutableListOf<String>()
        
        sourceGroups.forEach { (source, values) ->
            val fieldNames = values.map { it.fieldName }.take(3)
            val sourceText = when (source) {
                PrefillSource.USER_TYPED -> "根据您提供的信息"
                PrefillSource.USER_MEMORY -> "根据您的偏好"
                PrefillSource.ENTITY -> "根据对话内容"
                PrefillSource.APP -> "根据当前状态"
                PrefillSource.WEB -> "根据网络查询"
                else -> "自动"
            }
            
            if (values.size <= 3) {
                parts.add("$sourceText 填写了${fieldNames.joinToString("、")}")
            } else {
                parts.add("$sourceText 填写了${fieldNames.joinToString("、")}等${values.size}个字段")
            }
        }
        
        return parts.joinToString("，")
    }
}

/**
 * 表单元数据管理器（FormMeta）
 */
class FormMetaManager {
    
    // 已注册的表单schema缓存
    private val schemaRegistry = mutableMapOf<String, FormSchema>()
    
    /**
     * 注册表单schema
     */
    fun registerSchema(schema: FormSchema) {
        schemaRegistry[schema.formId] = schema
    }
    
    /**
     * 获取表单schema
     */
    fun getSchema(formId: String): FormSchema? {
        return schemaRegistry[formId]
    }
    
    /**
     * 取消注册表单schema
     */
    fun unregisterSchema(formId: String) {
        schemaRegistry.remove(formId)
    }
    
    /**
     * 从资产元数据生成表单schema
     * 
     * @param assetMeta 资产元数据（D11/D12/D15约定）
     * @return 表单schema
     */
    fun generateSchemaFromAsset(assetMeta: JSONObject): FormSchema {
        // TODO: 实际实现需要根据资产schema约定解析
        // 当前简化实现：假设资产元数据已包含表单定义
        
        val formId = assetMeta.getString("formId")
        val title = assetMeta.getString("title")
        val submitAction = assetMeta.getJSONObject("action").getString("name")
        
        val properties = mutableMapOf<String, FormField>()
        val fieldsArray = assetMeta.getJSONArray("fields")
        for (i in 0 until fieldsArray.length()) {
            val fieldJson = fieldsArray.getJSONObject(i)
            val fieldName = fieldJson.getString("name")
            properties[fieldName] = FormField.fromJson(fieldJson)
        }
        
        val required = mutableListOf<String>()
        val requiredArray = assetMeta.optJSONArray("required")
        if (requiredArray != null) {
            for (i in 0 until requiredArray.length()) {
                required.add(requiredArray.getString(i))
            }
        }
        
        return FormSchema(
            formId = formId,
            title = title,
            properties = properties,
            required = required,
            xAinative = FormMetadata(
                submitAction = submitAction,
                cancelable = assetMeta.optBoolean("cancelable", true),
                submitLabel = assetMeta.optString("submitLabel", "提交"),
                layout = assetMeta.optString("layout", "vertical")
            )
        )
    }
    
    /**
     * 准备表单实例（应用预填）
     */
    fun prepareFormInstance(
        formId: String,
        prefillValues: List<PrefillValue> = emptyList(),
        existingData: Map<String, Any?> = emptyMap()
    ): FormInstance? {
        val schema = getSchema(formId) ?: return null
        
        // 应用预填
        val data = PrefillStrategy.applyPrefill(schema, prefillValues, existingData)
        
        // 生成预填说明
        val explanation = PrefillStrategy.generateExplanation(prefillValues)
        
        return FormInstance(
            formId = formId,
            schema = schema,
            data = data.toMutableMap(),
            prefillValues = prefillValues,
            explanation = explanation
        )
    }
    
    /**
     * 获取所有已注册的表单ID
     */
    fun getAllFormIds(): Set<String> {
        return schemaRegistry.keys.toSet()
    }
    
    /**
     * 清空注册表
     */
    fun clear() {
        schemaRegistry.clear()
    }
}

/**
 * 表单实例（运行时）
 */
data class FormInstance(
    val formId: String,
    val schema: FormSchema,
    val data: MutableMap<String, Any?>,  // 当前表单数据
    val prefillValues: List<PrefillValue>,  // 预填值
    val explanation: String,  // 预填说明
    val instanceId: String = "form_${System.currentTimeMillis()}",  // 实例ID
    var isDirty: Boolean = false,  // 是否有修改
    var validationResult: FormValidationResult? = null  // 最后的校验结果
) {
    
    /**
     * 更新字段值
     */
    fun updateField(fieldName: String, value: Any?) {
        data[fieldName] = value
        isDirty = true
        validationResult = null  // 清空校验结果
    }
    
    /**
     * 批量更新字段
     */
    fun updateFields(updates: Map<String, Any?>) {
        data.putAll(updates)
        isDirty = true
        validationResult = null
    }
    
    /**
     * 获取字段值
     */
    fun getFieldValue(fieldName: String): Any? {
        return data[fieldName]
    }
    
    /**
     * 校验表单
     */
    fun validate(): FormValidationResult {
        val result = schema.validate(data)
        validationResult = result
        return result
    }
    
    /**
     * 是否可以提交
     */
    fun canSubmit(): Boolean {
        val result = validationResult ?: validate()
        return result.isValid
    }
    
    /**
     * 获取提交数据（仅包含有值的字段）
     */
    fun getSubmitData(): Map<String, Any> {
        return data.filterValues { it != null }.mapValues { it.value!! }
    }
    
    /**
     * 重置表单
     */
    fun reset() {
        data.clear()
        isDirty = false
        validationResult = null
        
        // 重新应用预填
        val prefilled = PrefillStrategy.applyPrefill(schema, prefillValues, emptyMap())
        data.putAll(prefilled)
    }
    
    /**
     * 转换为JSON（用于序列化）
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("formId", formId)
        json.put("instanceId", instanceId)
        json.put("data", JSONObject(data))
        json.put("isDirty", isDirty)
        json.put("explanation", explanation)
        return json
    }
}

/**
 * 预填数据构建器
 */
class PrefillBuilder(private val formId: String) {
    private val values = mutableListOf<PrefillValue>()
    
    /**
     * 添加用户输入的值
     */
    fun addUserTyped(fieldName: String, value: Any, reasoning: String? = null): PrefillBuilder {
        values.add(PrefillValue(
            fieldName = fieldName,
            value = value,
            source = PrefillSource.USER_TYPED,
            priority = PrefillSource.getDefaultPriority(PrefillSource.USER_TYPED),
            reasoning = reasoning
        ))
        return this
    }
    
    /**
     * 添加用户记忆的值
     */
    fun addUserMemory(fieldName: String, value: Any, confidence: Float = 1.0f): PrefillBuilder {
        values.add(PrefillValue(
            fieldName = fieldName,
            value = value,
            source = PrefillSource.USER_MEMORY,
            priority = PrefillSource.getDefaultPriority(PrefillSource.USER_MEMORY),
            confidence = confidence
        ))
        return this
    }
    
    /**
     * 添加实体提取的值
     */
    fun addEntity(fieldName: String, value: Any, confidence: Float = 0.8f): PrefillBuilder {
        values.add(PrefillValue(
            fieldName = fieldName,
            value = value,
            source = PrefillSource.ENTITY,
            priority = PrefillSource.getDefaultPriority(PrefillSource.ENTITY),
            confidence = confidence
        ))
        return this
    }
    
    /**
     * 添加应用状态的值
     */
    fun addAppState(fieldName: String, value: Any): PrefillBuilder {
        values.add(PrefillValue(
            fieldName = fieldName,
            value = value,
            source = PrefillSource.APP,
            priority = PrefillSource.getDefaultPriority(PrefillSource.APP)
        ))
        return this
    }
    
    /**
     * 添加Web查询的值
     */
    fun addWebResult(fieldName: String, value: Any, confidence: Float = 0.7f): PrefillBuilder {
        values.add(PrefillValue(
            fieldName = fieldName,
            value = value,
            source = PrefillSource.WEB,
            priority = PrefillSource.getDefaultPriority(PrefillSource.WEB),
            confidence = confidence
        ))
        return this
    }
    
    /**
     * 构建预填值列表
     */
    fun build(): List<PrefillValue> {
        return values.toList()
    }
}

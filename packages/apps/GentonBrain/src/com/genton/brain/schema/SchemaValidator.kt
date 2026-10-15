package com.genton.brain.schema

import android.util.Log

/**
 * C6.1 系统固定 schema 体系 - Schema 校验器
 * 
 * 职责：
 * 1. 校验表单 schema 定义的合法性
 * 2. 校验表单数据的完整性和正确性
 * 3. 提供详细的校验错误信息
 * 4. 支持自定义校验规则
 * 
 * 符合规范：
 * - JSON Schema Draft 7 校验规则
 * - x-genton 扩展校验
 * - 业务逻辑校验
 */
class SchemaValidator {
    
    companion object {
        private const val TAG = "SchemaValidator"
    }
    
    /**
     * 校验表单 schema 定义
     */
    fun validateSchema(schema: FormSchema): SchemaValidationResult {
        val errors = mutableListOf<String>()
        
        // 1. 基础字段校验
        if (schema.formId.isBlank()) {
            errors.add("formId 不能为空")
        }
        
        if (schema.title.isBlank()) {
            errors.add("title 不能为空")
        }
        
        if (schema.type != "object") {
            errors.add("根类型必须为 object")
        }
        
        // 2. 属性校验
        if (schema.properties.isEmpty()) {
            errors.add("properties 不能为空，至少需要一个字段")
        }
        
        // 3. 必填字段校验
        schema.required.forEach { fieldName ->
            if (!schema.properties.containsKey(fieldName)) {
                errors.add("必填字段 $fieldName 在 properties 中未定义")
            }
        }
        
        // 4. 字段定义校验
        schema.properties.forEach { (fieldName, field) ->
            val fieldErrors = validateField(fieldName, field)
            errors.addAll(fieldErrors)
        }
        
        // 5. 元数据校验
        val metaErrors = validateMetadata(schema.xGenton)
        errors.addAll(metaErrors)
        
        // 6. 控件映射校验
        schema.properties.forEach { (fieldName, field) ->
            try {
                val controlType = ControlMapper.mapFieldToControl(field)
                if (!ControlType.isValid(controlType)) {
                    errors.add("字段 $fieldName 映射到无效的控件类型: $controlType")
                }
            } catch (e: Exception) {
                errors.add("字段 $fieldName 控件映射失败: ${e.message}")
            }
        }
        
        return SchemaValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
    
    /**
     * 校验字段定义
     */
    private fun validateField(fieldName: String, field: FormField): List<String> {
        val errors = mutableListOf<String>()
        
        // 类型校验
        val validTypes = setOf("string", "number", "integer", "boolean", "array", "object")
        if (field.type !in validTypes) {
            errors.add("字段 $fieldName 的类型无效: ${field.type}")
        }
        
        // 标题校验
        if (field.title.isBlank()) {
            errors.add("字段 $fieldName 的 title 不能为空")
        }
        
        // 长度校验
        if (field.minLength != null && field.minLength < 0) {
            errors.add("字段 $fieldName 的 minLength 不能为负数")
        }
        
        if (field.maxLength != null && field.maxLength < 0) {
            errors.add("字段 $fieldName 的 maxLength 不能为负数")
        }
        
        if (field.minLength != null && field.maxLength != null && field.minLength > field.maxLength) {
            errors.add("字段 $fieldName 的 minLength 不能大于 maxLength")
        }
        
        // 数值范围校验
        if (field.minimum != null && field.maximum != null && field.minimum.toDouble() > field.maximum.toDouble()) {
            errors.add("字段 $fieldName 的 minimum 不能大于 maximum")
        }
        
        // 数组类型校验
        if (field.type == "array" && field.items == null) {
            errors.add("字段 $fieldName 是数组类型，必须定义 items")
        }
        
        // 对象类型校验
        if (field.type == "object" && field.properties == null) {
            errors.add("字段 $fieldName 是对象类型，必须定义 properties")
        }
        
        // 枚举值校验
        if (field.enum != null && field.enum.isEmpty()) {
            errors.add("字段 $fieldName 的 enum 不能为空列表")
        }
        
        // 正则表达式校验
        if (field.pattern != null) {
            try {
                Regex(field.pattern)
            } catch (e: Exception) {
                errors.add("字段 $fieldName 的 pattern 不是有效的正则表达式: ${field.pattern}")
            }
        }
        
        // 元数据校验
        if (field.xGenton != null) {
            val metaErrors = validateFieldMetadata(fieldName, field.xGenton)
            errors.addAll(metaErrors)
        }
        
        return errors
    }
    
    /**
     * 校验表单元数据
     */
    private fun validateMetadata(metadata: FormMetadata): List<String> {
        val errors = mutableListOf<String>()
        
        // 提交动作校验
        if (metadata.submitAction.isBlank()) {
            errors.add("submitAction 不能为空")
        }
        
        // 布局校验
        val validLayouts = setOf("vertical", "horizontal", "grid")
        if (metadata.layout !in validLayouts) {
            errors.add("layout 必须是以下之一: ${validLayouts.joinToString(", ")}")
        }
        
        // 校验模式校验
        val validModes = setOf("onSubmit", "onChange", "onBlur")
        if (metadata.validationMode !in validModes) {
            errors.add("validationMode 必须是以下之一: ${validModes.joinToString(", ")}")
        }
        
        return errors
    }
    
    /**
     * 校验字段元数据
     */
    private fun validateFieldMetadata(fieldName: String, metadata: FieldMetadata): List<String> {
        val errors = mutableListOf<String>()
        
        // 控件类型校验
        if (!ControlType.isValid(metadata.control)) {
            errors.add("字段 $fieldName 的控件类型无效: ${metadata.control}")
        }
        
        // 预填优先级校验
        if (metadata.prefillPriority < 0f || metadata.prefillPriority > 1f) {
            errors.add("字段 $fieldName 的 prefillPriority 必须在 0-1 之间")
        }
        
        // 预填来源校验
        if (metadata.prefillSource != null) {
            val validSources = setOf(
                PrefillSource.USER_TYPED,
                PrefillSource.USER_MEMORY,
                PrefillSource.ENTITY,
                PrefillSource.WEB,
                PrefillSource.APP,
                PrefillSource.DEFAULT
            )
            if (metadata.prefillSource !in validSources) {
                errors.add("字段 $fieldName 的 prefillSource 无效: ${metadata.prefillSource}")
            }
        }
        
        // 冲突校验
        if (metadata.disabled && metadata.autofocus) {
            errors.add("字段 $fieldName 不能同时设置 disabled 和 autofocus")
        }
        
        if (metadata.readonly && metadata.secret) {
            errors.add("字段 $fieldName 不能同时设置 readonly 和 secret")
        }
        
        return errors
    }
    
    /**
     * 校验表单数据
     */
    fun validateData(schema: FormSchema, data: Map<String, Any?>): FormValidationResult {
        return schema.validate(data)
    }
    
    /**
     * 校验预填值
     */
    fun validatePrefillValues(
        schema: FormSchema,
        prefillValues: List<PrefillValue>
    ): PrefillValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        prefillValues.forEach { prefill ->
            // 1. 字段存在性校验
            val field = schema.getField(prefill.fieldName)
            if (field == null) {
                errors.add("预填字段 ${prefill.fieldName} 在 schema 中不存在")
                return@forEach
            }
            
            // 2. 类型兼容性校验（简化）
            try {
                val fieldErrors = field.validate(prefill.value)
                if (fieldErrors.isNotEmpty()) {
                    warnings.add("预填字段 ${prefill.fieldName} 的值可能不符合约束: ${fieldErrors.joinToString(", ") { it.second }}")
                }
            } catch (e: Exception) {
                warnings.add("预填字段 ${prefill.fieldName} 的值校验失败: ${e.message}")
            }
            
            // 3. 优先级校验
            if (prefill.priority < 0f || prefill.priority > 1f) {
                errors.add("预填字段 ${prefill.fieldName} 的优先级必须在 0-1 之间")
            }
            
            if (prefill.confidence < 0f || prefill.confidence > 1f) {
                errors.add("预填字段 ${prefill.fieldName} 的置信度必须在 0-1 之间")
            }
            
            // 4. 敏感字段校验
            if (field.xGenton?.secret == true) {
                warnings.add("预填字段 ${prefill.fieldName} 是敏感字段，不应预填")
            }
        }
        
        // 5. 检查必填字段覆盖
        val prefillFieldNames = prefillValues.map { it.fieldName }.toSet()
        val missingRequired = schema.required.filter { it !in prefillFieldNames }
        if (missingRequired.isNotEmpty()) {
            warnings.add("以下必填字段未提供预填值: ${missingRequired.joinToString(", ")}")
        }
        
        return PrefillValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * 批量校验表单定义
     */
    fun validateMultipleSchemas(schemas: List<FormSchema>): Map<String, SchemaValidationResult> {
        val results = mutableMapOf<String, SchemaValidationResult>()
        
        schemas.forEach { schema ->
            results[schema.formId] = validateSchema(schema)
        }
        
        // 检查重复的 formId
        val formIds = schemas.map { it.formId }
        val duplicates = formIds.groupingBy { it }.eachCount().filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            Log.w(TAG, "发现重复的 formId: ${duplicates.keys.joinToString(", ")}")
        }
        
        return results
    }
    
    /**
     * 生成校验报告
     */
    fun generateReport(schema: FormSchema): String {
        val result = validateSchema(schema)
        
        val report = StringBuilder()
        report.appendLine("表单 Schema 校验报告")
        report.appendLine("=" .repeat(50))
        report.appendLine("表单ID: ${schema.formId}")
        report.appendLine("标题: ${schema.title}")
        report.appendLine("字段数: ${schema.properties.size}")
        report.appendLine("必填字段数: ${schema.required.size}")
        report.appendLine()
        
        if (result.isValid) {
            report.appendLine("✓ 校验通过")
        } else {
            report.appendLine("✗ 校验失败")
            report.appendLine()
            report.appendLine("错误列表:")
            result.errors.forEachIndexed { index, error ->
                report.appendLine("  ${index + 1}. $error")
            }
        }
        
        report.appendLine()
        report.appendLine("字段详情:")
        schema.properties.forEach { (fieldName, field) ->
            val controlType = try {
                ControlMapper.mapFieldToControl(field)
            } catch (e: Exception) {
                "ERROR"
            }
            
            val requiredMark = if (schema.isRequired(fieldName)) "*" else ""
            report.appendLine("  - $fieldName$requiredMark: ${field.type} → $controlType")
        }
        
        return report.toString()
    }
}

/**
 * Schema 校验结果
 */
data class SchemaValidationResult(
    val isValid: Boolean,
    val errors: List<String>
) {
    /**
     * 获取摘要信息
     */
    fun getSummary(): String {
        return if (isValid) {
            "Schema 校验通过"
        } else {
            "Schema 校验失败，共 ${errors.size} 个错误"
        }
    }
}

/**
 * 预填值校验结果
 */
data class PrefillValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String> = emptyList()
) {
    /**
     * 获取摘要信息
     */
    fun getSummary(): String {
        val parts = mutableListOf<String>()
        
        if (errors.isNotEmpty()) {
            parts.add("${errors.size} 个错误")
        }
        
        if (warnings.isNotEmpty()) {
            parts.add("${warnings.size} 个警告")
        }
        
        return if (parts.isEmpty()) {
            "预填值校验通过"
        } else {
            "预填值校验: ${parts.joinToString(", ")}"
        }
    }
}

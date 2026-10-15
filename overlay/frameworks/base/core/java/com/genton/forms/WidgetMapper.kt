package com.genton.forms

/**
 * C6.1 控件映射器
 * 
 * 职责：
 * schema (type/format/x-genton) → WidgetType 的确定性纯函数映射
 * 
 * 设计原则：
 * 1. 纯函数：相同输入永远产生相同输出，无环境依赖
 * 2. 完备性：任何合法 schema 都能映射到唯一 WidgetType
 * 3. 优先级：x-genton-widget > format > type（显式覆盖 > 格式推导 > 类型默认）
 * 4. 安全优先：x-genton-secret=true 或 format=password → 自动升级到安全控件
 * 
 * 映射表（D15 §4.2）：
 * 
 * | type    | format      | x-genton-widget | x-secret | → WidgetType          |
 * |---------|-------------|-------------------|----------|-----------------------|
 * | string  | -           | -                 | false    | TEXT_SINGLE_LINE      |
 * | string  | textarea    | -                 | false    | TEXT_MULTI_LINE       |
 * | string  | email       | -                 | false    | EMAIL                 |
 * | string  | phone       | -                 | false    | PHONE                 |
 * | string  | uri         | -                 | false    | URL                   |
 * | string  | password    | -                 | *        | PASSWORD              |
 * | string  | -           | -                 | true     | SECRET_TEXT           |
 * | string  | -           | credential        | *        | CREDENTIAL_PICKER     |
 * | string  | date        | -                 | false    | DATE                  |
 * | string  | time        | -                 | false    | TIME                  |
 * | string  | date-time   | -                 | false    | DATE_TIME             |
 * | string  | binary      | -                 | false    | FILE_PICKER           |
 * | string  | color       | -                 | false    | COLOR_PICKER          |
 * | number  | -           | -                 | false    | NUMBER                |
 * | number  | -           | slider            | false    | SLIDER                |
 * | integer | -           | -                 | false    | NUMBER                |
 * | integer | -           | stepper           | false    | STEPPER               |
 * | boolean | -           | -                 | false    | CHECKBOX              |
 * | string  | enum ≤5     | -                 | false    | RADIO                 |
 * | string  | enum >5     | -                 | false    | SELECT                |
 * | array   | enum        | -                 | false    | MULTI_SELECT          |
 * 
 * @since A2
 */
object WidgetMapper {
    
    /**
     * 核心映射函数：schema → WidgetType
     * 
     * @param field 字段 schema
     * @return 确定的控件类型
     */
    fun map(field: FieldSchema): WidgetType {
        // 优先级 1: x-genton-widget 显式覆盖
        val explicitWidget = field.xWidget?.let { mapExplicitWidget(it) }
        if (explicitWidget != null) {
            // 安全约束：即使显式指定，secret=true 也强制升级
            return if (field.xSecret) upgradToSecret(explicitWidget) else explicitWidget
        }
        
        // 优先级 2: x-genton-secret=true 强制安全控件
        if (field.xSecret) {
            return SECRET_TEXT
        }
        
        // 优先级 3: format 推导
        val formatWidget = field.format?.let { mapFormat(it, field) }
        if (formatWidget != null) {
            return formatWidget
        }
        
        // 优先级 4: type 默认
        return mapType(field)
    }
    
    /**
     * 显式控件映射（x-genton-widget）
     */
    private fun mapExplicitWidget(widget: String): WidgetType? {
        return when (widget.lowercase()) {
            "slider" -> WidgetType.SLIDER
            "stepper" -> WidgetType.STEPPER
            "radio" -> WidgetType.RADIO
            "select" -> WidgetType.SELECT
            "multiselect", "multi_select" -> WidgetType.MULTI_SELECT
            "textarea", "multiline" -> WidgetType.TEXT_MULTI_LINE
            "credential" -> WidgetType.CREDENTIAL_PICKER
            "file" -> WidgetType.FILE_PICKER
            "color" -> WidgetType.COLOR_PICKER
            "secret" -> WidgetType.SECRET_TEXT
            else -> null // 未识别的显式控件，回退到格式/类型推导
        }
    }
    
    /**
     * 格式推导（JSON Schema format）
     */
    private fun mapFormat(format: String, field: FieldSchema): WidgetType? {
        return when (format.lowercase()) {
            "email" -> WidgetType.EMAIL
            "phone", "tel" -> WidgetType.PHONE
            "uri", "url" -> WidgetType.URL
            "password" -> WidgetType.PASSWORD
            "date" -> WidgetType.DATE
            "time" -> WidgetType.TIME
            "date-time", "datetime" -> WidgetType.DATE_TIME
            "textarea" -> WidgetType.TEXT_MULTI_LINE
            "binary", "data-url" -> WidgetType.FILE_PICKER
            "color" -> WidgetType.COLOR_PICKER
            else -> null
        }
    }
    
    /**
     * 类型默认推导（JSON Schema type）
     */
    private fun mapType(field: FieldSchema): WidgetType {
        return when (field.type.lowercase()) {
            "string" -> {
                // enum 枚举：根据选项数量选择控件
                if (!field.enum.isNullOrEmpty()) {
                    if (field.enum.size <= 5) WidgetType.RADIO else WidgetType.SELECT
                } else {
                    WidgetType.TEXT_SINGLE_LINE
                }
            }
            "number", "integer" -> WidgetType.NUMBER
            "boolean" -> WidgetType.CHECKBOX
            "array" -> {
                // array + enum → MULTI_SELECT
                if (!field.enum.isNullOrEmpty()) {
                    WidgetType.MULTI_SELECT
                } else {
                    // 数组无枚举，回退到文本（用户输入逗号分隔）
                    WidgetType.TEXT_SINGLE_LINE
                }
            }
            else -> WidgetType.TEXT_SINGLE_LINE // 兜底
        }
    }
    
    /**
     * 升级到安全控件（x-secret=true 强制）
     */
    private fun upgradToSecret(original: WidgetType): WidgetType {
        return when (original) {
            WidgetType.CREDENTIAL_PICKER -> WidgetType.CREDENTIAL_PICKER // 已经是安全控件
            WidgetType.PASSWORD -> WidgetType.PASSWORD
            else -> WidgetType.SECRET_TEXT // 其他控件统一升级到 SECRET_TEXT
        }
    }
    
    /**
     * 批量映射（用于表单渲染器预计算）
     */
    fun mapAll(schema: FormSchema): Map<String, WidgetType> {
        return schema.fields.associate { field ->
            field.name to map(field)
        }
    }
    
    /**
     * 校验控件与字段类型兼容性（可选，用于开发期检查）
     */
    fun isCompatible(widget: WidgetType, field: FieldSchema): Boolean {
        // 简化版兼容性检查
        return when (widget) {
            WidgetType.NUMBER, WidgetType.SLIDER, WidgetType.STEPPER -> {
                field.type == "number" || field.type == "integer"
            }
            WidgetType.CHECKBOX -> field.type == "boolean"
            WidgetType.MULTI_SELECT -> field.type == "array" && !field.enum.isNullOrEmpty()
            WidgetType.RADIO, WidgetType.SELECT -> !field.enum.isNullOrEmpty()
            else -> true // 大部分文本类控件兼容 string
        }
    }
}

/**
 * 控件配置数据类（供 D5.1 渲染器使用）
 */
data class WidgetConfig(
    val widgetType: WidgetType,
    val field: FieldSchema,
    val prefillValue: String?,
    val isPrefilled: Boolean
) {
    /**
     * 是否可提交（客户端校验）
     */
    fun canSubmit(currentValue: String?): Boolean {
        // 必填校验
        if (field.required && currentValue.isNullOrBlank()) {
            return false
        }
        
        // secret 控件：值不可为空（如果有值）
        if (widgetType.isSecret() && !currentValue.isNullOrBlank()) {
            return true
        }
        
        return true
    }
    
    /**
     * 获取显示值（secret 控件脱敏）
     */
    fun getDisplayValue(currentValue: String?): String? {
        if (currentValue.isNullOrBlank()) return currentValue
        
        return if (widgetType.isSecret()) {
            "•".repeat(currentValue.length) // 脱敏显示
        } else {
            currentValue
        }
    }
}

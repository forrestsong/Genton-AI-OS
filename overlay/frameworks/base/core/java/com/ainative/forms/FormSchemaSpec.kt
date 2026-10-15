package com.ainative.forms

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * C6.1 表单 schema 体系核心规范
 * 
 * 职责：
 * 1. 定义 x-ainative 扩展约定（控件语义、预填源、安全约束）
 * 2. FormMeta 元数据结构（表单标识、版本、校验规则）
 * 3. schema ↔ 控件纯函数映射表（type/format/x-ainative → WidgetType）
 * 
 * 设计原则（D15 §4/§6）：
 * - 基于 JSON Schema Draft 2020-12，x-ainative 作为扩展命名空间
 * - 控件映射为确定性纯函数：schema → WidgetType，无环境依赖
 * - secret 控件值不可序列化、不可日志化、不可出进程（FLAG_SECURE 约束）
 * - 预填源分级：USER typed > USER memory > S4 entity > WEB/APP（secret 禁预填）
 * 
 * 依赖关系：
 * - 无前置依赖（D11/D12/D15 草案 schema 约定作为输入）
 * - 供 D5.1 表单渲染器消费
 * - 供 B3.2 FormSubmitRouter 校验
 * 
 * 版本：v1（A2 期，简化版；A3 期增强 S4 实体预填）
 * 
 * @since A2
 */

/**
 * x-ainative 扩展命名空间约定
 * 
 * 所有扩展字段以 x-ainative 为前缀，避免与标准 JSON Schema 字段冲突
 */
object XAinativeExtensions {
    // 控件语义扩展
    const val WIDGET = "x-ainative-widget"           // 指定控件类型（覆盖默认推导）
    const val WIDGET_HINT = "x-ainative-widget-hint" // 控件提示文本（用户友好）
    const val WIDGET_OPTIONS = "x-ainative-widget-options" // 控件选项（select/radio）
    
    // 预填源扩展
    const val PREFILL_SOURCE = "x-ainative-prefill-source" // 预填来源（USER_TYPED/USER_MEMORY/S4_ENTITY/NONE）
    const val PREFILL_KEY = "x-ainative-prefill-key"       // 预填键名（实体属性/记忆键）
    const val PREFILL_PRIORITY = "x-ainative-prefill-priority" // 预填优先级（1-10，USER_TYPED=10）
    
    // 安全约束扩展
    const val SECRET = "x-ainative-secret"           // 敏感值标记（true → FLAG_SECURE 控件）
    const val TAINT = "x-ainative-taint"             // taint 标记（传递给动作执行层）
    const val VALIDATE_LOCAL = "x-ainative-validate-local" // 本地校验规则（不出端）
    
    // 表单行为扩展
    const val SUBMIT_MODE = "x-ainative-submit-mode" // 提交模式（IMMEDIATE/CONFIRM/SCHEDULE）
    const val CANCEL_LABEL = "x-ainative-cancel-label" // 取消按钮文本
    const val SUBMIT_LABEL = "x-ainative-submit-label" // 提交按钮文本
}

/**
 * 预填源枚举（D15 §6）
 * 
 * 优先级顺序：USER_TYPED(10) > USER_MEMORY(8) > S4_ENTITY(6) > WEB(4) > APP(2) > NONE(0)
 * secret 字段强制 NONE
 */
enum class PrefillSource(val priority: Int) {
    USER_TYPED(10),      // 用户本次会话手动输入（最高优先级，A2 期生效）
    USER_MEMORY(8),      // 用户记忆层抽取（A2 期生效）
    S4_ENTITY(6),        // S4 实体预测（置信度 ≥0.6，A3 期随 C3.3 生效）
    WEB(4),              // Web 爬取（禁用于 secret）
    APP(2),              // App 内部状态（禁用于 secret）
    NONE(0);             // 无预填（secret 强制使用）
    
    companion object {
        fun fromString(value: String?): PrefillSource {
            return when (value?.uppercase()) {
                "USER_TYPED" -> USER_TYPED
                "USER_MEMORY" -> USER_MEMORY
                "S4_ENTITY" -> S4_ENTITY
                "WEB" -> WEB
                "APP" -> APP
                else -> NONE
            }
        }
    }
}

/**
 * 提交模式枚举
 */
enum class SubmitMode {
    IMMEDIATE,    // 立即提交（L0.5 免 LLM 管线）
    CONFIRM,      // 需确认（显示确认面板）
    SCHEDULE;     // 定时/延迟提交
    
    companion object {
        fun fromString(value: String?): SubmitMode {
            return when (value?.uppercase()) {
                "CONFIRM" -> CONFIRM
                "SCHEDULE" -> SCHEDULE
                else -> IMMEDIATE
            }
        }
    }
}

/**
 * 控件类型枚举
 * 
 * schema (type/format/x-ainative) → WidgetType 的确定性映射结果
 */
enum class WidgetType {
    // 文本输入族
    TEXT_SINGLE_LINE,     // string, no format
    TEXT_MULTI_LINE,      // string, format=textarea
    EMAIL,                // string, format=email
    PHONE,                // string, format=phone
    URL,                  // string, format=uri
    PASSWORD,             // string, format=password (自动 secret=true)
    
    // 数值输入族
    NUMBER,               // number/integer, no format
    SLIDER,               // number, x-ainative-widget=slider
    STEPPER,              // integer, x-ainative-widget=stepper
    
    // 日期时间族
    DATE,                 // string, format=date
    TIME,                 // string, format=time
    DATE_TIME,            // string, format=date-time
    
    // 选择族
    CHECKBOX,             // boolean
    RADIO,                // enum with ≤5 options
    SELECT,               // enum with >5 options
    MULTI_SELECT,         // array of enum
    
    // 特殊控件
    FILE_PICKER,          // string, format=binary or contentUri
    COLOR_PICKER,         // string, format=color
    
    // 安全控件（值不可序列化）
    SECRET_TEXT,          // string, x-ainative-secret=true
    CREDENTIAL_PICKER;    // string, x-ainative-widget=credential
    
    /**
     * 是否为敏感控件（值不可日志化、不可序列化）
     */
    fun isSecret(): Boolean {
        return this == PASSWORD || this == SECRET_TEXT || this == CREDENTIAL_PICKER
    }
}

/**
 * FormMeta 元数据
 * 
 * 每个表单 schema 的顶层元数据，用于标识表单来源、版本、校验规则
 */
@Parcelize
data class FormMeta(
    val formId: String,              // 表单唯一标识（如 "activate_suggestion_v1"）
    val formVersion: String,         // schema 版本（语义化版本号，如 "1.0.0"）
    val formTitle: String,           // 表单标题（用户可见）
    val formDescription: String?,    // 表单描述（可选）
    
    // 行为配置
    val submitMode: SubmitMode,      // 提交模式
    val submitLabel: String?,        // 提交按钮文本（null=默认"提交"）
    val cancelLabel: String?,        // 取消按钮文本（null=默认"取消"）
    
    // 校验配置
    val validateOnChange: Boolean,   // 是否在值变更时校验（默认 false）
    val validateOnSubmit: Boolean,   // 是否在提交时校验（默认 true）
    
    // 来源追踪（防注入）
    val provenance: String,          // 来源标注（system/agent/{agentId}/asset/{assetId}）
    val signatureHash: String?       // schema 签名哈希（策略包/资产签名）
) : Parcelable {
    companion object {
        /**
         * 系统内部表单（activate_suggestion/deactivate_rule 等）
         */
        fun systemForm(
            formId: String,
            formTitle: String,
            submitMode: SubmitMode = SubmitMode.IMMEDIATE
        ): FormMeta {
            return FormMeta(
                formId = formId,
                formVersion = "1.0.0",
                formTitle = formTitle,
                formDescription = null,
                submitMode = submitMode,
                submitLabel = null,
                cancelLabel = null,
                validateOnChange = false,
                validateOnSubmit = true,
                provenance = "system",
                signatureHash = null
            )
        }
    }
}

/**
 * 字段 schema 定义（简化版 JSON Schema）
 * 
 * 映射到控件的核心输入，包含类型、格式、约束、扩展字段
 */
@Parcelize
data class FieldSchema(
    val name: String,                // 字段名（JSON 键）
    val type: String,                // JSON Schema 类型（string/number/integer/boolean/array）
    val format: String?,             // JSON Schema format（email/phone/date/uri 等）
    
    // 显示属性
    val title: String,               // 字段标题（用户可见）
    val description: String?,        // 字段描述（可选）
    val placeholder: String?,        // 占位符文本
    
    // 约束属性
    val required: Boolean,           // 是否必填
    val minLength: Int?,             // 最小长度（string）
    val maxLength: Int?,             // 最大长度（string）
    val minimum: Double?,            // 最小值（number/integer）
    val maximum: Double?,            // 最大值（number/integer）
    val pattern: String?,            // 正则表达式（string）
    val enum: List<String>?,         // 枚举值（select/radio）
    
    // x-ainative 扩展
    val xWidget: String?,            // 覆盖控件类型
    val xWidgetHint: String?,        // 控件提示
    val xPrefillSource: String?,     // 预填源
    val xPrefillKey: String?,        // 预填键
    val xSecret: Boolean,            // 敏感标记
    val xTaint: Boolean,             // taint 标记
    
    // 默认值
    val defaultValue: String?        // 默认值（非预填）
) : Parcelable {
    companion object {
        /**
         * 快速构造器：文本字段
         */
        fun text(
            name: String,
            title: String,
            required: Boolean = false,
            maxLength: Int? = 255,
            prefillSource: PrefillSource = PrefillSource.NONE
        ): FieldSchema {
            return FieldSchema(
                name = name,
                type = "string",
                format = null,
                title = title,
                description = null,
                placeholder = null,
                required = required,
                minLength = null,
                maxLength = maxLength,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = null,
                xWidgetHint = null,
                xPrefillSource = prefillSource.name,
                xPrefillKey = null,
                xSecret = false,
                xTaint = false,
                defaultValue = null
            )
        }
        
        /**
         * 快速构造器：密码字段（自动 secret=true）
         */
        fun password(
            name: String,
            title: String,
            required: Boolean = true
        ): FieldSchema {
            return FieldSchema(
                name = name,
                type = "string",
                format = "password",
                title = title,
                description = null,
                placeholder = null,
                required = required,
                minLength = null,
                maxLength = null,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = null,
                xWidgetHint = null,
                xPrefillSource = PrefillSource.NONE.name, // secret 禁预填
                xPrefillKey = null,
                xSecret = true,
                xTaint = true,
                defaultValue = null
            )
        }
        
        /**
         * 快速构造器：枚举选择
         */
        fun select(
            name: String,
            title: String,
            options: List<String>,
            required: Boolean = false,
            defaultValue: String? = null
        ): FieldSchema {
            return FieldSchema(
                name = name,
                type = "string",
                format = null,
                title = title,
                description = null,
                placeholder = null,
                required = required,
                minLength = null,
                maxLength = null,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = options,
                xWidget = if (options.size <= 5) "radio" else null,
                xWidgetHint = null,
                xPrefillSource = null,
                xPrefillKey = null,
                xSecret = false,
                xTaint = false,
                defaultValue = defaultValue
            )
        }
    }
}

/**
 * 完整表单 schema
 */
@Parcelize
data class FormSchema(
    val meta: FormMeta,              // 表单元数据
    val fields: List<FieldSchema>    // 字段列表（保持顺序）
) : Parcelable {
    /**
     * 校验表单数据完整性
     * 
     * @return 校验错误列表（空列表=通过）
     */
    fun validate(data: Map<String, String>): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        for (field in fields) {
            val value = data[field.name]
            
            // 必填校验
            if (field.required && value.isNullOrBlank()) {
                errors.add(ValidationError(field.name, "字段不能为空"))
                continue
            }
            
            if (value.isNullOrBlank()) continue
            
            // 长度校验
            if (field.minLength != null && value.length < field.minLength) {
                errors.add(ValidationError(field.name, "长度不能少于 ${field.minLength} 个字符"))
            }
            if (field.maxLength != null && value.length > field.maxLength) {
                errors.add(ValidationError(field.name, "长度不能超过 ${field.maxLength} 个字符"))
            }
            
            // 正则校验
            if (field.pattern != null && !Regex(field.pattern).matches(value)) {
                errors.add(ValidationError(field.name, "格式不正确"))
            }
            
            // 枚举校验
            if (field.enum != null && !field.enum.contains(value)) {
                errors.add(ValidationError(field.name, "值必须是以下之一：${field.enum.joinToString(", ")}"))
            }
            
            // 数值范围校验（简化版）
            if (field.type == "number" || field.type == "integer") {
                val numValue = value.toDoubleOrNull()
                if (numValue == null) {
                    errors.add(ValidationError(field.name, "必须是数字"))
                } else {
                    if (field.minimum != null && numValue < field.minimum) {
                        errors.add(ValidationError(field.name, "不能小于 ${field.minimum}"))
                    }
                    if (field.maximum != null && numValue > field.maximum) {
                        errors.add(ValidationError(field.name, "不能大于 ${field.maximum}"))
                    }
                }
            }
        }
        
        return errors
    }
    
    /**
     * 提取所有 secret 字段名（用于安全通道路由）
     */
    fun getSecretFieldNames(): Set<String> {
        return fields.filter { it.xSecret || it.format == "password" }
            .map { it.name }
            .toSet()
    }
    
    /**
     * 提取所有 taint 字段名（用于动作执行层打标）
     */
    fun getTaintFieldNames(): Set<String> {
        return fields.filter { it.xTaint }
            .map { it.name }
            .toSet()
    }
}

/**
 * 校验错误
 */
@Parcelize
data class ValidationError(
    val fieldName: String,
    val message: String
) : Parcelable

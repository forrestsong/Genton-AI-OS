package com.genton.brain.schema

import org.json.JSONObject
import org.json.JSONArray

/**
 * C6.1 系统固定 schema 体系 - 表单 Schema 核心定义
 * 
 * 职责：
 * 1. 定义统一的表单 schema 结构
 * 2. 支持 x-genton 扩展约定
 * 3. 表单字段类型与控件映射
 * 4. 校验规则和预填规则
 * 
 * 符合规范：
 * - JSON Schema Draft 7 基础
 * - x-genton 自定义扩展
 * - 与 D11/D12/D15 资产 schema 对齐
 */

/**
 * 表单 Schema 根对象
 * 
 * 示例：
 * ```json
 * {
 *   "schema": "https://genton.os/schema/form/v1",
 *   "formId": "create_calendar_event",
 *   "title": "创建日程",
 *   "description": "创建新的日程事件",
 *   "type": "object",
 *   "properties": { ... },
 *   "required": ["title", "startTime"],
 *   "x-genton": {
 *     "submitAction": "calendar.create_event",
 *     "cancelable": true,
 *     "submitLabel": "创建",
 *     "layout": "vertical"
 *   }
 * }
 * ```
 */
data class FormSchema(
    val schema: String = "https://genton.os/schema/form/v1",
    val formId: String,  // 表单唯一标识
    val title: String,  // 表单标题
    val description: String? = null,  // 表单描述
    val type: String = "object",  // 根类型（固定为 object）
    val properties: Map<String, FormField>,  // 表单字段
    val required: List<String> = emptyList(),  // 必填字段列表
    val xGenton: FormMetadata  // x-genton 扩展元数据
) {
    companion object {
        const val SCHEMA_VERSION = "v1"
        const val SCHEMA_URI = "https://genton.os/schema/form/v1"
        
        /**
         * 从 JSON 解析
         */
        fun fromJson(json: JSONObject): FormSchema {
            val properties = mutableMapOf<String, FormField>()
            val propsObj = json.optJSONObject("properties")
            if (propsObj != null) {
                val keys = propsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    properties[key] = FormField.fromJson(propsObj.getJSONObject(key))
                }
            }
            
            val requiredArray = json.optJSONArray("required")
            val required = if (requiredArray != null) {
                (0 until requiredArray.length()).map { requiredArray.getString(it) }
            } else {
                emptyList()
            }
            
            return FormSchema(
                schema = json.optString("schema", SCHEMA_URI),
                formId = json.getString("formId"),
                title = json.getString("title"),
                description = json.optString("description", null),
                type = json.optString("type", "object"),
                properties = properties,
                required = required,
                xGenton = FormMetadata.fromJson(json.getJSONObject("x-genton"))
            )
        }
    }
    
    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("schema", schema)
        json.put("formId", formId)
        json.put("title", title)
        if (description != null) {
            json.put("description", description)
        }
        json.put("type", type)
        
        val propsObj = JSONObject()
        properties.forEach { (key, field) ->
            propsObj.put(key, field.toJson())
        }
        json.put("properties", propsObj)
        
        if (required.isNotEmpty()) {
            val reqArray = JSONArray(required)
            json.put("required", reqArray)
        }
        
        json.put("x-genton", xGenton.toJson())
        
        return json
    }
    
    /**
     * 获取字段
     */
    fun getField(fieldName: String): FormField? {
        return properties[fieldName]
    }
    
    /**
     * 检查字段是否必填
     */
    fun isRequired(fieldName: String): Boolean {
        return required.contains(fieldName)
    }
    
    /**
     * 校验表单数据
     */
    fun validate(data: Map<String, Any?>): FormValidationResult {
        val errors = mutableListOf<FieldError>()
        
        // 1. 检查必填字段
        required.forEach { fieldName ->
            if (!data.containsKey(fieldName) || data[fieldName] == null) {
                errors.add(FieldError(
                    fieldName = fieldName,
                    errorCode = "required",
                    message = "字段 $fieldName 是必填项"
                ))
            }
        }
        
        // 2. 校验各字段值
        data.forEach { (fieldName, value) ->
            val field = properties[fieldName]
            if (field != null && value != null) {
                val fieldErrors = field.validate(value)
                errors.addAll(fieldErrors.map { 
                    FieldError(fieldName, it.first, it.second)
                })
            }
        }
        
        return FormValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}

/**
 * 表单字段定义
 */
data class FormField(
    val type: String,  // 字段类型：string/number/integer/boolean/array/object
    val title: String,  // 字段标题
    val description: String? = null,  // 字段描述
    val default: Any? = null,  // 默认值
    val enum: List<Any>? = null,  // 枚举值列表
    val format: String? = null,  // 格式：date/time/datetime/email/phone/url 等
    val minLength: Int? = null,  // 最小长度
    val maxLength: Int? = null,  // 最大长度
    val minimum: Number? = null,  // 最小值
    val maximum: Number? = null,  // 最大值
    val pattern: String? = null,  // 正则表达式
    val items: FormField? = null,  // 数组项类型（type=array时）
    val properties: Map<String, FormField>? = null,  // 对象属性（type=object时）
    val xGenton: FieldMetadata? = null  // x-genton 扩展元数据
) {
    companion object {
        /**
         * 从 JSON 解析
         */
        fun fromJson(json: JSONObject): FormField {
            val enumList = json.optJSONArray("enum")?.let { arr ->
                (0 until arr.length()).map { arr.get(it) }
            }
            
            val itemsField = json.optJSONObject("items")?.let {
                fromJson(it)
            }
            
            val propertiesMap = json.optJSONObject("properties")?.let { propsObj ->
                val map = mutableMapOf<String, FormField>()
                val keys = propsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = fromJson(propsObj.getJSONObject(key))
                }
                map
            }
            
            return FormField(
                type = json.getString("type"),
                title = json.getString("title"),
                description = json.optString("description", null),
                default = json.opt("default"),
                enum = enumList,
                format = json.optString("format", null),
                minLength = if (json.has("minLength")) json.getInt("minLength") else null,
                maxLength = if (json.has("maxLength")) json.getInt("maxLength") else null,
                minimum = if (json.has("minimum")) json.getDouble("minimum") else null,
                maximum = if (json.has("maximum")) json.getDouble("maximum") else null,
                pattern = json.optString("pattern", null),
                items = itemsField,
                properties = propertiesMap,
                xGenton = json.optJSONObject("x-genton")?.let { FieldMetadata.fromJson(it) }
            )
        }
    }
    
    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("type", type)
        json.put("title", title)
        if (description != null) json.put("description", description)
        if (default != null) json.put("default", default)
        if (enum != null) json.put("enum", JSONArray(enum))
        if (format != null) json.put("format", format)
        if (minLength != null) json.put("minLength", minLength)
        if (maxLength != null) json.put("maxLength", maxLength)
        if (minimum != null) json.put("minimum", minimum)
        if (maximum != null) json.put("maximum", maximum)
        if (pattern != null) json.put("pattern", pattern)
        if (items != null) json.put("items", items.toJson())
        if (properties != null) {
            val propsObj = JSONObject()
            properties.forEach { (k, v) -> propsObj.put(k, v.toJson()) }
            json.put("properties", propsObj)
        }
        if (xGenton != null) json.put("x-genton", xGenton.toJson())
        return json
    }
    
    /**
     * 校验字段值
     * @return List<Pair<errorCode, message>>
     */
    fun validate(value: Any): List<Pair<String, String>> {
        val errors = mutableListOf<Pair<String, String>>()
        
        // 类型校验
        when (type) {
            "string" -> {
                if (value !is String) {
                    errors.add("type" to "值必须是字符串")
                } else {
                    // 长度校验
                    if (minLength != null && value.length < minLength) {
                        errors.add("minLength" to "长度不能少于 $minLength 个字符")
                    }
                    if (maxLength != null && value.length > maxLength) {
                        errors.add("maxLength" to "长度不能超过 $maxLength 个字符")
                    }
                    
                    // 正则校验
                    if (pattern != null && !Regex(pattern).matches(value)) {
                        errors.add("pattern" to "格式不正确")
                    }
                    
                    // 格式校验
                    if (format != null) {
                        val formatError = validateFormat(value, format)
                        if (formatError != null) {
                            errors.add(formatError)
                        }
                    }
                }
            }
            
            "number", "integer" -> {
                val numValue = when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                }
                
                if (numValue == null) {
                    errors.add("type" to "值必须是数字")
                } else {
                    if (minimum != null && numValue < minimum.toDouble()) {
                        errors.add("minimum" to "值不能小于 $minimum")
                    }
                    if (maximum != null && numValue > maximum.toDouble()) {
                        errors.add("maximum" to "值不能大于 $maximum")
                    }
                    
                    if (type == "integer" && numValue != numValue.toInt().toDouble()) {
                        errors.add("type" to "值必须是整数")
                    }
                }
            }
            
            "boolean" -> {
                if (value !is Boolean && value.toString().lowercase() !in listOf("true", "false")) {
                    errors.add("type" to "值必须是布尔类型")
                }
            }
        }
        
        // 枚举校验
        if (enum != null && value !in enum) {
            errors.add("enum" to "值必须是以下之一：${enum.joinToString(", ")}")
        }
        
        return errors
    }
    
    /**
     * 校验格式
     */
    private fun validateFormat(value: String, format: String): Pair<String, String>? {
        return when (format) {
            "email" -> {
                val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
                if (!emailRegex.matches(value)) {
                    "format" to "邮箱格式不正确"
                } else null
            }
            "phone" -> {
                val phoneRegex = Regex("^1[3-9]\\d{9}$")
                if (!phoneRegex.matches(value)) {
                    "format" to "手机号格式不正确"
                } else null
            }
            "url" -> {
                try {
                    java.net.URL(value)
                    null
                } catch (e: Exception) {
                    "format" to "URL格式不正确"
                }
            }
            "date" -> {
                // 简化校验：YYYY-MM-DD
                val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
                if (!dateRegex.matches(value)) {
                    "format" to "日期格式不正确（应为 YYYY-MM-DD）"
                } else null
            }
            "time" -> {
                // 简化校验：HH:MM 或 HH:MM:SS
                val timeRegex = Regex("^\\d{2}:\\d{2}(:\\d{2})?$")
                if (!timeRegex.matches(value)) {
                    "format" to "时间格式不正确（应为 HH:MM）"
                } else null
            }
            "datetime" -> {
                // ISO 8601 格式
                val dtRegex = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z?$")
                if (!dtRegex.matches(value)) {
                    "format" to "日期时间格式不正确（应为 ISO 8601）"
                } else null
            }
            else -> null
        }
    }
}

/**
 * 表单元数据（x-genton 扩展）
 */
data class FormMetadata(
    val submitAction: String,  // 提交动作（对应 ActionRequest 的 action）
    val cancelable: Boolean = true,  // 是否可取消
    val submitLabel: String = "提交",  // 提交按钮文本
    val cancelLabel: String = "取消",  // 取消按钮文本
    val layout: String = "vertical",  // 布局方式：vertical/horizontal/grid
    val submitOnEnter: Boolean = false,  // 回车是否提交
    val autoSave: Boolean = false,  // 是否自动保存
    val confirmOnCancel: Boolean = false,  // 取消时是否确认
    val validationMode: String = "onSubmit"  // 校验模式：onSubmit/onChange/onBlur
) {
    companion object {
        fun fromJson(json: JSONObject): FormMetadata {
            return FormMetadata(
                submitAction = json.getString("submitAction"),
                cancelable = json.optBoolean("cancelable", true),
                submitLabel = json.optString("submitLabel", "提交"),
                cancelLabel = json.optString("cancelLabel", "取消"),
                layout = json.optString("layout", "vertical"),
                submitOnEnter = json.optBoolean("submitOnEnter", false),
                autoSave = json.optBoolean("autoSave", false),
                confirmOnCancel = json.optBoolean("confirmOnCancel", false),
                validationMode = json.optString("validationMode", "onSubmit")
            )
        }
    }
    
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("submitAction", submitAction)
        json.put("cancelable", cancelable)
        json.put("submitLabel", submitLabel)
        json.put("cancelLabel", cancelLabel)
        json.put("layout", layout)
        json.put("submitOnEnter", submitOnEnter)
        json.put("autoSave", autoSave)
        json.put("confirmOnCancel", confirmOnCancel)
        json.put("validationMode", validationMode)
        return json
    }
}

/**
 * 字段元数据（x-genton 扩展）
 */
data class FieldMetadata(
    val control: String,  // 控件类型（见 ControlType）
    val placeholder: String? = null,  // 占位符文本
    val helpText: String? = null,  // 帮助文本
    val prefillSource: String? = null,  // 预填来源：USER_TYPED/USER_MEMORY/ENTITY/WEB/APP
    val prefillPriority: Float = 0.5f,  // 预填优先级 0-1
    val secret: Boolean = false,  // 是否敏感字段（值不出进程）
    val disabled: Boolean = false,  // 是否禁用
    val readonly: Boolean = false,  // 是否只读
    val autofocus: Boolean = false,  // 是否自动聚焦
    val validation: Map<String, Any>? = null,  // 额外校验规则
    val dependencies: List<String>? = null  // 依赖字段（影响显示/校验）
) {
    companion object {
        fun fromJson(json: JSONObject): FieldMetadata {
            val validationMap = json.optJSONObject("validation")?.let { obj ->
                val map = mutableMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.get(key)
                }
                map
            }
            
            val depsList = json.optJSONArray("dependencies")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            
            return FieldMetadata(
                control = json.getString("control"),
                placeholder = json.optString("placeholder", null),
                helpText = json.optString("helpText", null),
                prefillSource = json.optString("prefillSource", null),
                prefillPriority = json.optDouble("prefillPriority", 0.5).toFloat(),
                secret = json.optBoolean("secret", false),
                disabled = json.optBoolean("disabled", false),
                readonly = json.optBoolean("readonly", false),
                autofocus = json.optBoolean("autofocus", false),
                validation = validationMap,
                dependencies = depsList
            )
        }
    }
    
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("control", control)
        if (placeholder != null) json.put("placeholder", placeholder)
        if (helpText != null) json.put("helpText", helpText)
        if (prefillSource != null) json.put("prefillSource", prefillSource)
        json.put("prefillPriority", prefillPriority.toDouble())
        json.put("secret", secret)
        json.put("disabled", disabled)
        json.put("readonly", readonly)
        json.put("autofocus", autofocus)
        if (validation != null) json.put("validation", JSONObject(validation))
        if (dependencies != null) json.put("dependencies", JSONArray(dependencies))
        return json
    }
}

/**
 * 表单校验结果
 */
data class FormValidationResult(
    val isValid: Boolean,
    val errors: List<FieldError>
)

/**
 * 字段错误
 */
data class FieldError(
    val fieldName: String,
    val errorCode: String,
    val message: String
)

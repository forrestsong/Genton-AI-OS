package com.genton.action.registry

import org.json.JSONObject
import org.json.JSONArray

/**
 * A3.1 动作注册表 - 动作注册项数据模型
 * 
 * 职责：
 * 1. 定义可执行单元的唯一描述
 * 2. 提供动作元数据（名称、执行器、风险等级、能力要求）
 * 3. 定义输入输出 schema
 * 4. 支持参数来源限制（taint 规则）
 * 5. 关联审计事件类型
 * 
 * 设计要点：
 * - 与模型工具名保持 1:1 映射
 * - 风险分级驱动确认策略
 * - 参数来源校验支持 taint 纪律
 * - 审计事件类型关联
 */
data class ActionRegistryEntry(
    /**
     * 唯一动作名（与模型工具名1:1对应）
     * 格式：namespace.action（如 ui.tap, app.launch）
     */
    val name: String,
    
    /**
     * 人类可读名称
     * 用于审计日志和用户确认界面展示
     */
    val displayName: String,
    
    /**
     * 归属执行器标识
     * 可选值：activity, input, device_control, notification, clipboard, telephony 等
     */
    val executorId: String,
    
    /**
     * 风险等级
     * 0 = low（免确认，但仍需策略校验+审计）
     * 1 = medium（可配置，默认提示）
     * 2 = high（强制用户确认）
     */
    val riskLevel: Int,
    
    /**
     * 执行该动作所需的能力令牌名称
     * 对齐能力注册表（如 agent.input_inject, agent.launch_activity）
     */
    val requiredCapability: String,
    
    /**
     * 参数来源限制（可选）
     * 键：参数名
     * 值：来源等级（trusted/user/any）
     * 
     * 来源层次：trusted（系统/用户显式确认）> user（用户原话）> any（任意）
     * 未声明参数默认为 any
     */
    val inputSource: Map<String, InputSourceLevel>? = null,
    
    /**
     * 输入参数的 JSON Schema
     * 定义参数结构、类型、必填项、约束条件
     */
    val inputSchema: JSONObject,
    
    /**
     * 输出结果的 JSON Schema
     * 定义返回值结构
     */
    val outputSchema: JSONObject,
    
    /**
     * 审计事件类型名
     * 落审计时使用，必须在 audit-event schema 中存在
     */
    val auditEventType: String,
    
    /**
     * 确认角色要求（可选）
     * 指定需要确认的角色；为空则按风险等级默认处理
     */
    val requiresConfirmationRoles: List<String>? = null,
    
    /**
     * 动作描述（可选）
     * 详细说明动作用途和行为
     */
    val description: String? = null,
    
    /**
     * 是否已弃用（可选）
     * true 表示该动作已弃用，建议使用替代动作
     */
    val deprecated: Boolean = false,
    
    /**
     * 替代动作名（可选）
     * 如果该动作已弃用，指定替代动作
     */
    val replacedBy: String? = null
) {
    
    /**
     * 校验注册项有效性
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        
        // 1. 校验名称格式（必须是 namespace.action 格式）
        if (!name.matches(Regex("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$"))) {
            errors.add("Invalid action name format: $name (must be namespace.action)")
        }
        
        // 2. 校验风险等级
        if (riskLevel !in 0..2) {
            errors.add("Invalid risk level: $riskLevel (must be 0, 1, or 2)")
        }
        
        // 3. 校验能力令牌格式
        if (!requiredCapability.startsWith("agent.")) {
            errors.add("Invalid capability format: $requiredCapability (must start with 'agent.')")
        }
        
        // 4. 校验审计事件类型格式
        if (!auditEventType.startsWith("action.")) {
            errors.add("Invalid audit event type: $auditEventType (must start with 'action.')")
        }
        
        // 5. 校验 inputSource 参数存在性
        inputSource?.forEach { (paramName, _) ->
            if (!inputSchema.has("properties") || 
                !inputSchema.getJSONObject("properties").has(paramName)) {
                errors.add("inputSource references non-existent parameter: $paramName")
            }
        }
        
        // 6. 校验弃用状态
        if (deprecated && replacedBy == null) {
            errors.add("Deprecated action must specify replacedBy")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult(true)
        } else {
            ValidationResult(false, errors)
        }
    }
    
    /**
     * 获取参数的来源限制
     */
    fun getParamSourceLevel(paramName: String): InputSourceLevel {
        return inputSource?.get(paramName) ?: InputSourceLevel.ANY
    }
    
    /**
     * 是否需要用户确认
     */
    fun requiresUserConfirmation(): Boolean {
        // high 风险等级强制确认
        if (riskLevel == 2) {
            return true
        }
        
        // 显式指定了确认角色
        if (!requiresConfirmationRoles.isNullOrEmpty()) {
            return true
        }
        
        return false
    }
    
    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("displayName", displayName)
            put("executorId", executorId)
            put("riskLevel", riskLevel)
            put("requiredCapability", requiredCapability)
            
            // inputSource（可选）
            if (inputSource != null) {
                val sourceObj = JSONObject()
                inputSource.forEach { (key, value) ->
                    sourceObj.put(key, value.name.lowercase())
                }
                put("inputSource", sourceObj)
            }
            
            put("inputSchema", inputSchema)
            put("outputSchema", outputSchema)
            put("auditEventType", auditEventType)
            
            // requiresConfirmationRoles（可选）
            if (!requiresConfirmationRoles.isNullOrEmpty()) {
                put("requiresConfirmationRoles", JSONArray(requiresConfirmationRoles))
            }
            
            // description（可选）
            if (description != null) {
                put("description", description)
            }
            
            // deprecated 和 replacedBy（可选）
            if (deprecated) {
                put("deprecated", true)
                if (replacedBy != null) {
                    put("replacedBy", replacedBy)
                }
            }
        }
    }
    
    companion object {
        /**
         * 从 JSON 解析
         */
        fun fromJson(json: JSONObject): ActionRegistryEntry {
            // 解析 inputSource
            val inputSource = if (json.has("inputSource")) {
                val sourceObj = json.getJSONObject("inputSource")
                val map = mutableMapOf<String, InputSourceLevel>()
                sourceObj.keys().forEach { key ->
                    val levelStr = sourceObj.getString(key)
                    map[key] = InputSourceLevel.valueOf(levelStr.uppercase())
                }
                map
            } else {
                null
            }
            
            // 解析 requiresConfirmationRoles
            val confirmationRoles = if (json.has("requiresConfirmationRoles")) {
                val array = json.getJSONArray("requiresConfirmationRoles")
                List(array.length()) { array.getString(it) }
            } else {
                null
            }
            
            return ActionRegistryEntry(
                name = json.getString("name"),
                displayName = json.getString("displayName"),
                executorId = json.getString("executorId"),
                riskLevel = json.getInt("riskLevel"),
                requiredCapability = json.getString("requiredCapability"),
                inputSource = inputSource,
                inputSchema = json.getJSONObject("inputSchema"),
                outputSchema = json.getJSONObject("outputSchema"),
                auditEventType = json.getString("auditEventType"),
                requiresConfirmationRoles = confirmationRoles,
                description = json.optString("description", null),
                deprecated = json.optBoolean("deprecated", false),
                replacedBy = json.optString("replacedBy", null)
            )
        }
    }
}

/**
 * 参数来源等级
 * 
 * 来源层次：TRUSTED > USER > ANY
 */
enum class InputSourceLevel {
    /**
     * 可信来源（系统/用户显式确认）
     * 最高安全等级
     */
    TRUSTED,
    
    /**
     * 用户来源（用户原话）
     * 中等安全等级
     */
    USER,
    
    /**
     * 任意来源（含污染内容）
     * 最低安全等级
     */
    ANY;
    
    /**
     * 是否满足要求的来源等级
     */
    fun satisfies(required: InputSourceLevel): Boolean {
        return this.ordinal <= required.ordinal
    }
}

/**
 * 校验结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
) {
    fun getErrorMessage(): String {
        return errors.joinToString("; ")
    }
}

/**
 * 执行器类型（预定义常量）
 */
object ExecutorType {
    const val ACTIVITY = "activity"           // 活动启动
    const val INPUT = "input"                 // 输入注入
    const val DEVICE_CONTROL = "device_control" // 设备控制
    const val NOTIFICATION = "notification"   // 通知操作
    const val CLIPBOARD = "clipboard"         // 粘贴板
    const val TELEPHONY = "telephony"         // 电话/短信
    const val SYSTEM = "system"               // 系统操作
    const val SKILL = "skill"                 // Skill 资产
    const val DMI = "dmi"                     // DMI 资产
    const val MACRO = "macro"                 // Macro 资产
}

/**
 * 风险等级（预定义常量）
 */
object RiskLevel {
    const val LOW = 0      // 免确认（但仍需策略校验+审计）
    const val MEDIUM = 1   // 可配置（默认提示）
    const val HIGH = 2     // 强制用户确认
}

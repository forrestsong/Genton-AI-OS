package com.genton.brain.schema

/**
 * C6.1 系统固定 schema 体系 - 控件类型与映射
 * 
 * 职责：
 * 1. 定义所有支持的控件类型
 * 2. 提供表单字段 → 控件的纯函数映射
 * 3. 控件配置和行为定义
 * 
 * 设计原则：
 * - 纯函数映射（无副作用）
 * - 基于 JSON Schema type + format + x-genton.control
 * - 支持自定义控件扩展
 */
object ControlType {
    
    // ============ 文本输入类 ============
    const val TEXT_FIELD = "textField"  // 单行文本输入
    const val TEXT_AREA = "textArea"  // 多行文本输入
    const val PASSWORD = "password"  // 密码输入
    const val EMAIL = "email"  // 邮箱输入
    const val PHONE = "phone"  // 手机号输入
    const val URL = "url"  // URL输入
    const val SEARCH = "search"  // 搜索框
    
    // ============ 数字输入类 ============
    const val NUMBER = "number"  // 数字输入
    const val SLIDER = "slider"  // 滑块
    const val STEPPER = "stepper"  // 步进器
    
    // ============ 选择类 ============
    const val DROPDOWN = "dropdown"  // 下拉选择
    const val RADIO = "radio"  // 单选框
    const val CHECKBOX = "checkbox"  // 复选框（单个）
    const val CHECKBOX_GROUP = "checkboxGroup"  // 复选框组（多选）
    const val SWITCH = "switch"  // 开关
    const val SEGMENTED = "segmented"  // 分段控制
    
    // ============ 日期时间类 ============
    const val DATE_PICKER = "datePicker"  // 日期选择
    const val TIME_PICKER = "timePicker"  // 时间选择
    const val DATETIME_PICKER = "datetimePicker"  // 日期时间选择
    const val DATE_RANGE = "dateRange"  // 日期范围
    
    // ============ 文件上传类 ============
    const val FILE_UPLOAD = "fileUpload"  // 文件上传
    const val IMAGE_UPLOAD = "imageUpload"  // 图片上传
    const val AVATAR_UPLOAD = "avatarUpload"  // 头像上传
    
    // ============ 复合类 ============
    const val CONTACT_PICKER = "contactPicker"  // 联系人选择器
    const val LOCATION_PICKER = "locationPicker"  // 位置选择器
    const val COLOR_PICKER = "colorPicker"  // 颜色选择器
    const val RATING = "rating"  // 评分
    const val TAG_INPUT = "tagInput"  // 标签输入
    
    // ============ 特殊类 ============
    const val HIDDEN = "hidden"  // 隐藏字段
    const val STATIC_TEXT = "staticText"  // 静态文本（只读展示）
    const val DIVIDER = "divider"  // 分割线
    const val LABEL = "label"  // 标签（纯展示）
    
    /**
     * 所有支持的控件类型
     */
    val ALL_TYPES = setOf(
        TEXT_FIELD, TEXT_AREA, PASSWORD, EMAIL, PHONE, URL, SEARCH,
        NUMBER, SLIDER, STEPPER,
        DROPDOWN, RADIO, CHECKBOX, CHECKBOX_GROUP, SWITCH, SEGMENTED,
        DATE_PICKER, TIME_PICKER, DATETIME_PICKER, DATE_RANGE,
        FILE_UPLOAD, IMAGE_UPLOAD, AVATAR_UPLOAD,
        CONTACT_PICKER, LOCATION_PICKER, COLOR_PICKER, RATING, TAG_INPUT,
        HIDDEN, STATIC_TEXT, DIVIDER, LABEL
    )
    
    /**
     * 检查控件类型是否有效
     */
    fun isValid(controlType: String): Boolean {
        return controlType in ALL_TYPES
    }
}

/**
 * 控件映射器 - 纯函数映射表
 * 
 * 输入：FormField（包含 type/format/enum/x-genton.control）
 * 输出：控件类型（ControlType）
 */
object ControlMapper {
    
    /**
     * 将表单字段映射到控件类型（纯函数）
     * 
     * 映射优先级：
     * 1. x-genton.control（显式指定）
     * 2. format（格式约定）
     * 3. enum（枚举值）
     * 4. type（基础类型）
     */
    fun mapFieldToControl(field: FormField): String {
        // 1. 显式指定控件类型
        val explicitControl = field.xGenton?.control
        if (explicitControl != null && ControlType.isValid(explicitControl)) {
            return explicitControl
        }
        
        // 2. 基于 format 映射
        if (field.format != null) {
            val formatControl = mapFormatToControl(field.format)
            if (formatControl != null) {
                return formatControl
            }
        }
        
        // 3. 基于 enum 映射
        if (field.enum != null) {
            return mapEnumToControl(field.enum)
        }
        
        // 4. 基于 type 映射
        return mapTypeToControl(field.type, field)
    }
    
    /**
     * 基于 format 映射控件
     */
    private fun mapFormatToControl(format: String): String? {
        return when (format) {
            "email" -> ControlType.EMAIL
            "phone" -> ControlType.PHONE
            "url" -> ControlType.URL
            "date" -> ControlType.DATE_PICKER
            "time" -> ControlType.TIME_PICKER
            "datetime", "date-time" -> ControlType.DATETIME_PICKER
            "password" -> ControlType.PASSWORD
            "color" -> ControlType.COLOR_PICKER
            else -> null
        }
    }
    
    /**
     * 基于 enum 映射控件
     */
    private fun mapEnumToControl(enumValues: List<Any>): String {
        return when {
            enumValues.size <= 3 -> ControlType.RADIO  // 3个以内用单选框
            enumValues.size <= 5 -> ControlType.SEGMENTED  // 5个以内用分段控制
            else -> ControlType.DROPDOWN  // 更多用下拉选择
        }
    }
    
    /**
     * 基于 type 映射控件
     */
    private fun mapTypeToControl(type: String, field: FormField): String {
        return when (type) {
            "string" -> {
                // 根据长度限制判断
                when {
                    field.maxLength != null && field.maxLength > 100 -> ControlType.TEXT_AREA
                    field.xGenton?.secret == true -> ControlType.PASSWORD
                    else -> ControlType.TEXT_FIELD
                }
            }
            
            "number", "integer" -> {
                // 根据范围判断
                when {
                    field.minimum != null && field.maximum != null -> ControlType.SLIDER
                    else -> ControlType.NUMBER
                }
            }
            
            "boolean" -> ControlType.SWITCH
            
            "array" -> {
                // 数组类型，根据 items 判断
                if (field.items?.enum != null) {
                    ControlType.CHECKBOX_GROUP
                } else {
                    ControlType.TAG_INPUT
                }
            }
            
            "object" -> ControlType.STATIC_TEXT  // 对象类型默认静态展示
            
            else -> ControlType.TEXT_FIELD  // 默认文本输入
        }
    }
    
    /**
     * 获取控件配置
     */
    fun getControlConfig(controlType: String): ControlConfig {
        return when (controlType) {
            ControlType.TEXT_FIELD -> ControlConfig(
                requiresKeyboard = true,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true
            )
            
            ControlType.TEXT_AREA -> ControlConfig(
                requiresKeyboard = true,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                multiline = true
            )
            
            ControlType.PASSWORD -> ControlConfig(
                requiresKeyboard = true,
                supportsValidation = true,
                supportsPrefill = false,  // 密码不预填
                isInteractive = true,
                secure = true
            )
            
            ControlType.EMAIL -> ControlConfig(
                requiresKeyboard = true,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                keyboardType = "email"
            )
            
            ControlType.PHONE -> ControlConfig(
                requiresKeyboard = true,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                keyboardType = "phone"
            )
            
            ControlType.NUMBER -> ControlConfig(
                requiresKeyboard = true,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                keyboardType = "number"
            )
            
            ControlType.SLIDER -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true
            )
            
            ControlType.DROPDOWN, ControlType.RADIO, ControlType.SEGMENTED -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                requiresOptions = true
            )
            
            ControlType.SWITCH, ControlType.CHECKBOX -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = false,
                supportsPrefill = true,
                isInteractive = true
            )
            
            ControlType.DATE_PICKER, ControlType.TIME_PICKER, ControlType.DATETIME_PICKER -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                requiresPicker = true
            )
            
            ControlType.CONTACT_PICKER -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                requiresSystemAccess = true  // 需要访问通讯录
            )
            
            ControlType.LOCATION_PICKER -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = true,
                supportsPrefill = true,
                isInteractive = true,
                requiresSystemAccess = true  // 需要访问位置服务
            )
            
            ControlType.FILE_UPLOAD, ControlType.IMAGE_UPLOAD -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = true,
                supportsPrefill = false,
                isInteractive = true,
                requiresSystemAccess = true  // 需要访问文件系统
            )
            
            ControlType.HIDDEN -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = false,
                supportsPrefill = true,
                isInteractive = false,
                visible = false
            )
            
            ControlType.STATIC_TEXT, ControlType.LABEL -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = false,
                supportsPrefill = false,
                isInteractive = false,
                readonly = true
            )
            
            else -> ControlConfig(
                requiresKeyboard = false,
                supportsValidation = false,
                supportsPrefill = false,
                isInteractive = true
            )
        }
    }
}

/**
 * 控件配置
 */
data class ControlConfig(
    val requiresKeyboard: Boolean,  // 是否需要键盘
    val supportsValidation: Boolean,  // 是否支持校验
    val supportsPrefill: Boolean,  // 是否支持预填
    val isInteractive: Boolean,  // 是否可交互
    val multiline: Boolean = false,  // 是否多行
    val secure: Boolean = false,  // 是否安全字段
    val readonly: Boolean = false,  // 是否只读
    val visible: Boolean = true,  // 是否可见
    val requiresOptions: Boolean = false,  // 是否需要选项列表
    val requiresPicker: Boolean = false,  // 是否需要选择器
    val requiresSystemAccess: Boolean = false,  // 是否需要系统权限
    val keyboardType: String? = null  // 键盘类型：text/email/phone/number/url
)

/**
 * 控件渲染参数
 */
data class ControlRenderParams(
    val controlType: String,  // 控件类型
    val field: FormField,  // 字段定义
    val value: Any?,  // 当前值
    val isRequired: Boolean,  // 是否必填
    val error: String? = null,  // 错误信息
    val disabled: Boolean = false,  // 是否禁用
    val readonly: Boolean = false  // 是否只读
) {
    /**
     * 获取控件配置
     */
    fun getConfig(): ControlConfig {
        return ControlMapper.getControlConfig(controlType)
    }
    
    /**
     * 获取占位符文本
     */
    fun getPlaceholder(): String? {
        return field.xGenton?.placeholder ?: when (controlType) {
            ControlType.TEXT_FIELD -> "请输入${field.title}"
            ControlType.EMAIL -> "请输入邮箱地址"
            ControlType.PHONE -> "请输入手机号"
            ControlType.SEARCH -> "搜索..."
            else -> null
        }
    }
    
    /**
     * 获取帮助文本
     */
    fun getHelpText(): String? {
        return field.xGenton?.helpText ?: field.description
    }
}

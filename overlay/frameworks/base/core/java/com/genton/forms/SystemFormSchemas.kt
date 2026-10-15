package com.genton.forms

/**
 * C6.2 内部策略动作 schema
 * 
 * 职责：
 * 定义系统内部表单 schema（activate_suggestion/deactivate_rule）
 * 供 D4.2 learn 治理 UI 和 D6.2 规则编辑器使用
 * 
 * 设计原则（D15 §8、D17 §3）：
 * 1. 所有系统表单 provenance="system"，无需外部签名
 * 2. activate_suggestion 需确认（CONFIRM 模式），防误激活
 * 3. deactivate_rule 立即生效（IMMEDIATE 模式），紧急停用场景
 * 4. 表单数据直接路由到策略引擎（A7.1），不经过 LLM
 * 
 * 依赖关系：
 * - 前置：C1.1 DSL 规则目录 v1（规则 ID 枚举来源）
 * - 消费：C2.2 learn 状态机、D4.2 治理 UI
 * 
 * 版本：v1（A5 期）
 * 
 * @since A5
 */
object SystemFormSchemas {
    
    /**
     * 激活 learn 建议规则表单
     * 
     * 场景：用户审批 learn 生成的候选规则（suggestion → active）
     * 
     * 字段：
     * - suggestion_id: 建议 ID（必填，隐藏字段）
     * - rule_name: 规则名称（必填，用户可编辑）
     * - scope: 生效范围（必填，USER_ONLY/SYSTEM_WIDE）
     * - confirm_intent: 意图确认（必填，checkbox）
     * 
     * 行为：
     * - 提交模式：CONFIRM（显示确认面板，防误激活）
     * - 提交后触发：C2.2 状态机 suggestion→active + A7.1 PolicyPack 重编译
     */
    fun activateSuggestion(): FormSchema {
        val meta = FormMeta.systemForm(
            formId = "sys.policy.activate_suggestion_v1",
            formTitle = "激活 Learn 规则",
            submitMode = SubmitMode.CONFIRM
        ).copy(
            submitLabel = "激活规则",
            cancelLabel = "取消",
            formDescription = "将 Learn 建议提升为正式规则。激活后规则将立即生效，可能影响通知/通话处理。"
        )
        
        val fields = listOf(
            // 隐藏字段：建议 ID（由 UI 自动填充）
            FieldSchema(
                name = "suggestion_id",
                type = "string",
                format = null,
                title = "建议 ID",
                description = "内部标识，用户不可见",
                placeholder = null,
                required = true,
                minLength = null,
                maxLength = 64,
                minimum = null,
                maximum = null,
                pattern = "^learn\\.[a-z0-9_]+\\.[0-9]{13}$", // learn.notification_mute.1672531200000
                enum = null,
                xWidget = "hidden", // 隐藏控件（D5.1 渲染器不显示）
                xWidgetHint = null,
                xPrefillSource = PrefillSource.APP.name, // 由 UI 自动填充
                xPrefillKey = "suggestion_id",
                xSecret = false,
                xTaint = false,
                defaultValue = null
            ),
            
            // 规则名称（用户友好）
            FieldSchema.text(
                name = "rule_name",
                title = "规则名称",
                required = true,
                maxLength = 128,
                prefillSource = PrefillSource.USER_MEMORY // 预填建议的默认名称
            ).copy(
                description = "为这条规则起个易于识别的名称",
                placeholder = "例如：工作时段静音营销通知",
                xPrefillKey = "suggested_rule_name"
            ),
            
            // 生效范围
            FieldSchema.select(
                name = "scope",
                title = "生效范围",
                options = listOf("USER_ONLY", "SYSTEM_WIDE"),
                required = true,
                defaultValue = "USER_ONLY"
            ).copy(
                description = "USER_ONLY：仅对当前用户生效；SYSTEM_WIDE：对所有用户生效（需管理员权限）",
                xWidgetHint = "建议选择 USER_ONLY，避免影响其他用户"
            ),
            
            // 意图确认（防误激活）
            FieldSchema(
                name = "confirm_intent",
                type = "boolean",
                format = null,
                title = "我已理解此规则的作用",
                description = "请仔细阅读规则说明，确认激活后不会造成误操作",
                placeholder = null,
                required = true,
                minLength = null,
                maxLength = null,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = "checkbox",
                xWidgetHint = "必须勾选才能激活",
                xPrefillSource = null,
                xPrefillKey = null,
                xSecret = false,
                xTaint = false,
                defaultValue = "false"
            )
        )
        
        return FormSchema(meta, fields)
    }
    
    /**
     * 停用规则表单
     * 
     * 场景：用户紧急停用某条规则（active/learn → discarded）
     * 
     * 字段：
     * - rule_id: 规则 ID（必填，隐藏字段）
     * - reason: 停用原因（可选，多选）
     * - feedback: 补充说明（可选，文本）
     * 
     * 行为：
     * - 提交模式：IMMEDIATE（立即生效，紧急停用场景）
     * - 提交后触发：A7.1 PolicyPack 重编译 + C2.2 状态更新
     */
    fun deactivateRule(): FormSchema {
        val meta = FormMeta.systemForm(
            formId = "sys.policy.deactivate_rule_v1",
            formTitle = "停用规则",
            submitMode = SubmitMode.IMMEDIATE
        ).copy(
            submitLabel = "立即停用",
            cancelLabel = "取消",
            formDescription = "停用后规则将立即失效，不再影响通知/通话处理。"
        )
        
        val fields = listOf(
            // 隐藏字段：规则 ID
            FieldSchema(
                name = "rule_id",
                type = "string",
                format = null,
                title = "规则 ID",
                description = "内部标识",
                placeholder = null,
                required = true,
                minLength = null,
                maxLength = 128,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = "hidden",
                xWidgetHint = null,
                xPrefillSource = PrefillSource.APP.name,
                xPrefillKey = "rule_id",
                xSecret = false,
                xTaint = false,
                defaultValue = null
            ),
            
            // 停用原因（多选）
            FieldSchema(
                name = "reason",
                type = "array",
                format = null,
                title = "停用原因（可多选）",
                description = "帮助我们改进 Learn 功能",
                placeholder = null,
                required = false,
                minLength = null,
                maxLength = null,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = listOf(
                    "规则效果不符合预期",
                    "误屏蔽了重要通知/通话",
                    "规则过于激进",
                    "不再需要此规则",
                    "其他原因"
                ),
                xWidget = "multiselect",
                xWidgetHint = null,
                xPrefillSource = null,
                xPrefillKey = null,
                xSecret = false,
                xTaint = false,
                defaultValue = null
            ),
            
            // 补充说明
            FieldSchema(
                name = "feedback",
                type = "string",
                format = "textarea",
                title = "补充说明（可选）",
                description = null,
                placeholder = "请描述规则造成的具体问题...",
                required = false,
                minLength = null,
                maxLength = 500,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = null,
                xWidgetHint = null,
                xPrefillSource = null,
                xPrefillKey = null,
                xSecret = false,
                xTaint = false,
                defaultValue = null
            )
        )
        
        return FormSchema(meta, fields)
    }
    
    /**
     * 编辑通知规则表单（D6.2 规则编辑器）
     * 
     * 场景：用户手动创建/编辑通知过滤规则
     * 
     * 字段：
     * - rule_type: 规则类型（notification/call_gate）
     * - priority: 优先级（P0/P1/P2）
     * - condition: 条件表达式（DSL 简化版）
     * - action: 动作（ALLOW/DENY/ROUTE）
     * 
     * 行为：
     * - 提交模式：CONFIRM（DSL 编译检查后确认）
     * - 提交后触发：A7.2 DSL 编译器静态检查 + A7.1 PolicyPack 热编译
     */
    fun editNotificationRule(): FormSchema {
        val meta = FormMeta.systemForm(
            formId = "sys.policy.edit_notification_rule_v1",
            formTitle = "编辑通知规则",
            submitMode = SubmitMode.CONFIRM
        ).copy(
            submitLabel = "保存规则",
            cancelLabel = "取消",
            formDescription = "自定义通知过滤规则。请谨慎设置，避免误屏蔽重要通知。",
            validateOnChange = true // 实时校验 DSL 语法
        )
        
        val fields = listOf(
            // 规则类型
            FieldSchema.select(
                name = "rule_type",
                title = "规则类型",
                options = listOf("notification", "call_gate"),
                required = true,
                defaultValue = "notification"
            ).copy(
                description = "notification：通知过滤；call_gate：来电过滤"
            ),
            
            // 优先级
            FieldSchema.select(
                name = "priority",
                title = "优先级",
                options = listOf("P0_CRITICAL", "P1_HIGH", "P2_NORMAL"),
                required = true,
                defaultValue = "P2_NORMAL"
            ).copy(
                description = "P0：紧急（优先显示）；P1：重要；P2：普通"
            ),
            
            // 条件表达式（DSL 简化版）
            FieldSchema(
                name = "condition",
                type = "string",
                format = "textarea",
                title = "触发条件（DSL）",
                description = "示例：category == 'MARKETING' && sender.domain == 'spam.com'",
                placeholder = "category == 'MARKETING'",
                required = true,
                minLength = 5,
                maxLength = 500,
                minimum = null,
                maximum = null,
                pattern = null, // DSL 语法校验由 A7.2 编译器完成
                enum = null,
                xWidget = null,
                xWidgetHint = "支持 ==, !=, &&, || 运算符",
                xPrefillSource = null,
                xPrefillKey = null,
                xSecret = false,
                xTaint = false,
                defaultValue = null
            ),
            
            // 动作
            FieldSchema.select(
                name = "action",
                title = "执行动作",
                options = listOf("ALLOW", "DENY", "ROUTE_P1", "ROUTE_P2"),
                required = true,
                defaultValue = "DENY"
            ).copy(
                description = "ALLOW：放行；DENY：拒绝；ROUTE_P1/P2：降级显示"
            )
        )
        
        return FormSchema(meta, fields)
    }
    
    /**
     * 日程预准备授权表单（D7.2 日程 UI）
     * 
     * 场景：用户授权系统为即将到来的会议/活动做准备（A3.6 内部动作）
     * 
     * 字段：
     * - event_id: 日程事件 ID（必填，隐藏）
     * - prep_actions: 准备动作（多选）
     * - advance_minutes: 提前时间（分钟）
     * - allow_credential_injection: 是否允许注入凭据（checkbox）
     * 
     * 行为：
     * - 提交模式：CONFIRM（涉及凭据注入，需明确授权）
     * - 提交后触发：sys.schedule_prep 内部动作排队
     */
    fun schedulePrep(): FormSchema {
        val meta = FormMeta.systemForm(
            formId = "sys.schedule.prep_v1",
            formTitle = "日程预准备授权",
            submitMode = SubmitMode.CONFIRM
        ).copy(
            submitLabel = "授权准备",
            cancelLabel = "取消",
            formDescription = "系统将在会议开始前自动完成准备工作，如打开文档、登录账号等。"
        )
        
        val fields = listOf(
            // 隐藏字段：事件 ID
            FieldSchema(
                name = "event_id",
                type = "string",
                format = null,
                title = "事件 ID",
                description = null,
                placeholder = null,
                required = true,
                minLength = null,
                maxLength = 128,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = "hidden",
                xWidgetHint = null,
                xPrefillSource = PrefillSource.APP.name,
                xPrefillKey = "event_id",
                xSecret = false,
                xTaint = false,
                defaultValue = null
            ),
            
            // 准备动作（多选）
            FieldSchema(
                name = "prep_actions",
                type = "array",
                format = null,
                title = "准备动作",
                description = "选择需要自动执行的准备工作",
                placeholder = null,
                required = true,
                minLength = null,
                maxLength = null,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = listOf(
                    "打开会议链接",
                    "打开相关文档",
                    "登录会议账号",
                    "预热摄像头/麦克风",
                    "设置勿扰模式"
                ),
                xWidget = "multiselect",
                xWidgetHint = null,
                xPrefillSource = PrefillSource.S4_ENTITY.name, // S4 根据历史行为预填
                xPrefillKey = "user_preferred_prep_actions",
                xSecret = false,
                xTaint = false,
                defaultValue = null
            ),
            
            // 提前时间
            FieldSchema(
                name = "advance_minutes",
                type = "integer",
                format = null,
                title = "提前时间（分钟）",
                description = "在会议开始前多久开始准备",
                placeholder = "5",
                required = true,
                minLength = null,
                maxLength = null,
                minimum = 1.0,
                maximum = 60.0,
                pattern = null,
                enum = null,
                xWidget = "stepper",
                xWidgetHint = "建议 5-15 分钟",
                xPrefillSource = PrefillSource.USER_MEMORY.name,
                xPrefillKey = "preferred_prep_advance",
                xSecret = false,
                xTaint = false,
                defaultValue = "5"
            ),
            
            // 凭据注入授权（安全敏感）
            FieldSchema(
                name = "allow_credential_injection",
                type = "boolean",
                format = null,
                title = "允许自动填充登录凭据",
                description = "系统将安全地注入保存的用户名/密码，值不会离开设备",
                placeholder = null,
                required = true,
                minLength = null,
                maxLength = null,
                minimum = null,
                maximum = null,
                pattern = null,
                enum = null,
                xWidget = "checkbox",
                xWidgetHint = "仅在安全环境下启用",
                xPrefillSource = null,
                xPrefillKey = null,
                xSecret = false, // checkbox 本身不是 secret，但控制 secret 注入权限
                xTaint = true,   // 涉及凭据，需 taint 打标
                defaultValue = "false"
            )
        )
        
        return FormSchema(meta, fields)
    }
    
    /**
     * 获取所有系统表单（用于注册表预加载）
     */
    fun getAllSystemForms(): Map<String, FormSchema> {
        return mapOf(
            "sys.policy.activate_suggestion_v1" to activateSuggestion(),
            "sys.policy.deactivate_rule_v1" to deactivateRule(),
            "sys.policy.edit_notification_rule_v1" to editNotificationRule(),
            "sys.schedule.prep_v1" to schedulePrep()
        )
    }
}

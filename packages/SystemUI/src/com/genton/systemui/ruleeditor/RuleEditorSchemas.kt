package com.genton.systemui.ruleeditor

import com.genton.forms.FieldSchema
import com.genton.forms.FormMeta
import com.genton.forms.FormSchema
import com.genton.forms.SubmitMode

/**
 * D6.2 规则编辑器 - 声明式 schema（C6.1 表单 schema 体系）
 *
 * 规则表单 = 「声明式 schema → 控件」（L0.5/D5.1 FormRenderer 渲染）。
 * 用户仅编辑 user.* 命名空间规则；字段值以 DSL 令牌为准，经 RuleDsl 编译为 YAML。
 */
object RuleEditorSchemas {

    /** 通用字段构造器 */
    private fun f(
        name: String,
        type: String,
        title: String,
        description: String? = null,
        required: Boolean = false,
        enum: List<String>? = null,
        defaultValue: String? = null,
        pattern: String? = null,
        min: Double? = null,
        max: Double? = null,
        placeholder: String? = null
    ): FieldSchema = FieldSchema(
        name = name,
        type = type,
        format = null,
        title = title,
        description = description,
        placeholder = placeholder,
        required = required,
        minLength = if (type == "string") 0 else null,
        maxLength = if (type == "string") 64 else null,
        minimum = min,
        maximum = max,
        pattern = pattern,
        enum = enum,
        xWidget = null,
        xWidgetHint = null,
        xPrefillSource = null,
        xPrefillKey = null,
        xSecret = false,
        xTaint = false,
        defaultValue = defaultValue
    )

    /** 通知域规则 schema */
    fun notificationRuleSchema(): FormSchema = FormSchema(
        meta = FormMeta(
            formId = "sys.rule.editor.notification_v1",
            formVersion = "1.0.0",
            formTitle = "新增 / 编辑通知规则",
            formDescription = "用户自定义通知处理规则（user.*）。命中条件为 AND 关系；分类来自值域知识库，随 C6.4 OTA 联动。",
            submitMode = SubmitMode.IMMEDIATE,
            submitLabel = "保存规则",
            cancelLabel = "取消",
            validateOnChange = true,
            validateOnSubmit = false,   // 数组枚举在 schema.validate 不适用，由 RuleDslValidator 深校验
            provenance = "system",
            signatureHash = null
        ),
        fields = listOf(
            f("rule_id", "string", "规则 ID", "必须以 user. 开头；创建后不可修改。示例：user.notify.marketing-digest",
                required = true, pattern = RuleDsl.ID_PATTERN,
                placeholder = "user.notify.my-rule"),
            f("effect", "string", "处理方式（effect）",
                "route_p0_native=原生展示 · route_p1_digest=聚合简报 · route_p2_agent_act=Agent 代处理 · deny=拒绝展示",
                required = true, enum = RuleDsl.NOTIFICATION_EFFECTS,
                defaultValue = "route_p1_digest"),
            f("categories", "array", "通知分类（category）",
                "命中这些分类（16 类，与分类器版本联动）。可多选。",
                enum = RuleDsl.NOTIFICATION_CATEGORIES),
            f("sender_domain", "string", "来源域（可选）", "仅匹配该来源域，如 shop.example.com（留空=不限）",
                placeholder = "shop.example.com"),
            f("conf_op", "string", "分类器置信度比较符", "作用于 category 命中后的分类置信度", required = false,
                enum = RuleDsl.COMPARISON_OPS, defaultValue = ">="),
            f("conf_value", "number", "分类器置信度阈值（可选）", "留空=不限；取值 0.0~1.0",
                min = 0.0, max = 1.0, defaultValue = "0.8"),
            f("dnd_only", "boolean", "仅在免打扰时生效", "勾选后规则只在 DND（勿扰）状态下命中（when.context.dndActive）"),

            f("agg_min", "number", "P1 聚合窗口（分钟）", "仅 route_p1_digest 生效：同规则窗口内聚合为一张简报卡",
                min = 1.0, max = 1440.0, defaultValue = "30"),
            f("hold_dnd", "boolean", "P1 延后到免打扰结束", "仅 route_p1_digest 生效：holdUntilDndEnd"),
            f("suppress_badge", "boolean", "P1 隐藏角标", "仅 route_p1_digest 生效：suppressBadge", defaultValue = "true"),
            f("agent_act", "string", "P2 Agent 动作", "仅 route_p2_agent_act 生效：fold=折叠摘要 / dismiss=静默丢弃",
                enum = RuleDsl.AGENT_ACTS, defaultValue = "fold"),
            f("audit", "string", "审计级别", "summary=仅摘要 / full=完整记录（含规则命中链）",
                required = true, enum = RuleDsl.AUDIT_LEVELS, defaultValue = "summary")
        )
    )

    /** 通话（来电拦截）域规则 schema */
    fun callGateRuleSchema(): FormSchema = FormSchema(
        meta = FormMeta(
            formId = "sys.rule.editor.call_gate_v1",
            formVersion = "1.0.0",
            formTitle = "新增 / 编辑来电规则",
            formDescription = "用户自定义来电处理规则（user.*）。主叫类别取自 callerClass 值域；骚扰分来自 C4.2 SpamScorer。",
            submitMode = SubmitMode.IMMEDIATE,
            submitLabel = "保存规则",
            cancelLabel = "取消",
            validateOnChange = true,
            validateOnSubmit = false,
            provenance = "system",
            signatureHash = null
        ),
        fields = listOf(
            f("rule_id", "string", "规则 ID", "必须以 user. 开头；创建后不可修改。示例：user.callgate.spam-record",
                required = true, pattern = RuleDsl.ID_PATTERN,
                placeholder = "user.callgate.my-rule"),
            f("effect", "string", "处理方式（effect）",
                "route_ring_native=正常响铃 · route_agent_handle=Agent 应答 · route_record_only=仅录音 · deny=静默拦截",
                required = true, enum = RuleDsl.CALL_GATE_EFFECTS,
                defaultValue = "route_record_only"),
            f("caller_is", "array", "主叫类别（callerIs）", "emergency/system/carrier_service 由系统保护，命中优先于本规则",
                enum = RuleDsl.CALLER_CLASSES),
            f("number_prefix", "string", "号段（可选）", "如 95/400/170（留空=不限）",
                placeholder = "400"),
            f("spam_op", "string", "骚扰分比较符", "作用于 SpamScorer spamScore", required = false,
                enum = RuleDsl.COMPARISON_OPS, defaultValue = ">="),
            f("spam_value", "number", "骚扰分阈值（可选）", "留空=不限；取值 0.0~1.0",
                min = 0.0, max = 1.0, defaultValue = "0.9"),
            f("audit", "string", "审计级别", "summary=仅摘要 / full=完整记录",
                required = true, enum = RuleDsl.AUDIT_LEVELS, defaultValue = "summary")
        )
    )

    fun schemaFor(domain: String): FormSchema = when (domain) {
        RuleDsl.DOMAIN_NOTIFICATION -> notificationRuleSchema()
        RuleDsl.DOMAIN_CALL_GATE -> callGateRuleSchema()
        else -> notificationRuleSchema()
    }
}

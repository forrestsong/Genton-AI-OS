package com.ainative.systemui.ruleeditor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ainative.systemui.forms.FormRenderer

/**
 * D6.2 规则中心控制器
 *
 * 双页壳：
 *  - 「规则编辑」页：user.* 规则 CRUD + 声明式 schema → 表单（D5.1 FormRenderer）
 *  - 「近 7 天处理」页：透明页（脱敏摘要，数据源可替换为审计服务）
 *
 * 编排：规则列表 → 新增/编辑表单 → 语义校验（RuleDslValidator）→ 本地存储（UserRuleStore）
 * 生效链路（如实标注）：保存到本地 user 规则存储；装载到策略引擎（A7.1 reloadPolicySet）
 * 待策略引擎热装载口子接通后生效。
 */
class RuleCenterController(private val context: Context) {

    // ==================== 状态 ====================
    private val store: UserRuleStore = JsonUserRuleStore(context)
    private val rulesPage = RuleEditorView(context)
    private val recentPage = RecentActionsView(context)
    private var recentDataSource: RecentActionsView.DataSource = RecentActionsView.MockDataSource()

    private var currentFormState: FormRenderer.FormState? = null
    private var editingId: String? = null
    private var editingDomain: String = RuleDsl.DOMAIN_NOTIFICATION

    // ==================== 根视图 ====================
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val contentHost = FrameLayout(context)

    private val tabRules = TextView(context)
    private val tabRecent = TextView(context)

    private val density = context.resources.displayMetrics.density
    private val COLOR_TAB_ON = 0xFF1E88E5.toInt()
    private val COLOR_TAB_OFF = 0xFF757575.toInt()

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        root.orientation = LinearLayout.VERTICAL

        // 顶部 tab 栏
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        tabRules.text = "规则编辑"
        tabRules.textSize = 15f
        tabRules.setTypeface(Typeface.DEFAULT_BOLD)
        tabRules.isClickable = true
        tabRules.setOnClickListener { switchTab(tab = 0) }
        tabRow.addView(tabRules, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        tabRecent.text = "近 7 天处理了什么"
        tabRecent.textSize = 15f
        tabRecent.setTypeface(Typeface.DEFAULT_BOLD)
        tabRecent.isClickable = true
        tabRecent.setOnClickListener { switchTab(tab = 1) }
        tabRow.addView(tabRecent, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabRow)

        val divider = View(context).apply {
            background = GradientDrawable().apply { setColor(0xFFE0E0E0.toInt()) }
        }
        root.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        addSpace(dp(2))

        contentHost.setBackgroundColor(0xFFF7F7F7.toInt())
        root.addView(contentHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 规则列表页监听
        rulesPage.setListener(object : RuleEditorView.Listener {
            override fun onNewRule(domain: String) = showForm(domain, rule = null)
            override fun onEditRule(ruleId: String) {
                val rule = store.load().find { it.id == ruleId } ?: return
                showForm(rule.domain, rule)
            }
            override fun onDeleteRule(ruleId: String) {
                store.delete(ruleId)
                refreshRulesPage()
            }
        })
    }

    // ==================== 生命周期 ====================

    fun attachTo(container: ViewGroup) {
        if (root.parent != container) {
            root.parent?.let { (it as? ViewGroup)?.removeView(root) }
            container.addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
        switchTab(0)
    }

    fun detach() {
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** 设置近 7 天数据源（默认 Mock；接入审计服务时注入 AuditRecentActionsSource） */
    fun setRecentActionsDataSource(ds: RecentActionsView.DataSource) {
        recentDataSource = ds
    }

    // ==================== 页面切换 ====================

    private fun switchTab(tab: Int) {
        tabRules.setTextColor(if (tab == 0) COLOR_TAB_ON else COLOR_TAB_OFF)
        tabRecent.setTextColor(if (tab == 1) COLOR_TAB_ON else COLOR_TAB_OFF)
        if (tab == 0) {
            refreshRulesPage()
        } else {
            val items = recentDataSource.queryLast7Days()
            recentPage.render(items)
            contentShow(scrollWrap(recentPage))
        }
    }

    private fun refreshRulesPage() {
        val rules = store.load()
        rulesPage.render(rules)
        contentShow(scrollWrap(rulesPage))
    }

    private fun contentShow(view: View) {
        contentHost.removeAllViews()
        contentHost.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun scrollWrap(child: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        addView(child)
    }

    // ==================== 表单编辑流程 ====================

    private fun showForm(domain: String, rule: UserRule?) {
        editingDomain = domain
        editingId = rule?.id

        val schema = RuleEditorSchemas.schemaFor(domain)
        val prefill = if (rule != null) prefillFromRule(rule) else defaultPrefill(domain)

        // 组装编辑页
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }

        val backRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backLink = TextView(context).apply {
            text = "← 返回规则列表"
            textSize = 14f
            setTextColor(COLOR_TAB_ON)
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            setOnClickListener { switchTab(0) }
        }
        backRow.addView(backLink)
        page.addView(backRow)

        val notice = TextView(context).apply {
            text = if (rule != null)
                "编辑规则 ${rule.id}（规则 id 创建后不可修改）"
            else if (domain == RuleDsl.DOMAIN_NOTIFICATION)
                "新增通知规则：填写规则并保存。规则保存后可在列表预览 DSL。"
            else "新增来电规则：填写规则并保存。规则保存后可在列表预览 DSL。"
            textSize = 12f
            setTextColor(0xFF9E9E9E.toInt())
        }
        page.addView(notice)

        val statusTv = TextView(context).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        }
        page.addView(statusTv)

        val renderer = FormRenderer(context)
        val formView = renderer.render(schema, prefill, object : FormRenderer.RenderCallback {
            override fun onFormStateChanged(state: FormRenderer.FormState) {
                currentFormState = state
                if (state.schema.meta.validateOnChange) {
                    // 渲染器内联校验已驱动 canSubmit；此处清空旧错误状态
                    statusTv.text = ""
                }
            }

            override fun onSubmitClicked(state: FormRenderer.FormState) {
                handleSubmit(domain, state, statusTv)
            }

            override fun onCancelClicked() {
                switchTab(0)
            }
        })
        page.addView(formView)

        contentShow(scrollWrap(page))
    }

    private fun handleSubmit(
        domain: String,
        state: FormRenderer.FormState,
        statusTv: TextView
    ) {
        val rule = UserRule.fromValues(domain, state.values)

        // 语义校验（DSL 层）
        val errors = RuleDslValidator.validate(rule)
        if (errors.isNotEmpty()) {
            statusTv.setTextColor(0xFFE53935.toInt())
            statusTv.text = errors.joinToString("\n") { "• ${it.second}" }
            return
        }

        // 编辑时禁止改 id
        val existingId = editingId
        if (existingId != null && rule.id != existingId) {
            statusTv.setTextColor(0xFFE53935.toInt())
            statusTv.text = "• 规则 id 创建后不可修改"
            return
        }

        val saved = when {
            existingId != null -> store.update(rule)
            else -> store.save(rule)
        }
        if (!saved) {
            statusTv.setTextColor(0xFFE53935.toInt())
            statusTv.text = "• 保存失败：规则 id 重复或非法"
            return
        }

        // 成功 → 回列表
        editingId = null
        currentFormState = null
        switchTab(0)
    }

    // ==================== 预填 ====================

    private fun defaultPrefill(domain: String): Map<String, String> {
        val base = linkedMapOf<String, String>()
        base["conf_op"] = ">="
        base["audit"] = "summary"
        return if (domain == RuleDsl.DOMAIN_NOTIFICATION) {
            base["rule_id"] = "user.notify.my-rule"
            base["effect"] = "route_p1_digest"
            base["conf_value"] = "0.8"
            base["agg_min"] = "30"
            base["suppress_badge"] = "true"
            base["agent_act"] = "fold"
            base
        } else {
            base["rule_id"] = "user.callgate.my-rule"
            base["effect"] = "route_record_only"
            base["spam_value"] = "0.9"
            base
        }
    }

    private fun prefillFromRule(rule: UserRule): Map<String, String> {
        val m = defaultPrefill(rule.domain).toMutableMap()
        m["rule_id"] = rule.id
        m["effect"] = rule.effect
        if (rule.domain == RuleDsl.DOMAIN_NOTIFICATION) {
            m["categories"] = rule.categories.joinToString(",")
            if (rule.senderDomain.isNotBlank()) m["sender_domain"] = rule.senderDomain
            rule.confValue?.let { m["conf_value"] = it.toString() }
            m["conf_op"] = rule.confOp
            m["dnd_only"] = rule.dndOnly.toString()
            m["hold_dnd"] = rule.holdUntilDndEnd.toString()
            m["agg_min"] = (rule.aggregateWindowMs / 60_000L).toString()
            m["suppress_badge"] = rule.suppressBadge.toString()
            m["agent_act"] = rule.agentAct
        } else {
            m["caller_is"] = rule.callerIs.joinToString(",")
            if (rule.numberPrefix.isNotBlank()) m["number_prefix"] = rule.numberPrefix
            rule.spamValue?.let { m["spam_value"] = it.toString() }
            m["spam_op"] = rule.spamOp
        }
        m["audit"] = rule.audit
        return m
    }

    // ==================== 演示数据 ====================

    fun loadDemoRules() {
        if (store.load().isNotEmpty()) return
        val now = System.currentTimeMillis()
        store.save(
            UserRule(
                id = "user.notify.marketing-digest",
                domain = RuleDsl.DOMAIN_NOTIFICATION,
                effect = "route_p1_digest",
                categories = listOf("marketing", "advertising", "account_promo"),
                confValue = 0.8,
                confOp = ">=",
                aggregateWindowMs = 30 * 60 * 1000L,
                suppressBadge = true,
                audit = "summary",
                updatedAtMs = now - 86400_000L
            )
        )
        store.save(
            UserRule(
                id = "user.callgate.spam-record",
                domain = RuleDsl.DOMAIN_CALL_GATE,
                effect = "route_record_only",
                callerIs = listOf("unknown"),
                spamValue = 0.9,
                spamOp = ">=",
                audit = "full",
                updatedAtMs = now - 2 * 86400_000L
            )
        )
    }

    private fun addSpace(height: Int) {
        root.addView(View(context), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height))
    }
}

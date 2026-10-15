package com.genton.systemui.ruleeditor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * D6.2 规则编辑器 - 规则列表页视图（纯代码构建）
 *
 * 展示 user.* 规则列表：域徽章 / effect / 命中条件摘要 / DSL 预览 / 编辑 / 删除。
 * 新增入口分「通知规则」「来电规则」两域。
 */
class RuleEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onNewRule(domain: String)
        fun onEditRule(ruleId: String)
        fun onDeleteRule(ruleId: String)
    }

    private val density = resources.displayMetrics.density
    private val listContainer = LinearLayout(context)
    private val countView = TextView(context)
    private var listener: Listener? = null
    private val expandedYaml = HashSet<String>()
    private var lastRules: List<UserRule> = emptyList()

    companion object {
        private const val COLOR_TITLE = 0xFF37474F.toInt()
        private const val COLOR_SUBTEXT = 0xFF757575.toInt()
        private const val COLOR_BG_CARD = 0xFFFFFFFF.toInt()
        private const val COLOR_NOTIF = 0xFF1E88E5.toInt()
        private const val COLOR_CALL = 0xFFFB8C00.toInt()
        private const val COLOR_MONO = 0xFF424242.toInt()
        private const val COLOR_YAML_BG = 0xFFF5F5F5.toInt()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF7F7F7.toInt())
        setPadding(dp(16), dp(12), dp(16), dp(24))

        // 标题
        addView(textView("规则编辑器", 20f, COLOR_TITLE, bold = true))

        // 说明
        addView(textView(
            "定义通知 / 来电的自定义处理规则（user.* 命名空间）。" +
                "命中条件为 AND 关系，生效范围取决于策略引擎装载。",
            12f, COLOR_SUBTEXT
        ))
        addView(textView(
            "「user.」规则只能由本编辑器产生；「sys.」平台基线规则不可修改。",
            11f, 0xFF9E9E9E.toInt()
        ))
        addSpace(dp(8))

        // 新增按钮
        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(actionButton("+ 新增通知规则", COLOR_NOTIF) {
            listener?.onNewRule(RuleDsl.DOMAIN_NOTIFICATION)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addSpaceWidth(btnRow, dp(8))
        btnRow.addView(actionButton("+ 新增来电规则", COLOR_CALL) {
            listener?.onNewRule(RuleDsl.DOMAIN_CALL_GATE)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(btnRow)
        addSpace(dp(8))

        // 计数标题
        countView.text = "我的规则"
        countView.textSize = 15f
        countView.setTypeface(Typeface.DEFAULT_BOLD)
        countView.setTextColor(COLOR_TITLE)
        addView(countView)
        addSpace(dp(6))

        listContainer.orientation = VERTICAL
        addView(listContainer)
    }

    fun setListener(l: Listener) {
        listener = l
    }

    fun render(rules: List<UserRule>) {
        lastRules = rules
        listContainer.removeAllViews()
        countView.text = "我的规则（${rules.size}）"

        if (rules.isEmpty()) {
            val empty = textView(
                "暂无用户规则。点击上方按钮新增一条通知或来电规则。",
                13f, COLOR_SUBTEXT
            )
            listContainer.addView(empty)
            return
        }

        // 域内最新优先
        rules.sortedByDescending { it.updatedAtMs }.forEach { rule ->
            listContainer.addView(buildRuleCard(rule))
            addSpace(listContainer, dp(6))
        }
    }

    // ==================== 规则卡片 ====================

    private fun buildRuleCard(rule: UserRule): View {
        val domainColor = if (rule.domain == RuleDsl.DOMAIN_NOTIFICATION) COLOR_NOTIF else COLOR_CALL
        val domainLabel = if (rule.domain == RuleDsl.DOMAIN_NOTIFICATION) "通知" else "来电"

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = roundBg(COLOR_BG_CARD, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // 首行：域徽章 + id + 时间
        val head = row()
        head.addView(badge(domainLabel, domainColor))
        addSpaceWidth(head, dp(6))
        val idView = textView(rule.id, 14f, COLOR_TITLE, bold = true, mono = true)
        head.addView(idView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(textView(
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(rule.updatedAtMs)),
            11f, COLOR_SUBTEXT
        ))
        card.addView(head)

        // 次行：effect 标签
        val effectChip = badge(RuleDsl.effectLabel(rule.effect), domainColor)
        val effectLine = row()
        effectLine.addView(effectChip)
        if (rule.audit == "full") {
            addSpaceWidth(effectLine, dp(6))
            effectLine.addView(badge("full 审计", COLOR_SUBTEXT))
        }
        card.addView(effectLine)

        // 条件摘要
        val cond = textView(conditionSummary(rule), 12f, COLOR_SUBTEXT)
        card.addView(cond)
        addSpace(card, dp(2))

        // 操作：预览 DSL / 编辑 / 删除
        val actions = row().apply { gravity = Gravity.END }
        val yamlExpanded = expandedYaml.contains(rule.id)
        val previewBtn = link("${if (yamlExpanded) "收起" else "预览"} DSL", domainColor) {
            if (yamlExpanded) expandedYaml.remove(rule.id) else expandedYaml.add(rule.id)
            render(lastRules)
        }
        actions.addView(previewBtn)
        addSpaceWidth(actions, dp(10))
        actions.addView(link("编辑", COLOR_NOTIF) { listener?.onEditRule(rule.id) })
        addSpaceWidth(actions, dp(10))
        actions.addView(link("删除", 0xFFE53935.toInt()) { listener?.onDeleteRule(rule.id) })
        card.addView(actions)

        if (yamlExpanded) {
            val yaml = textView(rule.toYaml(), 11f, COLOR_MONO, mono = true)
            yaml.setPadding(dp(8), dp(6), dp(8), dp(6))
            yaml.setBackgroundColor(COLOR_YAML_BG)
            yaml.setLineSpacing(dp(2).toFloat(), 1.0f)
            card.addView(yaml)
        }
        return card
    }

    private fun conditionSummary(rule: UserRule): String = when (rule.domain) {
        RuleDsl.DOMAIN_NOTIFICATION -> buildString {
            append("when: ")
            if (rule.categories.isNotEmpty()) append("分类 ∈ [${rule.categories.joinToString(", ")}]")
            else append("分类任意")
            if (rule.senderDomain.isNotBlank()) append(" 且 来源域=${rule.senderDomain}")
            rule.confValue?.let { append(" 且 置信度 ${rule.confOp} $it") }
            if (rule.dndOnly) append(" 且 仅 DND")
        }
        RuleDsl.DOMAIN_CALL_GATE -> buildString {
            append("when: ")
            if (rule.callerIs.isNotEmpty()) append("主叫 ∈ [${rule.callerIs.joinToString(", ")}]")
            else append("主叫任意")
            if (rule.numberPrefix.isNotBlank()) append(" 且 号段=${rule.numberPrefix}")
            rule.spamValue?.let { append(" 且 骚扰分 ${rule.spamOp} $it") }
        }
        else -> ""
    }

    // ==================== 组件构建 ====================

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun textView(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        maxLines = Integer.MAX_VALUE
    }

    private fun badge(text: String, color: Int): TextView {
        val chip = textView(text, 11f, color)
        chip.background = roundBg(Color.argb(0x22, Color.red(color), Color.green(color), Color.blue(color)), 6f)
        chip.setPadding(dp(7), dp(2), dp(7), dp(2))
        return chip
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView {
        val btn = textView(label, 14f, Color.WHITE, bold = true)
        btn.background = roundBg(color, 10f)
        btn.gravity = Gravity.CENTER
        btn.setPadding(dp(8), dp(10), dp(8), dp(10))
        btn.isClickable = true
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun link(label: String, color: Int, onClick: () -> Unit): TextView {
        val tv = textView(label, 13f, color, bold = true)
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun roundBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun addSpace(height: Int) {
        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, height))
    }

    private fun addSpace(container: LinearLayout, height: Int) {
        container.addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, height))
    }

    private fun addSpaceWidth(container: LinearLayout, width: Int) {
        container.addView(View(context), LayoutParams(width, dp(1)))
    }
}

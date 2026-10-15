package com.ainative.systemui.notificationstack

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ainative.systemui.notificationstack.NotificationStackModels.DigestCard
import com.ainative.systemui.notificationstack.NotificationStackModels.StackNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * D6.1 通知栈三区视图
 *
 * 呈现三层通知档位：
 *  - P0 原生区：保持原生样式的通知行（穿透真实通知栈的镜像）
 *  - P1 简报区：聚合简报卡（窗口内同规则聚合、来源去重、条目明细）
 *  - P2 已处理区：Agent 代处理（折叠/静默）条目
 *
 * 「原文回看展开」：P0 行 / P1 明细条目 / P2 条目均可展开查看掩码原文（原文已 PII 掩码，
 * 掩码由 A2.3 AgentNotificationService 完成，此层不接触未掩码原文）。
 *
 * 纯代码构建视图（无 res/layout），与 D4.3 PolicyManagementView 风格一致。
 */
class NotificationStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val rootContainer = LinearLayout(context)
    private var gate: NotificationStackGate? = null

    // P1 卡片的「查看明细」本地展开态（原文展开态在 Gate 中）
    private val cardExpandedIds = HashSet<String>()

    companion object {
        private const val COLOR_BG = 0xFFF7F7F7.toInt()
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()
        private const val COLOR_HEADER = 0xFF37474F.toInt()
        private const val COLOR_SUBTEXT = 0xFF757575.toInt()
        private const val COLOR_DIVIDER = 0xFFE0E0E0.toInt()

        // 档位主题色
        private const val COLOR_P0 = 0xFF1E88E5.toInt()    // 蓝：原生
        private const val COLOR_P1 = 0xFF43A047.toInt()    // 绿：简报
        private const val COLOR_P2 = 0xFFFB8C00.toInt()    // 橙：已处理

        // category → 优先级主题色（对齐 C1.2 值域 priority 分组）
        private val BASELINE_CATS = setOf(
            "emergency", "alarm", "system_critical", "phone_ring_equivalent"
        )
        private val HIGH_CATS = setOf(
            "otp", "payment_result", "message", "system_info"
        )
        private val COLOR_CAT_BASELINE = 0xFFE53935.toInt()  // 红：不可打扰
        private val COLOR_CAT_HIGH = 0xFF1E88E5.toInt()      // 蓝：重要
        private val COLOR_CAT_MEDIUM = 0xFF43A047.toInt()    // 绿：普通
        private val COLOR_CAT_LOW = 0xFF757575.toInt()       // 灰：营销/低频
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        isFillViewport = true
        setBackgroundColor(COLOR_BG)
        rootContainer.orientation = LinearLayout.VERTICAL
        rootContainer.setPadding(dp(16), dp(12), dp(16), dp(24))
        addView(
            rootContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    /** 绑定 Gate 并渲染 */
    fun bind(gate: NotificationStackGate) {
        this.gate = gate
        render()
    }

    fun render() {
        val g = gate ?: return
        rootContainer.removeAllViews()

        buildHeader(g)
        buildP0Section(g)
        buildP1Section(g)
        buildP2Section(g)
    }

    // ==================== 头部 ====================

    private fun buildHeader(g: NotificationStackGate) {
        val stats = g.getStats()
        val title = TextView(context).apply {
            text = "AI 通知栈"
            setTextColor(COLOR_HEADER)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        rootContainer.addView(title)

        val subtitle = TextView(context).apply {
            text = buildString {
                append("P0 ${stats["p0_count"]} · P1 简报 ${stats["digest_card_count"]} 卡 / ${stats["p1_entry_count"]} 条")
                append(" · P2 已处理 ${stats["p2_count"]}")
                if (g.getDeferredCount() > 0) append(" · ${g.getDeferredCount()} 条延后")
                if (stats["dnd_active"] == true) append(" · DND 中")
            }
            setTextColor(COLOR_SUBTEXT)
            textSize = 12f
        }
        rootContainer.addView(subtitle)
        addSpace(dp(4))
    }

    // ==================== P0 原生区 ====================

    private fun buildP0Section(g: NotificationStackGate) {
        val list = g.getP0Notifications()
        addSectionHeader("P0 · 原生呈现", list.size, COLOR_P0)
        if (list.isEmpty()) {
            addEmptyHint("当前无 P0 原生通知")
            return
        }
        list.forEach { n ->
            addNotificationRow(
                n = n,
                accentColor = COLOR_P0,
                tagText = "原生",
                gate = g
            )
        }
        addSpace(dp(4))
    }

    // ==================== P1 简报区 ====================

    private fun buildP1Section(g: NotificationStackGate) {
        val cards = g.getDigestCards()
        val deferred = g.getDeferredCount()
        addSectionHeader("P1 · Agent 简报卡", cards.size, COLOR_P1)
        if (cards.isEmpty() && deferred == 0) {
            addEmptyHint("无简报聚合")
            return
        }
        if (deferred > 0) {
            addDeferredHint(deferred)
        }
        cards.forEach { card -> addDigestCard(g, card) }
        addSpace(dp(4))
    }

    private fun addDigestCard(g: NotificationStackGate, card: DigestCard) {
        val expanded = cardExpandedIds.contains(card.cardId)
        val cardBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCard(COLOR_CARD, 12f)
            val p = dp(12)
            setPadding(p, dp(10), p, dp(10))
        }

        // 卡片头：点击查看明细/收起
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                if (expanded) cardExpandedIds.remove(card.cardId)
                else cardExpandedIds.add(card.cardId)
                render()
            }
        }

        header.addView(dot(COLOR_P1))
        val summary = TextView(context).apply {
            text = "${card.sourceSummary} · ${card.entryCount} 条未读聚合"
            setTextColor(COLOR_HEADER)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(summary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val ruleTag = TextView(context).apply {
            text = card.ruleId
            textSize = 10f
            setTextColor(COLOR_P1)
            background = roundedCard(0x2243A047, 6f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        header.addView(ruleTag)

        cardBody.addView(header)
        addSpace(cardBody, dp(4))

        if (expanded) {
            addDivider(cardBody)
            val entries = g.getDigestEntries(card.cardId)
            entries.forEach { entry ->
                addDigestEntryRow(cardBody, g, entry)
            }
            addCardFooter(cardBody, card)
        }
        rootContainer.addView(cardBody)
        addSpace(dp(8))
    }

    private fun addDigestEntryRow(container: LinearLayout, g: NotificationStackGate, n: StackNotification) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(categoryChip(n.category))
        val preview = TextView(context).apply {
            text = n.title ?: n.appLabel
            setTextColor(COLOR_HEADER)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line.addView(preview, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(timeText(n.postTimeMs))
        row.addView(line)

        // 原文回看展开
        val showOriginal = g.isExpanded(n.key)
        val toggle = TextView(context).apply {
            text = if (showOriginal) "收起原文" else "原文回看"
            textSize = 12f
            setTextColor(COLOR_P1)
            isClickable = true
            setOnClickListener { g.toggleExpanded(n.key) }
        }
        row.addView(toggle)

        if (showOriginal) {
            row.addView(originalTextView(n.originalText))
        }
        container.addView(row)
    }

    private fun addCardFooter(container: LinearLayout, card: DigestCard) {
        val footer = TextView(context).apply {
            text = "摘要模型 ${card.summaryModel} · 规则 ${card.ruleId} · 窗口 ${fmtWindow(card)}"
            textSize = 11f
            setTextColor(COLOR_SUBTEXT)
        }
        container.addView(footer)
    }

    // ==================== P2 已处理区 ====================

    private fun buildP2Section(g: NotificationStackGate) {
        val list = g.getP2Notifications()
        addSectionHeader("P2 · Agent 已处理", list.size, COLOR_P2)
        if (list.isEmpty()) {
            addEmptyHint("无 Agent 代处理通知")
            return
        }
        list.forEach { n ->
            val act = g.agentActOf(n.key)
            addNotificationRow(
                n = n,
                accentColor = COLOR_P2,
                tagText = act?.label ?: "已处理",
                gate = g,
                showDismiss = true
            )
        }
        addSpace(dp(4))
    }

    // ==================== 通用行渲染 ====================

    /**
     * 渲染单条通知行（P0 原生 / P2 已处理共用），含「原文回看展开」与可选「移除」。
     */
    private fun addNotificationRow(
        n: StackNotification,
        accentColor: Int,
        tagText: String,
        gate: NotificationStackGate,
        showDismiss: Boolean = false
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCard(COLOR_CARD, 12f)
            val p = dp(12)
            setPadding(p, dp(10), p, dp(10))
        }

        // 首行：指示点 + 应用名 + 时间 + 档位标签
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(dot(accentColor))
        val app = TextView(context).apply {
            text = n.appLabel
            setTextColor(COLOR_HEADER)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        head.addView(app, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val pinned = gate.isPinnedP0(n.packageName) || n.userPinnedP0
        if (pinned) {
            val pinTag = TextView(context).apply {
                text = "钉选 P0"
                textSize = 10f
                setTextColor(COLOR_P0)
                background = roundedCard(0x221E88E5, 6f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            head.addView(pinTag)
            addSpaceWidth(head, dp(6))
        }

        val tag = TextView(context).apply {
            text = tagText
            textSize = 10f
            setTextColor(accentColor)
            background = roundedCard(Color.argb(0x22, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)), 6f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        head.addView(tag)
        addSpaceWidth(head, dp(6))
        head.addView(timeText(n.postTimeMs))
        row.addView(head)

        // 标题（category chip + 标题）
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        body.addView(categoryChip(n.category))
        val title = TextView(context).apply {
            text = n.title ?: "（无标题）"
            setTextColor(COLOR_HEADER)
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        body.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(body)

        // 操作行
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val showOriginal = gate.isExpanded(n.key)
        val originalBtn = linkButton(if (showOriginal) "收起原文" else "原文回看", accentColor)
        originalBtn.setOnClickListener { gate.toggleExpanded(n.key) }
        actions.addView(originalBtn)

        if (showDismiss) {
            addSpaceWidth(actions, dp(8))
            val dismissBtn = linkButton("移除", COLOR_SUBTEXT)
            dismissBtn.setOnClickListener { gate.dismissEntry(n.key) }
            actions.addView(dismissBtn)
        }
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (showOriginal) {
            row.addView(originalTextView(n.originalText))
        }
        rootContainer.addView(row)
        addSpace(dp(6))
    }

    // ==================== 子组件构建器 ====================

    private fun addSectionHeader(label: String, count: Int, color: Int) {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(2).toFloat()
            }
        }
        section.addView(bar, LinearLayout.LayoutParams(dp(4), dp(14)))
        addSpaceWidth(section, dp(6))
        val text = TextView(context).apply {
            text = "$label  ($count)"
            setTextColor(COLOR_HEADER)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        section.addView(text)
        rootContainer.addView(section)
        addSpace(dp(6))
    }

    private fun addEmptyHint(text: String) {
        val hint = TextView(context).apply {
            this.text = text
            setTextColor(COLOR_SUBTEXT)
            textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
        }
        rootContainer.addView(hint)
    }

    private fun addDeferredHint(count: Int) {
        val hint = TextView(context).apply {
            text = "免打扰期间延后 $count 条简报，结束后统一聚合"
            textSize = 12f
            setTextColor(COLOR_P1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedCard(0x2243A047, 8f)
        }
        rootContainer.addView(hint)
        addSpace(dp(4))
    }

    private fun categoryChip(category: String): TextView {
        val color = categoryColor(category)
        return TextView(context).apply {
            text = category
            textSize = 10f
            setTextColor(color)
            background = roundedCard(
                Color.argb(0x22, Color.red(color), Color.green(color), Color.blue(color)), 6f
            )
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6)
            layoutParams = lp
        }
    }

    private fun categoryColor(category: String): Int = when {
        category in BASELINE_CATS -> COLOR_CAT_BASELINE
        category in HIGH_CATS -> COLOR_CAT_HIGH
        category in setOf("news", "service_notice", "social_updates") -> COLOR_CAT_MEDIUM
        else -> COLOR_CAT_LOW
    }

    private fun dot(color: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val size = dp(8)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.rightMargin = dp(8)
        layoutParams = lp
    }

    private fun timeText(ms: Long): TextView = TextView(context).apply {
        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        textSize = 11f
        setTextColor(COLOR_SUBTEXT)
    }

    private fun originalTextView(original: String?): TextView {
        val tv = TextView(context).apply {
            text = original?.takeIf { it.isNotBlank() } ?: "（无原文内容）"
            textSize = 12f
            setTextColor(0xFF616161.toInt())
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedCard(0xFFF5F5F5, 8f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            topMargin = dp(4)
            layoutParams = lp
        }
        return tv
    }

    private fun linkButton(text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
    }

    private fun roundedCard(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun fmtWindow(card: DigestCard): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${fmt.format(Date(card.windowStartMs))}–${fmt.format(Date(card.windowEndMs))}"
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(context).apply {
            background = GradientDrawable().apply {
                setColor(COLOR_DIVIDER)
            }
        }
        container.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ))
    }

    private fun addSpace(height: Int) {
        val space = View(context)
        rootContainer.addView(space, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height
        ))
    }

    private fun addSpace(container: LinearLayout, height: Int) {
        val space = View(context)
        container.addView(space, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height
        ))
    }

    private fun addSpaceWidth(container: LinearLayout, width: Int) {
        val space = View(context)
        container.addView(space, LinearLayout.LayoutParams(width, dp(1)))
    }
}

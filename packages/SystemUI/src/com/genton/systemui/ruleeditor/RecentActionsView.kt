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
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * D6.2 「近 7 天处理了什么」透明页
 *
 * 展示最近 7 天 Agent/系统对通知与来电的自主处理（简报聚合、静默代处理、来电拦截等）。
 * 隐私：仅展示脱敏摘要（summary），不含原文与识别信息——与 AuditService USER viewerRole
 * 脱敏规则一致（只保留 timestamp/category/summary）。
 *
 * 数据源映射（真实链路，Binder 接通后启用）：
 *   AgentAuditService.queryEvents(AuditQuery(viewerRole=USER))
 *   → category 1-7 → RecentActionItem（按 timestamp ≥ now-7d 客户端过滤）
 */
class RecentActionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 处理动作类型 */
    enum class ActionKind(val label: String, val color: Int) {
        NOTIFY_DIGEST("简报聚合", 0xFF43A047.toInt()),
        NOTIFY_AGENT_ACT("Agent 代处理", 0xFFFB8C00.toInt()),
        CALL_HANDLE("来电处理", 0xFF00ACC1.toInt()),
        CALL_RECORD("来电录音", 0xFF8E24AA.toInt())
    }

    /** 透明页条目（USER 视角脱敏） */
    data class RecentActionItem(
        val id: String,
        val timestampMs: Long,
        val kind: ActionKind,
        val maskedSummary: String,
        val ruleId: String? = null,
        val count: Int = 1
    )

    /** 近 7 天数据源 */
    interface DataSource {
        fun queryLast7Days(): List<RecentActionItem>
    }

    /** Mock 数据源（无上游审计服务时用于目测验证） */
    class MockDataSource : DataSource {
        override fun queryLast7Days(): List<RecentActionItem> {
            val now = System.currentTimeMillis()
            val day = 24 * 3600 * 1000L
            return listOf(
                RecentActionItem("m1", now - 40 * 60 * 1000L, ActionKind.NOTIFY_DIGEST,
                    "聚合了购物类通知 3 条（营销/促销），生成了 1 张简报卡", ruleId = "user.notify.marketing-digest", count = 3),
                RecentActionItem("m2", now - 2 * 3600 * 1000L, ActionKind.NOTIFY_AGENT_ACT,
                    "静默处理了 1 条广告推送", ruleId = "sys.notify.advertising_to_p2"),
                RecentActionItem("m3", now - day - 2 * 3600 * 1000L, ActionKind.CALL_HANDLE,
                    "拦截了 1 通疑似骚扰来电（骚扰分 0.94）", ruleId = "user.callgate.spam-record"),
                RecentActionItem("m4", now - 2 * day - 5 * 3600 * 1000L, ActionKind.NOTIFY_DIGEST,
                    "聚合了 2 条新闻推送为简报", ruleId = "sys.notify.news_to_p1", count = 2),
                RecentActionItem("m5", now - 3 * day - 1 * 3600 * 1000L, ActionKind.CALL_RECORD,
                    "录音记录了 1 通未知号码来电", ruleId = "user.callgate.spam-record"),
                RecentActionItem("m6", now - 4 * day - 6 * 3600 * 1000L, ActionKind.NOTIFY_AGENT_ACT,
                    "折叠了 1 条应用更新通知", ruleId = "sys.notify.promo_fold_to_p2"),
                RecentActionItem("m7", now - 5 * day - 3 * 3600 * 1000L, ActionKind.CALL_HANDLE,
                    "Agent 应答了 1 通客服来电", ruleId = "user.callgate.agent-service")
            )
        }
    }

    private val density = resources.displayMetrics.density

    companion object {
        private const val COLOR_TITLE = 0xFF37474F.toInt()
        private const val COLOR_SUBTEXT = 0xFF757575.toInt()
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF7F7F7.toInt())
        setPadding(dp(16), dp(12), dp(16), dp(24))

        addView(text("近 7 天处理了什么", 20f, COLOR_TITLE, bold = true))
        addView(text("Agent 与系统近 7 天自主处理的透明记录。仅展示脱敏摘要，不含原文与识别信息。",
            12f, COLOR_SUBTEXT))
        addSpace(dp(10))
    }

    fun render(items: List<RecentActionItem>) {
        removeAllViews()
        addView(text("近 7 天处理了什么", 20f, COLOR_TITLE, bold = true))
        addView(text("Agent 与系统近 7 天自主处理的透明记录。仅展示脱敏摘要，不含原文与识别信息。",
            12f, COLOR_SUBTEXT))
        addSpace(dp(10))

        // 统计卡
        addView(buildSummaryCard(items))
        addSpace(dp(10))

        addView(text("处理明细", 15f, COLOR_TITLE, bold = true))
        addSpace(dp(6))

        if (items.isEmpty()) {
            addView(text("近 7 天没有自主处理记录。", 13f, COLOR_SUBTEXT))
            return
        }

        // 按天分组
        val sorted = items.sortedByDescending { it.timestampMs }
        var currentDayKey: String? = null
        for (item in sorted) {
            val key = dayKey(item.timestampMs)
            if (key != currentDayKey) {
                currentDayKey = key
                val dayLabel = dayLabel(item.timestampMs)
                val header = text(dayLabel, 13f, 0xFF9E9E9E.toInt(), bold = true)
                header.setPadding(dp(2), dp(8), 0, dp(2))
                addView(header)
            }
            addView(buildItemRow(item))
            addSpace(dp(4))
        }
    }

    private fun buildSummaryCard(items: List<RecentActionItem>): View {
        val digest = items.filter { it.kind == ActionKind.NOTIFY_DIGEST }.sumOf { it.count }
        val agent = items.filter { it.kind == ActionKind.NOTIFY_AGENT_ACT }.size
        val calls = items.filter { it.kind == ActionKind.CALL_HANDLE || it.kind == ActionKind.CALL_RECORD }.size

        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = roundBg(COLOR_CARD, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(statCell("聚合简报", "$digest 条", 0xFF43A047.toInt()),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(statCell("静默处理", "$agent 条", 0xFFFB8C00.toInt()),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(statCell("来电处理", "$calls 通", 0xFF00ACC1.toInt()),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun statCell(label: String, value: String, color: Int): View {
        val cell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        val v = text(value, 20f, color, bold = true)
        cell.addView(v)
        val l = text(label, 11f, COLOR_SUBTEXT)
        cell.addView(l)
        return cell
    }

    private fun buildItemRow(item: RecentActionItem): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = roundBg(COLOR_CARD, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(chip(item.kind.label, item.kind.color))
        addSpaceWidth(head, dp(8))
        head.addView(text(timeOf(item.timestampMs), 12f, COLOR_SUBTEXT))
        card.addView(head)
        addSpace(card, dp(2))

        card.addView(text(item.maskedSummary, 13f, 0xFF212121.toInt()))
        item.ruleId?.let {
            addSpace(card, dp(2))
            card.addView(text("规则: $it", 11f, 0xFF9E9E9E.toInt()))
        }
        return card
    }

    // ==================== 工具 ====================

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }

    private fun chip(label: String, color: Int): TextView {
        val tv = text(label, 11f, color)
        tv.background = roundBg(Color.argb(0x22, Color.red(color), Color.green(color), Color.blue(color)), 6f)
        tv.setPadding(dp(7), dp(2), dp(7), dp(2))
        return tv
    }

    private fun roundBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun addSpace(h: Int) {
        addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, h))
    }

    private fun addSpace(container: LinearLayout, h: Int) {
        container.addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, h))
    }

    private fun addSpaceWidth(container: LinearLayout, w: Int) {
        container.addView(View(context), LayoutParams(w, dp(1)))
    }

    private fun dayKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun dayLabel(ms: Long): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = ms
        val that = cal.get(Calendar.DAY_OF_YEAR)
        val yearToday = Calendar.getInstance().get(Calendar.YEAR)
        return when {
            yearToday == cal.get(Calendar.YEAR) && today - that == 0 -> "今天"
            yearToday == cal.get(Calendar.YEAR) && today - that == 1 -> "昨天"
            else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun timeOf(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}

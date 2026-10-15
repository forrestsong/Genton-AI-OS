package com.genton.systemui.scheduleprep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * D7.2 日程预准备卡片视图
 *
 * 展示日程预准备状态，包含：
 * - 日程标题 + 倒计时 + 紧急程度徽章
 * - 总体就绪进度条
 * - 凭据注入步骤列表（敏感值不出屏）
 * - 创作任务列表
 * - 一键关停按钮
 *
 * 设计约束：
 * - 所有敏感值（凭据内容、会议号码等）一律脱敏
 * - 凭据注入进度只显示百分比和类型，不展示具体凭据值
 * - 一键关停可随时触发，关停后状态更新为 SKIPPED
 */
class SchedulePrepCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val countdownView: TextView
    private val urgencyBadge: TextView
    private val statusView: TextView
    private val overallProgressBar: ProgressBarView
    private val credentialContainer: LinearLayout
    private val creativeTaskContainer: LinearLayout
    private val stopButton: TextView
    private val summaryView: TextView

    private var currentSnapshot: SchedulePrepSnapshot? = null
    private var onStopRequest: (() -> Unit)? = null

    companion object {
        // 颜色定义
        private val COLOR_URGENCY_NORMAL = Color.parseColor("#4CAF50")      // 绿
        private val COLOR_URGENCY_ELEVATED = Color.parseColor("#FFC107")    // 黄
        private val COLOR_URGENCY_URGENT = Color.parseColor("#F44336")    // 红
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#B0B0B0")
        private val COLOR_STATUS_WAITING = Color.parseColor("#90A4AE")
        private val COLOR_STATUS_PREPARING = Color.parseColor("#2196F3")
        private val COLOR_STATUS_READY = Color.parseColor("#4CAF50")
        private val COLOR_STATUS_FAILED = Color.parseColor("#F44336")
        private val COLOR_STOP_BUTTON = Color.parseColor("#FF5722")
        private val COLOR_CARD_BG = Color.parseColor("#1F2937") // 深灰蓝背景
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_CARD_BG)
        val padding16dp = (16 * resources.displayMetrics.density).toInt()
        val padding12dp = (12 * resources.displayMetrics.density).toInt()
        setPadding(padding16dp, padding16dp, padding16dp, padding16dp)

        // 顶部区域：标题 + 倒计时 + 紧急徽章
        val headerLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        titleView = TextView(context).apply {
            textSize = 18f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }

        urgencyBadge = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val badgePadding = (4 * resources.displayMetrics.density).toInt()
            setPadding(badgePadding * 2, badgePadding, badgePadding * 2, badgePadding)
            // 初始背景，随 urgency 变化
            setBackgroundColor(COLOR_URGENCY_NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            }
        }

        countdownView = TextView(context).apply {
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }

        statusView = TextView(context).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        overallProgressBar = ProgressBarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt()
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        val sectionLabel1 = createSectionLabel("凭据注入进度")
        credentialContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }

        val sectionLabel2 = createSectionLabel("创作任务")
        creativeTaskContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }

        summaryView = TextView(context).apply {
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        stopButton = TextView(context).apply {
            text = "一键关停预准备"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_STOP_BUTTON)
            val btnPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { onStopRequest?.invoke() }
        }

        // 组装 header
        headerLayout.addView(titleView)
        headerLayout.addView(urgencyBadge)

        // 组装主布局
        addView(headerLayout)
        addView(countdownView)
        addView(statusView)
        addView(overallProgressBar)
        addView(sectionLabel1)
        addView(credentialContainer)
        addView(sectionLabel2)
        addView(creativeTaskContainer)
        addView(summaryView)
        addView(stopButton)
    }

    private fun createSectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(COLOR_TEXT_SECONDARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }
    }

    /**
     * 设置关停回调
     */
    fun setOnStopRequest(callback: () -> Unit) {
        onStopRequest = callback
    }

    /**
     * 渲染日程预准备状态
     */
    fun render(snapshot: SchedulePrepSnapshot) {
        currentSnapshot = snapshot
        val event = snapshot.event

        // 标题
        titleView.text = event.titleMasked

        // 紧急程度徽章
        when (event.urgency) {
            UrgencyLevel.NORMAL -> {
                urgencyBadge.text = "常规"
                urgencyBadge.setBackgroundColor(COLOR_URGENCY_NORMAL)
            }
            UrgencyLevel.ELEVATED -> {
                urgencyBadge.text = "较高"
                urgencyBadge.setBackgroundColor(COLOR_URGENCY_ELEVATED)
            }
            UrgencyLevel.URGENT -> {
                urgencyBadge.text = "紧急"
                urgencyBadge.setBackgroundColor(COLOR_URGENCY_URGENT)
            }
        }

        // 倒计时
        countdownView.text = formatCountdown(event.secondsUntilStart)

        // 状态
        renderStatus(event.status)

        // 总体进度
        overallProgressBar.setProgress(snapshot.overallProgress)

        // 凭据注入步骤
        renderCredentialSteps(snapshot.credentialSteps)

        // 创作任务
        renderCreativeTasks(snapshot.creativeTasks)

        // 摘要
        renderSummary(snapshot)

        // 关停按钮
        stopButton.visibility = if (snapshot.canStop && event.status != PrepStatus.READY) View.VISIBLE else View.GONE
    }

    private fun renderStatus(status: PrepStatus) {
        val (text, color) = when (status) {
            PrepStatus.WAITING -> "⏳ 等待中" to COLOR_STATUS_WAITING
            PrepStatus.PREPARING -> "⚙️ 正在准备..." to COLOR_STATUS_PREPARING
            PrepStatus.READY -> "✅ 已就绪" to COLOR_STATUS_READY
            PrepStatus.SKIPPED -> "⏭️ 已跳过" to COLOR_STATUS_WAITING
            PrepStatus.FAILED -> "❌ 准备失败" to COLOR_STATUS_FAILED
        }
        statusView.text = text
        statusView.setTextColor(color)
    }

    private fun renderCredentialSteps(steps: List<CredentialInjectionStep>) {
        credentialContainer.removeAllViews()

        if (steps.isEmpty()) {
            return
        }

        val stepHeight = (28 * resources.displayMetrics.density).toInt()

        for (step in steps) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    stepHeight
                )
            }

            // 步骤状态图标
            val statusIcon = TextView(context).apply {
                text = when (step.status) {
                    CredentialStepStatus.COMPLETED -> "✓"
                    CredentialStepStatus.FAILED -> "✗"
                    CredentialStepStatus.INJECTING -> "…"
                    CredentialStepStatus.SKIPPED -> "-"
                    CredentialStepStatus.PENDING -> "○"
                }
                textSize = 14f
                setTextColor(
                    when (step.status) {
                        CredentialStepStatus.COMPLETED -> COLOR_STATUS_READY
                        CredentialStepStatus.FAILED -> COLOR_STATUS_FAILED
                        else -> COLOR_TEXT_SECONDARY
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
            }

            // 凭据类型描述
            val typeLabel = TextView(context).apply {
                text = step.credentialTypeMasked
                textSize = 12f
                setTextColor(COLOR_TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
            }

            // 进度百分比（值不出屏：仅显示百分比，不显示具体凭据值）
            val progressLabel = TextView(context).apply {
                text = if (step.isDone) {
                    if (step.status == CredentialStepStatus.SKIPPED) "跳过" else "完成"
                } else {
                    "${step.progress}%"
                }
                textSize = 11f
                setTextColor(COLOR_TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                }
            }

            row.addView(statusIcon)
            row.addView(typeLabel)
            row.addView(progressLabel)
            credentialContainer.addView(row)

            // 进度条（仅在 INJECTING 状态显示）
            if (step.status == CredentialStepStatus.INJECTING && step.progress > 0) {
                val bar = ProgressBarView(context).apply {
                    setProgress(step.progress)
                    layoutParams = LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        (3 * resources.displayMetrics.density).toInt()
                    )
                }
                credentialContainer.addView(bar)
            }
        }
    }

    private fun renderCreativeTasks(tasks: List<CreativeTask>) {
        creativeTaskContainer.removeAllViews()

        if (tasks.isEmpty()) {
            return
        }

        val taskHeight = (32 * resources.displayMetrics.density).toInt()

        for (task in tasks) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    taskHeight
                )
            }

            val statusIcon = TextView(context).apply {
                text = if (task.isCompleted) "✓" else "○"
                textSize = 14f
                setTextColor(if (task.isCompleted) COLOR_STATUS_READY else COLOR_TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
            }

            val taskTitle = TextView(context).apply {
                text = task.title
                textSize = 12f
                setTextColor(if (task.isCompleted) COLOR_TEXT_SECONDARY else COLOR_TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
            }

            val progressLabel = TextView(context).apply {
                text = if (task.isCompleted) "完成" else "${task.progress}%"
                textSize = 11f
                setTextColor(COLOR_TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(statusIcon)
            row.addView(taskTitle)
            row.addView(progressLabel)
            creativeTaskContainer.addView(row)

            if (!task.isCompleted && task.progress > 0) {
                val bar = ProgressBarView(context).apply {
                    setProgress(task.progress)
                    layoutParams = LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        (3 * resources.displayMetrics.density).toInt()
                    )
                }
                creativeTaskContainer.addView(bar)
            }
        }
    }

    private fun renderSummary(snapshot: SchedulePrepSnapshot) {
        val completedCred = snapshot.credentialSteps.count { it.isDone }
        val totalCred = snapshot.credentialSteps.size
        val completedTasks = snapshot.creativeTasks.count { it.isCompleted }
        val totalTasks = snapshot.creativeTasks.size

        val parts = mutableListOf<String>()
        if (totalCred > 0) {
            parts.add("凭据注入 $completedCred/$totalCred")
        }
        if (totalTasks > 0) {
            parts.add("创作 $completedTasks/$totalTasks")
        }
        if (snapshot.stopReason != null) {
            parts.add("已关停：${snapshot.stopReason}")
        }

        summaryView.text = if (parts.isNotEmpty()) parts.joinToString(" · ") else "无预准备项"
    }

    /**
     * 格式化倒计时
     */
    private fun formatCountdown(secondsUntilStart: Long): String {
        return when {
            secondsUntilStart < 0 -> "已开始 ${formatDuration(-secondsUntilStart)}"
            secondsUntilStart < 60 -> "${secondsUntilStart} 秒后开始"
            secondsUntilStart < 3600 -> "${secondsUntilStart / 60} 分钟后开始"
            else -> "${secondsUntilStart / 3600} 小时 ${(secondsUntilStart % 3600) / 60} 分钟后开始"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val min = seconds / 60
        val sec = seconds % 60
        return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
    }

    /**
     * 进度条视图（简单的水平进度条，无依赖任何 drawable 资源）
     */
    private class ProgressBarView @JvmOverloads constructor(
        ctx: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(ctx, attrs, defStyleAttr) {

        private var progress: Int = 0
            set(value) {
                field = value.coerceIn(0, 100)
                invalidate()
            }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#374151")
        }

        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            val corner = height / 2f

            // 背景
            canvas.drawRoundRect(
                RectF(0f, 0f, width, height),
                corner, corner, bgPaint
            )

            // 前景进度
            if (progress > 0) {
                val progressWidth = width * progress / 100f
                canvas.drawRoundRect(
                    RectF(0f, 0f, progressWidth, height),
                    corner, corner, fgPaint
                )
            }
        }
    }
}

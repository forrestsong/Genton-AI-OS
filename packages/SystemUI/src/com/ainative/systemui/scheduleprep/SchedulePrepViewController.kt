package com.ainative.systemui.scheduleprep

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * D7.2 日程预准备呈现 - 控制器
 *
 * 核心职责：
 * 1. 管理日程预准备状态机（WAITING → PREPARING → READY/FAILED）
 * 2. 驱动 UI 更新（倒计时、进度条、状态变更）
 * 3. 处理凭据注入进度和创作任务状态
 * 4. 支持一键关停（stopAllPrep）
 * 5. Mock 数据生成（无上游服务时演示用）
 *
 * 依赖上游（已满足）：
 * - A3.6 内部动作：sys.schedule_prep（日程预准备触发）
 * - C6.2 激活 schema：sys.schedule.prep_v1（预准备 schema）
 * - B3.2 卡片推送：创作任务 ActionCardData 关联
 *
 * 安全约束：
 * - 凭据值永远不直接展示，仅显示类型 + 进度百分比
 * - 关停后立即清理所有预准备状态
 * - 敏感场景（紧急）时降载 UI 自动触发（D8.2 联动）
 */
class SchedulePrepViewController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cardView: SchedulePrepCardView
    private val windowParams: WindowManager.LayoutParams

    /** 活跃的预准备快照（按 eventId 存储，v1 同一时刻只有一个活动预准备） */
    private var activeSnapshot: SchedulePrepSnapshot? = null

    /** 倒计时定时任务 */
    private var countdownRunnable: Runnable? = null

    /** 演示模式：无上游服务时注入模拟数据 */
    private var demoMode = true

    companion object {
        private const val TAG = "SchedulePrepCtrl"
    }

    init {
        cardView = SchedulePrepCardView(context)

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 卡片贴状态栏下方
            val statusBarHeight = run {
                val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
            }
            y = statusBarHeight + 8
            // 初始不可见
            width = WindowManager.LayoutParams.MATCH_PARENT
        }

        cardView.setOnStopRequest {
            stopAllPrep()
        }
    }

    /**
     * 设置演示模式
     */
    fun setDemoMode(enabled: Boolean) {
        demoMode = enabled
    }

    /**
     * 启动一个日程预准备会话
     *
     * @param snapshot 初始预准备快照
     */
    fun startPrep(snapshot: SchedulePrepSnapshot) {
        mainHandler.post {
            activeSnapshot = snapshot
            showCard()
            cardView.render(snapshot)
            startCountdown()
            Log.i(TAG, "Prep started: eventId=${snapshot.event.eventId}, status=${snapshot.event.status}")
        }
    }

    /**
     * 更新预准备状态
     */
    fun updateSnapshot(snapshot: SchedulePrepSnapshot) {
        mainHandler.post {
            activeSnapshot = snapshot
            cardView.render(snapshot)
            Log.d(TAG, "Snapshot updated: status=${snapshot.event.status}, progress=${snapshot.overallProgress}")
        }
    }

    /**
     * 停止所有预准备（一键关停）
     */
    fun stopAllPrep() {
        mainHandler.post {
            val snapshot = activeSnapshot ?: return@post
            val stoppedSnapshot = snapshot.copy(
                event = snapshot.event.copy(status = PrepStatus.SKIPPED),
                credentialSteps = snapshot.credentialSteps.map { step ->
                    if (!step.isDone) step.copy(status = CredentialStepStatus.SKIPPED, progress = 0)
                    else step
                },
                stopReason = "用户手动关停"
            )
            activeSnapshot = stoppedSnapshot
            cardView.render(stoppedSnapshot)
            Log.i(TAG, "Prep stopped by user: eventId=${snapshot.event.eventId}")

            // 短暂延迟后隐藏卡片
            mainHandler.postDelayed({
                hideCard()
                stopCountdown()
            }, 2000)
        }
    }

    /**
     * 完成预准备（所有步骤就绪）
     */
    fun markReady() {
        mainHandler.post {
            val snapshot = activeSnapshot ?: return@post
            val readySnapshot = snapshot.copy(
                event = snapshot.event.copy(status = PrepStatus.READY),
                overallProgress = 100
            )
            activeSnapshot = readySnapshot
            cardView.render(readySnapshot)
            Log.i(TAG, "Prep ready: eventId=${snapshot.event.eventId}")

            // 就绪后保持 5 秒，然后自动隐藏
            mainHandler.postDelayed({
                hideCard()
                stopCountdown()
            }, 5000)
        }
    }

    /**
     * 标记预准备失败
     */
    fun markFailed(reason: String) {
        mainHandler.post {
            val snapshot = activeSnapshot ?: return@post
            val failedSnapshot = snapshot.copy(
                event = snapshot.event.copy(status = PrepStatus.FAILED),
                stopReason = reason
            )
            activeSnapshot = failedSnapshot
            cardView.render(failedSnapshot)
            Log.w(TAG, "Prep failed: eventId=${snapshot.event.eventId}, reason=$reason")
        }
    }

    /**
     * 更新凭据注入步骤
     */
    fun updateCredentialStep(stepId: String, progress: Int, status: CredentialStepStatus? = null) {
        mainHandler.post {
            val snapshot = activeSnapshot ?: return@post
            val updatedSteps = snapshot.credentialSteps.map { step ->
                if (step.stepId == stepId) {
                    step.copy(
                        progress = progress.coerceIn(0, 100),
                        status = status ?: step.status
                    )
                } else step
            }
            val newSnapshot = snapshot.copy(
                credentialSteps = updatedSteps,
                overallProgress = computeOverallProgress(updatedSteps, snapshot.creativeTasks)
            )
            activeSnapshot = newSnapshot
            cardView.render(newSnapshot)
        }
    }

    /**
     * 更新创作任务进度
     */
    fun updateCreativeTask(taskId: String, progress: Int, isCompleted: Boolean? = null) {
        mainHandler.post {
            val snapshot = activeSnapshot ?: return@post
            val updatedTasks = snapshot.creativeTasks.map { task ->
                if (task.taskId == taskId) {
                    task.copy(
                        progress = progress.coerceIn(0, 100),
                        isCompleted = isCompleted ?: (progress >= 100)
                    )
                } else task
            }
            val newSnapshot = snapshot.copy(
                creativeTasks = updatedTasks,
                overallProgress = computeOverallProgress(snapshot.credentialSteps, updatedTasks)
            )
            activeSnapshot = newSnapshot
            cardView.render(newSnapshot)
        }
    }

    private fun computeOverallProgress(
        credentialSteps: List<CredentialInjectionStep>,
        creativeTasks: List<CreativeTask>
    ): Int {
        val weights = mutableListOf<Int>()
        val values = mutableListOf<Int>()

        // 凭据注入：权重 60%
        if (credentialSteps.isNotEmpty()) {
            weights.add(60)
            val avgProgress = credentialSteps.map {
                when {
                    it.isFailed -> 0
                    it.isDone -> 100
                    else -> it.progress
                }
            }.average().toInt()
            values.add(avgProgress)
        }

        // 创作任务：权重 40%
        if (creativeTasks.isNotEmpty()) {
            weights.add(40)
            val avgProgress = creativeTasks.map {
                if (it.isCompleted) 100 else it.progress
            }.average().toInt()
            values.add(avgProgress)
        }

        if (weights.isEmpty()) return 0

        val totalWeight = weights.sum()
        val weightedSum = weights.zip(values).sumOf { (w, v) -> w * v }
        return (weightedSum.toFloat() / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun startCountdown() {
        stopCountdown()

        val runnable = object : Runnable {
            override fun run() {
                val snapshot = activeSnapshot ?: return
                val now = System.currentTimeMillis()
                val secondsRemaining = ((snapshot.event.startTimeMs - now) / 1000).coerceAtLeast(0)

                val updatedEvent = snapshot.event.copy(secondsUntilStart = secondsRemaining)
                val updatedSnapshot = snapshot.copy(event = updatedEvent)
                activeSnapshot = updatedSnapshot
                cardView.render(updatedSnapshot)

                // 日程已开始但状态仍为 PREPARING → 降级为 URGENT
                if (secondsRemaining <= 0 && snapshot.event.status != PrepStatus.READY) {
                    val urgentSnapshot = updatedSnapshot.copy(
                        event = updatedEvent.copy(
                            urgency = UrgencyLevel.URGENT,
                            status = if (updatedSnapshot.overallProgress >= 100) PrepStatus.READY else updatedSnapshot.event.status
                        )
                    )
                    activeSnapshot = urgentSnapshot
                    cardView.render(urgentSnapshot)
                }

                mainHandler.postDelayed(this, 1000)
            }
        }

        countdownRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun showCard() {
        try {
            // 先移除已存在的视图（防重复添加）
            try {
                windowManager.removeView(cardView)
            } catch (_: Exception) { /* 忽略：视图可能未添加 */ }

            windowManager.addView(cardView, windowParams)
            Log.d(TAG, "Card shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show card", e)
        }
    }

    private fun hideCard() {
        try {
            windowManager.removeView(cardView)
            activeSnapshot = null
            Log.d(TAG, "Card hidden")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hide card", e)
        }
    }

    /**
     * ===== 演示数据生成（无上游服务时用） =====
     */

    /**
     * 启动演示：模拟一个 10 分钟后开始的会议日程预准备
     */
    fun startDemo() {
        if (!demoMode) return

        val startTimeMs = System.currentTimeMillis() + 10 * 60 * 1000L

        val initialSnapshot = SchedulePrepSnapshot(
            event = SchedulePrepEvent(
                eventId = "demo-meeting-001",
                titleMasked = "产品评审会议",
                startTimeMs = startTimeMs,
                locationMasked = "会议室 A203",
                scheduleType = "meeting",
                urgency = UrgencyLevel.NORMAL,
                status = PrepStatus.PREPARING,
                secondsUntilStart = 10 * 60
            ),
            credentialSteps = listOf(
                CredentialInjectionStep(
                    stepId = "cred-calendar",
                    credentialTypeMasked = "日历凭据",
                    targetMasked = "Calendar App",
                    status = CredentialStepStatus.INJECTING,
                    progress = 30
                ),
                CredentialInjectionStep(
                    stepId = "cred-meeting",
                    credentialTypeMasked = "Zoom 会议凭据",
                    targetMasked = "Zoom App",
                    status = CredentialStepStatus.PENDING,
                    progress = 0
                ),
                CredentialInjectionStep(
                    stepId = "cred-wifi",
                    credentialTypeMasked = "WiFi 凭据",
                    targetMasked = "系统设置",
                    status = CredentialStepStatus.COMPLETED,
                    progress = 100
                )
            ),
            creativeTasks = listOf(
                CreativeTask(
                    taskId = "task-agenda",
                    title = "准备会议议程",
                    descriptionMasked = "基于最近 3 天讨论自动生成",
                    progress = 25,
                    isCompleted = false,
                    actionCardId = "action-demo-001"
                ),
                CreativeTask(
                    taskId = "task-summary",
                    title = "整理上次会议纪要",
                    descriptionMasked = "摘要 + 待办项",
                    progress = 0,
                    isCompleted = false,
                    actionCardId = null
                )
            ),
            overallProgress = 30,
            canStop = true,
            stopReason = null
        )

        startPrep(initialSnapshot)
        runDemoAutomation()
    }

    /**
     * 演示自动化：逐步推进状态到 READY
     */
    private fun runDemoAutomation() {
        // 1 秒后：日历凭据注入到 60%
        mainHandler.postDelayed({
            updateCredentialStep("cred-calendar", 60)
        }, 1500)

        // 3 秒后：日历凭据完成，Zoom 开始注入
        mainHandler.postDelayed({
            updateCredentialStep("cred-calendar", 100, CredentialStepStatus.COMPLETED)
            updateCredentialStep("cred-meeting", 20, CredentialStepStatus.INJECTING)
            updateCreativeTask("task-agenda", 50)
        }, 3000)

        // 5 秒后：Zoom 注入到 70%，议程完成
        mainHandler.postDelayed({
            updateCredentialStep("cred-meeting", 70)
            updateCreativeTask("task-agenda", 100, true)
        }, 5000)

        // 7 秒后：Zoom 完成，纪要开始
        mainHandler.postDelayed({
            updateCredentialStep("cred-meeting", 100, CredentialStepStatus.COMPLETED)
            updateCreativeTask("task-summary", 40)
        }, 7000)

        // 10 秒后：纪要完成，整体就绪
        mainHandler.postDelayed({
            updateCreativeTask("task-summary", 100, true)
            markReady()
        }, 10000)
    }

    /**
     * 清理资源
     */
    fun destroy() {
        stopCountdown()
        hideCard()
    }
}

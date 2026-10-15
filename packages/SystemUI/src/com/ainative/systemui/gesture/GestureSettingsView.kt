package com.ainative.systemui.gesture

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * D9.2 姿态交互设置与授权 - 设置视图
 *
 * 核心功能：
 * 1. 姿态交互总开关（一键全停）
 * 2. 逐项 opt-in 开关（摇一摇/翻面备忘）
 * 3. 录音授权管理（逐项授权/撤销）
 * 4. 敏感场景禁用配置
 * 5. 误触发统计透明展示
 *
 * 安全约束：
 * - 默认全部关闭（opt-in）
 * - 录音授权独立确认
 * - 敏感场景自动禁用
 * - 误触发率可见（透明化）
 *
 * 依赖：
 * - D9.1 GesturePresentationController
 * - A12.1 MotionHubService
 */
class GestureSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    // UI 组件
    private val rootContainer: LinearLayout
    private val masterSwitchContainer: LinearLayout
    private val masterSwitchToggle: Switch
    private val masterSwitchStatus: TextView
    
    private val featureListContainer: LinearLayout
    private val recordingPermissionContainer: LinearLayout
    private val statsContainer: LinearLayout
    private val sensitiveScenarioContainer: LinearLayout

    // 当前设置快照
    private var currentSnapshot: GestureSettingsSnapshot? = null

    // 回调接口
    var onMasterSwitchChanged: ((Boolean) -> Unit)? = null
    var onFeatureToggled: ((GestureType, Boolean) -> Unit)? = null
    var onRecordingPermissionRequest: (() -> Unit)? = null
    var onRecordingPermissionRevoke: (() -> Unit)? = null
    var onSensitiveScenarioChanged: ((SensitiveScenario, Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "GestureSettingsView"
        
        private const val COLOR_PRIMARY = 0xFF2196F3.toInt()      // 蓝色
        private const val COLOR_SUCCESS = 0xFF4CAF50.toInt()      // 绿色
        private const val COLOR_WARNING = 0xFFFF9800.toInt()      // 橙色
        private const val COLOR_DANGER = 0xFFFF5252.toInt()       // 红色
    }

    init {
        // 根容器
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        // 总开关区域
        masterSwitchContainer = createMasterSwitchSection()
        rootContainer.addView(masterSwitchContainer)

        // 分隔线
        rootContainer.addView(createDivider())

        // 功能列表标题
        rootContainer.addView(createSectionTitle("姿态功能设置"))
        
        featureListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootContainer.addView(featureListContainer)

        // 分隔线
        rootContainer.addView(createDivider())

        // 录音授权区域
        rootContainer.addView(createSectionTitle("录音授权"))
        
        recordingPermissionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootContainer.addView(recordingPermissionContainer)

        // 分隔线
        rootContainer.addView(createDivider())

        // 敏感场景区域
        rootContainer.addView(createSectionTitle("敏感场景禁用"))
        
        sensitiveScenarioContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootContainer.addView(sensitiveScenarioContainer)

        // 分隔线
        rootContainer.addView(createDivider())

        // 误触发统计区域
        rootContainer.addView(createSectionTitle("误触发统计"))
        
        statsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootContainer.addView(statsContainer)

        addView(rootContainer)
    }

    /**
     * 创建总开关区域
     */
    private fun createMasterSwitchSection(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // 标题行（总开关 + Toggle）
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "姿态交互总开关"
            textSize = 18f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleRow.addView(titleText)

        masterSwitchToggle = Switch(context).apply {
            setOnCheckedChangeListener { _, isChecked ->
                onMasterSwitchChanged?.invoke(isChecked)
            }
        }
        titleRow.addView(masterSwitchToggle)

        container.addView(titleRow)

        // 状态说明
        masterSwitchStatus = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, paddingPx, 0, 0)
        }
        container.addView(masterSwitchStatus)

        return container
    }

    /**
     * 渲染设置快照
     */
    fun render(snapshot: GestureSettingsSnapshot) {
        currentSnapshot = snapshot

        // 更新总开关
        masterSwitchToggle.isChecked = snapshot.masterSwitch.enabled
        masterSwitchToggle.isEnabled = snapshot.masterSwitch.canToggle
        masterSwitchStatus.text = when {
            !snapshot.masterSwitch.canToggle -> "已禁用：${snapshot.masterSwitch.disabledReason}"
            snapshot.masterSwitch.enabled -> "已启用　所有姿态交互功能可用"
            else -> "已关闭　姿态交互已全部禁用"
        }
        masterSwitchStatus.setTextColor(
            if (snapshot.masterSwitch.enabled) COLOR_SUCCESS else 0xFF666666.toInt()
        )

        // 渲染功能列表
        renderFeatureList(snapshot.featureConfigs, snapshot.masterSwitch.enabled)

        // 渲染录音授权
        renderRecordingPermission(snapshot.recordingPermission)

        // 渲染敏感场景
        renderSensitiveScenarios(snapshot.sensitiveScenarios)

        // 渲染误触发统计
        renderStats(snapshot.falseActivationStats)
    }

    /**
     * 渲染功能列表
     */
    private fun renderFeatureList(features: List<GestureFeatureConfig>, masterEnabled: Boolean) {
        featureListContainer.removeAllViews()

        for (feature in features) {
            featureListContainer.addView(createFeatureCard(feature, masterEnabled))
        }
    }

    /**
     * 创建功能卡片
     */
    private fun createFeatureCard(feature: GestureFeatureConfig, masterEnabled: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(Color.WHITE)
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, marginPx)
            }
        }

        // 标题行（功能名 + Toggle）
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameText = TextView(context).apply {
            text = feature.gestureType.displayName
            textSize = 16f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleRow.addView(nameText)

        // 需要录音权限徽章
        if (feature.requiresRecordingPermission) {
            val permissionBadge = TextView(context).apply {
                text = "需录音权限"
                textSize = 10f
                setTextColor(Color.WHITE)
                val paddingPx = (4 * resources.displayMetrics.density).toInt()
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
                setBackgroundColor(COLOR_WARNING)
                val marginPx = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, marginPx, 0)
                }
            }
            titleRow.addView(permissionBadge)
        }

        val toggle = Switch(context).apply {
            isChecked = feature.enabled
            isEnabled = masterEnabled  // 总开关关闭时不可切换
            setOnCheckedChangeListener { _, isChecked ->
                onFeatureToggled?.invoke(feature.gestureType, isChecked)
            }
        }
        titleRow.addView(toggle)

        card.addView(titleRow)

        // 描述
        val descText = TextView(context).apply {
            text = feature.description
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            val paddingPx = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, paddingPx, 0, 0)
        }
        card.addView(descText)

        return card
    }

    /**
     * 渲染录音授权区域
     */
    private fun renderRecordingPermission(permission: RecordingPermissionState) {
        recordingPermissionContainer.removeAllViews()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(if (permission.granted) 0xFFE8F5E9.toInt() else 0xFFFFF3E0.toInt())
        }

        // 状态行
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusIcon = TextView(context).apply {
            text = if (permission.granted) "✓" else "⚠"
            textSize = 24f
            setTextColor(if (permission.granted) COLOR_SUCCESS else COLOR_WARNING)
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, marginPx, 0)
            }
        }
        statusRow.addView(statusIcon)

        val statusText = TextView(context).apply {
            text = if (permission.granted) "录音权限已授予" else "录音权限未授予"
            textSize = 16f
            setTextColor(if (permission.granted) COLOR_SUCCESS else COLOR_WARNING)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        statusRow.addView(statusText)

        card.addView(statusRow)

        // 授权时间
        if (permission.granted) {
            val timeText = TextView(context).apply {
                text = "授予时间：${formatTimestamp(permission.grantedAt)}"
                textSize = 12f
                setTextColor(0xFF666666.toInt())
                val paddingPx = (4 * resources.displayMetrics.density).toInt()
                setPadding(0, paddingPx, 0, 0)
            }
            card.addView(timeText)
        }

        // 操作按钮
        val button = Button(context).apply {
            text = if (permission.granted) "撤销授权" else "授予录音权限"
            setBackgroundColor(if (permission.granted) COLOR_DANGER else COLOR_PRIMARY)
            setTextColor(Color.WHITE)
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, 0)
            }
            isEnabled = !permission.granted || permission.canRevoke
            setOnClickListener {
                if (permission.granted) {
                    showRevokeConfirmDialog()
                } else {
                    onRecordingPermissionRequest?.invoke()
                }
            }
        }
        card.addView(button)

        recordingPermissionContainer.addView(card)
    }

    /**
     * 渲染敏感场景列表
     */
    private fun renderSensitiveScenarios(scenarios: List<SensitiveScenarioConfig>) {
        sensitiveScenarioContainer.removeAllViews()

        for (scenario in scenarios) {
            sensitiveScenarioContainer.addView(createSensitiveScenarioCard(scenario))
        }
    }

    /**
     * 创建敏感场景卡片
     */
    private fun createSensitiveScenarioCard(config: SensitiveScenarioConfig): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(Color.WHITE)
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, marginPx)
            }
        }

        // 标题行
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameText = TextView(context).apply {
            text = config.scenario.displayName
            textSize = 16f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleRow.addView(nameText)

        val disableToggle = Switch(context).apply {
            isChecked = config.disableGestures
            setOnCheckedChangeListener { _, isChecked ->
                onSensitiveScenarioChanged?.invoke(config.scenario, isChecked)
            }
        }
        titleRow.addView(disableToggle)

        card.addView(titleRow)

        // 描述
        val descText = TextView(context).apply {
            text = if (config.disableGestures) "该场景下姿态交互已禁用" else "该场景下姿态交互可用"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            val paddingPx = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, paddingPx, 0, 0)
        }
        card.addView(descText)

        return card
    }

    /**
     * 渲染误触发统计
     */
    private fun renderStats(stats: List<FalseActivationStats>) {
        statsContainer.removeAllViews()

        if (stats.isEmpty()) {
            statsContainer.addView(createEmptyText("暂无统计数据"))
            return
        }

        for (stat in stats) {
            statsContainer.addView(createStatsCard(stat))
        }
    }

    /**
     * 创建统计卡片
     */
    private fun createStatsCard(stats: FalseActivationStats): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(Color.WHITE)
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, marginPx)
            }
        }

        // 功能名
        val nameText = TextView(context).apply {
            text = stats.gestureType.displayName
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        card.addView(nameText)

        // 统计数据
        val statsGrid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, 0)
            }
        }

        statsGrid.addView(createStatItem("总激活", stats.totalActivations.toString()))
        statsGrid.addView(createStatItem("用户取消", stats.userCancelledCount.toString()))
        statsGrid.addView(createStatItem("误触发率", "${(stats.falsePositiveRate * 100).toInt()}%"))

        card.addView(statsGrid)

        // 最后激活时间
        val lastActiveText = TextView(context).apply {
            text = "最后激活：${formatTimestamp(stats.lastActivationTime)}"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            val paddingPx = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, paddingPx, 0, 0)
        }
        card.addView(lastActiveText)

        return card
    }

    /**
     * 创建统计项
     */
    private fun createStatItem(label: String, value: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

            addView(TextView(context).apply {
                text = value
                textSize = 20f
                setTextColor(COLOR_PRIMARY)
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.CENTER
            })
        }
    }

    /**
     * 创建节标题
     */
    private fun createSectionTitle(title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(Color.BLACK)
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, paddingPx, 0, (8 * resources.displayMetrics.density).toInt())
        }
    }

    /**
     * 创建分隔线
     */
    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(0xFFDDDDDD.toInt())
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            ).apply {
                setMargins(0, marginPx, 0, marginPx)
            }
        }
    }

    /**
     * 创建空状态文本
     */
    private fun createEmptyText(message: String): TextView {
        return TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
    }

    /**
     * 显示撤销录音权限确认对话框
     */
    private fun showRevokeConfirmDialog() {
        android.app.AlertDialog.Builder(context)
            .setTitle("撤销录音权限")
            .setMessage("撤销后，所有需要录音的姿态功能（如翻面备忘）将无法使用。是否继续？")
            .setPositiveButton("确认撤销") { _, _ ->
                onRecordingPermissionRevoke?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "从未"
        val date = Date(timestamp)
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }
}

package com.genton.systemui.policy

import android.content.Context
import android.graphics.Color
import android.os.RemoteException
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.genton.policy.IPolicyEngine
import com.genton.policy.PolicyRuleView
import com.genton.policy.PolicyStats
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 策略管理视图
 * 
 * 任务：D4.3 策略视图
 * WBS 引用：Agent 授予/拒绝历史、USER_ALLOW_TASK 清单、一键停止授权
 * 
 * 核心功能：
 * 1. 展示 Agent 被授予/拒绝的策略规则列表
 * 2. USER_ALLOW_TASK 授权清单（用户记忆化授权）
 * 3. 规则命中统计（命中次数、最后命中时间）
 * 4. 一键停止授权（撤销 Agent 所有令牌）
 * 5. 策略引擎统计面板（总裁决/活跃令牌/策略版本）
 * 
 * 依赖：
 * - A7.1 策略引擎服务端（IPolicyEngine）
 * - D10 策略引擎接口草案
 * 
 * 安全约束：
 * - 只读视图，不允许修改策略内容
 * - 一键停止需要用户二次确认
 * - 策略版本/基线标记清晰可见
 */
class PolicyManagementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    
    private var policyEngine: IPolicyEngine? = null
    private var currentAgentId: String? = null
    
    // UI 组件
    private val rootContainer: LinearLayout
    private val agentInfoContainer: LinearLayout
    private val agentNameText: TextView
    private val agentStatusText: TextView
    private val statsContainer: LinearLayout
    private val ruleListContainer: LinearLayout
    private val revokeButton: Button
    private val refreshButton: Button
    
    companion object {
        private const val TAG = "PolicyManagementView"
        
        // 效果类型颜色
        private const val COLOR_ALLOW = 0xFF4CAF50.toInt()      // 绿色
        private const val COLOR_DENY = 0xFFFF5252.toInt()       // 红色
        private const val COLOR_CONFIRM = 0xFFFF9800.toInt()    // 橙色
        private const val COLOR_BASELINE = 0xFF2196F3.toInt()   // 蓝色
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
        
        // Agent 信息区域
        agentInfoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        
        agentNameText = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        agentInfoContainer.addView(agentNameText)
        
        agentStatusText = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        agentInfoContainer.addView(agentStatusText)
        
        rootContainer.addView(agentInfoContainer)
        
        // 统计面板
        statsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, marginPx)
            }
        }
        rootContainer.addView(statsContainer)
        
        // 规则列表标题
        val ruleListTitle = TextView(context).apply {
            text = "策略规则清单"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        }
        rootContainer.addView(ruleListTitle)
        
        // 规则列表容器
        ruleListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootContainer.addView(ruleListContainer)
        
        // 按钮容器
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, 0)
            }
        }
        
        // 刷新按钮
        refreshButton = Button(context).apply {
            text = "刷新"
            setBackgroundColor(0xFF2196F3.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                val marginPx = (8 * resources.displayMetrics.density).toInt()
                setMargins(0, 0, marginPx, 0)
            }
            setOnClickListener {
                refreshData()
            }
        }
        buttonContainer.addView(refreshButton)
        
        // 一键停止授权按钮
        revokeButton = Button(context).apply {
            text = "一键停止授权"
            setBackgroundColor(0xFFFF5252.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                showRevokeConfirmDialog()
            }
        }
        buttonContainer.addView(revokeButton)
        
        rootContainer.addView(buttonContainer)
        
        addView(rootContainer)
    }
    
    /**
     * 设置策略引擎引用
     */
    fun setPolicyEngine(engine: IPolicyEngine?) {
        this.policyEngine = engine
        Log.i(TAG, "PolicyEngine set: ${engine != null}")
    }
    
    /**
     * 加载 Agent 策略数据
     */
    fun loadAgentPolicy(agentId: String, agentName: String) {
        this.currentAgentId = agentId
        
        agentNameText.text = agentName
        agentStatusText.text = "Agent ID: $agentId"
        
        refreshData()
    }
    
    /**
     * 刷新数据
     */
    private fun refreshData() {
        val agentId = currentAgentId
        val engine = policyEngine
        
        if (agentId == null || engine == null) {
            showError("数据加载失败：引擎或 Agent 未设置")
            return
        }
        
        try {
            // 获取策略统计
            val stats = engine.stats
            displayStats(stats)
            
            // 获取 Agent 适用规则
            val rules = engine.listApplicableRules(agentId)
            displayRules(rules)
            
            Log.i(TAG, "Loaded ${rules.size} rules for agent: $agentId")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to load policy data", e)
            showError("加载策略数据失败：${e.message}")
        }
    }
    
    /**
     * 显示统计数据
     */
    private fun displayStats(stats: PolicyStats?) {
        statsContainer.removeAllViews()
        
        if (stats == null) {
            statsContainer.addView(createErrorText("统计数据不可用"))
            return
        }
        
        val statsTitle = TextView(context).apply {
            text = "策略引擎统计"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        statsContainer.addView(statsTitle)
        
        // 统计卡片
        val statsCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        
        // 总裁决次数
        statsCard.addView(createStatRow("总裁决次数", stats.totalEvaluations.toString()))
        statsCard.addView(createStatRow("　批准", "${stats.grantCount} (${percentage(stats.grantCount, stats.totalEvaluations)}%)"))
        statsCard.addView(createStatRow("　拒绝", "${stats.denyCount} (${percentage(stats.denyCount, stats.totalEvaluations)}%)"))
        statsCard.addView(createStatRow("　需确认", "${stats.confirmationCount} (${percentage(stats.confirmationCount, stats.totalEvaluations)}%)"))
        
        // 活跃状态
        statsCard.addView(createDivider())
        statsCard.addView(createStatRow("挂起的确认请求", stats.pendingConfirmations.toString()))
        statsCard.addView(createStatRow("活跃令牌数", stats.activeTokens.toString()))
        
        // 策略版本
        statsCard.addView(createDivider())
        statsCard.addView(createStatRow("策略版本", stats.policyVersion))
        statsCard.addView(createStatRow("加载时间", formatTimestamp(stats.policyLoadedAtNanos)))
        
        statsContainer.addView(statsCard)
    }
    
    /**
     * 显示规则列表
     */
    private fun displayRules(rules: Array<PolicyRuleView>?) {
        ruleListContainer.removeAllViews()
        
        if (rules == null || rules.isEmpty()) {
            ruleListContainer.addView(createErrorText("暂无适用规则"))
            return
        }
        
        // 按效果分组统计
        val allowCount = rules.count { it.effect == "allow" }
        val denyCount = rules.count { it.effect == "deny" }
        val confirmCount = rules.count { it.effect == "confirmation" }
        val baselineCount = rules.count { it.baseline }
        
        // 分组统计卡片
        val summaryCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setBackgroundColor(0xFFF5F5F5.toInt())
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, marginPx)
            }
        }
        
        summaryCard.addView(createSummaryBadge("允许", allowCount, COLOR_ALLOW))
        summaryCard.addView(createSummaryBadge("拒绝", denyCount, COLOR_DENY))
        summaryCard.addView(createSummaryBadge("需确认", confirmCount, COLOR_CONFIRM))
        summaryCard.addView(createSummaryBadge("基线", baselineCount, COLOR_BASELINE))
        
        ruleListContainer.addView(summaryCard)
        
        // 规则卡片
        for (rule in rules) {
            ruleListContainer.addView(createRuleCard(rule))
        }
    }
    
    /**
     * 创建规则卡片
     */
    private fun createRuleCard(rule: PolicyRuleView): View {
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
        
        // 标题行（规则 ID + 效果标签）
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        val ruleIdText = TextView(context).apply {
            text = rule.ruleId
            textSize = 16f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleRow.addView(ruleIdText)
        
        // 效果标签
        val effectBadge = TextView(context).apply {
            text = when (rule.effect) {
                "allow" -> "允许"
                "deny" -> "拒绝"
                "confirmation" -> "需确认"
                else -> rule.effect
            }
            textSize = 12f
            setTextColor(Color.WHITE)
            val paddingPx = (6 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
            setBackgroundColor(when (rule.effect) {
                "allow" -> COLOR_ALLOW
                "deny" -> COLOR_DENY
                "confirmation" -> COLOR_CONFIRM
                else -> Color.GRAY
            })
        }
        titleRow.addView(effectBadge)
        
        // 基线标记
        if (rule.baseline) {
            val baselineBadge = TextView(context).apply {
                text = "基线"
                textSize = 12f
                setTextColor(Color.WHITE)
                val paddingPx = (6 * resources.displayMetrics.density).toInt()
                val marginPx = (4 * resources.displayMetrics.density).toInt()
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
                setBackgroundColor(COLOR_BASELINE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(marginPx, 0, 0, 0)
                }
            }
            titleRow.addView(baselineBadge)
        }
        
        card.addView(titleRow)
        
        // 能力名
        val capabilityText = TextView(context).apply {
            text = "能力：${rule.capability}"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        card.addView(capabilityText)
        
        // 命中统计
        val hitStatsText = TextView(context).apply {
            text = "命中 ${rule.hitCount} 次　最后命中：${formatTimestamp(rule.lastHitAtRealtimeNanos)}"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        card.addView(hitStatsText)
        
        return card
    }
    
    /**
     * 创建统计行
     */
    private fun createStatRow(label: String, value: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val paddingPx = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, paddingPx, 0, paddingPx)
            
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(Color.BLACK)
                gravity = Gravity.END
            })
        }
    }
    
    /**
     * 创建分组统计徽章
     */
    private fun createSummaryBadge(label: String, count: Int, color: Int): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            
            addView(TextView(context).apply {
                text = count.toString()
                textSize = 24f
                setTextColor(color)
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
     * 创建分隔线
     */
    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(0xFFDDDDDD.toInt())
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            ).apply {
                setMargins(0, marginPx, 0, marginPx)
            }
        }
    }
    
    /**
     * 创建错误提示文本
     */
    private fun createErrorText(message: String): TextView {
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
     * 显示错误提示
     */
    private fun showError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示撤销确认对话框
     */
    private fun showRevokeConfirmDialog() {
        val agentId = currentAgentId ?: return
        
        android.app.AlertDialog.Builder(context)
            .setTitle("确认操作")
            .setMessage("将撤销该 Agent 的所有授权令牌，当前正在执行的任务将被中断。是否继续？")
            .setPositiveButton("确认撤销") { _, _ ->
                revokeAllTokens(agentId)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 撤销所有令牌
     */
    private fun revokeAllTokens(agentId: String) {
        val engine = policyEngine
        if (engine == null) {
            showError("策略引擎不可用")
            return
        }
        
        try {
            val status = engine.revokeTokens(agentId, null)
            if (status == 0) { // STATUS_OK
                Toast.makeText(context, "授权已撤销", Toast.LENGTH_SHORT).show()
                refreshData()
            } else {
                showError("撤销失败：状态码 $status")
            }
            
            Log.i(TAG, "Revoked all tokens for agent: $agentId, status: $status")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to revoke tokens", e)
            showError("撤销失败：${e.message}")
        }
    }
    
    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(nanos: Long): String {
        if (nanos == 0L) return "从未"
        
        val millis = TimeUnit.NANOSECONDS.toMillis(nanos)
        val date = Date(System.currentTimeMillis() - (System.nanoTime() - nanos) / 1_000_000)
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }
    
    /**
     * 计算百分比
     */
    private fun percentage(count: Long, total: Long): Int {
        if (total == 0L) return 0
        return ((count * 100) / total).toInt()
    }
}

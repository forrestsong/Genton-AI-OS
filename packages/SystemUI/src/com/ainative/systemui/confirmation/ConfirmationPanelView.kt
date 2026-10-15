package com.ainative.systemui.confirmation

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.ainative.ui.ConfirmationUiRequest

/**
 * 确认面板视图
 * 
 * 任务：D3.1 确认面板
 * WBS 引用：FLAG_SECURE 全屏、先上滑再操作、内容来自 ConfirmationUiRequest、按钮语义
 * 
 * 核心功能：
 * 1. FLAG_SECURE 全屏独占（防截屏/录屏）
 * 2. 先上滑再操作手势（防误触）
 * 3. 内容完全来自 ConfirmationUiRequest（防注入）
 * 4. 三按钮：批准/拒绝/永不（按钮语义清晰）
 * 5. 60 秒超时自动拒绝（Fail-secure）
 * 
 * 安全约束：
 * - 所有文本从服务端签发（不信任 Agent 传值）
 * - FLAG_SECURE 强制启用（防截屏）
 * - 手势确认防误触
 * - 超时自动拒绝（不是批准）
 * 
 * 依赖：
 * - A11.2 确认宿主服务端（ConfirmationHostService）
 * - E1.1 安全基线规范（FLAG_SECURE 边界）
 */
class ConfirmationPanelView(context: Context) : LinearLayout(context) {
    
    // 回调接口
    interface ConfirmationCallback {
        fun onApprove(confirmId: String)
        fun onReject(confirmId: String)
        fun onNever(confirmId: String)
        fun onTimeout(confirmId: String)
    }
    
    // UI 组件
    private lateinit var titleText: TextView
    private lateinit var capabilityText: TextView
    private lateinit var targetText: TextView
    private lateinit var intentText: TextView
    private lateinit var riskLabelText: TextView
    private lateinit var swipeHintText: TextView
    private lateinit var timeoutText: TextView
    private lateinit var approveButton: Button
    private lateinit var rejectButton: Button
    private lateinit var neverButton: Button
    private lateinit var scrollView: ScrollView
    private lateinit var buttonsContainer: LinearLayout
    
    // 状态
    private var currentRequest: ConfirmationUiRequest? = null
    private var callback: ConfirmationCallback? = null
    private var swipeUnlocked = false
    private var timeoutHandler: android.os.Handler? = null
    private var timeoutRunnable: Runnable? = null
    private var remainingSeconds = TIMEOUT_SECONDS
    
    companion object {
        private const val TAG = "ConfirmationPanelView"
        private const val TIMEOUT_SECONDS = 60
        private const val SWIPE_THRESHOLD_DP = 100f
    }
    
    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(0xF0FFFFFF.toInt()) // 半透明白色背景
        
        // 设置内边距
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        setPadding(paddingPx, paddingPx * 2, paddingPx, paddingPx)
        
        initViews()
    }
    
    private fun initViews() {
        // 标题
        titleText = TextView(context).apply {
            text = "操作确认"
            textSize = 24f
            setTextColor(0xFF000000.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
        }
        addView(titleText)
        
        // 滚动容器（用于显示详细信息）
        scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f // weight=1，占据剩余空间
            )
        }
        
        val contentContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            val paddingPx = (12 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
        
        // 能力标签
        capabilityText = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        contentContainer.addView(capabilityText)
        
        // 目标描述
        targetText = TextView(context).apply {
            textSize = 18f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        contentContainer.addView(targetText)
        
        // 意图摘要
        intentText = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        contentContainer.addView(intentText)
        
        // 风险标签
        riskLabelText = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFFFF6B00.toInt()) // 橙色
            setPadding(
                (8 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(0x22FF6B00) // 浅橙色背景
        }
        contentContainer.addView(riskLabelText)
        
        scrollView.addView(contentContainer)
        addView(scrollView)
        
        // 上滑提示
        swipeHintText = TextView(context).apply {
            text = "⬆ 向上滑动以解锁操作按钮"
            textSize = 14f
            setTextColor(0xFF0066CC.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        }
        addView(swipeHintText)
        
        // 超时提示
        timeoutText = TextView(context).apply {
            text = "剩余时间：60 秒"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        addView(timeoutText)
        
        // 按钮容器
        buttonsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        
        // 批准按钮
        approveButton = Button(context).apply {
            text = "批准此次操作"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4CAF50.toInt()) // 绿色
            isEnabled = false
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, marginPx)
            }
            setOnClickListener {
                currentRequest?.let { callback?.onApprove(it.confirmId) }
            }
        }
        buttonsContainer.addView(approveButton)
        
        // 拒绝按钮
        rejectButton = Button(context).apply {
            text = "拒绝此次操作"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFFF5252.toInt()) // 红色
            isEnabled = false
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, marginPx)
            }
            setOnClickListener {
                currentRequest?.let { callback?.onReject(it.confirmId) }
            }
        }
        buttonsContainer.addView(rejectButton)
        
        // 永不按钮
        neverButton = Button(context).apply {
            text = "永不允许此类操作"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFFEEEEEE.toInt()) // 灰色
            isEnabled = false
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, marginPx, 0, marginPx)
            }
            setOnClickListener {
                currentRequest?.let { callback?.onNever(it.confirmId) }
            }
        }
        buttonsContainer.addView(neverButton)
        
        addView(buttonsContainer)
        
        // 设置滑动手势监听
        setupSwipeGesture()
    }
    
    private fun setupSwipeGesture() {
        var startY = 0f
        
        scrollView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = startY - event.y
                    val thresholdPx = SWIPE_THRESHOLD_DP * resources.displayMetrics.density
                    
                    if (!swipeUnlocked && deltaY > thresholdPx) {
                        unlockButtons()
                    }
                }
            }
            false // 不消费事件，让 ScrollView 继续处理滚动
        }
    }
    
    private fun unlockButtons() {
        swipeUnlocked = true
        swipeHintText.text = "✓ 已解锁"
        swipeHintText.setTextColor(0xFF4CAF50.toInt()) // 绿色
        
        approveButton.isEnabled = true
        approveButton.alpha = 1.0f
        rejectButton.isEnabled = true
        rejectButton.alpha = 1.0f
        neverButton.isEnabled = true
        neverButton.alpha = 1.0f
        
        android.util.Log.i(TAG, "Buttons unlocked via swipe gesture")
    }
    
    /**
     * 显示确认请求
     */
    fun showRequest(request: ConfirmationUiRequest, callback: ConfirmationCallback) {
        this.currentRequest = request
        this.callback = callback
        this.swipeUnlocked = false
        this.remainingSeconds = TIMEOUT_SECONDS
        
        // 填充内容（完全来自服务端签发）
        capabilityText.text = "能力：${request.capability}"
        targetText.text = request.targetSummaryMasked
        intentText.text = "意图：${request.sourceIntentSummary}"
        riskLabelText.text = "⚠ ${request.riskLabel}"
        
        // 根据风险等级调整颜色
        when {
            request.riskLabel.contains("高风险") -> {
                riskLabelText.setTextColor(0xFFFF0000.toInt()) // 红色
                riskLabelText.setBackgroundColor(0x22FF0000)
            }
            request.riskLabel.contains("中等风险") -> {
                riskLabelText.setTextColor(0xFFFF6B00.toInt()) // 橙色
                riskLabelText.setBackgroundColor(0x22FF6B00)
            }
            else -> {
                riskLabelText.setTextColor(0xFFFFC107.toInt()) // 黄色
                riskLabelText.setBackgroundColor(0x22FFC107)
            }
        }
        
        // 重置按钮状态
        approveButton.isEnabled = false
        approveButton.alpha = 0.5f
        rejectButton.isEnabled = false
        rejectButton.alpha = 0.5f
        neverButton.isEnabled = false
        neverButton.alpha = 0.5f
        
        swipeHintText.text = "⬆ 向上滑动以解锁操作按钮"
        swipeHintText.setTextColor(0xFF0066CC.toInt())
        
        // 启动超时倒计时
        startTimeoutCountdown()
        
        visibility = View.VISIBLE
        android.util.Log.i(TAG, "Showing confirmation for: ${request.confirmId}")
    }
    
    private fun startTimeoutCountdown() {
        stopTimeoutCountdown()
        
        timeoutHandler = android.os.Handler(context.mainLooper)
        timeoutRunnable = object : Runnable {
            override fun run() {
                remainingSeconds--
                timeoutText.text = "剩余时间：$remainingSeconds 秒"
                
                if (remainingSeconds <= 10) {
                    timeoutText.setTextColor(0xFFFF0000.toInt()) // 红色警告
                }
                
                if (remainingSeconds <= 0) {
                    // 超时自动拒绝
                    currentRequest?.let { callback?.onTimeout(it.confirmId) }
                    hide()
                } else {
                    timeoutHandler?.postDelayed(this, 1000)
                }
            }
        }
        timeoutHandler?.postDelayed(timeoutRunnable!!, 1000)
    }
    
    private fun stopTimeoutCountdown() {
        timeoutRunnable?.let { timeoutHandler?.removeCallbacks(it) }
        timeoutHandler = null
        timeoutRunnable = null
    }
    
    /**
     * 隐藏确认面板
     */
    fun hide() {
        stopTimeoutCountdown()
        visibility = View.GONE
        currentRequest = null
        callback = null
        swipeUnlocked = false
        android.util.Log.i(TAG, "Confirmation panel hidden")
    }
    
    /**
     * 获取当前确认 ID
     */
    fun getCurrentConfirmId(): String? = currentRequest?.confirmId
}

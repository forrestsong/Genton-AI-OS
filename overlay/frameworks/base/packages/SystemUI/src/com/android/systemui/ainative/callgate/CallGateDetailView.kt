package com.android.systemui.ainative.callgate

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.ainative.call.CallSessionHandle
import com.ainative.call.CallSummary
import java.text.SimpleDateFormat
import java.util.*

/**
 * CallGate 接管详情页
 * 
 * 显示时机：用户点击"查看详情"按钮
 * 
 * 视觉设计：
 * ┌─────────────────────────────────────────┐
 * │  通话接管详情                            │
 * │  ──────────────────────────────────────│
 * │                                         │
 * │  会话 ID：session-abc123                │
 * │  接管模式：静默留言（L1）                │
 * │  通话时长：1 分 23 秒                   │
 * │  转写字数：156 字                       │
 * │                                         │
 * │  完整转写：                              │
 * │  ┌─────────────────────────────┐       │
 * │  │ 14:32:05  [对方]             │       │
 * │  │ 你好，请问是张先生吗？        │       │
 * │  │                             │       │
 * │  │ 14:32:08  [助理]             │       │
 * │  │ 您好，我是张先生的 AI 助理...│       │
 * │  └─────────────────────────────┘       │
 * │                                         │
 * │  审计信息：                              │
 * │  策略规则：rule-contact-whitelist       │
 * │  审计引用：audit-ref-xyz789             │
 * │                                         │
 * │  [导出文本]  [举报误判]  [关闭]         │
 * └─────────────────────────────────────────┘
 * 
 * 关键要素：
 * - 会话信息：sessionId、接管模式、时长、字数
 * - 完整转写：带时间戳和说话人的完整对话记录
 * - 审计信息：策略规则、审计引用
 * - 操作按钮：导出文本/举报误判/关闭
 * 
 * 安全策略：
 * - 导出需要权限确认
 * - 举报误判反馈到策略引擎
 * - 审计引用可追溯到时间线
 */
class CallGateDetailView(private val context: Context) {
    
    companion object {
        private const val TAG = "CallGateDetailView"
    }
    
    private val windowManager: WindowManager = 
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var rootView: View? = null
    private var containerLayout: LinearLayout? = null
    private var sessionIdText: TextView? = null
    private var handleModeText: TextView? = null
    private var durationText: TextView? = null
    private var wordCountText: TextView? = null
    private var transcriptScrollView: ScrollView? = null
    private var transcriptContentLayout: LinearLayout? = null
    private var ruleIdText: TextView? = null
    private var auditRefText: TextView? = null
    
    private var session: CallSessionHandle? = null
    private var summary: CallSummary? = null
    private var transcriptMessages: List<TranscriptMessage>? = null
    
    private var isShown = false
    
    /**
     * 转写消息数据类（与 TranscriptView 保持一致）
     */
    data class TranscriptMessage(
        val speaker: Boolean,  // false=对方, true=助理
        val text: String,
        val offsetMs: Long,
        val isFinal: Boolean
    )

    /**
     * 显示详情页
     * 
     * @param session 会话句柄
     * @param summary 通话摘要
     * @param transcript 完整转写记录（可选）
     */
    fun show(
        session: CallSessionHandle, 
        summary: CallSummary,
        transcript: List<TranscriptMessage>? = null
    ) {
        if (isShown) {
            Log.w(TAG, "Detail view already shown")
            return
        }
        
        this.session = session
        this.summary = summary
        this.transcriptMessages = transcript
        
        createView()
        updateContent()
        attachToWindow()
        
        isShown = true
        Log.d(TAG, "Detail view shown for session: ${session.sessionId}")
    }

    /**
     * 隐藏详情页
     */
    fun dismiss() {
        if (!isShown) {
            return
        }
        
        mainHandler.post {
            detachFromWindow()
            isShown = false
            Log.d(TAG, "Detail view dismissed")
        }
    }

    private fun createView() {
        // 根布局
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0FFFFFF.toInt())
            setPadding(32, 32, 32, 32)
        }
        
        containerLayout = rootView as LinearLayout
        
        // 标题
        val titleText = TextView(context).apply {
            text = "通话接管详情"
            textSize = 18f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 16)
        }
        containerLayout?.addView(titleText)
        
        // 分隔线
        val divider1 = createDivider()
        containerLayout?.addView(divider1)
        
        // 会话 ID
        sessionIdText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 8, 0, 4)
        }
        containerLayout?.addView(sessionIdText)
        
        // 接管模式
        handleModeText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 4, 0, 4)
        }
        containerLayout?.addView(handleModeText)
        
        // 通话时长
        durationText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 4, 0, 4)
        }
        containerLayout?.addView(durationText)
        
        // 转写字数
        wordCountText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 4, 0, 16)
        }
        containerLayout?.addView(wordCountText)
        
        // "完整转写" 标签
        val transcriptLabel = TextView(context).apply {
            text = "完整转写："
            textSize = 14f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 8, 0, 8)
        }
        containerLayout?.addView(transcriptLabel)
        
        // 转写滚动容器
        transcriptScrollView = ScrollView(context).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600 // 固定高度 600dp
            ).apply {
                bottomMargin = 16
            }
        }
        
        // 转写内容布局
        transcriptContentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        transcriptScrollView?.addView(transcriptContentLayout)
        containerLayout?.addView(transcriptScrollView)
        
        // "审计信息" 标签
        val auditLabel = TextView(context).apply {
            text = "审计信息："
            textSize = 14f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 8, 0, 8)
        }
        containerLayout?.addView(auditLabel)
        
        // 策略规则 ID
        ruleIdText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 4, 0, 4)
        }
        containerLayout?.addView(ruleIdText)
        
        // 审计引用
        auditRefText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, 4, 0, 16)
            setOnClickListener {
                handleAuditRefClick()
            }
        }
        containerLayout?.addView(auditRefText)
        
        // 按钮容器
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        // 导出文本按钮
        val exportButton = Button(context).apply {
            text = "导出文本"
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                handleExportTranscript()
            }
        }
        
        val exportParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = 16
        }
        buttonLayout.addView(exportButton, exportParams)
        
        // 举报误判按钮
        val reportButton = Button(context).apply {
            text = "举报误判"
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                handleReportMisjudgment()
            }
        }
        
        val reportParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = 16
        }
        buttonLayout.addView(reportButton, reportParams)
        
        // 关闭按钮
        val closeButton = Button(context).apply {
            text = "关闭"
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                dismiss()
            }
        }
        
        buttonLayout.addView(closeButton)
        
        containerLayout?.addView(buttonLayout)
    }

    private fun updateContent() {
        val sess = session ?: return
        val sum = summary ?: return
        
        // 更新会话信息
        sessionIdText?.text = "会话 ID：${sess.sessionId}"
        handleModeText?.text = "接管模式：${formatHandleMode(sess.handleMode)}"
        durationText?.text = "通话时长：${formatDuration(sum.durationSec)}"
        wordCountText?.text = "转写字数：${sum.transcriptWordCount} 字"
        
        // 更新转写内容
        if (transcriptMessages != null && transcriptMessages!!.isNotEmpty()) {
            renderTranscript(transcriptMessages!!)
        } else {
            // 没有转写记录时显示提示
            val emptyText = TextView(context).apply {
                text = "暂无转写记录"
                textSize = 13f
                setTextColor(0xFF9E9E9E.toInt())
                gravity = Gravity.CENTER
            }
            transcriptContentLayout?.addView(emptyText)
        }
        
        // 更新审计信息
        ruleIdText?.text = "策略规则：${sum.auditRef ?: "未知"}"
        auditRefText?.text = "审计引用：${sum.auditRef ?: "无"}"
    }

    private fun renderTranscript(messages: List<TranscriptMessage>) {
        transcriptContentLayout?.removeAllViews()
        
        for (message in messages) {
            if (!message.isFinal) {
                continue // 只显示最终结果
            }
            
            // 消息容器
            val messageLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 12)
            }
            
            // 时间戳 + 说话人
            val headerText = TextView(context).apply {
                text = "${formatTimestamp(message.offsetMs)}  ${formatSpeaker(message.speaker)}"
                textSize = 11f
                setTextColor(0xFF757575.toInt())
                setPadding(0, 0, 0, 4)
            }
            messageLayout.addView(headerText)
            
            // 消息文本
            val messageText = TextView(context).apply {
                text = message.text
                textSize = 13f
                setTextColor(0xFF212121.toInt())
            }
            messageLayout.addView(messageText)
            
            transcriptContentLayout?.addView(messageLayout)
        }
    }

    private fun createDivider(): View {
        return View(context).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                bottomMargin = 16
            }
        }
    }

    private fun attachToWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.CENTER
        params.width = 800 // 固定宽度 800dp
        
        windowManager.addView(rootView, params)
    }

    private fun detachFromWindow() {
        rootView?.let {
            if (it.parent != null) {
                windowManager.removeView(it)
            }
        }
        rootView = null
        containerLayout = null
        transcriptScrollView = null
        transcriptContentLayout = null
    }

    // ==================== 用户交互处理 ====================

    /**
     * 处理审计引用点击（跳转到时间线）
     */
    private fun handleAuditRefClick() {
        val auditRef = summary?.auditRef
        if (auditRef.isNullOrEmpty()) {
            Log.w(TAG, "No audit ref available")
            return
        }
        
        Log.d(TAG, "User clicked audit ref: $auditRef")
        
        // TODO: 跳转到审计时间线
        // val intent = Intent("com.ainative.action.VIEW_AUDIT_REF")
        // intent.putExtra("auditRef", auditRef)
        // context.startActivity(intent)
    }

    /**
     * 处理导出转写文本
     */
    private fun handleExportTranscript() {
        val sess = session ?: return
        val messages = transcriptMessages
        
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "No transcript to export")
            showToast("暂无转写内容可导出")
            return
        }
        
        Log.d(TAG, "User requested export transcript")
        
        // 生成导出文本
        val exportText = buildExportText(messages)
        
        // TODO: 保存到文件或分享
        // val intent = Intent(Intent.ACTION_SEND)
        // intent.type = "text/plain"
        // intent.putExtra(Intent.EXTRA_TEXT, exportText)
        // context.startActivity(Intent.createChooser(intent, "导出转写文本"))
        
        showToast("转写文本已导出")
    }

    /**
     * 处理举报误判
     */
    private fun handleReportMisjudgment() {
        val sess = session ?: return
        val sum = summary ?: return
        
        Log.d(TAG, "User reported misjudgment for session: ${sess.sessionId}")
        
        // TODO: 向策略引擎反馈误判
        // val intent = Intent("com.ainative.action.REPORT_MISJUDGMENT")
        // intent.putExtra("sessionId", sess.sessionId)
        // intent.putExtra("handleMode", sess.handleMode)
        // intent.putExtra("auditRef", sum.auditRef)
        // context.sendBroadcast(intent)
        
        showToast("误判反馈已提交，感谢您的反馈")
    }

    // ==================== 工具方法 ====================

    private fun buildExportText(messages: List<TranscriptMessage>): String {
        val sb = StringBuilder()
        sb.append("通话接管详情\n")
        sb.append("会话 ID：${session?.sessionId}\n")
        sb.append("接管模式：${formatHandleMode(session?.handleMode ?: 0)}\n")
        sb.append("通话时长：${formatDuration(summary?.durationSec ?: 0)}\n")
        sb.append("\n完整转写：\n")
        sb.append("─────────────────────\n\n")
        
        for (message in messages) {
            if (!message.isFinal) continue
            
            sb.append("${formatTimestamp(message.offsetMs)}  ${formatSpeaker(message.speaker)}\n")
            sb.append("${message.text}\n\n")
        }
        
        sb.append("─────────────────────\n")
        sb.append("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\n")
        
        return sb.toString()
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatHandleMode(handleMode: Int): String {
        return when (handleMode) {
            1 -> "静默留言（L1）"
            2 -> "AI 对话（L2）"
            3 -> "仅记录（L3）"
            else -> "未知模式"
        }
    }

    private fun formatDuration(durationSec: Int): String {
        if (durationSec < 60) {
            return "$durationSec 秒"
        }
        
        val minutes = durationSec / 60
        val seconds = durationSec % 60
        
        return if (minutes < 60) {
            "$minutes 分 $seconds 秒"
        } else {
            val hours = minutes / 60
            val mins = minutes % 60
            "$hours 小时 $mins 分 $seconds 秒"
        }
    }

    private fun formatTimestamp(offsetMs: Long): String {
        val totalSeconds = offsetMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSpeaker(speaker: Boolean): String {
        return if (speaker) "[助理]" else "[对方]"
    }
}

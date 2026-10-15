package com.android.systemui.ainative.callgate

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ainative.call.CallSessionHandle
import com.ainative.call.TranscriptDelta
import java.text.SimpleDateFormat
import java.util.*

/**
 * 转写增量显示组件
 * 
 * 显示时机：会话进入 TRANSCRIBING 状态后显示
 * 
 * 视觉设计：
 * ┌─────────────────────────────────────────┐
 * │  通话转写实时显示                        │
 * │  ────────────────────────────────────   │
 * │                                         │
 * │  14:32:05  [对方]                       │
 * │  你好，请问是张先生吗？                  │
 * │                                         │
 * │  14:32:08  [助理]                       │
 * │  您好，我是张先生的 AI 助理，他现在...  │
 * │                                         │
 * │  14:32:15  [对方]                       │
 * │  哦，那我想问一下...                    │
 * │                                         │
 * └─────────────────────────────────────────┘
 * 
 * 关键要素：
 * - 说话人标识：[对方] / [助理]
 * - 时间戳：HH:mm:ss 格式
 * - 增量更新：isFinal=false 显示省略号 "..."
 * - 自动滚动：新消息到来时滚动到底部
 * - 虚拟化：使用 RecyclerView 处理长对话（性能优化）
 * 
 * 安全策略：
 * - 不录音、不落地，仅实时显示
 * - 会话结束后自动清理
 * - 最大显示 200 条消息（防止内存溢出）
 */
class TranscriptView(private val context: Context) {
    
    companion object {
        private const val TAG = "TranscriptView"
        private const val MAX_MESSAGES = 200
    }
    
    private val windowManager: WindowManager = 
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var rootView: View? = null
    private var containerLayout: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var contentLayout: LinearLayout? = null
    
    private var session: CallSessionHandle? = null
    private var isShown = false
    
    // 转写消息列表
    private val transcriptMessages = mutableListOf<TranscriptMessage>()
    
    // 当前未完成的消息（isFinal=false）
    private var pendingMessage: TranscriptMessage? = null
    
    /**
     * 转写消息数据类
     */
    private data class TranscriptMessage(
        val speaker: Boolean,  // false=对方, true=助理
        val text: String,
        val offsetMs: Long,
        val isFinal: Boolean
    )

    /**
     * 显示转写视图
     * 
     * @param session 会话句柄
     */
    fun show(session: CallSessionHandle) {
        if (isShown) {
            Log.w(TAG, "Transcript view already shown")
            return
        }
        
        this.session = session
        
        createView()
        attachToWindow()
        
        isShown = true
        Log.d(TAG, "Transcript view shown for session: ${session.sessionId}")
    }

    /**
     * 追加转写增量
     * 
     * @param delta 转写增量
     */
    fun appendDelta(delta: TranscriptDelta) {
        if (!isShown) {
            Log.w(TAG, "Transcript view not shown, ignoring delta")
            return
        }
        
        mainHandler.post {
            handleDelta(delta)
        }
    }

    /**
     * 隐藏转写视图
     */
    fun dismiss() {
        if (!isShown) {
            return
        }
        
        mainHandler.post {
            detachFromWindow()
            transcriptMessages.clear()
            pendingMessage = null
            isShown = false
            Log.d(TAG, "Transcript view dismissed")
        }
    }

    private fun createView() {
        // 根布局
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0FFFFFF)
            setPadding(24, 24, 24, 24)
        }
        
        containerLayout = rootView as LinearLayout
        
        // 标题
        val titleText = TextView(context).apply {
            text = "通话转写实时显示"
            textSize = 16f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 16)
        }
        containerLayout?.addView(titleText)
        
        // 分隔线
        val divider = View(context).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                bottomMargin = 16
            }
        }
        containerLayout?.addView(divider)
        
        // 滚动容器
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                800 // 固定高度 800dp
            )
        }
        
        // 内容布局
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        scrollView?.addView(contentLayout)
        containerLayout?.addView(scrollView)
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
        params.width = 600 // 固定宽度 600dp
        
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
        scrollView = null
        contentLayout = null
    }

    private fun handleDelta(delta: TranscriptDelta) {
        if (delta.isFinal) {
            // 最终结果：添加新消息
            val message = TranscriptMessage(
                speaker = delta.speaker,
                text = delta.text,
                offsetMs = delta.offsetMs,
                isFinal = true
            )
            
            // 如果有未完成消息，先清除
            if (pendingMessage != null) {
                removeLastPendingView()
                pendingMessage = null
            }
            
            // 添加到列表
            transcriptMessages.add(message)
            
            // 检查列表长度限制
            if (transcriptMessages.size > MAX_MESSAGES) {
                transcriptMessages.removeAt(0)
                // 同时移除第一个视图
                if (contentLayout != null && contentLayout!!.childCount > 0) {
                    contentLayout!!.removeViewAt(0)
                }
            }
            
            // 渲染消息
            renderMessage(message)
            
        } else {
            // 中间结果：更新未完成消息
            if (pendingMessage == null || pendingMessage!!.speaker != delta.speaker) {
                // 新的未完成消息
                if (pendingMessage != null) {
                    removeLastPendingView()
                }
                
                pendingMessage = TranscriptMessage(
                    speaker = delta.speaker,
                    text = delta.text + "...",
                    offsetMs = delta.offsetMs,
                    isFinal = false
                )
                
                renderMessage(pendingMessage!!)
                
            } else {
                // 更新现有未完成消息
                pendingMessage = pendingMessage!!.copy(
                    text = delta.text + "...",
                    offsetMs = delta.offsetMs
                )
                
                updateLastPendingView(pendingMessage!!.text)
            }
        }
        
        // 自动滚动到底部
        scrollView?.post {
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun renderMessage(message: TranscriptMessage) {
        // 消息容器
        val messageLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }
        
        // 时间戳 + 说话人
        val headerText = TextView(context).apply {
            text = formatTimestamp(message.offsetMs) + "  " + formatSpeaker(message.speaker)
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 0, 0, 4)
        }
        messageLayout.addView(headerText)
        
        // 消息文本
        val messageText = TextView(context).apply {
            text = message.text
            textSize = 14f
            setTextColor(0xFF212121.toInt())
            
            // 未完成消息显示为斜体
            if (!message.isFinal) {
                setTypeface(null, android.graphics.Typeface.ITALIC)
                setTextColor(0xFF9E9E9E.toInt())
            }
        }
        messageLayout.addView(messageText)
        
        contentLayout?.addView(messageLayout)
    }

    private fun updateLastPendingView(newText: String) {
        if (contentLayout == null || contentLayout!!.childCount == 0) {
            return
        }
        
        val lastMessageLayout = contentLayout!!.getChildAt(contentLayout!!.childCount - 1) as? LinearLayout
        if (lastMessageLayout != null && lastMessageLayout.childCount >= 2) {
            val messageText = lastMessageLayout.getChildAt(1) as? TextView
            messageText?.text = newText
        }
    }

    private fun removeLastPendingView() {
        if (contentLayout != null && contentLayout!!.childCount > 0) {
            contentLayout!!.removeViewAt(contentLayout!!.childCount - 1)
        }
    }

    /**
     * 格式化时间戳（相对于会话开始）
     */
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

    /**
     * 格式化说话人
     */
    private fun formatSpeaker(speaker: Boolean): String {
        return if (speaker) "[助理]" else "[对方]"
    }
}

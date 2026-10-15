package com.genton.systemui.gesture

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * D9.1 姿态手势呈现 - 摇一摇就绪提示卡
 *
 * 当 MotionHub 检测到摇一摇手势后，弹出就绪提示卡：
 * - 显示"可拍照/问这是什么"快捷入口
 * - 快捷操作：拍照、视觉问答、创建备忘
 * - 自动消失（5 秒或用户选择后）
 *
 * 依赖：A12.1 MotionHubService（手势检测）、D3.2 活性指示器（录音态复用）
 */
class ShakeReadyCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 就绪提示卡回调 */
    interface ShakeReadyCallback {
        /** 用户选择拍照 */
        fun onTakePhoto()
        /** 用户选择视觉问答 */
        fun onAskWhat()
        /** 用户选择创建备忘（触发录音预告） */
        fun onCreateMemo()
        /** 卡片自动消失或用户关闭 */
        fun onDismiss()
    }

    private var callback: ShakeReadyCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val titleView: TextView
    private val subtitleView: TextView
    private val hintView: TextView
    private val takePhotoBtn: TextView
    private val askWhatBtn: TextView
    private val createMemoBtn: TextView
    private val closeBtn: TextView

    private var autoDismissRunnable: Runnable? = null
    private var isShowing = false

    companion object {
        private const val TAG = "ShakeReadyCard"
        private const val AUTO_DISMISS_MS = 5000L

        private val COLOR_BG = Color.parseColor("#2D2D2D")
        private val COLOR_TITLE = Color.parseColor("#FFFFFF")
        private val COLOR_SUBTITLE = Color.parseColor("#B0B0B0")
        private val COLOR_PRIMARY_BTN = Color.parseColor("#2196F3")
        private val COLOR_SECONDARY_BTN = Color.parseColor("#424242")
        private val COLOR_CLOSE = Color.parseColor("#757575")
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BG)
        val pad16dp = (16 * resources.displayMetrics.density).toInt()
        val pad12dp = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad16dp, pad16dp, pad16dp, pad16dp)

        // 标题
        titleView = TextView(context).apply {
            text = "🤖 已就绪"
            textSize = 18f
            setTextColor(COLOR_TITLE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        // 副标题
        subtitleView = TextView(context).apply {
            text = "摇一摇检测到：你可能想..."
            textSize = 13f
            setTextColor(COLOR_SUBTITLE)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }

        // 快捷操作区
        val actionLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        takePhotoBtn = createActionButton("📷 拍照", COLOR_PRIMARY_BTN)
        askWhatBtn = createActionButton("🔍 这是什么", COLOR_SECONDARY_BTN)
        createMemoBtn = createActionButton("🎙️ 备忘", COLOR_SECONDARY_BTN)

        actionLayout.addView(takePhotoBtn)
        actionLayout.addView(askWhatBtn)
        actionLayout.addView(createMemoBtn)

        // 提示
        hintView = TextView(context).apply {
            text = "提示：再次摇一摇或点击关闭"
            textSize = 11f
            setTextColor(COLOR_SUBTITLE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        // 关闭按钮
        closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(COLOR_CLOSE)
            gravity = Gravity.CENTER
            val size = (24 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setOnClickListener { dismiss() }
        }

        // 组装
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        val innerLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        innerLayout.addView(titleView)
        innerLayout.addView(subtitleView)
        innerLayout.addView(actionLayout)
        innerLayout.addView(hintView)
        container.addView(innerLayout)
        container.addView(closeBtn)

        addView(container)

        // 按钮点击
        takePhotoBtn.setOnClickListener {
            callback?.onTakePhoto()
            dismiss()
        }
        askWhatBtn.setOnClickListener {
            callback?.onAskWhat()
            dismiss()
        }
        createMemoBtn.setOnClickListener {
            callback?.onCreateMemo()
            dismiss()
        }
    }

    private fun createActionButton(text: String, bgColor: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = (4 * resources.displayMetrics.density).toInt()
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
        }
    }

    fun setCallback(cb: ShakeReadyCallback) {
        callback = cb
    }

    fun isShowing() = isShowing

    fun markShowing() {
        isShowing = true
        scheduleAutoDismiss()
    }

    private fun scheduleAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable { dismiss() }
        mainHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_MS)
    }

    fun dismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        if (isShowing) {
            isShowing = false
            callback?.onDismiss()
            Log.d(TAG, "ShakeReadyCard dismissed")
        }
    }
}

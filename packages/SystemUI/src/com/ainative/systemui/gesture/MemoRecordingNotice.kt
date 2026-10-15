package com.ainative.systemui.gesture

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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * D9.1 姿态手势呈现 - 备忘收声预告
 *
 * 当 MotionHub 检测到翻面手势（GESTURE_FLIP）后：
 * 1. 立即显示"将开始备忘录音"预告卡片
 * 2. 短振动（300ms 轻振动）
 * 3. 3 秒取消窗口内用户可取消
 * 4. 超过取消窗口则自动进入录音态
 *
 * 安全约束（对齐 E4.3）：
 * - 录音必须有预告 + 3s 取消窗，禁止静默开启
 * - 录音态指示/悬浮条必须在录屏/截屏中可见
 * - 录音完成后粗位置命名不出端
 */
class MemoRecordingNotice @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 预告回调 */
    interface NoticeCallback {
        /** 用户确认开始录音（或超时自动确认） */
        fun onStartRecording()
        /** 用户在取消窗口内取消 */
        fun onCancelRecording()
        /** 卡片消失（无论原因） */
        fun onDismiss()
    }

    private var callback: NoticeCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null

    private val countdownView: TextView
    private val cancelBtn: TextView
    private val titleView: TextView

    private var countdownSeconds = 3
    private var countdownRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var isActive = false

    companion object {
        private const val TAG = "MemoRecordingNotice"
        private const val CANCEL_WINDOW_MS = 3000L   // 3s 取消窗口
        private const val PRE_FLASH_MS = 500L         // 预告显示时间

        private val COLOR_BG = Color.parseColor("#E53935")  // 红色警示背景
        private val COLOR_TITLE = Color.WHITE
        private val COLOR_COUNTDOWN = Color.WHITE
        private val COLOR_CANCEL = Color.parseColor("#FFFFFF")
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BG)
        val pad16dp = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad16dp, pad16dp, pad16dp, pad16dp)

        titleView = TextView(context).apply {
            text = "🎙️ 将开始备忘录音"
            textSize = 16f
            setTextColor(COLOR_TITLE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        countdownView = TextView(context).apply {
            text = "3 秒后开始录音..."
            textSize = 13f
            setTextColor(COLOR_COUNTDOWN)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 13f
            setTextColor(COLOR_CANCEL)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B71C1C"))
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { cancelRecording() }
        }

        addView(titleView)
        addView(countdownView)
        addView(cancelBtn)

        // 初始化振动器
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun setCallback(cb: NoticeCallback) {
        callback = cb
    }

    /**
     * 启动预告流程：振动 + 3s 倒计时
     */
    fun startNotice() {
        isActive = true
        countdownSeconds = 3
        updateCountdownText()

        // 短振动（300ms）
        vibrateShort()

        // 开始倒计时
        startCountdown()

        // 3 秒后自动开始录音
        timeoutRunnable = Runnable {
            if (isActive) {
                Log.i(TAG, "Cancel window expired, starting recording")
                isActive = false
                callback?.onStartRecording()
                callback?.onDismiss()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, CANCEL_WINDOW_MS)
    }

    private fun vibrateShort() {
        try {
            vibrator?.let { v ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(300)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun startCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                if (countdownSeconds > 0 && isActive) {
                    updateCountdownText()
                    countdownSeconds--
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun updateCountdownText() {
        countdownView.text = "${countdownSeconds} 秒后开始录音..."
    }

    private fun cancelRecording() {
        if (!isActive) return
        isActive = false

        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

        Log.i(TAG, "Recording cancelled by user")
        callback?.onCancelRecording()
        callback?.onDismiss()
    }

    fun isActive() = isActive

    fun stop() {
        isActive = false
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    }
}

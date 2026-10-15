package com.ainative.systemui.gesture

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * D9.1 姿态手势呈现 - 录音状态悬浮条
 *
 * 备忘收声进行时的状态指示悬浮条，对齐 D3.2 活性指示器设计。
 *
 * 安全约束（对齐 E4.3 + E4.1）：
 * - 录音进行中必须有可见指示，录屏/截屏中必须能看到
 * - 指示条不得消失（除非录音终止）
 * - 显示实时录音时长
 * - 显示录音状态标签（准备中/录音中/暂停中）
 */
class RecordingStatusBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 录音状态 */
    enum class RecordingState {
        PREPARING,      // 准备中（音频路由/权限检查）
        RECORDING,      // 录音中
        PAUSED,         // 暂停
        PROCESSING      // 处理中（录音结束后命名/转写）
    }

    private val statusIcon: TextView
    private val statusLabel: TextView
    private val durationLabel: TextView
    private val stopBtn: TextView

    private var recordingState = RecordingState.PREPARING
    private var recordingStartMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var durationRunnable: Runnable? = null
    private var dotBlinkRunnable: Runnable? = null
    private var dotVisible = true

    companion object {
        private const val TAG = "RecordingStatusBar"

        private val COLOR_RECORDING = Color.parseColor("#E53935")
        private val COLOR_PAUSED = Color.parseColor("#FF9800")
        private val COLOR_PROCESSING = Color.parseColor("#2196F3")
        private val COLOR_PREPARING = Color.parseColor("#90A4AE")
        private val COLOR_TEXT = Color.WHITE
        private val COLOR_STOP_BTN = Color.parseColor("#FFFFFF")
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad12dp = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad12dp, pad12dp, pad12dp, pad12dp)

        statusIcon = TextView(context).apply {
            text = "●"
            textSize = 14f
            setTextColor(COLOR_RECORDING)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
        }

        statusLabel = TextView(context).apply {
            text = "准备中"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        durationLabel = TextView(context).apply {
            text = "00:00"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, android.graphics.Typeface.MONOSPACE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            }
        }

        stopBtn = TextView(context).apply {
            text = "■ 停止"
            textSize = 12f
            setTextColor(COLOR_STOP_BTN)
            gravity = Gravity.CENTER
            val padH = (10 * resources.displayMetrics.density).toInt()
            val padV = (6 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setTextColor(COLOR_RECORDING)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (12 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { requestStopRecording() }
        }

        addView(statusIcon)
        addView(statusLabel)
        addView(durationLabel)
        addView(stopBtn)

        setBackgroundColor(getStateColor(recordingState))
    }

    /** 停止录音回调（外部注入） */
    var onStopRequest: (() -> Unit)? = null

    private fun requestStopRecording() {
        Log.i(TAG, "Stop recording requested by user")
        onStopRequest?.invoke()
    }

    /**
     * 开始录音计时
     */
    fun startRecording() {
        recordingState = RecordingState.RECORDING
        recordingStartMs = System.currentTimeMillis()
        updateUI()
        startDurationCounter()
        startDotBlink()
    }

    /**
     * 暂停录音
     */
    fun pauseRecording() {
        recordingState = RecordingState.PAUSED
        stopDurationCounter()
        stopDotBlink()
        updateUI()
    }

    /**
     * 恢复录音
     */
    fun resumeRecording() {
        recordingState = RecordingState.RECORDING
        // 不重置开始时间，继续累计
        updateUI()
        startDurationCounter()
        startDotBlink()
    }

    /**
     * 结束录音（进入处理中状态）
     */
    fun finishRecording() {
        recordingState = RecordingState.PROCESSING
        stopDurationCounter()
        stopDotBlink()
        updateUI()
    }

    /**
     * 设置状态（完整控制）
     */
    fun setState(state: RecordingState) {
        recordingState = state
        updateUI()

        when (state) {
            RecordingState.RECORDING -> {
                if (recordingStartMs == 0L) recordingStartMs = System.currentTimeMillis()
                startDurationCounter()
                startDotBlink()
            }
            else -> {
                stopDurationCounter()
                stopDotBlink()
            }
        }
    }

    private fun updateUI() {
        setBackgroundColor(getStateColor(recordingState))
        statusLabel.text = getStateLabel(recordingState)
        statusIcon.setTextColor(getStateColor(recordingState).let {
            // 状态颜色稍深一点给圆点
            it
        })
        statusIcon.text = when (recordingState) {
            RecordingState.PREPARING -> "○"
            RecordingState.RECORDING -> if (dotVisible) "●" else "○"
            RecordingState.PAUSED -> "⏸"
            RecordingState.PROCESSING -> "…"
        }
    }

    private fun getStateColor(state: RecordingState): Int = when (state) {
        RecordingState.PREPARING -> COLOR_PREPARING
        RecordingState.RECORDING -> COLOR_RECORDING
        RecordingState.PAUSED -> COLOR_PAUSED
        RecordingState.PROCESSING -> COLOR_PROCESSING
    }

    private fun getStateLabel(state: RecordingState): String = when (state) {
        RecordingState.PREPARING -> "准备中"
        RecordingState.RECORDING -> "录音中"
        RecordingState.PAUSED -> "已暂停"
        RecordingState.PROCESSING -> "处理中"
    }

    private fun startDurationCounter() {
        stopDurationCounter()
        val runnable = object : Runnable {
            override fun run() {
                if (recordingState == RecordingState.RECORDING) {
                    val elapsedMs = System.currentTimeMillis() - recordingStartMs
                    val totalSeconds = elapsedMs / 1000
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    durationLabel.text = String.format("%02d:%02d", minutes, seconds)
                }
                mainHandler.postDelayed(this, 500)
            }
        }
        durationRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopDurationCounter() {
        durationRunnable?.let { mainHandler.removeCallbacks(it) }
        durationRunnable = null
    }

    private fun startDotBlink() {
        stopDotBlink()
        val runnable = object : Runnable {
            override fun run() {
                if (recordingState == RecordingState.RECORDING) {
                    dotVisible = !dotVisible
                    statusIcon.text = if (dotVisible) "●" else "○"
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
        dotBlinkRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopDotBlink() {
        dotBlinkRunnable?.let { mainHandler.removeCallbacks(it) }
        dotBlinkRunnable = null
        dotVisible = true
    }

    /**
     * 停止所有计时器和动画（释放资源）
     */
    fun cleanup() {
        stopDurationCounter()
        stopDotBlink()
    }
}

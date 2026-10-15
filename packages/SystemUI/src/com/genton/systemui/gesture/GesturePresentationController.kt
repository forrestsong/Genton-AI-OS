package com.genton.systemui.gesture

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.genton.motion.GestureEvent
import com.genton.motion.GestureEvent.GESTURE_FLIP
import com.genton.motion.GestureEvent.GESTURE_SHAKE
import com.genton.motion.GestureEvent.GESTURE_SWIPE
import com.genton.motion.IMotionGestureListener
import com.genton.motion.IMotionHubService
import com.genton.motion.MotionTierState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * D9.1 姿态手势呈现 - 主控控制器
 *
 * 整合所有姿态手势 UI 组件：
 * 1. ShakeReadyCard - 摇一摇就绪提示卡
 * 2. MemoRecordingNotice - 翻面备忘录音预告（3s 取消窗口）
 * 3. RecordingStatusBar - 录音状态悬浮条
 * 4. MemoNamingPreview - 录音完成命名预览
 *
 * 生命周期：
 *   MotionHubService → IMotionGestureListener → 控制器回调 → UI 呈现
 *
 * 安全约束：
 * - 录音预告强制 3s 取消窗口（对齐 E4.1/E4.3）
 * - 录音状态悬浮条在录屏/截屏中必须可见
 * - 粗位置命名仅设备端生成，不出端
 *
 * 依赖：
 * - A12.1 MotionHubService
 * - D3.2 活性指示器（录音态指示视觉风格复用）
 */
class GesturePresentationController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // UI 组件
    private lateinit var shakeReadyCard: ShakeReadyCard
    private lateinit var recordingNotice: MemoRecordingNotice
    private lateinit var recordingStatusBar: RecordingStatusBar
    private lateinit var namingPreview: MemoNamingPreview

    // MotionHub 服务引用（mock 或真实 Binder）
    private var motionHubService: IMotionHubService? = null
    private var gestureListenerHandle: Long = -1

    // 当前活动状态
    private var isShakeCardShowing = false
    private var isRecordingNoticeShowing = false
    private var isRecordingActive = false
    private var isNamingShowing = false

    // 当前录音时长与命名
    private var memoRecordingStartMs = 0L

    companion object {
        private const val TAG = "GesturePresCtrl"
    }

    /**
     * 初始化所有 UI 组件
     */
    fun initialize() {
        setupShakeReadyCard()
        setupRecordingNotice()
        setupRecordingStatusBar()
        setupNamingPreview()
        Log.i(TAG, "GesturePresentationController initialized")
    }

    private fun setupShakeReadyCard() {
        shakeReadyCard = ShakeReadyCard(context)
        shakeReadyCard.setCallback(object : ShakeReadyCard.ShakeReadyCallback {
            override fun onTakePhoto() {
                Log.i(TAG, "Shake action: Take photo")
                // TODO: 启动相机 App 或 AI 相机
            }
            override fun onAskWhat() {
                Log.i(TAG, "Shake action: Ask what is this")
                // TODO: 启动视觉问答
            }
            override fun onCreateMemo() {
                Log.i(TAG, "Shake action: Create memo (skip notice, go directly?)")
                // 用户主动选择备忘 → 可跳过预告直接录音（视策略而定）
                // 但安全起见，仍然先走预告流程
                showRecordingNotice()
            }
            override fun onDismiss() {
                hideShakeCard()
            }
        })
    }

    private fun setupRecordingNotice() {
        recordingNotice = MemoRecordingNotice(context)
        recordingNotice.setCallback(object : MemoRecordingNotice.NoticeCallback {
            override fun onStartRecording() {
                startRecording()
            }
            override fun onCancelRecording() {
                Log.i(TAG, "Recording cancelled")
                isRecordingActive = false
            }
            override fun onDismiss() {
                hideRecordingNotice()
            }
        })
    }

    private fun setupRecordingStatusBar() {
        recordingStatusBar = RecordingStatusBar(context)
        recordingStatusBar.onStopRequest = {
            stopRecording(manualStop = true)
        }
    }

    private fun setupNamingPreview() {
        namingPreview = MemoNamingPreview(context)
        namingPreview.setCallback(object : MemoNamingPreview.NamingCallback {
            override fun onConfirmName(name: String) {
                Log.i(TAG, "Memo saved with name: $name")
                hideNamingPreview()
            }
            override fun onEditName(currentName: String) {
                Log.i(TAG, "Edit memo name: $currentName")
                // TODO: 弹出编辑框让用户修改
            }
            override fun onDiscard() {
                Log.i(TAG, "Memo discarded")
                hideNamingPreview()
            }
        })
    }

    // ===== MotionHub 服务接入 =====

    /**
     * 设置 MotionHub 服务（mock 或真实 Binder）
     * 并注册手势监听器
     */
    fun setMotionHubService(service: IMotionHubService?) {
        // 先注销旧监听
        if (motionHubService != null && gestureListenerHandle >= 0) {
            try {
                motionHubService?.unregisterGestureListener(gestureListenerHandle)
            } catch (_: Exception) {}
        }

        motionHubService = service

        if (service != null) {
            try {
                val mask = GESTURE_SHAKE or GESTURE_FLIP or GESTURE_SWIPE
                val handle = service.registerGestureListener(gestureListener, mask)
                gestureListenerHandle = handle
                Log.i(TAG, "Gesture listener registered, handle=$handle")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register gesture listener", e)
            }
        }
    }

    /**
     * 手势事件监听器（实现 IMotionGestureListener.Stub）
     */
    private val gestureListener = object : IMotionGestureListener.Stub() {
        override fun onGestureDetected(event: GestureEvent?) {
            if (event == null) return

            mainHandler.post {
                handleGesture(event)
            }
        }
    }

    private fun handleGesture(event: GestureEvent) {
        Log.i(TAG, "Gesture detected: type=${event.gestureType}, confidence=${event.confidence}")

        when (event.gestureType) {
            GESTURE_SHAKE -> {
                // 摇一摇 → 显示就绪提示卡
                if (!isRecordingActive && !isNamingShowing) {
                    showShakeReadyCard()
                }
            }
            GESTURE_FLIP -> {
                // 翻面 → 备忘收声预告
                if (!isRecordingActive && !isNamingShowing) {
                    showRecordingNotice()
                }
            }
            GESTURE_SWIPE -> {
                // 划动手势（预留）
                Log.d(TAG, "Swipe gesture detected (reserved)")
            }
            else -> {
                Log.d(TAG, "Unknown gesture type: ${event.gestureType}")
            }
        }
    }

    // ===== UI 显示/隐藏 =====

    /** 摇一摇就绪提示卡 */
    fun showShakeReadyCard() {
        if (isShakeCardShowing) return
        mainHandler.post {
            val params = createOverlayParams(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            try {
                windowManager.addView(shakeReadyCard, params)
                shakeReadyCard.markShowing()
                isShakeCardShowing = true
                Log.i(TAG, "ShakeReadyCard shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show ShakeReadyCard", e)
            }
        }
    }

    fun hideShakeCard() {
        if (!isShakeCardShowing) return
        mainHandler.post {
            try {
                windowManager.removeView(shakeReadyCard)
            } catch (_: Exception) {}
            isShakeCardShowing = false
            Log.d(TAG, "ShakeReadyCard hidden")
        }
    }

    /** 备忘收声预告（3s 取消窗口） */
    fun showRecordingNotice() {
        if (isRecordingNoticeShowing || isRecordingActive) return
        mainHandler.post {
            hideShakeCard() // 先隐藏摇一摇卡片（避免同时出现）

            val params = createOverlayParams(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            try {
                windowManager.addView(recordingNotice, params)
                isRecordingNoticeShowing = true
                recordingNotice.startNotice()
                Log.i(TAG, "Recording notice shown, 3s cancel window started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show recording notice", e)
            }
        }
    }

    fun hideRecordingNotice() {
        if (!isRecordingNoticeShowing) return
        mainHandler.post {
            try {
                windowManager.removeView(recordingNotice)
            } catch (_: Exception) {}
            recordingNotice.stop()
            isRecordingNoticeShowing = false
            Log.d(TAG, "Recording notice hidden")
        }
    }

    /** 录音状态悬浮条 */
    fun showRecordingStatusBar() {
        if (isRecordingActive) return
        mainHandler.post {
            hideRecordingNotice()

            val params = createOverlayParams(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            try {
                windowManager.addView(recordingStatusBar, params)
                recordingStatusBar.startRecording()
                isRecordingActive = true
                memoRecordingStartMs = System.currentTimeMillis()
                Log.i(TAG, "Recording status bar shown, recording started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show recording status bar", e)
            }
        }
    }

    fun hideRecordingStatusBar() {
        if (!isRecordingActive) return
        mainHandler.post {
            try {
                windowManager.removeView(recordingStatusBar)
            } catch (_: Exception) {}
            recordingStatusBar.cleanup()
            isRecordingActive = false
            Log.d(TAG, "Recording status bar hidden")
        }
    }

    /** 命名预览 */
    fun showNamingPreview(autoName: String, locationTag: String? = null, timeTag: String? = null) {
        if (isNamingShowing) return
        mainHandler.post {
            hideRecordingStatusBar()

            val params = createOverlayParams(Gravity.CENTER)
            try {
                windowManager.addView(namingPreview, params)
                namingPreview.setNamingData(autoName, locationTag, timeTag)
                isNamingShowing = true
                Log.i(TAG, "Naming preview shown: $autoName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show naming preview", e)
            }
        }
    }

    fun hideNamingPreview() {
        if (!isNamingShowing) return
        mainHandler.post {
            try {
                windowManager.removeView(namingPreview)
            } catch (_: Exception) {}
            isNamingShowing = false
            Log.d(TAG, "Naming preview hidden")
        }
    }

    // ===== 录音控制 =====

    fun startRecording() {
        if (isRecordingActive) return
        showRecordingStatusBar()
        Log.i(TAG, "Recording started")
    }

    /**
     * 停止录音
     * @param manualStop 是否用户手动停止（false = 自动停止/超时）
     */
    fun stopRecording(manualStop: Boolean = false) {
        if (!isRecordingActive) return

        recordingStatusBar.finishRecording()
        val durationSec = (System.currentTimeMillis() - memoRecordingStartMs) / 1000

        Log.i(TAG, "Recording stopped (manual=$manualStop), duration=${durationSec}s")

        // 生成命名预览（设备端粗位置 + 时间，不出端）
        val previewData = generateNamingPreview(durationSec)
        val (autoName, locTag, timeTag) = previewData

        // 延迟一点显示命名预览（让用户看到处理中状态）
        mainHandler.postDelayed({
            hideRecordingStatusBar()
            showNamingPreview(autoName, locTag, timeTag)
        }, 1500)
    }

    /**
     * 生成自动命名预览
     * 粗位置 + 时间标签，全部在设备端生成，不上云
     */
    private fun generateNamingPreview(durationSec: Long): Triple<String, String?, String> {
        // 自动命名："备忘 [时间标签]"
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeTagStr = timeFormat.format(now)

        // 时长友好描述
        val durationDesc = when {
            durationSec < 60 -> "${durationSec}秒"
            durationSec < 3600 -> "${durationSec / 60}分钟${if (durationSec % 60 > 0) durationSec % 60 else ""}"
            else -> "${durationSec / 3600}小时${(durationSec % 3600) / 60}分钟"
        }

        val autoName = "备忘 ${timeTagStr}（${durationDesc}）"
        val timeDisplay = "今天 ${timeTagStr} · ${durationDesc}"

        // 粗位置标签 mock（实际实现需调用位置服务 + 反地理编码）
        // 对齐 E4.3：位置仅在设备端模糊处理，不出端
        val locationTag: String? = null // TODO: 集成粗位置（WiFi 粗略区域，非 GPS）

        return Triple(autoName, locationTag, timeDisplay)
    }

    // ===== 工具方法 =====

    private fun createOverlayParams(gravity: Int): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }

        // 如果贴顶部，加一个状态栏高度偏移
        if (gravity and Gravity.TOP != 0) {
            val statusBarHeight = run {
                val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
            }
            params.y = statusBarHeight + 8
        }

        return params
    }

    // ===== 演示模式 =====

    /**
     * 启动完整手势演示序列（无上游 MotionHub 时使用）
     */
    fun startDemo() {
        val mainHandler = Handler(Looper.getMainLooper())

        // 1s 后：模拟摇一摇
        mainHandler.postDelayed({
            val shakeEvent = GestureEvent().apply {
                gestureType = GESTURE_SHAKE
                timestamp = System.currentTimeMillis()
                confidence = 0.95f
            }
            handleGesture(shakeEvent)
        }, 1000)

        // 7s 后：隐藏摇一摇，模拟翻面 → 触发录音预告
        mainHandler.postDelayed({
            val flipEvent = GestureEvent().apply {
                gestureType = GESTURE_FLIP
                timestamp = System.currentTimeMillis()
                confidence = 0.92f
            }
            handleGesture(flipEvent)
        }, 7000)

        // 12s 后：预告 3s 超时 → 进入录音态
        mainHandler.postDelayed({
            if (isRecordingNoticeShowing) {
                recordingNotice.stop()
                hideRecordingNotice()
                startRecording()
            }
        }, 12000)

        // 22s 后：模拟用户手动停止录音
        mainHandler.postDelayed({
            stopRecording(manualStop = true)
        }, 22000)
    }

    /**
     * 清理所有资源
     */
    fun destroy() {
        hideShakeCard()
        hideRecordingNotice()
        hideRecordingStatusBar()
        hideNamingPreview()

        // 注销手势监听
        if (motionHubService != null && gestureListenerHandle >= 0) {
            try {
                motionHubService?.unregisterGestureListener(gestureListenerHandle)
            } catch (_: Exception) {}
            gestureListenerHandle = -1
        }

        Log.i(TAG, "GesturePresentationController destroyed")
    }
}

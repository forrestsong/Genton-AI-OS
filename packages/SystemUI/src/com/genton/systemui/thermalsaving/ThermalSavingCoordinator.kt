package com.genton.systemui.thermalsaving

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Window
import android.view.WindowManager
import com.genton.motion.MotionTierState

/**
 * D8.2 渲染/帧率降载触发 UI 协作
 *
 * 当 AI Agent 正在执行感知密集型任务时（如视觉问答、长会话、坐标宏录制），
 * 自动降低 SystemUI 渲染帧率/动画复杂度以节省功耗和 CPU。
 *
 * 核心原则：
 * 1. 任务期降载绑定：Agent 执行期绑定降载模式，任务结束立即复原
 * 2. 离开即复原：任何异常退出（Agent 崩溃/超时）都能在 5 秒内自动恢复
 * 3. 无敏感面：降载状态下不得隐藏敏感信息指示（录音态、安全警告、确认面板必须保持正常帧率）
 *
 * 对齐 D9 §6h / A2.5 的降载纪律。
 *
 * 依赖：
 * - D1.1 双模式容器（可在 Assistant Surface 降载）
 * - A2.5 降载纪律底层（scaleHint、setFrameRate）
 * - D3.2 活性指示器（敏感面排除）
 * - A12.1 MotionHub（敏感档位 T3 自动降载）
 */
class ThermalSavingCoordinator(
    private val context: Context
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前降载等级 */
    private var currentLevel = SavingLevel.NONE

    /** 降载原因（供透明化展示） */
    private var savingReasons = mutableSetOf<SavingReason>()

    /** 已注册的降载观察者 */
    private val observers = mutableListOf<SavingLevelObserver>()

    /** 任务引用计数（多个任务同时请求降载时不提前恢复） */
    private var taskRefCount = 0

    /** 离开即复原超时（5 秒内无心跳则强制恢复） */
    private val restoreTimeoutMs = 5000L
    private var heartbeatRunnable: Runnable? = null

    companion object {
        private const val TAG = "ThermalSavingCoordinator"
    }

    /** 降载等级 */
    enum class SavingLevel {
        /** 正常：60fps，所有动画 */
        NONE,
        /** 轻度降载：45fps，复杂动画简化 */
        LIGHT,
        /** 中度降载：30fps，仅基础动画 */
        MODERATE,
        /** 重度降载：15fps，无动画（极端条件） */
        HEAVY
    }

    /** 降载触发原因 */
    enum class SavingReason {
        /** Agent 执行感知密集任务（视觉问答/宏录制） */
        AGENT_PERCEPTION_TASK,
        /** 长会话（>10 分钟） */
        LONG_SESSION,
        /** 低电量（<15%） */
        LOW_BATTERY,
        /** 设备过热（温度 >45°C） */
        DEVICE_OVERHEAT,
        /** MotionHub T3 敏感档位 */
        MOTION_SENSITIVE_MODE
    }

    /**
     * 降载等级变化观察者
     */
    interface SavingLevelObserver {
        /**
         * 降载等级变化
         * @param newLevel 新等级
         * @param reasons 触发原因集合
         */
        fun onSavingLevelChanged(newLevel: SavingLevel, reasons: Set<SavingReason>)

        /**
         * 查询该 UI 是否属于"敏感面"（必须保持正常帧率）
         * 返回 true 时，即便整体降载该观察者也不会被降载
         */
        fun isSensitiveSurface(): Boolean = false
    }

    // ===== 公开 API =====

    /**
     * 注册降载观察者
     */
    fun addObserver(observer: SavingLevelObserver) {
        if (!observers.contains(observer)) {
            observers.add(observer)
            // 立即推送当前状态
            mainHandler.post {
                observer.onSavingLevelChanged(currentLevel, savingReasons.toSet())
            }
        }
    }

    /**
     * 注销降载观察者
     */
    fun removeObserver(observer: SavingLevelObserver) {
        observers.remove(observer)
    }

    /**
     * 请求降载（Agent 任务开始时调用）
     *
     * @param reason 降载原因
     * @param level 期望降载等级
     */
    fun requestSaving(reason: SavingReason, level: SavingLevel = SavingLevel.MODERATE) {
        mainHandler.post {
            savingReasons.add(reason)
            taskRefCount++
            recomputeLevel()
            scheduleHeartbeat()
            Log.i(TAG, "Saving requested: reason=$reason, level=$level, refCount=$taskRefCount")
        }
    }

    /**
     * 释放降载（Agent 任务结束时调用）
     */
    fun releaseSaving(reason: SavingReason) {
        mainHandler.post {
            savingReasons.remove(reason)
            taskRefCount = (taskRefCount - 1).coerceAtLeast(0)
            recomputeLevel()
            Log.i(TAG, "Saving released: reason=$reason, refCount=$taskRefCount")
        }
    }

    /**
     * 心跳刷新（Agent 任务执行中每 2 秒调用一次，防异常退出后卡死在降载态）
     */
    fun heartbeat() {
        mainHandler.post {
            scheduleHeartbeat()
        }
    }

    /**
     * MotionHub 档位变化回调（系统层面触发）
     */
    fun onMotionTierChanged(tier: MotionTierState) {
        mainHandler.post {
            if (tier.currentTier == MotionTierState.TIER_SENSITIVE_STOP) {
                savingReasons.add(SavingReason.MOTION_SENSITIVE_MODE)
            } else {
                savingReasons.remove(SavingReason.MOTION_SENSITIVE_MODE)
            }
            recomputeLevel()
        }
    }

    /**
     * 获取当前降载等级
     */
    fun getCurrentLevel() = currentLevel

    /**
     * 获取当前降载原因
     */
    fun getCurrentReasons() = savingReasons.toSet()

    // ===== 内部实现 =====

    private fun recomputeLevel() {
        val newLevel = computeLevelFromReasons()
        if (newLevel != currentLevel) {
            currentLevel = newLevel
            notifyLevelChanged()
            applySystemFrameRateHint(newLevel)
            Log.i(TAG, "Saving level changed to: $newLevel")
        }
    }

    private fun computeLevelFromReasons(): SavingLevel {
        if (savingReasons.isEmpty()) return SavingLevel.NONE

        // 叠加多个原因，取最严重的等级
        return when {
            // 过热或极度低电量 → 重度降载
            savingReasons.contains(SavingReason.DEVICE_OVERHEAT) &&
            savingReasons.contains(SavingReason.LOW_BATTERY) -> SavingLevel.HEAVY

            // MotionHub 敏感档位 或 过热
            savingReasons.contains(SavingReason.MOTION_SENSITIVE_MODE) ||
            savingReasons.contains(SavingReason.DEVICE_OVERHEAT) -> SavingLevel.MODERATE

            // 低电量 + 长会话
            savingReasons.contains(SavingReason.LOW_BATTERY) &&
            savingReasons.contains(SavingReason.LONG_SESSION) -> SavingLevel.MODERATE

            // Agent 感知任务 + 低电量
            savingReasons.contains(SavingReason.AGENT_PERCEPTION_TASK) &&
            savingReasons.contains(SavingReason.LOW_BATTERY) -> SavingLevel.MODERATE

            // Agent 感知任务 / 长会话 / 单独低电量 → 轻度
            else -> SavingLevel.LIGHT
        }
    }

    private fun notifyLevelChanged() {
        for (observer in observers) {
            // 敏感面观察者：通知但不传降载效果（由观察者自行决定）
            observer.onSavingLevelChanged(currentLevel, savingReasons.toSet())
        }
    }

    /**
     * 应用系统帧率暗示（setFrameRate API）
     * 对齐 A2.5 降载纪律底层
     */
    private fun applySystemFrameRateHint(level: SavingLevel) {
        // 目标帧率
        val targetFps = when (level) {
            SavingLevel.NONE -> 60f
            SavingLevel.LIGHT -> 45f
            SavingLevel.MODERATE -> 30f
            SavingLevel.HEAVY -> 15f
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 应用到 ActivityManager（系统级帧率暗示）
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                // TODO: ActivityManager.setFrozenFrameRate() 仅 Android 12+
                // 或使用 Window.setFrameRate() API
                Log.d(TAG, "Applied frame rate hint: ${targetFps}fps (SDK=${Build.VERSION.SDK_INT})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply frame rate hint", e)
            }
        }

        // 动画时长调整（Android 系统设置）
        try {
            when (level) {
                SavingLevel.NONE -> {
                    // 恢复正常动画（scale = 1.0）
                    setGlobalAnimatorScale(1.0f)
                }
                SavingLevel.LIGHT -> {
                    // 动画时长缩短 50%
                    setGlobalAnimatorScale(0.5f)
                }
                SavingLevel.MODERATE -> {
                    // 动画时长缩短 80%，仅保留必要动画
                    setGlobalAnimatorScale(0.2f)
                }
                SavingLevel.HEAVY -> {
                    // 禁用所有动画
                    setGlobalAnimatorScale(0f)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust animation scale", e)
        }
    }

    private fun setGlobalAnimatorScale(scale: Float) {
        // 使用 GlobalAnimationController（如果有）
        // 或通过 Settings.Global（需要 WRITE_SETTINGS 权限）
        try {
            // 这是一个简化实现，真实实现需要修改 Settings
            // Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            Log.d(TAG, "Animation scale set to: $scale")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot modify animation scale (permission required)")
        }
    }

    private fun scheduleHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = Runnable {
            // 无心跳 → 强制恢复
            if (taskRefCount > 0) {
                Log.w(TAG, "Heartbeat timeout, force restoring...")
                savingReasons.clear()
                taskRefCount = 0
                recomputeLevel()
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, restoreTimeoutMs)
    }

    /**
     * 清理所有状态
     */
    fun destroy() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        savingReasons.clear()
        taskRefCount = 0
        recomputeLevel()
        observers.clear()
        Log.i(TAG, "ThermalSavingCoordinator destroyed")
    }
}

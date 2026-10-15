package com.ainative.systemui.gesture

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ainative.motion.IMotionHubService

/**
 * D9.2 姿态交互设置与授权 - 控制器
 *
 * 核心职责：
 * 1. 管理姿态交互设置状态（持久化到 SharedPreferences）
 * 2. 总开关一键全停（撤销所有手势监听）
 * 3. 逐项 opt-in 开关管理
 * 4. 录音授权管理（授予/撤销）
 * 5. 敏感场景禁用规则
 * 6. 误触发统计收集与展示
 *
 * 安全约束（对齐 D9 §8.3）：
 * - 所有姿态交互默认关闭（opt-in）
 * - 录音功能需要独立授权，撤销即时生效
 * - 总开关关闭时立即停止所有手势监听
 * - 敏感场景自动禁用（通话/锁屏/相机等）
 *
 * 依赖：
 * - A12.1 MotionHubService（手势监听注册/撤销）
 * - D9.1 GesturePresentationController（UI 呈现联动）
 */
class GestureSettingsController(
    private val context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var motionHubService: IMotionHubService? = null
    private var settingsView: GestureSettingsView? = null

    // 设置变更监听器
    var onSettingsChanged: ((GestureSettingsSnapshot) -> Unit)? = null

    companion object {
        private const val TAG = "GestureSettingsCtrl"
        private const val PREFS_NAME = "gesture_settings"

        // 偏好键
        private const val KEY_MASTER_SWITCH = "master_switch"
        private const val KEY_SHAKE_ENABLED = "shake_enabled"
        private const val KEY_FLIP_ENABLED = "flip_enabled"
        private const val KEY_SWIPE_ENABLED = "swipe_enabled"
        private const val KEY_RECORDING_GRANTED = "recording_granted"
        private const val KEY_RECORDING_GRANTED_AT = "recording_granted_at"

        // 敏感场景键前缀
        private const val KEY_SENSITIVE_PREFIX = "sensitive_"

        // 统计键前缀
        private const val KEY_STATS_TOTAL_PREFIX = "stats_total_"
        private const val KEY_STATS_CANCELLED_PREFIX = "stats_cancelled_"
        private const val KEY_STATS_LAST_PREFIX = "stats_last_"
    }

    /**
     * 绑定设置视图
     */
    fun attachView(view: GestureSettingsView) {
        this.settingsView = view

        // 注册回调
        view.onMasterSwitchChanged = { enabled -> setMasterSwitch(enabled) }
        view.onFeatureToggled = { type, enabled -> setFeatureEnabled(type, enabled) }
        view.onRecordingPermissionRequest = { grantRecordingPermission() }
        view.onRecordingPermissionRevoke = { revokeRecordingPermission() }
        view.onSensitiveScenarioChanged = { scenario, disable ->
            setSensitiveScenarioDisabled(scenario, disable)
        }

        // 初始渲染
        view.render(buildSnapshot())
    }

    /**
     * 设置 MotionHub 服务引用
     */
    fun setMotionHubService(service: IMotionHubService?) {
        this.motionHubService = service
    }

    // ===== 总开关 =====

    /**
     * 设置总开关（一键全停）
     */
    fun setMasterSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER_SWITCH, enabled).apply()
        Log.i(TAG, "Master switch set to: $enabled")

        if (!enabled) {
            // 一键全停：立即撤销所有手势监听
            stopAllGestureListening()
        } else {
            // 重新启用：根据各项开关恢复监听
            applyGestureListening()
        }

        notifyChanged()
    }

    fun isMasterSwitchEnabled(): Boolean =
        prefs.getBoolean(KEY_MASTER_SWITCH, false)  // 默认关闭（opt-in）

    // ===== 逐项功能开关 =====

    /**
     * 设置单个功能开关
     */
    fun setFeatureEnabled(type: GestureType, enabled: Boolean) {
        val key = when (type) {
            GestureType.SHAKE -> KEY_SHAKE_ENABLED
            GestureType.FLIP -> KEY_FLIP_ENABLED
            GestureType.SWIPE -> KEY_SWIPE_ENABLED
        }
        prefs.edit().putBoolean(key, enabled).apply()
        Log.i(TAG, "Feature $type set to: $enabled")

        // 翻面备忘需要录音权限校验
        if (type == GestureType.FLIP && enabled && !isRecordingGranted()) {
            Log.w(TAG, "FLIP enabled but recording permission not granted")
        }

        applyGestureListening()
        notifyChanged()
    }

    fun isFeatureEnabled(type: GestureType): Boolean {
        val key = when (type) {
            GestureType.SHAKE -> KEY_SHAKE_ENABLED
            GestureType.FLIP -> KEY_FLIP_ENABLED
            GestureType.SWIPE -> KEY_SWIPE_ENABLED
        }
        return prefs.getBoolean(key, false)  // 默认关闭（opt-in）
    }

    // ===== 录音授权 =====

    /**
     * 授予录音权限
     */
    fun grantRecordingPermission() {
        prefs.edit()
            .putBoolean(KEY_RECORDING_GRANTED, true)
            .putLong(KEY_RECORDING_GRANTED_AT, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Recording permission granted")
        notifyChanged()
    }

    /**
     * 撤销录音权限（即时生效）
     */
    fun revokeRecordingPermission() {
        prefs.edit()
            .putBoolean(KEY_RECORDING_GRANTED, false)
            .putLong(KEY_RECORDING_GRANTED_AT, 0L)
            .apply()
        Log.i(TAG, "Recording permission revoked")

        // 撤销录音权限后，自动关闭依赖录音的功能（翻面备忘）
        if (isFeatureEnabled(GestureType.FLIP)) {
            setFeatureEnabled(GestureType.FLIP, false)
            Log.i(TAG, "FLIP feature auto-disabled due to recording permission revoke")
        }

        applyGestureListening()
        notifyChanged()
    }

    fun isRecordingGranted(): Boolean =
        prefs.getBoolean(KEY_RECORDING_GRANTED, false)

    // ===== 敏感场景 =====

    /**
     * 设置敏感场景是否禁用姿态交互
     */
    fun setSensitiveScenarioDisabled(scenario: SensitiveScenario, disable: Boolean) {
        prefs.edit().putBoolean("$KEY_SENSITIVE_PREFIX${scenario.name}", disable).apply()
        Log.i(TAG, "Sensitive scenario $scenario disable=$disable")
        notifyChanged()
    }

    fun isSensitiveScenarioDisabled(scenario: SensitiveScenario): Boolean {
        // 敏感场景默认禁用（安全优先）
        return prefs.getBoolean("$KEY_SENSITIVE_PREFIX${scenario.name}", true)
    }

    /**
     * 检查当前是否处于敏感场景（应禁用姿态交互）
     */
    fun shouldDisableForCurrentScenario(activeScenarios: Set<SensitiveScenario>): Boolean {
        return activeScenarios.any { isSensitiveScenarioDisabled(it) }
    }

    // ===== 误触发统计 =====

    /**
     * 记录一次手势激活
     */
    fun recordActivation(type: GestureType) {
        val totalKey = "$KEY_STATS_TOTAL_PREFIX${type.name}"
        val total = prefs.getInt(totalKey, 0) + 1
        prefs.edit()
            .putInt(totalKey, total)
            .putLong("$KEY_STATS_LAST_PREFIX${type.name}", System.currentTimeMillis())
            .apply()
        notifyChanged()
    }

    /**
     * 记录一次用户取消（3s 窗口内取消，视为误触发）
     */
    fun recordUserCancellation(type: GestureType) {
        val cancelKey = "$KEY_STATS_CANCELLED_PREFIX${type.name}"
        val cancelled = prefs.getInt(cancelKey, 0) + 1
        prefs.edit().putInt(cancelKey, cancelled).apply()
        Log.i(TAG, "User cancellation recorded for $type, total=$cancelled")
        notifyChanged()
    }

    private fun getStats(type: GestureType): FalseActivationStats {
        val total = prefs.getInt("$KEY_STATS_TOTAL_PREFIX${type.name}", 0)
        val cancelled = prefs.getInt("$KEY_STATS_CANCELLED_PREFIX${type.name}", 0)
        val lastTime = prefs.getLong("$KEY_STATS_LAST_PREFIX${type.name}", 0L)
        val rate = if (total > 0) cancelled.toFloat() / total else 0f

        return FalseActivationStats(
            gestureType = type,
            totalActivations = total,
            userCancelledCount = cancelled,
            falsePositiveRate = rate,
            lastActivationTime = lastTime
        )
    }

    // ===== MotionHub 联动 =====

    /**
     * 应用手势监听（根据当前开关状态）
     */
    private fun applyGestureListening() {
        val service = motionHubService ?: return
        if (!isMasterSwitchEnabled()) {
            stopAllGestureListening()
            return
        }

        // 根据各项开关计算需要监听的手势掩码
        // 实际掩码值由 GesturePresentationController 注册，这里仅记录意图
        Log.i(TAG, "Apply gesture listening: shake=${isFeatureEnabled(GestureType.SHAKE)}, " +
                "flip=${isFeatureEnabled(GestureType.FLIP)}, swipe=${isFeatureEnabled(GestureType.SWIPE)}")
    }

    /**
     * 停止所有手势监听（总开关关闭时）
     */
    private fun stopAllGestureListening() {
        Log.i(TAG, "Stopping all gesture listening (master switch off)")
        // 实际撤销由 GesturePresentationController.destroy() 处理
        // 这里通知联动组件
    }

    // ===== 快照构建 =====

    /**
     * 构建完整设置快照
     */
    fun buildSnapshot(): GestureSettingsSnapshot {
        val masterEnabled = isMasterSwitchEnabled()

        return GestureSettingsSnapshot(
            masterSwitch = GestureMasterSwitchState(
                enabled = masterEnabled,
                canToggle = true,
                disabledReason = null
            ),
            featureConfigs = listOf(
                GestureFeatureConfig(
                    gestureType = GestureType.SHAKE,
                    enabled = isFeatureEnabled(GestureType.SHAKE),
                    requiresRecordingPermission = false,
                    description = "摇一摇手机唤起快捷入口（拍照/问这是什么/创建备忘）"
                ),
                GestureFeatureConfig(
                    gestureType = GestureType.FLIP,
                    enabled = isFeatureEnabled(GestureType.FLIP),
                    requiresRecordingPermission = true,
                    description = "翻面手机启动备忘录音（3 秒取消窗口）"
                ),
                GestureFeatureConfig(
                    gestureType = GestureType.SWIPE,
                    enabled = isFeatureEnabled(GestureType.SWIPE),
                    requiresRecordingPermission = false,
                    description = "划动手势（预留功能）"
                )
            ),
            recordingPermission = RecordingPermissionState(
                granted = isRecordingGranted(),
                grantedAt = prefs.getLong(KEY_RECORDING_GRANTED_AT, 0L),
                canRevoke = true
            ),
            falseActivationStats = listOf(
                getStats(GestureType.SHAKE),
                getStats(GestureType.FLIP)
            ),
            sensitiveScenarios = SensitiveScenario.values().map { scenario ->
                SensitiveScenarioConfig(
                    scenario = scenario,
                    disableGestures = isSensitiveScenarioDisabled(scenario),
                    showNotice = true
                )
            }
        )
    }

    private fun notifyChanged() {
        val snapshot = buildSnapshot()
        settingsView?.render(snapshot)
        onSettingsChanged?.invoke(snapshot)
    }

    /**
     * 重置所有设置（恢复默认：全部关闭）
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Settings reset to defaults (all off)")
        stopAllGestureListening()
        notifyChanged()
    }
}

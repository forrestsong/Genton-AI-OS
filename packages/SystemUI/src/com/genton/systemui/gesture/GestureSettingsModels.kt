package com.genton.systemui.gesture

/**
 * D9.2 姿态交互设置与授权 - 数据模型
 *
 * 核心数据结构：
 * 1. 逐项 opt-in 开关配置
 * 2. 录音授权状态
 * 3. 误触发统计数据
 * 4. 敏感场景禁用规则
 *
 * 对齐 D9 §8.3 安全约束：
 * - 所有姿态交互默认关闭（opt-in）
 * - 录音功能需要独立授权
 * - 误触发统计透明可见
 */

/**
 * 姿态类型枚举
 */
enum class GestureType(val displayName: String) {
    SHAKE("摇一摇"),
    FLIP("翻面备忘"),
    SWIPE("划动手势")  // 预留
}

/**
 * 姿态功能配置
 */
data class GestureFeatureConfig(
    val gestureType: GestureType,
    val enabled: Boolean,
    val requiresRecordingPermission: Boolean,  // 是否需要录音权限
    val description: String
)

/**
 * 录音授权状态
 */
data class RecordingPermissionState(
    val granted: Boolean,
    val grantedAt: Long,  // 授权时间戳
    val canRevoke: Boolean  // 是否可撤销
)

/**
 * 误触发统计数据
 */
data class FalseActivationStats(
    val gestureType: GestureType,
    val totalActivations: Int,       // 总激活次数
    val userCancelledCount: Int,     // 用户取消次数（3s 窗口内取消）
    val falsePositiveRate: Float,    // 误触发率（0.0 - 1.0）
    val lastActivationTime: Long     // 最后激活时间
)

/**
 * 敏感场景枚举
 */
enum class SensitiveScenario(val displayName: String) {
    PHONE_CALL("通话中"),
    SCREEN_LOCKED("锁屏状态"),
    CAMERA_ACTIVE("相机使用中"),
    PRESENTATION_MODE("演示模式"),
    DND_MODE("勿扰模式")
}

/**
 * 敏感场景禁用配置
 */
data class SensitiveScenarioConfig(
    val scenario: SensitiveScenario,
    val disableGestures: Boolean,  // 是否禁用姿态交互
    val showNotice: Boolean        // 是否显示禁用提示
)

/**
 * 姿态交互总开关状态
 */
data class GestureMasterSwitchState(
    val enabled: Boolean,
    val canToggle: Boolean,
    val disabledReason: String?  // 禁用原因（如果不可切换）
)

/**
 * 完整设置快照
 */
data class GestureSettingsSnapshot(
    val masterSwitch: GestureMasterSwitchState,
    val featureConfigs: List<GestureFeatureConfig>,
    val recordingPermission: RecordingPermissionState,
    val falseActivationStats: List<FalseActivationStats>,
    val sensitiveScenarios: List<SensitiveScenarioConfig>
)

package com.ainative.brain.prediction

/**
 * 动作空间定义
 * 
 * 定义 RL 预测模型的所有可执行动作类型、目标空间和约束条件。
 * 
 * 动作类型：
 * - PREWARM_APP: 预热 App 进程（白名单内）
 * - PRECREATE_ACTIVITY: 预创建目标 Activity
 * - PREFETCH_DATA: 预取页面数据到缓存
 * - PRELOAD_MACRO: 预装载宏/DMI 定义
 * - PRESUBSCRIBE_UI: 预订阅 UI 树增量
 * - NO_ACTION: 不执行预取
 * 
 * @author AI Native OS Team
 * @since A5
 */

/**
 * 预取动作类型
 */
enum class PrefetchActionType(
    val riskLevel: RiskLevel,
    val requiresToken: Boolean,
    val recyclable: Boolean
) {
    /** 预热 App 进程 */
    PREWARM_APP(RiskLevel.LOW, requiresToken = true, recyclable = true),
    
    /** 预创建 Activity */
    PRECREATE_ACTIVITY(RiskLevel.MEDIUM, requiresToken = true, recyclable = true),
    
    /** 预取数据 */
    PREFETCH_DATA(RiskLevel.LOW, requiresToken = false, recyclable = true),
    
    /** 预装载宏/DMI */
    PRELOAD_MACRO(RiskLevel.LOW, requiresToken = false, recyclable = true),
    
    /** 预订阅 UI 树 */
    PRESUBSCRIBE_UI(RiskLevel.LOW, requiresToken = false, recyclable = true),
    
    /** 不执行 */
    NO_ACTION(RiskLevel.NONE, requiresToken = false, recyclable = false);
}

/**
 * 风险级别
 */
enum class RiskLevel {
    NONE,    // 无风险
    LOW,     // 低风险
    MEDIUM,  // 中风险
    HIGH     // 高风险（当前未使用）
}

/**
 * 预测动作
 */
data class PredictedAction(
    val actionType: PrefetchActionType,
    val targetApp: String?,           // 目标 App packageName
    val targetActivity: String?,      // 目标 Activity 类名
    val targetResource: String?,      // 目标资源 (数据 URL/宏 ID)
    val confidence: Float,            // 置信度 [0, 1]
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /** 创建 NO_ACTION */
        fun noAction(): PredictedAction {
            return PredictedAction(
                actionType = PrefetchActionType.NO_ACTION,
                targetApp = null,
                targetActivity = null,
                targetResource = null,
                confidence = 1.0f
            )
        }
    }
    
    /** 是否为有效动作（非 NO_ACTION） */
    val isValid: Boolean
        get() = actionType != PrefetchActionType.NO_ACTION
    
    /** 获取内部动作名称 */
    fun getInternalActionName(): String {
        return when (actionType) {
            PrefetchActionType.PREWARM_APP -> "sys.prewarm_app"
            PrefetchActionType.PRECREATE_ACTIVITY -> "sys.prewarm_app"  // 复用同一动作
            PrefetchActionType.PREFETCH_DATA -> "sys.prefetch_data"
            PrefetchActionType.PRELOAD_MACRO -> "sys.preload_macro"
            PrefetchActionType.PRESUBSCRIBE_UI -> "sys.presubscribe_ui"
            PrefetchActionType.NO_ACTION -> ""
        }
    }
}

/**
 * 动作空间管理器
 * 
 * 管理可预取的目标 App 白名单、动作编码/解码
 */
class ActionSpaceManager(
    private val whitelistApps: Set<String>
) {
    companion object {
        // 动作空间大小：5 种动作类型 × ~20 个白名单 App
        const val ACTION_SPACE_SIZE = 100
        
        // 预定义的系统 App 白名单
        val SYSTEM_APPS = setOf(
            "com.android.dialer",          // 拨号
            "com.android.contacts",        // 联系人
            "com.android.mms",             // 短信
            "com.android.settings",        // 设置
            "com.android.calendar",        // 日历
            "com.android.browser",         // 浏览器
            "com.android.camera",          // 相机
            "com.android.gallery",         // 图库
            "com.android.deskclock"        // 时钟
        )
        
        // 常用工具 App 白名单（示例）
        val COMMON_APPS = setOf(
            "com.google.android.apps.maps",    // 地图
            "com.android.chrome",              // Chrome
            "com.whatsapp",                    // WhatsApp
            "com.tencent.mm",                  // 微信
            "com.alibaba.android.rimet"        // 钉钉
        )
    }
    
    /**
     * 检查 App 是否在白名单内
     */
    fun isWhitelisted(packageName: String): Boolean {
        return packageName in whitelistApps ||
               packageName in SYSTEM_APPS ||
               packageName in COMMON_APPS
    }
    
    /**
     * 将动作索引解码为预测动作
     * 
     * 编码规则：
     * - 索引 0: NO_ACTION
     * - 索引 1-20: PREWARM_APP for 白名单 App[0-19]
     * - 索引 21-40: PRECREATE_ACTIVITY for 白名单 App[0-19]
     * - 索引 41-60: PREFETCH_DATA for 白名单 App[0-19]
     * - 索引 61-80: PRELOAD_MACRO for 白名单 App[0-19]
     * - 索引 81-100: PRESUBSCRIBE_UI for 白名单 App[0-19]
     */
    fun decodeAction(actionIndex: Int, confidence: Float): PredictedAction {
        if (actionIndex == 0 || actionIndex >= ACTION_SPACE_SIZE) {
            return PredictedAction.noAction()
        }
        
        val appsArray = whitelistApps.toList()
        val actionTypeIndex = (actionIndex - 1) / 20
        val appIndex = (actionIndex - 1) % 20
        
        if (appIndex >= appsArray.size) {
            return PredictedAction.noAction()
        }
        
        val actionType = when (actionTypeIndex) {
            0 -> PrefetchActionType.PREWARM_APP
            1 -> PrefetchActionType.PRECREATE_ACTIVITY
            2 -> PrefetchActionType.PREFETCH_DATA
            3 -> PrefetchActionType.PRELOAD_MACRO
            4 -> PrefetchActionType.PRESUBSCRIBE_UI
            else -> return PredictedAction.noAction()
        }
        
        val targetApp = appsArray[appIndex]
        
        return PredictedAction(
            actionType = actionType,
            targetApp = targetApp,
            targetActivity = null,  // 需要从配置或历史获取
            targetResource = null,
            confidence = confidence
        )
    }
    
    /**
     * 将预测动作编码为动作索引
     */
    fun encodeAction(action: PredictedAction): Int {
        if (!action.isValid || action.targetApp == null) {
            return 0
        }
        
        val appsArray = whitelistApps.toList()
        val appIndex = appsArray.indexOf(action.targetApp)
        if (appIndex < 0) {
            return 0
        }
        
        val actionTypeIndex = when (action.actionType) {
            PrefetchActionType.PREWARM_APP -> 0
            PrefetchActionType.PRECREATE_ACTIVITY -> 1
            PrefetchActionType.PREFETCH_DATA -> 2
            PrefetchActionType.PRELOAD_MACRO -> 3
            PrefetchActionType.PRESUBSCRIBE_UI -> 4
            PrefetchActionType.NO_ACTION -> return 0
        }
        
        return 1 + actionTypeIndex * 20 + appIndex
    }
    
    /**
     * 获取所有可能的预测动作（用于探索）
     */
    fun getAllPossibleActions(): List<PredictedAction> {
        val actions = mutableListOf<PredictedAction>()
        
        // NO_ACTION
        actions.add(PredictedAction.noAction())
        
        // 遍历所有白名单 App 和动作类型
        val actionTypes = listOf(
            PrefetchActionType.PREWARM_APP,
            PrefetchActionType.PRECREATE_ACTIVITY,
            PrefetchActionType.PREFETCH_DATA,
            PrefetchActionType.PRELOAD_MACRO,
            PrefetchActionType.PRESUBSCRIBE_UI
        )
        
        whitelistApps.forEach { app ->
            actionTypes.forEach { actionType ->
                actions.add(
                    PredictedAction(
                        actionType = actionType,
                        targetApp = app,
                        targetActivity = null,
                        targetResource = null,
                        confidence = 0.0f
                    )
                )
            }
        }
        
        return actions
    }
}

/**
 * 实际动作（真实发生的用户/Agent 动作）
 * 用于奖励计算和模型训练
 */
data class ActualAction(
    val actionType: String,          // "launch_app" / "open_activity" / "fetch_data" 等
    val targetApp: String?,
    val targetActivity: String?,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 转换为预测动作类型（用于匹配比较）
     */
    fun toPrefetchActionType(): PrefetchActionType {
        return when (actionType) {
            "launch_app", "switch_app" -> PrefetchActionType.PREWARM_APP
            "open_activity", "start_activity" -> PrefetchActionType.PRECREATE_ACTIVITY
            "fetch_data", "load_data" -> PrefetchActionType.PREFETCH_DATA
            "execute_macro", "run_dmi" -> PrefetchActionType.PRELOAD_MACRO
            "read_ui_tree" -> PrefetchActionType.PRESUBSCRIBE_UI
            else -> PrefetchActionType.NO_ACTION
        }
    }
    
    /**
     * 检查是否与预测动作匹配
     */
    fun matches(predicted: PredictedAction): Boolean {
        if (predicted.actionType != toPrefetchActionType()) {
            return false
        }
        
        // 检查目标 App 是否匹配
        if (predicted.targetApp != null && predicted.targetApp != targetApp) {
            return false
        }
        
        // PRECREATE_ACTIVITY 需要 Activity 也匹配
        if (predicted.actionType == PrefetchActionType.PRECREATE_ACTIVITY) {
            if (predicted.targetActivity != null && 
                predicted.targetActivity != targetActivity) {
                return false
            }
        }
        
        return true
    }
}

/**
 * 预取策略配置
 */
data class PrefetchPolicy(
    // 置信度阈值
    val highConfidenceThreshold: Float = 0.9f,   // ≥0.9: 允许预创建
    val mediumConfidenceThreshold: Float = 0.7f, // 0.7-0.9: 仅预取数据
    
    // 资源预算
    val maxConcurrentPrecreates: Int = 3,        // 最多 3 个预创建实例
    val maxMemoryBudgetMb: Int = 200,            // 预创建池最大 200MB
    val maxCpuPercent: Int = 5,                  // 后台 CPU ≤5%
    val maxNetworkMbPerMin: Int = 10,            // 网络预算 ≤10MB/分钟
    
    // 生命周期
    val defaultTtlMs: Long = 60_000L,            // 默认 60 秒过期
    val prewarmTtlMs: Long = 30_000L,            // 预热进程 30 秒
    val prefetchDataTtlMs: Long = 120_000L,      // 预取数据 2 分钟
    
    // 敏感场景检测
    val pauseOnPayment: Boolean = true,          // 支付页暂停
    val pauseOnInput: Boolean = true,            // 输入聚焦暂停
    val pauseOnSecure: Boolean = true,           // 安全窗口暂停
    val pauseOnLowBattery: Boolean = true,       // 低电量暂停（<15%）
    
    // 学习配置
    val enableLocalTraining: Boolean = true,     // 启用本地训练
    val trainingBatchSize: Int = 32,             // 训练批次大小
    val trainingInterval: Long = 86400_000L      // 每天训练一次
) {
    /**
     * 根据置信度判断执行策略
     */
    fun getExecutionStrategy(confidence: Float): ExecutionStrategy {
        return when {
            confidence >= highConfidenceThreshold -> ExecutionStrategy.ALLOW_PRECREATE
            confidence >= mediumConfidenceThreshold -> ExecutionStrategy.ALLOW_PREFETCH_ONLY
            else -> ExecutionStrategy.NO_EXECUTION
        }
    }
}

/**
 * 执行策略
 */
enum class ExecutionStrategy {
    ALLOW_PRECREATE,      // 允许预创建/预热
    ALLOW_PREFETCH_ONLY,  // 仅允许预取数据
    NO_EXECUTION          // 不执行
}

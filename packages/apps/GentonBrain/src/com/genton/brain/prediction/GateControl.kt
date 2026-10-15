package com.genton.brain.prediction

import android.content.Context
import com.genton.context.ContextFrame

/**
 * 门控控制器
 * 
 * 基于置信度和上下文实施预取执行的门控策略：
 * 1. 置信度分档：HIGH (≥0.9) / MEDIUM (0.7-0.9) / LOW (<0.7)
 * 2. 敏感场景检测：支付/输入/安全窗口/通话/低电量
 * 3. 预算检查：内存/CPU/网络预算
 * 
 * @author AI Native OS Team
 * @since A5
 */
class GateControl(
    private val context: Context,
    private val policy: PrefetchPolicy
) {
    /**
     * 判断是否允许执行预取动作
     * 
     * @param predictedAction 预测的动作
     * @param contextFrame 当前上下文
     * @return 门控决策
     */
    fun shouldExecute(
        predictedAction: PredictedAction,
        contextFrame: ContextFrame
    ): GateDecision {
        // 1. 检查是否为有效动作
        if (!predictedAction.isValid) {
            return GateDecision.reject("Invalid action: NO_ACTION")
        }
        
        // 2. 敏感场景检测（优先级最高）
        val sensitiveCheck = checkSensitiveContext(contextFrame)
        if (!sensitiveCheck.allowed) {
            return sensitiveCheck
        }
        
        // 3. 置信度门控
        val confidenceCheck = checkConfidence(predictedAction)
        if (!confidenceCheck.allowed) {
            return confidenceCheck
        }
        
        // 4. 资源预算检查
        val budgetCheck = checkBudget(predictedAction, contextFrame)
        if (!budgetCheck.allowed) {
            return budgetCheck
        }
        
        // 5. 白名单检查（预创建类动作）
        if (predictedAction.actionType in listOf(
                PrefetchActionType.PREWARM_APP,
                PrefetchActionType.PRECREATE_ACTIVITY
            )) {
            val whitelistCheck = checkWhitelist(predictedAction)
            if (!whitelistCheck.allowed) {
                return whitelistCheck
            }
        }
        
        // 全部检查通过
        return GateDecision.allow(
            executionStrategy = policy.getExecutionStrategy(predictedAction.confidence),
            reason = "All checks passed"
        )
    }
    
    // ==================== 检查方法 ====================
    
    /**
     * 敏感场景检测
     */
    private fun checkSensitiveContext(contextFrame: ContextFrame): GateDecision {
        // 1. 支付页面检测
        if (policy.pauseOnPayment && contextFrame.isPaymentPage) {
            return GateDecision.reject("Sensitive: Payment page active")
        }
        
        // 2. 输入聚焦检测
        if (policy.pauseOnInput && contextFrame.isInputFocused) {
            return GateDecision.reject("Sensitive: Input focused")
        }
        
        // 3. 安全窗口检测（FLAG_SECURE）
        if (policy.pauseOnSecure && contextFrame.hasSecureWindow) {
            return GateDecision.reject("Sensitive: Secure window active")
        }
        
        // 4. 通话检测
        if (contextFrame.isInCall) {
            return GateDecision.reject("Sensitive: In call")
        }
        
        // 5. 低电量检测
        if (policy.pauseOnLowBattery && contextFrame.batteryLevel < 15) {
            return GateDecision.reject("Sensitive: Low battery (${contextFrame.batteryLevel}%)")
        }
        
        return GateDecision.allow(
            executionStrategy = ExecutionStrategy.ALLOW_PRECREATE,
            reason = "No sensitive context"
        )
    }
    
    /**
     * 置信度门控
     */
    private fun checkConfidence(predictedAction: PredictedAction): GateDecision {
        val confidence = predictedAction.confidence
        val strategy = policy.getExecutionStrategy(confidence)
        
        return when (strategy) {
            ExecutionStrategy.ALLOW_PRECREATE -> {
                // HIGH: ≥0.9，允许预创建
                GateDecision.allow(
                    executionStrategy = ExecutionStrategy.ALLOW_PRECREATE,
                    reason = "High confidence ($confidence)"
                )
            }
            ExecutionStrategy.ALLOW_PREFETCH_ONLY -> {
                // MEDIUM: 0.7-0.9，仅允许预取数据
                if (predictedAction.actionType in listOf(
                        PrefetchActionType.PREWARM_APP,
                        PrefetchActionType.PRECREATE_ACTIVITY
                    )) {
                    // 降级：预创建 → 仅预取数据
                    GateDecision.allow(
                        executionStrategy = ExecutionStrategy.ALLOW_PREFETCH_ONLY,
                        reason = "Medium confidence ($confidence), downgrade to prefetch only"
                    )
                } else {
                    GateDecision.allow(
                        executionStrategy = ExecutionStrategy.ALLOW_PREFETCH_ONLY,
                        reason = "Medium confidence ($confidence)"
                    )
                }
            }
            ExecutionStrategy.NO_EXECUTION -> {
                // LOW: <0.7，不执行
                GateDecision.reject("Low confidence ($confidence < ${policy.mediumConfidenceThreshold})")
            }
        }
    }
    
    /**
     * 资源预算检查
     */
    private fun checkBudget(
        predictedAction: PredictedAction,
        contextFrame: ContextFrame
    ): GateDecision {
        // 1. 内存预算检查（仅预创建类）
        if (predictedAction.actionType in listOf(
                PrefetchActionType.PREWARM_APP,
                PrefetchActionType.PRECREATE_ACTIVITY
            )) {
            val currentMemoryMb = contextFrame.precreatePoolMemoryMb ?: 0
            if (currentMemoryMb >= policy.maxMemoryBudgetMb) {
                return GateDecision.reject(
                    "Memory budget exceeded ($currentMemoryMb MB / ${policy.maxMemoryBudgetMb} MB)"
                )
            }
        }
        
        // 2. 并发预创建数量检查
        if (predictedAction.actionType in listOf(
                PrefetchActionType.PREWARM_APP,
                PrefetchActionType.PRECREATE_ACTIVITY
            )) {
            val currentCount = contextFrame.precreateCount ?: 0
            if (currentCount >= policy.maxConcurrentPrecreates) {
                return GateDecision.reject(
                    "Concurrent precreate limit ($currentCount / ${policy.maxConcurrentPrecreates})"
                )
            }
        }
        
        // 3. CPU 预算检查（简化版，实际需要测量）
        // TODO: 集成 CPU 使用率监控
        
        // 4. 网络预算检查（预取数据类）
        if (predictedAction.actionType == PrefetchActionType.PREFETCH_DATA) {
            val currentNetworkMb = contextFrame.networkUsageMbPerMin ?: 0.0f
            if (currentNetworkMb >= policy.maxNetworkMbPerMin) {
                return GateDecision.reject(
                    "Network budget exceeded ($currentNetworkMb MB/min / ${policy.maxNetworkMbPerMin} MB/min)"
                )
            }
        }
        
        return GateDecision.allow(
            executionStrategy = ExecutionStrategy.ALLOW_PRECREATE,
            reason = "Budget checks passed"
        )
    }
    
    /**
     * 白名单检查
     */
    private fun checkWhitelist(predictedAction: PredictedAction): GateDecision {
        val targetApp = predictedAction.targetApp ?: return GateDecision.reject("No target app")
        
        // 检查是否在白名单内（依赖 ActionSpaceManager）
        // TODO: 集成白名单服务
        val isWhitelisted = ActionSpaceManager.SYSTEM_APPS.contains(targetApp) ||
                           ActionSpaceManager.COMMON_APPS.contains(targetApp)
        
        return if (isWhitelisted) {
            GateDecision.allow(
                executionStrategy = ExecutionStrategy.ALLOW_PRECREATE,
                reason = "App whitelisted"
            )
        } else {
            GateDecision.reject("App not in whitelist: $targetApp")
        }
    }
}

/**
 * 门控决策
 */
data class GateDecision(
    val allowed: Boolean,
    val executionStrategy: ExecutionStrategy?,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun allow(executionStrategy: ExecutionStrategy, reason: String): GateDecision {
            return GateDecision(
                allowed = true,
                executionStrategy = executionStrategy,
                reason = reason
            )
        }
        
        fun reject(reason: String): GateDecision {
            return GateDecision(
                allowed = false,
                executionStrategy = null,
                reason = reason
            )
        }
    }
}

/**
 * 上下文帧扩展（预测相关字段）
 */
data class ContextFrame(
    // 既有字段（来自 A6.2 上下文服务）
    val foregroundApp: String?,
    val lastInteractionNode: String?,
    val recentEvents: List<EventSummary>?,
    val recentActions: List<ActionSummary>?,
    val currentEntityId: String?,
    val locationHint: String?,
    
    // 敏感场景检测字段
    val isPaymentPage: Boolean = false,
    val isInputFocused: Boolean = false,
    val hasSecureWindow: Boolean = false,
    val isInCall: Boolean = false,
    val batteryLevel: Int = 100,
    
    // 资源预算字段
    val precreatePoolMemoryMb: Int? = null,
    val precreateCount: Int? = null,
    val networkUsageMbPerMin: Float? = null
)

/**
 * 敏感场景检测器
 * 
 * 检测支付页面、输入聚焦、安全窗口等敏感上下文
 */
class SensitiveContextDetector(private val context: Context) {
    /**
     * 检测是否为支付页面
     */
    fun isPaymentPage(contextFrame: ContextFrame): Boolean {
        val foregroundApp = contextFrame.foregroundApp ?: return false
        
        // 检查支付 App 白名单
        val paymentApps = setOf(
            "com.eg.android.AlipayGphone",      // 支付宝
            "com.tencent.mm",                   // 微信支付
            "com.unionpay",                     // 云闪付
            "com.android.vending"               // Google Pay
        )
        
        if (foregroundApp in paymentApps) {
            // 进一步检查是否在支付页面（通过 UI 节点关键字）
            val node = contextFrame.lastInteractionNode ?: ""
            if (node.contains("pay", ignoreCase = true) ||
                node.contains("payment", ignoreCase = true) ||
                node.contains("checkout", ignoreCase = true)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 检测输入框是否聚焦
     */
    fun isInputFocused(contextFrame: ContextFrame): Boolean {
        // TODO: 集成 IME 状态检测
        // 当前占位实现：检查最近交互节点是否为 EditText
        val node = contextFrame.lastInteractionNode ?: return false
        return node.contains("EditText", ignoreCase = true) ||
               node.contains("input", ignoreCase = true)
    }
    
    /**
     * 检测是否有安全窗口（FLAG_SECURE）
     */
    fun hasSecureWindow(contextFrame: ContextFrame): Boolean {
        // TODO: 集成 WindowManager FLAG_SECURE 检测
        // 当前占位实现
        return false
    }
    
    /**
     * 检测是否在通话中
     */
    fun isInCall(contextFrame: ContextFrame): Boolean {
        // TODO: 集成 TelecomManager 通话状态检测
        return false
    }
}

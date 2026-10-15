package com.ainative.brain.prediction.gate

import android.content.Context
import com.ainative.audit.IAgentAuditService
import com.ainative.brain.prediction.*
import com.ainative.context.ContextFrame
import com.ainative.memory.IAgentMemoryService

/**
 * 增强门控控制器（C5.2）
 * 
 * 在 C5.1 基础门控上融合多源信号：
 * 1. 记忆黑名单（优先级 1）
 * 2. 敏感场景检测（优先级 2）
 * 3. 权限状态检查（优先级 3）
 * 4. 动态置信度门控（优先级 4）
 * 5. 预算检查（优先级 5）
 * 
 * 各层信号按优先级仲裁，输出增强决策。
 * 
 * @author AI Native OS Team
 * @since A5
 */
class EnhancedGateControl(
    private val context: Context,
    private val baseGate: GateControl,                          // C5.1 基础门控
    auditService: IAgentAuditService?,                          // 审计服务
    memoryService: IAgentMemoryService?                         // 记忆服务
) {
    // 增强信号层组件
    private val auditSignal: AuditSignalExtractor
    private val permissionChecker: PermissionChecker
    private val blacklistChecker: MemoryBlacklistChecker
    private val policyAdjuster: DynamicPolicyAdjuster
    
    companion object {
        private const val TAG = "EnhancedGateControl"
    }
    
    init {
        auditSignal = AuditSignalExtractor(context, auditService)
        permissionChecker = PermissionChecker(context)
        blacklistChecker = MemoryBlacklistChecker(context, memoryService)
        policyAdjuster = DynamicPolicyAdjuster(context, auditSignal)
    }
    
    /**
     * 增强门控决策
     * 
     * @param predictedAction 预测动作
     * @param contextFrame 当前上下文
     * @return 增强门控决策
     */
    fun shouldExecute(
        predictedAction: PredictedAction,
        contextFrame: ContextFrame
    ): EnhancedGateDecision {
        val signals = mutableListOf<GateSignal>()
        val sceneContext = SceneContext.from(contextFrame)
        
        android.util.Log.d(TAG, "Evaluating enhanced gate for action: ${predictedAction.actionType} app=${predictedAction.targetApp} confidence=${predictedAction.confidence}")
        
        // ==================== 优先级 1：记忆黑名单 ====================
        val blacklistResult = blacklistChecker.isBlacklisted(predictedAction, sceneContext)
        signals.add(GateSignal.Blacklist(blacklistResult))
        
        if (blacklistResult is BlacklistCheckResult.Blocked) {
            return EnhancedGateDecision.reject(
                reason = "User explicitly blocked: ${blacklistResult.entry.reason}",
                signals = signals,
                priority = 1
            )
        }
        
        // ==================== 优先级 2：基础敏感场景检测（C5.1）====================
        val baseDecision = baseGate.shouldExecute(predictedAction, contextFrame)
        signals.add(GateSignal.BaseSensitive(baseDecision))
        
        if (!baseDecision.allowed && baseDecision.reason.contains("Sensitive", ignoreCase = true)) {
            return EnhancedGateDecision.from(baseDecision, signals, priority = 2)
        }
        
        // ==================== 优先级 3：权限检查 ====================
        val permissionResult = permissionChecker.checkAppPermissions(
            predictedAction.targetApp ?: "",
            predictedAction.actionType
        )
        signals.add(GateSignal.Permission(permissionResult))
        
        when (permissionResult) {
            is PermissionCheckResult.Denied -> {
                // 降级策略：预创建降级为仅预热
                return if (predictedAction.actionType == PrefetchActionType.PRECREATE_ACTIVITY) {
                    EnhancedGateDecision.allow(
                        executionStrategy = ExecutionStrategy.ALLOW_PREFETCH_ONLY,
                        reason = "Permission denied (${permissionResult.missingPermissions.size} permissions), downgrade to prewarm",
                        signals = signals,
                        priority = 3
                    )
                } else {
                    EnhancedGateDecision.reject(
                        reason = "Permission denied: ${permissionResult.missingPermissions.joinToString()}",
                        signals = signals,
                        priority = 3
                    )
                }
            }
            is PermissionCheckResult.Unknown -> {
                android.util.Log.w(TAG, "Permission check failed: ${permissionResult.reason}, allowing with caution")
                // 未知状态，谨慎放行
            }
            is PermissionCheckResult.Granted -> {
                // 权限已授予，继续检查
            }
        }
        
        // ==================== 优先级 4：动态置信度门控 ====================
        
        // 4.1 检查是否应该跳过预测（频率限制）
        val shouldSkip = policyAdjuster.shouldSkipPrediction(predictedAction.targetApp ?: "")
        if (shouldSkip) {
            return EnhancedGateDecision.reject(
                reason = "High waste rate or low hit rate, skipping prediction",
                signals = signals,
                priority = 4
            )
        }
        
        // 4.2 动态调整置信度阈值
        val adjustedThreshold = policyAdjuster.adjustThreshold(
            baseThreshold = 0.7f,  // 基础阈值
            packageName = predictedAction.targetApp ?: "",
            context = sceneContext
        )
        signals.add(GateSignal.DynamicThreshold(adjustedThreshold))
        
        if (predictedAction.confidence < adjustedThreshold) {
            return EnhancedGateDecision.reject(
                reason = "Confidence ${predictedAction.confidence} below adjusted threshold $adjustedThreshold",
                signals = signals,
                priority = 4
            )
        }
        
        // 4.3 获取推荐的执行策略
        val recommendation = policyAdjuster.getRecommendedStrategy(
            predictedAction.targetApp ?: "",
            predictedAction.confidence
        )
        signals.add(GateSignal.StrategyRecommendation(recommendation))
        
        // ==================== 优先级 5：预算检查（C5.1）====================
        if (!baseDecision.allowed) {
            return EnhancedGateDecision.from(baseDecision, signals, priority = 5)
        }
        
        // ==================== 全部检查通过 ====================
        return EnhancedGateDecision.allow(
            executionStrategy = recommendation.strategy,
            reason = "All checks passed: ${recommendation.reason}",
            signals = signals,
            priority = 99
        )
    }
    
    /**
     * 获取 App 的历史统计（供调试/UI 展示）
     */
    fun getAppMetrics(packageName: String): AppMetrics {
        return auditSignal.getAppMetrics(packageName)
    }
}

/**
 * 增强门控决策
 */
data class EnhancedGateDecision(
    val allowed: Boolean,
    val executionStrategy: ExecutionStrategy?,
    val reason: String,
    val signals: List<GateSignal>,         // 各检查器信号
    val priority: Int                      // 决策优先级（1 最高，99 = 全部通过）
) {
    companion object {
        fun allow(
            executionStrategy: ExecutionStrategy,
            reason: String,
            signals: List<GateSignal>,
            priority: Int
        ) = EnhancedGateDecision(true, executionStrategy, reason, signals, priority)
        
        fun reject(
            reason: String,
            signals: List<GateSignal>,
            priority: Int
        ) = EnhancedGateDecision(false, null, reason, signals, priority)
        
        fun from(
            baseDecision: GateDecision,
            signals: List<GateSignal>,
            priority: Int
        ) = EnhancedGateDecision(
            baseDecision.allowed,
            baseDecision.executionStrategy,
            baseDecision.reason,
            signals,
            priority
        )
    }
    
    /**
     * 转换为基础门控决策（向下兼容）
     */
    fun toGateDecision(): GateDecision {
        return GateDecision(
            allowed = allowed,
            executionStrategy = executionStrategy,
            reason = reason
        )
    }
}

/**
 * 门控信号
 */
sealed class GateSignal {
    /**
     * 黑名单检查信号
     */
    data class Blacklist(val result: BlacklistCheckResult) : GateSignal() {
        override fun toString(): String {
            return when (result) {
                is BlacklistCheckResult.Allowed -> "Blacklist=Allowed"
                is BlacklistCheckResult.Blocked -> "Blacklist=Blocked(${result.entry.reason})"
            }
        }
    }
    
    /**
     * 基础敏感场景信号
     */
    data class BaseSensitive(val decision: GateDecision) : GateSignal() {
        override fun toString(): String {
            return "BaseSensitive=${if (decision.allowed) "Allowed" else "Rejected(${decision.reason})"}"
        }
    }
    
    /**
     * 权限检查信号
     */
    data class Permission(val result: PermissionCheckResult) : GateSignal() {
        override fun toString(): String {
            return when (result) {
                is PermissionCheckResult.Granted -> "Permission=Granted"
                is PermissionCheckResult.Denied -> "Permission=Denied(${result.missingPermissions.size})"
                is PermissionCheckResult.Unknown -> "Permission=Unknown"
            }
        }
    }
    
    /**
     * 动态阈值信号
     */
    data class DynamicThreshold(val adjustedThreshold: Float) : GateSignal() {
        override fun toString(): String {
            return "DynamicThreshold=$adjustedThreshold"
        }
    }
    
    /**
     * 执行策略推荐信号
     */
    data class StrategyRecommendation(val recommendation: ExecutionStrategyRecommendation) : GateSignal() {
        override fun toString(): String {
            return "Strategy=${recommendation.strategy}"
        }
    }
}

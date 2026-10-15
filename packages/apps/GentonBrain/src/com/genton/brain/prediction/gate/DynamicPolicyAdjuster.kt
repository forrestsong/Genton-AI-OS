package com.genton.brain.prediction.gate

import android.content.Context

/**
 * 动态策略调整器
 * 
 * 基于审计热度信号动态调整门控策略：
 * - 高成功率 App → 降低置信度阈值（更激进）
 * - 低成功率 App → 提高置信度阈值（更保守）
 * - 高成功场景 → 放宽门控
 * - 高价值动作 → 优先预测
 * 
 * @author AI Native OS Team
 * @since A5
 */
class DynamicPolicyAdjuster(
    private val context: Context,
    private val auditSignal: AuditSignalExtractor
) {
    companion object {
        private const val TAG = "DynamicPolicyAdjuster"
        
        // 成功率阈值
        private const val HIGH_SUCCESS_THRESHOLD = 0.8f
        private const val LOW_SUCCESS_THRESHOLD = 0.3f
        
        // 阈值调整幅度
        private const val AGGRESSIVE_ADJUSTMENT = -0.05f  // 降低阈值
        private const val CONSERVATIVE_ADJUSTMENT = 0.1f   // 提高阈值
        private const val SCENE_ADJUSTMENT = -0.03f        // 场景加成
        
        // 阈值边界
        private const val MIN_THRESHOLD = 0.5f
        private const val MAX_THRESHOLD = 0.95f
        
        // 浪费率阈值
        private const val HIGH_WASTE_THRESHOLD = 0.2f     // 20% 浪费率
    }
    
    /**
     * 根据历史成功率动态调整置信度阈值
     * 
     * @param baseThreshold 基础阈值
     * @param packageName App 包名
     * @param context 场景上下文
     * @return 调整后的阈值
     */
    fun adjustThreshold(
        baseThreshold: Float,
        packageName: String,
        context: SceneContext
    ): Float {
        var adjusted = baseThreshold
        
        // 1. App 成功率调整
        val appSuccessRate = auditSignal.getAppSuccessRate(packageName)
        adjusted += when {
            appSuccessRate > HIGH_SUCCESS_THRESHOLD -> {
                // 高成功率：更激进
                android.util.Log.d(TAG, "App $packageName has high success rate $appSuccessRate, lowering threshold")
                AGGRESSIVE_ADJUSTMENT
            }
            appSuccessRate < LOW_SUCCESS_THRESHOLD -> {
                // 低成功率：更保守
                android.util.Log.d(TAG, "App $packageName has low success rate $appSuccessRate, raising threshold")
                CONSERVATIVE_ADJUSTMENT
            }
            else -> 0f
        }
        
        // 2. App 浪费率调整
        val appWasteRate = auditSignal.getAppWasteRate(packageName)
        if (appWasteRate > HIGH_WASTE_THRESHOLD) {
            // 高浪费率：提高阈值
            android.util.Log.d(TAG, "App $packageName has high waste rate $appWasteRate, raising threshold")
            adjusted += CONSERVATIVE_ADJUSTMENT / 2
        }
        
        // 3. 场景成功率调整
        val sceneSuccessRate = auditSignal.getSceneSuccessRate(context.hour, context.location)
        if (sceneSuccessRate > 0.7f) {
            // 高成功场景：放宽门控
            android.util.Log.d(TAG, "Scene (${context.hour}h, ${context.location}) has high success rate $sceneSuccessRate")
            adjusted += SCENE_ADJUSTMENT
        }
        
        // 4. 限制在合理范围
        val finalThreshold = adjusted.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        
        if (finalThreshold != baseThreshold) {
            android.util.Log.i(TAG, "Adjusted threshold for $packageName: $baseThreshold → $finalThreshold")
        }
        
        return finalThreshold
    }
    
    /**
     * 判断是否应该跳过预测（频率限制）
     * 
     * @param packageName App 包名
     * @return 是否应该跳过
     */
    fun shouldSkipPrediction(packageName: String): Boolean {
        // 获取 App 统计
        val metrics = auditSignal.getAppMetrics(packageName)
        
        // 如果浪费率过高且命中率过低，跳过预测
        if (metrics.wasteRate > 0.3f && metrics.hitRate < 0.2f) {
            android.util.Log.i(TAG, "Skip prediction for $packageName: high waste rate ${metrics.wasteRate}, low hit rate ${metrics.hitRate}")
            return true
        }
        
        return false
    }
    
    /**
     * 获取推荐的执行策略
     * 
     * @param packageName App 包名
     * @param confidence 预测置信度
     * @return 执行策略建议
     */
    fun getRecommendedStrategy(
        packageName: String,
        confidence: Float
    ): ExecutionStrategyRecommendation {
        val metrics = auditSignal.getAppMetrics(packageName)
        
        return when {
            // 高成功率 + 高置信度 → 允许预创建
            metrics.hitRate > 0.7f && confidence > 0.85f -> {
                ExecutionStrategyRecommendation(
                    strategy = com.genton.brain.prediction.ExecutionStrategy.ALLOW_PRECREATE,
                    confidence = confidence,
                    reason = "High hit rate (${metrics.hitRate}) and confidence"
                )
            }
            // 中等成功率 或 中等置信度 → 仅预取
            metrics.hitRate > 0.5f || confidence > 0.7f -> {
                ExecutionStrategyRecommendation(
                    strategy = com.genton.brain.prediction.ExecutionStrategy.ALLOW_PREFETCH_ONLY,
                    confidence = confidence,
                    reason = "Medium hit rate (${metrics.hitRate}) or confidence"
                )
            }
            // 低成功率 或 低置信度 → 不执行
            else -> {
                ExecutionStrategyRecommendation(
                    strategy = com.genton.brain.prediction.ExecutionStrategy.NO_EXECUTION,
                    confidence = confidence,
                    reason = "Low hit rate (${metrics.hitRate}) or confidence"
                )
            }
        }
    }
}

/**
 * 执行策略建议
 */
data class ExecutionStrategyRecommendation(
    val strategy: com.genton.brain.prediction.ExecutionStrategy,
    val confidence: Float,
    val reason: String
)

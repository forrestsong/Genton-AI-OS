package com.genton.privacy

import android.content.Context
import android.util.Log

/**
 * PII 掩码统一服务
 * 
 * 职责：
 * 1. 提供统一的 PII 检测与掩码接口
 * 2. 智能选择模型或规则库
 * 3. 性能监控与降级策略
 * 
 * 集成到：
 * - A2.1 IAgentUiTreeService（UI 树 maskPii）
 * - A5.1 IAgentMemoryService（记忆写入前掩码）
 * - A2.3 IAgentNotificationService（通知掩码）
 * 
 * 使用示例：
 * ```kotlin
 * val service = PiiMaskService.getInstance(context)
 * val result = service.maskPii(text, MaskMode.AUTO)
 * ```
 * 
 * @since A3
 */
class PiiMaskService private constructor(context: Context) {
    
    companion object {
        private const val TAG = "PiiMaskService"
        
        @Volatile
        private var instance: PiiMaskService? = null
        
        fun getInstance(context: Context): PiiMaskService {
            return instance ?: synchronized(this) {
                instance ?: PiiMaskService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 掩码模式
     */
    enum class MaskMode {
        AUTO,       // 自动选择（模型优先，失败降级规则库）
        MODEL_ONLY, // 仅使用模型（失败返回原文）
        RULES_ONLY  // 仅使用规则库
    }
    
    private val modelInference: PiiMaskModelInference = PiiMaskModelInference(context)
    private val performanceMonitor = PerformanceMonitor()
    
    init {
        Log.i(TAG, "✅ PII 掩码服务已初始化")
    }
    
    /**
     * 掩码 PII
     * 
     * @param text 原始文本
     * @param mode 掩码模式
     * @param aggressiveMode 激进模式（宁可过掩码）
     * @return 掩码结果
     */
    fun maskPii(
        text: String,
        mode: MaskMode = MaskMode.AUTO,
        aggressiveMode: Boolean = true
    ): PiiMaskRules.MaskResult {
        if (text.isEmpty()) {
            return PiiMaskRules.MaskResult(text, emptyList(), false)
        }
        
        val startTime = System.nanoTime()
        
        val result = when (mode) {
            MaskMode.AUTO -> {
                // 智能降级策略
                if (performanceMonitor.shouldUseModel()) {
                    modelInference.detectAndMask(text, useModel = true)
                } else {
                    Log.d(TAG, "⚠️  模型性能降级，使用规则库")
                    PiiMaskRules.maskText(text, aggressiveMode)
                }
            }
            
            MaskMode.MODEL_ONLY -> {
                modelInference.detectAndMask(text, useModel = true)
            }
            
            MaskMode.RULES_ONLY -> {
                PiiMaskRules.maskText(text, aggressiveMode)
            }
        }
        
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        performanceMonitor.recordLatency(elapsedMs)
        
        if (elapsedMs > 50) {
            Log.w(TAG, "⚠️  掩码延迟过高：${elapsedMs}ms")
        }
        
        return result
    }
    
    /**
     * 批量掩码（优化版）
     */
    fun maskPiiBatch(
        texts: List<String>,
        mode: MaskMode = MaskMode.AUTO
    ): List<PiiMaskRules.MaskResult> {
        return texts.map { maskPii(it, mode) }
    }
    
    /**
     * 获取性能统计
     */
    fun getPerformanceStats(): PerformanceStats {
        return performanceMonitor.getStats()
    }
    
    /**
     * 重置性能监控
     */
    fun resetPerformanceMonitor() {
        performanceMonitor.reset()
    }
    
    /**
     * 释放资源
     */
    fun shutdown() {
        modelInference.close()
        Log.i(TAG, "PII 掩码服务已关闭")
    }
    
    // ==================== 性能监控 ====================
    
    /**
     * 性能监控器
     */
    private class PerformanceMonitor {
        private val latencies = mutableListOf<Long>()
        private var consecutiveSlowCalls = 0
        
        private val maxHistorySize = 100
        private val slowThreshold = 50L  // 50ms
        private val degradeThreshold = 5  // 连续 5 次慢调用降级
        
        @Synchronized
        fun recordLatency(latencyMs: Long) {
            latencies.add(latencyMs)
            
            if (latencies.size > maxHistorySize) {
                latencies.removeAt(0)
            }
            
            if (latencyMs > slowThreshold) {
                consecutiveSlowCalls++
            } else {
                consecutiveSlowCalls = 0
            }
        }
        
        @Synchronized
        fun shouldUseModel(): Boolean {
            // 连续慢调用超过阈值，降级到规则库
            return consecutiveSlowCalls < degradeThreshold
        }
        
        @Synchronized
        fun getStats(): PerformanceStats {
            if (latencies.isEmpty()) {
                return PerformanceStats(0.0, 0.0, 0L, 0L, 0)
            }
            
            val sorted = latencies.sorted()
            return PerformanceStats(
                avgLatency = latencies.average(),
                p95Latency = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble(),
                minLatency = sorted.first(),
                maxLatency = sorted.last(),
                sampleCount = latencies.size
            )
        }
        
        @Synchronized
        fun reset() {
            latencies.clear()
            consecutiveSlowCalls = 0
        }
    }
    
    /**
     * 性能统计数据
     */
    data class PerformanceStats(
        val avgLatency: Double,
        val p95Latency: Double,
        val minLatency: Long,
        val maxLatency: Long,
        val sampleCount: Int
    )
}

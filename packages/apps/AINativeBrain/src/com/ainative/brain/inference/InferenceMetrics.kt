package com.ainative.brain.inference

import android.os.SystemClock

/**
 * 推理用量指标
 * 
 * 记录单次推理调用的资源使用情况：
 * - Token 使用（输入/输出）
 * - Energy 消耗（微焦耳）
 * - 延迟（毫秒）
 * - 档位（本地 tiny/large/云端）
 */
data class InferenceMetrics(
    /** 推理请求 ID */
    val requestId: String,
    
    /** Agent ID */
    val agentId: String,
    
    /** 会话 ID */
    val sessionId: String?,
    
    /** 推理档位 */
    val tier: InferenceTier,
    
    /** 模型名称 */
    val modelName: String,
    
    /** 输入 Token 数 */
    var inputTokens: Int = 0,
    
    /** 输出 Token 数 */
    var outputTokens: Int = 0,
    
    /** 缓存命中 Token 数（Prefix 缓存） */
    var cachedTokens: Int = 0,
    
    /** Energy 消耗（微焦耳） */
    var energyMicroJoules: Long = 0L,
    
    /** 开始时间（毫秒） */
    val startTimeMs: Long = SystemClock.elapsedRealtime(),
    
    /** 结束时间（毫秒） */
    var endTimeMs: Long = 0L,
    
    /** 首 Token 延迟（毫秒，TTFT） */
    var timeToFirstTokenMs: Long = 0L,
    
    /** 成本（微分） */
    var costMicros: Long = 0L,
    
    /** 是否成功 */
    var success: Boolean = true,
    
    /** 错误信息 */
    var errorMessage: String? = null,
    
    /** 签名（防虚报） */
    var signature: String? = null,
    
    /** 时间戳（防篡改） */
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 总 Token 数
     */
    fun totalTokens(): Int = inputTokens + outputTokens
    
    /**
     * 实际计算 Token 数（不含缓存）
     */
    fun computedTokens(): Int = inputTokens + outputTokens - cachedTokens
    
    /**
     * 总延迟（毫秒）
     */
    fun totalLatencyMs(): Long {
        return if (endTimeMs > 0) endTimeMs - startTimeMs else 0L
    }
    
    /**
     * 吞吐量（tokens/秒）
     */
    fun throughputTokensPerSec(): Double {
        val latency = totalLatencyMs()
        return if (latency > 0) {
            outputTokens * 1000.0 / latency
        } else {
            0.0
        }
    }
    
    /**
     * 完成推理（更新结束时间）
     */
    fun complete() {
        if (endTimeMs == 0L) {
            endTimeMs = SystemClock.elapsedRealtime()
        }
    }
    
    /**
     * 标记失败
     */
    fun fail(error: String) {
        success = false
        errorMessage = error
        complete()
    }
}

/**
 * 推理档位
 */
enum class InferenceTier(val value: Int) {
    /** 常驻小模型（<2W 功耗） */
    LOCAL_TINY(0),
    
    /** 端侧大模型（NPU 推理） */
    LOCAL_LARGE(1),
    
    /** 云端推理 */
    CLOUD(2);
    
    companion object {
        fun fromValue(value: Int): InferenceTier {
            return values().find { it.value == value } ?: LOCAL_TINY
        }
    }
}

/**
 * 聚合指标（会话级别）
 */
data class AggregatedMetrics(
    /** 会话 ID */
    val sessionId: String,
    
    /** Agent ID */
    val agentId: String,
    
    /** 总推理次数 */
    var totalInferences: Int = 0,
    
    /** 总输入 Token */
    var totalInputTokens: Int = 0,
    
    /** 总输出 Token */
    var totalOutputTokens: Int = 0,
    
    /** 总缓存命中 Token */
    var totalCachedTokens: Int = 0,
    
    /** 总 Energy 消耗（微焦耳） */
    var totalEnergyMicroJoules: Long = 0L,
    
    /** 总成本（微分） */
    var totalCostMicros: Long = 0L,
    
    /** 总延迟（毫秒） */
    var totalLatencyMs: Long = 0L,
    
    /** 成功次数 */
    var successCount: Int = 0,
    
    /** 失败次数 */
    var failureCount: Int = 0,
    
    /** 档位使用统计 */
    val tierUsage: MutableMap<InferenceTier, Int> = mutableMapOf(),
    
    /** 开始时间 */
    val startTime: Long = System.currentTimeMillis(),
    
    /** 最后更新时间 */
    var lastUpdateTime: Long = startTime
) {
    /**
     * 合并单次指标
     */
    fun merge(metrics: InferenceMetrics) {
        totalInferences++
        totalInputTokens += metrics.inputTokens
        totalOutputTokens += metrics.outputTokens
        totalCachedTokens += metrics.cachedTokens
        totalEnergyMicroJoules += metrics.energyMicroJoules
        totalCostMicros += metrics.costMicros
        totalLatencyMs += metrics.totalLatencyMs()
        
        if (metrics.success) {
            successCount++
        } else {
            failureCount++
        }
        
        tierUsage[metrics.tier] = (tierUsage[metrics.tier] ?: 0) + 1
        lastUpdateTime = System.currentTimeMillis()
    }
    
    /**
     * 总 Token 数
     */
    fun totalTokens(): Int = totalInputTokens + totalOutputTokens
    
    /**
     * 平均延迟（毫秒）
     */
    fun averageLatencyMs(): Long {
        return if (totalInferences > 0) totalLatencyMs / totalInferences else 0L
    }
    
    /**
     * 缓存命中率
     */
    fun cacheHitRate(): Double {
        val total = totalInputTokens + totalOutputTokens
        return if (total > 0) totalCachedTokens.toDouble() / total else 0.0
    }
    
    /**
     * 成功率
     */
    fun successRate(): Double {
        return if (totalInferences > 0) {
            successCount.toDouble() / totalInferences
        } else {
            0.0
        }
    }
}

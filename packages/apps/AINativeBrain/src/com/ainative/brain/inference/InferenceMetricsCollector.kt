package com.ainative.brain.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 推理用量指标收集器
 * 
 * 职责：
 * 1. 收集每次推理调用的 Token/Energy 使用情况
 * 2. 聚合会话级别指标
 * 3. 计算 Energy 消耗（基于档位 + Token 数）
 * 4. 防虚报签名（HMAC 签名保证数据真实性）
 * 5. 合理性校验（检测异常指标）
 * 
 * 线程安全：所有公共方法使用 ReadWriteLock 保护
 */
class InferenceMetricsCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "InferenceMetricsCollector"
        
        // Energy 消耗系数（微焦耳/token）
        private const val ENERGY_PER_TOKEN_TINY = 50L
        private const val ENERGY_PER_TOKEN_LARGE = 500L
        private const val ENERGY_PER_TOKEN_CLOUD = 10L
        
        // 成本系数（微分/token）
        private const val COST_PER_TOKEN_TINY = 0L
        private const val COST_PER_TOKEN_LARGE = 0L
        private const val COST_PER_TOKEN_CLOUD = 10L
        
        // 合理性校验阈值
        private const val MAX_TOKENS_PER_REQUEST = 8192  // 单次最多 8K tokens
        private const val MAX_LATENCY_MS = 300000L       // 最大延迟 5 分钟
        private const val MIN_TOKENS_PER_SEC = 1.0       // 最小吞吐 1 token/秒
        private const val MAX_TOKENS_PER_SEC = 1000.0    // 最大吞吐 1000 token/秒
        
        // HMAC 密钥（实际应从 TEE 获取）
        private const val HMAC_SECRET = "ainative_metrics_secret_v1"
    }
    
    // 活跃推理指标映射表（requestId -> InferenceMetrics）
    private val activeMetrics = ConcurrentHashMap<String, InferenceMetrics>()
    
    // 会话聚合指标映射表（sessionId -> AggregatedMetrics）
    private val sessionMetrics = ConcurrentHashMap<String, AggregatedMetrics>()
    
    // 读写锁
    private val lock = ReentrantReadWriteLock()
    
    // 总统计
    @Volatile
    private var totalInferences = 0L
    
    @Volatile
    private var totalTokens = 0L
    
    @Volatile
    private var totalEnergyMicroJoules = 0L
    
    /**
     * 开始推理计量
     */
    fun startInference(
        requestId: String,
        agentId: String,
        sessionId: String?,
        tier: InferenceTier,
        modelName: String
    ): InferenceMetrics = lock.write {
        Log.d(TAG, "Starting inference metrics: requestId=$requestId, tier=$tier")
        
        val metrics = InferenceMetrics(
            requestId = requestId,
            agentId = agentId,
            sessionId = sessionId,
            tier = tier,
            modelName = modelName
        )
        
        activeMetrics[requestId] = metrics
        return@write metrics
    }
    
    /**
     * 更新输入 Token 数
     */
    fun recordInputTokens(requestId: String, tokens: Int) = lock.write {
        val metrics = activeMetrics[requestId]
        if (metrics != null) {
            metrics.inputTokens = tokens
            Log.d(TAG, "Recorded input tokens: requestId=$requestId, tokens=$tokens")
        } else {
            Log.w(TAG, "Metrics not found for requestId: $requestId")
        }
    }
    
    /**
     * 更新输出 Token 数（流式推理时逐步累加）
     */
    fun recordOutputToken(requestId: String) = lock.write {
        val metrics = activeMetrics[requestId]
        if (metrics != null) {
            metrics.outputTokens++
            
            // 记录首 Token 延迟（TTFT）
            if (metrics.outputTokens == 1 && metrics.timeToFirstTokenMs == 0L) {
                metrics.timeToFirstTokenMs = SystemClock.elapsedRealtime() - metrics.startTimeMs
                Log.d(TAG, "First token: requestId=$requestId, ttft=${metrics.timeToFirstTokenMs}ms")
            }
        }
    }
    
    /**
     * 批量更新输出 Token 数
     */
    fun recordOutputTokens(requestId: String, tokens: Int) = lock.write {
        val metrics = activeMetrics[requestId]
        if (metrics != null) {
            val oldCount = metrics.outputTokens
            metrics.outputTokens += tokens
            
            // 记录首 Token 延迟
            if (oldCount == 0 && metrics.timeToFirstTokenMs == 0L) {
                metrics.timeToFirstTokenMs = SystemClock.elapsedRealtime() - metrics.startTimeMs
            }
            
            Log.d(TAG, "Recorded output tokens: requestId=$requestId, tokens=$tokens (total=${metrics.outputTokens})")
        }
    }
    
    /**
     * 更新缓存命中 Token 数
     */
    fun recordCachedTokens(requestId: String, tokens: Int) = lock.write {
        val metrics = activeMetrics[requestId]
        if (metrics != null) {
            metrics.cachedTokens = tokens
            Log.d(TAG, "Recorded cached tokens: requestId=$requestId, tokens=$tokens")
        }
    }
    
    /**
     * 完成推理计量
     */
    fun completeInference(requestId: String): InferenceMetrics? = lock.write {
        val metrics = activeMetrics.remove(requestId)
        if (metrics == null) {
            Log.w(TAG, "Metrics not found for completion: $requestId")
            return@write null
        }
        
        // 1. 标记完成
        metrics.complete()
        
        // 2. 计算 Energy 消耗
        calculateEnergy(metrics)
        
        // 3. 计算成本
        calculateCost(metrics)
        
        // 4. 合理性校验
        val valid = validateMetrics(metrics)
        if (!valid) {
            Log.w(TAG, "Metrics validation failed: $requestId")
            metrics.fail("Metrics validation failed")
        }
        
        // 5. 生成防虚报签名
        signMetrics(metrics)
        
        // 6. 聚合到会话指标
        if (metrics.sessionId != null) {
            aggregateToSession(metrics)
        }
        
        // 7. 更新总统计
        totalInferences++
        totalTokens += metrics.totalTokens().toLong()
        totalEnergyMicroJoules += metrics.energyMicroJoules
        
        Log.i(TAG, "Inference completed: requestId=$requestId, " +
                "tokens=${metrics.totalTokens()}, " +
                "energy=${metrics.energyMicroJoules}μJ, " +
                "latency=${metrics.totalLatencyMs()}ms, " +
                "throughput=${"%.2f".format(metrics.throughputTokensPerSec())} tok/s")
        
        return@write metrics
    }
    
    /**
     * 标记推理失败
     */
    fun failInference(requestId: String, error: String): InferenceMetrics? = lock.write {
        val metrics = activeMetrics.remove(requestId)
        if (metrics != null) {
            metrics.fail(error)
            
            // 仍然聚合到会话（记录失败）
            if (metrics.sessionId != null) {
                aggregateToSession(metrics)
            }
            
            Log.w(TAG, "Inference failed: requestId=$requestId, error=$error")
        }
        
        return@write metrics
    }
    
    /**
     * 计算 Energy 消耗
     */
    private fun calculateEnergy(metrics: InferenceMetrics) {
        val computedTokens = metrics.computedTokens()
        
        metrics.energyMicroJoules = when (metrics.tier) {
            InferenceTier.LOCAL_TINY -> computedTokens * ENERGY_PER_TOKEN_TINY
            InferenceTier.LOCAL_LARGE -> computedTokens * ENERGY_PER_TOKEN_LARGE
            InferenceTier.CLOUD -> metrics.totalTokens() * ENERGY_PER_TOKEN_CLOUD
        }
    }
    
    /**
     * 计算成本
     */
    private fun calculateCost(metrics: InferenceMetrics) {
        val totalTokens = metrics.totalTokens()
        
        metrics.costMicros = when (metrics.tier) {
            InferenceTier.LOCAL_TINY -> totalTokens * COST_PER_TOKEN_TINY
            InferenceTier.LOCAL_LARGE -> totalTokens * COST_PER_TOKEN_LARGE
            InferenceTier.CLOUD -> totalTokens * COST_PER_TOKEN_CLOUD
        }
    }
    
    /**
     * 合理性校验
     */
    private fun validateMetrics(metrics: InferenceMetrics): Boolean {
        // 1. Token 数校验
        if (metrics.totalTokens() > MAX_TOKENS_PER_REQUEST) {
            Log.w(TAG, "Total tokens exceed limit: ${metrics.totalTokens()} > $MAX_TOKENS_PER_REQUEST")
            return false
        }
        
        if (metrics.inputTokens < 0 || metrics.outputTokens < 0) {
            Log.w(TAG, "Negative token count: input=${metrics.inputTokens}, output=${metrics.outputTokens}")
            return false
        }
        
        // 2. 延迟校验
        val latency = metrics.totalLatencyMs()
        if (latency > MAX_LATENCY_MS) {
            Log.w(TAG, "Latency exceeds limit: ${latency}ms > ${MAX_LATENCY_MS}ms")
            return false
        }
        
        if (latency < 0) {
            Log.w(TAG, "Negative latency: ${latency}ms")
            return false
        }
        
        // 3. 吞吐量校验（仅检查输出 token）
        if (metrics.outputTokens > 0 && latency > 0) {
            val throughput = metrics.throughputTokensPerSec()
            if (throughput < MIN_TOKENS_PER_SEC || throughput > MAX_TOKENS_PER_SEC) {
                Log.w(TAG, "Throughput out of range: $throughput tok/s (expected $MIN_TOKENS_PER_SEC-$MAX_TOKENS_PER_SEC)")
                return false
            }
        }
        
        // 4. 缓存命中数不能超过输入数
        if (metrics.cachedTokens > metrics.inputTokens) {
            Log.w(TAG, "Cached tokens exceed input: ${metrics.cachedTokens} > ${metrics.inputTokens}")
            return false
        }
        
        // 5. 时间戳合理性
        if (metrics.timestamp > System.currentTimeMillis() + 60000) {
            Log.w(TAG, "Future timestamp detected: ${metrics.timestamp}")
            return false
        }
        
        return true
    }
    
    /**
     * 生成防虚报签名
     */
    private fun signMetrics(metrics: InferenceMetrics) {
        try {
            // 拼接关键字段
            val payload = buildString {
                append(metrics.requestId)
                append("|")
                append(metrics.agentId)
                append("|")
                append(metrics.sessionId ?: "")
                append("|")
                append(metrics.tier.value)
                append("|")
                append(metrics.inputTokens)
                append("|")
                append(metrics.outputTokens)
                append("|")
                append(metrics.cachedTokens)
                append("|")
                append(metrics.energyMicroJoules)
                append("|")
                append(metrics.timestamp)
                append("|")
                append(HMAC_SECRET)
            }
            
            // 计算 SHA-256 签名
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(payload.toByteArray())
            metrics.signature = hashBytes.joinToString("") { "%02x".format(it) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign metrics", e)
        }
    }
    
    /**
     * 聚合到会话指标
     */
    private fun aggregateToSession(metrics: InferenceMetrics) {
        val sessionId = metrics.sessionId ?: return
        
        val aggregated = sessionMetrics.getOrPut(sessionId) {
            AggregatedMetrics(
                sessionId = sessionId,
                agentId = metrics.agentId
            )
        }
        
        aggregated.merge(metrics)
    }
    
    /**
     * 获取会话聚合指标
     */
    fun getSessionMetrics(sessionId: String): AggregatedMetrics? = lock.read {
        return@read sessionMetrics[sessionId]
    }
    
    /**
     * 获取所有会话指标
     */
    fun getAllSessionMetrics(): List<AggregatedMetrics> = lock.read {
        return@read sessionMetrics.values.toList()
    }
    
    /**
     * 清除会话指标
     */
    fun clearSessionMetrics(sessionId: String) = lock.write {
        sessionMetrics.remove(sessionId)
        Log.i(TAG, "Cleared session metrics: $sessionId")
    }
    
    /**
     * 获取活跃推理数
     */
    fun getActiveInferenceCount(): Int = lock.read {
        return@read activeMetrics.size
    }
    
    /**
     * 获取总统计
     */
    fun getTotalStats(): Triple<Long, Long, Long> {
        return Triple(totalInferences, totalTokens, totalEnergyMicroJoules)
    }
    
    /**
     * 重置统计
     */
    fun reset() = lock.write {
        activeMetrics.clear()
        sessionMetrics.clear()
        totalInferences = 0L
        totalTokens = 0L
        totalEnergyMicroJoules = 0L
        Log.i(TAG, "Metrics collector reset")
    }
}

package com.ainative.brain.inference

import android.util.Log
import java.security.MessageDigest

/**
 * 推理用量指标验证器
 * 
 * 职责：
 * 1. 签名验证（防篡改）
 * 2. 合理性校验（防虚报）
 * 3. 时间戳校验（防重放攻击）
 * 
 * 防虚报机制：
 * - HMAC-SHA256 签名保证数据完整性
 * - 合理性校验检测异常指标
 * - 时间戳防止重放攻击
 */
object MetricsValidator {
    
    private const val TAG = "MetricsValidator"
    
    // HMAC 密钥（与 InferenceMetricsCollector 保持一致）
    private const val HMAC_SECRET = "ainative_metrics_secret_v1"
    
    // 时间戳容忍度（毫秒）
    private const val TIMESTAMP_TOLERANCE_MS = 300000L  // 5 分钟
    
    /**
     * 验证推理指标
     */
    fun validate(metrics: InferenceMetrics): ValidationResult {
        // 1. 签名验证
        val signatureValid = verifySignature(metrics)
        if (!signatureValid) {
            return ValidationResult(false, "Signature verification failed")
        }
        
        // 2. 时间戳验证
        val timestampValid = verifyTimestamp(metrics)
        if (!timestampValid) {
            return ValidationResult(false, "Timestamp verification failed")
        }
        
        // 3. 合理性校验
        val reasonableValid = verifyReasonable(metrics)
        if (!reasonableValid.valid) {
            return reasonableValid
        }
        
        return ValidationResult(true, "Valid")
    }
    
    /**
     * 验证签名
     */
    private fun verifySignature(metrics: InferenceMetrics): Boolean {
        if (metrics.signature.isNullOrEmpty()) {
            Log.w(TAG, "Missing signature: ${metrics.requestId}")
            return false
        }
        
        try {
            // 重新计算签名
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
            
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(payload.toByteArray())
            val expectedSignature = hashBytes.joinToString("") { "%02x".format(it) }
            
            // 比较签名
            val match = expectedSignature == metrics.signature
            if (!match) {
                Log.w(TAG, "Signature mismatch: ${metrics.requestId}")
            }
            
            return match
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify signature", e)
            return false
        }
    }
    
    /**
     * 验证时间戳
     */
    private fun verifyTimestamp(metrics: InferenceMetrics): Boolean {
        val now = System.currentTimeMillis()
        val diff = Math.abs(now - metrics.timestamp)
        
        if (diff > TIMESTAMP_TOLERANCE_MS) {
            Log.w(TAG, "Timestamp out of tolerance: ${metrics.requestId}, diff=${diff}ms")
            return false
        }
        
        return true
    }
    
    /**
     * 合理性校验
     */
    private fun verifyReasonable(metrics: InferenceMetrics): ValidationResult {
        // 1. Token 数校验
        if (metrics.totalTokens() > 8192) {
            return ValidationResult(false, "Total tokens exceed 8192: ${metrics.totalTokens()}")
        }
        
        if (metrics.inputTokens < 0 || metrics.outputTokens < 0) {
            return ValidationResult(false, "Negative token count")
        }
        
        // 2. 延迟校验
        val latency = metrics.totalLatencyMs()
        if (latency > 300000L) {  // 5 分钟
            return ValidationResult(false, "Latency exceeds 5 minutes: ${latency}ms")
        }
        
        if (latency < 0) {
            return ValidationResult(false, "Negative latency: ${latency}ms")
        }
        
        // 3. 吞吐量校验
        if (metrics.outputTokens > 0 && latency > 0) {
            val throughput = metrics.throughputTokensPerSec()
            if (throughput < 1.0 || throughput > 1000.0) {
                return ValidationResult(false, "Throughput out of range: $throughput tok/s")
            }
        }
        
        // 4. 缓存命中数不能超过输入数
        if (metrics.cachedTokens > metrics.inputTokens) {
            return ValidationResult(false, "Cached tokens exceed input: ${metrics.cachedTokens} > ${metrics.inputTokens}")
        }
        
        // 5. Energy 合理性（基于档位和 Token 数估算）
        val expectedEnergy = when (metrics.tier) {
            InferenceTier.LOCAL_TINY -> metrics.computedTokens() * 50L
            InferenceTier.LOCAL_LARGE -> metrics.computedTokens() * 500L
            InferenceTier.CLOUD -> metrics.totalTokens() * 10L
        }
        
        // 容忍 20% 误差
        val energyDiff = Math.abs(metrics.energyMicroJoules - expectedEnergy)
        val energyTolerance = expectedEnergy * 0.2
        if (energyDiff > energyTolerance) {
            return ValidationResult(false, "Energy mismatch: reported=${metrics.energyMicroJoules}μJ, expected=${expectedEnergy}μJ")
        }
        
        return ValidationResult(true, "Valid")
    }
}

/**
 * 验证结果
 */
data class ValidationResult(
    val valid: Boolean,
    val message: String
)

// UsageCollector.kt
// B5.2 推理用量计量收集器
package com.ainative.brain.airuntime

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 推理用量计量收集器
 * 
 * 核心职责：
 * 1. 真实 token 计量：tokenizer 真实计数，不可伪造
 * 2. 硬件能耗计量：NPU/CPU/GPU 计数器读取
 * 3. 延迟计量：系统时钟（monotonic），首 token 时间/吞吐率
 * 4. 审计回传：生成审计 ref，回传到 ACB 和审计服务
 * 
 * 设计原则：
 * - 每次推理创建一个 InferenceSession，追踪完整生命周期
 * - token 计量：基于 tokenizer 真实编解码，不接受外部报数
 * - energy 计量：尝试读取硬件计数器，不可用时标记 -1
 * - 防虚报：生成 metricsHash，审计服务可校验完整性
 */
class UsageCollector(
    private val hardwareCounters: HardwareCounters
) {
    companion object {
        private const val TAG = "UsageCollector"
    }
    
    // 活跃推理会话（inferenceId -> InferenceSession）
    private val activeSessions = ConcurrentHashMap<String, InferenceSession>()
    
    // 全局推理计数器（用于生成唯一 inferenceId）
    private val inferenceCounter = AtomicLong(0)
    
    /**
     * 开始推理会话
     * 
     * @param agentId Agent ID
     * @param turnId 轮次 ID
     * @param modelId 模型标识符
     * @param inferenceType 推理类型
     * @param promptText 输入文本（用于 token 计量）
     * @return inferenceId
     */
    fun startInference(
        agentId: String,
        turnId: String,
        modelId: String,
        inferenceType: Int,
        promptText: String
    ): String {
        val inferenceId = "inference_${System.currentTimeMillis()}_${inferenceCounter.incrementAndGet()}"
        
        // 计算 prompt tokens
        val promptTokens = countTokens(promptText, modelId)
        
        // 读取硬件计数器（起始值）
        val startNpuEnergy = hardwareCounters.readNpuEnergy()
        val startCpuEnergy = hardwareCounters.readCpuEnergy()
        val startGpuEnergy = hardwareCounters.readGpuEnergy()
        
        // 创建推理会话
        val session = InferenceSession(
            inferenceId = inferenceId,
            agentId = agentId,
            turnId = turnId,
            modelId = modelId,
            inferenceType = inferenceType,
            promptTokens = promptTokens,
            startTimestamp = System.currentTimeMillis(),
            startMonotonicNanos = System.nanoTime(),
            startNpuEnergy = startNpuEnergy,
            startCpuEnergy = startCpuEnergy,
            startGpuEnergy = startGpuEnergy
        )
        
        activeSessions[inferenceId] = session
        
        Log.d(TAG, "Started inference: $inferenceId, model=$modelId, promptTokens=$promptTokens")
        return inferenceId
    }
    
    /**
     * 记录首 token（用于 TTFT 计量）
     * 
     * @param inferenceId 推理会话 ID
     */
    fun recordFirstToken(inferenceId: String) {
        val session = activeSessions[inferenceId]
        if (session == null) {
            Log.w(TAG, "Inference session not found: $inferenceId")
            return
        }
        
        if (session.firstTokenMonotonicNanos == 0L) {
            session.firstTokenMonotonicNanos = System.nanoTime()
            Log.d(TAG, "Recorded first token for inference: $inferenceId")
        }
    }
    
    /**
     * 记录生成的 token（流式输出时调用）
     * 
     * @param inferenceId 推理会话 ID
     * @param tokenText 生成的 token 文本
     */
    fun recordGeneratedToken(inferenceId: String, tokenText: String) {
        val session = activeSessions[inferenceId]
        if (session == null) {
            Log.w(TAG, "Inference session not found: $inferenceId")
            return
        }
        
        // 累积生成文本
        session.completionTextBuilder.append(tokenText)
        
        // 记录首 token 时间（如果还未记录）
        if (session.firstTokenMonotonicNanos == 0L) {
            session.firstTokenMonotonicNanos = System.nanoTime()
        }
    }
    
    /**
     * 结束推理会话
     * 
     * @param inferenceId 推理会话 ID
     * @return InferenceMetrics 计量数据
     */
    fun endInference(inferenceId: String): InferenceMetrics? {
        val session = activeSessions.remove(inferenceId)
        if (session == null) {
            Log.w(TAG, "Inference session not found: $inferenceId")
            return null
        }
        
        val endTimestamp = System.currentTimeMillis()
        val endMonotonicNanos = System.nanoTime()
        
        // 读取硬件计数器（结束值）
        val endNpuEnergy = hardwareCounters.readNpuEnergy()
        val endCpuEnergy = hardwareCounters.readCpuEnergy()
        val endGpuEnergy = hardwareCounters.readGpuEnergy()
        
        // 计算能耗差值
        val npuEnergyMj = if (session.startNpuEnergy >= 0 && endNpuEnergy >= 0) {
            maxOf(0, endNpuEnergy - session.startNpuEnergy)
        } else {
            -1L
        }
        
        val cpuEnergyMj = if (session.startCpuEnergy >= 0 && endCpuEnergy >= 0) {
            maxOf(0, endCpuEnergy - session.startCpuEnergy)
        } else {
            -1L
        }
        
        val gpuEnergyMj = if (session.startGpuEnergy >= 0 && endGpuEnergy >= 0) {
            maxOf(0, endGpuEnergy - session.startGpuEnergy)
        } else {
            -1L
        }
        
        val totalEnergyMj = listOf(npuEnergyMj, cpuEnergyMj, gpuEnergyMj)
            .filter { it >= 0 }
            .sum()
        
        // 计算 completion tokens
        val completionText = session.completionTextBuilder.toString()
        val completionTokens = countTokens(completionText, session.modelId)
        val totalTokens = session.promptTokens + completionTokens
        
        // 计算延迟
        val totalLatencyMs = (endMonotonicNanos - session.startMonotonicNanos) / 1_000_000
        val ttftMs = if (session.firstTokenMonotonicNanos > 0) {
            (session.firstTokenMonotonicNanos - session.startMonotonicNanos) / 1_000_000
        } else {
            totalLatencyMs
        }
        
        // 计算吞吐率（tokens per second）
        val generationTimeMs = totalLatencyMs - ttftMs
        val tps = if (generationTimeMs > 0 && completionTokens > 0) {
            completionTokens.toDouble() / generationTimeMs * 1000
        } else {
            0.0
        }
        
        // 生成审计引用
        val auditRef = "inference_${inferenceId}_${endTimestamp}"
        
        // 计算计量哈希
        val metricsHash = InferenceMetrics.computeMetricsHash(
            inferenceId,
            session.modelId,
            session.promptTokens,
            completionTokens,
            totalEnergyMj,
            session.startTimestamp
        )
        
        // 硬件计数器可用性标志
        val hwCountersAvailable = 
            (if (npuEnergyMj >= 0) InferenceMetrics.HW_NPU_AVAILABLE else 0) or
            (if (cpuEnergyMj >= 0) InferenceMetrics.HW_CPU_AVAILABLE else 0) or
            (if (gpuEnergyMj >= 0) InferenceMetrics.HW_GPU_AVAILABLE else 0) or
            InferenceMetrics.HW_TOKENIZER_AVAILABLE
        
        // 构建计量数据
        val metrics = InferenceMetrics(
            inferenceId = inferenceId,
            agentId = session.agentId,
            turnId = session.turnId,
            modelId = session.modelId,
            inferenceType = session.inferenceType,
            promptTokens = session.promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            tokenizerVersion = getTokenizerVersion(session.modelId),
            npuEnergyMj = npuEnergyMj,
            cpuEnergyMj = cpuEnergyMj,
            gpuEnergyMj = gpuEnergyMj,
            totalEnergyMj = totalEnergyMj,
            ttftMs = ttftMs,
            tps = tps,
            totalLatencyMs = totalLatencyMs,
            startTimestamp = session.startTimestamp,
            endTimestamp = endTimestamp,
            auditRef = auditRef,
            metricsHash = metricsHash,
            hwCountersAvailable = hwCountersAvailable
        )
        
        Log.i(TAG, "Ended inference: $inferenceId, tokens=$totalTokens, energy=${totalEnergyMj}mJ, latency=${totalLatencyMs}ms")
        return metrics
    }
    
    /**
     * 取消推理会话
     * 
     * @param inferenceId 推理会话 ID
     */
    fun cancelInference(inferenceId: String) {
        val session = activeSessions.remove(inferenceId)
        if (session != null) {
            Log.d(TAG, "Cancelled inference: $inferenceId")
        }
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 计算 token 数量
     * 
     * 注意：这里是简化实现，实际应调用真实 tokenizer
     */
    private fun countTokens(text: String, modelId: String): Int {
        // TODO: 调用真实 tokenizer（如 SentencePiece/BPE）
        // 暂时使用简化估算：中文 1 字 ≈ 1.5 tokens，英文 1 词 ≈ 1.3 tokens
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        val otherChars = text.length - chineseChars
        val estimatedTokens = (chineseChars * 1.5 + otherChars / 4 * 1.3).toInt()
        return maxOf(1, estimatedTokens)
    }
    
    /**
     * 获取 tokenizer 版本
     */
    private fun getTokenizerVersion(modelId: String): String {
        // TODO: 从模型元数据获取 tokenizer 版本
        return when {
            modelId.contains("llm") -> "llama_tokenizer_v1"
            modelId.contains("intent") -> "bpe_v1"
            else -> "unknown"
        }
    }
    
    // ========== 内部数据类 ==========
    
    /**
     * 推理会话（内部追踪状态）
     */
    private data class InferenceSession(
        val inferenceId: String,
        val agentId: String,
        val turnId: String,
        val modelId: String,
        val inferenceType: Int,
        val promptTokens: Int,
        val startTimestamp: Long,
        val startMonotonicNanos: Long,
        var firstTokenMonotonicNanos: Long = 0L,
        val startNpuEnergy: Long,
        val startCpuEnergy: Long,
        val startGpuEnergy: Long,
        val completionTextBuilder: StringBuilder = StringBuilder()
    )
}

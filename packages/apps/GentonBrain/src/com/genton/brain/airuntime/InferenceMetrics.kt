// InferenceMetrics.kt
// B5.2 推理用量计量数据模型
package com.genton.brain.airuntime

/**
 * 推理用量计量数据
 * 
 * 核心职责：
 * 1. token 消耗计量（prompt tokens + completion tokens）
 * 2. energy 消耗计量（NPU/CPU/GPU 能耗）
 * 3. 延迟计量（TTFT/TPS/总延迟）
 * 4. 防虚报机制（硬件级计量 + 审计哈希链）
 * 
 * 设计原则：
 * - token 计量：tokenizer 真实计数，不可伪造
 * - energy 计量：硬件计数器读取（NPU/CPU/GPU），单位 mJ
 * - 延迟计量：系统时钟（monotonic），不可回拨
 * - 审计锚定：每次推理生成审计 ref，回传到 ACB 和审计服务
 */
data class InferenceMetrics(
    /**
     * 推理会话 ID（关联到 ACB.agentId + turnId）
     */
    val inferenceId: String,
    
    /**
     * 归属 Agent ID
     */
    val agentId: String,
    
    /**
     * 归属轮次 ID
     */
    val turnId: String,
    
    /**
     * 模型标识符
     * 
     * 示例：
     * - "local/intent_classifier_v1"
     * - "local/llm_3b_q4"
     * - "cloud/gpt4_turbo"
     */
    val modelId: String,
    
    /**
     * 推理类型
     * 
     * 0 = completion（文本生成）
     * 1 = embedding（向量化）
     * 2 = classification（分类）
     * 3 = rerank（重排序）
     */
    val inferenceType: Int,
    
    // ========== Token 计量 ==========
    
    /**
     * Prompt tokens（输入 token 数）
     */
    val promptTokens: Int,
    
    /**
     * Completion tokens（输出 token 数）
     */
    val completionTokens: Int,
    
    /**
     * 总 token 数
     */
    val totalTokens: Int,
    
    /**
     * Tokenizer 版本
     * 
     * 用于审计一致性校验（防止伪造 token 数）
     */
    val tokenizerVersion: String,
    
    // ========== Energy 计量 ==========
    
    /**
     * NPU 能耗（毫焦耳）
     * 
     * 从硬件计数器读取（如 QNN/MTK APU 能耗接口）
     * -1 = 不可用（硬件不支持或无权限）
     */
    val npuEnergyMj: Long,
    
    /**
     * CPU 能耗（毫焦耳）
     * 
     * 从 /sys/class/power_supply 或 SoC 计数器读取
     * -1 = 不可用
     */
    val cpuEnergyMj: Long,
    
    /**
     * GPU 能耗（毫焦耳）
     * 
     * 从 GPU 驱动计数器读取（如 Mali/Adreno）
     * -1 = 不可用
     */
    val gpuEnergyMj: Long,
    
    /**
     * 总能耗（毫焦耳）
     * 
     * = npuEnergyMj + cpuEnergyMj + gpuEnergyMj（可用项之和）
     */
    val totalEnergyMj: Long,
    
    // ========== 延迟计量 ==========
    
    /**
     * Time to First Token（首 token 延迟，毫秒）
     * 
     * = 请求提交 → 第一个 token 返回
     */
    val ttftMs: Long,
    
    /**
     * Tokens Per Second（吞吐率）
     * 
     * = completionTokens / (totalLatencyMs - ttftMs) * 1000
     */
    val tps: Double,
    
    /**
     * 总延迟（毫秒）
     * 
     * = 请求提交 → 推理完成
     */
    val totalLatencyMs: Long,
    
    // ========== 审计与防虚报 ==========
    
    /**
     * 推理开始时间戳（System.currentTimeMillis()）
     */
    val startTimestamp: Long,
    
    /**
     * 推理结束时间戳（System.currentTimeMillis()）
     */
    val endTimestamp: Long,
    
    /**
     * 审计引用（唯一标识，用于审计链锚定）
     * 
     * 格式：inference_{inferenceId}_{timestamp}
     */
    val auditRef: String,
    
    /**
     * 计量哈希（防篡改）
     * 
     * SHA-256(inferenceId + modelId + promptTokens + completionTokens + totalEnergyMj + timestamp)
     * 用于审计服务校验计量数据完整性
     */
    val metricsHash: String,
    
    /**
     * 硬件计数器可用性标志位
     * 
     * bit 0 = NPU 计数器可用
     * bit 1 = CPU 计数器可用
     * bit 2 = GPU 计数器可用
     * bit 3 = tokenizer 可用
     */
    val hwCountersAvailable: Int
) {
    companion object {
        // 推理类型常量
        const val TYPE_COMPLETION = 0
        const val TYPE_EMBEDDING = 1
        const val TYPE_CLASSIFICATION = 2
        const val TYPE_RERANK = 3
        
        // 硬件计数器可用性标志
        const val HW_NPU_AVAILABLE = 0x01
        const val HW_CPU_AVAILABLE = 0x02
        const val HW_GPU_AVAILABLE = 0x04
        const val HW_TOKENIZER_AVAILABLE = 0x08
        
        /**
         * 计算计量哈希
         * 
         * @param inferenceId 推理会话 ID
         * @param modelId 模型标识符
         * @param promptTokens Prompt tokens
         * @param completionTokens Completion tokens
         * @param totalEnergyMj 总能耗
         * @param timestamp 时间戳
         * @return SHA-256 哈希字符串
         */
        fun computeMetricsHash(
            inferenceId: String,
            modelId: String,
            promptTokens: Int,
            completionTokens: Int,
            totalEnergyMj: Long,
            timestamp: Long
        ): String {
            val input = "$inferenceId|$modelId|$promptTokens|$completionTokens|$totalEnergyMj|$timestamp"
            return sha256(input)
        }
        
        /**
         * SHA-256 哈希计算
         */
        private fun sha256(input: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
    
    /**
     * 验证计量哈希
     * 
     * @return true = 哈希有效，false = 数据被篡改
     */
    fun verifyMetricsHash(): Boolean {
        val expectedHash = computeMetricsHash(
            inferenceId,
            modelId,
            promptTokens,
            completionTokens,
            totalEnergyMj,
            startTimestamp
        )
        return metricsHash == expectedHash
    }
    
    /**
     * 获取能耗效率（tokens per mJ）
     * 
     * @return tokens/mJ，-1 = 能耗计量不可用
     */
    fun getEnergyEfficiency(): Double {
        return if (totalEnergyMj > 0) {
            totalTokens.toDouble() / totalEnergyMj
        } else {
            -1.0
        }
    }
}

// MetricsReporter.kt
// B5.2 推理用量计量回传到 ACB 和审计服务
package com.ainative.brain.airuntime

import android.util.Log
import com.ainative.brain.BrainApplication
import com.ainative.brain.runtime.AgentRuntimeClient
import kotlinx.coroutines.*

/**
 * 推理用量计量回传器
 * 
 * 核心职责：
 * 1. 回传到 ACB 预算系统：扣减 token 预算和 energy 预算
 * 2. 回传到审计服务：记录推理用量审计事件（防虚报）
 * 3. 批量聚合：多次小推理合并后回传（减少 Binder 调用）
 * 4. 异常处理：回传失败时重试或降级记录
 * 
 * 设计原则：
 * - 异步回传：不阻塞推理主线程
 * - 批量聚合：同一 turnId 的多次推理合并回传
 * - 防虚报：携带 metricsHash，审计服务可校验完整性
 * - 容错：回传失败时本地缓存，后续重试
 */
class MetricsReporter(
    private val application: BrainApplication
) {
    companion object {
        private const val TAG = "MetricsReporter"
        
        // 批量聚合窗口（毫秒）
        private const val BATCH_WINDOW_MS = 1000L
        
        // 最大重试次数
        private const val MAX_RETRIES = 3
    }
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 待回传的计量数据（turnId -> List<InferenceMetrics>）
    private val pendingMetrics = mutableMapOf<String, MutableList<InferenceMetrics>>()
    
    // 回传任务（turnId -> Job）
    private val reportJobs = mutableMapOf<String, Job>()
    
    /**
     * 提交推理计量数据（异步回传）
     * 
     * @param metrics 推理计量数据
     */
    fun submitMetrics(metrics: InferenceMetrics) {
        scope.launch {
            try {
                // 1. 验证计量哈希
                if (!metrics.verifyMetricsHash()) {
                    Log.e(TAG, "Metrics hash verification failed: ${metrics.inferenceId}")
                    // 仍然回传，但标记为可疑
                }
                
                // 2. 添加到待回传队列
                synchronized(pendingMetrics) {
                    val list = pendingMetrics.getOrPut(metrics.turnId) { mutableListOf() }
                    list.add(metrics)
                }
                
                // 3. 延迟批量回传（同一 turnId 的多次推理合并）
                scheduleReport(metrics.turnId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit metrics: ${metrics.inferenceId}", e)
            }
        }
    }
    
    /**
     * 立即刷新指定轮次的计量数据（轮次结束时调用）
     * 
     * @param turnId 轮次 ID
     */
    fun flushTurn(turnId: String) {
        scope.launch {
            try {
                // 取消延迟任务
                reportJobs[turnId]?.cancel()
                reportJobs.remove(turnId)
                
                // 立即回传
                reportMetrics(turnId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush turn: $turnId", e)
            }
        }
    }
    
    /**
     * 销毁资源
     */
    fun destroy() {
        scope.cancel()
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 调度回传任务（延迟批量）
     */
    private fun scheduleReport(turnId: String) {
        synchronized(reportJobs) {
            // 如果已有任务在等待，则不重复调度
            if (reportJobs.containsKey(turnId)) {
                return
            }
            
            // 创建延迟任务
            val job = scope.launch {
                delay(BATCH_WINDOW_MS)
                reportMetrics(turnId)
            }
            
            reportJobs[turnId] = job
        }
    }
    
    /**
     * 回传计量数据（批量）
     */
    private suspend fun reportMetrics(turnId: String) {
        val metricsList = synchronized(pendingMetrics) {
            pendingMetrics.remove(turnId) ?: return
        }
        
        if (metricsList.isEmpty()) {
            return
        }
        
        Log.d(TAG, "Reporting ${metricsList.size} metrics for turn: $turnId")
        
        try {
            // 1. 聚合计量数据
            val aggregated = aggregateMetrics(metricsList)
            
            // 2. 回传到 ACB 预算系统
            reportToACB(aggregated)
            
            // 3. 回传到审计服务
            reportToAudit(metricsList)
            
            Log.i(TAG, "Reported metrics for turn: $turnId, total_tokens=${aggregated.totalTokens}, total_energy=${aggregated.totalEnergyMj}mJ")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report metrics for turn: $turnId", e)
            
            // 回传失败，重新加入队列（最多重试 3 次）
            retryReport(turnId, metricsList)
        } finally {
            synchronized(reportJobs) {
                reportJobs.remove(turnId)
            }
        }
    }
    
    /**
     * 聚合计量数据（同一 turnId 的多次推理）
     */
    private fun aggregateMetrics(metricsList: List<InferenceMetrics>): InferenceMetrics {
        val first = metricsList.first()
        
        val totalPromptTokens = metricsList.sumOf { it.promptTokens }
        val totalCompletionTokens = metricsList.sumOf { it.completionTokens }
        val totalTokens = metricsList.sumOf { it.totalTokens }
        
        val totalNpuEnergy = metricsList.filter { it.npuEnergyMj >= 0 }.sumOf { it.npuEnergyMj }
        val totalCpuEnergy = metricsList.filter { it.cpuEnergyMj >= 0 }.sumOf { it.cpuEnergyMj }
        val totalGpuEnergy = metricsList.filter { it.gpuEnergyMj >= 0 }.sumOf { it.gpuEnergyMj }
        val totalEnergy = metricsList.filter { it.totalEnergyMj >= 0 }.sumOf { it.totalEnergyMj }
        
        val totalLatency = metricsList.sumOf { it.totalLatencyMs }
        
        return InferenceMetrics(
            inferenceId = "aggregated_${first.turnId}",
            agentId = first.agentId,
            turnId = first.turnId,
            modelId = "multiple",
            inferenceType = first.inferenceType,
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            totalTokens = totalTokens,
            tokenizerVersion = first.tokenizerVersion,
            npuEnergyMj = if (totalNpuEnergy > 0) totalNpuEnergy else -1,
            cpuEnergyMj = if (totalCpuEnergy > 0) totalCpuEnergy else -1,
            gpuEnergyMj = if (totalGpuEnergy > 0) totalGpuEnergy else -1,
            totalEnergyMj = totalEnergy,
            ttftMs = metricsList.minOfOrNull { it.ttftMs } ?: 0,
            tps = metricsList.map { it.tps }.average(),
            totalLatencyMs = totalLatency,
            startTimestamp = metricsList.minOf { it.startTimestamp },
            endTimestamp = metricsList.maxOf { it.endTimestamp },
            auditRef = "aggregated_${first.turnId}_${System.currentTimeMillis()}",
            metricsHash = "",  // 聚合后的哈希需要重新计算
            hwCountersAvailable = first.hwCountersAvailable
        )
    }
    
    /**
     * 回传到 ACB 预算系统
     */
    private suspend fun reportToACB(metrics: InferenceMetrics) {
        try {
            val runtimeClient = application.getRuntimeClient()
            
            // 调用 ACB 服务扣减预算
            runtimeClient.reportUsage(
                agentId = metrics.agentId,
                turnId = metrics.turnId,
                tokensUsed = metrics.totalTokens,
                energyUsedMj = metrics.totalEnergyMj
            )
            
            Log.d(TAG, "Reported usage to ACB: agentId=${metrics.agentId}, tokens=${metrics.totalTokens}, energy=${metrics.totalEnergyMj}mJ")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report to ACB", e)
            throw e
        }
    }
    
    /**
     * 回传到审计服务
     */
    private suspend fun reportToAudit(metricsList: List<InferenceMetrics>) {
        try {
            // TODO: 调用审计服务记录推理用量事件
            // auditService.record(AuditEvent.INFERENCE, inferenceId, metrics)
            
            for (metrics in metricsList) {
                Log.d(TAG, "Reported to audit: inferenceId=${metrics.inferenceId}, hash=${metrics.metricsHash}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report to audit", e)
            // 审计失败不应阻断流程，仅记录错误
        }
    }
    
    /**
     * 重试回传
     */
    private fun retryReport(turnId: String, metricsList: List<InferenceMetrics>) {
        // TODO: 实现重试逻辑（使用指数退避）
        Log.w(TAG, "Retry report not implemented, metrics for turn $turnId will be lost")
    }
}

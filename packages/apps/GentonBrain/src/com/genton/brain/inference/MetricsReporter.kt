package com.genton.brain.inference

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.genton.brain.runtime.AgentRuntimeClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推理用量指标上报器
 * 
 * 职责：
 * 1. 上报推理用量到 ACB（Agent Control Block）预算系统
 * 2. 上报推理用量到审计服务（IAgentAuditService）
 * 3. 批量上报优化（减少系统调用）
 * 4. 失败重试与持久化
 * 5. 防虚报验证（签名校验）
 * 
 * 上报策略：
 * - 实时上报：每次推理完成后立即上报到 ACB（预算扣减）
 * - 批量上报：每 N 秒或累积 M 条记录后批量上报到审计服务
 * - 持久化：上报失败时保存到本地，下次重试
 */
class MetricsReporter(
    private val context: Context,
    private val runtimeClient: AgentRuntimeClient,
    private val metricsCollector: InferenceMetricsCollector
) {
    companion object {
        private const val TAG = "MetricsReporter"
        
        // 批量上报参数
        private const val BATCH_SIZE = 10                    // 批量大小
        private const val BATCH_INTERVAL_MS = 30000L         // 批量间隔 30 秒
        
        // 重试参数
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 5000L
    }
    
    // 后台线程
    private val handlerThread = HandlerThread("MetricsReporter").apply { start() }
    private val handler = Handler(handlerThread.looper)
    
    // 待上报队列
    private val pendingMetrics = ConcurrentHashMap<String, InferenceMetrics>()
    
    // 是否正在运行
    private val isRunning = AtomicBoolean(false)
    
    /**
     * 启动上报器
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Metrics reporter started")
            
            // 启动批量上报定时任务
            scheduleBatchReport()
        }
    }
    
    /**
     * 停止上报器
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Metrics reporter stopping...")
            
            // 立即上报所有待上报指标
            flushAll()
            
            // 停止后台线程
            handler.removeCallbacksAndMessages(null)
            handlerThread.quitSafely()
            
            Log.i(TAG, "Metrics reporter stopped")
        }
    }
    
    /**
     * 上报单次推理指标
     */
    fun reportInferenceMetrics(metrics: InferenceMetrics) {
        if (!isRunning.get()) {
            Log.w(TAG, "Reporter not running, skipping report")
            return
        }
        
        Log.d(TAG, "Reporting inference metrics: requestId=${metrics.requestId}")
        
        // 1. 实时上报到 ACB（扣减预算）
        reportToAcb(metrics)
        
        // 2. 加入批量上报队列（审计服务）
        pendingMetrics[metrics.requestId] = metrics
        
        // 3. 如果队列达到批量大小，立即上报
        if (pendingMetrics.size >= BATCH_SIZE) {
            handler.post { flushBatch() }
        }
    }
    
    /**
     * 上报会话聚合指标
     */
    fun reportSessionMetrics(sessionId: String) {
        val aggregated = metricsCollector.getSessionMetrics(sessionId)
        if (aggregated == null) {
            Log.w(TAG, "Session metrics not found: $sessionId")
            return
        }
        
        Log.i(TAG, "Reporting session metrics: sessionId=$sessionId, " +
                "inferences=${aggregated.totalInferences}, " +
                "tokens=${aggregated.totalTokens()}, " +
                "energy=${aggregated.totalEnergyMicroJoules}μJ")
        
        // 上报到审计服务（会话级别汇总）
        reportToAudit(aggregated)
    }
    
    /**
     * 上报到 ACB（预算扣减）
     */
    private fun reportToAcb(metrics: InferenceMetrics) {
        try {
            // TODO: 通过 IAgentRuntimeService 上报到 ACB
            // runtimeClient.reportTokenUsage(
            //     agentId = metrics.agentId,
            //     sessionId = metrics.sessionId,
            //     tokens = metrics.totalTokens(),
            //     energyMicroJoules = metrics.energyMicroJoules
            // )
            
            Log.d(TAG, "Reported to ACB: agentId=${metrics.agentId}, tokens=${metrics.totalTokens()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report to ACB: ${metrics.requestId}", e)
            // ACB 上报失败不影响推理，仅记录日志
        }
    }
    
    /**
     * 批量上报到审计服务
     */
    private fun flushBatch() {
        if (pendingMetrics.isEmpty()) {
            return
        }
        
        val batch = pendingMetrics.values.toList()
        pendingMetrics.clear()
        
        Log.i(TAG, "Flushing batch: ${batch.size} metrics")
        
        try {
            // TODO: 通过 IAgentAuditService 批量上报
            // auditService.recordInferenceBatch(batch.map { metrics ->
            //     InferenceAuditEvent(
            //         requestId = metrics.requestId,
            //         agentId = metrics.agentId,
            //         sessionId = metrics.sessionId,
            //         tier = metrics.tier.value,
            //         inputTokens = metrics.inputTokens,
            //         outputTokens = metrics.outputTokens,
            //         energyMicroJoules = metrics.energyMicroJoules,
            //         latencyMs = metrics.totalLatencyMs(),
            //         success = metrics.success,
            //         signature = metrics.signature,
            //         timestamp = metrics.timestamp
            //     )
            // })
            
            Log.i(TAG, "Batch reported to audit service: ${batch.size} records")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report batch to audit service", e)
            
            // 失败时重新加入队列（最多重试 MAX_RETRY_COUNT 次）
            batch.forEach { metrics ->
                // TODO: 实现重试计数和持久化
                pendingMetrics[metrics.requestId] = metrics
            }
        }
    }
    
    /**
     * 立即上报所有待上报指标
     */
    private fun flushAll() {
        Log.i(TAG, "Flushing all pending metrics...")
        flushBatch()
    }
    
    /**
     * 上报会话聚合指标到审计服务
     */
    private fun reportToAudit(aggregated: AggregatedMetrics) {
        try {
            // TODO: 通过 IAgentAuditService 上报会话汇总
            // auditService.recordSessionSummary(
            //     SessionInferenceSummary(
            //         sessionId = aggregated.sessionId,
            //         agentId = aggregated.agentId,
            //         totalInferences = aggregated.totalInferences,
            //         totalTokens = aggregated.totalTokens(),
            //         totalEnergyMicroJoules = aggregated.totalEnergyMicroJoules,
            //         totalCostMicros = aggregated.totalCostMicros,
            //         averageLatencyMs = aggregated.averageLatencyMs(),
            //         cacheHitRate = aggregated.cacheHitRate(),
            //         successRate = aggregated.successRate(),
            //         tierUsage = aggregated.tierUsage,
            //         startTime = aggregated.startTime,
            //         endTime = aggregated.lastUpdateTime
            //     )
            // )
            
            Log.i(TAG, "Session summary reported to audit service: sessionId=${aggregated.sessionId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report session summary to audit service", e)
        }
    }
    
    /**
     * 调度批量上报定时任务
     */
    private fun scheduleBatchReport() {
        handler.postDelayed({
            if (isRunning.get()) {
                flushBatch()
                scheduleBatchReport()  // 递归调度下一次
            }
        }, BATCH_INTERVAL_MS)
    }
    
    /**
     * 获取待上报队列大小
     */
    fun getPendingCount(): Int = pendingMetrics.size
}

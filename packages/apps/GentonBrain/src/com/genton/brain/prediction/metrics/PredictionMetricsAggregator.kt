package com.genton.brain.prediction.metrics

import android.content.Context
import com.genton.audit.AuditQuery
import com.genton.audit.IAgentAuditService
import com.genton.brain.prediction.gate.AppMetrics

/**
 * 预测统计聚合器
 * 
 * 从审计服务聚合预测统计数据：
 * - 按 App 聚合
 * - 按场景（时段+位置）聚合
 * - 按动作类型聚合
 * - 门控拒绝原因分布
 * 
 * @author AI Native OS Team
 * @since A5
 */
class PredictionMetricsAggregator(
    private val context: Context,
    private val auditService: IAgentAuditService?
) {
    companion object {
        private const val TAG = "PredictionMetricsAggregator"
        private const val DEFAULT_DAYS = 7
        private const val MS_PER_DAY = 86400000L
    }
    
    /**
     * 聚合全局预测统计
     */
    fun aggregateGlobalMetrics(days: Int = DEFAULT_DAYS): GlobalPredictionMetrics {
        if (auditService == null) {
            return GlobalPredictionMetrics.empty()
        }
        
        try {
            val query = AuditQuery.Builder()
                .setTimeRange(System.currentTimeMillis() - days * MS_PER_DAY, System.currentTimeMillis())
                .build()
            
            val events = auditService.queryEvents(query) ?: emptyList()
            
            val predictionEvents = events.filter { it.type.startsWith("prediction.") }
            
            val totalPredictions = predictionEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_MADE }
            val gatePass = predictionEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_GATE_PASS }
            val gateReject = predictionEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_GATE_REJECT }
            val hits = predictionEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_HIT }
            val misses = predictionEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_MISS }
            val recycled = predictionEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_RECYCLED }
            
            return GlobalPredictionMetrics(
                totalPredictions = totalPredictions,
                gatePassCount = gatePass,
                gateRejectCount = gateReject,
                hitCount = hits,
                missCount = misses,
                recycledCount = recycled,
                gatePassRate = if (totalPredictions > 0) gatePass.toFloat() / totalPredictions else 0f,
                hitRate = if (gatePass > 0) hits.toFloat() / gatePass else 0f,
                wasteRate = if (gatePass > 0) recycled.toFloat() / gatePass else 0f
            )
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to aggregate global metrics", e)
            return GlobalPredictionMetrics.empty()
        }
    }
    
    /**
     * 聚合门控拒绝原因分布
     */
    fun aggregateGateRejectReasons(days: Int = DEFAULT_DAYS): Map<String, Int> {
        if (auditService == null) return emptyMap()
        
        try {
            val query = AuditQuery.Builder()
                .setTimeRange(System.currentTimeMillis() - days * MS_PER_DAY, System.currentTimeMillis())
                .build()
            
            val events = auditService.queryEvents(query) ?: emptyList()
            val rejectEvents = events.filter { it.type == PredictionAuditLogger.EVENT_PREDICTION_GATE_REJECT }
            
            return rejectEvents
                .groupBy { extractGateReason(it.payload) }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .toMap()
                
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to aggregate gate reject reasons", e)
            return emptyMap()
        }
    }
    
    /**
     * 聚合 Top N 预测 App
     */
    fun aggregateTopApps(limit: Int = 10, days: Int = DEFAULT_DAYS): List<AppMetrics> {
        if (auditService == null) return emptyList()
        
        try {
            val query = AuditQuery.Builder()
                .setTimeRange(System.currentTimeMillis() - days * MS_PER_DAY, System.currentTimeMillis())
                .build()
            
            val events = auditService.queryEvents(query) ?: emptyList()
            val predictionEvents = events.filter { it.type.startsWith("prediction.") }
            
            // 按 App 分组
            val appGroups = predictionEvents
                .mapNotNull { extractPackageName(it.payload) }
                .groupBy { it }
            
            // 计算每个 App 的统计
            val appMetrics = appGroups.map { (packageName, _) ->
                calculateAppMetrics(packageName, predictionEvents)
            }
            
            // 按总预测次数排序，取 Top N
            return appMetrics
                .sortedByDescending { it.totalPredictions }
                .take(limit)
                
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to aggregate top apps", e)
            return emptyList()
        }
    }
    
    /**
     * 生成预测报告
     */
    fun generateReport(days: Int = DEFAULT_DAYS): PredictionReport {
        val globalMetrics = aggregateGlobalMetrics(days)
        val rejectReasons = aggregateGateRejectReasons(days)
        val topApps = aggregateTopApps(limit = 10, days = days)
        
        return PredictionReport(
            periodDays = days,
            globalMetrics = globalMetrics,
            topApps = topApps,
            gateRejectReasons = rejectReasons,
            generatedAt = System.currentTimeMillis()
        )
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 从载荷中提取包名
     */
    private fun extractPackageName(payload: String): String? {
        return try {
            val json = org.json.JSONObject(payload)
            json.optString("predictedApp").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 从载荷中提取门控拒绝原因
     */
    private fun extractGateReason(payload: String): String {
        return try {
            val json = org.json.JSONObject(payload)
            json.optString("gateReason", "Unknown")
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * 计算单个 App 的统计
     */
    private fun calculateAppMetrics(
        packageName: String,
        allEvents: List<com.genton.audit.AuditEvent>
    ): AppMetrics {
        val appEvents = allEvents.filter { 
            extractPackageName(it.payload) == packageName
        }
        
        val total = appEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_MADE }
        val gatePass = appEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_GATE_PASS }
        val gateReject = appEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_GATE_REJECT }
        val hits = appEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_HIT }
        val misses = appEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_MISS }
        val recycled = appEvents.count { it.type == PredictionAuditLogger.EVENT_PREDICTION_RECYCLED }
        
        // 计算平均延迟节省
        val hitEvents = appEvents.filter { it.type == PredictionAuditLogger.EVENT_PREDICTION_HIT }
        val avgTimeSaved = if (hitEvents.isNotEmpty()) {
            hitEvents.mapNotNull { extractTimeSaved(it.payload) }.average().toLong()
        } else {
            0L
        }
        
        return AppMetrics(
            packageName = packageName,
            totalPredictions = total,
            gatePassRate = if (total > 0) gatePass.toFloat() / total else 0f,
            gateRejectRate = if (total > 0) gateReject.toFloat() / total else 0f,
            hitRate = if (gatePass > 0) hits.toFloat() / gatePass else 0f,
            wasteRate = if (gatePass > 0) recycled.toFloat() / gatePass else 0f,
            avgTimeSavedMs = avgTimeSaved
        )
    }
    
    /**
     * 从载荷中提取延迟节省
     */
    private fun extractTimeSaved(payload: String): Long? {
        return try {
            val json = org.json.JSONObject(payload)
            if (json.has("timeSavedMs")) json.getLong("timeSavedMs") else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 全局预测统计
 */
data class GlobalPredictionMetrics(
    val totalPredictions: Int,
    val gatePassCount: Int,
    val gateRejectCount: Int,
    val hitCount: Int,
    val missCount: Int,
    val recycledCount: Int,
    val gatePassRate: Float,
    val hitRate: Float,
    val wasteRate: Float
) {
    companion object {
        fun empty() = GlobalPredictionMetrics(
            totalPredictions = 0,
            gatePassCount = 0,
            gateRejectCount = 0,
            hitCount = 0,
            missCount = 0,
            recycledCount = 0,
            gatePassRate = 0f,
            hitRate = 0f,
            wasteRate = 0f
        )
    }
}

/**
 * 预测报告
 */
data class PredictionReport(
    val periodDays: Int,
    val globalMetrics: GlobalPredictionMetrics,
    val topApps: List<AppMetrics>,
    val gateRejectReasons: Map<String, Int>,
    val generatedAt: Long
) {
    /**
     * 生成人类可读的报告文本
     */
    fun toReadableString(): String {
        return buildString {
            appendLine("====== Prediction Report (Last $periodDays days) ======")
            appendLine()
            appendLine("Global Metrics:")
            appendLine("  Total Predictions: ${globalMetrics.totalPredictions}")
            appendLine("  Gate Pass Rate: ${"%.1f".format(globalMetrics.gatePassRate * 100)}%")
            appendLine("  Hit Rate: ${"%.1f".format(globalMetrics.hitRate * 100)}%")
            appendLine("  Waste Rate: ${"%.1f".format(globalMetrics.wasteRate * 100)}%")
            appendLine()
            appendLine("Top Apps:")
            topApps.take(5).forEach { app ->
                appendLine("  ${app.packageName}: ${app.totalPredictions} predictions, ${("%.1f".format(app.hitRate * 100))}% hit rate")
            }
            appendLine()
            appendLine("Gate Reject Reasons:")
            gateRejectReasons.entries.take(5).forEach { (reason, count) ->
                appendLine("  $reason: $count times")
            }
        }
    }
}

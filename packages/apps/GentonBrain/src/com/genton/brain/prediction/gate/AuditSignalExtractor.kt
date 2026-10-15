package com.genton.brain.prediction.gate

import android.content.Context
import com.genton.audit.AuditEvent
import com.genton.audit.AuditQuery
import com.genton.audit.IAgentAuditService

/**
 * 审计热度信号提取器
 * 
 * 从审计服务中提取预测相关的历史热度信号：
 * - App 成功率/失败率
 * - 场景成功率（时段+位置）
 * - 平均延迟节省
 * - 门控拒绝原因分布
 * 
 * @author AI Native OS Team
 * @since A5
 */
class AuditSignalExtractor(
    private val context: Context,
    private val auditService: IAgentAuditService?
) {
    companion object {
        private const val TAG = "AuditSignalExtractor"
        
        // 审计事件类型
        const val EVENT_PREDICTION_MADE = "prediction.made"
        const val EVENT_PREDICTION_GATE_PASS = "prediction.gate_pass"
        const val EVENT_PREDICTION_GATE_REJECT = "prediction.gate_reject"
        const val EVENT_PREDICTION_HIT = "prediction.hit"
        const val EVENT_PREDICTION_MISS = "prediction.miss"
        const val EVENT_PREDICTION_RECYCLED = "prediction.recycled"
        
        // 默认查询时间窗口
        private const val DEFAULT_DAYS = 7
        private const val MS_PER_DAY = 86400000L
        
        // 默认值（无历史数据时）
        private const val DEFAULT_SUCCESS_RATE = 0.5f
        private const val DEFAULT_TIME_SAVED = 0L
    }
    
    /**
     * 获取 App 的历史成功率
     * 
     * @param packageName App 包名
     * @param days 查询天数
     * @return 成功率 [0.0, 1.0]，无数据时返回 0.5
     */
    fun getAppSuccessRate(packageName: String, days: Int = DEFAULT_DAYS): Float {
        if (auditService == null) {
            android.util.Log.w(TAG, "Audit service not available, using default success rate")
            return DEFAULT_SUCCESS_RATE
        }
        
        try {
            val query = buildPredictionQuery(packageName, days)
            val events = auditService.queryEvents(query) ?: emptyList()
            
            val hits = events.count { it.type == EVENT_PREDICTION_HIT }
            val misses = events.count { it.type == EVENT_PREDICTION_MISS }
            val total = hits + misses
            
            return if (total > 0) {
                hits.toFloat() / total
            } else {
                DEFAULT_SUCCESS_RATE
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get app success rate", e)
            return DEFAULT_SUCCESS_RATE
        }
    }
    
    /**
     * 获取 App 的失败率（浪费率）
     */
    fun getAppWasteRate(packageName: String, days: Int = DEFAULT_DAYS): Float {
        if (auditService == null) return 0f
        
        try {
            val query = buildPredictionQuery(packageName, days)
            val events = auditService.queryEvents(query) ?: emptyList()
            
            val recycled = events.count { it.type == EVENT_PREDICTION_RECYCLED }
            val gatePass = events.count { it.type == EVENT_PREDICTION_GATE_PASS }
            
            return if (gatePass > 0) {
                recycled.toFloat() / gatePass
            } else {
                0f
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get app waste rate", e)
            return 0f
        }
    }
    
    /**
     * 获取特定场景的成功率
     * 
     * @param hour 小时 [0, 23]
     * @param location 位置 (home/office/transit/other)
     * @param days 查询天数
     * @return 成功率 [0.0, 1.0]
     */
    fun getSceneSuccessRate(hour: Int, location: String, days: Int = DEFAULT_DAYS): Float {
        if (auditService == null) return DEFAULT_SUCCESS_RATE
        
        try {
            val query = AuditQuery.Builder()
                .setTimeRange(System.currentTimeMillis() - days * MS_PER_DAY, System.currentTimeMillis())
                .build()
            
            val events = auditService.queryEvents(query) ?: emptyList()
            
            // 过滤匹配场景的事件
            val sceneEvents = events.filter { event ->
                matchesScene(event, hour, location)
            }
            
            val hits = sceneEvents.count { it.type == EVENT_PREDICTION_HIT }
            val misses = sceneEvents.count { it.type == EVENT_PREDICTION_MISS }
            val total = hits + misses
            
            return if (total > 0) {
                hits.toFloat() / total
            } else {
                DEFAULT_SUCCESS_RATE
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get scene success rate", e)
            return DEFAULT_SUCCESS_RATE
        }
    }
    
    /**
     * 获取 App 的平均延迟节省
     * 
     * @param packageName App 包名
     * @param days 查询天数
     * @return 平均延迟节省（毫秒）
     */
    fun getAvgTimeSaved(packageName: String, days: Int = DEFAULT_DAYS): Long {
        if (auditService == null) return DEFAULT_TIME_SAVED
        
        try {
            val query = buildPredictionQuery(packageName, days)
            val events = auditService.queryEvents(query) ?: emptyList()
            
            val hitEvents = events.filter { it.type == EVENT_PREDICTION_HIT }
            if (hitEvents.isEmpty()) return DEFAULT_TIME_SAVED
            
            val timeSavedList = hitEvents.mapNotNull { event ->
                extractTimeSaved(event)
            }
            
            return if (timeSavedList.isNotEmpty()) {
                timeSavedList.average().toLong()
            } else {
                DEFAULT_TIME_SAVED
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get avg time saved", e)
            return DEFAULT_TIME_SAVED
        }
    }
    
    /**
     * 获取门控拒绝原因分布
     * 
     * @param days 查询天数
     * @return 原因 -> 次数的映射
     */
    fun getGateRejectReasons(days: Int = DEFAULT_DAYS): Map<String, Int> {
        if (auditService == null) return emptyMap()
        
        try {
            val query = AuditQuery.Builder()
                .setTimeRange(System.currentTimeMillis() - days * MS_PER_DAY, System.currentTimeMillis())
                .build()
            
            val events = auditService.queryEvents(query) ?: emptyList()
            val rejectEvents = events.filter { it.type == EVENT_PREDICTION_GATE_REJECT }
            
            return rejectEvents
                .groupBy { extractGateReason(it) }
                .mapValues { it.value.size }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get gate reject reasons", e)
            return emptyMap()
        }
    }
    
    /**
     * 获取 App 的总体统计
     */
    fun getAppMetrics(packageName: String, days: Int = DEFAULT_DAYS): AppMetrics {
        if (auditService == null) {
            return AppMetrics(
                packageName = packageName,
                totalPredictions = 0,
                gatePassRate = 0f,
                gateRejectRate = 0f,
                hitRate = DEFAULT_SUCCESS_RATE,
                wasteRate = 0f,
                avgTimeSavedMs = DEFAULT_TIME_SAVED
            )
        }
        
        try {
            val query = buildPredictionQuery(packageName, days)
            val events = auditService.queryEvents(query) ?: emptyList()
            
            val total = events.count { it.type == EVENT_PREDICTION_MADE }
            val gatePass = events.count { it.type == EVENT_PREDICTION_GATE_PASS }
            val gateReject = events.count { it.type == EVENT_PREDICTION_GATE_REJECT }
            val hits = events.count { it.type == EVENT_PREDICTION_HIT }
            val misses = events.count { it.type == EVENT_PREDICTION_MISS }
            val recycled = events.count { it.type == EVENT_PREDICTION_RECYCLED }
            
            val hitEvents = events.filter { it.type == EVENT_PREDICTION_HIT }
            val avgTimeSaved = if (hitEvents.isNotEmpty()) {
                hitEvents.mapNotNull { extractTimeSaved(it) }.average().toLong()
            } else {
                DEFAULT_TIME_SAVED
            }
            
            return AppMetrics(
                packageName = packageName,
                totalPredictions = total,
                gatePassRate = if (total > 0) gatePass.toFloat() / total else 0f,
                gateRejectRate = if (total > 0) gateReject.toFloat() / total else 0f,
                hitRate = if (gatePass > 0) hits.toFloat() / gatePass else DEFAULT_SUCCESS_RATE,
                wasteRate = if (gatePass > 0) recycled.toFloat() / gatePass else 0f,
                avgTimeSavedMs = avgTimeSaved
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get app metrics", e)
            return AppMetrics(
                packageName = packageName,
                totalPredictions = 0,
                gatePassRate = 0f,
                gateRejectRate = 0f,
                hitRate = DEFAULT_SUCCESS_RATE,
                wasteRate = 0f,
                avgTimeSavedMs = DEFAULT_TIME_SAVED
            )
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 构建预测事件查询
     */
    private fun buildPredictionQuery(packageName: String, days: Int): AuditQuery {
        return AuditQuery.Builder()
            .setTimeRange(System.currentTimeMillis() - days * MS_PER_DAY, System.currentTimeMillis())
            .build()
        // TODO: 添加 filter("predictedApp", packageName) 当 AuditQuery 支持过滤时
    }
    
    /**
     * 检查事件是否匹配场景
     */
    private fun matchesScene(event: AuditEvent, hour: Int, location: String): Boolean {
        // TODO: 从 event.payload 中解析 context 字段
        // 当前占位实现
        return true
    }
    
    /**
     * 从事件中提取延迟节省
     */
    private fun extractTimeSaved(event: AuditEvent): Long? {
        // TODO: 从 event.payload 中解析 timeSavedMs 字段
        // 当前占位实现
        return null
    }
    
    /**
     * 从事件中提取门控拒绝原因
     */
    private fun extractGateReason(event: AuditEvent): String {
        // TODO: 从 event.payload 中解析 gateReason 字段
        // 当前占位实现
        return "Unknown"
    }
}

/**
 * App 统计指标
 */
data class AppMetrics(
    val packageName: String,
    val totalPredictions: Int,      // 总预测次数
    val gatePassRate: Float,        // 门控通过率
    val gateRejectRate: Float,      // 门控拒绝率
    val hitRate: Float,             // 命中率
    val wasteRate: Float,           // 浪费率
    val avgTimeSavedMs: Long        // 平均延迟节省
)

/**
 * 场景上下文
 */
data class SceneContext(
    val hour: Int,                  // 小时 [0, 23]
    val location: String,           // 位置 (home/office/transit/other)
    val dayOfWeek: Int,            // 星期 [0=周日, 6=周六]
    val isWeekend: Boolean         // 是否周末
) {
    companion object {
        fun from(contextFrame: com.genton.context.ContextFrame): SceneContext {
            val calendar = java.util.Calendar.getInstance()
            return SceneContext(
                hour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
                location = contextFrame.locationHint ?: "other",
                dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1,
                isWeekend = calendar.get(java.util.Calendar.DAY_OF_WEEK) in listOf(
                    java.util.Calendar.SATURDAY,
                    java.util.Calendar.SUNDAY
                )
            )
        }
    }
}

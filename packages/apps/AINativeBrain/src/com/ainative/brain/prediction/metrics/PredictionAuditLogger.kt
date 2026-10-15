package com.ainative.brain.prediction.metrics

import android.content.Context
import com.ainative.audit.AuditEvent
import com.ainative.audit.IAgentAuditService
import com.ainative.brain.prediction.PredictedAction
import com.ainative.brain.prediction.gate.EnhancedGateDecision
import com.ainative.context.ContextFrame
import org.json.JSONObject

/**
 * 预测审计日志记录器
 * 
 * 将预测相关事件写入审计服务：
 * - prediction.made: 预测发生
 * - prediction.gate_pass: 门控通过
 * - prediction.gate_reject: 门控拒绝
 * - prediction.hit: 预测命中
 * - prediction.miss: 预测未命中
 * - prediction.recycled: 资源回收
 * 
 * @author AI Native OS Team
 * @since A5
 */
class PredictionAuditLogger(
    private val context: Context,
    private val auditService: IAgentAuditService?
) {
    companion object {
        private const val TAG = "PredictionAuditLogger"
        
        // 事件类型
        const val EVENT_PREDICTION_MADE = "prediction.made"
        const val EVENT_PREDICTION_GATE_PASS = "prediction.gate_pass"
        const val EVENT_PREDICTION_GATE_REJECT = "prediction.gate_reject"
        const val EVENT_PREDICTION_HIT = "prediction.hit"
        const val EVENT_PREDICTION_MISS = "prediction.miss"
        const val EVENT_PREDICTION_RECYCLED = "prediction.recycled"
    }
    
    /**
     * 记录预测发生事件
     */
    fun logPredictionMade(
        predictedAction: PredictedAction,
        context: ContextFrame
    ) {
        if (auditService == null) return
        
        try {
            val event = buildAuditEvent(
                type = EVENT_PREDICTION_MADE,
                predictedAction = predictedAction,
                context = context
            )
            auditService.logEvent(event)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to log prediction made", e)
        }
    }
    
    /**
     * 记录门控决策事件
     */
    fun logGateDecision(
        predictedAction: PredictedAction,
        decision: EnhancedGateDecision,
        context: ContextFrame
    ) {
        if (auditService == null) return
        
        try {
            val eventType = if (decision.allowed) EVENT_PREDICTION_GATE_PASS else EVENT_PREDICTION_GATE_REJECT
            
            val payload = buildBasePayload(predictedAction, context).apply {
                put("gateDecision", if (decision.allowed) "ALLOW" else "REJECT")
                put("gateReason", decision.reason)
                put("gatePriority", decision.priority)
                put("executionStrategy", decision.executionStrategy?.name ?: "NONE")
                put("signals", serializeSignals(decision.signals))
            }
            
            val event = AuditEvent.Builder()
                .setType(eventType)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload.toString())
                .build()
            
            auditService.logEvent(event)
            
            android.util.Log.d(TAG, "Gate decision logged: $eventType for ${predictedAction.targetApp}")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to log gate decision", e)
        }
    }
    
    /**
     * 记录预测命中事件
     */
    fun logPredictionHit(
        predictedAction: PredictedAction,
        timeSavedMs: Long,
        context: ContextFrame
    ) {
        if (auditService == null) return
        
        try {
            val payload = buildBasePayload(predictedAction, context).apply {
                put("timeSavedMs", timeSavedMs)
                put("result", "HIT")
            }
            
            val event = AuditEvent.Builder()
                .setType(EVENT_PREDICTION_HIT)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload.toString())
                .build()
            
            auditService.logEvent(event)
            
            android.util.Log.i(TAG, "Prediction HIT logged: ${predictedAction.targetApp}, saved ${timeSavedMs}ms")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to log prediction hit", e)
        }
    }
    
    /**
     * 记录预测未命中事件
     */
    fun logPredictionMiss(
        predictedAction: PredictedAction,
        context: ContextFrame
    ) {
        if (auditService == null) return
        
        try {
            val payload = buildBasePayload(predictedAction, context).apply {
                put("result", "MISS")
            }
            
            val event = AuditEvent.Builder()
                .setType(EVENT_PREDICTION_MISS)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload.toString())
                .build()
            
            auditService.logEvent(event)
            
            android.util.Log.d(TAG, "Prediction MISS logged: ${predictedAction.targetApp}")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to log prediction miss", e)
        }
    }
    
    /**
     * 记录资源回收事件
     */
    fun logPredictionRecycled(
        predictedAction: PredictedAction,
        reason: String,
        context: ContextFrame
    ) {
        if (auditService == null) return
        
        try {
            val payload = buildBasePayload(predictedAction, context).apply {
                put("recycleReason", reason)
                put("result", "RECYCLED")
            }
            
            val event = AuditEvent.Builder()
                .setType(EVENT_PREDICTION_RECYCLED)
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload.toString())
                .build()
            
            auditService.logEvent(event)
            
            android.util.Log.d(TAG, "Prediction RECYCLED logged: ${predictedAction.targetApp}, reason=$reason")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to log prediction recycled", e)
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 构建审计事件
     */
    private fun buildAuditEvent(
        type: String,
        predictedAction: PredictedAction,
        context: ContextFrame
    ): AuditEvent {
        val payload = buildBasePayload(predictedAction, context)
        
        return AuditEvent.Builder()
            .setType(type)
            .setTimestamp(System.currentTimeMillis())
            .setPayload(payload.toString())
            .build()
    }
    
    /**
     * 构建基础载荷
     */
    private fun buildBasePayload(
        predictedAction: PredictedAction,
        context: ContextFrame
    ): JSONObject {
        return JSONObject().apply {
            put("predictedApp", predictedAction.targetApp ?: "")
            put("predictedAction", predictedAction.actionType.name)
            put("confidence", predictedAction.confidence)
            put("targetActivity", predictedAction.targetActivity ?: "")
            put("targetResource", predictedAction.targetResource ?: "")
            put("context", serializeContext(context))
        }
    }
    
    /**
     * 序列化上下文
     */
    private fun serializeContext(context: ContextFrame): JSONObject {
        val calendar = java.util.Calendar.getInstance()
        return JSONObject().apply {
            put("hour", calendar.get(java.util.Calendar.HOUR_OF_DAY))
            put("dayOfWeek", calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1)
            put("location", context.locationHint ?: "unknown")
            put("foregroundApp", context.foregroundApp ?: "")
        }
    }
    
    /**
     * 序列化门控信号
     */
    private fun serializeSignals(signals: List<com.ainative.brain.prediction.gate.GateSignal>): String {
        return signals.joinToString(", ") { it.toString() }
    }
}

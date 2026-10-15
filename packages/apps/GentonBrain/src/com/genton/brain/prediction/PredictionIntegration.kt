package com.genton.brain.prediction

import android.content.Context
import com.genton.audit.IAgentAuditService
import com.genton.context.IAgentContextService
import com.genton.events.IAgentEventStreamService
import com.genton.memory.IAgentMemoryService

/**
 * 预测服务集成器
 * 
 * 负责将预测服务集成到系统大脑主服务中，管理与其他服务的协作。
 * 
 * @author AI Native OS Team
 * @since A5
 */
class PredictionIntegration(
    private val context: Context,
    private val contextService: IAgentContextService,
    private val eventStreamService: IAgentEventStreamService,
    private val memoryService: IAgentMemoryService,
    private val auditService: IAgentAuditService
) {
    private val predictionService: PredictionService = PredictionService.getInstance(context)
    
    /**
     * 初始化集成
     */
    fun initialize() {
        // 注入系统服务
        predictionService.setContextService(contextService)
        predictionService.setEventStreamService(eventStreamService)
        predictionService.setMemoryService(memoryService)
        predictionService.setAuditService(auditService)
        
        // 订阅事件流，监听实际动作发生
        subscribeToActionEvents()
        
        android.util.Log.i("PredictionIntegration", "Prediction service integrated")
    }
    
    /**
     * 启动预测服务
     */
    fun start() {
        predictionService.start()
    }
    
    /**
     * 停止预测服务
     */
    fun stop() {
        predictionService.stop()
    }
    
    /**
     * 订阅动作事件
     */
    private fun subscribeToActionEvents() {
        // TODO: 订阅事件流中的实际动作事件
        // eventStreamService.subscribe("action.*") { event ->
        //     val actualAction = ActualAction.fromEvent(event)
        //     predictionService.onActualAction(actualAction)
        // }
        
        android.util.Log.d("PredictionIntegration", "Subscribed to action events (placeholder)")
    }
    
    /**
     * 获取预测统计
     */
    fun getMetrics(): PredictionMetrics {
        return predictionService.getMetrics()
    }
}

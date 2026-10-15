package com.genton.brain.intent

import android.util.Log
import com.genton.brain.BrainApplication
import com.genton.brain.planner.Planner
import com.genton.brain.planner.PlanningResult
import com.genton.brain.security.SecurityInspector
import com.genton.brain.toolloop.ToolLoop
import org.json.JSONObject

/**
 * 意图接收管理器
 * 
 * 职责：
 * 1. 接收用户会话输入（submitUserTurn）
 * 2. 接收事件流主动触发（onEventTriggered）
 * 3. 统一 Turn 入口，转换为标准化意图对象
 * 4. 路由到规划器进行处理
 */
class IntentReceiver(
    private val application: BrainApplication,
    private val turnManager: TurnManager
) {
    
    companion object {
        private const val TAG = "IntentReceiver"
        
        // Turn 来源
        const val SOURCE_USER_SESSION = "user_session"
        const val SOURCE_EVENT_STREAM = "event_stream"
        const val SOURCE_SYSTEM_TRIGGER = "system_trigger"
    }
    
    // 安检模块实例
    private val securityInspector = SecurityInspector()
    
    // 规划器实例
    private var planner: Planner? = null
    
    // 工具循环实例
    private var toolLoop: ToolLoop? = null
    
    /**
     * 设置规划器（延迟注入，避免循环依赖）
     */
    fun setPlanner(planner: Planner) {
        this.planner = planner
    }
    
    /**
     * 设置工具循环（延迟注入，避免循环依赖）
     */
    fun setToolLoop(toolLoop: ToolLoop) {
        this.toolLoop = toolLoop
    }
    
    /**
     * 提交用户 Turn（会话通道）
     * 
     * @param sessionId 会话 ID
     * @param turnId Turn ID（支持幂等性）
     * @param userInput 用户输入文本
     * @param kind 输入类型（0=text 1=voice 2=attachment 3=command 4=form_submit）
     * @param attachments 附件引用列表
     */
    fun submitUserTurn(
        sessionId: String,
        turnId: String,
        userInput: String,
        kind: Int = 0,
        attachments: List<String> = emptyList()
    ) {
        Log.i(TAG, "Received user turn: sessionId=$sessionId, turnId=$turnId, kind=$kind")
        
        // 构建标准化意图对象
        val intent = Intent(
            turnId = turnId,
            source = SOURCE_USER_SESSION,
            sourceData = mapOf(
                "session_id" to sessionId,
                "user_input" to userInput,
                "kind" to kind,
                "attachments" to attachments
            ),
            timestamp = System.currentTimeMillis()
        )
        
        // 记录到 Turn 管理器
        turnManager.createTurn(turnId, intent.source, intent.sourceData)
        
        // 记录到审计日志
        recordIntentReceived(intent)
        
        // 路由到规划器
        routeToPlanner(intent)
    }
    
    /**
     * 取消 Turn
     * 
     * @param turnId Turn ID
     */
    fun cancelTurn(turnId: String) {
        Log.i(TAG, "Cancelling turn: $turnId")
        
        try {
            // 1. 更新 Turn 状态
            turnManager.cancelTurn(turnId)
            
            // 2. 取消规划器执行
            planner?.cancelTurn(turnId)
            
            // 3. 取消工具循环执行
            toolLoop?.cancelExecution(turnId)
            
            Log.i(TAG, "Turn cancelled successfully: $turnId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel turn: $turnId", e)
            throw e
        }
    }
    
    /**
     * 事件流主动触发
     * 
     * @param eventType 事件类型
     * @param eventData 事件数据
     * @param triggerRules 触发规则 ID 列表
     * @return Turn ID
     */
    fun onEventTriggered(
        eventType: String,
        eventData: Map<String, Any>,
        triggerRules: List<String>
    ): String {
        Log.i(TAG, "Event triggered: type=$eventType, rules=$triggerRules")
        
        // 1. 生成 Turn ID
        val turnId = generateTurnId(SOURCE_EVENT_STREAM)
        
        // 2. 构建标准化意图对象
        val intent = Intent(
            turnId = turnId,
            source = SOURCE_EVENT_STREAM,
            sourceData = mapOf(
                "event_type" to eventType,
                "event_data" to eventData,
                "trigger_rules" to triggerRules
            ),
            timestamp = System.currentTimeMillis()
        )
        
        // 3. 记录到审计日志
        recordIntentReceived(intent)
        
        // 4. 路由到规划器
        routeToPlanner(intent)
        
        return turnId
    }
    
    /**
     * 系统触发（内部使用）
     * 
     * @param trigger 触发类型
     * @param triggerData 触发数据
     * @return Turn ID
     */
    fun onSystemTriggered(
        trigger: String,
        triggerData: Map<String, Any>
    ): String {
        Log.i(TAG, "System triggered: trigger=$trigger")
        
        // 1. 生成 Turn ID
        val turnId = generateTurnId(SOURCE_SYSTEM_TRIGGER)
        
        // 2. 构建标准化意图对象
        val intent = Intent(
            turnId = turnId,
            source = SOURCE_SYSTEM_TRIGGER,
            sourceData = mapOf(
                "trigger" to trigger,
                "trigger_data" to triggerData
            ),
            timestamp = System.currentTimeMillis()
        )
        
        // 3. 创建 Turn 记录
        turnManager.createTurn(turnId, intent.source, intent.sourceData)
        
        // 4. 记录到审计日志
        recordIntentReceived(intent)
        
        // 4. 路由到规划器
        routeToPlanner(intent)
        
        return turnId
    }
    
    /**
     * 生成 Turn ID
     */
    private fun generateTurnId(source: String): String {
        val timestamp = System.currentTimeMillis()
        val random = (0..9999).random()
        return "turn_${source}_${timestamp}_${random}"
    }
    
    /**
     * 记录意图接收到审计日志
     */
    private fun recordIntentReceived(intent: Intent) {
        try {
            // TODO: 调用审计服务记录
            // auditService.record(
            //     category = AuditCategory.INTENT_RECEIVED,
            //     action = "receive_intent",
            //     details = intent.toJson()
            // )
            
            Log.d(TAG, "Intent recorded to audit log: ${intent.turnId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record intent to audit log", e)
        }
    }
    
    /**
     * 路由到规划器
     */
    private fun routeToPlanner(intent: Intent) {
        try {
            // 更新 Turn 状态为 PLANNING
            turnManager.updateTurnState(intent.turnId, TurnManager.TurnState.PLANNING)
            
            // 安检：在进入规划器之前执行安检与打标
            val securityResult = performSecurityInspection(intent)
            
            if (!securityResult.passed) {
                Log.w(TAG, "Security inspection failed for turn: ${intent.turnId}")
                turnManager.updateTurnState(
                    intent.turnId,
                    TurnManager.TurnState.FAILED,
                    "Security inspection failed: ${securityResult.violations.size} violations"
                )
                return
            }
            
            // 将安检结果附加到意图对象
            val enrichedIntent = intent.copy(
                securityInspection = securityResult
            )
            
            // 调用规划器处理意图
            if (planner != null) {
                try {
                    val planningResult = planner!!.process(enrichedIntent)
                    
                    Log.i(TAG, "Planning completed: intentType=${planningResult.intentType}, " +
                            "goals=${planningResult.goalDAG.goals.size}, " +
                            "shouldEscalate=${planningResult.escalation.shouldEscalate}")
                    
                    // TODO: 根据规划结果执行后续流程
                    // if (planningResult.escalation.shouldEscalate) {
                    //     // 升级到云端大模型
                    //     escalateToCloud(planningResult)
                    // } else {
                    //     // 本地执行工具循环
                    //     executeLocally(planningResult)
                    // }
                    
                } catch (e: Planner.PlanningException) {
                    Log.e(TAG, "Planning failed", e)
                    turnManager.updateTurnState(
                        intent.turnId,
                        TurnManager.TurnState.FAILED,
                        "Planning failed: ${e.message}"
                    )
                    return
                }
            } else {
                Log.w(TAG, "Planner not initialized, skipping planning")
            }
            
            Log.i(TAG, "Intent routed to planner: ${intent.turnId}")
            
            // 临时实现：直接模拟处理
            simulatePlanning(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route intent to planner", e)
            handleRoutingError(intent, e)
        }
    }
    
    /**
     * 模拟规划处理（临时实现）
     */
    private fun simulatePlanning(intent: Intent) {
        Log.d(TAG, "Simulating planning for intent: ${intent.turnId}")
        
        when (intent.source) {
            SOURCE_USER_SESSION -> {
                val userInput = intent.sourceData["user_input"] as? String
                Log.d(TAG, "Processing user input: '$userInput'")
                // TODO: 调用意图分类模型
            }
            SOURCE_EVENT_STREAM -> {
                val eventType = intent.sourceData["event_type"] as? String
                Log.d(TAG, "Processing event: $eventType")
                // TODO: 查询事件触发规则
            }
            SOURCE_SYSTEM_TRIGGER -> {
                val trigger = intent.sourceData["trigger"] as? String
                Log.d(TAG, "Processing system trigger: $trigger")
                // TODO: 执行系统预定义流程
            }
        }
    }
    
    /**
     * 处理路由错误
     */
    private fun handleRoutingError(intent: Intent, error: Exception) {
        Log.e(TAG, "Routing error for intent: ${intent.turnId}", error)
        
        // 记录错误到审计日志
        try {
            // TODO: 调用审计服务记录错误
            // auditService.record(
            //     category = AuditCategory.INTENT_ERROR,
            //     action = "routing_failed",
            //     details = mapOf("turn_id" to intent.turnId, "error" to error.message)
            // )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record routing error", e)
        }
    }
    
    /**
     * 执行安检与打标
     * 
     * @param intent 意图对象
     * @return 安检结果
     */
    private fun performSecurityInspection(intent: Intent): SecurityInspector.InspectionResult {
        Log.d(TAG, "Performing security inspection for turn: ${intent.turnId}")
        
        try {
            // 1. 提取感知数据
            val perceptionData = extractPerceptionData(intent)
            
            // 2. 提取用户意图
            val userIntent = extractUserIntent(intent)
            
            // 3. 提取派生参数（临时为空，实际由规划器生成）
            val derivedParams = emptyMap<String, Any>()
            
            // 4. 执行安检
            val result = securityInspector.inspect(
                perceptionData = perceptionData,
                userIntent = userIntent,
                derivedParams = derivedParams
            )
            
            // 5. 记录安检结果到审计日志
            recordSecurityInspection(intent.turnId, result)
            
            Log.i(TAG, "Security inspection completed: passed=${result.passed}, violations=${result.violations.size}")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Security inspection failed with exception", e)
            
            // 异常时返回失败结果
            return SecurityInspector.InspectionResult(
                passed = false,
                trustLevelDistribution = emptyMap(),
                taintMap = emptyMap(),
                violations = listOf("Security inspection exception: ${e.message}"),
                shouldDowngrade = false,
                downgradedContent = emptyMap()
            )
        }
    }
    
    /**
     * 提取感知数据
     */
    private fun extractPerceptionData(intent: Intent): Map<String, Any> {
        val perceptionData = mutableMapOf<String, Any>()
        
        when (intent.source) {
            SOURCE_USER_SESSION -> {
                // 用户会话：用户输入 + 上下文数据
                val userInput = intent.sourceData["user_input"] as? String
                val contextData = intent.sourceData["context_data"] as? Map<*, *>
                
                if (userInput != null) {
                    perceptionData["user_input"] = userInput
                }
                
                if (contextData != null) {
                    perceptionData.putAll(contextData.mapKeys { it.key.toString() })
                }
            }
            
            SOURCE_EVENT_STREAM -> {
                // 事件流：事件数据
                val eventData = intent.sourceData["event_data"] as? Map<*, *>
                if (eventData != null) {
                    perceptionData.putAll(eventData.mapKeys { it.key.toString() })
                }
            }
            
            SOURCE_SYSTEM_TRIGGER -> {
                // 系统触发：触发数据
                val triggerData = intent.sourceData["trigger_data"] as? Map<*, *>
                if (triggerData != null) {
                    perceptionData.putAll(triggerData.mapKeys { it.key.toString() })
                }
            }
        }
        
        return perceptionData
    }
    
    /**
     * 提取用户意图
     */
    private fun extractUserIntent(intent: Intent): String? {
        return when (intent.source) {
            SOURCE_USER_SESSION -> {
                // 用户会话：直接使用用户输入
                intent.sourceData["user_input"] as? String
            }
            SOURCE_EVENT_STREAM -> {
                // 事件流：构建意图描述
                val eventType = intent.sourceData["event_type"] as? String
                "Event triggered: $eventType"
            }
            SOURCE_SYSTEM_TRIGGER -> {
                // 系统触发：构建意图描述
                val trigger = intent.sourceData["trigger"] as? String
                "System trigger: $trigger"
            }
            else -> null
        }
    }
    
    /**
     * 记录安检结果到审计日志
     */
    private fun recordSecurityInspection(
        turnId: String,
        result: SecurityInspector.InspectionResult
    ) {
        try {
            // TODO: 调用审计服务记录安检结果
            // auditService.record(
            //     category = AuditCategory.SECURITY_INSPECTION,
            //     action = "inspect_intent",
            //     details = mapOf(
            //         "turn_id" to turnId,
            //         "passed" to result.passed,
            //         "violations" to result.violations,
            //         "should_downgrade" to result.shouldDowngrade,
            //         "trust_level_distribution" to result.trustLevelDistribution
            //     )
            // )
            
            Log.d(TAG, "Security inspection result recorded: turnId=$turnId, passed=${result.passed}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record security inspection result", e)
        }
    }
    
    /**
     * 意图数据类
     */
    data class Intent(
        val turnId: String,
        val source: String,
        val sourceData: Map<String, Any>,
        val timestamp: Long,
        val securityInspection: SecurityInspector.InspectionResult? = null
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("turn_id", turnId)
            json.put("source", source)
            json.put("source_data", JSONObject(sourceData))
            json.put("timestamp", timestamp)
            return json.toString()
        }
    }
}

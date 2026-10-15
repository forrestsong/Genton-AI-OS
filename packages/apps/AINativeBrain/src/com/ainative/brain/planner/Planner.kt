package com.ainative.brain.planner

import android.util.Log
import com.ainative.brain.BrainApplication
import com.ainative.brain.intent.IntentReceiver
import com.ainative.brain.intent.TurnManager
import com.ainative.brain.runtime.AgentRuntimeClient
import com.ainative.brain.security.SecurityInspector
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规划器
 * 
 * 职责：
 * 1. 意图分类：本地小模型识别意图类型
 * 2. Goal DAG 分解：将用户意图分解为多层目标树
 * 3. 复杂任务升级判定：决定是否需要云端大模型
 * 4. 能力需求分析：提取每个目标所需的能力列表
 */
class Planner(
    private val application: BrainApplication,
    private val turnManager: TurnManager,
    private val runtimeClient: AgentRuntimeClient
) {
    
    companion object {
        private const val TAG = "Planner"
        
        // 意图类型
        const val INTENT_TYPE_SIMPLE_QUERY = "simple_query"           // 简单查询
        const val INTENT_TYPE_SIMPLE_ACTION = "simple_action"         // 简单操作
        const val INTENT_TYPE_COMPLEX_WORKFLOW = "complex_workflow"   // 复杂流程
        const val INTENT_TYPE_MULTI_STEP = "multi_step"               // 多步骤任务
        const val INTENT_TYPE_AMBIGUOUS = "ambiguous"                 // 模糊意图
        
        // 升级阈值
        const val COMPLEXITY_THRESHOLD_LOCAL = 3   // 子目标数 ≤ 3：本地处理
        const val COMPLEXITY_THRESHOLD_CLOUD = 8   // 子目标数 > 8：云端处理
    }
    
    /**
     * 处理意图
     * 
     * @param enrichedIntent 包含安检结果的意图对象
     * @return 规划结果
     */
    fun process(enrichedIntent: IntentReceiver.Intent): PlanningResult {
        Log.i(TAG, "Processing intent: ${enrichedIntent.turnId}")
        
        try {
            // 1. 意图分类
            val intentType = classifyIntent(enrichedIntent)
            Log.d(TAG, "Intent classified as: $intentType")
            
            // 2. Goal DAG 分解
            val goalDAG = decomposeGoals(enrichedIntent, intentType)
            Log.d(TAG, "Goals decomposed: ${goalDAG.goals.size} goals")
            
            // 3. 复杂任务升级判定
            val escalation = evaluateEscalation(goalDAG, enrichedIntent)
            Log.d(TAG, "Escalation decision: shouldEscalate=${escalation.shouldEscalate}")
            
            // 4. 构建规划结果
            val result = PlanningResult(
                turnId = enrichedIntent.turnId,
                intentType = intentType,
                goalDAG = goalDAG,
                escalation = escalation,
                timestamp = System.currentTimeMillis()
            )
            
            // 5. 记录规划结果到审计日志
            recordPlanningResult(result)
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Planning failed for intent: ${enrichedIntent.turnId}", e)
            throw PlanningException("Planning failed: ${e.message}", e)
        }
    }
    
    /**
     * 意图分类（本地小模型）
     * 
     * 使用本地小模型对用户意图进行分类
     */
    private fun classifyIntent(intent: IntentReceiver.Intent): String {
        Log.d(TAG, "Classifying intent: ${intent.turnId}")
        
        // 提取用户输入
        val userInput = when (intent.source) {
            IntentReceiver.SOURCE_USER_SESSION -> {
                intent.sourceData["user_input"] as? String
            }
            IntentReceiver.SOURCE_EVENT_STREAM -> {
                val eventType = intent.sourceData["event_type"] as? String
                "Event: $eventType"
            }
            IntentReceiver.SOURCE_SYSTEM_TRIGGER -> {
                val trigger = intent.sourceData["trigger"] as? String
                "System: $trigger"
            }
            else -> null
        } ?: return INTENT_TYPE_AMBIGUOUS
        
        // TODO: 调用本地小模型进行意图分类
        // val classificationResult = localModel.classify(userInput)
        
        // 临时实现：基于关键词规则分类
        return classifyByKeywords(userInput)
    }
    
    /**
     * 基于关键词的意图分类（临时实现）
     */
    private fun classifyByKeywords(userInput: String): String {
        val input = userInput.lowercase()
        
        return when {
            // 简单查询
            input.matches(Regex(".*什么.*|.*哪.*|.*怎么.*|.*为什么.*|.*查询.*")) -> {
                INTENT_TYPE_SIMPLE_QUERY
            }
            
            // 简单操作
            input.matches(Regex(".*打开.*|.*关闭.*|.*发送.*|.*拨打.*|.*设置.*")) &&
            !input.contains("然后") && !input.contains("接着") -> {
                INTENT_TYPE_SIMPLE_ACTION
            }
            
            // 多步骤任务
            input.contains("然后") || input.contains("接着") || input.contains("之后") -> {
                INTENT_TYPE_MULTI_STEP
            }
            
            // 复杂流程
            input.length > 50 || 
            input.split("，|,|。|；|;".toRegex()).size > 3 -> {
                INTENT_TYPE_COMPLEX_WORKFLOW
            }
            
            // 默认：模糊意图
            else -> INTENT_TYPE_AMBIGUOUS
        }
    }
    
    /**
     * Goal DAG 分解
     * 
     * 将用户意图分解为多层目标有向无环图（DAG）
     */
    private fun decomposeGoals(
        intent: IntentReceiver.Intent,
        intentType: String
    ): GoalDAG {
        Log.d(TAG, "Decomposing goals for intent: ${intent.turnId}")
        
        val goals = mutableListOf<GoalNode>()
        
        // 1. 创建顶层目标
        val topGoal = createTopLevelGoal(intent, intentType)
        goals.add(topGoal)
        
        // 2. 根据意图类型分解子目标
        val subGoals = when (intentType) {
            INTENT_TYPE_SIMPLE_QUERY -> {
                decomposeSimpleQuery(intent, topGoal)
            }
            INTENT_TYPE_SIMPLE_ACTION -> {
                decomposeSimpleAction(intent, topGoal)
            }
            INTENT_TYPE_MULTI_STEP -> {
                decomposeMultiStep(intent, topGoal)
            }
            INTENT_TYPE_COMPLEX_WORKFLOW -> {
                decomposeComplexWorkflow(intent, topGoal)
            }
            else -> {
                emptyList()
            }
        }
        
        goals.addAll(subGoals)
        
        return GoalDAG(
            turnId = intent.turnId,
            goals = goals,
            rootGoalId = topGoal.goalId
        )
    }
    
    /**
     * 创建顶层目标
     */
    private fun createTopLevelGoal(
        intent: IntentReceiver.Intent,
        intentType: String
    ): GoalNode {
        val userInput = extractUserInput(intent)
        
        return GoalNode(
            goalId = generateGoalId(intent.turnId, "root"),
            parentGoalId = null,
            description = summarizeIntent(userInput),
            status = GoalStatus.PLANNED,
            requiredCapabilities = extractCapabilities(intentType),
            depth = 0
        )
    }
    
    /**
     * 分解简单查询
     */
    private fun decomposeSimpleQuery(
        intent: IntentReceiver.Intent,
        parentGoal: GoalNode
    ): List<GoalNode> {
        // 简单查询：检索信息 → 格式化回复
        return listOf(
            GoalNode(
                goalId = generateGoalId(intent.turnId, "retrieve"),
                parentGoalId = parentGoal.goalId,
                description = "检索相关信息",
                status = GoalStatus.PLANNED,
                requiredCapabilities = listOf("agent.context_read", "agent.web_search"),
                depth = 1
            ),
            GoalNode(
                goalId = generateGoalId(intent.turnId, "format"),
                parentGoalId = parentGoal.goalId,
                description = "格式化回复",
                status = GoalStatus.PLANNED,
                requiredCapabilities = listOf("agent.llm_inference"),
                depth = 1
            )
        )
    }
    
    /**
     * 分解简单操作
     */
    private fun decomposeSimpleAction(
        intent: IntentReceiver.Intent,
        parentGoal: GoalNode
    ): List<GoalNode> {
        // 简单操作：参数提取 → 执行动作
        return listOf(
            GoalNode(
                goalId = generateGoalId(intent.turnId, "extract"),
                parentGoalId = parentGoal.goalId,
                description = "提取动作参数",
                status = GoalStatus.PLANNED,
                requiredCapabilities = listOf("agent.llm_inference"),
                depth = 1
            ),
            GoalNode(
                goalId = generateGoalId(intent.turnId, "execute"),
                parentGoalId = parentGoal.goalId,
                description = "执行动作",
                status = GoalStatus.PLANNED,
                requiredCapabilities = listOf("agent.action_execute"),
                depth = 1
            )
        )
    }
    
    /**
     * 分解多步骤任务
     */
    private fun decomposeMultiStep(
        intent: IntentReceiver.Intent,
        parentGoal: GoalNode
    ): List<GoalNode> {
        val userInput = extractUserInput(intent)
        
        // 多步骤任务：识别步骤 → 逐步执行
        val goals = mutableListOf<GoalNode>()
        
        // 步骤 1：识别步骤序列
        goals.add(
            GoalNode(
                goalId = generateGoalId(intent.turnId, "identify_steps"),
                parentGoalId = parentGoal.goalId,
                description = "识别任务步骤",
                status = GoalStatus.PLANNED,
                requiredCapabilities = listOf("agent.llm_inference"),
                depth = 1
            )
        )
        
        // 步骤 2-N：执行各步骤（临时生成 3 个占位步骤）
        for (i in 1..3) {
            goals.add(
                GoalNode(
                    goalId = generateGoalId(intent.turnId, "step_$i"),
                    parentGoalId = goals[0].goalId,
                    description = "执行步骤 $i",
                    status = GoalStatus.PLANNED,
                    requiredCapabilities = listOf("agent.action_execute"),
                    depth = 2
                )
            )
        }
        
        return goals
    }
    
    /**
     * 分解复杂流程
     */
    private fun decomposeComplexWorkflow(
        intent: IntentReceiver.Intent,
        parentGoal: GoalNode
    ): List<GoalNode> {
        // 复杂流程：需要调用本地小模型进行深度分解
        // TODO: 调用本地小模型生成详细的 Goal DAG
        
        // 临时实现：生成占位目标
        val goals = mutableListOf<GoalNode>()
        
        goals.add(
            GoalNode(
                goalId = generateGoalId(intent.turnId, "analyze"),
                parentGoalId = parentGoal.goalId,
                description = "分析任务结构",
                status = GoalStatus.PLANNED,
                requiredCapabilities = listOf("agent.llm_inference"),
                depth = 1
            )
        )
        
        // 生成多个子任务占位
        for (i in 1..5) {
            goals.add(
                GoalNode(
                    goalId = generateGoalId(intent.turnId, "subtask_$i"),
                    parentGoalId = goals[0].goalId,
                    description = "子任务 $i",
                    status = GoalStatus.PLANNED,
                    requiredCapabilities = listOf("agent.action_execute"),
                    depth = 2
                )
            )
        }
        
        return goals
    }
    
    /**
     * 复杂任务升级判定
     * 
     * 决定任务是否需要升级到云端大模型处理
     */
    private fun evaluateEscalation(
        goalDAG: GoalDAG,
        intent: IntentReceiver.Intent
    ): EscalationDecision {
        Log.d(TAG, "Evaluating escalation for ${goalDAG.goals.size} goals")
        
        val goalCount = goalDAG.goals.size
        val maxDepth = goalDAG.goals.maxOfOrNull { it.depth } ?: 0
        
        // 判定标准
        val shouldEscalate = when {
            // 1. 子目标数量过多
            goalCount > COMPLEXITY_THRESHOLD_CLOUD -> {
                true
            }
            
            // 2. 目标层级过深
            maxDepth > 3 -> {
                true
            }
            
            // 3. 安检结果包含高风险内容
            intent.securityInspection?.let { it.shouldDowngrade } == true -> {
                true
            }
            
            // 4. 子目标数量较少，本地处理
            goalCount <= COMPLEXITY_THRESHOLD_LOCAL -> {
                false
            }
            
            // 5. 默认：中等复杂度，本地处理
            else -> false
        }
        
        // 生成原因
        val reasons = mutableListOf<String>()
        if (goalCount > COMPLEXITY_THRESHOLD_CLOUD) {
            reasons.add("Goal count ($goalCount) exceeds cloud threshold ($COMPLEXITY_THRESHOLD_CLOUD)")
        }
        if (maxDepth > 3) {
            reasons.add("Goal depth ($maxDepth) exceeds threshold (3)")
        }
        if (intent.securityInspection?.shouldDowngrade == true) {
            reasons.add("Security inspection requires content downgrade")
        }
        if (!shouldEscalate && goalCount <= COMPLEXITY_THRESHOLD_LOCAL) {
            reasons.add("Goal count ($goalCount) within local threshold ($COMPLEXITY_THRESHOLD_LOCAL)")
        }
        
        return EscalationDecision(
            shouldEscalate = shouldEscalate,
            reasons = reasons,
            estimatedComplexity = estimateComplexity(goalCount, maxDepth)
        )
    }
    
    /**
     * 估算任务复杂度
     */
    private fun estimateComplexity(goalCount: Int, maxDepth: Int): String {
        return when {
            goalCount <= 2 && maxDepth <= 1 -> "trivial"
            goalCount <= COMPLEXITY_THRESHOLD_LOCAL && maxDepth <= 2 -> "simple"
            goalCount <= COMPLEXITY_THRESHOLD_CLOUD && maxDepth <= 3 -> "moderate"
            else -> "complex"
        }
    }
    
    /**
     * 提取用户输入
     */
    private fun extractUserInput(intent: IntentReceiver.Intent): String {
        return when (intent.source) {
            IntentReceiver.SOURCE_USER_SESSION -> {
                intent.sourceData["user_input"] as? String ?: ""
            }
            IntentReceiver.SOURCE_EVENT_STREAM -> {
                val eventType = intent.sourceData["event_type"] as? String
                "Event: $eventType"
            }
            IntentReceiver.SOURCE_SYSTEM_TRIGGER -> {
                val trigger = intent.sourceData["trigger"] as? String
                "System: $trigger"
            }
            else -> ""
        }
    }
    
    /**
     * 总结意图（生成顶层目标描述）
     */
    private fun summarizeIntent(userInput: String): String {
        // 简单截断为摘要
        return if (userInput.length > 50) {
            userInput.substring(0, 47) + "..."
        } else {
            userInput
        }
    }
    
    /**
     * 提取能力需求
     */
    private fun extractCapabilities(intentType: String): List<String> {
        return when (intentType) {
            INTENT_TYPE_SIMPLE_QUERY -> listOf("agent.context_read", "agent.llm_inference")
            INTENT_TYPE_SIMPLE_ACTION -> listOf("agent.action_execute")
            INTENT_TYPE_MULTI_STEP -> listOf("agent.llm_inference", "agent.action_execute")
            INTENT_TYPE_COMPLEX_WORKFLOW -> listOf("agent.llm_inference", "agent.action_execute", "agent.context_read")
            else -> emptyList()
        }
    }
    
    /**
     * 生成目标 ID
     */
    private fun generateGoalId(turnId: String, suffix: String): String {
        return "goal_${turnId}_${suffix}_${System.currentTimeMillis()}"
    }
    
    /**
     * 记录规划结果到审计日志
     */
    private fun recordPlanningResult(result: PlanningResult) {
        try {
            // TODO: 调用审计服务记录
            // auditService.record(
            //     category = AuditCategory.PLANNING,
            //     action = "plan_intent",
            //     details = result.toJson()
            // )
            
            Log.d(TAG, "Planning result recorded: turnId=${result.turnId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record planning result", e)
        }
    }
    
    /**
     * 规划异常
     */
    class PlanningException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

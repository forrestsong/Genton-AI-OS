package com.genton.brain.toolloop

import android.util.Log
import com.genton.brain.BrainApplication
import com.genton.brain.executor.ActionExecutor
import com.genton.brain.planner.GoalDAG
import com.genton.brain.planner.GoalNode
import com.genton.brain.planner.GoalStatus
import com.genton.brain.planner.PlanningResult
import com.genton.brain.runtime.AgentRuntimeClient
import com.genton.brain.intent.TurnManager
import com.genton.brain.security.SecurityInspector
import org.json.JSONObject

/**
 * 工具循环
 * 
 * 职责：
 * 1. 能力令牌管理：逐子目标 acquire/release 令牌
 * 2. 动作编排：按 Goal DAG 顺序编排动作执行
 * 3. 结果断言：验证动作执行结果是否符合预期
 * 4. 预算回看：监控预算消耗，超限处理
 */
class ToolLoop(
    private val application: BrainApplication,
    private val runtimeClient: AgentRuntimeClient,
    private val turnManager: TurnManager
) {
    
    companion object {
        private const val TAG = "ToolLoop"
        
        // 执行状态
        const val EXECUTION_SUCCESS = 0
        const val EXECUTION_FAILED = 1
        const val EXECUTION_BUDGET_EXCEEDED = 2
        const val EXECUTION_TOKEN_DENIED = 3
        const val EXECUTION_ASSERTION_FAILED = 4
    }
    
    /**
     * 执行 Goal DAG
     * 
     * @param planningResult 规划结果
     * @param agentId Agent ID
     * @return 执行结果
     */
    fun execute(planningResult: PlanningResult, agentId: String): ExecutionResult {
        Log.i(TAG, "Starting tool loop execution for turn: ${planningResult.turnId}")
        
        try {
            // 1. 初始化执行上下文
            val context = ExecutionContext(
                turnId = planningResult.turnId,
                agentId = agentId,
                goalDAG = planningResult.goalDAG,
                startTime = System.currentTimeMillis(),
                securityInspection = null  // TODO: 从 planningResult 传递安检结果
            )
            
            // 2. 更新 Turn 状态为 EXECUTING
            turnManager.updateTurnState(
                planningResult.turnId,
                TurnManager.TurnState.EXECUTING
            )
            
            // 3. 执行 Goal DAG
            val result = executeGoalDAG(context)
            
            // 4. 更新 Turn 状态
            if (result.status == EXECUTION_SUCCESS) {
                turnManager.updateTurnState(
                    planningResult.turnId,
                    TurnManager.TurnState.COMPLETED,
                    "Execution completed successfully"
                )
            } else {
                turnManager.updateTurnState(
                    planningResult.turnId,
                    TurnManager.TurnState.FAILED,
                    "Execution failed: ${result.failureReason}"
                )
            }
            
            Log.i(TAG, "Tool loop execution completed: status=${result.status}")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Tool loop execution failed", e)
            
            turnManager.updateTurnState(
                planningResult.turnId,
                TurnManager.TurnState.FAILED,
                "Execution exception: ${e.message}"
            )
            
            return ExecutionResult(
                turnId = planningResult.turnId,
                status = EXECUTION_FAILED,
                failureReason = "Exception: ${e.message}",
                completedGoals = emptyList(),
                budgetUsage = BudgetUsage()
            )
        }
    }
    
    /**
     * 执行 Goal DAG
     */
    private fun executeGoalDAG(context: ExecutionContext): ExecutionResult {
        Log.d(TAG, "Executing Goal DAG with ${context.goalDAG.goals.size} goals")
        
        val completedGoals = mutableListOf<String>()
        val budgetUsage = BudgetUsage()
        
        // 获取根目标
        val rootGoal = context.goalDAG.getRootGoal()
        if (rootGoal == null) {
            return ExecutionResult(
                turnId = context.turnId,
                status = EXECUTION_FAILED,
                failureReason = "No root goal found",
                completedGoals = emptyList(),
                budgetUsage = budgetUsage
            )
        }
        
        // 按拓扑顺序执行目标
        val executionOrder = getTopologicalOrder(context.goalDAG)
        
        for (goalId in executionOrder) {
            val goal = context.goalDAG.goals.find { it.goalId == goalId }
            if (goal == null) {
                Log.w(TAG, "Goal not found: $goalId")
                continue
            }
            
            // 执行单个目标
            val goalResult = executeGoal(goal, context, budgetUsage)
            
            when (goalResult.status) {
                GoalExecutionStatus.SUCCESS -> {
                    completedGoals.add(goalId)
                    goal.status = GoalStatus.DONE
                    Log.d(TAG, "Goal completed: $goalId")
                }
                GoalExecutionStatus.BUDGET_EXCEEDED -> {
                    Log.w(TAG, "Budget exceeded at goal: $goalId")
                    return ExecutionResult(
                        turnId = context.turnId,
                        status = EXECUTION_BUDGET_EXCEEDED,
                        failureReason = "Budget exceeded at goal: ${goal.description}",
                        completedGoals = completedGoals,
                        budgetUsage = budgetUsage
                    )
                }
                GoalExecutionStatus.TOKEN_DENIED -> {
                    Log.w(TAG, "Token denied for goal: $goalId")
                    return ExecutionResult(
                        turnId = context.turnId,
                        status = EXECUTION_TOKEN_DENIED,
                        failureReason = "Token denied for capability: ${goal.requiredCapabilities.joinToString()}",
                        completedGoals = completedGoals,
                        budgetUsage = budgetUsage
                    )
                }
                GoalExecutionStatus.ASSERTION_FAILED -> {
                    Log.w(TAG, "Assertion failed for goal: $goalId")
                    goal.status = GoalStatus.FAILED
                    return ExecutionResult(
                        turnId = context.turnId,
                        status = EXECUTION_ASSERTION_FAILED,
                        failureReason = "Assertion failed: ${goalResult.failureReason}",
                        completedGoals = completedGoals,
                        budgetUsage = budgetUsage
                    )
                }
                GoalExecutionStatus.FAILED -> {
                    Log.w(TAG, "Goal execution failed: $goalId")
                    goal.status = GoalStatus.FAILED
                    return ExecutionResult(
                        turnId = context.turnId,
                        status = EXECUTION_FAILED,
                        failureReason = goalResult.failureReason ?: "Goal execution failed",
                        completedGoals = completedGoals,
                        budgetUsage = budgetUsage
                    )
                }
            }
        }
        
        return ExecutionResult(
            turnId = context.turnId,
            status = com.genton.brain.toolloop.EXECUTION_SUCCESS,
            failureReason = null,
            completedGoals = completedGoals,
            budgetUsage = budgetUsage,
            finalText = "执行完成",
            actionCardIds = emptyList(),
            tokensConsumed = budgetUsage.tokensUsed,
            auditRef = "audit_${context.turnId}"
        )
    }
    
    /**
     * 执行单个目标
     */
    private fun executeGoal(
        goal: GoalNode,
        context: ExecutionContext,
        budgetUsage: BudgetUsage
    ): GoalExecutionResult {
        Log.d(TAG, "Executing goal: ${goal.goalId} - ${goal.description}")
        
        goal.status = GoalStatus.IN_PROGRESS
        
        try {
            // 1. 获取能力令牌
            val tokens = acquireCapabilityTokens(goal, context)
            if (tokens == null) {
                return GoalExecutionResult(
                    status = GoalExecutionStatus.TOKEN_DENIED,
                    failureReason = "Failed to acquire capability tokens"
                )
            }
            
            // 2. 检查预算
            if (isBudgetExceeded(context, budgetUsage)) {
                releaseCapabilityTokens(tokens, context)
                return GoalExecutionResult(
                    status = GoalExecutionStatus.BUDGET_EXCEEDED,
                    failureReason = "Budget limit exceeded"
                )
            }
            
            // 3. 编排动作
            val actions = orchestrateActions(goal, context)
            
            // 4. 执行动作
            val actionResults = executeActions(actions, context, budgetUsage)
            
            // 5. 断言结果
            val assertionResult = assertResults(goal, actionResults, context)
            if (!assertionResult.passed) {
                releaseCapabilityTokens(tokens, context)
                return GoalExecutionResult(
                    status = GoalExecutionStatus.ASSERTION_FAILED,
                    failureReason = assertionResult.reason
                )
            }
            
            // 6. 释放能力令牌
            releaseCapabilityTokens(tokens, context)
            
            return GoalExecutionResult(
                status = GoalExecutionStatus.SUCCESS,
                failureReason = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Goal execution failed", e)
            return GoalExecutionResult(
                status = GoalExecutionStatus.FAILED,
                failureReason = e.message
            )
        }
    }
    
    /**
     * 获取能力令牌
     */
    private fun acquireCapabilityTokens(
        goal: GoalNode,
        context: ExecutionContext
    ): List<CapabilityTokenInfo>? {
        Log.d(TAG, "Acquiring capability tokens for: ${goal.requiredCapabilities.joinToString()}")
        
        val tokens = mutableListOf<CapabilityTokenInfo>()
        
        for (capability in goal.requiredCapabilities) {
            try {
                // TODO: 调用 ACB 获取能力令牌
                // val token = runtimeClient.acquireCapabilityToken(
                //     agentId = context.agentId,
                //     capability = capability,
                //     scopeIntentId = context.turnId
                // )
                
                // 临时实现：模拟令牌生成
                val token = CapabilityTokenInfo(
                    tokenId = "token_${capability}_${System.currentTimeMillis()}",
                    capability = capability,
                    expiresAt = System.currentTimeMillis() + 60_000, // 60s TTL
                    scopeIntentId = context.turnId
                )
                
                tokens.add(token)
                Log.d(TAG, "Acquired token for capability: $capability")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire token for capability: $capability", e)
                // 失败时释放已获取的令牌
                releaseCapabilityTokens(tokens, context)
                return null
            }
        }
        
        return tokens
    }
    
    /**
     * 释放能力令牌
     */
    private fun releaseCapabilityTokens(
        tokens: List<CapabilityTokenInfo>,
        context: ExecutionContext
    ) {
        Log.d(TAG, "Releasing ${tokens.size} capability tokens")
        
        for (token in tokens) {
            try {
                // TODO: 调用 ACB 释放能力令牌
                // runtimeClient.releaseCapabilityToken(
                //     agentId = context.agentId,
                //     tokenId = token.tokenId
                // )
                
                Log.d(TAG, "Released token: ${token.tokenId}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release token: ${token.tokenId}", e)
            }
        }
    }
    
    /**
     * 检查预算是否超限
     */
    private fun isBudgetExceeded(
        context: ExecutionContext,
        budgetUsage: BudgetUsage
    ): Boolean {
        // TODO: 从 ACB 获取预算信息并检查
        // val budget = runtimeClient.getAgentBudget(context.agentId)
        // return budgetUsage.tokensUsed >= budget.maxTokens ||
        //        budgetUsage.energyUsed >= budget.maxEnergyJoules ||
        //        budgetUsage.wallTimeUsed >= budget.maxWallTimeMs
        
        // 临时实现：暂不检查预算
        return false
    }
    
    /**
     * 编排动作
     */
    private fun orchestrateActions(
        goal: GoalNode,
        context: ExecutionContext
    ): List<ActionPlan> {
        Log.d(TAG, "Orchestrating actions for goal: ${goal.description}")
        
        // TODO: 根据目标类型和能力生成动作序列
        // 临时实现：返回占位动作
        return listOf(
            ActionPlan(
                actionType = "placeholder_action",
                parameters = mapOf("goal_id" to goal.goalId),
                expectedResult = "success"
            )
        )
    }
    
    /**
     * 执行动作
     */
    private fun executeActions(
        actions: List<ActionPlan>,
        context: ExecutionContext,
        budgetUsage: BudgetUsage
    ): List<ActionExecutionResult> {
        Log.d(TAG, "Executing ${actions.size} actions")
        
        val results = mutableListOf<ActionExecutionResult>()
        
        for (action in actions) {
            try {
                val result = if (action.actionType.startsWith("dmi.")) {
                    // DMI 工具调用
                    executeDmiAction(action, context)
                } else {
                    // 普通动作执行（通过 A3.2 执行中介）
                    executeNormalAction(action, context)
                }
                
                // 更新预算使用情况
                budgetUsage.tokensUsed += result.tokensConsumed
                budgetUsage.energyUsed += result.energyConsumed
                budgetUsage.wallTimeUsed += result.wallTimeMs
                
                results.add(result)
                Log.d(TAG, "Action executed: ${action.actionType}, status=${result.status}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Action execution failed: ${action.actionType}", e)
                results.add(
                    ActionExecutionResult(
                        actionType = action.actionType,
                        status = "failed",
                        result = mapOf("error" to e.message),
                        tokensConsumed = 0,
                        energyConsumed = 0,
                        wallTimeMs = 0
                    )
                )
            }
        }
        
        return results
    }
    
    /**
     * 执行 DMI 动作
     */
    private fun executeDmiAction(
        action: ActionPlan,
        context: ExecutionContext
    ): ActionExecutionResult {
        Log.d(TAG, "Executing DMI action: ${action.actionType}")
        
        val startTime = System.currentTimeMillis()
        
        // 获取 DmiExecutor
        val dmiExecutor = application.getDmiExecutor()
        
        // 构建参数 JSON
        val parametersJson = org.json.JSONObject(action.parameters).toString()
        
        // 同步执行 DMI 工具（使用 CountDownLatch 等待）
        var executionResult: ActionExecutionResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        
        dmiExecutor.executeDmiTool(
            requestId = "${context.turnId}_${action.actionType}_${System.currentTimeMillis()}",
            toolName = action.actionType,
            parametersJson = parametersJson,
            callback = object : com.genton.brain.dmi.DmiExecutionCallback {
                override fun onSuccess(resultJson: String) {
                    val wallTime = System.currentTimeMillis() - startTime
                    executionResult = ActionExecutionResult(
                        actionType = action.actionType,
                        status = "success",
                        result = parseJsonToMap(resultJson),
                        tokensConsumed = 5,  // DMI 不消耗 LLM token
                        energyConsumed = 3,
                        wallTimeMs = wallTime
                    )
                    latch.countDown()
                }
                
                override fun onFailure(errorCode: Int, errorMessage: String) {
                    val wallTime = System.currentTimeMillis() - startTime
                    executionResult = ActionExecutionResult(
                        actionType = action.actionType,
                        status = "failed",
                        result = mapOf(
                            "error" to errorMessage,
                            "errorCode" to errorCode
                        ),
                        tokensConsumed = 0,
                        energyConsumed = 0,
                        wallTimeMs = wallTime
                    )
                    latch.countDown()
                }
            }
        )
        
        // 等待执行完成（超时 30 秒）
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        
        return executionResult ?: ActionExecutionResult(
            actionType = action.actionType,
            status = "timeout",
            result = mapOf("error" to "DMI execution timeout"),
            tokensConsumed = 0,
            energyConsumed = 0,
            wallTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    /**
     * 执行普通动作
     */
    private fun executeNormalAction(
        action: ActionPlan,
        context: ExecutionContext
    ): ActionExecutionResult {
        // TODO: 调用 A3.2 执行中介执行动作
        
        // 临时实现：模拟动作执行
        return ActionExecutionResult(
            actionType = action.actionType,
            status = "success",
            result = mapOf("message" to "Action executed successfully"),
            tokensConsumed = 10,
            energyConsumed = 5,
            wallTimeMs = 100
        )
    }
    
    /**
     * 解析 JSON 字符串为 Map
     */
    private fun parseJsonToMap(jsonString: String): Map<String, Any> {
        return try {
            val json = org.json.JSONObject(jsonString)
            val map = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.get(key)
            }
            map
        } catch (e: Exception) {
            mapOf("result" to jsonString)
        }
    }
    
    /**
     * 断言结果
     */
    private fun assertResults(
        goal: GoalNode,
        actionResults: List<ActionExecutionResult>,
        context: ExecutionContext
    ): AssertionResult {
        Log.d(TAG, "Asserting results for goal: ${goal.description}")
        
        // 检查所有动作是否成功
        val failedActions = actionResults.filter { it.status != "success" }
        if (failedActions.isNotEmpty()) {
            return AssertionResult(
                passed = false,
                reason = "Actions failed: ${failedActions.map { it.actionType }.joinToString()}"
            )
        }
        
        // TODO: 实现更复杂的断言逻辑
        // - 验证动作结果是否符合预期
        // - 验证系统状态变化
        // - 验证副作用
        
        return AssertionResult(
            passed = true,
            reason = null
        )
    }
    
    /**
     * 获取拓扑排序（按 Goal DAG 依赖顺序）
     */
    private fun getTopologicalOrder(goalDAG: GoalDAG): List<String> {
        val order = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        
        // 从根目标开始 DFS
        val rootGoal = goalDAG.getRootGoal()
        if (rootGoal != null) {
            dfs(rootGoal.goalId, goalDAG, visited, order)
        }
        
        return order
    }
    
    /**
     * 深度优先搜索（DFS）
     */
    private fun dfs(
        goalId: String,
        goalDAG: GoalDAG,
        visited: MutableSet<String>,
        order: MutableList<String>
    ) {
        if (visited.contains(goalId)) {
            return
        }
        
        visited.add(goalId)
        
        // 先访问子目标
        val children = goalDAG.getChildGoals(goalId)
        for (child in children) {
            dfs(child.goalId, goalDAG, visited, order)
        }
        
        // 后序遍历：子目标完成后再执行父目标
        order.add(goalId)
    }
}

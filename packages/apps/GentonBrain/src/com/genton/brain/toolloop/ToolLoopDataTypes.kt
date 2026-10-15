package com.genton.brain.toolloop

import com.genton.brain.planner.GoalDAG
import com.genton.brain.security.SecurityInspector
import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行上下文
 */
data class ExecutionContext(
    val turnId: String,
    val agentId: String,
    val goalDAG: GoalDAG,
    val startTime: Long,
    val securityInspection: SecurityInspector.InspectionResult? = null
)

/**
 * 执行结果
 */
data class ExecutionResult(
    val turnId: String,
    val status: Int,
    val failureReason: String?,
    val completedGoals: List<String>,
    val budgetUsage: BudgetUsage
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("turn_id", turnId)
        json.put("status", status)
        json.put("failure_reason", failureReason ?: JSONObject.NULL)
        
        val goalsArray = JSONArray()
        for (goalId in completedGoals) {
            goalsArray.put(goalId)
        }
        json.put("completed_goals", goalsArray)
        json.put("budget_usage", budgetUsage.toJson())
        
        return json.toString(2)
    }
}

/**
 * 预算使用情况
 */
data class BudgetUsage(
    var tokensUsed: Long = 0,
    var energyUsed: Long = 0,
    var wallTimeUsed: Long = 0,
    var costUsed: Long = 0
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("tokens_used", tokensUsed)
        json.put("energy_used", energyUsed)
        json.put("wall_time_used", wallTimeUsed)
        json.put("cost_used", costUsed)
        return json
    }
}

/**
 * 目标执行结果
 */
data class GoalExecutionResult(
    val status: GoalExecutionStatus,
    val failureReason: String?
)

/**
 * 目标执行状态
 */
enum class GoalExecutionStatus {
    SUCCESS,            // 成功
    FAILED,             // 失败
    BUDGET_EXCEEDED,    // 预算超限
    TOKEN_DENIED,       // 令牌被拒
    ASSERTION_FAILED    // 断言失败
}

/**
 * 能力令牌信息
 */
data class CapabilityTokenInfo(
    val tokenId: String,
    val capability: String,
    val expiresAt: Long,
    val scopeIntentId: String
)

/**
 * 动作计划
 */
data class ActionPlan(
    val actionType: String,
    val parameters: Map<String, Any>,
    val expectedResult: String
)

/**
 * 动作执行结果
 */
data class ActionExecutionResult(
    val actionType: String,
    val status: String,
    val result: Map<String, Any?>,
    val tokensConsumed: Long,
    val energyConsumed: Long,
    val wallTimeMs: Long
)

/**
 * 断言结果
 */
data class AssertionResult(
    val passed: Boolean,
    val reason: String?
)

package com.genton.brain.toolloop

/**
 * 动作计划
 * 
 * 表示一个待执行的动作及其参数
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
    val result: Map<String, Any>,
    val tokensConsumed: Int,
    val energyConsumed: Int,
    val wallTimeMs: Long
)

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
    SUCCESS,
    FAILED,
    BUDGET_EXCEEDED,
    TOKEN_DENIED,
    ASSERTION_FAILED
}

/**
 * 执行上下文
 */
data class ExecutionContext(
    val turnId: String,
    val agentId: String,
    val goalDAG: com.genton.brain.planner.GoalDAG,
    val startTime: Long,
    val securityInspection: com.genton.brain.security.SecurityInspector.InspectionResult?
)

/**
 * 执行结果
 */
data class ExecutionResult(
    val turnId: String,
    val status: Int,
    val failureReason: String?,
    val completedGoals: List<String>,
    val budgetUsage: BudgetUsage,
    val finalText: String? = null,
    val actionCardIds: List<String>? = null,
    val tokensConsumed: Int = 0,
    val auditRef: String? = null
)

/**
 * 预算使用情况
 */
data class BudgetUsage(
    var tokensUsed: Int = 0,
    var energyUsed: Int = 0,
    var wallTimeUsed: Long = 0
)

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
 * 断言结果
 */
data class AssertionResult(
    val passed: Boolean,
    val reason: String?
)

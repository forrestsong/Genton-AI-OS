package com.genton.brain.session

import com.genton.agent.TurnResultSummary

/**
 * 执行结果（内部使用）
 */
data class ExecutionResult(
    val status: Int,
    val finalText: String?,
    val actionCardIds: List<String>?,
    val tokensConsumed: Int,
    val auditRef: String?
)

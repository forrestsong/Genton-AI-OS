package com.genton.brain.planner

/**
 * 目标节点
 */
data class GoalNode(
    val goalId: String,
    val description: String,
    val requiredCapabilities: List<String> = emptyList(),
    var status: GoalStatus = GoalStatus.PENDING,
    val actions: List<com.genton.brain.toolloop.ActionPlan> = emptyList()
)

/**
 * 目标状态
 */
enum class GoalStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED
}

/**
 * 目标 DAG
 */
data class GoalDAG(
    val goals: List<GoalNode>,
    val edges: Map<String, List<String>> = emptyMap()
) {
    fun getRootGoal(): GoalNode? {
        return goals.firstOrNull()
    }
    
    fun getChildGoals(goalId: String): List<GoalNode> {
        val childIds = edges[goalId] ?: emptyList()
        return goals.filter { it.goalId in childIds }
    }
}

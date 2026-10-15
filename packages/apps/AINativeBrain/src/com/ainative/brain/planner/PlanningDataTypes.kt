package com.ainative.brain.planner

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规划结果
 */
data class PlanningResult(
    val turnId: String,
    val intentType: String,
    val goalDAG: GoalDAG,
    val escalation: EscalationDecision,
    val timestamp: Long
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("turn_id", turnId)
        json.put("intent_type", intentType)
        json.put("goal_dag", goalDAG.toJson())
        json.put("escalation", escalation.toJson())
        json.put("timestamp", timestamp)
        return json.toString(2)
    }
}

/**
 * Goal DAG（目标有向无环图）
 */
data class GoalDAG(
    val turnId: String,
    val goals: List<GoalNode>,
    val rootGoalId: String
) {
    /**
     * 获取顶层目标
     */
    fun getRootGoal(): GoalNode? {
        return goals.find { it.goalId == rootGoalId }
    }
    
    /**
     * 获取子目标
     */
    fun getChildGoals(parentGoalId: String): List<GoalNode> {
        return goals.filter { it.parentGoalId == parentGoalId }
    }
    
    /**
     * 获取最大深度
     */
    fun getMaxDepth(): Int {
        return goals.maxOfOrNull { it.depth } ?: 0
    }
    
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("turn_id", turnId)
        json.put("root_goal_id", rootGoalId)
        
        val goalsArray = JSONArray()
        for (goal in goals) {
            goalsArray.put(goal.toJson())
        }
        json.put("goals", goalsArray)
        
        return json
    }
}

/**
 * 目标节点
 */
data class GoalNode(
    val goalId: String,
    val parentGoalId: String?,
    val description: String,
    var status: GoalStatus,
    val requiredCapabilities: List<String>,
    val depth: Int
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("goal_id", goalId)
        json.put("parent_goal_id", parentGoalId ?: JSONObject.NULL)
        json.put("description", description)
        json.put("status", status.name)
        
        val capsArray = JSONArray()
        for (cap in requiredCapabilities) {
            capsArray.put(cap)
        }
        json.put("required_capabilities", capsArray)
        json.put("depth", depth)
        
        return json
    }
}

/**
 * 目标状态
 */
enum class GoalStatus {
    PLANNED,        // 已计划
    IN_PROGRESS,    // 进行中
    DONE,           // 已完成
    FAILED,         // 失败
    SUPERSEDED      // 被取代
}

/**
 * 升级决策
 */
data class EscalationDecision(
    val shouldEscalate: Boolean,
    val reasons: List<String>,
    val estimatedComplexity: String
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("should_escalate", shouldEscalate)
        json.put("estimated_complexity", estimatedComplexity)
        
        val reasonsArray = JSONArray()
        for (reason in reasons) {
            reasonsArray.put(reason)
        }
        json.put("reasons", reasonsArray)
        
        return json
    }
}

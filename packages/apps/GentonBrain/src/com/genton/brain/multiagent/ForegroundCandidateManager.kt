package com.genton.brain.multiagent

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 前台候选管理器
 * 
 * 职责：
 * 1. 维护前台候选队列（基于优先级和活跃度排序）
 * 2. 管理单一前台约束
 * 3. 提供候选 Agent 推荐
 * 4. 处理前台切换请求
 * 
 * B3.3 多 Agent 托管 - 前台候选管理
 */
class ForegroundCandidateManager(
    private val instanceManager: AgentInstanceManager
) {
    
    companion object {
        private const val TAG = "ForegroundCandidateManager"
        
        // 候选列表最大长度
        private const val MAX_CANDIDATES = 10
        
        // 活跃度权重（用于排序）
        private const val PRIORITY_WEIGHT = 0.7f
        private const val RECENCY_WEIGHT = 0.3f
    }
    
    // 候选列表（按得分排序）
    private val candidates = mutableListOf<CandidateEntry>()
    
    // 读写锁
    private val lock = ReentrantReadWriteLock()
    
    // 候选评分缓存
    private val scoreCache = ConcurrentHashMap<String, Float>()
    
    /**
     * 更新候选列表
     */
    fun updateCandidates() = lock.write {
        Log.d(TAG, "Updating foreground candidates")
        
        val allAgents = instanceManager.getAllAgents()
        
        // 过滤可以成为前台候选的 Agent
        val eligibleAgents = allAgents.filter { agent ->
            agent.canSwitchToForeground() || agent.isForeground()
        }
        
        // 计算每个 Agent 的得分
        val scored = eligibleAgents.map { agent ->
            val score = calculateScore(agent)
            scoreCache[agent.agentId] = score
            
            CandidateEntry(
                agentId = agent.agentId,
                displayName = agent.displayName,
                score = score,
                state = agent.state,
                lastActiveAt = agent.lastActiveAt,
                priority = agent.priority
            )
        }
        
        // 按得分降序排序，取前 N 个
        candidates.clear()
        candidates.addAll(
            scored.sortedByDescending { it.score }
                .take(MAX_CANDIDATES)
        )
        
        Log.i(TAG, "Candidates updated: ${candidates.size} agents")
    }
    
    /**
     * 获取候选列表
     */
    fun getCandidates(): List<CandidateEntry> = lock.read {
        return@read candidates.toList()
    }
    
    /**
     * 获取推荐的下一个前台 Agent
     */
    fun getRecommendedNext(): CandidateEntry? = lock.read {
        val current = instanceManager.getForegroundAgent()
        
        // 返回候选列表中第一个非当前前台的 Agent
        return@read candidates.firstOrNull { it.agentId != current?.agentId }
    }
    
    /**
     * 计算 Agent 得分
     * 
     * 得分规则：
     * - 优先级占 70%
     * - 最近活跃度占 30%
     * - 前台 Agent 额外加分
     */
    private fun calculateScore(agent: AgentInstance): Float {
        // 1. 优先级得分（0-100 归一化）
        val priorityScore = agent.priority / 100f
        
        // 2. 活跃度得分（基于时间衰减）
        val now = System.currentTimeMillis()
        val timeSinceActive = now - agent.lastActiveAt
        val recencyScore = when {
            timeSinceActive < 60_000 -> 1.0f        // 1 分钟内：满分
            timeSinceActive < 300_000 -> 0.8f       // 5 分钟内：0.8
            timeSinceActive < 1800_000 -> 0.5f      // 30 分钟内：0.5
            timeSinceActive < 3600_000 -> 0.3f      // 1 小时内：0.3
            else -> 0.1f                             // 1 小时以上：0.1
        }
        
        // 3. 综合得分
        var score = priorityScore * PRIORITY_WEIGHT + recencyScore * RECENCY_WEIGHT
        
        // 4. 前台 Agent 额外加分（确保当前前台排在前面）
        if (agent.isForeground()) {
            score += 0.5f
        }
        
        return score.coerceIn(0f, 2f)
    }
    
    /**
     * 获取 Agent 得分
     */
    fun getScore(agentId: String): Float {
        return scoreCache[agentId] ?: 0f
    }
    
    /**
     * 清除得分缓存
     */
    fun clearScoreCache() {
        scoreCache.clear()
    }
}

/**
 * 候选条目
 */
data class CandidateEntry(
    val agentId: String,
    val displayName: String,
    val score: Float,
    val state: AgentState,
    val lastActiveAt: Long,
    val priority: Int
) {
    /**
     * 格式化显示
     */
    override fun toString(): String {
        return "$displayName (score=${"%.2f".format(score)}, state=$state)"
    }
}

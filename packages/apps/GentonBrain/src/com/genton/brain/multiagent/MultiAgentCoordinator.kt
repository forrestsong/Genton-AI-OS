package com.genton.brain.multiagent

import android.content.Context
import android.util.Log
import com.genton.brain.checkpoint.CheckpointManager
import com.genton.brain.runtime.AgentRuntimeClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 多 Agent 协调器
 * 
 * 职责：
 * 1. 统一协调 Agent 实例管理、切换、候选管理
 * 2. 定期更新前台候选列表
 * 3. 自动挂起空闲 Agent
 * 4. 提供统一的多 Agent 操作接口
 * 
 * B3.3 多 Agent 托管 - 统一协调层
 */
class MultiAgentCoordinator(
    context: Context,
    checkpointManager: CheckpointManager,
    runtimeClient: AgentRuntimeClient
) {
    
    companion object {
        private const val TAG = "MultiAgentCoordinator"
        
        // 候选列表更新间隔（秒）
        private const val CANDIDATE_UPDATE_INTERVAL_SEC = 30L
        
        // 空闲检查间隔（分钟）
        private const val IDLE_CHECK_INTERVAL_MIN = 5L
    }
    
    // 配置管理器
    private val config = MultiAgentConfig(context)
    
    // 实例管理器
    val instanceManager = AgentInstanceManager(context, checkpointManager, runtimeClient)
    
    // 切换协调器
    val switchCoordinator = AgentSwitchCoordinator(
        context,
        instanceManager,
        checkpointManager,
        runtimeClient
    )
    
    // 候选管理器
    private val candidateManager = ForegroundCandidateManager(instanceManager)
    
    // 定时任务调度器
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    // 是否已初始化
    @Volatile
    private var initialized = false
    
    /**
     * 初始化多 Agent 协调器
     */
    fun initialize() {
        if (initialized) {
            Log.w(TAG, "Already initialized")
            return
        }
        
        Log.i(TAG, "Initializing multi-agent coordinator...")
        
        // 打印配置
        config.logCurrentConfig()
        
        // 验证配置
        if (!config.validate()) {
            Log.e(TAG, "Invalid configuration, using defaults")
            config.resetToDefaults()
        }
        
        // 初始化默认 Agent
        instanceManager.initializeDefaultAgent()
        
        // 启动定时任务
        if (config.multiAgentEnabled) {
            startScheduledTasks()
        }
        
        initialized = true
        Log.i(TAG, "Multi-agent coordinator initialized")
    }
    
    /**
     * 关闭多 Agent 协调器
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down multi-agent coordinator...")
        
        // 停止定时任务
        scheduler.shutdown()
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
        
        initialized = false
        Log.i(TAG, "Multi-agent coordinator shut down")
    }
    
    /**
     * 启动定时任务
     */
    private fun startScheduledTasks() {
        // 定期更新候选列表
        scheduler.scheduleAtFixedRate(
            {
                try {
                    candidateManager.updateCandidates()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update candidates", e)
                }
            },
            0,
            CANDIDATE_UPDATE_INTERVAL_SEC,
            TimeUnit.SECONDS
        )
        
        // 定期检查空闲 Agent 并挂起
        scheduler.scheduleAtFixedRate(
            {
                try {
                    checkAndSuspendIdleAgents()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check idle agents", e)
                }
            },
            IDLE_CHECK_INTERVAL_MIN,
            IDLE_CHECK_INTERVAL_MIN,
            TimeUnit.MINUTES
        )
        
        Log.i(TAG, "Scheduled tasks started")
    }
    
    /**
     * 检查并挂起空闲 Agent
     */
    private fun checkAndSuspendIdleAgents() {
        val idleThresholdMs = config.autoSuspendIdleMin * 60 * 1000L
        val now = System.currentTimeMillis()
        
        val allAgents = instanceManager.getAllAgents()
        
        for (agent in allAgents) {
            // 跳过前台和已挂起的 Agent
            if (agent.isForeground() || agent.isSuspended()) {
                continue
            }
            
            // 检查是否空闲超过阈值
            val idleTime = now - agent.lastActiveAt
            if (idleTime > idleThresholdMs) {
                Log.i(TAG, "Auto-suspending idle agent: ${agent.agentId} (idle=${idleTime}ms)")
                instanceManager.suspendAgent(agent.agentId)
            }
        }
    }
    
    /**
     * 注册 Agent
     */
    fun registerAgent(
        agentId: String,
        displayName: String,
        packageName: String,
        type: AgentType = AgentType.THIRD_PARTY,
        priority: Int = 50
    ): AgentInstance? {
        if (!config.multiAgentEnabled) {
            Log.w(TAG, "Multi-agent disabled, registration rejected: $agentId")
            return null
        }
        
        val instance = instanceManager.registerAgent(agentId, displayName, packageName, type)
        
        if (instance != null) {
            // 设置优先级和配额
            instance.priority = priority
            instance.memoryQuotaMb = config.defaultMemoryQuotaMb
            instance.tokenBudget = config.defaultTokenBudget
            
            // 更新候选列表
            candidateManager.updateCandidates()
        }
        
        return instance
    }
    
    /**
     * 注销 Agent
     */
    fun unregisterAgent(agentId: String): Boolean {
        val success = instanceManager.unregisterAgent(agentId)
        
        if (success) {
            // 更新候选列表
            candidateManager.updateCandidates()
        }
        
        return success
    }
    
    /**
     * 切换到指定 Agent
     */
    fun switchToAgent(
        targetAgentId: String,
        listener: AgentSwitchCoordinator.SwitchListener? = null
    ): Boolean {
        if (!config.multiAgentEnabled) {
            Log.w(TAG, "Multi-agent disabled, switch rejected: $targetAgentId")
            listener?.onSwitchFailed(targetAgentId, "Multi-agent disabled")
            return false
        }
        
        return switchCoordinator.switchToAgent(targetAgentId, listener)
    }
    
    /**
     * 获取前台候选列表
     */
    fun getForegroundCandidates(): List<CandidateEntry> {
        return candidateManager.getCandidates()
    }
    
    /**
     * 获取推荐的下一个前台 Agent
     */
    fun getRecommendedNextAgent(): CandidateEntry? {
        return candidateManager.getRecommendedNext()
    }
    
    /**
     * 获取当前前台 Agent
     */
    fun getForegroundAgent(): AgentInstance? {
        return instanceManager.getForegroundAgent()
    }
    
    /**
     * 获取所有 Agent
     */
    fun getAllAgents(): List<AgentInstance> {
        return instanceManager.getAllAgents()
    }
    
    /**
     * 获取 Agent 实例
     */
    fun getAgent(agentId: String): AgentInstance? {
        return instanceManager.getAgent(agentId)
    }
    
    /**
     * 获取配置
     */
    fun getConfig(): MultiAgentConfig {
        return config
    }
    
    /**
     * 强制更新候选列表
     */
    fun updateCandidates() {
        candidateManager.updateCandidates()
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): MultiAgentStatistics {
        val allAgents = instanceManager.getAllAgents()
        
        return MultiAgentStatistics(
            totalAgents = allAgents.size,
            foregroundAgents = allAgents.count { it.isForeground() },
            backgroundAgents = allAgents.count { it.isBackground() },
            suspendedAgents = allAgents.count { it.isSuspended() },
            candidatesCount = candidateManager.getCandidates().size,
            totalMemoryMb = allAgents.sumOf { it.memoryQuotaMb },
            totalTokensUsed = allAgents.sumOf { it.tokenUsed }
        )
    }
}

/**
 * 多 Agent 统计信息
 */
data class MultiAgentStatistics(
    val totalAgents: Int,
    val foregroundAgents: Int,
    val backgroundAgents: Int,
    val suspendedAgents: Int,
    val candidatesCount: Int,
    val totalMemoryMb: Int,
    val totalTokensUsed: Int
) {
    override fun toString(): String {
        return """
            Total: $totalAgents
            Foreground: $foregroundAgents
            Background: $backgroundAgents
            Suspended: $suspendedAgents
            Candidates: $candidatesCount
            Memory: ${totalMemoryMb}MB
            Tokens Used: $totalTokensUsed
        """.trimIndent()
    }
}

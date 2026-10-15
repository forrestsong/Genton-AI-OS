package com.ainative.brain.multiagent

import android.content.Context
import android.util.Log
import com.ainative.brain.checkpoint.CheckpointManager
import com.ainative.brain.runtime.AgentRuntimeClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Agent 实例管理器
 * 
 * 职责：
 * 1. 多 Agent 实例注册与注销
 * 2. 实例隔离（进程/存储/配额）
 * 3. 生命周期管理（创建/销毁/挂起/恢复）
 * 4. 前台 Agent 管理
 * 5. 资源配额执行
 * 
 * 线程安全：所有公共方法都使用 ReadWriteLock 保护
 */
class AgentInstanceManager(
    private val context: Context,
    private val checkpointManager: CheckpointManager,
    private val runtimeClient: AgentRuntimeClient
) {
    companion object {
        private const val TAG = "AgentInstanceManager"
        
        // 最大 Agent 实例数
        private const val MAX_AGENT_INSTANCES = 5
        
        // 最大后台 Agent 数（其余会被挂起）
        private const val MAX_BACKGROUND_AGENTS = 2
        
        // 默认 Agent ID
        private const val DEFAULT_AGENT_ID = "system_default"
    }
    
    // Agent 实例映射表
    private val instances = ConcurrentHashMap<String, AgentInstance>()
    
    // 读写锁
    private val lock = ReentrantReadWriteLock()
    
    // 当前前台 Agent ID
    @Volatile
    private var foregroundAgentId: String? = null
    
    /**
     * 注册 Agent 实例
     */
    fun registerAgent(
        agentId: String,
        displayName: String,
        packageName: String,
        type: AgentType
    ): AgentInstance? = lock.write {
        Log.i(TAG, "Registering agent: $agentId (type=$type)")
        
        // 检查是否已注册
        if (instances.containsKey(agentId)) {
            Log.w(TAG, "Agent already registered: $agentId")
            return@write instances[agentId]
        }
        
        // 检查实例数上限
        if (instances.size >= MAX_AGENT_INSTANCES) {
            Log.e(TAG, "Max agent instances reached: $MAX_AGENT_INSTANCES")
            
            // 尝试清理最久未使用的挂起 Agent
            if (!evictOldestSuspendedAgent()) {
                Log.e(TAG, "Cannot evict agent, registration failed")
                return@write null
            }
        }
        
        // 创建新实例
        val instance = AgentInstance(
            agentId = agentId,
            displayName = displayName,
            packageName = packageName,
            type = type,
            state = AgentState.INITIALIZING
        )
        
        instances[agentId] = instance
        
        Log.i(TAG, "Agent registered: $agentId (total=${instances.size})")
        return@write instance
    }
    
    /**
     * 注销 Agent 实例
     */
    fun unregisterAgent(agentId: String): Boolean = lock.write {
        Log.i(TAG, "Unregistering agent: $agentId")
        
        val instance = instances.remove(agentId)
        if (instance == null) {
            Log.w(TAG, "Agent not found: $agentId")
            return@write false
        }
        
        // 清理资源
        cleanupAgentResources(instance)
        
        // 如果是前台 Agent，清除前台状态
        if (foregroundAgentId == agentId) {
            foregroundAgentId = null
        }
        
        Log.i(TAG, "Agent unregistered: $agentId")
        return@write true
    }
    
    /**
     * 获取 Agent 实例
     */
    fun getAgent(agentId: String): AgentInstance? = lock.read {
        return@read instances[agentId]
    }
    
    /**
     * 获取所有 Agent 实例
     */
    fun getAllAgents(): List<AgentInstance> = lock.read {
        return@read instances.values.toList()
    }
    
    /**
     * 获取前台 Agent
     */
    fun getForegroundAgent(): AgentInstance? = lock.read {
        val agentId = foregroundAgentId ?: return@read null
        return@read instances[agentId]
    }
    
    /**
     * 设置前台 Agent
     */
    fun setForegroundAgent(agentId: String): Boolean = lock.write {
        Log.i(TAG, "Setting foreground agent: $agentId")
        
        val instance = instances[agentId]
        if (instance == null) {
            Log.w(TAG, "Agent not found: $agentId")
            return@write false
        }
        
        if (!instance.canSwitchToForeground()) {
            Log.w(TAG, "Agent cannot switch to foreground: $agentId (state=${instance.state})")
            return@write false
        }
        
        // 将当前前台 Agent 切换到后台
        val currentForeground = foregroundAgentId
        if (currentForeground != null && currentForeground != agentId) {
            val currentInstance = instances[currentForeground]
            if (currentInstance != null && currentInstance.isForeground()) {
                currentInstance.state = AgentState.BACKGROUND
                Log.i(TAG, "Agent moved to background: $currentForeground")
            }
        }
        
        // 设置新前台 Agent
        instance.state = AgentState.FOREGROUND
        instance.updateActivity()
        foregroundAgentId = agentId
        
        // 检查后台 Agent 数量，必要时挂起
        manageBackgroundAgents()
        
        Log.i(TAG, "Foreground agent set: $agentId")
        return@write true
    }
    
    /**
     * 将 Agent 移到后台
     */
    fun moveToBackground(agentId: String): Boolean = lock.write {
        Log.i(TAG, "Moving agent to background: $agentId")
        
        val instance = instances[agentId]
        if (instance == null) {
            Log.w(TAG, "Agent not found: $agentId")
            return@write false
        }
        
        if (!instance.isForeground()) {
            Log.w(TAG, "Agent is not foreground: $agentId")
            return@write false
        }
        
        instance.state = AgentState.BACKGROUND
        instance.updateActivity()
        
        if (foregroundAgentId == agentId) {
            foregroundAgentId = null
        }
        
        // 检查后台 Agent 数量
        manageBackgroundAgents()
        
        Log.i(TAG, "Agent moved to background: $agentId")
        return@write true
    }
    
    /**
     * 挂起 Agent（冷切换）
     */
    fun suspendAgent(agentId: String): Boolean = lock.write {
        Log.i(TAG, "Suspending agent: $agentId")
        
        val instance = instances[agentId]
        if (instance == null) {
            Log.w(TAG, "Agent not found: $agentId")
            return@write false
        }
        
        if (instance.isForeground()) {
            Log.w(TAG, "Cannot suspend foreground agent: $agentId")
            return@write false
        }
        
        if (instance.isSuspended()) {
            Log.i(TAG, "Agent already suspended: $agentId")
            return@write true
        }
        
        try {
            // 1. 创建 checkpoint
            val checkpoint = createAgentCheckpoint(instance)
            instance.checkpointId = checkpoint.checkpointId
            
            // 2. 保存 UI 状态截图（如果有）
            // TODO: 通过 IAgentUiTreeService 捕获 Surface 截图
            // instance.surfaceSnapshot = captureAgentSurface(instance)
            
            // 3. 释放内存资源
            releaseAgentMemory(instance)
            
            // 4. 更新状态
            instance.state = AgentState.SUSPENDED
            instance.acbPointer = null  // 清除 ACB 指针
            
            Log.i(TAG, "Agent suspended: $agentId (checkpoint=${checkpoint.checkpointId})")
            return@write true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend agent: $agentId", e)
            return@write false
        }
    }
    
    /**
     * 恢复 Agent（从挂起状态）
     */
    fun resumeAgent(agentId: String): Boolean = lock.write {
        Log.i(TAG, "Resuming agent: $agentId")
        
        val instance = instances[agentId]
        if (instance == null) {
            Log.w(TAG, "Agent not found: $agentId")
            return@write false
        }
        
        if (!instance.isSuspended()) {
            Log.w(TAG, "Agent is not suspended: $agentId (state=${instance.state})")
            return@write false
        }
        
        try {
            // 1. 恢复 checkpoint
            val checkpointId = instance.checkpointId
            if (checkpointId != null) {
                val checkpoint = checkpointManager.restoreCheckpoint(checkpointId)
                if (checkpoint == null) {
                    Log.e(TAG, "Failed to restore checkpoint: $checkpointId")
                    return@write false
                }
                
                // 恢复 ACB 指针
                instance.acbPointer = checkpoint.agentId
            }
            
            // 2. 恢复 UI 状态（如果有截图）
            // TODO: 恢复 Surface 状态
            
            // 3. 更新状态
            instance.state = AgentState.BACKGROUND
            instance.updateActivity()
            
            Log.i(TAG, "Agent resumed: $agentId")
            return@write true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume agent: $agentId", e)
            return@write false
        }
    }
    
    /**
     * 销毁 Agent
     */
    fun destroyAgent(agentId: String): Boolean = lock.write {
        Log.i(TAG, "Destroying agent: $agentId")
        
        val instance = instances[agentId]
        if (instance == null) {
            Log.w(TAG, "Agent not found: $agentId")
            return@write false
        }
        
        // 清理资源
        cleanupAgentResources(instance)
        
        // 更新状态
        instance.state = AgentState.DESTROYED
        
        // 从映射表中移除
        instances.remove(agentId)
        
        if (foregroundAgentId == agentId) {
            foregroundAgentId = null
        }
        
        Log.i(TAG, "Agent destroyed: $agentId")
        return@write true
    }
    
    /**
     * 管理后台 Agent（超过上限时挂起最久未使用的）
     */
    private fun manageBackgroundAgents() {
        val backgroundAgents = instances.values
            .filter { it.isBackground() }
            .sortedBy { it.lastActiveAt }
        
        val toSuspend = backgroundAgents.size - MAX_BACKGROUND_AGENTS
        if (toSuspend > 0) {
            Log.i(TAG, "Too many background agents ($backgroundAgents.size), suspending $toSuspend")
            
            backgroundAgents.take(toSuspend).forEach { agent ->
                Log.i(TAG, "Auto-suspending idle agent: ${agent.agentId}")
                suspendAgent(agent.agentId)
            }
        }
    }
    
    /**
     * 驱逐最久未使用的挂起 Agent
     */
    private fun evictOldestSuspendedAgent(): Boolean {
        val suspendedAgents = instances.values
            .filter { it.isSuspended() }
            .sortedBy { it.lastActiveAt }
        
        val oldest = suspendedAgents.firstOrNull()
        if (oldest != null) {
            Log.i(TAG, "Evicting oldest suspended agent: ${oldest.agentId}")
            return destroyAgent(oldest.agentId)
        }
        
        return false
    }
    
    /**
     * 创建 Agent checkpoint
     */
    private fun createAgentCheckpoint(instance: AgentInstance): com.ainative.brain.checkpoint.Checkpoint {
        // 序列化 Agent 状态
        val state = buildString {
            append("agentId=${instance.agentId}\n")
            append("sessionId=${instance.sessionId}\n")
            append("acbPointer=${instance.acbPointer}\n")
            append("surfaceToken=${instance.surfaceToken}\n")
            append("lastActiveAt=${instance.lastActiveAt}\n")
        }
        
        return checkpointManager.createCheckpoint(instance.agentId, state)
    }
    
    /**
     * 释放 Agent 内存
     */
    private fun releaseAgentMemory(instance: AgentInstance) {
        try {
            // 释放运行时资源
            runtimeClient.releaseAgentResources(instance.agentId)
            
            // 清除 Surface
            instance.surfaceToken = null
            
            Log.i(TAG, "Agent memory released: ${instance.agentId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release agent memory: ${instance.agentId}", e)
        }
    }
    
    /**
     * 清理 Agent 资源
     */
    private fun cleanupAgentResources(instance: AgentInstance) {
        try {
            // 1. 删除 checkpoint
            instance.checkpointId?.let { checkpointId ->
                checkpointManager.deleteCheckpoint(checkpointId)
            }
            
            // 2. 释放内存
            releaseAgentMemory(instance)
            
            // 3. 释放 Surface 截图
            instance.surfaceSnapshot?.recycle()
            instance.surfaceSnapshot = null
            
            Log.i(TAG, "Agent resources cleaned up: ${instance.agentId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup agent resources: ${instance.agentId}", e)
        }
    }
    
    /**
     * 初始化默认 Agent
     */
    fun initializeDefaultAgent(): AgentInstance? {
        Log.i(TAG, "Initializing default agent...")
        
        val instance = registerAgent(
            agentId = DEFAULT_AGENT_ID,
            displayName = "AI Assistant",
            packageName = context.packageName,
            type = AgentType.SYSTEM_DEFAULT
        )
        
        if (instance != null) {
            instance.state = AgentState.BACKGROUND
            setForegroundAgent(DEFAULT_AGENT_ID)
            Log.i(TAG, "Default agent initialized")
        } else {
            Log.e(TAG, "Failed to initialize default agent")
        }
        
        return instance
    }
}

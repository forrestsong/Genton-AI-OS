package com.genton.brain.session

import android.util.Log
import com.genton.brain.runtime.AgentRuntimeClient
import com.genton.brain.runtime.CheckpointData
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话状态管理器（A4.3）
 * 
 * 实现会话热/冷分级与快速切换：
 * - ACTING：前台会话（获推理/NPU预算优先）
 * - WAITING：热驻留（冻结UI、保留KV与记忆句柄，节流）
 * - SUSPENDED：持久化挂起（checkpoint保存，冷恢复≤300ms）
 * 
 * 切换性能目标：
 * - 热切换（WAITING → ACTING）：≤100ms（P95）
 * - 冷恢复（SUSPENDED → ACTING）：≤300ms（P95）
 * 
 * 核心机制：
 * 1. ACB 指针交换（热切换，指针级操作）
 * 2. checkpoint + packId 关联（冷恢复，上下文打包）
 * 3. 会话快照预显示（切换期间UX）
 */
class SessionStateManager(
    private val runtimeClient: AgentRuntimeClient
) {
    companion object {
        private const val TAG = "SessionStateManager"
        
        // 会话状态常量（对齐 S3 设计）
        const val STATE_ACTING = "ACTING"         // 前台活跃
        const val STATE_WAITING = "WAITING"       // 热驻留
        const val STATE_SUSPENDED = "SUSPENDED"   // 冷挂起
        
        // 热驻留池大小（最多保留N个热会话）
        const val MAX_HOT_SESSIONS = 3
        
        // checkpoint 自动保存阈值（会话闲置时间）
        const val AUTO_CHECKPOINT_IDLE_MS = 300000L  // 5分钟
    }
    
    // 会话状态表（sessionId -> SessionState）
    private val sessionStates = ConcurrentHashMap<String, SessionState>()
    
    // 当前前台会话
    @Volatile
    private var foregroundSessionId: String? = null
    
    // 热驻留队列（LRU顺序）
    private val hotSessionQueue = ArrayDeque<String>()
    
    /**
     * 注册新会话（初始状态为 ACTING）
     */
    fun registerSession(
        sessionId: String,
        agentId: String,
        acbPointer: String,
        packId: String? = null,
        workingMemoryIds: List<String> = emptyList()
    ) {
        val state = SessionState(
            sessionId = sessionId,
            agentId = agentId,
            acbPointer = acbPointer,
            currentState = STATE_ACTING,
            packId = packId,
            workingMemoryIds = workingMemoryIds.toMutableList(),
            lastActiveMs = System.currentTimeMillis()
        )
        
        sessionStates[sessionId] = state
        foregroundSessionId = sessionId
        
        Log.i(TAG, "Session registered: sessionId=$sessionId, state=$STATE_ACTING")
    }
    
    /**
     * 热切换：从前台切换到另一个热会话
     * 
     * @param targetSessionId 目标会话 ID
     * @return 是否切换成功
     */
    fun switchToHotSession(targetSessionId: String): Boolean {
        val startTime = System.currentTimeMillis()
        
        try {
            val targetState = sessionStates[targetSessionId]
            if (targetState == null) {
                Log.e(TAG, "Target session not found: $targetSessionId")
                return false
            }
            
            if (targetState.currentState != STATE_WAITING) {
                Log.w(TAG, "Target session is not in WAITING state: $targetSessionId (state=${targetState.currentState})")
                // 如果已经是 ACTING，直接返回成功
                if (targetState.currentState == STATE_ACTING) {
                    return true
                }
                return false
            }
            
            // 1. 将当前前台会话移到 WAITING
            val currentSessionId = foregroundSessionId
            if (currentSessionId != null && currentSessionId != targetSessionId) {
                val currentState = sessionStates[currentSessionId]
                if (currentState != null) {
                    moveToWaiting(currentState)
                }
            }
            
            // 2. 交换 ACB 指针（原子操作，核心性能路径）
            val swapSuccess = runtimeClient.swapAcbPointers(
                currentAcb = sessionStates[currentSessionId]?.acbPointer,
                targetAcb = targetState.acbPointer
            )
            
            if (!swapSuccess) {
                Log.e(TAG, "Failed to swap ACB pointers")
                return false
            }
            
            // 3. 更新目标会话状态为 ACTING
            targetState.currentState = STATE_ACTING
            targetState.lastActiveMs = System.currentTimeMillis()
            foregroundSessionId = targetSessionId
            
            // 4. 更新热队列
            hotSessionQueue.remove(targetSessionId)
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Hot switch completed: $currentSessionId -> $targetSessionId (${elapsed}ms)")
            
            // 验收：热切换应 ≤100ms
            if (elapsed > 100) {
                Log.w(TAG, "Hot switch exceeded 100ms target: ${elapsed}ms")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Hot switch failed", e)
            return false
        }
    }
    
    /**
     * 冷恢复：从 checkpoint 恢复会话
     * 
     * @param checkpointId checkpoint ID
     * @return 恢复的会话 ID，失败返回 null
     */
    fun resumeFromCheckpoint(checkpointId: String): String? {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 加载 checkpoint
            val checkpoint = runtimeClient.loadCheckpoint(checkpointId)
            
            // 2. 恢复 ACB 状态
            // TODO: 实际应通过 IAgentRuntimeService 恢复 ACB
            val restoredAcb = checkpoint.acbPointer
            
            // 3. 恢复上下文打包（packId）
            // TODO: 通过 IEntityQueryService.loadContextPack(packId) 恢复实体子图
            // 这一步是冷恢复的关键性能路径
            
            // 4. 恢复工作记忆
            // TODO: 通过 IAgentMemoryService 重新关联工作记忆 ID
            
            // 5. 创建会话状态
            val state = SessionState(
                sessionId = checkpoint.sessionId,
                agentId = extractAgentIdFromSession(checkpoint.sessionId),
                acbPointer = restoredAcb,
                currentState = STATE_ACTING,
                packId = checkpoint.packId,
                workingMemoryIds = checkpoint.workingMemoryIds.toMutableList(),
                lastActiveMs = System.currentTimeMillis(),
                checkpointId = checkpointId
            )
            
            // 6. 将当前前台会话移到 WAITING
            val currentSessionId = foregroundSessionId
            if (currentSessionId != null) {
                val currentState = sessionStates[currentSessionId]
                if (currentState != null) {
                    moveToWaiting(currentState)
                }
            }
            
            // 7. 注册恢复的会话
            sessionStates[checkpoint.sessionId] = state
            foregroundSessionId = checkpoint.sessionId
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Cold resume completed: sessionId=${checkpoint.sessionId} (${elapsed}ms)")
            
            // 验收：冷恢复应 ≤300ms
            if (elapsed > 300) {
                Log.w(TAG, "Cold resume exceeded 300ms target: ${elapsed}ms")
            }
            
            return checkpoint.sessionId
            
        } catch (e: Exception) {
            Log.e(TAG, "Cold resume failed", e)
            return null
        }
    }
    
    /**
     * 移到热驻留状态（WAITING）
     */
    private fun moveToWaiting(state: SessionState) {
        state.currentState = STATE_WAITING
        state.lastActiveMs = System.currentTimeMillis()
        
        // 加入热队列
        hotSessionQueue.addLast(state.sessionId)
        
        // LRU 淘汰：超过上限时，将最老的会话挂起
        while (hotSessionQueue.size > MAX_HOT_SESSIONS) {
            val oldestSessionId = hotSessionQueue.removeFirst()
            val oldestState = sessionStates[oldestSessionId]
            if (oldestState != null) {
                suspendSession(oldestState)
            }
        }
        
        Log.d(TAG, "Session moved to WAITING: ${state.sessionId}")
    }
    
    /**
     * 挂起会话（保存 checkpoint）
     */
    fun suspendSession(state: SessionState): String? {
        try {
            // 0. 如果 packId 为空，创建上下文打包（C3.3 集成）
            if (state.packId == null && contextPackingService != null) {
                val packId = createContextPack(state)
                if (packId != null) {
                    state.packId = packId.toString()
                    Log.d(TAG, "Created context pack for suspension: sessionId=${state.sessionId}, packId=$packId")
                }
            }
            
            // 1. 保存 checkpoint
            val checkpointId = runtimeClient.saveCheckpoint(
                sessionId = state.sessionId,
                acbPointer = state.acbPointer,
                packId = state.packId ?: "",
                workingMemoryIds = state.workingMemoryIds
            )
            
            // 2. 更新状态
            state.currentState = STATE_SUSPENDED
            state.checkpointId = checkpointId
            
            // 3. 释放 ACB 资源（可选，节省内存）
            // runtimeClient.releaseAcb(state.acbPointer)
            
            Log.i(TAG, "Session suspended: sessionId=${state.sessionId}, checkpointId=$checkpointId")
            
            return checkpointId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend session: ${state.sessionId}", e)
            return null
        }
    }
    
    /**
     * 手动保存 checkpoint（不改变状态）
     */
    fun saveCheckpoint(sessionId: String): String? {
        val state = sessionStates[sessionId] ?: return null
        
        try {
            val checkpointId = runtimeClient.saveCheckpoint(
                sessionId = state.sessionId,
                acbPointer = state.acbPointer,
                packId = state.packId ?: "",
                workingMemoryIds = state.workingMemoryIds
            )
            
            state.checkpointId = checkpointId
            
            Log.i(TAG, "Checkpoint saved: sessionId=$sessionId, checkpointId=$checkpointId")
            
            return checkpointId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save checkpoint: $sessionId", e)
            return null
        }
    }
    
    /**
     * 更新会话的 packId（上下文打包）
     */
    fun updatePackId(sessionId: String, packId: String) {
        val state = sessionStates[sessionId]
        if (state != null) {
            state.packId = packId
            Log.d(TAG, "PackId updated: sessionId=$sessionId, packId=$packId")
        }
    }
    
    /**
     * 更新工作记忆列表
     */
    fun updateWorkingMemories(sessionId: String, memoryIds: List<String>) {
        val state = sessionStates[sessionId]
        if (state != null) {
            state.workingMemoryIds.clear()
            state.workingMemoryIds.addAll(memoryIds)
            Log.d(TAG, "Working memories updated: sessionId=$sessionId, count=${memoryIds.size}")
        }
    }
    
    /**
     * 获取会话状态
     */
    fun getSessionState(sessionId: String): String? {
        return sessionStates[sessionId]?.currentState
    }
    
    /**
     * 取消注册会话
     */
    fun unregisterSession(sessionId: String) {
        val state = sessionStates.remove(sessionId)
        if (state != null) {
            hotSessionQueue.remove(sessionId)
            
            if (foregroundSessionId == sessionId) {
                foregroundSessionId = null
            }
            
            Log.i(TAG, "Session unregistered: $sessionId")
        }
    }
    
    /**
     * 自动 checkpoint：对闲置的热会话进行 checkpoint
     */
    fun autoCheckpoint() {
        val now = System.currentTimeMillis()
        
        sessionStates.values.forEach { state ->
            if (state.currentState == STATE_WAITING) {
                val idleTime = now - state.lastActiveMs
                
                if (idleTime > AUTO_CHECKPOINT_IDLE_MS && state.checkpointId == null) {
                    Log.i(TAG, "Auto checkpoint triggered: sessionId=${state.sessionId} (idle=${idleTime}ms)")
                    saveCheckpoint(state.sessionId)
                }
            }
        }
    }
    
    /**
     * 垃圾回收：清理过期的 SUSPENDED 会话
     */
    fun gcSuspendedSessions(olderThanDays: Int = 7) {
        val threshold = System.currentTimeMillis() - (olderThanDays * 86400000L)
        
        val toRemove = sessionStates.values.filter { state ->
            state.currentState == STATE_SUSPENDED && state.lastActiveMs < threshold
        }
        
        toRemove.forEach { state ->
            // 删除 checkpoint
            if (state.checkpointId != null) {
                runtimeClient.deleteCheckpoint(state.checkpointId!!)
            }
            
            unregisterSession(state.sessionId)
        }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "GC completed: removed ${toRemove.size} suspended sessions")
        }
    }
    
    /**
     * 获取当前前台会话 ID
     */
    fun getForegroundSessionId(): String? = foregroundSessionId
    
    /**
     * 获取 ACB 指针
     */
    fun getAcbPointer(sessionId: String): String? {
        return sessionStates[sessionId]?.acbPointer
    }
    
    /**
     * 获取所有会话状态（诊断用）
     */
    fun getAllSessionStates(): Map<String, String> {
        return sessionStates.mapValues { it.value.currentState }
    }
    
    private fun extractAgentIdFromSession(sessionId: String): String {
        // sessionId 格式：session_{agentId}_{timestamp}
        val parts = sessionId.split("_")
        return if (parts.size >= 2) parts[1] else "unknown"
    }
    
    /**
     * 创建上下文打包（C3.3 集成）
     * 
     * 为会话挂起时自动创建 ContextPack
     * 
     * @param state 会话状态
     * @return packId，失败返回 null
     */
    private fun createContextPack(state: SessionState): Long? {
        if (contextPackingService == null) {
            return null
        }
        
        try {
            // 构建打包请求
            // TODO: 从会话状态中提取种子实体 ID（当前工作上下文中的实体）
            val seedEntityIds = state.workingMemoryIds.mapNotNull { memoryId ->
                // 简化实现：假设 workingMemoryId 可以映射到 EntityId
                // 实际应该通过记忆服务查询记忆关联的实体
                null as EntityId?
            }
            
            if (seedEntityIds.isEmpty()) {
                Log.w(TAG, "No seed entities for context packing: sessionId=${state.sessionId}")
                return null
            }
            
            val request = ContextPackRequest(
                seedEntityIds = seedEntityIds,
                relationDepth = 2,
                maxMemories = 20,
                includeSystemMemoryPage = true,
                maxTokensHint = 4000,
                taskDescription = "Session suspension context pack",
                intentId = "checkpoint_${state.sessionId}"
            )
            
            val pack = contextPackingService.packContext(request)
            Log.i(TAG, "Context pack created: packId=${pack.packId}, entities=${pack.entities.size}")
            
            return pack.packId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create context pack", e)
            return null
        }
    }
    
    /**
     * 会话状态
     */
    private data class SessionState(
        val sessionId: String,
        val agentId: String,
        val acbPointer: String,
        var currentState: String,              // ACTING/WAITING/SUSPENDED
        var packId: String?,                   // 上下文打包 ID
        val workingMemoryIds: MutableList<String>,
        var lastActiveMs: Long,
        var checkpointId: String? = null       // checkpoint ID（SUSPENDED 时非空）
    )
}

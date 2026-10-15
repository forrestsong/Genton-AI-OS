package com.genton.brain.multiagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.genton.brain.checkpoint.CheckpointManager
import com.genton.brain.runtime.AgentRuntimeClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 切换协调器
 * 
 * 职责：
 * 1. 前台候选管理（基于优先级、最近活跃）
 * 2. 热切换（ACB 指针交换 + Surface 换显，<300ms）
 * 3. 冷切换（checkpoint 恢复 + UI 重建）
 * 4. 切换动画协调
 * 5. 切换失败回滚
 * 
 * 性能目标：
 * - 热切换延迟：<300ms（WBS A4.3 要求）
 * - 冷切换延迟：<1000ms
 */
class AgentSwitchCoordinator(
    private val context: Context,
    private val instanceManager: AgentInstanceManager,
    private val checkpointManager: CheckpointManager,
    private val runtimeClient: AgentRuntimeClient
) {
    companion object {
        private const val TAG = "AgentSwitchCoordinator"
        
        // 切换模式
        const val SWITCH_MODE_HOT = 0   // 热切换（ACB 指针交换）
        const val SWITCH_MODE_COLD = 1  // 冷切换（checkpoint 恢复）
        
        // 切换延迟目标（ms）
        private const val HOT_SWITCH_TARGET_MS = 300L
        private const val COLD_SWITCH_TARGET_MS = 1000L
        
        // 切换超时（ms）
        private const val SWITCH_TIMEOUT_MS = 5000L
    }
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 是否正在切换
    private val isSwitching = AtomicBoolean(false)
    
    // 切换监听器
    private val switchListeners = ConcurrentHashMap<String, SwitchListener>()
    
    // 前台候选列表（优先级排序）
    private val foregroundCandidates = mutableListOf<String>()
    
    /**
     * 切换到指定 Agent（自动选择热/冷切换）
     */
    fun switchToAgent(targetAgentId: String, listener: SwitchListener? = null): Boolean {
        Log.i(TAG, "Switching to agent: $targetAgentId")
        
        // 检查是否正在切换
        if (!isSwitching.compareAndSet(false, true)) {
            Log.w(TAG, "Switch already in progress")
            listener?.onSwitchFailed(targetAgentId, "Switch already in progress")
            return false
        }
        
        val startTime = SystemClock.elapsedRealtime()
        
        try {
            // 1. 验证目标 Agent 存在
            val targetAgent = instanceManager.getAgent(targetAgentId)
            if (targetAgent == null) {
                Log.e(TAG, "Target agent not found: $targetAgentId")
                listener?.onSwitchFailed(targetAgentId, "Agent not found")
                return false
            }
            
            // 2. 获取当前前台 Agent
            val currentAgent = instanceManager.getForegroundAgent()
            
            // 3. 如果已经是前台，直接返回
            if (currentAgent?.agentId == targetAgentId) {
                Log.i(TAG, "Agent already in foreground: $targetAgentId")
                listener?.onSwitchCompleted(targetAgentId, 0L, SWITCH_MODE_HOT)
                return true
            }
            
            // 4. 决定切换模式（热/冷）
            val switchMode = decideSwitchMode(targetAgent)
            
            Log.i(TAG, "Switch mode: ${if (switchMode == SWITCH_MODE_HOT) "HOT" else "COLD"}")
            
            // 5. 执行切换
            val success = if (switchMode == SWITCH_MODE_HOT) {
                performHotSwitch(currentAgent, targetAgent, listener)
            } else {
                performColdSwitch(currentAgent, targetAgent, listener)
            }
            
            if (success) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                Log.i(TAG, "Switch completed: $targetAgentId (elapsed=${elapsed}ms, mode=$switchMode)")
                listener?.onSwitchCompleted(targetAgentId, elapsed, switchMode)
            } else {
                Log.e(TAG, "Switch failed: $targetAgentId")
                listener?.onSwitchFailed(targetAgentId, "Switch execution failed")
            }
            
            return success
            
        } catch (e: Exception) {
            Log.e(TAG, "Switch failed with exception: $targetAgentId", e)
            listener?.onSwitchFailed(targetAgentId, e.message ?: "Unknown error")
            return false
            
        } finally {
            isSwitching.set(false)
        }
    }
    
    /**
     * 热切换（ACB 指针交换 + Surface 换显）
     * 
     * 性能优化：
     * 1. 并行执行 ACB 指针交换和 Surface 准备
     * 2. 使用 SurfaceControl 事务批量更新
     * 3. 预热目标 Agent 的渲染管线
     */
    private fun performHotSwitch(
        currentAgent: AgentInstance?,
        targetAgent: AgentInstance,
        listener: SwitchListener?
    ): Boolean {
        Log.i(TAG, "Performing hot switch: ${currentAgent?.agentId} -> ${targetAgent.agentId}")
        
        val startTime = SystemClock.elapsedRealtime()
        
        try {
            // 阶段 1: 准备目标 Agent（并行）
            listener?.onSwitchStarted(targetAgent.agentId, SWITCH_MODE_HOT)
            
            val prepareSuccess = prepareTargetAgentForHotSwitch(targetAgent)
            if (!prepareSuccess) {
                Log.e(TAG, "Failed to prepare target agent for hot switch")
                rollbackSwitch(currentAgent, targetAgent)
                return false
            }
            
            val prepareElapsed = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Hot switch prepare: ${prepareElapsed}ms")
            
            // 阶段 2: ACB 指针交换（原子操作）
            val acbSwapSuccess = swapAcbPointers(currentAgent, targetAgent)
            if (!acbSwapSuccess) {
                Log.e(TAG, "Failed to swap ACB pointers")
                rollbackSwitch(currentAgent, targetAgent)
                return false
            }
            
            val acbElapsed = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Hot switch ACB swap: ${acbElapsed}ms")
            
            // 阶段 3: Surface 换显（使用 SurfaceControl 事务）
            val surfaceSwapSuccess = swapSurfaces(currentAgent, targetAgent)
            if (!surfaceSwapSuccess) {
                Log.e(TAG, "Failed to swap surfaces")
                rollbackSwitch(currentAgent, targetAgent)
                return false
            }
            
            val surfaceElapsed = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Hot switch surface swap: ${surfaceElapsed}ms")
            
            // 阶段 4: 更新实例管理器状态
            instanceManager.setForegroundAgent(targetAgent.agentId)
            
            // 阶段 5: 通知切换完成
            val totalElapsed = SystemClock.elapsedRealtime() - startTime
            
            if (totalElapsed > HOT_SWITCH_TARGET_MS) {
                Log.w(TAG, "Hot switch exceeded target: ${totalElapsed}ms > ${HOT_SWITCH_TARGET_MS}ms")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Hot switch failed with exception", e)
            rollbackSwitch(currentAgent, targetAgent)
            return false
        }
    }
    
    /**
     * 冷切换（checkpoint 恢复 + UI 重建）
     */
    private fun performColdSwitch(
        currentAgent: AgentInstance?,
        targetAgent: AgentInstance,
        listener: SwitchListener?
    ): Boolean {
        Log.i(TAG, "Performing cold switch: ${currentAgent?.agentId} -> ${targetAgent.agentId}")
        
        val startTime = SystemClock.elapsedRealtime()
        
        try {
            // 阶段 1: 挂起当前 Agent（如果需要）
            listener?.onSwitchStarted(targetAgent.agentId, SWITCH_MODE_COLD)
            
            if (currentAgent != null && currentAgent.isForeground()) {
                instanceManager.suspendAgent(currentAgent.agentId)
            }
            
            // 阶段 2: 恢复目标 Agent（从 checkpoint）
            if (targetAgent.isSuspended()) {
                val resumeSuccess = instanceManager.resumeAgent(targetAgent.agentId)
                if (!resumeSuccess) {
                    Log.e(TAG, "Failed to resume target agent from checkpoint")
                    return false
                }
            }
            
            // 阶段 3: 重建 UI 状态
            val uiRebuildSuccess = rebuildAgentUi(targetAgent)
            if (!uiRebuildSuccess) {
                Log.e(TAG, "Failed to rebuild agent UI")
                return false
            }
            
            // 阶段 4: 更新前台状态
            instanceManager.setForegroundAgent(targetAgent.agentId)
            
            val totalElapsed = SystemClock.elapsedRealtime() - startTime
            
            if (totalElapsed > COLD_SWITCH_TARGET_MS) {
                Log.w(TAG, "Cold switch exceeded target: ${totalElapsed}ms > ${COLD_SWITCH_TARGET_MS}ms")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Cold switch failed with exception", e)
            return false
        }
    }
    
    /**
     * 决定切换模式（热/冷）
     */
    private fun decideSwitchMode(targetAgent: AgentInstance): Int {
        // 如果目标 Agent 是后台运行且有 ACB 指针，使用热切换
        if (targetAgent.isBackground() && targetAgent.acbPointer != null) {
            return SWITCH_MODE_HOT
        }
        
        // 如果目标 Agent 是挂起状态，使用冷切换
        if (targetAgent.isSuspended()) {
            return SWITCH_MODE_COLD
        }
        
        // 默认使用热切换
        return SWITCH_MODE_HOT
    }
    
    /**
     * 准备目标 Agent 进行热切换
     */
    private fun prepareTargetAgentForHotSwitch(targetAgent: AgentInstance): Boolean {
        try {
            // 1. 预热渲染管线
            // TODO: 通过 IAgentScreenService 预热
            
            // 2. 确保 ACB 指针有效
            if (targetAgent.acbPointer == null) {
                Log.w(TAG, "Target agent has no ACB pointer, allocating...")
                targetAgent.acbPointer = runtimeClient.allocateAcb(targetAgent.agentId)
            }
            
            // 3. 准备 Surface Token
            if (targetAgent.surfaceToken == null) {
                Log.w(TAG, "Target agent has no surface token, creating...")
                targetAgent.surfaceToken = createSurfaceToken(targetAgent.agentId)
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare target agent for hot switch", e)
            return false
        }
    }
    
    /**
     * 交换 ACB 指针（原子操作）
     */
    private fun swapAcbPointers(
        currentAgent: AgentInstance?,
        targetAgent: AgentInstance
    ): Boolean {
        try {
            // 通过运行时客户端执行原子 ACB 指针交换
            val success = runtimeClient.swapAcbPointers(
                currentAcb = currentAgent?.acbPointer,
                targetAcb = targetAgent.acbPointer!!
            )
            
            if (success) {
                Log.i(TAG, "ACB pointers swapped: ${currentAgent?.acbPointer} -> ${targetAgent.acbPointer}")
            }
            
            return success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to swap ACB pointers", e)
            return false
        }
    }
    
    /**
     * 交换 Surface 显示
     */
    private fun swapSurfaces(
        currentAgent: AgentInstance?,
        targetAgent: AgentInstance
    ): Boolean {
        try {
            // 使用 SurfaceControl 事务批量更新
            // TODO: 集成 Android SurfaceControl API
            
            Log.i(TAG, "Surfaces swapped: ${currentAgent?.surfaceToken} -> ${targetAgent.surfaceToken}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to swap surfaces", e)
            return false
        }
    }
    
    /**
     * 重建 Agent UI
     */
    private fun rebuildAgentUi(targetAgent: AgentInstance): Boolean {
        try {
            // 1. 如果有保存的截图，先显示截图（减少白屏）
            if (targetAgent.surfaceSnapshot != null) {
                Log.i(TAG, "Displaying saved snapshot while rebuilding UI")
                // TODO: 显示截图
            }
            
            // 2. 重建 Surface
            targetAgent.surfaceToken = createSurfaceToken(targetAgent.agentId)
            
            // 3. 通知 SystemUI 更新显示
            // TODO: 通过 IAgentSessionListener 通知 UI 更新
            
            Log.i(TAG, "Agent UI rebuilt: ${targetAgent.agentId}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild agent UI", e)
            return false
        }
    }
    
    /**
     * 回滚切换
     */
    private fun rollbackSwitch(
        previousAgent: AgentInstance?,
        failedAgent: AgentInstance
    ) {
        Log.w(TAG, "Rolling back switch from ${failedAgent.agentId} to ${previousAgent?.agentId}")
        
        try {
            if (previousAgent != null) {
                // 恢复前一个 Agent 为前台
                instanceManager.setForegroundAgent(previousAgent.agentId)
                
                // 如果目标 Agent 部分初始化，清理资源
                if (failedAgent.state == AgentState.INITIALIZING) {
                    instanceManager.destroyAgent(failedAgent.agentId)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rollback switch", e)
        }
    }
    
    /**
     * 创建 Surface Token
     */
    private fun createSurfaceToken(agentId: String): String {
        // TODO: 通过 WindowManager 创建真实 Surface Token
        return "surface_${agentId}_${System.currentTimeMillis()}"
    }
    
    /**
     * 更新前台候选列表
     */
    fun updateForegroundCandidates() {
        val allAgents = instanceManager.getAllAgents()
        
        // 排序规则：
        // 1. 优先级高的优先
        // 2. 最近活跃的优先
        // 3. 前台/后台优先于挂起
        foregroundCandidates.clear()
        foregroundCandidates.addAll(
            allAgents
                .filter { it.canSwitchToForeground() || it.isForeground() }
                .sortedWith(compareByDescending<AgentInstance> { it.priority }
                    .thenByDescending { it.lastActiveAt }
                    .thenBy { it.state.ordinal })
                .map { it.agentId }
        )
        
        Log.d(TAG, "Foreground candidates updated: ${foregroundCandidates.size} agents")
    }
    
    /**
     * 获取前台候选列表
     */
    fun getForegroundCandidates(): List<String> = foregroundCandidates.toList()
    
    /**
     * 注册切换监听器
     */
    fun registerSwitchListener(listenerId: String, listener: SwitchListener) {
        switchListeners[listenerId] = listener
    }
    
    /**
     * 注销切换监听器
     */
    fun unregisterSwitchListener(listenerId: String) {
        switchListeners.remove(listenerId)
    }
    
    /**
     * 切换监听器接口
     */
    interface SwitchListener {
        /**
         * 切换开始
         */
        fun onSwitchStarted(agentId: String, mode: Int)
        
        /**
         * 切换完成
         */
        fun onSwitchCompleted(agentId: String, elapsedMs: Long, mode: Int)
        
        /**
         * 切换失败
         */
        fun onSwitchFailed(agentId: String, reason: String)
    }
}

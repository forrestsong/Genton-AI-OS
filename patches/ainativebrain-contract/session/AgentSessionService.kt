// AgentSessionService.kt
// B3.1 会话服务（大脑侧）：IAgentSessionService 实现
package com.ainative.brain.session

import android.os.RemoteException
import android.util.Log
import com.ainative.agent.*
import com.ainative.brain.intent.TurnManager
import com.ainative.brain.multiagent.AgentInstance
import com.ainative.brain.runtime.AgentRuntimeClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 会话服务（大脑侧）
 * 
 * 核心职责：
 * 1. openSession：创建会话，支持 entryMode=resume 恢复工作记忆
 * 2. submitUserTurn：提交用户轮次，幂等性保证
 * 3. cancel：取消轮次
 * 4. endSession：结束会话
 * 5. 状态同步：与 ACB 状态机协同
 * 
 * 前置依赖：
 * - B1.2 IntentReceiver（Turn 入口）
 * - B2.2 ToolLoop（工具循环）
 * - D2.2（SystemUI 侧会话状态消费）
 * 
 * 增强计划：
 * - A3 期：entryMode=resume 恢复工作记忆（依赖 C3.3 上下文预打包）
 */
class AgentSessionService(
    private val turnManager: TurnManager,
    private val runtimeClient: AgentRuntimeClient,
    private val streamCoordinator: StreamCoordinator
) : IAgentSessionService.Stub() {

    companion object {
        private const val TAG = "AgentSessionService"
        
        // 会话模式常量
        const val ENTRY_MODE_NEW = 0      // 新会话
        const val ENTRY_MODE_RESUME = 1   // 恢复会话（A4.3 已支持）
        
        // 错误码
        const val ERROR_INVALID_SESSION = -1
        const val ERROR_INVALID_AGENT = -2
        const val ERROR_DUPLICATE_TURN = -3
        const val ERROR_RUNTIME_UNAVAILABLE = -4
    }
    
    // 线程池（会话操作异步化）
    private val executor = Executors.newCachedThreadPool()
    
    // 活跃会话表（sessionId -> SessionInfo）
    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()
    
    // 监听器表（sessionId -> listener）
    private val sessionListeners = ConcurrentHashMap<String, IAgentSessionListener>()
    
    // A4.3 会话状态管理器（热/冷分级）
    private val stateManager = SessionStateManager(runtimeClient)
    
    /**
     * 打开会话
     * 
     * @param request 会话打开请求
     * @param listener 会话监听器
     * @return 会话句柄，失败返回 null
     */
    private fun openSessionImpl(
        request: SessionOpenRequest,
        listener: IAgentSessionListener
    ): SessionHandle? {
        try {
            // 1. 验证 Agent 是否已注册
            val agentStatus = runtimeClient.getAgentStatus(request.agentId)
            if (agentStatus == null) {
                Log.e(TAG, "Agent not registered: ${request.agentId}")
                return null
            }
            
            // 2. 生成会话 ID
            val sessionId = generateSessionId(request.agentId)
            
            // 3. 创建会话信息
            val sessionInfo = SessionInfo(
                sessionId = sessionId,
                agentId = request.agentId,
                entryMode = request.entryMode,
                openedAtMs = System.currentTimeMillis()
            )
            
            // 4. resume 模式：从 checkpoint 冷恢复（A4.3）
            val acbPointer: String
            if (request.entryMode == ENTRY_MODE_RESUME && request.resumePackId != null) {
                // 冷恢复：从 checkpoint 恢复会话（目标 ≤300ms）
                val resumedSessionId = stateManager.resumeFromCheckpoint(request.resumePackId)
                if (resumedSessionId == null) {
                    Log.e(TAG, "Failed to resume from checkpoint: ${request.resumePackId}")
                    return null
                }
                Log.i(TAG, "Session resumed from checkpoint: sessionId=$resumedSessionId")
                acbPointer = stateManager.getAcbPointer(resumedSessionId) ?: ""
            } else {
                // 新会话：创建 ACB
                acbPointer = runtimeClient.createAcb(request.agentId, sessionId)
                // 注册到会话状态管理器
                stateManager.registerSession(
                    sessionId = sessionId,
                    agentId = request.agentId,
                    acbPointer = acbPointer
                )
            }
            
            // 5. 注册会话
            activeSessions[sessionId] = sessionInfo
            sessionListeners[sessionId] = listener
            
            Log.i(TAG, "Session opened: sessionId=$sessionId, agentId=${request.agentId}, entryMode=${request.entryMode}")
            
            // 6. 构造会话句柄
            return SessionHandle().apply {
                this.sessionId = sessionId
                this.agentId = request.agentId
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open session", e)
            return null
        }
    }
    
    /**
     * 提交用户轮次（幂等）
     * 
     * @param handle 会话句柄
     * @param turn 用户轮次
     * @return 轮次 ID，失败返回 null
     */
    private fun submitUserTurnImpl(
        handle: SessionHandle,
        turn: UserTurn
    ): String? {
        try {
            // 1. 验证会话
            val sessionInfo = activeSessions[handle.sessionId]
            if (sessionInfo == null) {
                Log.e(TAG, "Invalid session: ${handle.sessionId}")
                return null
            }
            
            // 2. 幂等性检查（基于 turn.requestId）
            if (turn.requestId != null && sessionInfo.processedTurns.contains(turn.requestId)) {
                Log.w(TAG, "Duplicate turn request ignored: requestId=${turn.requestId}")
                return sessionInfo.turnIdByRequestId[turn.requestId]
            }
            
            // 3. 生成轮次 ID
            val turnId = generateTurnId(handle.sessionId)
            
            // 4. 记录幂等性状态
            if (turn.requestId != null) {
                sessionInfo.processedTurns.add(turn.requestId)
                sessionInfo.turnIdByRequestId[turn.requestId] = turnId
            }
            
            // 5. 异步处理轮次
            executor.execute {
                processTurn(handle, turnId, turn)
            }
            
            Log.i(TAG, "User turn submitted: sessionId=${handle.sessionId}, turnId=$turnId")
            return turnId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit user turn", e)
            return null
        }
    }
    
    /**
     * 取消轮次
     * 
     * @param handle 会话句柄
     * @param turnId 轮次 ID
     * @return 是否成功
     */
    private fun cancelImpl(handle: SessionHandle, turnId: String): Boolean {
        try {
            // 1. 验证会话
            val sessionInfo = activeSessions[handle.sessionId]
            if (sessionInfo == null) {
                Log.e(TAG, "Invalid session: ${handle.sessionId}")
                return false
            }
            
            // 2. 取消 TurnManager 中的 Turn
            val cancelled = turnManager.cancelTurn(turnId)
            
            if (cancelled) {
                // 3. 清理流式状态
                streamCoordinator.clear(turnId)
                
                // 4. 通知监听器
                val listener = sessionListeners[handle.sessionId]
                listener?.onTurnCancelled(handle, turnId)
                
                Log.i(TAG, "Turn cancelled: sessionId=${handle.sessionId}, turnId=$turnId")
            }
            
            return cancelled
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel turn", e)
            return false
        }
    }
    
    /**
     * 结束会话
     * 
     * @param handle 会话句柄
     */
    private fun endSessionImpl(handle: SessionHandle) {
        try {
            // 1. 移除会话
            val sessionInfo = activeSessions.remove(handle.sessionId)
            if (sessionInfo == null) {
                Log.w(TAG, "Session not found: ${handle.sessionId}")
                return
            }
            
            // 2. 从状态管理器注销（A4.3）
            stateManager.unregisterSession(handle.sessionId)
            
            // 3. 移除监听器
            sessionListeners.remove(handle.sessionId)
            
            // 4. 清理所有相关的流式状态
            sessionInfo.processedTurns.forEach { turnId ->
                streamCoordinator.clear(turnId)
            }
            
            Log.i(TAG, "Session ended: sessionId=${handle.sessionId}, duration=${System.currentTimeMillis() - sessionInfo.openedAtMs}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end session", e)
        }
    }
    
    /**
     * 切换会话（A4.3 热切换 API）
     * 
     * @param targetSessionId 目标会话 ID
     * @return 是否成功切换
     */
    fun switchToSession(targetSessionId: String): Boolean {
        try {
            // 验证目标会话存在
            if (!activeSessions.containsKey(targetSessionId)) {
                Log.e(TAG, "Target session not found: $targetSessionId")
                return false
            }
            
            // 热切换（目标 ≤100ms）
            val success = stateManager.switchToHotSession(targetSessionId)
            
            if (success) {
                Log.i(TAG, "Switched to session: $targetSessionId")
            }
            
            return success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch session", e)
            return false
        }
    }
    
    /**
     * 保存会话 checkpoint（A4.3）
     * 
     * @param sessionId 会话 ID
     * @param packId 上下文打包 ID（来自 EntityQueryService）
     * @param workingMemoryIds 工作记忆 ID 列表
     * @return checkpoint ID，失败返回 null
     */
    fun saveSessionCheckpoint(
        sessionId: String,
        packId: String,
        workingMemoryIds: List<String>
    ): String? {
        return stateManager.saveCheckpoint(sessionId, packId, workingMemoryIds)
    }
    
    /**
     * 获取会话状态信息（A4.3）
     * 
     * @param sessionId 会话 ID
     * @return 状态字符串（ACTING/WAITING/SUSPENDED）或 null
     */
    fun getSessionState(sessionId: String): String? {
        return stateManager.getSessionState(sessionId)
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 处理轮次（异步）
     */
    private fun processTurn(handle: SessionHandle, turnId: String, turn: UserTurn) {
        val listener = sessionListeners[handle.sessionId]
        if (listener == null) {
            Log.e(TAG, "Session listener not found: ${handle.sessionId}")
            return
        }
        
        try {
            // 1. 通知轮次开始
            listener.onTurnStarted(handle, turnId)
            
            // 2. 提交到 TurnManager（规划器入口）
            val sourceData = mapOf(
                "user_input" to (turn.text ?: ""),
                "attachments" to (turn.attachments ?: emptyList<String>()),
                "voice_mode" to turn.voiceMode
            )
            
            val turnInfo = turnManager.submitTurn(
                sessionId = handle.sessionId,
                agentId = handle.agentId,
                turnId = turnId,
                sourceData = sourceData
            )
            
            // 3. 流式推送（由 TurnManager 状态变化触发，通过 TurnPushAdapter）
            // 这里只是注册流式状态，实际推送由工具循环触发
            
            // 4. 等待轮次完成（由 TurnManager 异步完成，这里不阻塞）
            Log.d(TAG, "Turn processing started: turnId=$turnId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process turn: turnId=$turnId", e)
            
            // 通知失败
            try {
                val result = TurnResultSummary().apply {
                    this.turnId = turnId
                    this.status = TurnResultSummary.STATUS_FAILED
                    this.errorMessage = e.message ?: "Unknown error"
                }
                listener.onTurnCompleted(handle, result)
            } catch (e2: RemoteException) {
                Log.e(TAG, "Failed to notify turn failure", e2)
            }
        }
    }
    
    /**
     * 生成会话 ID
     */
    private fun generateSessionId(agentId: String): String {
        return "session_${agentId}_${UUID.randomUUID().toString().take(8)}"
    }
    
    /**
     * 生成轮次 ID
     */
    private fun generateTurnId(sessionId: String): String {
        return "turn_${sessionId}_${System.currentTimeMillis()}"
    }
    
    // ========== 内部数据类 ==========
    
    /**
     * 会话信息
     */
    private data class SessionInfo(
        val sessionId: String,
        val agentId: String,
        val entryMode: Int,
        val openedAtMs: Long,
        val processedTurns: MutableSet<String> = mutableSetOf(),  // 已处理的 requestId
        val turnIdByRequestId: MutableMap<String, String> = mutableMapOf()  // requestId -> turnId
    )

    // ============================================================
    // IAgentSessionService AIDL 契约适配层
    // 原实现（*Impl）返回 Kotlin 友好类型；此处按 AIDL 契约
    // （返回 int 状态码 + out 参数）做薄适配。
    // ============================================================

    override fun openSession(
        request: SessionOpenRequest,
        listener: IAgentSessionListener,
        handle: SessionHandle
    ): Int {
        val h = openSessionImpl(request, listener)
        if (h == null) {
            Log.w(TAG, "openSession failed: agentId=" + request.agentId)
            return STATUS_INTERNAL_ERROR
        }
        // AIDL parcelable 的 out 参数直接传对象（可变），逐字段回填
        handle.sessionId = h.sessionId
        handle.ownerAgentId = h.ownerAgentId
        return STATUS_OK
    }

    override fun submitUserTurn(
        handle: SessionHandle,
        turn: UserTurn,
        turnId: Array<String>
    ): Int {
        if (activeSessions[handle.sessionId] == null) {
            Log.w(TAG, "submitUserTurn: session not found " + handle.sessionId)
            return STATUS_SESSION_NOT_FOUND
        }
        val id = submitUserTurnImpl(handle, turn)
        if (id == null) {
            Log.w(TAG, "submitUserTurn failed: sessionId=" + handle.sessionId)
            return STATUS_INTERNAL_ERROR
        }
        turnId[0] = id
        return STATUS_OK
    }

    override fun cancelTurn(handle: SessionHandle, turnId: String): Int {
        if (activeSessions[handle.sessionId] == null) {
            return STATUS_SESSION_NOT_FOUND
        }
        return if (cancelImpl(handle, turnId)) STATUS_OK else STATUS_INTERNAL_ERROR
    }

    override fun endSession(handle: SessionHandle, reason: Int): Int {
        if (activeSessions[handle.sessionId] == null) {
            return STATUS_SESSION_NOT_FOUND
        }
        endSessionImpl(handle)
        Log.i(TAG, "endSession: sessionId=" + handle.sessionId + " reason=" + reason)
        return STATUS_OK
    }
}

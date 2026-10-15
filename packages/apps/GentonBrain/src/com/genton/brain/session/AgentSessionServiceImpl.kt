package com.genton.brain.session

import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.genton.agent.*
import com.genton.brain.BrainApplication
import com.genton.brain.checkpoint.CheckpointManager
import com.genton.brain.planner.Planner
import com.genton.brain.toolloop.ToolLoop
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 会话服务实现
 * 
 * 职责：
 * 1. 会话生命周期管理（打开/结束/恢复）
 * 2. 用户轮次提交与流式响应协调
 * 3. 幂等性保证（turnId 去重）
 * 4. 状态机保护（防并发冲突）
 * 5. checkpoint 恢复（resume 模式）
 * 
 * B3.1 会话服务（大脑侧）实现
 */
class AgentSessionServiceImpl(
    private val context: Context,
    private val application: BrainApplication
) : IAgentSessionService.Stub() {
    
    companion object {
        private const val TAG = "AgentSessionService"
        
        // 入口模式
        const val ENTRY_MODE_USER_INIT = 0
        const val ENTRY_MODE_VOICE_WAKE = 1
        const val ENTRY_MODE_EVENT_PROACTIVE = 2
        const val ENTRY_MODE_RESUME = 3
        
        // 会话状态
        const val SESSION_STATE_INITIALIZING = 0
        const val SESSION_STATE_ACTIVE = 1
        const val SESSION_STATE_WAITING_INPUT = 2
        const val SESSION_STATE_ENDED = 3
    }
    
    // 活跃会话映射表
    private val activeSessions = ConcurrentHashMap<String, SessionContext>()
    
    // 已提交的 turnId（防重复提交）
    private val submittedTurns = ConcurrentHashMap<String, Long>()
    
    // 执行器（用于异步任务）
    private val executor = Executors.newCachedThreadPool()
    
    // 组件引用
    private val planner: Planner by lazy { application.getPlanner() }
    private val toolLoop: ToolLoop by lazy { application.getToolLoop() }
    private val checkpointManager: CheckpointManager by lazy { application.getCheckpointManager() }
    
    /**
     * 打开（或复用）一个会话
     */
    override fun openSession(
        request: SessionOpenRequest,
        listener: IAgentSessionListener,
        handle: SessionHandle
    ): Int {
        Log.i(TAG, "Opening session: sessionId=${request.sessionId}, agentId=${request.agentId}, entryMode=${request.entryMode}")
        
        try {
            // 1. 验证请求参数
            if (request.sessionId.isNullOrEmpty()) {
                Log.e(TAG, "Invalid sessionId")
                return IAgentSessionService.STATUS_DENIED
            }
            
            // 2. 检查是否已有活跃会话
            val existingSession = activeSessions[request.sessionId]
            if (existingSession != null) {
                Log.i(TAG, "Reusing existing session: ${request.sessionId}")
                
                // 更新监听器
                existingSession.listener = listener
                
                // 返回现有句柄
                handle.sessionId = existingSession.sessionId
                handle.ownerAgentId = existingSession.agentId
                
                return IAgentSessionService.STATUS_OK
            }
            
            // 3. 创建新会话上下文
            val agentId = request.agentId.ifEmpty { "system_default" }
            val sessionContext = SessionContext(
                sessionId = request.sessionId,
                agentId = agentId,
                listener = listener,
                entryMode = request.entryMode,
                state = SESSION_STATE_INITIALIZING
            )
            
            // 4. 处理恢复模式
            if (request.entryMode == ENTRY_MODE_RESUME && !request.resumePackId.isNullOrEmpty()) {
                val resumeSuccess = resumeSessionFromCheckpoint(sessionContext, request.resumePackId)
                if (!resumeSuccess) {
                    Log.w(TAG, "Failed to resume from checkpoint, starting fresh session")
                }
            }
            
            // 5. 注册会话
            activeSessions[request.sessionId] = sessionContext
            sessionContext.state = SESSION_STATE_WAITING_INPUT
            
            // 6. 填充句柄
            handle.sessionId = request.sessionId
            handle.ownerAgentId = agentId
            
            // 7. 通知会话状态
            notifySessionState(handle, IAgentSessionListener.SESSION_IDLE)
            
            Log.i(TAG, "Session opened: ${request.sessionId}")
            return IAgentSessionService.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open session", e)
            return IAgentSessionService.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 提交用户轮次
     */
    override fun submitUserTurn(
        handle: SessionHandle,
        turn: UserTurn,
        turnId: Array<String>
    ): Int {
        Log.i(TAG, "Submitting user turn: sessionId=${handle.sessionId}, turnId=${turn.turnId}")
        
        try {
            // 1. 查找会话
            val session = activeSessions[handle.sessionId]
            if (session == null) {
                Log.e(TAG, "Session not found: ${handle.sessionId}")
                return IAgentSessionService.STATUS_SESSION_NOT_FOUND
            }
            
            // 2. 检查会话状态
            if (session.state == SESSION_STATE_ENDED) {
                Log.e(TAG, "Session already ended: ${handle.sessionId}")
                return IAgentSessionService.STATUS_SESSION_NOT_FOUND
            }
            
            // 3. 幂等性检查（防重复提交）
            val existingTurnTime = submittedTurns[turn.turnId]
            if (existingTurnTime != null) {
                Log.w(TAG, "Turn already submitted: ${turn.turnId}")
                turnId[0] = turn.turnId
                return IAgentSessionService.STATUS_OK
            }
            
            // 4. 检查是否有轮次正在处理
            if (session.isProcessingTurn.get()) {
                Log.w(TAG, "Session busy, another turn is processing")
                return IAgentSessionService.STATUS_BUSY
            }
            
            // 5. 标记轮次已提交
            submittedTurns[turn.turnId] = System.currentTimeMillis()
            turnId[0] = turn.turnId
            
            // 6. 异步处理轮次
            session.isProcessingTurn.set(true)
            session.state = SESSION_STATE_ACTIVE
            
            executor.submit {
                try {
                    processTurn(session, handle, turn)
                } finally {
                    session.isProcessingTurn.set(false)
                    session.state = SESSION_STATE_WAITING_INPUT
                }
            }
            
            Log.i(TAG, "User turn submitted: ${turn.turnId}")
            return IAgentSessionService.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit user turn", e)
            return IAgentSessionService.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 取消当前轮次
     */
    override fun cancelTurn(handle: SessionHandle, turnId: String): Int {
        Log.i(TAG, "Cancelling turn: sessionId=${handle.sessionId}, turnId=$turnId")
        
        try {
            // 1. 查找会话
            val session = activeSessions[handle.sessionId]
            if (session == null) {
                Log.e(TAG, "Session not found: ${handle.sessionId}")
                return IAgentSessionService.STATUS_SESSION_NOT_FOUND
            }
            
            // 2. 检查是否有正在处理的轮次
            if (!session.isProcessingTurn.get()) {
                Log.w(TAG, "No turn is being processed")
                return IAgentSessionService.STATUS_OK
            }
            
            // 3. 标记取消请求
            session.cancelRequested.set(true)
            
            // 4. 通知 ToolLoop 取消
            // TODO: 调用 ToolLoop.cancel(turnId)
            
            Log.i(TAG, "Turn cancellation requested: $turnId")
            return IAgentSessionService.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel turn", e)
            return IAgentSessionService.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 结束会话
     */
    override fun endSession(handle: SessionHandle, reason: Int): Int {
        Log.i(TAG, "Ending session: sessionId=${handle.sessionId}, reason=$reason")
        
        try {
            // 1. 查找会话
            val session = activeSessions.remove(handle.sessionId)
            if (session == null) {
                Log.w(TAG, "Session not found: ${handle.sessionId}")
                return IAgentSessionService.STATUS_SESSION_NOT_FOUND
            }
            
            // 2. 更新状态
            session.state = SESSION_STATE_ENDED
            
            // 3. 取消正在处理的轮次
            if (session.isProcessingTurn.get()) {
                session.cancelRequested.set(true)
            }
            
            // 4. 创建 checkpoint（用于后续恢复）
            if (reason != 2) { // 2 = agent_done，不需要保存
                createSessionCheckpoint(session)
            }
            
            // 5. 清理卡片状态
            cardStateManager.clearSession(session.agentId)
            
            // 6. 清理已提交轮次记录（保留最近100个）
            cleanupSubmittedTurns()
            
            Log.i(TAG, "Session ended: ${handle.sessionId}")
            return IAgentSessionService.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end session", e)
            return IAgentSessionService.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 处理用户轮次
     */
    private fun processTurn(
        session: SessionContext,
        handle: SessionHandle,
        turn: UserTurn
    ) {
        Log.i(TAG, "Processing turn: ${turn.turnId}")
        
        try {
            // 1. 通知会话状态为活跃
            notifySessionState(handle, IAgentSessionListener.SESSION_ACTIVE)
            
            // 2. 推送任务卡片（PLANNING 状态）
            cardStateManager.pushTaskCard(
                session.listener,
                handle,
                session.agentId,
                CardStateManager.TASK_STATE_PLANNING,
                "处理用户请求",
                -1,
                false
            )
            
            // 3. 检查是否为表单提交（L0.5 免 LLM 直连）
            if (turn.kind == 4 && turn.formSubmit != null) {
                processFormSubmit(session, handle, turn)
                return
            }
            
            // 4. 构建输入内容
            val input = buildTurnInput(turn)
            
            // 5. 推送流式增量（thinking）
            val thinkingDelta = TurnDelta().apply {
                type = StreamCoordinator.TYPE_THINKING
                text = "正在思考..."
                toolKind = 0
            }
            streamCoordinator.pushDelta(session.listener, handle, turn.turnId, thinkingDelta)
            
            // 6. 调用规划器生成 Goal DAG
            val planningResult = planner.plan(
                agentId = session.agentId,
                userInput = input,
                context = session.contextPack
            )
            
            // 7. 更新任务卡片（EXECUTING 状态）
            cardStateManager.updateTaskState(
                session.listener,
                handle,
                session.agentId,
                CardStateManager.TASK_STATE_EXECUTING
            )
            
            // 8. 检查是否取消
            if (session.cancelRequested.get()) {
                Log.i(TAG, "Turn cancelled: ${turn.turnId}")
                session.cancelRequested.set(false)
                return
            }
            
            // 5. 执行 Goal DAG（通过 ToolLoop）
            val executionResult = toolLoop.execute(planningResult, session.agentId)
            
            // 6. 检查是否取消
            if (session.cancelRequested.get()) {
                Log.i(TAG, "Turn cancelled after execution: ${turn.turnId}")
                session.cancelRequested.set(false)
                return
            }
            
            // 7. 刷新流式缓冲区
            streamCoordinator.flush(session.listener, handle, turn.turnId)
            
            // 8. 更新任务卡片（TERMINATED 状态）
            cardStateManager.updateTaskState(
                session.listener,
                handle,
                session.agentId,
                CardStateManager.TASK_STATE_TERMINATED
            )
            
            // 9. 构建结果摘要
            val resultSummary = TurnResultSummary().apply {
                this.turnId = turn.turnId
                this.finalText = executionResult.finalText ?: ""
                this.actionCardIds = executionResult.actionCardIds?.toTypedArray() ?: emptyArray()
                this.tokensConsumed = executionResult.tokensConsumed
                this.auditRef = executionResult.auditRef ?: ""
            }
            
            // 10. 通知轮次完成
            session.listener.onTurnCompleted(handle, turn.turnId, resultSummary)
            
            // 11. 通知会话状态为空闲
            notifySessionState(handle, IAgentSessionListener.SESSION_IDLE)
            
            Log.i(TAG, "Turn completed: ${turn.turnId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process turn: ${turn.turnId}", e)
            
            // 清理流式状态
            streamCoordinator.clear(turn.turnId)
            
            // 通知错误
            try {
                session.listener.onSessionError(
                    handle,
                    IAgentSessionService.STATUS_INTERNAL_ERROR,
                    e.message ?: "Unknown error"
                )
            } catch (re: RemoteException) {
                Log.e(TAG, "Failed to notify error", re)
            }
        } finally {
            // 重置处理标志
            session.isProcessingTurn.set(false)
        }
    }
    
    /**
     * 构建轮次输入
     */
    private fun buildTurnInput(turn: UserTurn): String {
        return when (turn.kind) {
            0 -> turn.content // text
            1 -> turn.content // voice (已转写)
            3 -> turn.content // command
            4 -> "表单提交: ${turn.formSubmit?.formId ?: "unknown"}" // form_submit
            else -> turn.content
        }
    }
    
    /**
     * 从 checkpoint 恢复会话
     */
    private fun resumeSessionFromCheckpoint(
        session: SessionContext,
        resumePackId: String
    ): Boolean {
        Log.i(TAG, "Resuming session from checkpoint: $resumePackId")
        
        try {
            // 1. 恢复 checkpoint
            val checkpoint = checkpointManager.restoreCheckpoint(resumePackId)
            if (checkpoint == null) {
                Log.e(TAG, "Checkpoint not found: $resumePackId")
                return false
            }
            
            // 2. 恢复上下文包
            // TODO: 解析 checkpoint 数据，恢复 ContextPack
            session.contextPack = null
            
            Log.i(TAG, "Session resumed from checkpoint: $resumePackId")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume session from checkpoint", e)
            return false
        }
    }
    
    /**
     * 创建会话 checkpoint
     */
    private fun createSessionCheckpoint(session: SessionContext) {
        try {
            // 序列化会话状态
            val state = buildString {
                append("sessionId=${session.sessionId}\n")
                append("agentId=${session.agentId}\n")
                append("entryMode=${session.entryMode}\n")
                append("createdAt=${session.createdAt}\n")
            }
            
            checkpointManager.createCheckpoint(session.sessionId, state)
            
            Log.i(TAG, "Session checkpoint created: ${session.sessionId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session checkpoint", e)
        }
    }
    
    /**
     * 通知会话状态
     */
    private fun notifySessionState(handle: SessionHandle, state: Int) {
        try {
            activeSessions[handle.sessionId]?.listener?.onSessionStateChanged(handle, state)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to notify session state", e)
        }
    }
    
    /**
     * 处理表单提交（L0.5 免 LLM 直连）
     */
    private fun processFormSubmit(
        session: SessionContext,
        handle: SessionHandle,
        turn: UserTurn
    ) {
        Log.i(TAG, "Processing form submit: ${turn.formSubmit?.formId}")
        
        val formPayload = turn.formSubmit ?: return
        
        // 推送动作卡片（进度中）
        val cardId = "form_${formPayload.formId}"
        cardStateManager.pushActionProgress(
            session.listener,
            handle,
            cardId,
            turn.turnId,
            "正在提交表单",
            formPayload.toolName ?: "未知操作"
        )
        
        // 提交表单
        formSubmitRouter.submitForm(
            session.sessionId,
            turn.turnId,
            formPayload,
            object : FormSubmitRouter.FormSubmitCallback {
                override fun onValidationFailed(errors: List<String>) {
                    Log.w(TAG, "Form validation failed: $errors")
                    
                    // 更新表单卡片状态（显示错误）
                    val updatedForm = FormPayload().apply {
                        this.formId = formPayload.formId
                        this.assetId = formPayload.assetId
                        this.toolName = formPayload.toolName
                        this.executionMode = formPayload.executionMode
                        this.status = 4  // canceled
                        this.error = errors.joinToString("; ")
                        this.canSubmit = false
                        this.fields = formPayload.fields
                    }
                    
                    cardStateManager.pushFormCard(
                        session.listener,
                        handle,
                        cardId,
                        turn.turnId,
                        "表单验证失败",
                        errors.joinToString("; "),
                        updatedForm
                    )
                    
                    // 重置处理标志
                    session.isProcessingTurn.set(false)
                    
                    // 通知会话状态为空闲
                    notifySessionState(handle, IAgentSessionListener.SESSION_IDLE)
                }
                
                override fun onSubmitSuccess(result: com.genton.brain.toolloop.ExecutionResult) {
                    Log.i(TAG, "Form submit success: ${formPayload.formId}")
                    
                    // 推送动作卡片（已完成）
                    cardStateManager.pushActionDone(
                        session.listener,
                        handle,
                        cardId,
                        turn.turnId,
                        "表单提交成功",
                        formPayload.toolName ?: "操作完成",
                        result.auditRef,
                        false
                    )
                    
                    // 构建结果摘要
                    val resultSummary = TurnResultSummary().apply {
                        this.turnId = turn.turnId
                        this.finalText = "表单提交成功"
                        this.actionCardIds = arrayOf(cardId)
                        this.tokensConsumed = 0  // 免 LLM
                        this.auditRef = result.auditRef ?: ""
                    }
                    
                    // 通知轮次完成
                    session.listener.onTurnCompleted(handle, turn.turnId, resultSummary)
                    
                    // 重置处理标志
                    session.isProcessingTurn.set(false)
                    
                    // 通知会话状态为空闲
                    notifySessionState(handle, IAgentSessionListener.SESSION_IDLE)
                }
                
                override fun onSubmitFailed(reason: String) {
                    Log.e(TAG, "Form submit failed: $reason")
                    
                    // 推送动作卡片（需要关注）
                    cardStateManager.pushActionNeedsAttention(
                        session.listener,
                        handle,
                        cardId,
                        turn.turnId,
                        "表单提交失败",
                        reason,
                        null
                    )
                    
                    // 通知错误
                    try {
                        session.listener.onSessionError(
                            handle,
                            IAgentSessionService.STATUS_INTERNAL_ERROR,
                            reason
                        )
                    } catch (e: RemoteException) {
                        Log.e(TAG, "Failed to notify error", e)
                    }
                    
                    // 重置处理标志
                    session.isProcessingTurn.set(false)
                    
                    // 通知会话状态为空闲
                    notifySessionState(handle, IAgentSessionListener.SESSION_IDLE)
                }
            }
        )
    }
    
    /**
     * 清理已提交轮次记录
     */
    private fun cleanupSubmittedTurns() {
        if (submittedTurns.size > 100) {
            // 移除最旧的条目
            val sortedEntries = submittedTurns.entries.sortedBy { it.value }
            val toRemove = sortedEntries.take(submittedTurns.size - 100)
            
            for (entry in toRemove) {
                submittedTurns.remove(entry.key)
            }
            
            Log.d(TAG, "Cleaned up ${toRemove.size} old turn records")
        }
    }
    
    /**
     * 关闭服务
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down AgentSessionService...")
        
        // 结束所有活跃会话
        val activeSessions = activeSessions.keys.toList()
        for (sessionId in activeSessions) {
            val handle = SessionHandle().apply {
                this.sessionId = sessionId
                this.ownerAgentId = ""
            }
            endSession(handle, 1) // reason=1 (app_bg)
        }
        
        // 关闭执行器
        executor.shutdown()
        
        Log.i(TAG, "AgentSessionService shut down")
    }
}

/**
 * 会话上下文
 */
data class SessionContext(
    val sessionId: String,
    val agentId: String,
    var listener: IAgentSessionListener,
    val entryMode: Int,
    var state: Int,
    val createdAt: Long = System.currentTimeMillis(),
    var contextPack: Any? = null, // TODO: 定义 ContextPack 类型
    val isProcessingTurn: AtomicBoolean = AtomicBoolean(false),
    val cancelRequested: AtomicBoolean = AtomicBoolean(false)
)

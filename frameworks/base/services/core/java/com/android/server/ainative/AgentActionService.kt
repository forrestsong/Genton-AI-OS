package com.android.server.ainative

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ainative.action.ActionModels.*
import com.ainative.action.registry.ActionRegistry
import com.android.server.am.ActivityManagerService
import com.android.server.wm.WindowManagerService
import com.android.server.ainative.action.executor.*
import com.android.server.ainative.acb.ACBPolicyEngine
import com.android.server.ainative.audit.AuditLogger
import com.android.server.ainative.perception.AgentUiTreeService
import com.android.server.ainative.perception.SecureWindowDetector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A3.2 执行中介 - Agent动作服务（已集成A3.3/A3.5/A3.6执行器）
 * 
 * 职责：
 * 1. 提供动作执行的唯一入口（submit/cancel/getStatus）
 * 2. 编排完整执行流程（校验 → 策略 → 确认 → 执行 → 审计）
 * 3. 管理动作生命周期（8态状态机）
 * 4. 协调执行器和审计服务
 * 5. 支持能力令牌校验
 * 
 * 核心原则：
 * - 所有副作用动作只能通过本服务，禁止直触 AMS/InputDispatcher
 * - 执行前一致性校验（UI快照节点、overlay防护）
 * - 审计全覆盖（每个动作记录审计事件）
 * 
 * 已集成执行器：
 * - A3.3 确定性执行器：ActivityExecutor/InputExecutor/DeviceControlExecutor
 * - A3.5 系统侧执行器：NotificationExecutor/ClipboardExecutor/ContentExecutor
 * - A3.6 内部动作执行器：InternalActionExecutor（sys.*/policy.*/cmd.*内部动作）
 * - 执行器注册表统一管理，动作前缀路由到对应执行器
 */
class AgentActionService(
    private val context: Context,
    private val ams: ActivityManagerService,
    private val wms: WindowManagerService,
    private val policyEngine: ACBPolicyEngine,
    private val auditLogger: AuditLogger,
    private val uiTreeService: AgentUiTreeService
) {
    
    companion object {
        private const val TAG = "AgentActionService"
    }
    
    // 动作注册表
    private val registry = ActionRegistry.getInstance(context)
    
    // 状态机
    private val stateMachine = ActionStateMachine()
    
    // 参数来源校验器
    private val sourceValidator = ActionSourceValidator(registry)
    
    // 执行器注册表
    private val executorRegistry = ActionExecutorRegistry()
    
    // 工作线程
    private val workerThread = HandlerThread("AgentActionService").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    
    // 请求ID生成器
    private val requestIdGenerator = AtomicLong(0)
    
    // 活跃请求（actionRequestId -> ActionExecutionInstance）
    private val activeRequests = ConcurrentHashMap<String, ActionExecutionInstance>()
    
    init {
        // 初始化执行器
        initializeExecutors()
        
        // 加载动作注册表
        registry.load()
        
        // 初始化taint规则（A3.4）
        TaintRuleInitializer.initialize()
    }
    
    /**
     * 初始化所有执行器
     */
    private fun initializeExecutors() {
        val secureDetector = SecureWindowDetector(wms)
        
        // A3.3 确定性执行器
        // 注册ActivityExecutor
        val activityExecutor = ActivityExecutor(context, ams, auditLogger)
        executorRegistry.registerExecutor("activity", activityExecutor)
        
        // 注册InputExecutor
        val inputExecutor = InputExecutor(context, wms, uiTreeService, secureDetector, auditLogger)
        executorRegistry.registerExecutor("input", inputExecutor)
        
        // 注册DeviceControlExecutor
        val deviceControlExecutor = DeviceControlExecutor(context, auditLogger)
        executorRegistry.registerExecutor("device", deviceControlExecutor)
        
        // A3.5 系统侧执行器
        // 注册NotificationExecutor（需要NMS和通知服务）
        val nms = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notificationService = AgentNotificationService.getInstance(context)
        val notificationExecutor = NotificationExecutor(context, nms, notificationService, auditLogger)
        executorRegistry.registerExecutor("notification", notificationExecutor)
        
        // 注册ClipboardExecutor
        val clipboardExecutor = ClipboardExecutor(context, auditLogger)
        executorRegistry.registerExecutor("clipboard", clipboardExecutor)
        
        // 注册ContentExecutor
        val contentExecutor = ContentExecutor(context, auditLogger)
        executorRegistry.registerExecutor("content", contentExecutor)
        
        // A3.6 内部动作执行器（系统内部专用）
        // 注册InternalActionExecutor（处理sys.*、policy.*、cmd.*等内部动作）
        val internalExecutor = InternalActionExecutor(context, ams, policyEngine, auditLogger)
        executorRegistry.registerExecutor("sys", internalExecutor)
        executorRegistry.registerExecutor("policy", internalExecutor)
        executorRegistry.registerExecutor("cmd", internalExecutor)
        executorRegistry.registerExecutor("internal", internalExecutor)
        
        // 未来可扩展：
        // executorRegistry.registerExecutor("telephony", TelephonyExecutor(...))
        // executorRegistry.registerExecutor("media", MediaExecutor(...))
    }
    
    /**
     * 提交动作执行请求
     * 
     * @param request 动作请求
     * @param callback 回调接口（可选）
     * @return 提交结果码和请求ID
     */
    fun submitAction(
        request: ActionRequest,
        callback: IAgentActionCallback? = null
    ): SubmitActionResult {
        // 1. 校验动作是否存在
        val entry = registry.getAction(request.actionName)
        if (entry == null) {
            auditLogger.logActionExecution(
                success = false,
                actionName = request.actionName,
                denyReason = "Unknown action"
            )
            return SubmitActionResult(SubmitResult.SUBMIT_UNKNOWN_ACTION, null)
        }
        
        // 2. 校验能力令牌
        val capabilityCheck = policyEngine.checkCapability(
            capability = entry.requiredCapability,
            reason = "Execute action: ${request.actionName}"
        )
        if (!capabilityCheck.allowed) {
            auditLogger.logActionExecution(
                success = false,
                actionName = request.actionName,
                denyReason = "Capability denied: ${capabilityCheck.denyReason}"
            )
            return SubmitActionResult(SubmitResult.SUBMIT_POLICY_DENIED, null)
        }
        
        // 3. 校验参数来源（taint规则）
        val sourceValidation = sourceValidator.validate(request)
        if (sourceValidation.code != ValidationResult.VALID) {
            auditLogger.logActionExecution(
                success = false,
                actionName = request.actionName,
                denyReason = "Source validation failed"
            )
            return SubmitActionResult(SubmitResult.SUBMIT_TAINT_DENIED, null)
        }
        
        // 4. 创建执行实例
        val actionRequestId = "action_${requestIdGenerator.incrementAndGet()}_${SystemClock.elapsedRealtime()}"
        
        val instance = ActionExecutionInstance(
            actionRequestId = actionRequestId,
            request = request,
            entry = entry,
            callback = callback,
            stateMachine = stateMachine
        )
        
        activeRequests[actionRequestId] = instance
        
        // 5. 异步执行
        workerHandler.post {
            executeAction(instance)
        }
        
        // 6. 检查是否需要用户确认
        if (entry.requiresConfirmation) {
            return SubmitActionResult(SubmitResult.SUBMIT_CONFIRMATION_REQUIRED, actionRequestId)
        }
        
        return SubmitActionResult(SubmitResult.SUBMIT_ACCEPTED, actionRequestId)
    }
    
    /**
     * 取消动作执行
     */
    fun cancelAction(actionRequestId: String): Boolean {
        val instance = activeRequests[actionRequestId] ?: return false
        
        // 调用执行器的取消方法
        val executor = executorRegistry.getExecutor(instance.request.actionName)
        val cancelled = executor?.cancel(actionRequestId) ?: false
        
        if (cancelled) {
            instance.stateMachine.transitionTo(ActionState.ABORTED, "User cancelled")
            instance.callback?.onStateChanged(actionRequestId, ActionState.ABORTED)
            
            auditLogger.logActionExecution(
                success = false,
                actionName = instance.request.actionName,
                denyReason = "User cancelled"
            )
            
            activeRequests.remove(actionRequestId)
        }
        
        return cancelled
    }
    
    /**
     * 查询动作状态
     */
    fun getActionStatus(actionRequestId: String): ActionExecutionStatus? {
        val instance = activeRequests[actionRequestId] ?: return null
        
        return ActionExecutionStatus(
            actionRequestId = actionRequestId,
            actionName = instance.request.actionName,
            state = instance.stateMachine.currentState,
            submitTime = instance.submitTime,
            startTime = instance.startTime,
            endTime = instance.endTime,
            resultData = instance.resultData,
            errorMessage = instance.errorMessage
        )
    }
    
    /**
     * 列出所有活跃请求
     */
    fun listActiveActions(): List<ActionExecutionStatus> {
        return activeRequests.values.map { instance ->
            ActionExecutionStatus(
                actionRequestId = instance.actionRequestId,
                actionName = instance.request.actionName,
                state = instance.stateMachine.currentState,
                submitTime = instance.submitTime,
                startTime = instance.startTime,
                endTime = instance.endTime,
                resultData = instance.resultData,
                errorMessage = instance.errorMessage
            )
        }
    }
    
    /**
     * 执行动作（私有方法）
     */
    private fun executeAction(instance: ActionExecutionInstance) {
        try {
            // QUEUED → POLICY_CHECK
            instance.stateMachine.transitionTo(ActionState.POLICY_CHECK, "Checking policy")
            instance.callback?.onStateChanged(instance.actionRequestId, ActionState.POLICY_CHECK)
            
            // POLICY_CHECK → CONFIRMATION (如果需要) 或 EXECUTING
            if (instance.entry.requiresConfirmation) {
                instance.stateMachine.transitionTo(ActionState.CONFIRMATION, "Waiting for confirmation")
                instance.callback?.onStateChanged(instance.actionRequestId, ActionState.CONFIRMATION)
                // 确认流程由外部触发（通过confirmAction方法）
                return
            }
            
            // CONFIRMATION → EXECUTING
            instance.stateMachine.transitionTo(ActionState.EXECUTING, "Executing")
            instance.startTime = SystemClock.elapsedRealtime()
            instance.callback?.onStateChanged(instance.actionRequestId, ActionState.EXECUTING)
            
            // 查找执行器
            val executor = executorRegistry.getExecutor(instance.request.actionName)
            if (executor == null) {
                instance.stateMachine.transitionTo(ActionState.FAILED, "No executor found")
                instance.endTime = SystemClock.elapsedRealtime()
                instance.errorMessage = "No executor found for action: ${instance.request.actionName}"
                instance.callback?.onStateChanged(instance.actionRequestId, ActionState.FAILED)
                
                auditLogger.logActionExecution(
                    success = false,
                    actionName = instance.request.actionName,
                    denyReason = "No executor found"
                )
                
                activeRequests.remove(instance.actionRequestId)
                return
            }
            
            // 执行
            val result = executor.execute(instance.request)
            
            // 处理结果
            instance.endTime = SystemClock.elapsedRealtime()
            
            if (result.success) {
                instance.stateMachine.transitionTo(ActionState.SUCCEEDED, "Execution succeeded")
                instance.resultData = result.resultData
                instance.callback?.onCompleted(instance.actionRequestId, result.resultData)
                
                auditLogger.logActionExecution(
                    success = true,
                    actionName = instance.request.actionName,
                    elapsedMs = instance.endTime - instance.startTime
                )
            } else {
                instance.stateMachine.transitionTo(ActionState.FAILED, result.errorMessage ?: "Unknown error")
                instance.errorMessage = result.errorMessage
                instance.callback?.onFailed(instance.actionRequestId, result.errorCode ?: "UNKNOWN", result.errorMessage)
                
                auditLogger.logActionExecution(
                    success = false,
                    actionName = instance.request.actionName,
                    denyReason = result.errorMessage ?: "Unknown error"
                )
            }
            
            // 清理
            activeRequests.remove(instance.actionRequestId)
            
        } catch (e: Exception) {
            instance.stateMachine.transitionTo(ActionState.FAILED, "Exception: ${e.message}")
            instance.endTime = SystemClock.elapsedRealtime()
            instance.errorMessage = e.message
            instance.callback?.onFailed(instance.actionRequestId, "EXCEPTION", e.message)
            
            auditLogger.logActionExecution(
                success = false,
                actionName = instance.request.actionName,
                denyReason = "Exception: ${e.message}"
            )
            
            activeRequests.remove(instance.actionRequestId)
        }
    }
    
    /**
     * 确认动作执行（用户确认后调用）
     */
    fun confirmAction(actionRequestId: String) {
        val instance = activeRequests[actionRequestId] ?: return
        
        if (instance.stateMachine.currentState != ActionState.CONFIRMATION) {
            return
        }
        
        // 继续执行
        workerHandler.post {
            instance.stateMachine.transitionTo(ActionState.EXECUTING, "User confirmed")
            instance.startTime = SystemClock.elapsedRealtime()
            instance.callback?.onStateChanged(actionRequestId, ActionState.EXECUTING)
            
            // 执行逻辑与executeAction相同
            val executor = executorRegistry.getExecutor(instance.request.actionName)
            if (executor != null) {
                val result = executor.execute(instance.request)
                instance.endTime = SystemClock.elapsedRealtime()
                
                if (result.success) {
                    instance.stateMachine.transitionTo(ActionState.SUCCEEDED, "Execution succeeded")
                    instance.resultData = result.resultData
                    instance.callback?.onCompleted(actionRequestId, result.resultData)
                } else {
                    instance.stateMachine.transitionTo(ActionState.FAILED, result.errorMessage ?: "Unknown error")
                    instance.errorMessage = result.errorMessage
                    instance.callback?.onFailed(actionRequestId, result.errorCode ?: "UNKNOWN", result.errorMessage)
                }
            }
            
            activeRequests.remove(actionRequestId)
        }
    }
    
    /**
     * 获取执行器能力描述
     */
    fun describeExecutors(): Map<String, String> {
        return executorRegistry.listExecutors()
    }
    
    /**
     * 关闭服务
     */
    fun shutdown() {
        // 取消所有活跃请求
        activeRequests.keys.toList().forEach { actionRequestId ->
            cancelAction(actionRequestId)
        }
        
        workerThread.quitSafely()
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 动作执行实例
     */
    private data class ActionExecutionInstance(
        val actionRequestId: String,
        val request: ActionRequest,
        val entry: ActionRegistryEntry,
        val callback: IAgentActionCallback?,
        val stateMachine: ActionStateMachine,
        val submitTime: Long = SystemClock.elapsedRealtime(),
        var startTime: Long = 0,
        var endTime: Long = 0,
        var resultData: Map<String, Any>? = null,
        var errorMessage: String? = null
    )
}


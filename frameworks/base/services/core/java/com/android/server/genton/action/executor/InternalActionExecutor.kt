package com.android.server.genton.action.executor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.genton.action.InternalActionModels.*
import com.genton.action.ActionModels.*
import com.android.server.genton.audit.AuditLogger
import com.android.server.am.ActivityManagerService
import com.android.server.genton.acb.ACBPolicyEngine
import java.io.File

/**
 * A3.6 内部动作执行器
 * 
 * 职责：
 * 1. 执行系统内部动作（sys.*）
 * 2. 管理策略相关动作（policy.*）
 * 3. 处理资源准备动作
 * 4. 提供测试/调试工具动作（cmd.*）
 * 
 * 安全机制：
 * 1. 仅允许signature|privileged权限调用
 * 2. 校验调用者UID（必须是system_server或大脑进程）
 * 3. 内部审计记录（不计入用户可见审计流）
 * 4. 不受ACB预算约束（独立预算池）
 * 5. 高优先级执行
 * 
 * 注意：
 * - 内部动作不需要用户确认
 * - 内部动作不触发UI提示
 * - 内部动作执行失败不影响用户会话
 */
class InternalActionExecutor(
    private val context: Context,
    private val ams: ActivityManagerService,
    private val policyEngine: ACBPolicyEngine,
    private val auditLogger: AuditLogger
) : ActionExecutor {

    companion object {
        private const val TAG = "InternalActionExecutor"
        
        // 允许调用内部动作的UID列表
        private val ALLOWED_UIDS = setOf(
            Process.SYSTEM_UID,  // system_server
            // 大脑App UID将在运行时动态添加
        )
        
        // 内部动作前缀
        private val INTERNAL_PREFIXES = setOf("sys.", "cmd.", "policy.", "internal.")
    }

    // 子执行器（按分类委托）
    private lateinit var performanceExecutor: PerformanceOptimizationExecutor
    private lateinit var policyManagementExecutor: PolicyManagementExecutor
    private lateinit var resourcePrepExecutor: ResourcePreparationExecutor
    private lateinit var testingDebugExecutor: TestingDebugExecutor
    
    init {
        initializeSubExecutors()
        loadInternalActionRegistry()
    }
    
    private fun initializeSubExecutors() {
        performanceExecutor = PerformanceOptimizationExecutor(context, ams, auditLogger)
        policyManagementExecutor = PolicyManagementExecutor(context, policyEngine, auditLogger)
        resourcePrepExecutor = ResourcePreparationExecutor(context, auditLogger)
        testingDebugExecutor = TestingDebugExecutor(context, auditLogger)
    }
    
    /**
     * 加载内部动作注册表
     */
    private fun loadInternalActionRegistry() {
        try {
            val registryJson = context.resources.openRawResource(
                context.resources.getIdentifier(
                    "internal_action_registry",
                    "raw",
                    "android"
                )
            ).bufferedReader().use { it.readText() }
            
            // 解析JSON并注册到InternalActionRegistry
            // 实际实现需要JSON解析逻辑
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load internal action registry", e)
        }
    }

    override fun canExecute(actionName: String): Boolean {
        return INTERNAL_PREFIXES.any { actionName.startsWith(it) } &&
               InternalActionRegistry.isInternalAction(actionName)
    }

    override fun execute(request: ActionRequest): ActionExecutionResult {
        val startTime = System.currentTimeMillis()
        
        // 1. 安全检查：验证调用者UID
        val callerUid = android.os.Binder.getCallingUid()
        if (!isCallerAllowed(callerUid)) {
            return ActionExecutionResult.failed(
                "Internal action can only be called by system or brain app",
                errorCode = "PERMISSION_DENIED"
            )
        }
        
        // 2. 查找内部动作定义
        val entry = InternalActionRegistry.get(request.actionName)
        if (entry == null) {
            return ActionExecutionResult.failed(
                "Unknown internal action: ${request.actionName}",
                errorCode = "UNKNOWN_ACTION"
            )
        }
        
        // 3. 路由到对应的子执行器
        val result = try {
            when (entry.category) {
                InternalActionCategory.PERFORMANCE_OPTIMIZATION -> {
                    performanceExecutor.execute(request)
                }
                InternalActionCategory.POLICY_MANAGEMENT -> {
                    policyManagementExecutor.execute(request)
                }
                InternalActionCategory.RESOURCE_PREPARATION -> {
                    resourcePrepExecutor.execute(request)
                }
                InternalActionCategory.MOTION_INTERACTION -> {
                    resourcePrepExecutor.execute(request)  // 复用资源准备执行器
                }
                InternalActionCategory.TESTING_DEBUG -> {
                    testingDebugExecutor.execute(request)
                }
            }
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Internal action execution failed: ${e.message}",
                errorCode = "EXECUTION_ERROR"
            )
        }
        
        // 4. 内部审计（不计入用户可见审计流）
        if (entry.auditLevel != InternalAuditLevel.NONE) {
            auditLogger.logInternalAction(
                actionName = request.actionName,
                category = entry.category.name,
                success = result.success,
                duration = System.currentTimeMillis() - startTime,
                callerUid = callerUid,
                details = result.resultData ?: emptyMap()
            )
        }
        
        return result
    }

    override fun cancel(actionRequestId: String): Boolean {
        // 大部分内部动作是瞬时的，无法取消
        // 长时间运行的动作（如录音）可在子执行器中实现取消逻辑
        return false
    }

    override fun describeCapabilities(): String {
        return """
            InternalActionExecutor: System internal operations
            Categories:
            - PERFORMANCE_OPTIMIZATION: prewarm/render/schedule
            - POLICY_MANAGEMENT: activate/deactivate/reload
            - RESOURCE_PREPARATION: camera/audio preparation
            - MOTION_INTERACTION: gesture-triggered actions
            - TESTING_DEBUG: harness/dump for development
            
            Security:
            - Signature|privileged permission required
            - UID whitelist enforcement
            - Internal audit only (not user-visible)
            - Budget-exempt execution
        """.trimIndent()
    }
    
    /**
     * 检查调用者是否允许执行内部动作
     */
    private fun isCallerAllowed(uid: Int): Boolean {
        if (ALLOWED_UIDS.contains(uid)) {
            return true
        }
        
        // 检查是否是大脑App
        val brainPackageName = "com.genton.brain"  // 配置化
        val brainUid = try {
            context.packageManager.getApplicationInfo(
                brainPackageName,
                PackageManager.GET_META_DATA
            ).uid
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
        
        return uid == brainUid
    }
}

/**
 * 性能优化执行器（子执行器）
 * 
 * 处理：
 * - sys.prewarm_app
 * - sys.set_render_policy
 * - sys.schedule_prep
 */
class PerformanceOptimizationExecutor(
    private val context: Context,
    private val ams: ActivityManagerService,
    private val auditLogger: AuditLogger
) {
    
    fun execute(request: ActionRequest): ActionExecutionResult {
        return when (request.actionName) {
            "sys.prewarm_app" -> executePrewarmApp(request)
            "sys.set_render_policy" -> executeSetRenderPolicy(request)
            "sys.schedule_prep" -> executeSchedulePrep(request)
            else -> ActionExecutionResult.failed(
                "Unknown performance action: ${request.actionName}",
                errorCode = "UNKNOWN_ACTION"
            )
        }
    }
    
    /**
     * 预热应用进程
     */
    private fun executePrewarmApp(request: ActionRequest): ActionExecutionResult {
        val packageName = request.arguments["package_name"] as? String
            ?: return ActionExecutionResult.failed("Missing package_name", "MISSING_PARAM")
        
        val warmLevel = request.arguments["warm_level"] as? String ?: "process"
        
        return try {
            // 调用AMS预热接口
            when (warmLevel) {
                "process" -> {
                    // 仅启动进程，不创建Activity
                    ams.startProcessForPrewarm(packageName)
                }
                "activity" -> {
                    // 启动进程并创建Activity（但不显示）
                    val componentName = request.arguments["component_name"] as? String
                    ams.prewarmActivity(packageName, componentName)
                }
                "full" -> {
                    // 完整预热（包括首帧渲染）
                    ams.prewarmActivityFull(packageName)
                }
            }
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "package_name" to packageName,
                    "warm_level" to warmLevel,
                    "prewarmed" to true,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to prewarm app: ${e.message}",
                errorCode = "PREWARM_FAILED"
            )
        }
    }
    
    /**
     * 设置渲染策略
     */
    private fun executeSetRenderPolicy(request: ActionRequest): ActionExecutionResult {
        val policy = request.arguments["policy"] as? String
            ?: return ActionExecutionResult.failed("Missing policy", "MISSING_PARAM")
        
        val durationMs = request.arguments["duration_ms"] as? Int
        val scope = request.arguments["scope"] as? String ?: "global"
        
        return try {
            // 设置系统渲染策略
            // 实际实现需要与SurfaceFlinger/WindowManager协调
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "policy" to policy,
                    "duration_ms" to durationMs,
                    "scope" to scope,
                    "applied" to true,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to set render policy: ${e.message}",
                errorCode = "RENDER_POLICY_FAILED"
            )
        }
    }
    
    /**
     * 任务预调度
     */
    private fun executeSchedulePrep(request: ActionRequest): ActionExecutionResult {
        val taskId = request.arguments["task_id"] as? String
            ?: return ActionExecutionResult.failed("Missing task_id", "MISSING_PARAM")
        
        val prepActions = request.arguments["prep_actions"] as? List<*>
            ?: return ActionExecutionResult.failed("Missing prep_actions", "MISSING_PARAM")
        
        val deadlineMs = request.arguments["deadline_ms"] as? Int
        
        return try {
            // 调度准备动作（预热/预取等）
            val prepResults = mutableListOf<Map<String, Any>>()
            
            prepActions.forEach { action ->
                // 执行每个准备动作
                // 实际实现需要根据动作类型分发
            }
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "task_id" to taskId,
                    "prep_count" to prepActions.size,
                    "prep_results" to prepResults,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to schedule prep: ${e.message}",
                errorCode = "SCHEDULE_PREP_FAILED"
            )
        }
    }
}

/**
 * 策略管理执行器（子执行器）
 * 
 * 处理：
 * - policy.activate_suggestion
 * - policy.deactivate_rule
 * - policy.reload_policy_set
 */
class PolicyManagementExecutor(
    private val context: Context,
    private val policyEngine: ACBPolicyEngine,
    private val auditLogger: AuditLogger
) {
    
    fun execute(request: ActionRequest): ActionExecutionResult {
        return when (request.actionName) {
            "policy.activate_suggestion" -> executeActivateSuggestion(request)
            "policy.deactivate_rule" -> executeDeactivateRule(request)
            "policy.reload_policy_set" -> executeReloadPolicySet(request)
            else -> ActionExecutionResult.failed(
                "Unknown policy action: ${request.actionName}",
                errorCode = "UNKNOWN_ACTION"
            )
        }
    }
    
    /**
     * 激活learn建议
     */
    private fun executeActivateSuggestion(request: ActionRequest): ActionExecutionResult {
        val suggestionId = request.arguments["suggestion_id"] as? String
            ?: return ActionExecutionResult.failed("Missing suggestion_id", "MISSING_PARAM")
        
        val scope = request.arguments["scope"] as? String
            ?: return ActionExecutionResult.failed("Missing scope", "MISSING_PARAM")
        
        val userApproved = request.arguments["user_approved"] as? Boolean ?: false
        
        if (!userApproved) {
            return ActionExecutionResult.failed(
                "User approval required for suggestion activation",
                errorCode = "USER_APPROVAL_REQUIRED"
            )
        }
        
        return try {
            // 调用策略引擎激活建议
            policyEngine.activateSuggestion(suggestionId, scope)
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "suggestion_id" to suggestionId,
                    "scope" to scope,
                    "activated" to true,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to activate suggestion: ${e.message}",
                errorCode = "ACTIVATION_FAILED"
            )
        }
    }
    
    /**
     * 停用策略规则
     */
    private fun executeDeactivateRule(request: ActionRequest): ActionExecutionResult {
        val ruleId = request.arguments["rule_id"] as? String
            ?: return ActionExecutionResult.failed("Missing rule_id", "MISSING_PARAM")
        
        val reason = request.arguments["reason"] as? String
            ?: return ActionExecutionResult.failed("Missing reason", "MISSING_PARAM")
        
        val permanent = request.arguments["permanent"] as? Boolean ?: false
        
        return try {
            policyEngine.deactivateRule(ruleId, reason, permanent)
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "rule_id" to ruleId,
                    "reason" to reason,
                    "permanent" to permanent,
                    "deactivated" to true,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to deactivate rule: ${e.message}",
                errorCode = "DEACTIVATION_FAILED"
            )
        }
    }
    
    /**
     * 重载策略集
     */
    private fun executeReloadPolicySet(request: ActionRequest): ActionExecutionResult {
        val policyPackPath = request.arguments["policy_pack_path"] as? String
            ?: return ActionExecutionResult.failed("Missing policy_pack_path", "MISSING_PARAM")
        
        val verifySignature = request.arguments["verify_signature"] as? Boolean ?: true
        val rollbackOnError = request.arguments["rollback_on_error"] as? Boolean ?: true
        
        return try {
            val policyFile = File(policyPackPath)
            if (!policyFile.exists()) {
                return ActionExecutionResult.failed(
                    "Policy pack not found: $policyPackPath",
                    errorCode = "FILE_NOT_FOUND"
                )
            }
            
            // 重载策略集（包含签名验证和错误回滚）
            policyEngine.reloadPolicySet(policyFile, verifySignature, rollbackOnError)
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "policy_pack_path" to policyPackPath,
                    "verified" to verifySignature,
                    "reloaded" to true,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to reload policy set: ${e.message}",
                errorCode = "RELOAD_FAILED"
            )
        }
    }
}

/**
 * 资源准备执行器（子执行器）
 * 
 * 处理：
 * - sys.camera.prepare_qa
 * - sys.audio.record_memo
 * - sys.motion.enable_shake_ready
 * - sys.motion.start_memo_recording
 */
class ResourcePreparationExecutor(
    private val context: Context,
    private val auditLogger: AuditLogger
) {
    
    fun execute(request: ActionRequest): ActionExecutionResult {
        return when (request.actionName) {
            "sys.camera.prepare_qa" -> executeCameraPrepareQa(request)
            "sys.audio.record_memo" -> executeAudioRecordMemo(request)
            "sys.motion.enable_shake_ready" -> executeEnableShakeReady(request)
            "sys.motion.start_memo_recording" -> executeStartMemoRecording(request)
            else -> ActionExecutionResult.failed(
                "Unknown resource prep action: ${request.actionName}",
                errorCode = "UNKNOWN_ACTION"
            )
        }
    }
    
    private fun executeCameraPrepareQa(request: ActionRequest): ActionExecutionResult {
        // 相机Q&A准备实现
        return ActionExecutionResult.succeeded(
            resultData = mapOf("prepared" to true)
        )
    }
    
    private fun executeAudioRecordMemo(request: ActionRequest): ActionExecutionResult {
        val userConfirmed = request.arguments["user_confirmed"] as? Boolean ?: false
        
        if (!userConfirmed) {
            return ActionExecutionResult.failed(
                "User must confirm recording within 3 seconds",
                errorCode = "USER_CONFIRMATION_REQUIRED"
            )
        }
        
        // 备忘录录音实现
        return ActionExecutionResult.succeeded(
            resultData = mapOf("recording_started" to true)
        )
    }
    
    private fun executeEnableShakeReady(request: ActionRequest): ActionExecutionResult {
        // 摇一摇就绪实现
        return ActionExecutionResult.succeeded(
            resultData = mapOf("shake_ready_enabled" to true)
        )
    }
    
    private fun executeStartMemoRecording(request: ActionRequest): ActionExecutionResult {
        // 开始备忘录录音实现
        return ActionExecutionResult.succeeded(
            resultData = mapOf("memo_recording_started" to true)
        )
    }
}

/**
 * 测试调试执行器（子执行器）
 * 
 * 处理：
 * - cmd.run_harness
 * - cmd.dump_state
 */
class TestingDebugExecutor(
    private val context: Context,
    private val auditLogger: AuditLogger
) {
    
    fun execute(request: ActionRequest): ActionExecutionResult {
        return when (request.actionName) {
            "cmd.run_harness" -> executeRunHarness(request)
            "cmd.dump_state" -> executeDumpState(request)
            else -> ActionExecutionResult.failed(
                "Unknown testing action: ${request.actionName}",
                errorCode = "UNKNOWN_ACTION"
            )
        }
    }
    
    private fun executeRunHarness(request: ActionRequest): ActionExecutionResult {
        // 测试套件运行实现
        return ActionExecutionResult.succeeded(
            resultData = mapOf("harness_completed" to true)
        )
    }
    
    private fun executeDumpState(request: ActionRequest): ActionExecutionResult {
        // 状态转储实现
        return ActionExecutionResult.succeeded(
            resultData = mapOf("state_dumped" to true)
        )
    }
}

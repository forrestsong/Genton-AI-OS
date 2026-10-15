package com.ainative.brain.executor

import android.util.Log
import com.ainative.brain.BrainApplication
import com.ainative.brain.runtime.AgentRuntimeClient
import com.ainative.brain.security.SecurityInspector
import com.ainative.brain.security.TaintMarker
import com.ainative.action.ActionRequest
import com.ainative.brain.toolloop.ActionPlan
import com.ainative.brain.toolloop.ActionExecutionResult
import org.json.JSONObject

/**
 * 动作执行器（大脑侧）
 * 
 * 职责：
 * 1. 构建 ActionRequest：将 ActionPlan 转换为 AIDL 格式
 * 2. Taint 传递：将 taintMap 写入 ActionRequest.argTaints
 * 3. 调用 ACB：通过 AgentRuntimeClient 提交 executeAction
 * 4. 异常处理：处理执行失败、超时等异常
 */
class ActionExecutor(
    private val application: BrainApplication,
    private val runtimeClient: AgentRuntimeClient
) {
    
    companion object {
        private const val TAG = "ActionExecutor"
        
        // 执行超时（毫秒）
        const val DEFAULT_TIMEOUT_MS = 30_000L
        
        // 执行状态码（对应 ACB 返回值）
        const val SUBMIT_ACCEPTED = 0
        const val SUBMIT_UNKNOWN_ACTION = 1
        const val SUBMIT_POLICY_DENIED = 2
        const val SUBMIT_ARGUMENT_INVALID = 3
        const val SUBMIT_TAINT_DENIED = 4
        const val SUBMIT_CONFIRMATION_REQUIRED = 5
    }
    
    /**
     * 执行动作
     * 
     * @param action 动作计划
     * @param context 执行上下文
     * @param taintMap Taint 映射表
     * @return 动作执行结果
     */
    fun execute(
        action: ActionPlan,
        context: ExecutionContext,
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): ActionExecutionResult {
        Log.d(TAG, "Executing action: ${action.actionType}")
        
        try {
            // 1. 构建 ActionRequest
            val request = buildActionRequest(action, context, taintMap)
            
            // 2. 提交到 ACB 执行
            val submitResult = submitToACB(request, context)
            
            if (submitResult.submitCode != SUBMIT_ACCEPTED) {
                return ActionExecutionResult(
                    actionType = action.actionType,
                    status = "failed",
                    result = mapOf(
                        "error" to "Submit failed",
                        "submit_code" to submitResult.submitCode,
                        "message" to submitResult.message
                    ),
                    tokensConsumed = 0,
                    energyConsumed = 0,
                    wallTimeMs = 0
                )
            }
            
            // 3. 等待执行完成
            val executionResult = waitForCompletion(
                submitResult.actionRequestId,
                context,
                DEFAULT_TIMEOUT_MS
            )
            
            Log.i(TAG, "Action executed: ${action.actionType}, status=${executionResult.status}")
            
            return executionResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Action execution failed: ${action.actionType}", e)
            
            return ActionExecutionResult(
                actionType = action.actionType,
                status = "failed",
                result = mapOf("error" to (e.message ?: "unknown error")),
                tokensConsumed = 0,
                energyConsumed = 0,
                wallTimeMs = 0
            )
        }
    }
    
    /**
     * 构建 ActionRequest
     */
    private fun buildActionRequest(
        action: ActionPlan,
        context: ExecutionContext,
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): ActionRequest {
        Log.d(TAG, "Building ActionRequest for: ${action.actionType}")
        
        // 1. 构建参数 JSON
        val argumentsJson = JSONObject(action.parameters).toString()
        
        // 2. 生成 Taint 标记
        val argTaints = TaintMarker.generateAidlTaintMap(taintMap)
        
        // 3. 构建 ActionRequest
        // AIDL 生成的 ActionRequest 为 Java 类，只能逐字段赋值（不支持 Kotlin 命名参数）
        val request = ActionRequest()
        request.actionId = action.actionType
        request.actionName = action.actionType
        request.agentId = context.agentId
        request.sourceIntentId = context.turnId
        request.argumentsJson = argumentsJson
        request.argTaints = argTaints
        request.targetSummary = generateTargetSummary(action)
        request.riskEstimate = estimateRisk(action, taintMap)
        request.timeoutMs = DEFAULT_TIMEOUT_MS
        request.requireConfirmation = false
        return request
    }
    
    /**
     * 生成目标摘要（脱敏）
     */
    private fun generateTargetSummary(action: ActionPlan): String {
        // TODO: 实现更智能的摘要生成
        val params = action.parameters.entries.take(2).joinToString(", ") { 
            "${it.key}=${maskSensitiveValue(it.value)}" 
        }
        return "${action.actionType}($params)"
    }
    
    /**
     * 脱敏敏感值
     */
    private fun maskSensitiveValue(value: Any): String {
        val str = value.toString()
        return when {
            str.length > 20 -> str.substring(0, 17) + "..."
            else -> str
        }
    }
    
    /**
     * 估算风险等级
     */
    private fun estimateRisk(
        action: ActionPlan,
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): Int {
        // 0 = low, 1 = medium, 2 = high
        
        // 检查是否有高风险 Taint
        val maxTaintLevel = taintMap.values.maxOfOrNull { it.sourceLevel } ?: 0
        
        return when {
            maxTaintLevel >= 3 -> 2  // WEB_CONTENT/APP_CONTENT → high
            maxTaintLevel >= 2 -> 1  // AGENT_DERIVED → medium
            else -> 0                 // TRUSTED_SYSTEM/USER_INPUT → low
        }
    }
    
    /**
     * 提交到 ACB 执行
     */
    private fun submitToACB(
        request: ActionRequest,
        context: ExecutionContext
    ): SubmitResult {
        Log.d(TAG, "Submitting action to ACB: ${request.actionId}")
        
        try {
            // 创建回调
            val callback = createActionCallback(request.actionId, context)
            
            // 调用 AgentRuntimeClient.executeAction
            val success = runtimeClient.executeAction(request, callback)
            
            if (!success) {
                return SubmitResult(
                    submitCode = SUBMIT_UNKNOWN_ACTION,
                    message = "Failed to submit action to ACB",
                    actionRequestId = request.actionId
                )
            }
            
            // 返回成功结果
            return SubmitResult(
                submitCode = SUBMIT_ACCEPTED,
                message = "Action submitted successfully",
                actionRequestId = callback.actionRequestId ?: request.actionId
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit action to ACB", e)
            return SubmitResult(
                submitCode = SUBMIT_UNKNOWN_ACTION,
                message = "Exception: ${e.message}",
                actionRequestId = request.actionId
            )
        }
    }
    
    /**
     * 等待执行完成
     */
    private fun waitForCompletion(
        actionRequestId: String,
        context: ExecutionContext,
        timeoutMs: Long
    ): ActionExecutionResult {
        Log.d(TAG, "Waiting for action completion: $actionRequestId")
        
        // 通过 callback 的 waitForCompletion 等待
        // 注意：此方法现在由 ActionCallbackImpl.waitForCompletion() 接管
        
        // 如果没有 callback 引用，则使用轮询方式
        val startTime = System.currentTimeMillis()
        var lastStatus: AgentRuntimeClient.ActionStatus? = null
        
        while (true) {
            val status = runtimeClient.queryActionStatus(actionRequestId)
            
            if (status != null && status.isCompleted()) {
                lastStatus = status
                break
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > timeoutMs) {
                // 超时
                return ActionExecutionResult(
                    actionType = actionRequestId,
                    status = "timeout",
                    result = mapOf("error" to "Execution timeout"),
                    tokensConsumed = 0,
                    energyConsumed = 0,
                    wallTimeMs = elapsed
                )
            }
            
            Thread.sleep(100)
        }
        
        // 转换状态
        return if (lastStatus != null) {
            ActionExecutionResult(
                actionType = actionRequestId,
                status = if (lastStatus.isSuccess()) "success" else "failed",
                result = if (lastStatus.resultJson != null) {
                    try {
                        val json = JSONObject(lastStatus.resultJson)
                        json.keys().asSequence().associateWith { json.get(it) }
                    } catch (e: Exception) {
                        mapOf("result" to lastStatus.resultJson)
                    }
                } else {
                    mapOf("error" to (lastStatus.errorMessage ?: "Unknown error"))
                },
                tokensConsumed = lastStatus.tokensConsumed,
                energyConsumed = lastStatus.energyConsumed,
                wallTimeMs = lastStatus.wallTimeMs
            )
        } else {
            ActionExecutionResult(
                actionType = actionRequestId,
                status = "failed",
                result = mapOf("error" to "No status available"),
                tokensConsumed = 0,
                energyConsumed = 0,
                wallTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * 创建动作回调
     */
    private fun createActionCallback(
        actionId: String,
        context: ExecutionContext
    ): ActionCallback {
        return ActionCallback(actionId, context)
    }
    
    /**
     * 提交结果
     */
    data class SubmitResult(
        val submitCode: Int,
        val message: String,
        val actionRequestId: String?
    )
    
    /**
     * 执行上下文
     */
    data class ExecutionContext(
        val turnId: String,
        val agentId: String
    )
}

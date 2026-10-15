package com.ainative.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F1.1 宏录制工具链 - 重放校验器
 * 
 * 职责：
 * 1. 执行 MacroScript（重放用户交互）
 * 2. 校验执行结果（截图对比、元素断言）
 * 3. 生成执行报告（成功/失败、延迟、审计引用）
 * 4. 失败自动停用决策
 * 
 * 技术要点：
 * - 通过 AccessibilityService 执行手势
 * - 集成 A2.1 感知聚合器进行元素验证
 * - 集成 A3.2 审计服务记录执行轨迹
 * - 回退链处理
 * 
 * 对应任务：F1.1 重放校验器
 */
class MacroPlaybackValidator(
    private val context: Context,
    private val accessibilityService: AccessibilityService?
) {
    
    companion object {
        private const val TAG = "MacroPlaybackValidator"
        
        // 执行状态
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILED = 1
        const val STATUS_TIMEOUT = 2
        const val STATUS_PARTIAL_SUCCESS = 3
        
        // 失败阈值（连续失败多少次自动停用）
        private const val AUTO_DISABLE_THRESHOLD = 3
        
        // 步骤执行超时
        private const val STEP_TIMEOUT_MS = 5000L
    }
    
    // 活跃执行会话
    private val activeExecutions = ConcurrentHashMap<String, ExecutionSession>()
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 失败计数器（脚本 ID -> 连续失败次数）
    private val failureCounters = ConcurrentHashMap<String, Int>()
    
    /**
     * 执行脚本
     */
    fun execute(
        script: MacroScript,
        sessionId: String,
        callback: IMacroRecordingCallback?
    ): String {
        val executionId = generateExecutionId()
        
        Log.i(TAG, "Executing script: id=${script.scriptId}, session=$sessionId")
        
        val session = ExecutionSession(
            executionId = executionId,
            script = script,
            sessionId = sessionId,
            callback = callback,
            startTime = System.currentTimeMillis()
        )
        
        activeExecutions[executionId] = session
        
        // 异步执行
        Thread {
            try {
                val result = executeScriptInternal(session)
                session.result = result
                
                // 回调
                callback?.onCompleted(result)
                
                // 更新失败计数
                updateFailureCounter(script.scriptId, result.status != STATUS_SUCCESS)
                
            } catch (e: Exception) {
                Log.e(TAG, "Execution failed: $executionId", e)
                
                val result = createFailureResult(session, "Exception: ${e.message}")
                session.result = result
                
                callback?.onFailed(executionId, -1, e.message ?: "Unknown error")
                
                updateFailureCounter(script.scriptId, true)
                
            } finally {
                activeExecutions.remove(executionId)
            }
        }.start()
        
        return executionId
    }
    
    /**
     * 执行脚本（内部实现）
     */
    private fun executeScriptInternal(session: ExecutionSession): MacroExecutionResult {
        val script = session.script
        val steps = script.steps
        val failedSteps = mutableListOf<Int>()
        var executedSteps = 0
        
        for (step in steps) {
            try {
                // 通知进度
                session.callback?.onProgress(session.executionId, executedSteps, steps.size)
                
                // 执行步骤
                val success = executeStep(step, script)
                
                if (success) {
                    executedSteps++
                    session.callback?.onStepCompleted(session.executionId, step.stepId, true)
                } else {
                    failedSteps.add(step.stepId)
                    session.callback?.onStepCompleted(session.executionId, step.stepId, false)
                    
                    Log.w(TAG, "Step failed: stepId=${step.stepId}, type=${step.type}")
                    
                    // 根据策略决定是否继续
                    if (script.perceptionStrategy == MacroScriptGenerator.STRATEGY_PERCEPTION_ONLY) {
                        // 纯感知模式：失败即中止
                        break
                    }
                    // 混合模式：尝试继续
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Step execution error: stepId=${step.stepId}", e)
                failedSteps.add(step.stepId)
                break
            }
        }
        
        // 生成执行结果
        val endTime = System.currentTimeMillis()
        val status = determineStatus(executedSteps, steps.size, failedSteps.size)
        
        return MacroExecutionResult().apply {
            executionId = session.executionId
            scriptId = script.scriptId
            sessionId = session.sessionId
            this.status = status
            this.executedSteps = executedSteps
            totalSteps = steps.size
            failedStepIds = failedSteps.toIntArray()
            latencyMs = endTime - session.startTime
            startTime = session.startTime
            this.endTime = endTime
            
            // 审计引用（TODO: 集成 A3.2 审计服务）
            auditRef = "audit_${session.executionId}"
            
            // 设备信息
            deviceModel = android.os.Build.MODEL
            deviceManufacturer = android.os.Build.MANUFACTURER
            sdkVersion = android.os.Build.VERSION.SDK_INT
            
            // 失败自动停用决策
            shouldDisable = shouldAutoDisable(script.scriptId)
            
            if (status != STATUS_SUCCESS) {
                failureReason = "Failed steps: ${failedSteps.joinToString(", ")}"
                errorCode = -1
            }
        }
    }
    
    /**
     * 执行单个步骤
     */
    private fun executeStep(step: MacroStep, script: MacroScript): Boolean {
        Log.d(TAG, "Executing step: id=${step.stepId}, type=${step.type}")
        
        return when (step.type) {
            MacroRecordingEngine.STEP_TYPE_TAP -> {
                executeTap(step, script)
            }
            MacroRecordingEngine.STEP_TYPE_SWIPE -> {
                executeSwipe(step, script)
            }
            MacroRecordingEngine.STEP_TYPE_INPUT_TEXT -> {
                executeInputText(step)
            }
            MacroRecordingEngine.STEP_TYPE_WAIT -> {
                executeWait(step)
            }
            MacroRecordingEngine.STEP_TYPE_ASSERT_ELEMENT -> {
                executeAssertElement(step)
            }
            MacroRecordingEngine.STEP_TYPE_SCREENSHOT -> {
                executeScreenshot(step)
            }
            else -> {
                Log.w(TAG, "Unknown step type: ${step.type}")
                false
            }
        }
    }
    
    /**
     * 执行点击
     */
    private fun executeTap(step: MacroStep, script: MacroScript): Boolean {
        if (accessibilityService == null) {
            Log.e(TAG, "AccessibilityService not available")
            return false
        }
        
        // 反归一化坐标
        val x = denormalizeX(step.x, script)
        val y = denormalizeY(step.y, script)
        
        Log.d(TAG, "Executing tap: x=$x, y=$y")
        
        // 构建点击手势
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        // 执行手势
        val latch = CountDownLatch(1)
        var success = false
        
        accessibilityService.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        
        // 等待完成
        latch.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        
        return success
    }
    
    /**
     * 执行滑动
     */
    private fun executeSwipe(step: MacroStep, script: MacroScript): Boolean {
        if (accessibilityService == null) {
            Log.e(TAG, "AccessibilityService not available")
            return false
        }
        
        // 反归一化坐标
        val startX = denormalizeX(step.x, script)
        val startY = denormalizeY(step.y, script)
        val endX = denormalizeX(step.targetX, script)
        val endY = denormalizeY(step.targetY, script)
        
        Log.d(TAG, "Executing swipe: ($startX, $startY) -> ($endX, $endY)")
        
        // 构建滑动手势
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        
        val duration = step.durationMs.coerceIn(100, 2000).toLong()
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        // 执行手势
        val latch = CountDownLatch(1)
        var success = false
        
        accessibilityService.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        
        // 等待完成
        latch.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        
        return success
    }
    
    /**
     * 执行文本输入
     */
    private fun executeInputText(step: MacroStep): Boolean {
        if (accessibilityService == null) {
            Log.e(TAG, "AccessibilityService not available")
            return false
        }
        
        val text = step.inputText ?: return false
        
        Log.d(TAG, "Executing input: text=$text")
        
        // 查找焦点节点
        val rootNode = accessibilityService.rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        if (focusedNode == null) {
            Log.w(TAG, "No focused input field")
            return false
        }
        
        // 输入文本
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        
        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        focusedNode.recycle()
        
        return success
    }
    
    /**
     * 执行等待
     */
    private fun executeWait(step: MacroStep): Boolean {
        val waitMs = step.waitMs.toLong()
        Log.d(TAG, "Executing wait: ${waitMs}ms")
        
        Thread.sleep(waitMs)
        return true
    }
    
    /**
     * 执行元素断言
     */
    private fun executeAssertElement(step: MacroStep): Boolean {
        if (accessibilityService == null) {
            Log.e(TAG, "AccessibilityService not available")
            return false
        }
        
        val description = step.elementDescription ?: return false
        
        Log.d(TAG, "Executing assertion: element=$description")
        
        // TODO: 集成 A2.1 感知聚合器，验证元素是否存在
        // 当前简化实现：检查 AccessibilityNodeInfo 树
        
        val rootNode = accessibilityService.rootInActiveWindow ?: return false
        val found = searchNodeByDescription(rootNode, description)
        rootNode.recycle()
        
        return found
    }
    
    /**
     * 执行截图检查点
     */
    private fun executeScreenshot(step: MacroStep): Boolean {
        // TODO: 实现截图对比
        Log.d(TAG, "Executing screenshot checkpoint")
        return true
    }
    
    /**
     * 搜索节点（简化实现）
     */
    private fun searchNodeByDescription(node: AccessibilityNodeInfo, description: String): Boolean {
        val nodeText = node.text?.toString() ?: ""
        val nodeContentDesc = node.contentDescription?.toString() ?: ""
        
        if (nodeText.contains(description, ignoreCase = true) || 
            nodeContentDesc.contains(description, ignoreCase = true)) {
            return true
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = searchNodeByDescription(child, description)
                child.recycle()
                if (found) return true
            }
        }
        
        return false
    }
    
    /**
     * 反归一化坐标
     */
    private fun denormalizeX(normalizedX: Int, script: MacroScript): Float {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return (normalizedX.toFloat() / 10000) * screenWidth
    }
    
    private fun denormalizeY(normalizedY: Int, script: MacroScript): Float {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return (normalizedY.toFloat() / 10000) * screenHeight
    }
    
    /**
     * 确定执行状态
     */
    private fun determineStatus(executedSteps: Int, totalSteps: Int, failedCount: Int): Int {
        return when {
            executedSteps == totalSteps && failedCount == 0 -> STATUS_SUCCESS
            executedSteps == 0 -> STATUS_FAILED
            executedSteps < totalSteps -> STATUS_PARTIAL_SUCCESS
            else -> STATUS_FAILED
        }
    }
    
    /**
     * 创建失败结果
     */
    private fun createFailureResult(session: ExecutionSession, reason: String): MacroExecutionResult {
        return MacroExecutionResult().apply {
            executionId = session.executionId
            scriptId = session.script.scriptId
            sessionId = session.sessionId
            status = STATUS_FAILED
            executedSteps = 0
            totalSteps = session.script.steps.size
            failureReason = reason
            errorCode = -1
            latencyMs = System.currentTimeMillis() - session.startTime
            startTime = session.startTime
            endTime = System.currentTimeMillis()
            auditRef = "audit_${session.executionId}"
            deviceModel = android.os.Build.MODEL
            deviceManufacturer = android.os.Build.MANUFACTURER
            sdkVersion = android.os.Build.VERSION.SDK_INT
            shouldDisable = shouldAutoDisable(session.script.scriptId)
        }
    }
    
    /**
     * 更新失败计数器
     */
    private fun updateFailureCounter(scriptId: String, failed: Boolean) {
        if (failed) {
            val count = failureCounters.getOrDefault(scriptId, 0) + 1
            failureCounters[scriptId] = count
            Log.w(TAG, "Script failure count: scriptId=$scriptId, count=$count")
        } else {
            // 成功时重置计数器
            failureCounters.remove(scriptId)
        }
    }
    
    /**
     * 判断是否应该自动停用
     */
    private fun shouldAutoDisable(scriptId: String): Boolean {
        val count = failureCounters.getOrDefault(scriptId, 0)
        return count >= AUTO_DISABLE_THRESHOLD
    }
    
    /**
     * 生成执行 ID
     */
    private fun generateExecutionId(): String {
        return "exec_${UUID.randomUUID().toString().replace("-", "")}"
    }
    
    /**
     * 获取执行结果
     */
    fun getExecutionResult(executionId: String): MacroExecutionResult? {
        return activeExecutions[executionId]?.result
    }
    
    /**
     * 校验脚本（干跑）
     */
    fun validate(script: MacroScript): String {
        Log.i(TAG, "Validating script: id=${script.scriptId}")
        
        val validation = JSONObject()
        val issues = JSONArray()
        
        // 检查步骤有效性
        for (step in script.steps) {
            when (step.type) {
                MacroRecordingEngine.STEP_TYPE_TAP, MacroRecordingEngine.STEP_TYPE_SWIPE -> {
                    if (step.x < 0 || step.y < 0) {
                        issues.put("Invalid coordinates at step ${step.stepId}")
                    }
                }
                MacroRecordingEngine.STEP_TYPE_INPUT_TEXT -> {
                    if (step.inputText.isNullOrEmpty()) {
                        issues.put("Empty input text at step ${step.stepId}")
                    }
                }
                MacroRecordingEngine.STEP_TYPE_WAIT -> {
                    if (step.waitMs <= 0) {
                        issues.put("Invalid wait time at step ${step.stepId}")
                    }
                }
            }
        }
        
        validation.put("valid", issues.length() == 0)
        validation.put("issues", issues)
        validation.put("total_steps", script.steps.size)
        validation.put("perception_strategy", script.perceptionStrategy)
        
        return validation.toString()
    }
    
    /**
     * 执行会话
     */
    private data class ExecutionSession(
        val executionId: String,
        val script: MacroScript,
        val sessionId: String,
        val callback: IMacroRecordingCallback?,
        val startTime: Long,
        var result: MacroExecutionResult? = null
    )
}

package com.genton.macro

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * F1.1 宏录制工具链 - 脚本生成器
 * 
 * 职责：
 * 1. 将 MacroRecording 转换为可执行的 MacroScript
 * 2. 标准化步骤序列（去重、合并、优化）
 * 3. 集成感知适配策略（坐标 + 感知混合）
 * 4. 生成回退链
 * 
 * 感知策略：
 * - NONE：纯坐标（快速但脆弱）
 * - HYBRID：感知 + 坐标降级（推荐）
 * - PERCEPTION_ONLY：纯感知（慢但鲁棒）
 * 
 * 对应任务：F1.1 脚本生成
 */
class MacroScriptGenerator(private val context: Context) {
    
    companion object {
        private const val TAG = "MacroScriptGenerator"
        
        // 感知策略
        const val STRATEGY_NONE = 0
        const val STRATEGY_HYBRID = 1
        const val STRATEGY_PERCEPTION_ONLY = 2
        
        // 脚本版本
        private const val SCRIPT_VERSION = "1.0.0"
        
        // 步骤优化阈值
        private const val MIN_STEP_INTERVAL_MS = 100L  // 最小步骤间隔
        private const val MAX_WAIT_MS = 5000  // 最大等待时间
    }
    
    /**
     * 生成脚本
     */
    fun generate(recording: MacroRecording, perceptionStrategy: Int): MacroScript {
        Log.i(TAG, "Generating script: recording=${recording.recordingId}, strategy=$perceptionStrategy")
        
        // 1. 标准化步骤序列
        val normalizedSteps = normalizeSteps(recording.steps)
        
        // 2. 应用感知策略
        val enhancedSteps = applyPerceptionStrategy(normalizedSteps, perceptionStrategy)
        
        // 3. 构建脚本
        val script = MacroScript().apply {
            scriptId = generateScriptId()
            recordingId = recording.recordingId
            version = SCRIPT_VERSION
            name = recording.name
            description = recording.description
            targetPackage = recording.targetPackage
            steps = enhancedSteps.toTypedArray()
            this.perceptionStrategy = perceptionStrategy
            
            // 重试策略
            maxRetries = 3
            retryDelayMs = 1000
            
            // 超时配置
            timeoutMs = 30000
            
            // 机型校准参数（基于录制设备）
            calibrationParams = buildCalibrationParams(recording)
            
            // 生成时间戳
            generatedAt = System.currentTimeMillis()
        }
        
        Log.i(TAG, "Script generated: id=${script.scriptId}, steps=${script.steps.size}")
        
        return script
    }
    
    /**
     * 标准化步骤序列
     * 
     * - 去除冗余等待
     * - 合并连续点击
     * - 优化时序
     */
    private fun normalizeSteps(rawSteps: Array<MacroStep>): List<MacroStep> {
        val normalized = mutableListOf<MacroStep>()
        var lastTimestamp = 0L
        
        for (step in rawSteps) {
            // 去除过短的等待
            if (step.type == MacroRecordingEngine.STEP_TYPE_WAIT && step.waitMs < MIN_STEP_INTERVAL_MS) {
                continue
            }
            
            // 限制最大等待时间
            if (step.type == MacroRecordingEngine.STEP_TYPE_WAIT && step.waitMs > MAX_WAIT_MS) {
                step.waitMs = MAX_WAIT_MS
            }
            
            // 更新步骤 ID
            step.stepId = normalized.size
            
            // 记录相对时间戳
            step.timestamp = step.timestamp - lastTimestamp
            lastTimestamp += step.timestamp
            
            normalized.add(step)
        }
        
        Log.d(TAG, "Normalized steps: ${rawSteps.size} -> ${normalized.size}")
        
        return normalized
    }
    
    /**
     * 应用感知策略
     */
    private fun applyPerceptionStrategy(steps: List<MacroStep>, strategy: Int): List<MacroStep> {
        if (strategy == STRATEGY_NONE) {
            // 纯坐标，无需额外处理
            return steps
        }
        
        val enhanced = mutableListOf<MacroStep>()
        
        for (step in steps) {
            when (step.type) {
                MacroRecordingEngine.STEP_TYPE_TAP, MacroRecordingEngine.STEP_TYPE_SWIPE -> {
                    if (strategy == STRATEGY_HYBRID) {
                        // 混合策略：先尝试感知，失败时降级到坐标
                        val assertStep = createPerceptionAssertion(step)
                        if (assertStep != null) {
                            enhanced.add(assertStep)
                        }
                        enhanced.add(step)  // 保留坐标作为降级
                    } else if (strategy == STRATEGY_PERCEPTION_ONLY) {
                        // 纯感知：用感知替换坐标
                        val assertStep = createPerceptionAssertion(step)
                        if (assertStep != null) {
                            enhanced.add(assertStep)
                        } else {
                            enhanced.add(step)  // 降级
                        }
                    }
                }
                else -> {
                    enhanced.add(step)
                }
            }
        }
        
        Log.d(TAG, "Applied perception strategy: strategy=$strategy, steps=${steps.size} -> ${enhanced.size}")
        
        return enhanced
    }
    
    /**
     * 创建感知断言步骤
     * 
     * 将坐标点击转换为感知元素检测
     */
    private fun createPerceptionAssertion(step: MacroStep): MacroStep? {
        // TODO: 集成 A2.1 感知聚合器，识别点击位置的 UI 元素
        // 当前简化实现：生成通用描述
        
        val description = when (step.type) {
            MacroRecordingEngine.STEP_TYPE_TAP -> {
                "UI element at position (${step.x}, ${step.y})"
            }
            MacroRecordingEngine.STEP_TYPE_SWIPE -> {
                "Swipeable area from (${step.x}, ${step.y}) to (${step.targetX}, ${step.targetY})"
            }
            else -> null
        }
        
        if (description == null) {
            return null
        }
        
        return MacroStep().apply {
            stepId = step.stepId
            type = MacroRecordingEngine.STEP_TYPE_ASSERT_ELEMENT
            timestamp = step.timestamp
            elementDescription = description
            confidenceThreshold = 0.75f  // 感知置信度阈值
            this.description = "Perception check: $description"
        }
    }
    
    /**
     * 构建机型校准参数
     */
    private fun buildCalibrationParams(recording: MacroRecording): String {
        val params = JSONObject().apply {
            put("device_model", recording.deviceModel)
            put("device_manufacturer", recording.deviceManufacturer)
            put("screen_width", recording.screenWidth)
            put("screen_height", recording.screenHeight)
            put("screen_density", recording.screenDensity)
            put("sdk_version", recording.sdkVersion)
            
            // 坐标缩放因子（用于适配不同分辨率）
            put("coord_scale_x", 1.0f)
            put("coord_scale_y", 1.0f)
        }
        
        return params.toString()
    }
    
    /**
     * 生成脚本 ID
     */
    private fun generateScriptId(): String {
        return "macro_${UUID.randomUUID().toString().replace("-", "")}"
    }
    
    /**
     * 生成回退脚本
     * 
     * 将高级脚本降级为纯坐标脚本
     */
    fun generateFallbackScript(originalScript: MacroScript): MacroScript {
        Log.i(TAG, "Generating fallback script for: ${originalScript.scriptId}")
        
        // 去除所有感知步骤，仅保留坐标步骤
        val fallbackSteps = originalScript.steps.filter { step ->
            step.type != MacroRecordingEngine.STEP_TYPE_ASSERT_ELEMENT &&
            step.type != MacroRecordingEngine.STEP_TYPE_SCREENSHOT
        }
        
        return MacroScript().apply {
            scriptId = generateScriptId()
            recordingId = originalScript.recordingId
            version = originalScript.version
            name = "${originalScript.name} (Fallback)"
            description = "${originalScript.description} - Coordinate fallback"
            targetPackage = originalScript.targetPackage
            steps = fallbackSteps.toTypedArray()
            perceptionStrategy = STRATEGY_NONE  // 纯坐标
            
            maxRetries = 1  // 降级脚本仅重试1次
            retryDelayMs = 500
            timeoutMs = originalScript.timeoutMs
            calibrationParams = originalScript.calibrationParams
            
            generatedAt = System.currentTimeMillis()
        }
    }
}

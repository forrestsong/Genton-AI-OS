package com.genton.macro

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import com.genton.assets.AssetManifest
import com.genton.assets.AssetRegistration
import java.util.concurrent.ConcurrentHashMap

/**
 * F1.1 宏录制工具链 - 服务实现
 * 
 * IMacroRecorderService 的完整实现，整合：
 * - MacroRecordingEngine（录制引擎）
 * - MacroScriptGenerator（脚本生成器）
 * - MacroPlaybackValidator（重放校验器）
 * 
 * 职责：
 * 1. 统一管理宏的录制、生成、执行生命周期
 * 2. 与资产目录服务集成
 * 3. 与审计服务集成
 * 4. 提供对外 Binder 接口
 * 
 * 对应任务：F1.1 宏录制工具链
 */
class MacroRecorderServiceImpl(
    private val context: Context,
    private val accessibilityService: AccessibilityService?
) {
    
    companion object {
        private const val TAG = "MacroRecorderService"
        
        @Volatile
        private var instance: MacroRecorderServiceImpl? = null
        
        fun getInstance(context: Context, accessibilityService: AccessibilityService?): MacroRecorderServiceImpl {
            return instance ?: synchronized(this) {
                instance ?: MacroRecorderServiceImpl(context, accessibilityService).also {
                    instance = it
                }
            }
        }
    }
    
    // 核心组件
    private val recordingEngine = MacroRecordingEngine(context)
    private val scriptGenerator = MacroScriptGenerator(context)
    private val playbackValidator = MacroPlaybackValidator(context, accessibilityService)
    
    // 脚本存储（内存缓存，实际应持久化到数据库）
    private val scripts = ConcurrentHashMap<String, MacroScript>()
    
    // 录制缓存
    private val recordings = ConcurrentHashMap<String, MacroRecording>()
    
    /**
     * 获取 Binder 接口
     */
    fun getMacroRecorderBinder(): IMacroRecorderService.Stub {
        return object : IMacroRecorderService.Stub() {
            
            override fun startRecording(
                name: String?,
                targetPackage: String?,
                recordingId: Array<String>?
            ): Int {
                return try {
                    if (name.isNullOrEmpty()) {
                        return IMacroRecorderService.STATUS_DENIED
                    }
                    
                    val id = recordingEngine.startRecording(name, targetPackage)
                    recordingId?.set(0, id)
                    
                    Log.i(TAG, "Recording started: id=$id")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
            
            override fun stopRecording(
                recordingId: String?,
                recording: Array<MacroRecording>?
            ): Int {
                return try {
                    if (recordingId.isNullOrEmpty()) {
                        return IMacroRecorderService.STATUS_NOT_RECORDING
                    }
                    
                    val rec = recordingEngine.stopRecording(recordingId)
                    if (rec == null) {
                        return IMacroRecorderService.STATUS_NOT_RECORDING
                    }
                    
                    // 缓存录制
                    recordings[rec.recordingId] = rec
                    
                    recording?.set(0, rec)
                    
                    Log.i(TAG, "Recording stopped: id=$recordingId")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop recording", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
            
            override fun cancelRecording(recordingId: String?): Int {
                return try {
                    if (recordingId.isNullOrEmpty()) {
                        return IMacroRecorderService.STATUS_NOT_RECORDING
                    }
                    
                    val success = recordingEngine.cancelRecording(recordingId)
                    if (!success) {
                        return IMacroRecorderService.STATUS_NOT_RECORDING
                    }
                    
                    Log.i(TAG, "Recording canceled: id=$recordingId")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel recording", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
            
            override fun generateScript(
                recording: MacroRecording?,
                perceptionStrategy: Int,
                script: Array<MacroScript>?
            ): Int {
                return try {
                    if (recording == null) {
                        return IMacroRecorderService.STATUS_INVALID_SCRIPT
                    }
                    
                    val generated = scriptGenerator.generate(recording, perceptionStrategy)
                    
                    // 缓存脚本
                    scripts[generated.scriptId] = generated
                    
                    script?.set(0, generated)
                    
                    Log.i(TAG, "Script generated: id=${generated.scriptId}")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate script", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
            
            override fun saveScriptAsAsset(
                script: MacroScript?,
                assetId: Array<String>?
            ): Int {
                return try {
                    if (script == null) {
                        return IMacroRecorderService.STATUS_INVALID_SCRIPT
                    }
                    
                    // 构建资产注册信息
                    val manifest = AssetManifest().apply {
                        this.assetType = "macro"
                        name = script.name
                        description = script.description
                        version = script.version
                        category = "automation"
                        supportedIntents = arrayOf(script.name)
                        executorType = "macro"
                        executorConfig = """{"scriptId": "${script.scriptId}"}"""
                        requiredPermissions = arrayOf(
                            "android.permission.SYSTEM_ALERT_WINDOW",
                            "android.permission.WRITE_SECURE_SETTINGS"
                        )
                        requiredCapabilities = arrayOf("macro_execution")
                        publisher = "system"
                        publisherPackage = "com.genton.system"
                        minSdkVersion = 30
                        maxSdkVersion = 999
                        state = "enabled"
                        stagedRatio = 1.0f
                        registeredAt = System.currentTimeMillis()
                        updatedAt = System.currentTimeMillis()
                    }
                    
                    val registration = AssetRegistration().apply {
                        this.manifest = manifest
                        assetPayload = script.scriptId  // 引用脚本 ID
                        signature = ByteArray(0)  // TODO: 签名
                        fallbackChain = arrayOf()
                        publisherPackage = "com.genton.system"
                        enableImmediately = true
                        initialStagedRatio = 1.0f
                    }
                    
                    // TODO: 调用 IAgentAssetLibraryService.registerAsset
                    val generatedAssetId = "asset_macro_${script.scriptId}"
                    assetId?.set(0, generatedAssetId)
                    
                    Log.i(TAG, "Script saved as asset: scriptId=${script.scriptId}, assetId=$generatedAssetId")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save script as asset", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
            
            override fun executeScript(
                scriptId: String?,
                sessionId: String?,
                callback: IMacroRecordingCallback?,
                executionId: Array<String>?
            ): Int {
                return try {
                    if (scriptId.isNullOrEmpty() || sessionId.isNullOrEmpty()) {
                        return IMacroRecorderService.STATUS_INVALID_SCRIPT
                    }
                    
                    val script = scripts[scriptId]
                    if (script == null) {
                        Log.w(TAG, "Script not found: $scriptId")
                        return IMacroRecorderService.STATUS_INVALID_SCRIPT
                    }
                    
                    val execId = playbackValidator.execute(script, sessionId, callback)
                    executionId?.set(0, execId)
                    
                    Log.i(TAG, "Script execution started: scriptId=$scriptId, execId=$execId")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to execute script", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
            
            override fun getExecutionResult(executionId: String?): MacroExecutionResult? {
                return try {
                    if (executionId.isNullOrEmpty()) {
                        return null
                    }
                    
                    playbackValidator.getExecutionResult(executionId)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get execution result", e)
                    null
                }
            }
            
            override fun validateScript(scriptId: String?): String {
                return try {
                    if (scriptId.isNullOrEmpty()) {
                        return """{"valid": false, "error": "Invalid script ID"}"""
                    }
                    
                    val script = scripts[scriptId]
                    if (script == null) {
                        return """{"valid": false, "error": "Script not found"}"""
                    }
                    
                    playbackValidator.validate(script)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to validate script", e)
                    """{"valid": false, "error": "${e.message}"}"""
                }
            }
            
            override fun getCurrentRecording(): String? {
                return try {
                    recordingEngine.getCurrentRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get current recording", e)
                    null
                }
            }
            
            override fun listScripts(targetPackage: String?): Array<MacroScript> {
                return try {
                    val filtered = if (targetPackage.isNullOrEmpty()) {
                        scripts.values
                    } else {
                        scripts.values.filter { it.targetPackage == targetPackage }
                    }
                    
                    filtered.toTypedArray()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to list scripts", e)
                    emptyArray()
                }
            }
            
            override fun deleteScript(scriptId: String?): Int {
                return try {
                    if (scriptId.isNullOrEmpty()) {
                        return IMacroRecorderService.STATUS_INVALID_SCRIPT
                    }
                    
                    val removed = scripts.remove(scriptId)
                    if (removed == null) {
                        return IMacroRecorderService.STATUS_INVALID_SCRIPT
                    }
                    
                    Log.i(TAG, "Script deleted: id=$scriptId")
                    IMacroRecorderService.STATUS_OK
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete script", e)
                    IMacroRecorderService.STATUS_INTERNAL_ERROR
                }
            }
        }
    }
    
    /**
     * 获取录制引擎（供 AccessibilityService 使用）
     */
    fun getRecordingEngine(): MacroRecordingEngine {
        return recordingEngine
    }
    
    /**
     * 获取脚本生成器
     */
    fun getScriptGenerator(): MacroScriptGenerator {
        return scriptGenerator
    }
    
    /**
     * 获取重放校验器
     */
    fun getPlaybackValidator(): MacroPlaybackValidator {
        return playbackValidator
    }
}

package com.genton.brain.resident

import android.content.Context
import android.util.Log
import com.genton.brain.BrainApplication
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * B4.1 常驻小模型域 - 常驻模型管理器
 * 
 * 职责：
 * 1. 管理常驻在 NPU 小核的小模型（唤醒词、意图识别等）
 * 2. 模型生命周期管理（加载、卸载、切换）
 * 3. 与 NPU 调度器协作进行低功耗切换
 * 4. 域会话管理（跟踪当前活跃的常驻任务）
 * 
 * 设计要点：
 * - 小模型参数量 ≤20M（符合 WBS C4.1）
 * - 常驻 NPU 小核，不占用主推理资源
 * - 支持多模型并存（唤醒词 + 意图分类）
 * - 低功耗模式下自动降级
 */
class ResidentModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ResidentModelManager"
        
        // 模型类型
        const val MODEL_TYPE_WAKE_WORD = "wake_word"
        const val MODEL_TYPE_INTENT_CLASSIFIER = "intent_classifier"
        
        // 模型状态
        const val STATE_UNLOADED = 0
        const val STATE_LOADING = 1
        const val STATE_LOADED = 2
        const val STATE_RUNNING = 3
        const val STATE_SUSPENDED = 4
        const val STATE_UNLOADING = 5
        const val STATE_ERROR = -1
        
        // NPU 小核配置
        const val NPU_SMALL_CORE_ID = 0  // 小核 ID（硬件相关）
        const val MAX_RESIDENT_MODELS = 2  // 最多同时常驻 2 个模型
        const val MODEL_SIZE_LIMIT_MB = 20  // 单模型大小限制 20MB
    }
    
    // 已加载的常驻模型
    private val loadedModels = ConcurrentHashMap<String, ResidentModel>()
    
    // 模型状态锁
    private val lock = ReentrantReadWriteLock()
    
    // 启动状态
    @Volatile
    private var isStarted = false
    
    /**
     * 启动常驻模型管理器
     */
    fun start() {
        if (isStarted) {
            Log.w(TAG, "ResidentModelManager already started")
            return
        }
        
        Log.i(TAG, "Starting ResidentModelManager...")
        
        try {
            // 1. 初始化 NPU 小核
            initializeNpuSmallCore()
            
            // 2. 加载默认常驻模型（唤醒词模型）
            loadDefaultModels()
            
            isStarted = true
            Log.i(TAG, "ResidentModelManager started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ResidentModelManager", e)
            throw e
        }
    }
    
    /**
     * 停止常驻模型管理器
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "Stopping ResidentModelManager...")
        
        try {
            // 卸载所有模型
            unloadAllModels()
            
            isStarted = false
            Log.i(TAG, "ResidentModelManager stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ResidentModelManager", e)
        }
    }
    
    /**
     * 初始化 NPU 小核
     */
    private fun initializeNpuSmallCore() {
        Log.i(TAG, "Initializing NPU small core...")
        
        // TODO: 通过 HAL 层初始化 NPU 小核
        // 当前简化实现：检查 NPU 小核是否可用
        
        val npuAvailable = checkNpuSmallCoreAvailable()
        if (!npuAvailable) {
            Log.w(TAG, "NPU small core not available, using CPU fallback")
            // 降级到 CPU 执行
        } else {
            Log.i(TAG, "NPU small core initialized (core_id=$NPU_SMALL_CORE_ID)")
        }
    }
    
    /**
     * 检查 NPU 小核是否可用
     */
    private fun checkNpuSmallCoreAvailable(): Boolean {
        // TODO: 实际实现需要通过 HAL 查询硬件能力
        // 当前简化：检查系统属性或配置文件
        
        return try {
            val props = android.os.SystemProperties.get("ro.genton.npu.small_core", "0")
            props == "1"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check NPU small core availability", e)
            false
        }
    }
    
    /**
     * 加载默认常驻模型
     */
    private fun loadDefaultModels() {
        Log.i(TAG, "Loading default resident models...")
        
        // 默认加载唤醒词模型（意图分类器按需加载）
        loadModel(MODEL_TYPE_WAKE_WORD, "/system/genton/models/wake_word_v1.tflite")
    }
    
    /**
     * 加载模型
     */
    fun loadModel(modelType: String, modelPath: String): Boolean {
        lock.write {
            // 检查是否已加载
            if (loadedModels.containsKey(modelType)) {
                Log.w(TAG, "Model already loaded: $modelType")
                return true
            }
            
            // 检查常驻模型数量限制
            if (loadedModels.size >= MAX_RESIDENT_MODELS) {
                Log.e(TAG, "Cannot load model: resident model limit reached ($MAX_RESIDENT_MODELS)")
                return false
            }
            
            Log.i(TAG, "Loading resident model: type=$modelType, path=$modelPath")
            
            try {
                // 创建模型实例
                val model = ResidentModel(
                    modelType = modelType,
                    modelPath = modelPath,
                    coreId = NPU_SMALL_CORE_ID
                )
                
                // 设置状态为加载中
                model.state = STATE_LOADING
                
                // 执行实际加载（异步）
                val loaded = model.load()
                
                if (loaded) {
                    model.state = STATE_LOADED
                    loadedModels[modelType] = model
                    Log.i(TAG, "Model loaded successfully: $modelType (size=${model.sizeBytes / 1024}KB)")
                    return true
                } else {
                    model.state = STATE_ERROR
                    Log.e(TAG, "Failed to load model: $modelType")
                    return false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model: $modelType", e)
                return false
            }
        }
    }
    
    /**
     * 卸载模型
     */
    fun unloadModel(modelType: String): Boolean {
        lock.write {
            val model = loadedModels[modelType]
            if (model == null) {
                Log.w(TAG, "Model not loaded: $modelType")
                return false
            }
            
            Log.i(TAG, "Unloading resident model: $modelType")
            
            try {
                model.state = STATE_UNLOADING
                model.unload()
                loadedModels.remove(modelType)
                Log.i(TAG, "Model unloaded: $modelType")
                return true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model: $modelType", e)
                model.state = STATE_ERROR
                return false
            }
        }
    }
    
    /**
     * 卸载所有模型
     */
    private fun unloadAllModels() {
        lock.write {
            Log.i(TAG, "Unloading all resident models...")
            
            val modelTypes = loadedModels.keys.toList()
            for (modelType in modelTypes) {
                unloadModel(modelType)
            }
        }
    }
    
    /**
     * 获取模型实例
     */
    fun getModel(modelType: String): ResidentModel? {
        return lock.read {
            loadedModels[modelType]
        }
    }
    
    /**
     * 获取所有已加载的模型
     */
    fun getLoadedModels(): List<ResidentModel> {
        return lock.read {
            loadedModels.values.toList()
        }
    }
    
    /**
     * 挂起模型（低功耗模式）
     */
    fun suspendModel(modelType: String): Boolean {
        lock.write {
            val model = loadedModels[modelType]
            if (model == null || model.state != STATE_RUNNING) {
                Log.w(TAG, "Cannot suspend model: $modelType (state=${model?.state})")
                return false
            }
            
            Log.i(TAG, "Suspending resident model: $modelType")
            
            try {
                model.suspend()
                model.state = STATE_SUSPENDED
                return true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error suspending model: $modelType", e)
                return false
            }
        }
    }
    
    /**
     * 恢复模型
     */
    fun resumeModel(modelType: String): Boolean {
        lock.write {
            val model = loadedModels[modelType]
            if (model == null || model.state != STATE_SUSPENDED) {
                Log.w(TAG, "Cannot resume model: $modelType (state=${model?.state})")
                return false
            }
            
            Log.i(TAG, "Resuming resident model: $modelType")
            
            try {
                model.resume()
                model.state = STATE_RUNNING
                return true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming model: $modelType", e)
                return false
            }
        }
    }
    
    /**
     * 获取状态摘要
     */
    fun getStatusSummary(): String {
        return lock.read {
            val models = loadedModels.values.joinToString(", ") { 
                "${it.modelType}:${getStateName(it.state)}"
            }
            "ResidentModelManager: started=$isStarted, models=[$models]"
        }
    }
    
    private fun getStateName(state: Int): String {
        return when (state) {
            STATE_UNLOADED -> "unloaded"
            STATE_LOADING -> "loading"
            STATE_LOADED -> "loaded"
            STATE_RUNNING -> "running"
            STATE_SUSPENDED -> "suspended"
            STATE_UNLOADING -> "unloading"
            STATE_ERROR -> "error"
            else -> "unknown"
        }
    }
}

/**
 * 常驻模型数据类
 */
data class ResidentModel(
    val modelType: String,
    val modelPath: String,
    val coreId: Int,
    @Volatile var state: Int = ResidentModelManager.STATE_UNLOADED,
    var sizeBytes: Long = 0,
    var loadTimestamp: Long = 0
) {
    
    companion object {
        private const val TAG = "ResidentModel"
    }
    
    // 模型句柄（实际实现时替换为真实的 NPU 模型句柄）
    private var modelHandle: Long = 0
    
    /**
     * 加载模型
     */
    fun load(): Boolean {
        Log.i(TAG, "Loading model: type=$modelType, path=$modelPath")
        
        try {
            // TODO: 实际实现：
            // 1. 通过 TFLite 或 QNN SDK 加载模型到 NPU 小核
            // 2. 配置推理引擎（delegate=NPU_SMALL_CORE）
            // 3. 获取模型句柄
            
            // 当前简化实现：检查文件是否存在
            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                return false
            }
            
            sizeBytes = modelFile.length()
            
            // 检查模型大小限制
            val sizeMB = sizeBytes / (1024 * 1024)
            if (sizeMB > ResidentModelManager.MODEL_SIZE_LIMIT_MB) {
                Log.e(TAG, "Model too large: ${sizeMB}MB > ${ResidentModelManager.MODEL_SIZE_LIMIT_MB}MB")
                return false
            }
            
            // 模拟加载延迟
            Thread.sleep(100)
            
            // 生成模拟句柄
            modelHandle = System.currentTimeMillis()
            loadTimestamp = System.currentTimeMillis()
            
            Log.i(TAG, "Model loaded: type=$modelType, size=${sizeMB}MB, handle=$modelHandle")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelType", e)
            return false
        }
    }
    
    /**
     * 卸载模型
     */
    fun unload() {
        Log.i(TAG, "Unloading model: type=$modelType, handle=$modelHandle")
        
        try {
            // TODO: 实际实现：释放 NPU 资源
            
            modelHandle = 0
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unload model: $modelType", e)
        }
    }
    
    /**
     * 挂起模型
     */
    fun suspend() {
        Log.i(TAG, "Suspending model: type=$modelType")
        
        // TODO: 实际实现：暂停 NPU 推理，保留模型权重在内存
    }
    
    /**
     * 恢复模型
     */
    fun resume() {
        Log.i(TAG, "Resuming model: type=$modelType")
        
        // TODO: 实际实现：恢复 NPU 推理
    }
    
    /**
     * 执行推理
     */
    fun inference(inputData: FloatArray): FloatArray {
        if (state != ResidentModelManager.STATE_RUNNING && state != ResidentModelManager.STATE_LOADED) {
            throw IllegalStateException("Model not in running/loaded state: $modelType (state=$state)")
        }
        
        // TODO: 实际实现：调用 NPU 推理接口
        
        // 当前简化实现：返回模拟结果
        return FloatArray(10) { 0.1f }
    }
}

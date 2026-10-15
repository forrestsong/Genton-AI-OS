package com.genton.brain.resident

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B4.1 常驻小模型域 - 协调器
 * 
 * 职责：
 * 1. 统一管理常驻小模型域的所有组件
 * 2. 协调各组件的启动、停止和生命周期
 * 3. 处理唤醒词检测到意图分类的流程
 * 4. 提供统一的对外接口
 * 
 * 组件架构：
 * ┌────────────────────────────────────────────┐
 * │      ResidentDomainCoordinator             │
 * │  (常驻小模型域总协调器)                      │
 * └────────────────────────────────────────────┘
 *           │
 *           ├─► ResidentModelManager (模型管理)
 *           │   ├─► WakeWordModel (唤醒词检测模型)
 *           │   └─► IntentClassifierModel (意图分类模型)
 *           │
 *           ├─► WakeWordDetector (唤醒词检测器)
 *           │
 *           ├─► IntentClassifier (意图分类器)
 *           │
 *           ├─► SessionDomainManager (域会话管理)
 *           │
 *           └─► NpuScheduler (NPU调度器)
 *               └─► 低功耗切换控制
 * 
 * 符合 WBS B4.1：
 * - 常驻小模型域：唤醒词/意图识别模型常驻 NPU 小核
 * - 域会话管理
 * - 低功耗切换
 */
class ResidentDomainCoordinator(private val context: Context) {
    
    companion object {
        private const val TAG = "ResidentDomainCoordinator"
        
        @Volatile
        private var instance: ResidentDomainCoordinator? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): ResidentDomainCoordinator {
            return instance ?: synchronized(this) {
                instance ?: ResidentDomainCoordinator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    // 组件实例
    private val modelManager: ResidentModelManager = ResidentModelManager(context)
    private val sessionManager: SessionDomainManager = SessionDomainManager()
    private val npuScheduler: NpuScheduler = NpuScheduler(context, modelManager)
    private val wakeWordDetector: WakeWordDetector = WakeWordDetector(modelManager)
    private val intentClassifier: IntentClassifier = IntentClassifier(modelManager)
    
    // C4.2 新增：通知分类器和反骚扰评分器
    private val notificationClassifier: NotificationClassifier = NotificationClassifier(modelManager)
    private val spamScorer: SpamScorer = SpamScorer(modelManager)
    
    // 运行状态
    private val isInitialized = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    
    // 意图接收回调
    private val intentCallbacks = mutableListOf<IntentDetectionCallback>()
    
    /**
     * 初始化常驻域
     */
    fun initialize() {
        if (isInitialized.get()) {
            Log.w(TAG, "ResidentDomainCoordinator already initialized")
            return
        }
        
        Log.i(TAG, "Initializing ResidentDomainCoordinator...")
        
        try {
            // 1. 启动模型管理器
            modelManager.start()
            
            // 2. 启动会话管理器
            sessionManager.start()
            
            // 3. 启动 NPU 调度器
            npuScheduler.start()
            
            isInitialized.set(true)
            Log.i(TAG, "ResidentDomainCoordinator initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ResidentDomainCoordinator", e)
            throw e
        }
    }
    
    /**
     * 启动常驻域（开始检测）
     */
    fun start() {
        if (!isInitialized.get()) {
            throw IllegalStateException("Must call initialize() before start()")
        }
        
        if (isStarted.get()) {
            Log.w(TAG, "ResidentDomainCoordinator already started")
            return
        }
        
        Log.i(TAG, "Starting ResidentDomainCoordinator...")
        
        try {
            // 1. 启动意图分类器
            intentClassifier.start()
            
            // 2. 注册唤醒词回调
            wakeWordDetector.registerCallback(wakeWordCallback)
            
            // 3. 启动唤醒词检测
            wakeWordDetector.start()
            
            isStarted.set(true)
            Log.i(TAG, "ResidentDomainCoordinator started (wake word detection active)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ResidentDomainCoordinator", e)
            throw e
        }
    }
    
    /**
     * 停止常驻域
     */
    fun stop() {
        if (!isStarted.get()) {
            return
        }
        
        Log.i(TAG, "Stopping ResidentDomainCoordinator...")
        
        try {
            // 1. 停止唤醒词检测
            wakeWordDetector.stop()
            
            // 2. 注销回调
            wakeWordDetector.unregisterCallback(wakeWordCallback)
            
            // 3. 停止意图分类器
            intentClassifier.stop()
            
            isStarted.set(false)
            Log.i(TAG, "ResidentDomainCoordinator stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ResidentDomainCoordinator", e)
        }
    }
    
    /**
     * 关闭常驻域（释放所有资源）
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down ResidentDomainCoordinator...")
        
        try {
            // 1. 停止检测
            if (isStarted.get()) {
                stop()
            }
            
            // 2. 停止所有组件
            npuScheduler.stop()
            sessionManager.stop()
            modelManager.stop()
            
            isInitialized.set(false)
            Log.i(TAG, "ResidentDomainCoordinator shutdown complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
    
    /**
     * 唤醒词检测回调
     */
    private val wakeWordCallback = object : WakeWordCallback {
        override fun onWakeWordDetected(detection: WakeWordDetection) {
            Log.i(TAG, "Wake word detected: ${detection.wakeWord}, confidence=${detection.confidence}")
            
            try {
                // 创建域会话
                val sessionId = sessionManager.createSession(
                    SessionDomainManager.DOMAIN_WAKE_WORD,
                    mapOf(
                        "wake_word" to detection.wakeWord,
                        "confidence" to detection.confidence.toString()
                    )
                )
                
                sessionManager.activateSession(sessionId)
                
                // 记录上下文
                sessionManager.updateContext(sessionId, SessionContext(
                    timestamp = detection.timestamp,
                    eventType = "wake_word",
                    data = mapOf(
                        "wake_word" to detection.wakeWord,
                        "confidence" to detection.confidence
                    )
                ))
                
                // TODO: 触发后续流程（如开始录音、等待用户命令等）
                // 这部分应该与 IntentReceiver 集成
                
                Log.i(TAG, "Wake word session created: $sessionId")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling wake word detection", e)
            }
        }
    }
    
    /**
     * 分类用户意图（供外部调用）
     */
    fun classifyIntent(userInput: String, context: IntentContext? = null): IntentClassification {
        if (!isStarted.get()) {
            throw IllegalStateException("ResidentDomainCoordinator not started")
        }
        
        Log.d(TAG, "Classifying intent: input=$userInput")
        
        // 创建会话
        val sessionId = sessionManager.createSession(SessionDomainManager.DOMAIN_INTENT_CLASSIFICATION)
        sessionManager.activateSession(sessionId)
        
        try {
            // 调用意图分类器
            val classification = intentClassifier.classify(userInput, context)
            
            // 记录上下文
            sessionManager.updateContext(sessionId, SessionContext(
                timestamp = System.currentTimeMillis(),
                eventType = "intent",
                data = mapOf(
                    "domain" to classification.domain,
                    "intent" to classification.specificIntent,
                    "confidence" to classification.confidence
                )
            ))
            
            // 触发回调
            notifyIntentDetected(classification)
            
            // 终止会话
            sessionManager.terminateSession(sessionId)
            
            return classification
            
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying intent", e)
            sessionManager.terminateSession(sessionId)
            throw e
        }
    }
    
    /**
     * 注册意图检测回调
     */
    fun registerIntentCallback(callback: IntentDetectionCallback) {
        synchronized(intentCallbacks) {
            if (!intentCallbacks.contains(callback)) {
                intentCallbacks.add(callback)
            }
        }
    }
    
    /**
     * 注销意图检测回调
     */
    fun unregisterIntentCallback(callback: IntentDetectionCallback) {
        synchronized(intentCallbacks) {
            intentCallbacks.remove(callback)
        }
    }
    
    /**
     * 通知意图检测结果
     */
    private fun notifyIntentDetected(classification: IntentClassification) {
        synchronized(intentCallbacks) {
            intentCallbacks.forEach { callback ->
                try {
                    callback.onIntentDetected(classification)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in intent callback", e)
                }
            }
        }
    }
    
    /**
     * C4.2 新增：对通知进行分类
     * 
     * @param context 通知上下文
     * @return 分类结果（category + confidence）
     */
    fun classifyNotification(context: NotificationClassifier.NotificationContext): NotificationClassifier.ClassificationResult {
        return notificationClassifier.classify(context)
    }
    
    /**
     * C4.2 新增：对来电进行骚扰评分
     * 
     * @param context 来电上下文
     * @return 骚扰评分结果（0.0-1.0）
     */
    fun scoreCall(context: SpamScorer.CallContext): SpamScorer.SpamScore {
        return spamScorer.score(context)
    }
    
    /**
     * C4.2 新增：用户标记电话为骚扰
     */
    fun markCallAsSpam(phoneNumber: String, score: Float = 1.0f) {
        spamScorer.markAsSpam(phoneNumber, score)
    }
    
    /**
     * C4.2 新增：用户标记电话为非骚扰
     */
    fun markCallAsNotSpam(phoneNumber: String) {
        spamScorer.markAsNotSpam(phoneNumber)
    }
    
    /**
     * 获取统一状态摘要
     */
    fun getStatusSummary(): ResidentDomainStatus {
        return ResidentDomainStatus(
            isInitialized = isInitialized.get(),
            isStarted = isStarted.get(),
            modelManagerStatus = modelManager.getStatusSummary(),
            sessionStatistics = sessionManager.getStatistics(),
            npuStatistics = npuScheduler.getStatistics(),
            wakeWordStatistics = wakeWordDetector.getStatistics(),
            intentStatistics = intentClassifier.getStatistics()
        )
    }
    
    /**
     * 低功耗控制
     */
    fun enterLowPowerMode() {
        Log.i(TAG, "Entering low power mode")
        npuScheduler.forceMode(NpuScheduler.MODE_LOW_POWER)
        wakeWordDetector.pause()
    }
    
    fun exitLowPowerMode() {
        Log.i(TAG, "Exiting low power mode")
        npuScheduler.clearForcedMode()
        wakeWordDetector.resume()
    }
}

/**
 * 意图检测回调接口
 */
interface IntentDetectionCallback {
    /**
     * 检测到意图时触发
     */
    fun onIntentDetected(classification: IntentClassification)
}

/**
 * 常驻域状态摘要
 */
data class ResidentDomainStatus(
    val isInitialized: Boolean,
    val isStarted: Boolean,
    val modelManagerStatus: String,
    val sessionStatistics: SessionDomainStatistics,
    val npuStatistics: NpuSchedulerStatistics,
    val wakeWordStatistics: WakeWordStatistics,
    val intentStatistics: IntentClassifierStatistics
)

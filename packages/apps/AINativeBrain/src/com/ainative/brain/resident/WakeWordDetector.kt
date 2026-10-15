package com.ainative.brain.resident

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * B4.1 常驻小模型域 - 唤醒词检测器
 * 
 * 职责：
 * 1. 监听音频流，检测唤醒词（如 "Hi, Brain"）
 * 2. 常驻 NPU 小核运行，低功耗实时检测
 * 3. 检测到唤醒词后触发意图接收流程
 * 4. 支持多唤醒词配置（不同唤醒词触发不同意图）
 * 
 * 设计要点：
 * - 使用小参数量模型（≤5M），常驻 NPU 小核
 * - 音频流采样率：16kHz，16bit
 * - 检测延迟目标：≤150ms（从唤醒词结束到检测完成）
 * - 误唤醒率：<1次/天（环境噪声）
 * - 支持低功耗模式（锁屏时降低采样频率）
 */
class WakeWordDetector(private val modelManager: ResidentModelManager) {
    
    companion object {
        private const val TAG = "WakeWordDetector"
        
        // 音频配置
        private const val SAMPLE_RATE = 16000  // 16kHz
        private const val CHANNELS = 1  // 单声道
        private const val BIT_DEPTH = 16  // 16bit
        
        // 检测配置
        private const val DETECTION_WINDOW_MS = 1000  // 检测窗口 1秒
        private const val CONFIDENCE_THRESHOLD = 0.85f  // 置信度阈值
        private const val MIN_ACTIVATION_INTERVAL_MS = 3000L  // 最小激活间隔 3秒（防抖）
        
        // 唤醒词类型
        const val WAKE_WORD_PRIMARY = "hi_brain"  // 主唤醒词
        const val WAKE_WORD_CAMERA = "show_me"  // 相机唤醒词（配合姿态）
        const val WAKE_WORD_MEMO = "take_note"  // 备忘录唤醒词
    }
    
    // 运行状态
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    
    // 音频采集线程
    private var audioThread: Thread? = null
    
    // 检测回调
    private val callbacks = mutableListOf<WakeWordCallback>()
    private val callbackLock = ReentrantLock()
    
    // 最后激活时间（防抖）
    private var lastActivationTime = 0L
    
    // 统计信息
    private var totalDetections = 0L
    private var falsePositives = 0L
    
    /**
     * 启动唤醒词检测
     */
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "WakeWordDetector already running")
            return
        }
        
        Log.i(TAG, "Starting wake word detector...")
        
        try {
            // 1. 确保唤醒词模型已加载
            ensureModelLoaded()
            
            // 2. 启动音频采集线程
            startAudioCapture()
            
            isRunning.set(true)
            Log.i(TAG, "Wake word detector started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word detector", e)
            throw e
        }
    }
    
    /**
     * 停止唤醒词检测
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        Log.i(TAG, "Stopping wake word detector...")
        
        try {
            // 停止音频采集
            stopAudioCapture()
            
            isRunning.set(false)
            Log.i(TAG, "Wake word detector stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop wake word detector", e)
        }
    }
    
    /**
     * 暂停检测（低功耗模式）
     */
    fun pause() {
        if (!isRunning.get() || isPaused.get()) {
            return
        }
        
        Log.i(TAG, "Pausing wake word detector...")
        isPaused.set(true)
        
        // 挂起模型以节省功耗
        modelManager.suspendModel(ResidentModelManager.MODEL_TYPE_WAKE_WORD)
    }
    
    /**
     * 恢复检测
     */
    fun resume() {
        if (!isRunning.get() || !isPaused.get()) {
            return
        }
        
        Log.i(TAG, "Resuming wake word detector...")
        isPaused.set(false)
        
        // 恢复模型
        modelManager.resumeModel(ResidentModelManager.MODEL_TYPE_WAKE_WORD)
    }
    
    /**
     * 确保唤醒词模型已加载
     */
    private fun ensureModelLoaded() {
        val model = modelManager.getModel(ResidentModelManager.MODEL_TYPE_WAKE_WORD)
        
        if (model == null) {
            // 模型未加载，尝试加载
            val loaded = modelManager.loadModel(
                ResidentModelManager.MODEL_TYPE_WAKE_WORD,
                "/system/ainative/models/wake_word_v1.tflite"
            )
            
            if (!loaded) {
                throw IllegalStateException("Failed to load wake word model")
            }
        }
        
        // 将模型状态设置为运行
        val loadedModel = modelManager.getModel(ResidentModelManager.MODEL_TYPE_WAKE_WORD)
        if (loadedModel != null && loadedModel.state == ResidentModelManager.STATE_LOADED) {
            loadedModel.state = ResidentModelManager.STATE_RUNNING
        }
    }
    
    /**
     * 启动音频采集
     */
    private fun startAudioCapture() {
        audioThread = Thread({
            Log.i(TAG, "Audio capture thread started")
            
            try {
                // TODO: 实际实现：
                // 1. 打开音频输入设备（AudioRecord）
                // 2. 配置音频参数（16kHz, 16bit, mono）
                // 3. 循环读取音频帧
                
                // 当前简化实现：模拟音频采集
                simulateAudioCapture()
                
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
            } finally {
                Log.i(TAG, "Audio capture thread stopped")
            }
        }, "WakeWordAudioCapture")
        
        audioThread?.start()
    }
    
    /**
     * 停止音频采集
     */
    private fun stopAudioCapture() {
        audioThread?.interrupt()
        audioThread?.join(1000)
        audioThread = null
    }
    
    /**
     * 模拟音频采集（简化实现）
     */
    private fun simulateAudioCapture() {
        val windowSizeBytes = (SAMPLE_RATE * DETECTION_WINDOW_MS / 1000) * (BIT_DEPTH / 8) * CHANNELS
        val audioBuffer = ByteArray(windowSizeBytes)
        
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                // 暂停时跳过处理
                if (isPaused.get()) {
                    Thread.sleep(100)
                    continue
                }
                
                // TODO: 实际实现：从 AudioRecord 读取音频数据
                // audioRecord.read(audioBuffer, 0, audioBuffer.size)
                
                // 模拟音频读取延迟
                Thread.sleep(DETECTION_WINDOW_MS.toLong())
                
                // 处理音频帧
                processAudioFrame(audioBuffer)
                
            } catch (e: InterruptedException) {
                Log.i(TAG, "Audio capture interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio frame", e)
            }
        }
    }
    
    /**
     * 处理音频帧，检测唤醒词
     */
    private fun processAudioFrame(audioData: ByteArray) {
        try {
            // 1. 转换音频数据为模型输入格式（特征提取：MFCC/Mel-spectrogram）
            val inputFeatures = extractAudioFeatures(audioData)
            
            // 2. 调用常驻模型进行推理
            val model = modelManager.getModel(ResidentModelManager.MODEL_TYPE_WAKE_WORD)
            if (model == null || model.state != ResidentModelManager.STATE_RUNNING) {
                return
            }
            
            val output = model.inference(inputFeatures)
            
            // 3. 解析模型输出（多类别：hi_brain, show_me, take_note, background）
            val detectionResult = parseModelOutput(output)
            
            // 4. 如果检测到唤醒词，触发回调
            if (detectionResult != null) {
                handleWakeWordDetected(detectionResult)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in wake word detection", e)
        }
    }
    
    /**
     * 提取音频特征
     */
    private fun extractAudioFeatures(audioData: ByteArray): FloatArray {
        // TODO: 实际实现：
        // 1. 预加重（Pre-emphasis）
        // 2. 分帧和加窗（Framing and Windowing）
        // 3. FFT 频谱分析
        // 4. Mel 滤波器组
        // 5. 对数能量
        // 6. DCT（MFCC）或直接使用 Mel-spectrogram
        
        // 当前简化实现：返回模拟特征向量（40维MFCC * 100帧）
        return FloatArray(40 * 100) { Math.random().toFloat() }
    }
    
    /**
     * 解析模型输出
     */
    private fun parseModelOutput(output: FloatArray): WakeWordDetection? {
        // TODO: 实际实现：
        // 模型输出格式：[prob_hi_brain, prob_show_me, prob_take_note, prob_background]
        
        // 当前简化实现：模拟检测（极低概率）
        if (Math.random() < 0.001) {  // 0.1% 概率触发（仅用于测试）
            return WakeWordDetection(
                wakeWord = WAKE_WORD_PRIMARY,
                confidence = 0.92f,
                timestamp = System.currentTimeMillis()
            )
        }
        
        return null
    }
    
    /**
     * 处理检测到的唤醒词
     */
    private fun handleWakeWordDetected(detection: WakeWordDetection) {
        // 1. 防抖：检查最小激活间隔
        val now = System.currentTimeMillis()
        if (now - lastActivationTime < MIN_ACTIVATION_INTERVAL_MS) {
            Log.d(TAG, "Wake word ignored (debounce): word=${detection.wakeWord}")
            return
        }
        
        // 2. 检查置信度阈值
        if (detection.confidence < CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Wake word confidence too low: ${detection.confidence}")
            return
        }
        
        Log.i(TAG, "Wake word detected: word=${detection.wakeWord}, confidence=${detection.confidence}")
        
        // 3. 更新统计
        totalDetections++
        lastActivationTime = now
        
        // 4. 触发回调
        callbackLock.withLock {
            callbacks.forEach { callback ->
                try {
                    callback.onWakeWordDetected(detection)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in wake word callback", e)
                }
            }
        }
    }
    
    /**
     * 注册唤醒词检测回调
     */
    fun registerCallback(callback: WakeWordCallback) {
        callbackLock.withLock {
            if (!callbacks.contains(callback)) {
                callbacks.add(callback)
                Log.d(TAG, "Wake word callback registered")
            }
        }
    }
    
    /**
     * 注销唤醒词检测回调
     */
    fun unregisterCallback(callback: WakeWordCallback) {
        callbackLock.withLock {
            callbacks.remove(callback)
            Log.d(TAG, "Wake word callback unregistered")
        }
    }
    
    /**
     * 报告误检（用于统计和学习）
     */
    fun reportFalsePositive(detectionTimestamp: Long) {
        falsePositives++
        Log.i(TAG, "False positive reported: timestamp=$detectionTimestamp")
        
        // TODO: 记录到审计日志，用于模型改进
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): WakeWordStatistics {
        return WakeWordStatistics(
            isRunning = isRunning.get(),
            isPaused = isPaused.get(),
            totalDetections = totalDetections,
            falsePositives = falsePositives,
            falsePositiveRate = if (totalDetections > 0) falsePositives.toFloat() / totalDetections else 0f
        )
    }
}

/**
 * 唤醒词检测结果
 */
data class WakeWordDetection(
    val wakeWord: String,  // 唤醒词类型
    val confidence: Float,  // 置信度 0-1
    val timestamp: Long  // 检测时间戳
)

/**
 * 唤醒词检测回调接口
 */
interface WakeWordCallback {
    /**
     * 检测到唤醒词时触发
     */
    fun onWakeWordDetected(detection: WakeWordDetection)
}

/**
 * 唤醒词检测统计信息
 */
data class WakeWordStatistics(
    val isRunning: Boolean,
    val isPaused: Boolean,
    val totalDetections: Long,
    val falsePositives: Long,
    val falsePositiveRate: Float
)

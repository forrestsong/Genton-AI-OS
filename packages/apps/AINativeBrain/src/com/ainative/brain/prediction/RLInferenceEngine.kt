package com.ainative.brain.prediction

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * RL 推理引擎
 * 
 * 超轻量 RL 策略网络推理引擎（15-20M 参数）
 * 模型架构：Embedding → LSTM/GRU → Self-Attention → MLP → Softmax
 * 
 * 输入：128 维状态特征向量
 * 输出：动作概率分布（100 维）+ 状态价值（1 维）
 * 
 * @author AI Native OS Team
 * @since A5
 */
class RLInferenceEngine(
    context: Context,
    modelPath: String
) {
    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val outputProbsBuffer: ByteBuffer
    private val outputValueBuffer: ByteBuffer
    
    companion object {
        const val INPUT_DIM = 128
        const val OUTPUT_ACTION_DIM = 100
        const val OUTPUT_VALUE_DIM = 1
        
        // TFLite 配置
        const val NUM_THREADS = 2
        const val USE_NNAPI = true  // 使用 NNAPI 加速
    }
    
    init {
        // 加载 TFLite 模型
        val modelFile = loadModelFile(context, modelPath)
        
        // 配置解释器
        val options = Interpreter.Options().apply {
            setNumThreads(NUM_THREADS)
            if (USE_NNAPI) {
                setUseNNAPI(true)  // 启用 NPU 加速
            }
        }
        
        interpreter = Interpreter(modelFile, options)
        
        // 预分配输入输出缓冲区
        inputBuffer = ByteBuffer.allocateDirect(INPUT_DIM * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        
        outputProbsBuffer = ByteBuffer.allocateDirect(OUTPUT_ACTION_DIM * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        
        outputValueBuffer = ByteBuffer.allocateDirect(OUTPUT_VALUE_DIM * 4).apply {
            order(ByteOrder.nativeOrder())
        }
    }
    
    /**
     * 预测下一步动作
     * 
     * @param stateVector 128 维状态特征向量
     * @return 预测结果（动作索引 + 置信度 + 状态价值）
     */
    fun predict(stateVector: FloatArray): PredictionResult {
        require(stateVector.size == INPUT_DIM) {
            "State vector must be $INPUT_DIM dimensions, got ${stateVector.size}"
        }
        
        val startTime = System.currentTimeMillis()
        
        // 1. 准备输入
        inputBuffer.rewind()
        stateVector.forEach { inputBuffer.putFloat(it) }
        
        // 2. 运行推理
        val outputs = hashMapOf<Int, Any>(
            0 to outputProbsBuffer,  // 动作概率
            1 to outputValueBuffer   // 状态价值
        )
        
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        
        // 3. 解析输出
        val actionProbs = FloatArray(OUTPUT_ACTION_DIM)
        outputProbsBuffer.rewind()
        outputProbsBuffer.asFloatBuffer().get(actionProbs)
        
        val stateValue = outputValueBuffer.rewind().float
        
        // 4. 选择最优动作（贪婪策略）
        val (bestActionIndex, confidence) = selectAction(actionProbs)
        
        val latency = System.currentTimeMillis() - startTime
        
        return PredictionResult(
            actionIndex = bestActionIndex,
            confidence = confidence,
            stateValue = stateValue,
            actionProbs = actionProbs,
            latencyMs = latency
        )
    }
    
    /**
     * 批量预测（用于训练）
     */
    fun predictBatch(stateVectors: List<FloatArray>): List<PredictionResult> {
        return stateVectors.map { predict(it) }
    }
    
    /**
     * 动作选择策略
     * 
     * @param actionProbs 动作概率分布
     * @return Pair(动作索引, 置信度)
     */
    private fun selectAction(actionProbs: FloatArray): Pair<Int, Float> {
        // 贪婪策略：选择概率最高的动作
        var maxProb = Float.MIN_VALUE
        var bestAction = 0
        
        for (i in actionProbs.indices) {
            if (actionProbs[i] > maxProb) {
                maxProb = actionProbs[i]
                bestAction = i
            }
        }
        
        return Pair(bestAction, maxProb)
    }
    
    /**
     * ε-贪婪策略（用于训练探索）
     */
    fun selectActionEpsilonGreedy(
        actionProbs: FloatArray,
        epsilon: Float = 0.1f
    ): Pair<Int, Float> {
        if (Math.random() < epsilon) {
            // 探索：随机选择动作
            val randomAction = (Math.random() * actionProbs.size).toInt()
            return Pair(randomAction, actionProbs[randomAction])
        } else {
            // 利用：选择最优动作
            return selectAction(actionProbs)
        }
    }
    
    /**
     * 加载模型文件
     */
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * 释放资源
     */
    fun close() {
        interpreter.close()
    }
}

/**
 * 预测结果
 */
data class PredictionResult(
    val actionIndex: Int,           // 预测的动作索引 [0, 99]
    val confidence: Float,          // 置信度 [0, 1]
    val stateValue: Float,          // 状态价值估计（V(s)）
    val actionProbs: FloatArray,    // 完整的动作概率分布
    val latencyMs: Long             // 推理延迟（毫秒）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PredictionResult

        if (actionIndex != other.actionIndex) return false
        if (confidence != other.confidence) return false
        if (stateValue != other.stateValue) return false
        if (!actionProbs.contentEquals(other.actionProbs)) return false
        if (latencyMs != other.latencyMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = actionIndex
        result = 31 * result + confidence.hashCode()
        result = 31 * result + stateValue.hashCode()
        result = 31 * result + actionProbs.contentHashCode()
        result = 31 * result + latencyMs.hashCode()
        return result
    }
}

/**
 * 占位 RL 推理引擎（简化版，用于开发测试）
 * 
 * 使用基于规则的启发式策略替代真实 RL 模型：
 * - 基于时间/位置/前台 App 的简单规则
 * - 返回模拟的置信度
 * 
 * TODO: 替换为真实训练的 RL 模型
 */
class PlaceholderRLEngine(
    private val context: Context,
    private val actionSpaceManager: ActionSpaceManager
) {
    /**
     * 基于规则的预测（占位实现）
     */
    fun predict(stateVector: FloatArray): PredictionResult {
        val startTime = System.currentTimeMillis()
        
        // 简单规则：根据时间和前台 App 预测
        val (actionIndex, confidence) = predictByRules(stateVector)
        
        // 生成模拟的动作概率分布
        val actionProbs = FloatArray(ActionSpaceManager.ACTION_SPACE_SIZE) { 0.01f }
        actionProbs[actionIndex] = confidence
        
        // 归一化
        val sum = actionProbs.sum()
        for (i in actionProbs.indices) {
            actionProbs[i] /= sum
        }
        
        val latency = System.currentTimeMillis() - startTime
        
        return PredictionResult(
            actionIndex = actionIndex,
            confidence = confidence,
            stateValue = confidence * 10.0f,  // 模拟状态价值
            actionProbs = actionProbs,
            latencyMs = latency
        )
    }
    
    /**
     * 基于规则的预测逻辑
     */
    private fun predictByRules(stateVector: FloatArray): Pair<Int, Float> {
        // 提取特征（简化版）
        val hourOfDay = (stateVector[7] * 24).toInt()  // 归一化小时
        val isWeekend = stateVector[8] > 0.5f
        
        // 规则 1: 早上 7-9 点，高概率打开日历
        if (hourOfDay in 7..9 && !isWeekend) {
            val calendarAction = findActionForApp("com.android.calendar")
            if (calendarAction >= 0) {
                return Pair(calendarAction, 0.85f)
            }
        }
        
        // 规则 2: 晚上 20-22 点，高概率打开社交 App
        if (hourOfDay in 20..22) {
            val wechatAction = findActionForApp("com.tencent.mm")
            if (wechatAction >= 0) {
                return Pair(wechatAction, 0.75f)
            }
        }
        
        // 规则 3: 工作日白天，高概率打开办公 App
        if (hourOfDay in 9..17 && !isWeekend) {
            val dingTalkAction = findActionForApp("com.alibaba.android.rimet")
            if (dingTalkAction >= 0) {
                return Pair(dingTalkAction, 0.70f)
            }
        }
        
        // 默认：NO_ACTION
        return Pair(0, 0.5f)
    }
    
    /**
     * 查找 App 的预热动作索引
     */
    private fun findActionForApp(packageName: String): Int {
        if (!actionSpaceManager.isWhitelisted(packageName)) {
            return -1
        }
        
        // PREWARM_APP 动作索引范围: 1-20
        val whitelistApps = actionSpaceManager.whitelistApps.toList()
        val appIndex = whitelistApps.indexOf(packageName)
        if (appIndex < 0 || appIndex >= 20) {
            return -1
        }
        
        return 1 + appIndex  // PREWARM_APP 起始索引为 1
    }
}

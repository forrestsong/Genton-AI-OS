package com.genton.brain.prediction

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * 本地增量训练器
 * 
 * 实现 PPO (Proximal Policy Optimization) 算法的本地增量训练：
 * 1. 收集经验数据（状态-动作-奖励三元组）
 * 2. 周期性训练（每日凌晨或积累 N 条经验）
 * 3. 数据不出设备
 * 4. 更新策略网络权重
 * 
 * 注意：当前为架构占位实现，真实训练需要集成 TensorFlow Lite 训练 API
 * 
 * @author AI Native OS Team
 * @since A5
 */
class LocalTrainer(
    private val context: Context,
    private val modelPath: String
) {
    private val experienceBuffer = mutableListOf<Experience>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    companion object {
        const val BATCH_SIZE = 32
        const val BUFFER_SIZE = 1000
        const val TRAINING_INTERVAL_MS = 86400_000L  // 24 小时
        const val MIN_EXPERIENCES_FOR_TRAINING = 100
        
        // PPO 超参数
        const val LEARNING_RATE = 3e-4f
        const val CLIP_RANGE = 0.2f
        const val ENTROPY_COEF = 0.01f
        const val VALUE_COEF = 0.5f
        const val DISCOUNT_FACTOR = 0.99f
        const val GAE_LAMBDA = 0.95f
    }
    
    /**
     * 添加经验数据
     */
    fun addExperience(experience: Experience) {
        synchronized(experienceBuffer) {
            experienceBuffer.add(experience)
            
            // 限制缓冲区大小
            if (experienceBuffer.size > BUFFER_SIZE) {
                experienceBuffer.removeAt(0)
            }
            
            // 达到训练阈值，触发训练
            if (experienceBuffer.size >= MIN_EXPERIENCES_FOR_TRAINING) {
                scheduleTraining()
            }
        }
    }
    
    /**
     * 调度训练任务
     */
    private fun scheduleTraining() {
        scope.launch {
            try {
                train()
            } catch (e: Exception) {
                android.util.Log.e("LocalTrainer", "Training failed", e)
            }
        }
    }
    
    /**
     * 执行训练
     */
    private suspend fun train() = withContext(Dispatchers.Default) {
        synchronized(experienceBuffer) {
            if (experienceBuffer.size < MIN_EXPERIENCES_FOR_TRAINING) {
                return@withContext
            }
            
            val experiences = experienceBuffer.toList()
            experienceBuffer.clear()
            
            android.util.Log.i("LocalTrainer", "Starting training with ${experiences.size} experiences")
            
            // 1. 计算优势函数（Advantage）
            val advantages = computeAdvantages(experiences)
            
            // 2. 准备训练批次
            val batches = prepareBatches(experiences, advantages)
            
            // 3. PPO 训练迭代
            val numEpochs = 4
            repeat(numEpochs) { epoch ->
                batches.shuffled().forEach { batch ->
                    trainBatch(batch)
                }
            }
            
            // 4. 保存更新的模型
            saveModel()
            
            android.util.Log.i("LocalTrainer", "Training completed")
        }
    }
    
    /**
     * 计算优势函数（GAE）
     */
    private fun computeAdvantages(experiences: List<Experience>): FloatArray {
        val advantages = FloatArray(experiences.size)
        var gae = 0.0f
        
        // 反向计算 GAE
        for (i in experiences.size - 1 downTo 0) {
            val exp = experiences[i]
            val nextValue = if (i < experiences.size - 1) {
                experiences[i + 1].stateValue
            } else {
                0.0f
            }
            
            // TD 误差
            val delta = exp.reward + DISCOUNT_FACTOR * nextValue - exp.stateValue
            
            // GAE
            gae = delta + DISCOUNT_FACTOR * GAE_LAMBDA * gae
            advantages[i] = gae
        }
        
        // 归一化优势
        val mean = advantages.average().toFloat()
        val std = advantages.map { (it - mean) * (it - mean) }.average().toFloat().let { 
            kotlin.math.sqrt(it) 
        }
        
        for (i in advantages.indices) {
            advantages[i] = (advantages[i] - mean) / (std + 1e-8f)
        }
        
        return advantages
    }
    
    /**
     * 准备训练批次
     */
    private fun prepareBatches(
        experiences: List<Experience>,
        advantages: FloatArray
    ): List<TrainingBatch> {
        val batches = mutableListOf<TrainingBatch>()
        
        for (i in experiences.indices step BATCH_SIZE) {
            val endIdx = minOf(i + BATCH_SIZE, experiences.size)
            val batchExperiences = experiences.subList(i, endIdx)
            val batchAdvantages = advantages.sliceArray(i until endIdx)
            
            batches.add(
                TrainingBatch(
                    experiences = batchExperiences,
                    advantages = batchAdvantages
                )
            )
        }
        
        return batches
    }
    
    /**
     * 训练单个批次（占位实现）
     */
    private fun trainBatch(batch: TrainingBatch) {
        // TODO: 实现真实的 PPO 训练逻辑
        // 当前 TFLite 不直接支持训练，需要：
        // 1. 使用 TensorFlow Lite Model Maker 或
        // 2. 在本地运行完整 TensorFlow 训练后转 TFLite 或
        // 3. 使用联邦学习框架（梯度上传到云端聚合）
        
        // 占位：模拟训练过程
        val states = batch.experiences.map { it.state }.toTypedArray()
        val actions = batch.experiences.map { it.action }.toIntArray()
        val advantages = batch.advantages
        val oldProbs = batch.experiences.map { it.actionProb }.toFloatArray()
        
        // 计算损失（占位）
        val policyLoss = computePolicyLoss(states, actions, advantages, oldProbs)
        val valueLoss = computeValueLoss(states, batch.experiences.map { it.reward }.toFloatArray())
        val entropyLoss = computeEntropyLoss(states)
        
        val totalLoss = policyLoss - ENTROPY_COEF * entropyLoss + VALUE_COEF * valueLoss
        
        android.util.Log.d("LocalTrainer", "Batch loss: policy=$policyLoss, value=$valueLoss, entropy=$entropyLoss")
    }
    
    /**
     * 计算策略损失（PPO Clip）
     */
    private fun computePolicyLoss(
        states: Array<FloatArray>,
        actions: IntArray,
        advantages: FloatArray,
        oldProbs: FloatArray
    ): Float {
        // 占位实现：简化的损失计算
        var loss = 0.0f
        
        for (i in states.indices) {
            // 新策略概率（需要前向传播，这里用占位值）
            val newProb = oldProbs[i] * (1.0f + (Math.random().toFloat() - 0.5f) * 0.1f)
            
            // 概率比率
            val ratio = newProb / (oldProbs[i] + 1e-8f)
            
            // PPO Clip 目标
            val clippedRatio = ratio.coerceIn(1.0f - CLIP_RANGE, 1.0f + CLIP_RANGE)
            val obj = minOf(ratio * advantages[i], clippedRatio * advantages[i])
            
            loss -= obj
        }
        
        return loss / states.size
    }
    
    /**
     * 计算价值损失（MSE）
     */
    private fun computeValueLoss(states: Array<FloatArray>, returns: FloatArray): Float {
        // 占位实现
        var loss = 0.0f
        
        for (i in states.indices) {
            val predictedValue = returns[i] * 0.9f  // 占位：模拟预测值
            val error = returns[i] - predictedValue
            loss += error * error
        }
        
        return loss / states.size
    }
    
    /**
     * 计算熵损失（鼓励探索）
     */
    private fun computeEntropyLoss(states: Array<FloatArray>): Float {
        // 占位实现
        var entropy = 0.0f
        
        for (state in states) {
            // 模拟动作概率分布
            val probs = FloatArray(100) { (Math.random() * 0.01).toFloat() }
            probs[0] = 1.0f - probs.sum()
            
            // 熵 = -Σ p*log(p)
            for (p in probs) {
                if (p > 1e-8f) {
                    entropy -= p * kotlin.math.ln(p)
                }
            }
        }
        
        return entropy / states.size
    }
    
    /**
     * 保存模型（占位）
     */
    private fun saveModel() {
        // TODO: 保存更新后的模型权重
        val modelFile = File(context.filesDir, modelPath)
        android.util.Log.i("LocalTrainer", "Model saved to: ${modelFile.absolutePath}")
    }
    
    /**
     * 清理资源
     */
    fun shutdown() {
        scope.cancel()
    }
}

/**
 * 经验数据
 */
data class Experience(
    val state: FloatArray,          // 状态特征向量
    val action: Int,                // 执行的动作索引
    val actionProb: Float,          // 动作概率（旧策略）
    val reward: Float,              // 奖励
    val nextState: FloatArray,      // 下一状态
    val stateValue: Float,          // 状态价值估计
    val done: Boolean = false,      // 是否为终止状态
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Experience

        if (!state.contentEquals(other.state)) return false
        if (action != other.action) return false
        if (actionProb != other.actionProb) return false
        if (reward != other.reward) return false
        if (!nextState.contentEquals(other.nextState)) return false
        if (stateValue != other.stateValue) return false
        if (done != other.done) return false

        return true
    }

    override fun hashCode(): Int {
        var result = state.contentHashCode()
        result = 31 * result + action
        result = 31 * result + actionProb.hashCode()
        result = 31 * result + reward.hashCode()
        result = 31 * result + nextState.contentHashCode()
        result = 31 * result + stateValue.hashCode()
        result = 31 * result + done.hashCode()
        return result
    }
}

/**
 * 训练批次
 */
data class TrainingBatch(
    val experiences: List<Experience>,
    val advantages: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrainingBatch

        if (experiences != other.experiences) return false
        if (!advantages.contentEquals(other.advantages)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = experiences.hashCode()
        result = 31 * result + advantages.contentHashCode()
        return result
    }
}

/**
 * 奖励计算器
 * 
 * 根据预测动作和实际动作计算奖励信号
 */
class RewardCalculator {
    companion object {
        const val EXACT_MATCH_REWARD = 10.0f      // 完全匹配
        const val APP_MATCH_REWARD = 5.0f         // 仅 App 匹配
        const val WASTE_PENALTY = -1.0f           // 未命中浪费
        const val OOM_PENALTY = -2.0f             // 导致内存压力
    }
    
    /**
     * 计算奖励
     */
    fun calculateReward(
        predicted: PredictedAction,
        actual: ActualAction?,
        resourceStatus: ResourceStatus,
        timeDeltaMs: Long
    ): Float {
        var reward = 0.0f
        
        if (actual != null && actual.matches(predicted)) {
            // 命中：正奖励
            reward = if (predicted.targetActivity != null && 
                        predicted.targetActivity == actual.targetActivity) {
                EXACT_MATCH_REWARD  // Activity 也匹配
            } else {
                APP_MATCH_REWARD    // 仅 App 匹配
            }
            
            // 时间奖励：预测越早奖励越高
            val timeBonus = maxOf(0.0f, 1.0f - timeDeltaMs / 60000.0f)
            reward *= (1.0f + timeBonus)
            
        } else if (resourceStatus == ResourceStatus.EXPIRED) {
            // 未命中：负奖励（浪费资源）
            reward = when (predicted.actionType) {
                PrefetchActionType.PREWARM_APP,
                PrefetchActionType.PRECREATE_ACTIVITY -> WASTE_PENALTY
                else -> WASTE_PENALTY * 0.5f  // 预取数据成本较低
            }
        }
        
        return reward
    }
}

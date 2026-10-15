package com.ainative.privacy

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PII 掩码模型推理引擎
 * 
 * 职责：
 * 1. 加载 TFLite 模型并执行推理
 * 2. 检测文本中的 PII 实体（NER 任务）
 * 3. 失败时降级到 PiiMaskRules
 * 
 * 性能目标：
 * - 推理延迟 P95 ≤ 50ms
 * - 内存占用 ≤ 30MB
 * - 模型大小 ≤ 10MB
 * 
 * 使用示例：
 * ```kotlin
 * val inference = PiiMaskModelInference(context)
 * val result = inference.detectAndMask("我的银行卡号是 6222001234567890")
 * // result.maskedText = "我的银行卡号是 6222 **** **** 7890"
 * // result.modelUsed = true
 * ```
 * 
 * @since A3
 */
class PiiMaskModelInference(private val context: Context) {
    
    companion object {
        private const val TAG = "PiiMaskModelInference"
        private const val MODEL_FILE = "pii_mask_model_quantized.tflite"
        private const val MAX_SEQ_LENGTH = 128
        private const val CONFIDENCE_THRESHOLD = 0.6f
        
        // BIO 标签映射（与训练脚本对应）
        private val ID_TO_LABEL = mapOf(
            0 to "O",
            1 to "B-FIN-CARD", 2 to "I-FIN-CARD",
            3 to "B-FIN-CVV", 4 to "I-FIN-CVV",
            5 to "B-FIN-AMOUNT", 6 to "I-FIN-AMOUNT",
            7 to "B-HEALTH-ID", 8 to "I-HEALTH-ID",
            9 to "B-HEALTH-DIAG", 10 to "I-HEALTH-DIAG",
            11 to "B-ACCOUNT-PHONE", 12 to "I-ACCOUNT-PHONE",
            13 to "B-ACCOUNT-EMAIL", 14 to "I-ACCOUNT-EMAIL",
            15 to "B-ACCOUNT-KEY", 16 to "I-ACCOUNT-KEY",
            17 to "B-ACCOUNT-PASS", 18 to "I-ACCOUNT-PASS"
        )
        
        // 标签到 PII 类别的映射
        private val LABEL_TO_CATEGORY = mapOf(
            "FIN-CARD" to PiiMaskRules.PiiCategory.BANK_CARD,
            "FIN-CVV" to PiiMaskRules.PiiCategory.BANK_CARD,
            "FIN-AMOUNT" to PiiMaskRules.PiiCategory.FINANCIAL,
            "HEALTH-ID" to PiiMaskRules.PiiCategory.ID_CARD,
            "HEALTH-DIAG" to PiiMaskRules.PiiCategory.HEALTH,
            "ACCOUNT-PHONE" to PiiMaskRules.PiiCategory.PHONE,
            "ACCOUNT-EMAIL" to PiiMaskRules.PiiCategory.EMAIL,
            "ACCOUNT-KEY" to PiiMaskRules.PiiCategory.API_KEY,
            "ACCOUNT-PASS" to PiiMaskRules.PiiCategory.PASSWORD
        )
    }
    
    private var interpreter: Interpreter? = null
    private var modelLoaded = false
    
    init {
        loadModel()
    }
    
    /**
     * 加载 TFLite 模型
     */
    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)  // 2 线程推理
                setUseNNAPI(false)  // 禁用 NNAPI（兼容性）
            }
            
            interpreter = Interpreter(modelBuffer, options)
            modelLoaded = true
            
            Log.i(TAG, "✅ PII 掩码模型已加载：$MODEL_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 模型加载失败，将使用规则库 fallback", e)
            modelLoaded = false
        }
    }
    
    /**
     * 检测并掩码文本中的 PII
     * 
     * @param text 原始文本
     * @param useModel 是否优先使用模型（false 则直接用规则库）
     * @return 掩码结果
     */
    fun detectAndMask(text: String, useModel: Boolean = true): PiiMaskRules.MaskResult {
        if (text.isEmpty()) {
            return PiiMaskRules.MaskResult(text, emptyList(), false)
        }
        
        // 尝试模型推理
        if (useModel && modelLoaded) {
            try {
                val startTime = System.currentTimeMillis()
                val modelResult = runModelInference(text)
                val inferenceTime = System.currentTimeMillis() - startTime
                
                // 检查置信度
                if (modelResult.confidence >= CONFIDENCE_THRESHOLD) {
                    Log.d(TAG, "✅ 模型推理成功（${inferenceTime}ms，置信度：${modelResult.confidence}）")
                    return convertToMaskResult(text, modelResult.entities, true)
                } else {
                    Log.w(TAG, "⚠️  模型置信度低（${modelResult.confidence}），降级到规则库")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 模型推理失败，降级到规则库", e)
            }
        }
        
        // Fallback: 使用规则库
        Log.d(TAG, "使用规则库 fallback")
        return PiiMaskRules.maskText(text, aggressiveMode = true)
    }
    
    /**
     * 执行模型推理
     */
    private fun runModelInference(text: String): InferenceResult {
        val interpreter = this.interpreter ?: throw IllegalStateException("Model not loaded")
        
        // 1. Tokenize（简化：字符级）
        val tokens = text.toCharArray().take(MAX_SEQ_LENGTH)
        val inputIds = IntArray(MAX_SEQ_LENGTH) { if (it < tokens.size) tokens[it].code else 0 }
        val attentionMask = IntArray(MAX_SEQ_LENGTH) { if (it < tokens.size) 1 else 0 }
        
        // 2. 转换为 ByteBuffer（INT32）
        val inputBuffer = ByteBuffer.allocateDirect(MAX_SEQ_LENGTH * 4).apply {
            order(ByteOrder.nativeOrder())
            inputIds.forEach { putInt(it) }
            rewind()
        }
        
        // 3. 推理
        val outputBuffer = ByteBuffer.allocateDirect(MAX_SEQ_LENGTH * ID_TO_LABEL.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        
        interpreter.run(inputBuffer, outputBuffer)
        
        // 4. 解析输出（logits）
        outputBuffer.rewind()
        val logits = Array(MAX_SEQ_LENGTH) { FloatArray(ID_TO_LABEL.size) }
        
        for (i in 0 until MAX_SEQ_LENGTH) {
            for (j in 0 until ID_TO_LABEL.size) {
                logits[i][j] = outputBuffer.float
            }
        }
        
        // 5. 提取实体
        val entities = extractEntities(tokens.joinToString(""), logits, tokens.size)
        
        // 6. 计算平均置信度
        val avgConfidence = if (entities.isNotEmpty()) {
            entities.map { it.confidence }.average().toFloat()
        } else {
            1.0f  // 未检测到 PII，高置信度
        }
        
        return InferenceResult(entities, avgConfidence)
    }
    
    /**
     * 从 logits 中提取 PII 实体
     */
    private fun extractEntities(
        text: String,
        logits: Array<FloatArray>,
        validLength: Int
    ): List<DetectedEntity> {
        val entities = mutableListOf<DetectedEntity>()
        
        var currentEntity: DetectedEntity? = null
        
        for (i in 0 until validLength) {
            // 获取最大概率的标签
            val labelId = logits[i].indices.maxByOrNull { logits[i][it] } ?: 0
            val label = ID_TO_LABEL[labelId] ?: "O"
            val confidence = softmax(logits[i])[labelId]
            
            when {
                label == "O" -> {
                    // 结束当前实体
                    currentEntity?.let { entities.add(it) }
                    currentEntity = null
                }
                
                label.startsWith("B-") -> {
                    // 开始新实体
                    currentEntity?.let { entities.add(it) }
                    
                    val piiType = label.substring(2)
                    currentEntity = DetectedEntity(
                        type = piiType,
                        start = i,
                        end = i + 1,
                        confidence = confidence
                    )
                }
                
                label.startsWith("I-") -> {
                    // 继续当前实体
                    currentEntity?.let {
                        it.end = i + 1
                        it.confidence = (it.confidence + confidence) / 2  // 平均置信度
                    }
                }
            }
        }
        
        // 添加最后一个实体
        currentEntity?.let { entities.add(it) }
        
        // 提取实际文本
        entities.forEach { entity ->
            entity.text = text.substring(entity.start, entity.end.coerceAtMost(text.length))
        }
        
        return entities
    }
    
    /**
     * Softmax 激活函数
     */
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val expValues = logits.map { kotlin.math.exp((it - maxLogit).toDouble()).toFloat() }
        val sumExp = expValues.sum()
        return expValues.map { it / sumExp }.toFloatArray()
    }
    
    /**
     * 转换为 MaskResult
     */
    private fun convertToMaskResult(
        text: String,
        entities: List<DetectedEntity>,
        modelUsed: Boolean
    ): PiiMaskRules.MaskResult {
        val detectedPii = entities.map { entity ->
            val category = LABEL_TO_CATEGORY[entity.type] ?: PiiMaskRules.PiiCategory.UNKNOWN
            val maskLevel = when (category) {
                PiiMaskRules.PiiCategory.PASSWORD,
                PiiMaskRules.PiiCategory.API_KEY -> PiiMaskRules.MaskLevel.STRONG
                PiiMaskRules.PiiCategory.BANK_CARD,
                PiiMaskRules.PiiCategory.ID_CARD,
                PiiMaskRules.PiiCategory.PHONE,
                PiiMaskRules.PiiCategory.EMAIL -> PiiMaskRules.MaskLevel.MEDIUM
                else -> PiiMaskRules.MaskLevel.WEAK
            }
            
            PiiMaskRules.DetectedPii(
                category = category,
                originalText = entity.text,
                maskedText = maskText(entity.text, category),
                start = entity.start,
                end = entity.end,
                confidence = entity.confidence,
                maskLevel = maskLevel
            )
        }
        
        // 应用掩码
        val maskedText = applyMasks(text, detectedPii)
        
        return PiiMaskRules.MaskResult(
            maskedText = maskedText,
            detectedPii = detectedPii,
            maskApplied = detectedPii.isNotEmpty()
        )
    }
    
    /**
     * 掩码单个文本
     */
    private fun maskText(text: String, category: PiiMaskRules.PiiCategory): String {
        return when (category) {
            PiiMaskRules.PiiCategory.BANK_CARD -> {
                val cleaned = text.replace(Regex("[\\s-]"), "")
                if (cleaned.length >= 8) {
                    "${cleaned.substring(0, 4)} **** **** ${cleaned.substring(cleaned.length - 4)}"
                } else "****"
            }
            PiiMaskRules.PiiCategory.PHONE -> {
                if (text.length == 11) {
                    "${text.substring(0, 3)}****${text.substring(7)}"
                } else "***"
            }
            PiiMaskRules.PiiCategory.EMAIL -> {
                val parts = text.split("@")
                if (parts.size == 2 && parts[0].isNotEmpty()) {
                    "${parts[0][0]}***@${parts[1]}"
                } else "***@***"
            }
            PiiMaskRules.PiiCategory.PASSWORD,
            PiiMaskRules.PiiCategory.API_KEY -> "***"
            else -> "***"
        }
    }
    
    /**
     * 应用掩码到原文本
     */
    private fun applyMasks(text: String, detectedPii: List<PiiMaskRules.DetectedPii>): String {
        if (detectedPii.isEmpty()) return text
        
        val result = StringBuilder()
        var lastEnd = 0
        
        for (pii in detectedPii.sortedBy { it.start }) {
            if (pii.start < lastEnd) continue
            
            result.append(text.substring(lastEnd, pii.start))
            result.append(pii.maskedText)
            
            lastEnd = pii.end
        }
        
        if (lastEnd < text.length) {
            result.append(text.substring(lastEnd))
        }
        
        return result.toString()
    }
    
    /**
     * 释放资源
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        modelLoaded = false
        Log.i(TAG, "PII 掩码模型已释放")
    }
    
    // ==================== 内部数据类 ====================
    
    /**
     * 检测到的实体
     */
    private data class DetectedEntity(
        val type: String,
        var text: String = "",
        val start: Int,
        var end: Int,
        var confidence: Float
    )
    
    /**
     * 推理结果
     */
    private data class InferenceResult(
        val entities: List<DetectedEntity>,
        val confidence: Float
    )
}

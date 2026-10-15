package com.genton.brain.resident

import android.content.pm.ApplicationInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * C4.2 通知分类器 - Notification Classifier
 * 
 * 职责：
 * 1. 对通知内容进行实时分类（category + classifierConfidence）
 * 2. 支持 16 个 category 分类（见 C1.2 值域知识库）
 * 3. 常驻 NPU 小核，快速推理（目标延迟 <30ms）
 * 4. 输出置信度用于策略引擎裁决（低置信度回退到原生）
 * 
 * 模型架构：
 * - 基础模型：DistilBERT-tiny（18M 参数，复用 C4.1 架构）
 * - 输入：title + text（截断到 256 tokens）
 * - 输出：16 维 softmax（category 概率分布）+ confidence
 * - 模型大小：~4.5MB（INT8 量化）
 * - 推理延迟：<30ms（NPU 小核）
 * 
 * 分类策略：
 * - 高置信度（≥0.85）：直接使用分类结果
 * - 中置信度（0.60-0.85）：使用分类 + 启发式规则增强
 * - 低置信度（<0.60）：仅使用启发式规则，不信任模型输出
 * 
 * 符合 WBS：
 * - C4.2 通知分类器（广告/营销/OTP 等 category + classifierConfidence）
 * - A7.3 策略引擎路由轴（notification 域消费 category）
 * - D6.1 通知栈呈现（P0/P1/P2 路由依赖分类结果）
 */
class NotificationClassifier(private val modelManager: ResidentModelManager) {
    
    companion object {
        private const val TAG = "NotificationClassifier"
        
        // 推理配置
        private const val MAX_INPUT_LENGTH = 256  // 最大输入 token 数
        private const val INFERENCE_TIMEOUT_MS = 50L  // 推理超时 50ms
        private const val CONFIDENCE_THRESHOLD_HIGH = 0.85f
        private const val CONFIDENCE_THRESHOLD_MEDIUM = 0.60f
        
        // Category 常量（与 C1.2 值域知识库对齐）
        // Critical 类别（baseline 保护）
        const val CATEGORY_ALARM = "alarm"
        const val CATEGORY_SYSTEM_CRITICAL = "system_critical"
        const val CATEGORY_PHONE_RING_EQUIVALENT = "phone_ring_equivalent"
        
        // High 优先级类别
        const val CATEGORY_OTP = "otp"
        const val CATEGORY_PAYMENT_RESULT = "payment_result"
        const val CATEGORY_EMERGENCY = "emergency"
        
        // Medium 优先级类别
        const val CATEGORY_SOCIAL_UPDATES = "social_updates"
        const val CATEGORY_MESSAGE = "message"
        const val CATEGORY_SYSTEM_INFO = "system_info"
        const val CATEGORY_SERVICE_NOTICE = "service_notice"
        
        // Low 优先级类别（营销、广告）
        const val CATEGORY_NEWS = "news"
        const val CATEGORY_APP_UPDATES = "app_updates"
        const val CATEGORY_APP_UPDATE_NOTICE = "app_update_notice"
        const val CATEGORY_ADVERTISING = "advertising"
        const val CATEGORY_MARKETING = "marketing"
        const val CATEGORY_ACCOUNT_PROMO = "account_promo"
        
        // 未分类
        const val CATEGORY_UNKNOWN = "unknown"
        
        // 模型元数据
        private const val MODEL_NAME = "notification_classifier"
        private const val MODEL_VERSION = "1.0.0"
        
        // 启发式规则关键词（用于低置信度增强）
        private val OTP_KEYWORDS = setOf(
            "验证码", "code", "verification", "动态密码", "确认码",
            "校验码", "识别码", "captcha", "otp"
        )
        
        private val PAYMENT_KEYWORDS = setOf(
            "支付", "pay", "payment", "转账", "充值", "扣款", "退款",
            "订单", "order", "purchase", "交易", "transaction"
        )
        
        private val MARKETING_KEYWORDS = setOf(
            "优惠", "discount", "促销", "活动", "限时", "特惠",
            "降价", "sale", "offer", "deal", "营销", "推广"
        )
        
        private val ADVERTISING_KEYWORDS = setOf(
            "广告", "advertisement", "推荐", "recommend", "试用",
            "新品", "上新", "热卖", "爆款"
        )
        
        private val SOCIAL_KEYWORDS = setOf(
            "点赞", "like", "评论", "comment", "关注", "follow",
            "分享", "share", "@", "提到了你", "mentioned"
        )
    }
    
    // 分类结果数据类
    data class ClassificationResult(
        val category: String,
        val confidence: Float,
        val allScores: Map<String, Float>,  // 所有类别的得分
        val inferenceTimeMs: Long,
        val method: String  // "model", "heuristic", "hybrid"
    )
    
    // 通知上下文（用于分类增强）
    data class NotificationContext(
        val packageName: String,
        val title: String?,
        val text: String?,
        val subText: String?,
        val bigText: String?,
        val appInfo: ApplicationInfo?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // 性能统计
    private val totalInferences = AtomicLong(0)
    private val totalInferenceTimeMs = AtomicLong(0)
    private val categoryCounters = ConcurrentHashMap<String, AtomicLong>()
    
    /**
     * 对通知进行分类
     * 
     * @param context 通知上下文
     * @return 分类结果（category + confidence）
     */
    fun classify(context: NotificationContext): ClassificationResult {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 预处理输入
            val input = preprocessInput(context)
            if (input.isBlank()) {
                return createFallbackResult("empty_input", startTime)
            }
            
            // 2. 模型推理
            val modelResult = runModelInference(input)
            
            // 3. 置信度评估和增强策略
            val finalResult = when {
                modelResult.confidence >= CONFIDENCE_THRESHOLD_HIGH -> {
                    // 高置信度：直接使用模型结果
                    modelResult.copy(method = "model")
                }
                modelResult.confidence >= CONFIDENCE_THRESHOLD_MEDIUM -> {
                    // 中置信度：混合模型 + 启发式
                    enhanceWithHeuristics(modelResult, context)
                }
                else -> {
                    // 低置信度：仅使用启发式规则
                    applyHeuristicsOnly(context, startTime)
                }
            }
            
            // 4. 更新统计
            updateStats(finalResult)
            
            return finalResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            return createFallbackResult("error", startTime)
        }
    }
    
    /**
     * 预处理输入文本
     */
    private fun preprocessInput(context: NotificationContext): String {
        val parts = mutableListOf<String>()
        
        // 组合 title + text + bigText
        context.title?.let { if (it.isNotBlank()) parts.add(it) }
        context.text?.let { if (it.isNotBlank()) parts.add(it) }
        context.bigText?.let { if (it.isNotBlank()) parts.add(it) }
        
        val combined = parts.joinToString(" ")
        
        // 截断到最大长度（粗略按字符数估算，实际按 token）
        return if (combined.length > MAX_INPUT_LENGTH * 4) {
            combined.substring(0, MAX_INPUT_LENGTH * 4)
        } else {
            combined
        }
    }
    
    /**
     * 运行模型推理
     */
    private fun runModelInference(input: String): ClassificationResult {
        val startTime = System.currentTimeMillis()
        
        // TODO: 调用 ResidentModelManager 执行实际推理
        // 当前返回模拟结果，实际实现需要：
        // 1. 加载 TFLite/QNN 模型
        // 2. Tokenize 输入
        // 3. 执行推理
        // 4. 解析输出（16 维 softmax）
        
        // 模拟推理输出（实际应从模型获取）
        val scores = simulateModelOutput(input)
        val topCategory = scores.maxByOrNull { it.value }!!
        
        val inferenceTime = System.currentTimeMillis() - startTime
        
        return ClassificationResult(
            category = topCategory.key,
            confidence = topCategory.value,
            allScores = scores,
            inferenceTimeMs = inferenceTime,
            method = "model"
        )
    }
    
    /**
     * 模拟模型输出（实际应从 TFLite 推理获取）
     */
    private fun simulateModelOutput(input: String): Map<String, Float> {
        // 基于启发式规则模拟（实际应为神经网络输出）
        val scores = mutableMapOf<String, Float>()
        val inputLower = input.lowercase()
        
        // OTP 检测
        scores[CATEGORY_OTP] = if (OTP_KEYWORDS.any { inputLower.contains(it) }) 0.92f else 0.05f
        
        // 支付检测
        scores[CATEGORY_PAYMENT_RESULT] = if (PAYMENT_KEYWORDS.any { inputLower.contains(it) }) 0.88f else 0.04f
        
        // 营销检测
        scores[CATEGORY_MARKETING] = if (MARKETING_KEYWORDS.any { inputLower.contains(it) }) 0.85f else 0.06f
        
        // 广告检测
        scores[CATEGORY_ADVERTISING] = if (ADVERTISING_KEYWORDS.any { inputLower.contains(it) }) 0.83f else 0.07f
        
        // 社交检测
        scores[CATEGORY_SOCIAL_UPDATES] = if (SOCIAL_KEYWORDS.any { inputLower.contains(it) }) 0.80f else 0.08f
        
        // 其他类别默认低分
        scores[CATEGORY_MESSAGE] = 0.10f
        scores[CATEGORY_NEWS] = 0.09f
        scores[CATEGORY_SYSTEM_INFO] = 0.05f
        scores[CATEGORY_ALARM] = 0.03f
        scores[CATEGORY_UNKNOWN] = 0.15f
        
        // 归一化（softmax 近似）
        val sum = scores.values.sum()
        return scores.mapValues { it.value / sum }
    }
    
    /**
     * 使用启发式规则增强模型结果
     */
    private fun enhanceWithHeuristics(
        modelResult: ClassificationResult,
        context: NotificationContext
    ): ClassificationResult {
        val input = "${context.title} ${context.text}".lowercase()
        
        // 启发式规则增强置信度
        var boostedConfidence = modelResult.confidence
        var finalCategory = modelResult.category
        
        // OTP 强特征（数字码模式）
        if (modelResult.category == CATEGORY_OTP) {
            val hasDigitPattern = Regex("""\d{4,6}""").containsMatchIn(input)
            if (hasDigitPattern) {
                boostedConfidence = minOf(0.95f, boostedConfidence + 0.10f)
            }
        }
        
        // 支付强特征（金额模式）
        if (modelResult.category == CATEGORY_PAYMENT_RESULT) {
            val hasAmountPattern = Regex("""[¥$]\d+(\.\d{2})?""").containsMatchIn(input)
            if (hasAmountPattern) {
                boostedConfidence = minOf(0.95f, boostedConfidence + 0.08f)
            }
        }
        
        // 营销强特征（多个营销词同时出现）
        if (modelResult.category == CATEGORY_MARKETING || modelResult.category == CATEGORY_ADVERTISING) {
            val marketingCount = MARKETING_KEYWORDS.count { input.contains(it) } +
                                ADVERTISING_KEYWORDS.count { input.contains(it) }
            if (marketingCount >= 2) {
                boostedConfidence = minOf(0.92f, boostedConfidence + 0.07f)
            }
        }
        
        return modelResult.copy(
            confidence = boostedConfidence,
            category = finalCategory,
            method = "hybrid"
        )
    }
    
    /**
     * 仅使用启发式规则分类（低置信度时）
     */
    private fun applyHeuristicsOnly(
        context: NotificationContext,
        startTime: Long
    ): ClassificationResult {
        val input = "${context.title} ${context.text} ${context.bigText}".lowercase()
        
        // 按优先级检查规则
        val (category, confidence) = when {
            // OTP（高优先级）
            OTP_KEYWORDS.any { input.contains(it) } && Regex("""\d{4,6}""").containsMatchIn(input) -> {
                CATEGORY_OTP to 0.75f
            }
            
            // 支付结果
            PAYMENT_KEYWORDS.any { input.contains(it) } -> {
                CATEGORY_PAYMENT_RESULT to 0.70f
            }
            
            // 营销/广告
            MARKETING_KEYWORDS.count { input.contains(it) } >= 2 -> {
                CATEGORY_MARKETING to 0.68f
            }
            ADVERTISING_KEYWORDS.count { input.contains(it) } >= 2 -> {
                CATEGORY_ADVERTISING to 0.65f
            }
            
            // 社交
            SOCIAL_KEYWORDS.any { input.contains(it) } -> {
                CATEGORY_SOCIAL_UPDATES to 0.60f
            }
            
            // 默认未知
            else -> CATEGORY_UNKNOWN to 0.50f
        }
        
        val inferenceTime = System.currentTimeMillis() - startTime
        
        return ClassificationResult(
            category = category,
            confidence = confidence,
            allScores = mapOf(category to confidence),
            inferenceTimeMs = inferenceTime,
            method = "heuristic"
        )
    }
    
    /**
     * 创建回退结果（错误或空输入时）
     */
    private fun createFallbackResult(reason: String, startTime: Long): ClassificationResult {
        val inferenceTime = System.currentTimeMillis() - startTime
        return ClassificationResult(
            category = CATEGORY_UNKNOWN,
            confidence = 0.0f,
            allScores = mapOf(CATEGORY_UNKNOWN to 0.0f),
            inferenceTimeMs = inferenceTime,
            method = "fallback:$reason"
        )
    }
    
    /**
     * 更新统计信息
     */
    private fun updateStats(result: ClassificationResult) {
        totalInferences.incrementAndGet()
        totalInferenceTimeMs.addAndGet(result.inferenceTimeMs)
        
        categoryCounters.computeIfAbsent(result.category) { AtomicLong(0) }
            .incrementAndGet()
    }
    
    /**
     * 获取性能统计
     */
    fun getStats(): Map<String, Any> {
        val count = totalInferences.get()
        val avgTime = if (count > 0) totalInferenceTimeMs.get() / count else 0L
        
        return mapOf(
            "total_inferences" to count,
            "avg_inference_time_ms" to avgTime,
            "category_distribution" to categoryCounters.mapValues { it.value.get() }
        )
    }
    
    /**
     * 重置统计
     */
    fun resetStats() {
        totalInferences.set(0)
        totalInferenceTimeMs.set(0)
        categoryCounters.clear()
    }
}

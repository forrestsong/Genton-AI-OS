package com.genton.brain.resident

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B4.1 常驻小模型域 - 意图分类器
 * 
 * 职责：
 * 1. 对用户输入进行实时意图分类（本地小模型，≤20M）
 * 2. 常驻推理，快速响应（目标延迟 <50ms）
 * 3. 支持多层级意图分类（一级：域分类，二级：具体意图）
 * 4. 与规划器协作，决定是否需要升级到大模型
 * 
 * 设计要点：
 * - 常驻 NPU 小核，不占用主推理资源
 * - 支持增量学习（用户反馈 → 模型微调）
 * - 置信度输出（低置信度时升级到大模型）
 * - 域分类：通信、日程、文件、应用、系统、通用对话等
 * 
 * 符合 WBS：
 * - B2.1 规划器：本地小模型意图分类 + Goal DAG 分解 + 复杂任务升级判定
 * - C4.1 意图/路由分类小模型（常驻预算 ≤20M 参数级）
 */
class IntentClassifier(private val modelManager: ResidentModelManager) {
    
    companion object {
        private const val TAG = "IntentClassifier"
        
        // 推理配置
        private const val MAX_INPUT_LENGTH = 512  // 最大输入token数
        private const val CONFIDENCE_THRESHOLD_HIGH = 0.85f  // 高置信度阈值
        private const val CONFIDENCE_THRESHOLD_MEDIUM = 0.60f  // 中置信度阈值
        private const val INFERENCE_TIMEOUT_MS = 100L  // 推理超时 100ms
        
        // 意图域分类（一级）
        const val DOMAIN_COMMUNICATION = "communication"  // 通信（通话、短信、邮件）
        const val DOMAIN_CALENDAR = "calendar"  // 日程
        const val DOMAIN_FILES = "files"  // 文件管理
        const val DOMAIN_APPS = "apps"  // 应用控制
        const val DOMAIN_SYSTEM = "system"  // 系统设置
        const val DOMAIN_CONVERSATION = "conversation"  // 通用对话
        const val DOMAIN_UNKNOWN = "unknown"  // 未知
        
        // 升级策略
        const val UPGRADE_NOT_NEEDED = 0  // 不需要升级
        const val UPGRADE_RECOMMENDED = 1  // 推荐升级（中置信度）
        const val UPGRADE_REQUIRED = 2  // 必须升级（低置信度或复杂任务）
    }
    
    // 运行状态
    private val isStarted = AtomicBoolean(false)
    
    // 推理缓存（最近N个推理结果）
    private val inferenceCache = ConcurrentHashMap<String, CachedIntentResult>()
    private val cacheMaxSize = 100
    
    // 统计信息
    private var totalInferences = 0L
    private var cacheHits = 0L
    private var upgradeRecommendations = 0L
    
    /**
     * 启动意图分类器
     */
    fun start() {
        if (isStarted.get()) {
            Log.w(TAG, "IntentClassifier already started")
            return
        }
        
        Log.i(TAG, "Starting intent classifier...")
        
        try {
            // 确保意图分类模型已加载
            ensureModelLoaded()
            
            isStarted.set(true)
            Log.i(TAG, "Intent classifier started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start intent classifier", e)
            throw e
        }
    }
    
    /**
     * 停止意图分类器
     */
    fun stop() {
        if (!isStarted.get()) {
            return
        }
        
        Log.i(TAG, "Stopping intent classifier...")
        
        // 清空缓存
        inferenceCache.clear()
        
        isStarted.set(false)
        Log.i(TAG, "Intent classifier stopped")
    }
    
    /**
     * 确保意图分类模型已加载
     */
    private fun ensureModelLoaded() {
        val model = modelManager.getModel(ResidentModelManager.MODEL_TYPE_INTENT_CLASSIFIER)
        
        if (model == null) {
            // 模型未加载，尝试加载
            val loaded = modelManager.loadModel(
                ResidentModelManager.MODEL_TYPE_INTENT_CLASSIFIER,
                "/system/genton/models/intent_classifier_v1.tflite"
            )
            
            if (!loaded) {
                throw IllegalStateException("Failed to load intent classifier model")
            }
        }
        
        // 将模型状态设置为运行
        val loadedModel = modelManager.getModel(ResidentModelManager.MODEL_TYPE_INTENT_CLASSIFIER)
        if (loadedModel != null && loadedModel.state == ResidentModelManager.STATE_LOADED) {
            loadedModel.state = ResidentModelManager.STATE_RUNNING
        }
    }
    
    /**
     * 分类用户意图
     */
    fun classify(userInput: String, context: IntentContext? = null): IntentClassification {
        if (!isStarted.get()) {
            throw IllegalStateException("IntentClassifier not started")
        }
        
        totalInferences++
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 检查缓存
            val cacheKey = computeCacheKey(userInput, context)
            val cached = inferenceCache[cacheKey]
            if (cached != null && !cached.isExpired()) {
                cacheHits++
                Log.d(TAG, "Intent cache hit: input=$userInput")
                return cached.result
            }
            
            // 2. 预处理输入
            val processedInput = preprocessInput(userInput)
            
            // 3. 获取模型并推理
            val model = modelManager.getModel(ResidentModelManager.MODEL_TYPE_INTENT_CLASSIFIER)
            if (model == null || model.state != ResidentModelManager.STATE_RUNNING) {
                Log.w(TAG, "Intent model not available, using fallback")
                return createFallbackClassification(userInput)
            }
            
            // 4. 编码输入为模型格式
            val inputTensor = encodeInput(processedInput, context)
            
            // 5. 执行推理
            val output = model.inference(inputTensor)
            
            // 6. 解析输出
            val classification = parseModelOutput(output, userInput)
            
            // 7. 缓存结果
            cacheResult(cacheKey, classification)
            
            val latency = System.currentTimeMillis() - startTime
            Log.i(TAG, "Intent classified: domain=${classification.domain}, " +
                    "confidence=${classification.confidence}, latency=${latency}ms")
            
            // 统计升级推荐
            if (classification.upgradeRecommendation > UPGRADE_NOT_NEEDED) {
                upgradeRecommendations++
            }
            
            return classification
            
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying intent", e)
            return createFallbackClassification(userInput)
        }
    }
    
    /**
     * 预处理输入
     */
    private fun preprocessInput(input: String): String {
        // 1. 清理文本（去除多余空格、特殊字符）
        var processed = input.trim().replace(Regex("\\s+"), " ")
        
        // 2. 截断超长输入
        if (processed.length > MAX_INPUT_LENGTH * 4) {  // 估算 token 长度
            processed = processed.substring(0, MAX_INPUT_LENGTH * 4)
        }
        
        return processed
    }
    
    /**
     * 编码输入为模型格式
     */
    private fun encodeInput(input: String, context: IntentContext?): FloatArray {
        // TODO: 实际实现：
        // 1. Tokenization（分词）
        // 2. Token to ID 映射
        // 3. Padding/Truncation
        // 4. 添加上下文特征（如果提供）
        // 5. 转换为 FloatArray
        
        // 当前简化实现：返回模拟输入张量（假设 embedding size=128）
        val inputIds = FloatArray(MAX_INPUT_LENGTH) { 0f }
        
        // 模拟 token ID 编码（实际应使用 tokenizer）
        val tokens = input.split(" ").take(MAX_INPUT_LENGTH)
        tokens.forEachIndexed { index, token ->
            inputIds[index] = (token.hashCode() % 30000).toFloat()  // 模拟 vocab size=30000
        }
        
        return inputIds
    }
    
    /**
     * 解析模型输出
     */
    private fun parseModelOutput(output: FloatArray, originalInput: String): IntentClassification {
        // TODO: 实际实现：
        // 模型输出格式：
        // - 域分类 logits（7个域）
        // - 具体意图 logits（每个域的细分意图）
        // - 复杂度评分（是否需要升级到大模型）
        
        // 当前简化实现：模拟解析
        val domains = listOf(
            DOMAIN_COMMUNICATION, DOMAIN_CALENDAR, DOMAIN_FILES,
            DOMAIN_APPS, DOMAIN_SYSTEM, DOMAIN_CONVERSATION, DOMAIN_UNKNOWN
        )
        
        // 模拟 softmax 输出
        val domainScores = FloatArray(domains.size) { Math.random().toFloat() }
        val sum = domainScores.sum()
        for (i in domainScores.indices) {
            domainScores[i] /= sum
        }
        
        // 选择最高置信度的域
        val maxIndex = domainScores.indices.maxByOrNull { domainScores[it] } ?: 0
        val domain = domains[maxIndex]
        val confidence = domainScores[maxIndex]
        
        // 决定是否需要升级
        val upgradeRecommendation = when {
            confidence >= CONFIDENCE_THRESHOLD_HIGH -> UPGRADE_NOT_NEEDED
            confidence >= CONFIDENCE_THRESHOLD_MEDIUM -> UPGRADE_RECOMMENDED
            else -> UPGRADE_REQUIRED
        }
        
        // 提取具体意图（简化：直接从输入推断）
        val specificIntent = inferSpecificIntent(domain, originalInput)
        
        return IntentClassification(
            domain = domain,
            specificIntent = specificIntent,
            confidence = confidence,
            upgradeRecommendation = upgradeRecommendation,
            entities = extractEntities(originalInput),
            rawInput = originalInput
        )
    }
    
    /**
     * 推断具体意图
     */
    private fun inferSpecificIntent(domain: String, input: String): String {
        // TODO: 实际实现：基于二级分类器或规则推断
        
        return when (domain) {
            DOMAIN_COMMUNICATION -> {
                when {
                    input.contains("打电话") || input.contains("拨打") -> "make_call"
                    input.contains("发短信") || input.contains("发信息") -> "send_sms"
                    input.contains("发邮件") -> "send_email"
                    else -> "unknown_communication"
                }
            }
            DOMAIN_CALENDAR -> {
                when {
                    input.contains("创建") || input.contains("添加") || input.contains("新建") -> "create_event"
                    input.contains("查看") || input.contains("有什么") -> "query_event"
                    input.contains("提醒") -> "set_reminder"
                    else -> "unknown_calendar"
                }
            }
            DOMAIN_FILES -> {
                when {
                    input.contains("打开") -> "open_file"
                    input.contains("查找") || input.contains("搜索") -> "search_file"
                    input.contains("删除") -> "delete_file"
                    else -> "unknown_files"
                }
            }
            DOMAIN_APPS -> {
                when {
                    input.contains("打开") || input.contains("启动") -> "launch_app"
                    input.contains("关闭") -> "close_app"
                    else -> "unknown_apps"
                }
            }
            DOMAIN_SYSTEM -> {
                when {
                    input.contains("音量") -> "adjust_volume"
                    input.contains("亮度") -> "adjust_brightness"
                    input.contains("设置") -> "open_settings"
                    else -> "unknown_system"
                }
            }
            DOMAIN_CONVERSATION -> "general_conversation"
            else -> "unknown"
        }
    }
    
    /**
     * 提取实体
     */
    private fun extractEntities(input: String): Map<String, String> {
        // TODO: 实际实现：使用 NER 模型或规则提取实体
        
        val entities = mutableMapOf<String, String>()
        
        // 简化实现：基于规则提取
        // 时间实体
        if (input.contains("明天")) {
            entities["time"] = "tomorrow"
        } else if (input.contains("今天")) {
            entities["time"] = "today"
        }
        
        // 联系人实体（简化）
        val namePattern = Regex("给\\s*(\\S+)\\s*打电话")
        namePattern.find(input)?.let {
            entities["contact"] = it.groupValues[1]
        }
        
        return entities
    }
    
    /**
     * 创建降级分类结果
     */
    private fun createFallbackClassification(input: String): IntentClassification {
        return IntentClassification(
            domain = DOMAIN_UNKNOWN,
            specificIntent = "unknown",
            confidence = 0.1f,
            upgradeRecommendation = UPGRADE_REQUIRED,
            entities = emptyMap(),
            rawInput = input
        )
    }
    
    /**
     * 计算缓存键
     */
    private fun computeCacheKey(input: String, context: IntentContext?): String {
        return if (context != null) {
            "${input}_${context.hashCode()}"
        } else {
            input
        }
    }
    
    /**
     * 缓存结果
     */
    private fun cacheResult(key: String, result: IntentClassification) {
        // 限制缓存大小
        if (inferenceCache.size >= cacheMaxSize) {
            // 删除最旧的条目（简化实现）
            val oldestKey = inferenceCache.keys.firstOrNull()
            if (oldestKey != null) {
                inferenceCache.remove(oldestKey)
            }
        }
        
        inferenceCache[key] = CachedIntentResult(
            result = result,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): IntentClassifierStatistics {
        return IntentClassifierStatistics(
            isRunning = isStarted.get(),
            totalInferences = totalInferences,
            cacheHits = cacheHits,
            cacheHitRate = if (totalInferences > 0) cacheHits.toFloat() / totalInferences else 0f,
            upgradeRecommendations = upgradeRecommendations,
            upgradeRate = if (totalInferences > 0) upgradeRecommendations.toFloat() / totalInferences else 0f
        )
    }
}

/**
 * 意图分类结果
 */
data class IntentClassification(
    val domain: String,  // 意图域（一级分类）
    val specificIntent: String,  // 具体意图（二级分类）
    val confidence: Float,  // 置信度 0-1
    val upgradeRecommendation: Int,  // 升级推荐
    val entities: Map<String, String>,  // 提取的实体
    val rawInput: String  // 原始输入
)

/**
 * 意图上下文（可选，提供额外上下文信息）
 */
data class IntentContext(
    val previousIntent: String? = null,  // 上一个意图
    val currentApp: String? = null,  // 当前应用
    val userState: String? = null  // 用户状态（driving, walking, etc.）
)

/**
 * 缓存的意图结果
 */
private data class CachedIntentResult(
    val result: IntentClassification,
    val timestamp: Long,
    val ttlMs: Long = 60000  // 缓存有效期 60秒
) {
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMs
    }
}

/**
 * 意图分类器统计信息
 */
data class IntentClassifierStatistics(
    val isRunning: Boolean,
    val totalInferences: Long,
    val cacheHits: Long,
    val cacheHitRate: Float,
    val upgradeRecommendations: Long,
    val upgradeRate: Float
)

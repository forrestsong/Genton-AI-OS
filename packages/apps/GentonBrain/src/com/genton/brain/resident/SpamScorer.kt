package com.genton.brain.resident

import android.telecom.Call
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * C4.2 反骚扰评分器 - Spam Scorer
 * 
 * 职责：
 * 1. 对来电进行实时骚扰评分（spamScore: 0.0-1.0）
 * 2. 支持策略引擎 call_gate 域的 spamScore 裁决
 * 3. 常驻 NPU 小核，快速推理（目标延迟 <50ms）
 * 4. 多维度特征融合（号码特征、行为特征、内容特征）
 * 
 * 模型架构：
 * - 基础模型：轻量级 MLP（Multi-Layer Perceptron，<5M 参数）
 * - 输入特征：号码特征（20 维）+ 行为特征（10 维）+ 文本特征（可选）
 * - 输出：骚扰概率（0.0-1.0）
 * - 模型大小：~1.5MB（INT8 量化）
 * - 推理延迟：<50ms（NPU 小核或 CPU）
 * 
 * 特征工程：
 * - 号码特征：号段、位数、重复模式、虚拟号段、国际号码
 * - 行为特征：呼叫频率、时段分布、通话时长、挂断速度
 * - 文本特征（可选）：来电显示文本（如"营销电话"）
 * - 黑白名单：联系人白名单、用户标记黑名单、众包数据库
 * 
 * 评分策略：
 * - spamScore ≥ 0.9：高度可疑（策略引擎可路由到 RECORD_ONLY）
 * - spamScore 0.7-0.9：中度可疑（用户可选策略）
 * - spamScore < 0.7：正常来电（原生响铃）
 * 
 * 符合 WBS：
 * - C4.2 CallGate 反骚扰 spamScore 模型
 * - A10.1 CallGate 服务端（classifyIncoming 消费 spamScore）
 * - D7.1 CallGate 接管 UI（显示骚扰评分）
 */
class SpamScorer(private val modelManager: ResidentModelManager) {
    
    companion object {
        private const val TAG = "SpamScorer"
        
        // 推理配置
        private const val INFERENCE_TIMEOUT_MS = 50L  // 推理超时 50ms
        private const val SCORE_THRESHOLD_HIGH = 0.9f  // 高度可疑阈值
        private const val SCORE_THRESHOLD_MEDIUM = 0.7f  // 中度可疑阈值
        
        // 号段特征（中国号段规则）
        private val SUSPICIOUS_PREFIXES = setOf(
            "95", "96", "400", "950", "951", "952", "953", "954", "955", "956"
        )
        
        private val MARKETING_PREFIXES = setOf(
            "400", "95"  // 400 企业服务号、95 开头银行/运营商客服
        )
        
        private val VIRTUAL_PREFIXES = setOf(
            "170", "171"  // 虚拟运营商号段（骚扰高发）
        )
        
        // 行为特征阈值
        private const val FREQUENT_CALL_THRESHOLD = 3  // 短时间内频繁呼叫
        private const val FREQUENT_CALL_WINDOW_MS = 3600000L  // 1 小时窗口
        
        // 模型元数据
        private const val MODEL_NAME = "spam_scorer"
        private const val MODEL_VERSION = "1.0.0"
    }
    
    // 评分结果数据类
    data class SpamScore(
        val score: Float,  // 0.0-1.0，越高越可疑
        val confidence: Float,  // 置信度
        val features: Map<String, Float>,  // 特征贡献度
        val inferenceTimeMs: Long,
        val method: String  // "model", "heuristic", "whitelist"
    )
    
    // 来电上下文
    data class CallContext(
        val phoneNumber: String,
        val callerName: String?,  // 来电显示名称
        val isContact: Boolean,  // 是否为联系人
        val isWhitelisted: Boolean,  // 是否在白名单
        val callHistory: List<CallRecord>,  // 历史通话记录
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // 通话记录
    data class CallRecord(
        val timestamp: Long,
        val duration: Long,  // 通话时长（秒）
        val isAnswered: Boolean,
        val isOutgoing: Boolean
    )
    
    // 性能统计
    private val totalScores = AtomicLong(0)
    private val totalScoreTimeMs = AtomicLong(0)
    private val highSpamCount = AtomicLong(0)  // ≥0.9
    private val mediumSpamCount = AtomicLong(0)  // 0.7-0.9
    
    // 用户标记缓存（黑名单）
    private val userBlacklist = ConcurrentHashMap<String, Float>()  // phoneNumber -> score
    
    // 频繁呼叫追踪
    private val recentCalls = ConcurrentHashMap<String, MutableList<Long>>()  // phoneNumber -> timestamps
    
    /**
     * 对来电进行骚扰评分
     * 
     * @param context 来电上下文
     * @return 骚扰评分结果（0.0-1.0）
     */
    fun score(context: CallContext): SpamScore {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 白名单快速放行
            if (context.isWhitelisted || context.isContact) {
                return createWhitelistResult(startTime)
            }
            
            // 2. 用户黑名单检查
            val userMarkedScore = userBlacklist[context.phoneNumber]
            if (userMarkedScore != null && userMarkedScore >= SCORE_THRESHOLD_HIGH) {
                return createUserBlacklistResult(userMarkedScore, startTime)
            }
            
            // 3. 提取特征
            val features = extractFeatures(context)
            
            // 4. 模型推理
            val modelScore = runModelInference(features)
            
            // 5. 更新统计
            updateStats(modelScore)
            
            return modelScore
            
        } catch (e: Exception) {
            Log.e(TAG, "Scoring failed", e)
            return createFallbackResult(startTime)
        }
    }
    
    /**
     * 提取特征向量
     */
    private fun extractFeatures(context: CallContext): Map<String, Float> {
        val features = mutableMapOf<String, Float>()
        val number = context.phoneNumber
        
        // === 号码特征（20 维）===
        
        // 1. 号段特征
        features["has_suspicious_prefix"] = if (SUSPICIOUS_PREFIXES.any { number.startsWith(it) }) 1.0f else 0.0f
        features["has_marketing_prefix"] = if (MARKETING_PREFIXES.any { number.startsWith(it) }) 1.0f else 0.0f
        features["has_virtual_prefix"] = if (VIRTUAL_PREFIXES.any { number.startsWith(it) }) 1.0f else 0.0f
        
        // 2. 号码长度特征
        features["number_length"] = number.length.toFloat() / 15.0f  // 归一化
        features["is_short_code"] = if (number.length <= 6) 1.0f else 0.0f  // 短号码（如 95XXX）
        
        // 3. 号码模式特征
        features["has_repeated_digits"] = detectRepeatedDigits(number)
        features["has_sequential_digits"] = detectSequentialDigits(number)
        features["digit_diversity"] = calculateDigitDiversity(number)
        
        // 4. 国际号码特征
        features["is_international"] = if (number.startsWith("+") || number.startsWith("00")) 1.0f else 0.0f
        
        // === 行为特征（10 维）===
        
        // 5. 频繁呼叫检测
        val recentCallCount = countRecentCalls(number)
        features["recent_call_count"] = minOf(recentCallCount.toFloat() / 5.0f, 1.0f)  // 归一化到 [0,1]
        features["is_frequent_caller"] = if (recentCallCount >= FREQUENT_CALL_THRESHOLD) 1.0f else 0.0f
        
        // 6. 历史通话行为
        val history = context.callHistory
        features["total_calls"] = minOf(history.size.toFloat() / 10.0f, 1.0f)
        features["answered_ratio"] = if (history.isNotEmpty()) {
            history.count { it.isAnswered }.toFloat() / history.size
        } else 0.0f
        
        // 7. 平均通话时长
        val avgDuration = if (history.isNotEmpty()) {
            history.filter { it.isAnswered }.map { it.duration }.average().toFloat()
        } else 0.0f
        features["avg_call_duration"] = minOf(avgDuration / 300.0f, 1.0f)  // 归一化（5 分钟）
        features["has_short_calls"] = if (avgDuration < 10.0f) 1.0f else 0.0f  // 短通话（<10 秒）
        
        // 8. 呼叫时段特征
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        features["is_off_hours"] = if (hour < 8 || hour > 21) 1.0f else 0.0f  // 非正常时段
        
        // === 文本特征（可选）===
        
        // 9. 来电显示文本特征
        context.callerName?.let { name ->
            features["has_marketing_text"] = if (name.contains("营销") || name.contains("推广")) 1.0f else 0.0f
            features["has_spam_text"] = if (name.contains("骚扰") || name.contains("诈骗")) 1.0f else 0.0f
        }
        
        return features
    }
    
    /**
     * 检测重复数字（如 111、888）
     */
    private fun detectRepeatedDigits(number: String): Float {
        val repeatPattern = Regex("""(\d)\1{2,}""")  // 3 个及以上重复
        val matches = repeatPattern.findAll(number)
        return if (matches.count() > 0) 1.0f else 0.0f
    }
    
    /**
     * 检测连续数字（如 123、456）
     */
    private fun detectSequentialDigits(number: String): Float {
        for (i in 0 until number.length - 2) {
            val a = number[i].digitToIntOrNull() ?: continue
            val b = number[i + 1].digitToIntOrNull() ?: continue
            val c = number[i + 2].digitToIntOrNull() ?: continue
            if (b == a + 1 && c == b + 1) return 1.0f
        }
        return 0.0f
    }
    
    /**
     * 计算数字多样性（0-1，越低越可疑）
     */
    private fun calculateDigitDiversity(number: String): Float {
        val uniqueDigits = number.filter { it.isDigit() }.toSet().size
        return uniqueDigits.toFloat() / 10.0f  // 最多 10 个不同数字
    }
    
    /**
     * 统计最近呼叫次数
     */
    private fun countRecentCalls(phoneNumber: String): Int {
        val now = System.currentTimeMillis()
        val calls = recentCalls.getOrPut(phoneNumber) { mutableListOf() }
        
        // 清理过期记录
        calls.removeAll { now - it > FREQUENT_CALL_WINDOW_MS }
        
        // 记录当前呼叫
        calls.add(now)
        
        return calls.size
    }
    
    /**
     * 运行模型推理
     */
    private fun runModelInference(features: Map<String, Float>): SpamScore {
        val startTime = System.currentTimeMillis()
        
        // TODO: 调用 ResidentModelManager 执行实际推理
        // 当前返回基于特征的启发式评分，实际实现需要：
        // 1. 加载 TFLite 模型（轻量级 MLP）
        // 2. 将特征向量转换为输入张量
        // 3. 执行推理
        // 4. 解析输出（单一 sigmoid 输出）
        
        // 启发式评分（实际应从模型获取）
        val score = calculateHeuristicScore(features)
        val confidence = calculateConfidence(features)
        
        val inferenceTime = System.currentTimeMillis() - startTime
        
        return SpamScore(
            score = score,
            confidence = confidence,
            features = features,
            inferenceTimeMs = inferenceTime,
            method = "heuristic"  // 实际应为 "model"
        )
    }
    
    /**
     * 启发式评分计算（模拟模型输出）
     */
    private fun calculateHeuristicScore(features: Map<String, Float>): Float {
        var score = 0.0f
        
        // 号段强特征（权重高）
        score += features["has_suspicious_prefix"]!! * 0.25f
        score += features["has_marketing_prefix"]!! * 0.20f
        score += features["has_virtual_prefix"]!! * 0.15f
        
        // 号码模式特征
        score += features["has_repeated_digits"]!! * 0.10f
        score += features["has_sequential_digits"]!! * 0.08f
        score += (1.0f - features["digit_diversity"]!!) * 0.10f  // 低多样性加分
        
        // 行为特征
        score += features["is_frequent_caller"]!! * 0.20f
        score += features["has_short_calls"]!! * 0.12f
        score += (1.0f - features["answered_ratio"]!!) * 0.10f  // 低接听率加分
        
        // 文本特征
        score += (features["has_marketing_text"] ?: 0.0f) * 0.15f
        score += (features["has_spam_text"] ?: 0.0f) * 0.30f
        
        // 时段特征
        score += features["is_off_hours"]!! * 0.05f
        
        return minOf(score, 1.0f)  // 限制在 [0, 1]
    }
    
    /**
     * 计算置信度
     */
    private fun calculateConfidence(features: Map<String, Float>): Float {
        // 如果有多个强特征命中，置信度高
        val strongFeatures = listOf(
            features["has_suspicious_prefix"]!!,
            features["has_marketing_prefix"]!!,
            features["is_frequent_caller"]!!,
            features["has_spam_text"] ?: 0.0f
        )
        
        val strongCount = strongFeatures.count { it > 0.5f }
        return when {
            strongCount >= 3 -> 0.90f
            strongCount == 2 -> 0.75f
            strongCount == 1 -> 0.60f
            else -> 0.50f
        }
    }
    
    /**
     * 创建白名单结果
     */
    private fun createWhitelistResult(startTime: Long): SpamScore {
        val inferenceTime = System.currentTimeMillis() - startTime
        return SpamScore(
            score = 0.0f,
            confidence = 1.0f,
            features = mapOf("whitelist" to 1.0f),
            inferenceTimeMs = inferenceTime,
            method = "whitelist"
        )
    }
    
    /**
     * 创建用户黑名单结果
     */
    private fun createUserBlacklistResult(score: Float, startTime: Long): SpamScore {
        val inferenceTime = System.currentTimeMillis() - startTime
        return SpamScore(
            score = score,
            confidence = 1.0f,
            features = mapOf("user_blacklist" to 1.0f),
            inferenceTimeMs = inferenceTime,
            method = "user_blacklist"
        )
    }
    
    /**
     * 创建回退结果（错误时）
     */
    private fun createFallbackResult(startTime: Long): SpamScore {
        val inferenceTime = System.currentTimeMillis() - startTime
        return SpamScore(
            score = 0.5f,  // 中性评分
            confidence = 0.0f,
            features = emptyMap(),
            inferenceTimeMs = inferenceTime,
            method = "fallback"
        )
    }
    
    /**
     * 用户标记为骚扰（更新黑名单）
     */
    fun markAsSpam(phoneNumber: String, score: Float = 1.0f) {
        userBlacklist[phoneNumber] = score
        Log.d(TAG, "User marked $phoneNumber as spam (score=$score)")
    }
    
    /**
     * 用户标记为非骚扰（移除黑名单）
     */
    fun markAsNotSpam(phoneNumber: String) {
        userBlacklist.remove(phoneNumber)
        Log.d(TAG, "User marked $phoneNumber as not spam")
    }
    
    /**
     * 更新统计信息
     */
    private fun updateStats(result: SpamScore) {
        totalScores.incrementAndGet()
        totalScoreTimeMs.addAndGet(result.inferenceTimeMs)
        
        when {
            result.score >= SCORE_THRESHOLD_HIGH -> highSpamCount.incrementAndGet()
            result.score >= SCORE_THRESHOLD_MEDIUM -> mediumSpamCount.incrementAndGet()
        }
    }
    
    /**
     * 获取性能统计
     */
    fun getStats(): Map<String, Any> {
        val count = totalScores.get()
        val avgTime = if (count > 0) totalScoreTimeMs.get() / count else 0L
        
        return mapOf(
            "total_scores" to count,
            "avg_score_time_ms" to avgTime,
            "high_spam_count" to highSpamCount.get(),
            "medium_spam_count" to mediumSpamCount.get(),
            "user_blacklist_size" to userBlacklist.size
        )
    }
    
    /**
     * 重置统计
     */
    fun resetStats() {
        totalScores.set(0)
        totalScoreTimeMs.set(0)
        highSpamCount.set(0)
        mediumSpamCount.set(0)
    }
    
    /**
     * 清理过期的频繁呼叫记录（定期调用）
     */
    fun cleanupOldRecords() {
        val now = System.currentTimeMillis()
        recentCalls.forEach { (number, calls) ->
            calls.removeAll { now - it > FREQUENT_CALL_WINDOW_MS }
            if (calls.isEmpty()) {
                recentCalls.remove(number)
            }
        }
    }
}

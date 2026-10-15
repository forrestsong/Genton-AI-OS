package com.genton.brain.memory.extraction

import android.util.Log
import java.util.regex.Pattern

/**
 * 偏好抽取器 - 从对话中提取用户偏好
 * 
 * 职责：
 * 1. 识别喜好/厌恶/习惯
 * 2. 评估偏好强度（1-5）
 * 3. 评估抽取置信度
 * 
 * 偏好类型：
 * - LIKE: 喜欢（"我喜欢 X"）
 * - DISLIKE: 不喜欢（"我不喜欢 Y"）
 * - HABIT: 习惯（"我通常 Z"）
 * 
 * 验收标准：
 * - 准确率 ≥75%
 * - 召回率 ≥65%
 * 
 * @since A3
 */
class PreferenceExtractor {
    
    companion object {
        private const val TAG = "PreferenceExtractor"
        private const val MIN_CONFIDENCE = 0.7f
        
        // 喜好模式
        private val LIKE_PATTERNS = mapOf(
            Pattern.compile("(特别|非常|很|超)?(喜欢|爱|偏爱|钟爱)([^，。！？]+)") to 5,
            Pattern.compile("(喜欢|爱|偏爱)([^，。！？]+)") to 3,
            Pattern.compile("([^，。！？]+)(不错|很好|挺好)") to 2
        )
        
        // 厌恶模式
        private val DISLIKE_PATTERNS = mapOf(
            Pattern.compile("(特别|非常|很|超)?(讨厌|厌恶|不喜欢|反感)([^，。！？]+)") to 5,
            Pattern.compile("(讨厌|不喜欢|反感)([^，。！？]+)") to 3,
            Pattern.compile("([^，。！？]+)(不太喜欢|不大喜欢|一般)") to 2
        )
        
        // 习惯模式
        private val HABIT_PATTERNS = mapOf(
            Pattern.compile("(每天|每周|每月|总是|经常|通常|习惯)([^，。！？]+)") to 4,
            Pattern.compile("(一般|平时|偶尔)([^，。！？]+)") to 2
        )
    }
    
    /**
     * 从消息列表中抽取偏好
     * 
     * @param messages 对话消息列表
     * @return 抽取的偏好列表
     */
    fun extract(messages: List<Message>): List<ExtractedPreference> {
        val preferences = mutableListOf<ExtractedPreference>()
        
        // 只处理用户消息
        val userMessages = messages.filter { it.role == MessageRole.USER }
        
        if (userMessages.isEmpty()) {
            Log.d(TAG, "无用户消息，跳过偏好抽取")
            return emptyList()
        }
        
        Log.i(TAG, "开始偏好抽取，用户消息数：${userMessages.size}")
        
        // 逐条消息抽取
        userMessages.forEach { message ->
            val text = message.content
            
            // 抽取喜好
            preferences.addAll(extractLikes(text, message.timestamp))
            
            // 抽取厌恶
            preferences.addAll(extractDislikes(text, message.timestamp))
            
            // 抽取习惯
            preferences.addAll(extractHabits(text, message.timestamp))
        }
        
        // 过滤低置信度
        val filtered = preferences.filter { it.confidence >= MIN_CONFIDENCE }
        
        Log.i(TAG, "偏好抽取完成：总计 ${preferences.size}，过滤后 ${filtered.size}")
        
        return filtered
    }
    
    /**
     * 抽取喜好
     */
    private fun extractLikes(text: String, timestamp: Long): List<ExtractedPreference> {
        val preferences = mutableListOf<ExtractedPreference>()
        
        LIKE_PATTERNS.forEach { (pattern, baseStrength) ->
            val matcher = pattern.matcher(text)
            
            while (matcher.find()) {
                val subject = if (matcher.groupCount() >= 3) {
                    matcher.group(matcher.groupCount())  // 最后一组
                } else {
                    matcher.group(1)
                }.trim()
                
                // 跳过过短或过长的主语
                if (subject.length < 2 || subject.length > 20) {
                    continue
                }
                
                preferences.add(
                    ExtractedPreference(
                        type = PreferenceType.LIKE,
                        subject = subject,
                        strength = baseStrength,
                        sourceText = text,
                        timestamp = timestamp,
                        confidence = calculateConfidence(pattern, text)
                    )
                )
            }
        }
        
        return preferences
    }
    
    /**
     * 抽取厌恶
     */
    private fun extractDislikes(text: String, timestamp: Long): List<ExtractedPreference> {
        val preferences = mutableListOf<ExtractedPreference>()
        
        DISLIKE_PATTERNS.forEach { (pattern, baseStrength) ->
            val matcher = pattern.matcher(text)
            
            while (matcher.find()) {
                val subject = if (matcher.groupCount() >= 3) {
                    matcher.group(matcher.groupCount())
                } else {
                    matcher.group(1)
                }.trim()
                
                if (subject.length < 2 || subject.length > 20) {
                    continue
                }
                
                preferences.add(
                    ExtractedPreference(
                        type = PreferenceType.DISLIKE,
                        subject = subject,
                        strength = baseStrength,
                        sourceText = text,
                        timestamp = timestamp,
                        confidence = calculateConfidence(pattern, text)
                    )
                )
            }
        }
        
        return preferences
    }
    
    /**
     * 抽取习惯
     */
    private fun extractHabits(text: String, timestamp: Long): List<ExtractedPreference> {
        val preferences = mutableListOf<ExtractedPreference>()
        
        HABIT_PATTERNS.forEach { (pattern, baseStrength) ->
            val matcher = pattern.matcher(text)
            
            while (matcher.find()) {
                val subject = matcher.group(matcher.groupCount()).trim()
                
                if (subject.length < 2 || subject.length > 30) {
                    continue
                }
                
                preferences.add(
                    ExtractedPreference(
                        type = PreferenceType.HABIT,
                        subject = subject,
                        strength = baseStrength,
                        sourceText = text,
                        timestamp = timestamp,
                        confidence = calculateConfidence(pattern, text)
                    )
                )
            }
        }
        
        return preferences
    }
    
    /**
     * 计算置信度（基于模式匹配质量）
     */
    private fun calculateConfidence(pattern: Pattern, text: String): Float {
        // 简化：根据文本长度和模式复杂度
        val baseConfidence = 0.75f
        
        // 文本越短，置信度越高（更明确）
        val lengthBonus = if (text.length < 50) 0.15f else 0.05f
        
        return (baseConfidence + lengthBonus).coerceIn(0f, 1f)
    }
}

/**
 * 抽取的偏好
 */
data class ExtractedPreference(
    val type: PreferenceType,
    val subject: String,          // 偏好对象（如"咖啡"）
    val strength: Int,            // 强度 1-5
    val sourceText: String,       // 原文
    val timestamp: Long,
    val confidence: Float,
    val tags: List<String> = emptyList()
)

/**
 * 偏好类型
 */
enum class PreferenceType {
    LIKE,      // 喜好
    DISLIKE,   // 厌恶
    HABIT      // 习惯
}

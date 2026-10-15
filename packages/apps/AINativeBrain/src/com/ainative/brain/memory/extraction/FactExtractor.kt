package com.ainative.brain.memory.extraction

import android.util.Log
import java.util.regex.Pattern

/**
 * 事实抽取器 - 从对话中提取结构化事实
 * 
 * 职责：
 * 1. 识别人物/地点/时间/事件等实体
 * 2. 评估抽取置信度
 * 3. 过滤低置信度结果
 * 
 * 抽取策略：
 * - v1: 基于规则的 NER（正则 + 关键词）
 * - v2: 集成端侧 NER 模型（后续优化）
 * 
 * 验收标准：
 * - 准确率 ≥80%（人工标注 100 条对话）
 * - 召回率 ≥70%
 * 
 * @since A3
 */
class FactExtractor {
    
    companion object {
        private const val TAG = "FactExtractor"
        private const val MIN_CONFIDENCE = 0.7f
        
        // 时间关键词
        private val TIME_KEYWORDS = setOf(
            "明天", "后天", "今天", "昨天", "下周", "下个月", "今年", "明年",
            "早上", "上午", "中午", "下午", "晚上", "凌晨",
            "周一", "周二", "周三", "周四", "周五", "周六", "周日",
            "点", "分", "秒"
        )
        
        // 地点关键词
        private val LOCATION_KEYWORDS = setOf(
            "北京", "上海", "广州", "深圳", "杭州", "成都",
            "公司", "家", "办公室", "会议室", "餐厅", "咖啡厅",
            "机场", "车站", "酒店", "商场", "医院", "学校"
        )
        
        // 事件关键词
        private val EVENT_KEYWORDS = setOf(
            "会议", "出差", "旅行", "聚餐", "约会", "面试",
            "生日", "纪念日", "截止", "提交", "汇报", "演示"
        )
        
        // 人物模式
        private val PERSON_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})(先生|女士|老师|经理|总监|同事|朋友|客户)"
        )
        
        // 时间模式
        private val TIME_PATTERN = Pattern.compile(
            "(明天|后天|下周[一二三四五六日]?|\\d{1,2}月\\d{1,2}日)?(早上|上午|中午|下午|晚上)?(\\d{1,2})[点时]"
        )
        
        // 日期模式
        private val DATE_PATTERN = Pattern.compile(
            "(\\d{4}年)?(\\d{1,2})月(\\d{1,2})日"
        )
    }
    
    /**
     * 从消息列表中抽取事实
     * 
     * @param messages 对话消息列表
     * @return 抽取的事实列表
     */
    fun extract(messages: List<Message>): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        
        // 只处理用户消息
        val userMessages = messages.filter { it.role == MessageRole.USER }
        
        if (userMessages.isEmpty()) {
            Log.d(TAG, "无用户消息，跳过事实抽取")
            return emptyList()
        }
        
        Log.i(TAG, "开始事实抽取，用户消息数：${userMessages.size}")
        
        // 逐条消息抽取
        userMessages.forEach { message ->
            val text = message.content
            
            // 抽取人物
            facts.addAll(extractPersons(text, message.timestamp))
            
            // 抽取地点
            facts.addAll(extractLocations(text, message.timestamp))
            
            // 抽取时间
            facts.addAll(extractTimes(text, message.timestamp))
            
            // 抽取事件
            facts.addAll(extractEvents(text, message.timestamp))
        }
        
        // 过滤低置信度
        val filtered = facts.filter { it.confidence >= MIN_CONFIDENCE }
        
        Log.i(TAG, "事实抽取完成：总计 ${facts.size}，过滤后 ${filtered.size}")
        
        return filtered
    }
    
    /**
     * 抽取人物实体
     */
    private fun extractPersons(text: String, timestamp: Long): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        val matcher = PERSON_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val name = matcher.group(1)
            val title = matcher.group(2)
            
            facts.add(
                ExtractedFact(
                    type = FactType.PERSON,
                    content = "$name$title",
                    entities = listOf(
                        Entity(EntityType.PERSON, "$name$title")
                    ),
                    sourceText = text,
                    timestamp = timestamp,
                    confidence = 0.85f
                )
            )
        }
        
        return facts
    }
    
    /**
     * 抽取地点实体
     */
    private fun extractLocations(text: String, timestamp: Long): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        
        // 关键词匹配
        LOCATION_KEYWORDS.forEach { keyword ->
            if (keyword in text) {
                // 提取上下文（前后 10 字符）
                val index = text.indexOf(keyword)
                val start = maxOf(0, index - 10)
                val end = minOf(text.length, index + keyword.length + 10)
                val context = text.substring(start, end)
                
                facts.add(
                    ExtractedFact(
                        type = FactType.LOCATION,
                        content = context.trim(),
                        entities = listOf(
                            Entity(EntityType.LOCATION, keyword)
                        ),
                        sourceText = text,
                        timestamp = timestamp,
                        confidence = 0.80f
                    )
                )
            }
        }
        
        return facts
    }
    
    /**
     * 抽取时间实体
     */
    private fun extractTimes(text: String, timestamp: Long): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        
        // 时间模式（如"明天下午 3 点"）
        val timeMatcher = TIME_PATTERN.matcher(text)
        while (timeMatcher.find()) {
            val timeExpr = timeMatcher.group(0)
            
            facts.add(
                ExtractedFact(
                    type = FactType.TIME,
                    content = timeExpr,
                    entities = listOf(
                        Entity(EntityType.TIME, timeExpr)
                    ),
                    sourceText = text,
                    timestamp = timestamp,
                    confidence = 0.90f
                )
            )
        }
        
        // 日期模式（如"12 月 1 日"）
        val dateMatcher = DATE_PATTERN.matcher(text)
        while (dateMatcher.find()) {
            val dateExpr = dateMatcher.group(0)
            
            facts.add(
                ExtractedFact(
                    type = FactType.TIME,
                    content = dateExpr,
                    entities = listOf(
                        Entity(EntityType.TIME, dateExpr)
                    ),
                    sourceText = text,
                    timestamp = timestamp,
                    confidence = 0.95f
                )
            )
        }
        
        return facts
    }
    
    /**
     * 抽取事件实体
     */
    private fun extractEvents(text: String, timestamp: Long): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        
        // 事件关键词匹配
        EVENT_KEYWORDS.forEach { keyword ->
            if (keyword in text) {
                // 提取完整句子（到句号/问号/感叹号）
                val index = text.indexOf(keyword)
                val start = text.lastIndexOf(Regex("[。？！]"), index).let { 
                    if (it == -1) 0 else it + 1 
                }
                val end = text.indexOf(Regex("[。？！]"), index + keyword.length).let {
                    if (it == -1) text.length else it
                }
                val sentence = text.substring(start, end).trim()
                
                facts.add(
                    ExtractedFact(
                        type = FactType.EVENT,
                        content = sentence,
                        entities = listOf(
                            Entity(EntityType.EVENT, keyword)
                        ),
                        sourceText = text,
                        timestamp = timestamp,
                        confidence = 0.75f
                    )
                )
            }
        }
        
        return facts
    }
}

/**
 * 抽取的事实
 */
data class ExtractedFact(
    val type: FactType,
    val content: String,
    val entities: List<Entity>,
    val sourceText: String,
    val timestamp: Long,
    val confidence: Float,
    val tags: List<String> = emptyList()
)

/**
 * 事实类型
 */
enum class FactType {
    PERSON,      // 人物
    LOCATION,    // 地点
    TIME,        // 时间
    EVENT,       // 事件
    ORGANIZATION // 组织
}

/**
 * 实体
 */
data class Entity(
    val type: EntityType,
    val value: String
)

/**
 * 实体类型
 */
enum class EntityType {
    PERSON,
    LOCATION,
    TIME,
    EVENT,
    ORGANIZATION
}

/**
 * 消息数据类
 */
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long
)

/**
 * 消息角色
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

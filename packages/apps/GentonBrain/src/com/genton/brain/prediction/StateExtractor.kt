package com.genton.brain.prediction

import android.content.Context
import com.genton.context.ContextFrame
import com.genton.events.EventSummary
import com.genton.memory.IAgentMemoryService
import java.util.Calendar

/**
 * 状态特征提取器
 * 
 * 从当前系统状态提取 128 维特征向量，用于 RL 预测模型输入。
 * 
 * 特征组成：
 * - 时间上下文 (16维): 星期 + 小时 + 工作日标志
 * - 位置上下文 (8维): 在家/办公/在途/其他
 * - 前台应用 (32维): App embedding
 * - UI 节点 (16维): 最近交互节点 embedding
 * - 最近事件 (24维): 最近 5 条事件 embedding
 * - 操作序列 (24维): 最近操作序列 embedding
 * - 当前实体 (8维): 实体 ID embedding
 * 
 * @author AI Native OS Team
 * @since A5
 */
class StateExtractor(
    private val context: Context,
    private val embeddingProvider: EmbeddingProvider
) {
    companion object {
        const val STATE_VECTOR_DIM = 128
        const val MAX_RECENT_EVENTS = 5
        const val MAX_RECENT_ACTIONS = 10
    }

    /**
     * 提取当前状态特征向量
     */
    fun extractFeatures(contextFrame: ContextFrame): StateFeatures {
        val calendar = Calendar.getInstance()
        
        return StateFeatures(
            timestamp = System.currentTimeMillis(),
            dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1, // 0-6
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),      // 0-23
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(
                Calendar.SATURDAY, Calendar.SUNDAY
            ),
            location = extractLocationContext(contextFrame),
            foregroundApp = contextFrame.foregroundApp ?: "",
            recentUiNode = contextFrame.lastInteractionNode,
            recentEvents = extractRecentEvents(contextFrame),
            recentActions = extractRecentActions(contextFrame),
            currentEntity = contextFrame.currentEntityId
        )
    }

    /**
     * 将特征转换为模型输入的浮点向量
     */
    fun featuresToVector(features: StateFeatures): FloatArray {
        val vector = FloatArray(STATE_VECTOR_DIM)
        var offset = 0

        // 1. 时间上下文 (16维)
        offset = encodeTimeContext(features, vector, offset)

        // 2. 位置上下文 (8维)
        offset = encodeLocationContext(features.location, vector, offset)

        // 3. 前台应用 (32维)
        offset = encodeApp(features.foregroundApp, vector, offset)

        // 4. UI 节点 (16维)
        offset = encodeUiNode(features.recentUiNode, vector, offset)

        // 5. 最近事件 (24维)
        offset = encodeRecentEvents(features.recentEvents, vector, offset)

        // 6. 操作序列 (24维)
        offset = encodeRecentActions(features.recentActions, vector, offset)

        // 7. 当前实体 (8维)
        offset = encodeEntity(features.currentEntity, vector, offset)

        return vector
    }

    // ==================== 私有方法 ====================

    private fun extractLocationContext(contextFrame: ContextFrame): LocationContext {
        // 从上下文推断粗位置语义
        // TODO: 集成位置服务和记忆服务的语义标签
        val locationHint = contextFrame.locationHint ?: return LocationContext.OTHER
        
        return when {
            locationHint.contains("home", ignoreCase = true) -> LocationContext.HOME
            locationHint.contains("office", ignoreCase = true) ||
            locationHint.contains("work", ignoreCase = true) -> LocationContext.OFFICE
            locationHint.contains("transit", ignoreCase = true) ||
            locationHint.contains("travel", ignoreCase = true) -> LocationContext.TRANSIT
            else -> LocationContext.OTHER
        }
    }

    private fun extractRecentEvents(contextFrame: ContextFrame): List<EventSummary> {
        // 从上下文获取最近事件
        return contextFrame.recentEvents?.take(MAX_RECENT_EVENTS) ?: emptyList()
    }

    private fun extractRecentActions(contextFrame: ContextFrame): List<ActionSummary> {
        // 从审计日志获取最近操作
        return contextFrame.recentActions?.take(MAX_RECENT_ACTIONS) ?: emptyList()
    }

    // ==================== 编码方法 ====================

    private fun encodeTimeContext(features: StateFeatures, vector: FloatArray, offset: Int): Int {
        var idx = offset
        
        // 星期 one-hot (7维)
        for (i in 0 until 7) {
            vector[idx++] = if (i == features.dayOfWeek) 1.0f else 0.0f
        }
        
        // 小时归一化 (1维)
        vector[idx++] = features.hourOfDay / 24.0f
        
        // 工作日标志 (1维)
        vector[idx++] = if (features.isWeekend) 0.0f else 1.0f
        
        // 时段分段 (4维): 清晨/上午/下午/晚上
        val timeSlot = when (features.hourOfDay) {
            in 0..5 -> 0    // 清晨
            in 6..11 -> 1   // 上午
            in 12..17 -> 2  // 下午
            else -> 3       // 晚上
        }
        for (i in 0 until 4) {
            vector[idx++] = if (i == timeSlot) 1.0f else 0.0f
        }
        
        // 填充到 16 维
        while (idx < offset + 16) {
            vector[idx++] = 0.0f
        }
        
        return idx
    }

    private fun encodeLocationContext(location: LocationContext, vector: FloatArray, offset: Int): Int {
        var idx = offset
        
        // One-hot 编码 (4维)
        LocationContext.values().forEachIndexed { i, loc ->
            vector[idx++] = if (loc == location) 1.0f else 0.0f
        }
        
        // 填充到 8 维
        while (idx < offset + 8) {
            vector[idx++] = 0.0f
        }
        
        return idx
    }

    private fun encodeApp(packageName: String, vector: FloatArray, offset: Int): Int {
        val embedding = embeddingProvider.getAppEmbedding(packageName)
        embedding.copyInto(vector, offset)
        return offset + 32
    }

    private fun encodeUiNode(nodeId: String?, vector: FloatArray, offset: Int): Int {
        if (nodeId == null) {
            // 空节点，填充零向量
            for (i in 0 until 16) {
                vector[offset + i] = 0.0f
            }
        } else {
            val embedding = embeddingProvider.getUiNodeEmbedding(nodeId)
            embedding.copyInto(vector, offset)
        }
        return offset + 16
    }

    private fun encodeRecentEvents(events: List<EventSummary>, vector: FloatArray, offset: Int): Int {
        var idx = offset
        
        // 每个事件编码为 4.8 维（取整为 5 维）
        // 5 个事件 × 5 维 = 25 维，但我们只用 24 维
        events.take(MAX_RECENT_EVENTS).forEachIndexed { i, event ->
            val eventTypeId = when (event.type) {
                "notification" -> 0
                "calendar" -> 1
                "message" -> 2
                "call" -> 3
                else -> 4
            }
            
            // 事件类型 one-hot (部分维度)
            vector[idx++] = if (eventTypeId == 0) 1.0f else 0.0f
            vector[idx++] = if (eventTypeId == 1) 1.0f else 0.0f
            vector[idx++] = if (eventTypeId == 2) 1.0f else 0.0f
            
            // 时间衰减因子
            val timeDelta = System.currentTimeMillis() - event.timestamp
            val decayFactor = Math.exp(-timeDelta / 3600000.0).toFloat() // 1小时衰减
            vector[idx++] = decayFactor
            
            if (idx >= offset + 24) break
        }
        
        // 填充剩余维度
        while (idx < offset + 24) {
            vector[idx++] = 0.0f
        }
        
        return idx
    }

    private fun encodeRecentActions(actions: List<ActionSummary>, vector: FloatArray, offset: Int): Int {
        var idx = offset
        
        // 最近 6 个操作，每个 4 维
        actions.take(6).forEach { action ->
            val actionTypeId = when (action.type) {
                "tap" -> 0
                "type" -> 1
                "swipe" -> 2
                "agent_action" -> 3
                else -> 4
            }
            
            // 动作类型 one-hot (部分)
            vector[idx++] = if (actionTypeId == 0) 1.0f else 0.0f
            vector[idx++] = if (actionTypeId == 1) 1.0f else 0.0f
            vector[idx++] = if (actionTypeId == 2) 1.0f else 0.0f
            
            // 时间衰减
            val timeDelta = System.currentTimeMillis() - action.timestamp
            val decayFactor = Math.exp(-timeDelta / 300000.0).toFloat() // 5分钟衰减
            vector[idx++] = decayFactor
            
            if (idx >= offset + 24) break
        }
        
        // 填充剩余维度
        while (idx < offset + 24) {
            vector[idx++] = 0.0f
        }
        
        return idx
    }

    private fun encodeEntity(entityId: String?, vector: FloatArray, offset: Int): Int {
        if (entityId == null) {
            for (i in 0 until 8) {
                vector[offset + i] = 0.0f
            }
        } else {
            val embedding = embeddingProvider.getEntityEmbedding(entityId)
            embedding.copyInto(vector, offset, 0, 8)
        }
        return offset + 8
    }
}

/**
 * 状态特征数据类
 */
data class StateFeatures(
    val timestamp: Long,
    val dayOfWeek: Int,           // 0-6 (周一到周日)
    val hourOfDay: Int,           // 0-23
    val isWeekend: Boolean,
    val location: LocationContext,
    val foregroundApp: String,
    val recentUiNode: String?,
    val recentEvents: List<EventSummary>,
    val recentActions: List<ActionSummary>,
    val currentEntity: String?
)

/**
 * 位置语义枚举
 */
enum class LocationContext {
    HOME,      // 在家
    OFFICE,    // 办公
    TRANSIT,   // 在途
    OTHER      // 其他
}

/**
 * 事件摘要
 */
data class EventSummary(
    val type: String,      // notification/calendar/message/call
    val appSource: String, // 来源 App
    val timestamp: Long
)

/**
 * 操作摘要
 */
data class ActionSummary(
    val type: String,      // tap/type/swipe/agent_action
    val target: String?,   // 目标节点或动作名
    val timestamp: Long
)

/**
 * Embedding 提供者
 * 
 * 提供 App、UI 节点、实体的向量 embedding
 */
class EmbeddingProvider(private val context: Context) {
    // TODO: 从预训练 embedding 文件加载
    // 当前使用简单的哈希编码作为占位
    
    fun getAppEmbedding(packageName: String): FloatArray {
        // 返回 32 维 embedding
        val embedding = FloatArray(32)
        val hash = packageName.hashCode()
        for (i in embedding.indices) {
            embedding[i] = ((hash shr i) and 1).toFloat() * 0.5f - 0.25f
        }
        return embedding
    }
    
    fun getUiNodeEmbedding(nodeId: String): FloatArray {
        // 返回 16 维 embedding
        val embedding = FloatArray(16)
        val hash = nodeId.hashCode()
        for (i in embedding.indices) {
            embedding[i] = ((hash shr i) and 1).toFloat() * 0.5f - 0.25f
        }
        return embedding
    }
    
    fun getEntityEmbedding(entityId: String): FloatArray {
        // 返回 8 维 embedding
        val embedding = FloatArray(8)
        val hash = entityId.hashCode()
        for (i in embedding.indices) {
            embedding[i] = ((hash shr i) and 1).toFloat() * 0.5f - 0.25f
        }
        return embedding
    }
}

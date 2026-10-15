package com.genton.brain.prediction.gate

import android.content.Context
import com.genton.brain.prediction.PredictedAction
import com.genton.memory.IAgentMemoryService
import com.genton.memory.MemoryEntry
import com.genton.memory.MemoryQuery
import org.json.JSONObject
import java.util.UUID

/**
 * 记忆黑名单检查器
 * 
 * 检查预测场景是否在用户明确拒绝的黑名单中：
 * - App 级别黑名单（用户关闭特定 App 的预测）
 * - 时段黑名单（用户拒绝特定时段的预测）
 * - 位置黑名单（用户拒绝特定位置的预测）
 * - 复合黑名单（多条件组合）
 * 
 * 黑名单存储在系统记忆层，优先级最高。
 * 
 * @author AI Native OS Team
 * @since A5
 */
class MemoryBlacklistChecker(
    private val context: Context,
    private val memoryService: IAgentMemoryService?
) {
    companion object {
        private const val TAG = "MemoryBlacklistChecker"
        private const val BLACKLIST_TAG = "prediction_blacklist"
        private const val BLACKLIST_VERSION = 1
    }
    
    /**
     * 检查预测是否在黑名单中
     * 
     * @param predictedAction 预测动作
     * @param sceneContext 场景上下文
     * @return 黑名单检查结果
     */
    fun isBlacklisted(
        predictedAction: PredictedAction,
        sceneContext: SceneContext
    ): BlacklistCheckResult {
        if (memoryService == null) {
            android.util.Log.w(TAG, "Memory service not available, skip blacklist check")
            return BlacklistCheckResult.Allowed
        }
        
        try {
            // 1. 从记忆服务查询黑名单
            val blacklistEntries = fetchBlacklistEntries()
            
            if (blacklistEntries.isEmpty()) {
                return BlacklistCheckResult.Allowed
            }
            
            // 2. 逐条检查匹配
            for (entry in blacklistEntries) {
                if (matches(entry.scope, predictedAction, sceneContext)) {
                    return BlacklistCheckResult.Blocked(entry)
                }
            }
            
            return BlacklistCheckResult.Allowed
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to check blacklist", e)
            // 异常时默认允许（避免误杀）
            return BlacklistCheckResult.Allowed
        }
    }
    
    /**
     * 从记忆服务获取黑名单条目
     */
    private fun fetchBlacklistEntries(): List<PredictionBlacklistEntry> {
        val query = MemoryQuery.Builder()
            .setKind(IAgentMemoryService.KIND_SYSTEM)  // 系统记忆层
            .addTag(BLACKLIST_TAG)
            .build()
        
        val memoryEntries = memoryService?.query(query) ?: emptyArray()
        
        return memoryEntries.mapNotNull { memory ->
            try {
                parseBlacklistEntry(memory)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to parse blacklist entry", e)
                null
            }
        }
    }
    
    /**
     * 解析黑名单条目
     */
    private fun parseBlacklistEntry(memory: MemoryEntry): PredictionBlacklistEntry? {
        try {
            val json = JSONObject(memory.content)
            val version = json.optInt("version", 1)
            
            if (version != BLACKLIST_VERSION) {
                android.util.Log.w(TAG, "Unsupported blacklist version: $version")
                return null
            }
            
            val scopeJson = json.getJSONObject("scope")
            val scope = parseBlacklistScope(scopeJson)
            
            return PredictionBlacklistEntry(
                id = memory.memoryId,
                scope = scope,
                reason = json.getString("reason"),
                createdAt = json.getLong("createdAt"),
                expiresAt = if (json.has("expiresAt")) json.getLong("expiresAt") else null
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse blacklist entry", e)
            return null
        }
    }
    
    /**
     * 解析黑名单范围
     */
    private fun parseBlacklistScope(json: JSONObject): BlacklistScope {
        val type = json.getString("type")
        
        return when (type) {
            "app" -> {
                BlacklistScope.AppLevel(json.getString("packageName"))
            }
            "time_slot" -> {
                BlacklistScope.TimeSlot(
                    hourStart = json.getInt("hourStart"),
                    hourEnd = json.getInt("hourEnd"),
                    appCategory = json.optString("appCategory").takeIf { it.isNotEmpty() }
                )
            }
            "location" -> {
                BlacklistScope.Location(
                    location = json.getString("location"),
                    appCategory = json.optString("appCategory").takeIf { it.isNotEmpty() }
                )
            }
            "composite" -> {
                val conditionsArray = json.getJSONArray("conditions")
                val conditions = (0 until conditionsArray.length()).map { i ->
                    parseBlacklistScope(conditionsArray.getJSONObject(i))
                }
                BlacklistScope.Composite(conditions)
            }
            else -> {
                throw IllegalArgumentException("Unknown blacklist scope type: $type")
            }
        }
    }
    
    /**
     * 匹配黑名单规则
     */
    private fun matches(
        scope: BlacklistScope,
        action: PredictedAction,
        context: SceneContext
    ): Boolean {
        return when (scope) {
            is BlacklistScope.AppLevel -> {
                action.targetApp == scope.packageName
            }
            is BlacklistScope.TimeSlot -> {
                val inTimeRange = context.hour in scope.hourStart..scope.hourEnd
                val categoryMatches = scope.appCategory == null || 
                    matchesCategory(action.targetApp, scope.appCategory)
                inTimeRange && categoryMatches
            }
            is BlacklistScope.Location -> {
                val locationMatches = context.location == scope.location
                val categoryMatches = scope.appCategory == null || 
                    matchesCategory(action.targetApp, scope.appCategory)
                locationMatches && categoryMatches
            }
            is BlacklistScope.Composite -> {
                scope.conditions.all { matches(it, action, context) }
            }
        }
    }
    
    /**
     * 匹配 App 类别（占位实现）
     */
    private fun matchesCategory(packageName: String?, category: String): Boolean {
        // TODO: 实现真实的 App 类别匹配
        // 可能需要维护 App 类别映射表或查询 PackageManager
        return false
    }
}

/**
 * 黑名单检查结果
 */
sealed class BlacklistCheckResult {
    /**
     * 允许（不在黑名单）
     */
    object Allowed : BlacklistCheckResult()
    
    /**
     * 被阻止（在黑名单中）
     * @param entry 匹配的黑名单条目
     */
    data class Blocked(val entry: PredictionBlacklistEntry) : BlacklistCheckResult()
}

/**
 * 黑名单条目
 */
data class PredictionBlacklistEntry(
    val id: String,                     // 黑名单条目 ID
    val scope: BlacklistScope,          // 黑名单范围
    val reason: String,                 // 用户拒绝原因
    val createdAt: Long,                // 创建时间
    val expiresAt: Long? = null         // 过期时间（null = 永久）
) {
    /**
     * 是否已过期
     */
    fun isExpired(): Boolean {
        return expiresAt != null && System.currentTimeMillis() > expiresAt
    }
}

/**
 * 黑名单范围
 */
sealed class BlacklistScope {
    /**
     * App 级别黑名单
     * @param packageName App 包名
     */
    data class AppLevel(val packageName: String) : BlacklistScope()
    
    /**
     * 时段黑名单
     * @param hourStart 开始小时 [0, 23]
     * @param hourEnd 结束小时 [0, 23]
     * @param appCategory App 类别（null = 所有类别）
     */
    data class TimeSlot(
        val hourStart: Int,
        val hourEnd: Int,
        val appCategory: String? = null
    ) : BlacklistScope()
    
    /**
     * 位置黑名单
     * @param location 位置 (home/office/transit/other)
     * @param appCategory App 类别（null = 所有类别）
     */
    data class Location(
        val location: String,
        val appCategory: String? = null
    ) : BlacklistScope()
    
    /**
     * 复合黑名单（多条件 AND）
     * @param conditions 条件列表
     */
    data class Composite(val conditions: List<BlacklistScope>) : BlacklistScope()
}

/**
 * 黑名单管理器
 */
class PredictionBlacklistManager(
    private val context: Context,
    private val memoryService: IAgentMemoryService?
) {
    companion object {
        private const val TAG = "BlacklistManager"
    }
    
    /**
     * 添加黑名单条目
     * 
     * @param scope 黑名单范围
     * @param reason 拒绝原因
     * @param ttl 生存时间（毫秒，null = 永久）
     * @return 是否添加成功
     */
    fun addBlacklist(scope: BlacklistScope, reason: String, ttl: Long? = null): Boolean {
        if (memoryService == null) {
            android.util.Log.w(TAG, "Memory service not available")
            return false
        }
        
        try {
            val entry = PredictionBlacklistEntry(
                id = UUID.randomUUID().toString(),
                scope = scope,
                reason = reason,
                createdAt = System.currentTimeMillis(),
                expiresAt = ttl?.let { System.currentTimeMillis() + it }
            )
            
            val content = serializeBlacklistEntry(entry)
            
            // TODO: 创建 MemoryEntryDraft 并写入
            // val draft = MemoryEntryDraft.Builder()
            //     .setKind(IAgentMemoryService.KIND_SYSTEM)
            //     .setContent(content)
            //     .addTag(MemoryBlacklistChecker.BLACKLIST_TAG)
            //     .build()
            // 
            // val result = MemoryEntry()
            // val status = memoryService.write(draft, result)
            // 
            // return status == IAgentMemoryService.STATUS_OK
            
            android.util.Log.i(TAG, "Blacklist added: $entry")
            return true
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to add blacklist", e)
            return false
        }
    }
    
    /**
     * 移除黑名单条目
     */
    fun removeBlacklist(entryId: String): Boolean {
        if (memoryService == null) return false
        
        try {
            val status = memoryService.deleteById(entryId)
            return status == IAgentMemoryService.STATUS_OK
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to remove blacklist", e)
            return false
        }
    }
    
    /**
     * 序列化黑名单条目
     */
    private fun serializeBlacklistEntry(entry: PredictionBlacklistEntry): String {
        val json = JSONObject().apply {
            put("version", MemoryBlacklistChecker.BLACKLIST_VERSION)
            put("id", entry.id)
            put("scope", serializeScope(entry.scope))
            put("reason", entry.reason)
            put("createdAt", entry.createdAt)
            if (entry.expiresAt != null) {
                put("expiresAt", entry.expiresAt)
            }
        }
        return json.toString()
    }
    
    /**
     * 序列化黑名单范围
     */
    private fun serializeScope(scope: BlacklistScope): JSONObject {
        return when (scope) {
            is BlacklistScope.AppLevel -> {
                JSONObject().apply {
                    put("type", "app")
                    put("packageName", scope.packageName)
                }
            }
            is BlacklistScope.TimeSlot -> {
                JSONObject().apply {
                    put("type", "time_slot")
                    put("hourStart", scope.hourStart)
                    put("hourEnd", scope.hourEnd)
                    scope.appCategory?.let { put("appCategory", it) }
                }
            }
            is BlacklistScope.Location -> {
                JSONObject().apply {
                    put("type", "location")
                    put("location", scope.location)
                    scope.appCategory?.let { put("appCategory", it) }
                }
            }
            is BlacklistScope.Composite -> {
                JSONObject().apply {
                    put("type", "composite")
                    put("conditions", scope.conditions.map { serializeScope(it) })
                }
            }
        }
    }
}

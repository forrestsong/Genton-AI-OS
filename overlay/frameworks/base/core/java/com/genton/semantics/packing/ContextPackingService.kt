// ContextPackingService.kt
// C3.3 上下文预打包策略：子图半径/Token 预算裁剪/ACB checkpoint 联动
package com.genton.semantics.packing

import android.content.Context
import android.util.Log
import com.genton.semantics.ontology.*
import com.genton.semantics.extraction.EntityExtractionPipeline
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 上下文打包服务
 * 
 * 核心职责：
 * 1. 从种子实体出发，按子图半径遍历关系图
 * 2. 根据 Token 预算裁剪实体和记忆
 * 3. 生成 ContextPack（包含实体、关系边、相关记忆）
 * 4. 与 ACB checkpoint 联动（保存 packId）
 * 
 * 性能目标：
 * - packContext P95 ≤ 100ms
 * - 支持缓存（实体变更时失效）
 * 
 * 安全保证：
 * - 低置信度实体（< 0.6）不参与打包
 * - 调用方授权过滤（visibility rules）
 * - Provenance 标注（防长期记忆投毒）
 */
class ContextPackingService(
    private val context: Context,
    private val extractionPipeline: EntityExtractionPipeline
) {
    companion object {
        private const val TAG = "ContextPackingService"
        
        // 默认配置
        private const val DEFAULT_RELATION_DEPTH = 2          // 默认子图半径
        private const val DEFAULT_MAX_MEMORIES = 20           // 默认记忆数量上限
        private const val DEFAULT_MAX_TOKENS = 4000           // 默认 Token 预算
        
        // Token 估算权重（经验值）
        private const val TOKENS_PER_ENTITY = 50              // 每个实体约 50 tokens
        private const val TOKENS_PER_RELATION = 20            // 每条关系边约 20 tokens
        private const val TOKENS_PER_MEMORY = 100             // 每条记忆约 100 tokens
        private const val TOKENS_SYSTEM_MEMORY_SUMMARY = 200  // 系统记忆页摘要约 200 tokens
        
        // 缓存配置
        private const val CACHE_TTL_MS = 60000L               // 缓存有效期 1 分钟
    }
    
    // packId 生成器
    private val packIdGenerator = AtomicLong(System.currentTimeMillis())
    
    // 缓存（packId -> ContextPack）
    private val packCache = ConcurrentHashMap<Long, CachedPack>()
    
    /**
     * 打包上下文（主入口）
     * 
     * @param request 打包请求
     * @return ContextPack
     */
    fun packContext(request: ContextPackRequest): ContextPack {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 检查缓存
            val cachedPack = checkCache(request)
            if (cachedPack != null) {
                Log.d(TAG, "Cache hit for pack request")
                return cachedPack
            }
            
            // 2. 获取种子实体
            val seedEntities = getSeedEntities(request.seedEntityIds)
            if (seedEntities.isEmpty()) {
                Log.w(TAG, "No seed entities found")
                return createEmptyPack(request)
            }
            
            // 3. 遍历子图（BFS，按 relationDepth 控制半径）
            val subgraph = traverseSubgraph(
                seedEntities = seedEntities,
                maxDepth = request.relationDepth
            )
            
            // 4. 裁剪子图（根据 Token 预算）
            val trimmedSubgraph = trimByTokenBudget(
                subgraph = subgraph,
                maxTokens = request.maxTokensHint ?: DEFAULT_MAX_TOKENS,
                taskDescription = request.taskDescription
            )
            
            // 5. 查询相关记忆
            val relevantMemories = queryRelevantMemories(
                entityIds = trimmedSubgraph.entities.map { it.entityId },
                maxMemories = request.maxMemories
            )
            
            // 6. 获取系统记忆页摘要
            val systemMemorySummary = if (request.includeSystemMemoryPage) {
                getSystemMemorySummary()
            } else {
                ""
            }
            
            // 7. 估算 Token 使用量
            val estimatedTokens = estimateTokens(
                entityCount = trimmedSubgraph.entities.size,
                relationCount = trimmedSubgraph.edges.size,
                memoryCount = relevantMemories.size,
                hasSystemMemory = request.includeSystemMemoryPage
            )
            
            // 8. 生成 packId
            val packId = packIdGenerator.incrementAndGet()
            
            // 9. 构建 ContextPack
            val pack = ContextPack(
                packId = packId,
                builtAtMs = System.currentTimeMillis(),
                entities = trimmedSubgraph.entities,
                edges = trimmedSubgraph.edges,
                relevantMemories = relevantMemories,
                systemMemorySummary = systemMemorySummary,
                estimatedTokens = estimatedTokens,
                intentId = request.intentId
            )
            
            // 10. 缓存
            cachePack(pack, request)
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Context packed: packId=$packId, entities=${pack.entities.size}, " +
                    "edges=${pack.edges.size}, memories=${pack.relevantMemories.size}, " +
                    "tokens=${pack.estimatedTokens}, elapsed=${elapsed}ms")
            
            // 验收：P95 ≤ 100ms
            if (elapsed > 100) {
                Log.w(TAG, "Context packing exceeded 100ms target: ${elapsed}ms")
            }
            
            return pack
            
        } catch (e: Exception) {
            Log.e(TAG, "Error packing context", e)
            return createEmptyPack(request)
        }
    }
    
    /**
     * 加载已打包的上下文（用于 checkpoint 恢复）
     * 
     * @param packId pack ID
     * @return ContextPack，不存在返回 null
     */
    fun loadContextPack(packId: Long): ContextPack? {
        val cached = packCache[packId]
        if (cached != null && !cached.isExpired()) {
            Log.d(TAG, "Loaded pack from cache: packId=$packId")
            return cached.pack
        }
        
        // TODO: 从持久化存储加载
        Log.w(TAG, "Pack not found in cache: packId=$packId")
        return null
    }
    
    /**
     * 失效缓存（实体变更时调用）
     * 
     * @param entityId 变更的实体 ID
     */
    fun invalidateCache(entityId: EntityId) {
        // 遍历缓存，移除包含该实体的所有 pack
        val iterator = packCache.entries.iterator()
        var invalidatedCount = 0
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pack = entry.value.pack
            
            if (pack.entities.any { it.entityId == entityId }) {
                iterator.remove()
                invalidatedCount++
            }
        }
        
        if (invalidatedCount > 0) {
            Log.d(TAG, "Invalidated $invalidatedCount cached packs due to entity change: ${entityId.id}")
        }
    }
    
    /**
     * 清理过期缓存
     */
    fun cleanupExpiredCache() {
        val iterator = packCache.entries.iterator()
        var cleanedCount = 0
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
                cleanedCount++
            }
        }
        
        if (cleanedCount > 0) {
            Log.d(TAG, "Cleaned up $cleanedCount expired cached packs")
        }
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 检查缓存
     */
    private fun checkCache(request: ContextPackRequest): ContextPack? {
        // 根据请求参数生成缓存键
        val cacheKey = generateCacheKey(request)
        
        // 查找缓存
        for ((packId, cached) in packCache) {
            if (cached.requestKey == cacheKey && !cached.isExpired()) {
                return cached.pack
            }
        }
        
        return null
    }
    
    /**
     * 生成缓存键
     */
    private fun generateCacheKey(request: ContextPackRequest): String {
        return "${request.seedEntityIds.sorted().joinToString(",")}_" +
                "${request.relationDepth}_${request.maxMemories}_${request.maxTokensHint}"
    }
    
    /**
     * 获取种子实体
     */
    private fun getSeedEntities(entityIds: List<EntityId>): List<Entity> {
        // 调用 C3.2 抽取管线获取可打包实体（过滤低置信度）
        return extractionPipeline.getPackableEntities(entityIds)
    }
    
    /**
     * 遍历子图（BFS）
     * 
     * @param seedEntities 种子实体列表
     * @param maxDepth 最大深度（子图半径）
     * @return 子图（实体 + 关系边）
     */
    private fun traverseSubgraph(
        seedEntities: List<Entity>,
        maxDepth: Int
    ): Subgraph {
        val entities = mutableMapOf<EntityId, Entity>()
        val edges = mutableListOf<RelationEdge>()
        val visited = mutableSetOf<EntityId>()
        
        // 初始化：种子实体
        val queue = ArrayDeque<Pair<Entity, Int>>()  // (entity, depth)
        for (entity in seedEntities) {
            entities[entity.entityId] = entity
            visited.add(entity.entityId)
            queue.addLast(entity to 0)
        }
        
        // BFS 遍历
        while (queue.isNotEmpty()) {
            val (currentEntity, depth) = queue.removeFirst()
            
            // 达到最大深度，停止扩展
            if (depth >= maxDepth) {
                continue
            }
            
            // 查询当前实体的关系边
            val relatedEdges = queryRelationEdges(currentEntity.entityId)
            
            for (edge in relatedEdges) {
                edges.add(edge)
                
                // 获取目标实体
                val targetEntityId = edge.toEntityId
                if (targetEntityId in visited) {
                    continue
                }
                
                // 加载目标实体
                val targetEntity = loadEntity(targetEntityId) ?: continue
                
                // 检查置信度（低置信度不参与打包）
                if (targetEntity.confidence < 0.6f) {
                    continue
                }
                
                // 添加到子图
                entities[targetEntityId] = targetEntity
                visited.add(targetEntityId)
                queue.addLast(targetEntity to depth + 1)
            }
        }
        
        return Subgraph(
            entities = entities.values.toList(),
            edges = edges
        )
    }
    
    /**
     * 根据 Token 预算裁剪子图
     * 
     * 裁剪策略：
     * 1. 保留种子实体（核心实体）
     * 2. 按实体置信度排序
     * 3. 按 Token 预算逐个添加，直到达到预算上限
     */
    private fun trimByTokenBudget(
        subgraph: Subgraph,
        maxTokens: Int,
        taskDescription: String?
    ): Subgraph {
        // 如果子图已经在预算内，直接返回
        val currentTokens = estimateTokens(
            entityCount = subgraph.entities.size,
            relationCount = subgraph.edges.size,
            memoryCount = 0,  // 记忆还未加载
            hasSystemMemory = false
        )
        
        if (currentTokens <= maxTokens) {
            return subgraph
        }
        
        Log.d(TAG, "Trimming subgraph: current=$currentTokens, budget=$maxTokens")
        
        // 按置信度排序（保留高置信度实体）
        val sortedEntities = subgraph.entities.sortedByDescending { it.confidence }
        
        // 逐个添加实体，直到达到预算
        val trimmedEntities = mutableListOf<Entity>()
        val trimmedEntityIds = mutableSetOf<EntityId>()
        var accumulatedTokens = 0
        
        for (entity in sortedEntities) {
            val entityTokens = TOKENS_PER_ENTITY
            if (accumulatedTokens + entityTokens > maxTokens) {
                break
            }
            
            trimmedEntities.add(entity)
            trimmedEntityIds.add(entity.entityId)
            accumulatedTokens += entityTokens
        }
        
        // 过滤关系边（只保留两端实体都在裁剪后子图中的边）
        val trimmedEdges = subgraph.edges.filter { edge ->
            edge.fromEntityId in trimmedEntityIds && edge.toEntityId in trimmedEntityIds
        }
        
        Log.d(TAG, "Subgraph trimmed: entities=${subgraph.entities.size}->${trimmedEntities.size}, " +
                "edges=${subgraph.edges.size}->${trimmedEdges.size}")
        
        return Subgraph(
            entities = trimmedEntities,
            edges = trimmedEdges
        )
    }
    
    /**
     * 查询相关记忆
     * 
     * TODO: 集成记忆服务（A5.1）
     */
    private fun queryRelevantMemories(
        entityIds: List<EntityId>,
        maxMemories: Int
    ): List<MemoryEntry> {
        // TODO: 调用 IAgentMemoryService.queryMemories()
        // 根据实体 ID 查询相关记忆（按相关性排序，取 top-N）
        
        return emptyList()
    }
    
    /**
     * 获取系统记忆页摘要
     * 
     * TODO: 集成记忆服务（A5.1）
     */
    private fun getSystemMemorySummary(): String {
        // TODO: 调用 IAgentMemoryService.getSystemMemoryPage()
        // 返回常驻系统记忆页（身份/偏好/设备信息）的摘要
        
        return ""
    }
    
    /**
     * 查询关系边
     * 
     * TODO: 实现关系存储和查询（A3 阶段）
     */
    private fun queryRelationEdges(entityId: EntityId): List<RelationEdge> {
        // TODO: 从关系图数据库查询该实体的所有出边
        
        return emptyList()
    }
    
    /**
     * 加载实体
     * 
     * TODO: 集成实体查询服务
     */
    private fun loadEntity(entityId: EntityId): Entity? {
        // TODO: 调用 IEntityQueryService.getEntity()
        
        return null
    }
    
    /**
     * 估算 Token 使用量
     */
    private fun estimateTokens(
        entityCount: Int,
        relationCount: Int,
        memoryCount: Int,
        hasSystemMemory: Boolean
    ): Int {
        var total = 0
        total += entityCount * TOKENS_PER_ENTITY
        total += relationCount * TOKENS_PER_RELATION
        total += memoryCount * TOKENS_PER_MEMORY
        if (hasSystemMemory) {
            total += TOKENS_SYSTEM_MEMORY_SUMMARY
        }
        return total
    }
    
    /**
     * 缓存 pack
     */
    private fun cachePack(pack: ContextPack, request: ContextPackRequest) {
        val cacheKey = generateCacheKey(request)
        val cached = CachedPack(
            pack = pack,
            requestKey = cacheKey,
            cachedAtMs = System.currentTimeMillis()
        )
        packCache[pack.packId] = cached
    }
    
    /**
     * 创建空 pack
     */
    private fun createEmptyPack(request: ContextPackRequest): ContextPack {
        return ContextPack(
            packId = packIdGenerator.incrementAndGet(),
            builtAtMs = System.currentTimeMillis(),
            entities = emptyList(),
            edges = emptyList(),
            relevantMemories = emptyList(),
            systemMemorySummary = "",
            estimatedTokens = 0,
            intentId = request.intentId
        )
    }
}

/**
 * 上下文打包请求
 */
data class ContextPackRequest(
    val seedEntityIds: List<EntityId>,         // 任务核心实体（如"客户A + 项目X"）
    val relationDepth: Int = 2,                // 子图半径（上限保护）
    val maxMemories: Int = 20,                 // 相关记忆断言上限
    val includeSystemMemoryPage: Boolean = true, // 常驻系统记忆页（身份/偏好/设备）
    val maxTokensHint: Int? = null,            // 上下文预算（供打包器裁剪）
    val taskDescription: String? = null,       // 任务一句话（指导子图裁剪）→ 审计
    val intentId: String? = null               // sourceIntentId（授权与审计关联）
)

/**
 * 上下文打包结果
 */
data class ContextPack(
    val packId: Long,                          // pack ID（用于 checkpoint 关联）
    val builtAtMs: Long,                       // 打包时间戳
    val entities: List<Entity>,                // 实体列表（已按授权过滤）
    val edges: List<RelationEdge>,             // 关系边列表
    val relevantMemories: List<MemoryEntry>,   // 相关记忆（脱敏版本）
    val systemMemorySummary: String,           // 系统记忆页摘要（掩码后）
    val estimatedTokens: Int,                  // 估算 Token 占用
    val intentId: String?                      // 关联的意图 ID
)

/**
 * 子图（实体 + 关系边）
 */
data class Subgraph(
    val entities: List<Entity>,
    val edges: List<RelationEdge>
)

/**
 * 缓存的 pack
 */
data class CachedPack(
    val pack: ContextPack,
    val requestKey: String,                    // 请求缓存键
    val cachedAtMs: Long                       // 缓存时间戳
) {
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - cachedAtMs > ContextPackingService.CACHE_TTL_MS
    }
}

/**
 * 记忆条目（占位符，等待 A5.1 集成）
 */
data class MemoryEntry(
    val memoryId: String,
    val content: String,
    val entityIds: List<EntityId>,             // 关联的实体 ID
    val provenance: String,                    // 来源标注
    val confidence: Float,
    val createdAtMs: Long
)

/**
 * 关系边（占位符，等待 C3.1 完整实现）
 */
data class RelationEdge(
    val fromEntityId: EntityId,
    val relationType: Int,                     // REL_* 常量
    val toEntityId: EntityId,
    val weight: Float,                         // 0~1（共现/置信加权）
    val sourceRef: String,                     // 证据来源
    val observedAtMs: Long
)

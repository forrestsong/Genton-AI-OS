package com.genton.semantics.packing

import android.util.Log
import com.genton.semantics.ontology.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * C3.3 上下文预打包策略
 * 
 * 职责：
 * 1. packContext 子图半径裁剪（N 跳关系内的实体）
 * 2. Token 预算裁剪规则（根据 LLM 上下文窗口限制）
 * 3. ACB checkpoint 联动（会话恢复时从 checkpoint 加载打包图）
 * 4. 置信度过滤（confidence <0.6 不打包）
 * 5. 优先级排序（用户偏好 > 任务相关 > 背景知识）
 * 
 * 设计原则（D6 §4、D3 §5）：
 * - 子图裁剪：以种子实体为中心，N 跳关系内构建子图
 * - Token 预算：每个实体平均 50 tokens，关系 20 tokens，预算内优先级排序
 * - 置信度门槛：只打包 confidence ≥0.6 的实体（entity.canPack()）
 * - 会话恢复：entryMode=RESUME 时从 checkpoint 恢复打包图（A3 期增强）
 * 
 * 依赖关系：
 * - 前置：C3.1 本体模型 v1 schema（Entity/Relation/EntityGraph）
 * - 消费：B3.1 会话服务（工作记忆恢复）、A4.3 ACB checkpoint
 * 
 * 版本：v1（A3 期）
 * 
 * @since A3
 */
object ContextPackingStrategy {
    private const val TAG = "ContextPackingStrategy"
    
    // 预算配置
    private const val DEFAULT_TOKEN_BUDGET = 2000          // 默认上下文预算（tokens）
    private const val AVG_TOKENS_PER_ENTITY = 50           // 每个实体平均 token 数
    private const val AVG_TOKENS_PER_RELATION = 20         // 每个关系平均 token 数
    
    // 子图配置
    private const val DEFAULT_HOP_RADIUS = 2               // 默认跳数半径
    private const val MAX_HOP_RADIUS = 3                   // 最大跳数半径
    
    // 置信度阈值
    private const val CONFIDENCE_PACK_THRESHOLD = 0.6f     // 可打包阈值（对齐 C3.2）
    
    /**
     * 打包配置
     */
    data class PackingConfig(
        val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
        val hopRadius: Int = DEFAULT_HOP_RADIUS,
        val includeRelations: Boolean = true,
        val priorityMode: PriorityMode = PriorityMode.TASK_RELEVANT
    )
    
    /**
     * 优先级模式
     */
    enum class PriorityMode {
        TASK_RELEVANT,      // 任务相关优先（默认）
        USER_PREFERENCE,    // 用户偏好优先
        CHRONOLOGICAL,      // 时间顺序（最新优先）
        CONFIDENCE_FIRST    // 置信度优先
    }
    
    /**
     * 打包结果
     */
    data class PackingResult(
        val graph: EntityGraph,
        val tokenEstimate: Int,
        val entitiesPacked: Int,
        val relationsPacked: Int,
        val entitiesFiltered: Int,      // 因置信度/预算过滤的实体数
        val context: String              // JSON 格式上下文
    )
    
    /**
     * 核心打包函数：从种子实体开始构建子图
     * 
     * @param seedEntityIds 种子实体 ID 列表（任务相关实体）
     * @param fullGraph 完整实体图（从 A5.4 查询获得）
     * @param config 打包配置
     * @return 打包结果
     */
    fun packContext(
        seedEntityIds: List<EntityId>,
        fullGraph: EntityGraph,
        config: PackingConfig = PackingConfig()
    ): PackingResult {
        Log.d(TAG, "Packing context: seeds=${seedEntityIds.size}, fullGraph=${fullGraph.entities.size} entities")
        
        // 1. 子图裁剪（N 跳关系内）
        val subgraph = extractSubgraph(seedEntityIds, fullGraph, config.hopRadius)
        
        // 2. 置信度过滤
        val filteredEntities = subgraph.entities.filter { it.canPack() }
        val entitiesFiltered = subgraph.entities.size - filteredEntities.size
        
        Log.d(TAG, "After confidence filter: ${filteredEntities.size} entities (filtered $entitiesFiltered)")
        
        // 3. 优先级排序
        val sortedEntities = prioritizeEntities(filteredEntities, config.priorityMode)
        
        // 4. Token 预算裁剪
        val budgetedResult = applyTokenBudget(
            sortedEntities,
            subgraph.relations,
            config.tokenBudget,
            config.includeRelations
        )
        
        // 5. 构造打包图
        val packedGraph = EntityGraph(
            entities = budgetedResult.entities,
            relations = budgetedResult.relations,
            queriedAtMs = System.currentTimeMillis()
        )
        
        // 6. 序列化为 JSON 上下文
        val contextJson = serializeToContext(packedGraph)
        
        return PackingResult(
            graph = packedGraph,
            tokenEstimate = budgetedResult.tokenEstimate,
            entitiesPacked = budgetedResult.entities.size,
            relationsPacked = budgetedResult.relations.size,
            entitiesFiltered = entitiesFiltered + (sortedEntities.size - budgetedResult.entities.size),
            context = contextJson
        )
    }
    
    /**
     * 从 ACB checkpoint 恢复打包上下文（A3 期增强）
     * 
     * @param checkpointPackId checkpoint 包 ID
     * @param acbService ACB 运行时服务（A4.1）
     * @return 恢复的打包图，null 表示恢复失败
     */
    fun restoreFromCheckpoint(
        checkpointPackId: String,
        acbService: IAgentRuntimeService?
    ): EntityGraph? {
        if (acbService == null) {
            Log.w(TAG, "[Mock] Cannot restore from checkpoint without ACB service")
            return null
        }
        
        // TODO A3: 调用 ACB 服务读取 checkpoint
        // val checkpointData = acbService.loadCheckpoint(checkpointPackId)
        // val graphJson = checkpointData.getString("packed_context")
        // return deserializeFromContext(graphJson)
        
        Log.d(TAG, "[Mock] Restore from checkpoint: $checkpointPackId")
        return null
    }
    
    /**
     * 保存打包上下文到 ACB checkpoint（A3 期增强）
     * 
     * @param graph 打包图
     * @param checkpointPackId checkpoint 包 ID
     * @param acbService ACB 运行时服务
     */
    fun saveToCheckpoint(
        graph: EntityGraph,
        checkpointPackId: String,
        acbService: IAgentRuntimeService?
    ) {
        if (acbService == null) {
            Log.w(TAG, "[Mock] Cannot save to checkpoint without ACB service")
            return
        }
        
        val contextJson = serializeToContext(graph)
        
        // TODO A3: 调用 ACB 服务写入 checkpoint
        // val checkpointData = JSONObject().apply {
        //     put("packed_context", contextJson)
        //     put("timestamp_ms", System.currentTimeMillis())
        // }
        // acbService.saveCheckpoint(checkpointPackId, checkpointData)
        
        Log.d(TAG, "[Mock] Save to checkpoint: $checkpointPackId (${graph.entities.size} entities)")
    }
    
    /**
     * 提取子图（N 跳关系内）
     */
    private fun extractSubgraph(
        seedEntityIds: List<EntityId>,
        fullGraph: EntityGraph,
        hopRadius: Int
    ): EntityGraph {
        val visited = mutableSetOf<EntityId>()
        val subgraphEntities = mutableListOf<Entity>()
        val subgraphRelations = mutableListOf<Relation>()
        
        // BFS 遍历
        val queue = ArrayDeque<Pair<EntityId, Int>>() // (entityId, currentHop)
        seedEntityIds.forEach { queue.add(it to 0) }
        visited.addAll(seedEntityIds)
        
        while (queue.isNotEmpty()) {
            val (entityId, currentHop) = queue.removeFirst()
            
            // 添加当前实体
            fullGraph.entities.find { it.entityId == entityId }?.let { subgraphEntities.add(it) }
            
            // 如果未达到跳数限制，继续扩展
            if (currentHop < hopRadius) {
                // 获取出边和入边
                val outgoingRelations = fullGraph.getOutgoingRelations(entityId)
                val incomingRelations = fullGraph.getIncomingRelations(entityId)
                
                for (relation in outgoingRelations + incomingRelations) {
                    subgraphRelations.add(relation)
                    
                    // 扩展到关联实体
                    val nextEntityId = if (relation.sourceEntityId == entityId) {
                        relation.targetEntityId
                    } else {
                        relation.sourceEntityId
                    }
                    
                    if (!visited.contains(nextEntityId)) {
                        visited.add(nextEntityId)
                        queue.add(nextEntityId to currentHop + 1)
                    }
                }
            }
        }
        
        return EntityGraph(
            entities = subgraphEntities.distinctBy { it.entityId },
            relations = subgraphRelations.distinctBy { it.relationId },
            queriedAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * 实体优先级排序
     */
    private fun prioritizeEntities(
        entities: List<Entity>,
        mode: PriorityMode
    ): List<Entity> {
        return when (mode) {
            PriorityMode.TASK_RELEVANT -> {
                // 任务相关：Task/Event > Person/Organization > 其他
                entities.sortedByDescending {
                    when (it.type) {
                        EntityType.TASK, EntityType.EVENT -> 3
                        EntityType.PERSON, EntityType.ORGANIZATION -> 2
                        else -> 1
                    }
                }
            }
            PriorityMode.USER_PREFERENCE -> {
                // 用户偏好：Preference/Person > 其他
                entities.sortedByDescending {
                    when (it.type) {
                        EntityType.PREFERENCE -> 3
                        EntityType.PERSON -> 2
                        else -> 1
                    }
                }
            }
            PriorityMode.CHRONOLOGICAL -> {
                // 时间顺序：最新优先
                entities.sortedByDescending { it.updatedAtMs }
            }
            PriorityMode.CONFIDENCE_FIRST -> {
                // 置信度优先
                entities.sortedByDescending { it.confidence }
            }
        }
    }
    
    /**
     * Token 预算裁剪
     */
    private fun applyTokenBudget(
        sortedEntities: List<Entity>,
        relations: List<Relation>,
        tokenBudget: Int,
        includeRelations: Boolean
    ): BudgetedResult {
        val selectedEntities = mutableListOf<Entity>()
        val selectedRelations = mutableListOf<Relation>()
        var currentTokens = 0
        
        // 1. 先分配实体
        for (entity in sortedEntities) {
            val entityTokens = estimateEntityTokens(entity)
            if (currentTokens + entityTokens <= tokenBudget) {
                selectedEntities.add(entity)
                currentTokens += entityTokens
            } else {
                break // 预算耗尽
            }
        }
        
        // 2. 再分配关系（仅包含已选实体间的关系）
        if (includeRelations) {
            val selectedEntityIds = selectedEntities.map { it.entityId }.toSet()
            
            for (relation in relations) {
                if (selectedEntityIds.contains(relation.sourceEntityId) &&
                    selectedEntityIds.contains(relation.targetEntityId)) {
                    
                    val relationTokens = AVG_TOKENS_PER_RELATION
                    if (currentTokens + relationTokens <= tokenBudget) {
                        selectedRelations.add(relation)
                        currentTokens += relationTokens
                    } else {
                        break
                    }
                }
            }
        }
        
        return BudgetedResult(
            entities = selectedEntities,
            relations = selectedRelations,
            tokenEstimate = currentTokens
        )
    }
    
    /**
     * 预算裁剪结果
     */
    private data class BudgetedResult(
        val entities: List<Entity>,
        val relations: List<Relation>,
        val tokenEstimate: Int
    )
    
    /**
     * 估算实体 token 数（简化版）
     */
    private fun estimateEntityTokens(entity: Entity): Int {
        // 简化：类型名 + 显示名 + 属性
        val baseTokens = 10 // 类型和字段名
        val nameTokens = entity.displayName.length / 4 // 粗略估算：4 字符 ≈ 1 token
        val attrTokens = entity.attributes.length / 4
        
        return baseTokens + nameTokens + attrTokens
    }
    
    /**
     * 序列化为 JSON 上下文（供 LLM 消费）
     */
    private fun serializeToContext(graph: EntityGraph): String {
        val contextJson = JSONObject()
        
        // 实体列表
        val entitiesArray = JSONArray()
        for (entity in graph.entities) {
            val entityJson = JSONObject().apply {
                put("id", entity.entityId.value)
                put("type", entity.type.typeName)
                put("name", entity.displayName)
                put("confidence", entity.confidence)
                
                // 解析属性 JSON
                try {
                    val attrs = JSONObject(entity.attributes)
                    put("attributes", attrs)
                } catch (e: Exception) {
                    put("attributes", JSONObject())
                }
            }
            entitiesArray.put(entityJson)
        }
        contextJson.put("entities", entitiesArray)
        
        // 关系列表
        val relationsArray = JSONArray()
        for (relation in graph.relations) {
            val relationJson = JSONObject().apply {
                put("id", relation.relationId.value)
                put("type", relation.type.typeName)
                put("source", relation.sourceEntityId.value)
                put("target", relation.targetEntityId.value)
                put("strength", relation.strength)
            }
            relationsArray.put(relationJson)
        }
        contextJson.put("relations", relationsArray)
        
        contextJson.put("timestamp_ms", graph.queriedAtMs)
        
        return contextJson.toString(2) // 2 空格缩进
    }
    
    /**
     * 从 JSON 上下文反序列化
     */
    fun deserializeFromContext(contextJson: String): EntityGraph {
        val json = JSONObject(contextJson)
        
        // 解析实体
        val entitiesArray = json.getJSONArray("entities")
        val entities = mutableListOf<Entity>()
        for (i in 0 until entitiesArray.length()) {
            val entityJson = entitiesArray.getJSONObject(i)
            
            val entity = Entity(
                entityId = EntityId(entityJson.getString("id")),
                type = EntityType.fromTypeName(entityJson.getString("type")),
                displayName = entityJson.getString("name"),
                attributes = entityJson.getJSONObject("attributes").toString(),
                provenance = "checkpoint",
                confidence = entityJson.getDouble("confidence").toFloat(),
                createdAtMs = 0L, // 未保存
                updatedAtMs = 0L,
                isDeleted = false
            )
            entities.add(entity)
        }
        
        // 解析关系
        val relationsArray = json.getJSONArray("relations")
        val relations = mutableListOf<Relation>()
        for (i in 0 until relationsArray.length()) {
            val relationJson = relationsArray.getJSONObject(i)
            
            val relation = Relation(
                relationId = RelationId(relationJson.getString("id")),
                type = RelationType.fromTypeName(relationJson.getString("type")),
                sourceEntityId = EntityId(relationJson.getString("source")),
                targetEntityId = EntityId(relationJson.getString("target")),
                strength = relationJson.getDouble("strength").toFloat(),
                provenance = "checkpoint",
                createdAtMs = 0L,
                isDeleted = false
            )
            relations.add(relation)
        }
        
        return EntityGraph(
            entities = entities,
            relations = relations,
            queriedAtMs = json.getLong("timestamp_ms")
        )
    }
    
    /**
     * 计算打包效率指标（用于调试/优化）
     */
    fun calculatePackingMetrics(result: PackingResult): PackingMetrics {
        val avgConfidence = result.graph.entities.map { it.confidence }.average().toFloat()
        val coverageRatio = result.entitiesPacked.toFloat() / 
                           (result.entitiesPacked + result.entitiesFiltered)
        
        return PackingMetrics(
            avgConfidence = avgConfidence,
            coverageRatio = coverageRatio,
            tokensPerEntity = result.tokenEstimate.toFloat() / result.entitiesPacked,
            relationDensity = result.relationsPacked.toFloat() / result.entitiesPacked
        )
    }
    
    data class PackingMetrics(
        val avgConfidence: Float,
        val coverageRatio: Float,
        val tokensPerEntity: Float,
        val relationDensity: Float
    )
}

/**
 * IAgentRuntimeService 接口占位符（实际由 A4.1 实现）
 */
interface IAgentRuntimeService {
    // TODO A3: checkpoint 相关接口
}

// EntityExtractionPipeline.kt
// C3.2 实体抽取管线：来源物化/懒创建/provenance 强制/变更广播
package com.genton.semantics.extraction

import android.content.Context
import android.util.Log
import com.genton.semantics.ontology.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 实体抽取管线
 * 
 * 核心职责：
 * 1. 来源物化：所有实体必须标注 provenance（防投毒）
 * 2. 懒创建：实体首次被引用时才创建，避免冗余
 * 3. 低置信不打包：confidence < 0.6 的实体不参与上下文打包
 * 4. 变更广播：实体变更时广播给监听者（触发缓存失效）
 * 
 * 数据来源：
 * - 联系人同步（contact_sync）
 * - 日历事件（calendar_event）
 * - 对话分析（conversation_turn_*）
 * - 通知分析（notification_*）
 * - 用户输入（user_input）
 * - 文件索引（file_index）
 */
class EntityExtractionPipeline(
    private val context: Context
) {
    companion object {
        private const val TAG = "EntityExtractionPipeline"
        
        // 置信度阈值
        private const val MIN_CONFIDENCE_FOR_PACKING = 0.6f  // 低于此值不参与上下文打包
        private const val MIN_CONFIDENCE_FOR_STORAGE = 0.3f  // 低于此值拒绝入库
        
        // 来源白名单（防投毒）
        private val ALLOWED_PROVENANCE_PREFIXES = setOf(
            "contact_sync",
            "calendar_event",
            "conversation_turn",
            "notification",
            "user_input",
            "file_index",
            "system_context"
        )
    }
    
    // 实体存储（内存缓存，实际应持久化到数据库）
    private val entityCache = ConcurrentHashMap<EntityId, Entity>()
    
    // 待创建实体（懒创建队列）
    private val lazyCreationQueue = ConcurrentHashMap<String, PendingEntity>()
    
    // 变更监听器
    private val changeListeners = mutableListOf<IEntityChangeListener>()
    
    /**
     * 抽取实体（主入口）
     * 
     * @param request 抽取请求
     * @return 抽取结果
     */
    fun extractEntity(request: EntityExtractionRequest): EntityExtractionResult {
        // 1. 验证 provenance
        if (!isProvenanceValid(request.provenance)) {
            Log.w(TAG, "Invalid provenance: ${request.provenance}")
            return EntityExtractionResult(
                success = false,
                errorCode = ErrorCode.INVALID_PROVENANCE,
                errorMessage = "Provenance must start with one of: ${ALLOWED_PROVENANCE_PREFIXES.joinToString()}"
            )
        }
        
        // 2. 验证置信度
        if (request.confidence < MIN_CONFIDENCE_FOR_STORAGE) {
            Log.w(TAG, "Confidence too low: ${request.confidence} < $MIN_CONFIDENCE_FOR_STORAGE")
            return EntityExtractionResult(
                success = false,
                errorCode = ErrorCode.CONFIDENCE_TOO_LOW,
                errorMessage = "Confidence ${request.confidence} is below minimum threshold $MIN_CONFIDENCE_FOR_STORAGE"
            )
        }
        
        // 3. 检查是否已存在（去重）
        val existingEntity = findExistingEntity(request)
        if (existingEntity != null) {
            Log.d(TAG, "Entity already exists: ${existingEntity.entityId.id}")
            return EntityExtractionResult(
                success = true,
                entity = existingEntity,
                isNew = false
            )
        }
        
        // 4. 创建新实体
        val entity = createEntity(request)
        
        // 5. 存储实体
        storeEntity(entity)
        
        // 6. 广播变更
        broadcastChange(entity, ChangeType.UPSERT)
        
        Log.i(TAG, "Entity extracted: ${entity.entityId.id}, type=${entity.type.typeName}, confidence=${entity.confidence}")
        
        return EntityExtractionResult(
            success = true,
            entity = entity,
            isNew = true
        )
    }
    
    /**
     * 批量抽取实体
     */
    fun extractBatch(requests: List<EntityExtractionRequest>): List<EntityExtractionResult> {
        return requests.map { extractEntity(it) }
    }
    
    /**
     * 懒创建：注册待创建实体（首次引用时才实际创建）
     * 
     * 场景：对话中提到"张三"，但暂时不确定是否需要创建实体，
     * 先注册到懒创建队列，等到真正需要时（如加入上下文打包）再创建
     */
    fun registerLazyEntity(pending: PendingEntity) {
        lazyCreationQueue[pending.temporaryId] = pending
        Log.d(TAG, "Registered lazy entity: ${pending.temporaryId}, type=${pending.type.typeName}")
    }
    
    /**
     * 实现懒创建：根据临时 ID 创建实体
     */
    fun realizeLazyEntity(temporaryId: String): Entity? {
        val pending = lazyCreationQueue.remove(temporaryId) ?: run {
            Log.w(TAG, "Lazy entity not found: $temporaryId")
            return null
        }
        
        // 转换为实体抽取请求
        val request = EntityExtractionRequest(
            type = pending.type,
            displayName = pending.displayName,
            attributes = pending.attributes,
            provenance = pending.provenance,
            confidence = pending.confidence,
            lazyCreation = false  // 现在是真正创建
        )
        
        val result = extractEntity(request)
        return if (result.success) result.entity else null
    }
    
    /**
     * 获取可打包实体（过滤低置信度）
     * 
     * 用于上下文打包（C3.3），只返回 confidence >= 0.6 的实体
     */
    fun getPackableEntities(entityIds: List<EntityId>): List<Entity> {
        return entityIds.mapNotNull { entityId ->
            entityCache[entityId]?.takeIf { it.confidence >= MIN_CONFIDENCE_FOR_PACKING }
        }
    }
    
    /**
     * 更新实体
     */
    fun updateEntity(entityId: EntityId, updates: EntityUpdate): Boolean {
        val entity = entityCache[entityId] ?: run {
            Log.w(TAG, "Entity not found for update: ${entityId.id}")
            return false
        }
        
        // 应用更新
        val updatedEntity = applyUpdate(entity, updates)
        
        // 更新存储
        storeEntity(updatedEntity)
        
        // 广播变更
        broadcastChange(updatedEntity, ChangeType.UPSERT)
        
        Log.i(TAG, "Entity updated: ${entityId.id}")
        return true
    }
    
    /**
     * 删除实体
     */
    fun deleteEntity(entityId: EntityId): Boolean {
        val entity = entityCache.remove(entityId) ?: run {
            Log.w(TAG, "Entity not found for deletion: ${entityId.id}")
            return false
        }
        
        // 广播删除
        broadcastChange(entity, ChangeType.DELETE)
        
        Log.i(TAG, "Entity deleted: ${entityId.id}")
        return true
    }
    
    /**
     * 注册变更监听器
     */
    fun registerChangeListener(listener: IEntityChangeListener) {
        changeListeners.add(listener)
    }
    
    /**
     * 注销变更监听器
     */
    fun unregisterChangeListener(listener: IEntityChangeListener) {
        changeListeners.remove(listener)
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 验证 provenance 是否合法
     */
    private fun isProvenanceValid(provenance: String): Boolean {
        return ALLOWED_PROVENANCE_PREFIXES.any { provenance.startsWith(it) }
    }
    
    /**
     * 查找已存在的实体（去重）
     * 
     * 使用 C3.1 的合并启发式算法
     */
    private fun findExistingEntity(request: EntityExtractionRequest): Entity? {
        // 遍历相同类型的实体
        val sameTypeEntities = entityCache.values.filter { it.type == request.type }
        
        for (entity in sameTypeEntities) {
            val mergeDecision = EntityMergeHeuristics.shouldMerge(
                entity1 = entity,
                entity2Attributes = parseAttributes(request.attributes),
                entity2Type = request.type
            )
            
            if (mergeDecision.shouldMerge && mergeDecision.confidence >= 0.95f) {
                // 高置信度自动合并（视为同一实体）
                return entity
            }
        }
        
        return null
    }
    
    /**
     * 创建实体
     */
    private fun createEntity(request: EntityExtractionRequest): Entity {
        val entityId = EntityId.generate(request.type)
        val now = System.currentTimeMillis()
        
        return Entity(
            entityId = entityId,
            type = request.type,
            displayName = request.displayName,
            attributes = request.attributes,
            provenance = request.provenance,
            confidence = request.confidence,
            createdAtMs = now,
            updatedAtMs = now
        )
    }
    
    /**
     * 存储实体
     */
    private fun storeEntity(entity: Entity) {
        entityCache[entity.entityId] = entity
        // TODO: 持久化到数据库
    }
    
    /**
     * 应用更新
     */
    private fun applyUpdate(entity: Entity, updates: EntityUpdate): Entity {
        return entity.copy(
            displayName = updates.displayName ?: entity.displayName,
            attributes = updates.attributes ?: entity.attributes,
            confidence = updates.confidence ?: entity.confidence,
            updatedAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * 广播变更
     */
    private fun broadcastChange(entity: Entity, changeType: ChangeType) {
        val change = EntityChange(
            entityId = entity.entityId,
            type = changeType,
            timestamp = System.currentTimeMillis()
        )
        
        changeListeners.forEach { listener ->
            try {
                listener.onEntityChanged(change)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener: ${e.message}", e)
            }
        }
    }
    
    /**
     * 解析属性
     */
    private fun parseAttributes(attributesJson: String): Map<String, Any> {
        return try {
            val json = JSONObject(attributesJson)
            json.keys().asSequence().associateWith { key ->
                json.get(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing attributes: ${e.message}", e)
            emptyMap()
        }
    }
}

/**
 * 实体抽取请求
 */
data class EntityExtractionRequest(
    val type: EntityType,
    val displayName: String,
    val attributes: String,  // JSON 字符串
    val provenance: String,
    val confidence: Float,
    val lazyCreation: Boolean = false  // 是否懒创建
)

/**
 * 实体抽取结果
 */
data class EntityExtractionResult(
    val success: Boolean,
    val entity: Entity? = null,
    val isNew: Boolean = false,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null
)

/**
 * 错误码
 */
enum class ErrorCode {
    INVALID_PROVENANCE,      // provenance 不合法
    CONFIDENCE_TOO_LOW,      // 置信度过低
    DUPLICATE_ENTITY,        // 重复实体
    INVALID_ATTRIBUTES,      // 属性格式错误
    INTERNAL_ERROR           // 内部错误
}

/**
 * 待创建实体（懒创建）
 */
data class PendingEntity(
    val temporaryId: String,  // 临时 ID（如 temp_person_001）
    val type: EntityType,
    val displayName: String,
    val attributes: String,
    val provenance: String,
    val confidence: Float
)

/**
 * 实体更新
 */
data class EntityUpdate(
    val displayName: String? = null,
    val attributes: String? = null,
    val confidence: Float? = null
)

/**
 * 实体变更
 */
data class EntityChange(
    val entityId: EntityId,
    val type: ChangeType,
    val timestamp: Long
)

/**
 * 变更类型
 */
enum class ChangeType {
    UPSERT,   // 创建或更新
    DELETE,   // 删除
    VISIBILITY // 可见性变更
}

/**
 * 实体变更监听器
 */
interface IEntityChangeListener {
    fun onEntityChanged(change: EntityChange)
}

// OntologyModels.kt
// C3.1 本体模型 v1 schema：核心数据模型
package com.genton.semantics.ontology

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 实体 ID（全局唯一标识符）
 * 
 * 格式：entity_{type}_{uuid}
 * 例：entity_person_a1b2c3d4
 */
@Parcelize
data class EntityId(
    val id: String
) : Parcelable {
    companion object {
        /**
         * 生成实体 ID
         */
        fun generate(type: EntityType): EntityId {
            val uuid = java.util.UUID.randomUUID().toString().take(8)
            return EntityId("entity_${type.typeName}_$uuid")
        }
        
        /**
         * 从字符串解析实体 ID
         */
        fun parse(id: String): EntityId {
            return EntityId(id)
        }
    }
    
    /**
     * 提取实体类型
     */
    fun extractType(): EntityType? {
        val parts = id.split("_")
        if (parts.size >= 3 && parts[0] == "entity") {
            return EntityType.fromName(parts[1])
        }
        return null
    }
}

/**
 * 实体（Entity）
 * 
 * 本体模型的基本单元，表示现实世界中的对象
 */
@Parcelize
data class Entity(
    /**
     * 实体 ID（全局唯一）
     */
    val entityId: EntityId,
    
    /**
     * 实体类型
     */
    val type: EntityType,
    
    /**
     * 显示名称
     */
    val displayName: String,
    
    /**
     * 属性键值对（JSON 字符串）
     * 例：{"phone": "+86 138****1234", "email": "user@example.com"}
     */
    val attributes: String,
    
    /**
     * 来源（provenance）
     * 记录实体从哪里提取（防投毒）
     * 例：conversation_turn_123, contact_sync, user_input
     */
    val provenance: String,
    
    /**
     * 置信度（0.0 - 1.0）
     * 低置信度实体不会被打包进上下文
     */
    val confidence: Float,
    
    /**
     * 创建时间戳（ms）
     */
    val createdAtMs: Long,
    
    /**
     * 最后更新时间戳（ms）
     */
    val updatedAtMs: Long,
    
    /**
     * 是否已删除（软删除）
     */
    val isDeleted: Boolean = false
) : Parcelable {
    /**
     * 是否可以打包进上下文
     * 
     * 条件：confidence >= 0.6 且未删除
     */
    fun canPack(): Boolean {
        return confidence >= 0.6f && !isDeleted
    }
}

/**
 * 关系（Relation）
 * 
 * 连接两个实体的有向关系
 */
@Parcelize
data class Relation(
    /**
     * 关系 ID（全局唯一）
     */
    val relationId: String,
    
    /**
     * 关系类型
     */
    val type: RelationType,
    
    /**
     * 源实体 ID（from）
     */
    val sourceEntityId: EntityId,
    
    /**
     * 目标实体 ID（to）
     */
    val targetEntityId: EntityId,
    
    /**
     * 关系属性（JSON 字符串）
     * 例：{"strength": 0.8, "frequency": "weekly"}
     */
    val attributes: String,
    
    /**
     * 来源（provenance）
     */
    val provenance: String,
    
    /**
     * 置信度（0.0 - 1.0）
     */
    val confidence: Float,
    
    /**
     * 创建时间戳（ms）
     */
    val createdAtMs: Long,
    
    /**
     * 是否已删除（软删除）
     */
    val isDeleted: Boolean = false
) : Parcelable {
    companion object {
        /**
         * 生成关系 ID
         */
        fun generateId(): String {
            val uuid = java.util.UUID.randomUUID().toString().take(8)
            return "relation_$uuid"
        }
    }
}

/**
 * 实体合并记录
 * 
 * 记录实体消歧合并操作（EntityID 合并启发式）
 */
@Parcelize
data class EntityMergeRecord(
    /**
     * 记录 ID
     */
    val recordId: String,
    
    /**
     * 源实体 ID（被合并的实体）
     */
    val sourceEntityId: EntityId,
    
    /**
     * 目标实体 ID（合并到的实体）
     */
    val targetEntityId: EntityId,
    
    /**
     * 合并原因
     * 例：same_phone, same_email, name_similarity
     */
    val mergeReason: String,
    
    /**
     * 合并时间戳（ms）
     */
    val mergedAtMs: Long,
    
    /**
     * 操作者（system 或 user）
     */
    val mergedBy: String
) : Parcelable

/**
 * 实体拆分记录（split 回滚日志）
 * 
 * 支持将错误合并的实体拆分回原状
 */
@Parcelize
data class EntitySplitRecord(
    /**
     * 记录 ID
     */
    val recordId: String,
    
    /**
     * 合并记录 ID（引用 EntityMergeRecord）
     */
    val mergeRecordId: String,
    
    /**
     * 拆分后的实体 ID 列表
     */
    val splitEntityIds: List<EntityId>,
    
    /**
     * 拆分时间戳（ms）
     */
    val splitAtMs: Long,
    
    /**
     * 操作者（system 或 user）
     */
    val splitBy: String
) : Parcelable

/**
 * 实体图查询结果
 * 
 * 包含实体及其关系的子图
 */
@Parcelize
data class EntityGraph(
    /**
     * 实体列表
     */
    val entities: List<Entity>,
    
    /**
     * 关系列表
     */
    val relations: List<Relation>,
    
    /**
     * 查询时间戳（ms）
     */
    val queriedAtMs: Long
) : Parcelable {
    /**
     * 获取指定实体的所有出边关系
     */
    fun getOutgoingRelations(entityId: EntityId): List<Relation> {
        return relations.filter { it.sourceEntityId == entityId && !it.isDeleted }
    }
    
    /**
     * 获取指定实体的所有入边关系
     */
    fun getIncomingRelations(entityId: EntityId): List<Relation> {
        return relations.filter { it.targetEntityId == entityId && !it.isDeleted }
    }
    
    /**
     * 获取指定实体的所有相关实体（通过关系连接）
     */
    fun getRelatedEntities(entityId: EntityId): List<Entity> {
        val relatedIds = mutableSetOf<EntityId>()
        
        // 出边关系的目标实体
        getOutgoingRelations(entityId).forEach {
            relatedIds.add(it.targetEntityId)
        }
        
        // 入边关系的源实体
        getIncomingRelations(entityId).forEach {
            relatedIds.add(it.sourceEntityId)
        }
        
        return entities.filter { it.entityId in relatedIds && !it.isDeleted }
    }
}

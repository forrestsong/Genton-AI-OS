// RelationTypes.kt
// C3.1 本体模型 v1 schema：9 关系类型定义
package com.genton.semantics.ontology

/**
 * 关系类型枚举（9 种）
 * 
 * 对齐 D6 §1.1 本体模型定义
 */
enum class RelationType(val relationId: Int, val relationName: String) {
    /**
     * 归属关系（属于、拥有）
     * 例：某人 BELONGS_TO 某组织
     */
    BELONGS_TO(1, "belongs_to"),
    
    /**
     * 位于关系（在某地）
     * 例：某事件 LOCATED_AT 某地点
     */
    LOCATED_AT(2, "located_at"),
    
    /**
     * 参与关系（参加、涉及）
     * 例：某人 PARTICIPATES_IN 某事件
     */
    PARTICIPATES_IN(3, "participates_in"),
    
    /**
     * 创建关系（创建者）
     * 例：某人 CREATED 某文档
     */
    CREATED(4, "created"),
    
    /**
     * 关联关系（相关、提及）
     * 例：某文档 RELATED_TO 某任务
     */
    RELATED_TO(5, "related_to"),
    
    /**
     * 依赖关系（依赖、需要）
     * 例：某任务 DEPENDS_ON 另一任务
     */
    DEPENDS_ON(6, "depends_on"),
    
    /**
     * 通信关系（联系、沟通）
     * 例：某人 COMMUNICATES_WITH 另一人
     */
    COMMUNICATES_WITH(7, "communicates_with"),
    
    /**
     * 使用关系（使用、访问）
     * 例：某人 USES 某应用
     */
    USES(8, "uses"),
    
    /**
     * 偏好关系（喜欢、偏好）
     * 例：某人 PREFERS 某类别
     */
    PREFERS(9, "prefers");
    
    companion object {
        /**
         * 从 relationId 获取关系类型
         */
        fun fromId(relationId: Int): RelationType? {
            return values().find { it.relationId == relationId }
        }
        
        /**
         * 从 relationName 获取关系类型
         */
        fun fromName(relationName: String): RelationType? {
            return values().find { it.relationName == relationName }
        }
    }
}

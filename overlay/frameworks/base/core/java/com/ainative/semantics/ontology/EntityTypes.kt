// EntityTypes.kt
// C3.1 本体模型 v1 schema：12 实体类型定义
package com.ainative.semantics.ontology

/**
 * 实体类型枚举（12 种）
 * 
 * 对齐 D6 §1.1 本体模型定义
 */
enum class EntityType(val typeId: Int, val typeName: String) {
    /**
     * 人（联系人、用户自己、提及的人）
     * 属性：姓名、手机号、邮箱、关系（家人/同事/朋友）
     */
    PERSON(1, "person"),
    
    /**
     * 组织（公司、机构、团队）
     * 属性：名称、行业、规模
     */
    ORGANIZATION(2, "organization"),
    
    /**
     * 地点（地址、位置、POI）
     * 属性：名称、地址、经纬度、类型（家/公司/餐厅）
     */
    LOCATION(3, "location"),
    
    /**
     * 事件（日程、会议、活动）
     * 属性：标题、时间、地点、参与人
     */
    EVENT(4, "event"),
    
    /**
     * 任务（待办事项、目标）
     * 属性：标题、截止日期、状态、优先级
     */
    TASK(5, "task"),
    
    /**
     * 文档（文件、笔记、消息）
     * 属性：标题、内容摘要、创建时间、来源
     */
    DOCUMENT(6, "document"),
    
    /**
     * 应用（App、服务）
     * 属性：包名、应用名、版本
     */
    APPLICATION(7, "application"),
    
    /**
     * 媒体（照片、视频、音频）
     * 属性：文件路径、时间、地点、标签
     */
    MEDIA(8, "media"),
    
    /**
     * 账号（登录凭证、订阅）
     * 属性：服务名、用户名（脱敏）、状态
     */
    ACCOUNT(9, "account"),
    
    /**
     * 偏好（用户偏好、设置）
     * 属性：类别、值、置信度、来源
     */
    PREFERENCE(10, "preference"),
    
    /**
     * 概念（抽象概念、类别）
     * 属性：名称、描述、分类
     */
    CONCEPT(11, "concept"),
    
    /**
     * 实体（通用实体，兜底）
     * 属性：名称、类型标签
     */
    ENTITY(12, "entity");
    
    companion object {
        /**
         * 从 typeId 获取实体类型
         */
        fun fromId(typeId: Int): EntityType? {
            return values().find { it.typeId == typeId }
        }
        
        /**
         * 从 typeName 获取实体类型
         */
        fun fromName(typeName: String): EntityType? {
            return values().find { it.typeName == typeName }
        }
    }
}

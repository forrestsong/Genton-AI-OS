package com.ainative.semantics;

/**
 * 可见性规则
 * 
 * 实体级授权，控制哪些主体可以看到哪些实体
 */
parcelable VisibilityRule {
    /**
     * 实体ID
     */
    String entityId;
    
    /**
     * 主体类型："agent" | "app"
     */
    String subjectType;
    
    /**
     * 主体ID（agentId 或 包名；"*"=全局隐藏）
     */
    String subjectId;
    
    /**
     * 是否隐藏（true=对该主体隐藏该实体）
     */
    boolean hidden;
}

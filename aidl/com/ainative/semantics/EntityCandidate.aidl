package com.ainative.semantics;

/**
 * 实体候选
 * 
 * 歧义消解的结果候选
 */
parcelable EntityCandidate {
    /**
     * 实体ID
     */
    String entityId;
    
    /**
     * 显示名称
     */
    String label;
    
    /**
     * 实体类型
     */
    int entityType;
    
    /**
     * 匹配分数 0~1
     */
    float score;
    
    /**
     * 匹配原因（"别名匹配 / 属性匹配 / 上下文共现"）
     */
    String reason;
}

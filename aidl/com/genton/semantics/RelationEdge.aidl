package com.genton.semantics;

/**
 * 关系边
 * 
 * 实体之间的语义关系
 */
parcelable RelationEdge {
    /**
     * 起始实体ID
     */
    String fromEntityId;
    
    /**
     * 关系类型（REL_* 常量）
     */
    int relationType;
    
    /**
     * 目标实体ID
     */
    String toEntityId;
    
    /**
     * 权重 0~1（共现/置信加权）
     */
    float weight;
    
    /**
     * 证据来源
     */
    String sourceRef;
    
    /**
     * 观察时间（realtime 纳秒）
     */
    long observedAtRealtimeNanos;
}

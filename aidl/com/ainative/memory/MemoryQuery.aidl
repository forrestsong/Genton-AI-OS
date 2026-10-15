package com.ainative.memory;

/**
 * 记忆查询请求
 * 
 * 支持语义检索、实体过滤、时间范围等
 */
parcelable MemoryQuery {
    /**
     * 记忆层级过滤（-1=不限层）
     */
    int kind = -1;
    
    /**
     * 自然语言语义检索（端侧 embedding）
     */
    String semanticQuery;
    
    /**
     * 知识图谱实体过滤（如 "客户A"、"项目X"）
     */
    String[] entityFilter;
    
    /**
     * 时间范围起始（realtime 纳秒，0=不限）
     */
    long timeFromRealtimeNanos;
    
    /**
     * 时间范围结束（realtime 纳秒）
     */
    long timeToRealtimeNanos;
    
    /**
     * 返回结果数量上限
     */
    int topK = 10;
    
    /**
     * 最小相似度阈值 0~1
     */
    float minSimilarity;
}

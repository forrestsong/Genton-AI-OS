package com.genton.semantics;

/**
 * 语义服务统计信息
 */
parcelable SemanticsStats {
    /**
     * 实体总数
     */
    long entityCount;
    
    /**
     * 关系边总数
     */
    long relationCount;
    
    /**
     * 挂接的记忆断言数
     */
    long assertionCount;
    
    /**
     * 缓存命中率 0~100（实体/子图缓存命中率）
     */
    long cacheHitRate;
    
    /**
     * 查询平均耗时（毫秒，滚动均值）
     */
    long avgQueryMs;
}

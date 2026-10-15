package com.ainative.memory;

/**
 * 记忆统计信息
 */
parcelable MemoryStats {
    /**
     * 工作记忆条数
     */
    long workingCount;
    
    /**
     * 长期记忆条数
     */
    long longTermCount;
    
    /**
     * 系统记忆条数
     */
    long systemCount;
    
    /**
     * 向量索引条数
     */
    long vectorCount;
    
    /**
     * 知识图谱节点数
     */
    long graphNodeCount;
    
    /**
     * 存储容量（字节）
     */
    long storageBytes;
    
    /**
     * 最近同步时间（realtime 纳秒）
     */
    long lastSyncRealtimeNanos;
}

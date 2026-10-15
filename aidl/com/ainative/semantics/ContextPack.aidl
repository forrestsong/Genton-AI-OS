package com.ainative.semantics;

import com.ainative.semantics.Entity;
import com.ainative.semantics.RelationEdge;
import com.ainative.memory.MemoryEntry;

/**
 * 上下文打包结果
 * 
 * 包含实体子图、关系边、相关记忆、系统记忆页摘要
 */
parcelable ContextPack {
    /**
     * 打包ID（用于缓存与恢复）
     */
    long packId;
    
    /**
     * 构建时间（realtime 纳秒）
     */
    long builtAtRealtimeNanos;
    
    /**
     * 实体列表（已按调用方授权过滤）
     */
    Entity[] entities;
    
    /**
     * 关系边列表
     */
    RelationEdge[] edges;
    
    /**
     * 挂接在本体上的近期事实断言（脱敏版本）
     */
    MemoryEntry[] relevantMemories;
    
    /**
     * 系统记忆页摘要（掩码后）
     */
    String systemMemorySummary;
    
    /**
     * 估算占用（供大脑做上下文预算决策）
     */
    int estimatedTokens;
}

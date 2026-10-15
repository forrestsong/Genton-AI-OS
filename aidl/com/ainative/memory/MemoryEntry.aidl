package com.ainative.memory;

import com.ainative.memory.ProvenanceInfo;

/**
 * 记忆条目
 * 
 * 已存储的记忆，包含掩码/脱敏后的呈现版本
 */
parcelable MemoryEntry {
    /**
     * 记忆唯一标识
     */
    String memoryId;
    
    /**
     * 记忆层级：KIND_WORKING/KIND_LONG_TERM/KIND_SYSTEM
     */
    int kind;
    
    /**
     * 归属 Agent（WORKING 必填；LONG_TERM 记"写入者"用于追溯）
     */
    String ownerAgentId;
    
    /**
     * 记忆内容（已掩码/脱敏后的呈现版本）
     */
    String content;
    
    /**
     * 分类标签
     */
    String[] tags;
    
    /**
     * 来源可信度
     */
    int trust;
    
    /**
     * 是否已做 PII 掩码
     */
    boolean piiMasked;
    
    /**
     * 创建时间（realtime 纳秒）
     */
    long createdAtRealtimeNanos;
    
    /**
     * 更新时间（realtime 纳秒）
     */
    long updatedAtRealtimeNanos;
    
    /**
     * 溯源信息
     */
    ProvenanceInfo provenance;
}

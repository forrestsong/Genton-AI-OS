package com.ainative.memory;

/**
 * 记忆条目草稿
 * 
 * 大脑提交的待写入记忆，服务端会完成掩码/加密/索引/provenance 处理
 */
parcelable MemoryEntryDraft {
    /**
     * 记忆层级：KIND_WORKING/KIND_LONG_TERM/KIND_SYSTEM
     */
    int kind;
    
    /**
     * 记忆内容（结构化或自然语言事实，大脑抽取产物）
     */
    String content;
    
    /**
     * 分类标签
     */
    String[] tags;
    
    /**
     * 来源可信度（taint，来自感知/用户输入标注）
     */
    int trust;
    
    /**
     * 触发会话ID（provenance 锚定）
     */
    String sourceSessionId;
    
    /**
     * 过期时间（realtime 纳秒，0=永久）
     */
    long expiresAtRealtimeNanos;
}

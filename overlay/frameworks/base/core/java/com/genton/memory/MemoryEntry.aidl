package com.genton.memory;

import com.genton.memory.ProvenanceInfo;

/**
 * MemoryEntry - 已落库的记忆条目
 *
 * 服务端实现：AgentMemoryService（mWorkingMemories / mLongTermMemories /
 * mSystemMemories 三个 Map<String, MemoryEntry>）
 *
 * 说明：createdAtRealtimeNanos / updatedAtRealtimeNanos 使用单调时钟
 * System.nanoTime()，不受系统墙钟跳变影响。
 */
parcelable MemoryEntry {
    /** 记忆 ID（服务端生成） */
    String memoryId;

    /** 记忆类别（IAgentMemoryService.KIND_*） */
    int kind;

    /** 归属 Agent ID */
    String ownerAgentId;

    /** 记忆正文（已按 PII 策略处理） */
    String content;

    /** 标签（可空） */
    String[] tags;

    /** 来源可信度（0..4） */
    int trust;

    /** 正文是否发生过 PII 掩码 */
    boolean piiMasked;

    /** 创建时间（单调时钟，纳秒） */
    long createdAtRealtimeNanos;

    /** 最近更新时间（单调时钟，纳秒） */
    long updatedAtRealtimeNanos;

    /** 溯源信息 */
    ProvenanceInfo provenance;
}

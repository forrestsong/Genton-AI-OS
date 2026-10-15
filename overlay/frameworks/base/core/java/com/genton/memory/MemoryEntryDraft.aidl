package com.genton.memory;

/**
 * MemoryEntryDraft - 待写入的记忆草稿
 *
 * 服务端实现：AgentMemoryService.write(MemoryEntryDraft draft, MemoryEntry entry)
 *
 * 说明：draft 由调用方（Agent 大脑）构造，服务端校验后再生成正式的
 * MemoryEntry（补 memoryId / ownerAgentId / piiMasked / 时间戳 / 溯源信息）。
 */
parcelable MemoryEntryDraft {
    /** 记忆类别（IAgentMemoryService.KIND_*） */
    int kind;

    /** 记忆正文（写入前经 PII 防火墙处理） */
    String content;

    /** 标签（可空） */
    String[] tags;

    /** 来源可信度（0..4，取值见 IAgentMemoryService.TRUST_*） */
    int trust;

    /** 来源会话 ID（必填，作为溯源的 sourceRef） */
    String sourceSessionId;
}

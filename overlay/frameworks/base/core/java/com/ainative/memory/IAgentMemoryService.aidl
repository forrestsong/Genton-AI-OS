package com.ainative.memory;

import com.ainative.memory.MemoryEntryDraft;
import com.ainative.memory.MemoryEntry;
import com.ainative.memory.MemoryQuery;
import com.ainative.memory.ForgetRequest;
import com.ainative.memory.ForgetJob;
import com.ainative.memory.ProvenanceInfo;
import com.ainative.memory.MemoryStats;
import com.ainative.memory.IAgentMemoryListener;

/**
 * IAgentMemoryService
 * AI Native OS 记忆服务接口
 *
 * 对齐 services/core/java/com/android/server/ainative/AgentMemoryService.java 实现
 * （三层记忆写入/查询/遗忘、PII 屏蔽、来源 taint、向量索引、监听）
 */
interface IAgentMemoryService {
    const int STATUS_OK         = 0;
    const int STATUS_DENIED     = 1;
    const int STATUS_NOT_FOUND  = 2;
    const int STATUS_PII_REJECT = 3;
    const int STATUS_INTERNAL_ERROR = -1;

    // 记忆层级
    const int KIND_WORKING    = 0;
    const int KIND_LONG_TERM  = 1;
    const int KIND_SYSTEM     = 2;

    // 来源 taint
    const int SOURCE_USER_STMT       = 0;
    const int SOURCE_SCREEN          = 1;
    const int SOURCE_NOTIFICATION    = 2;
    const int SOURCE_APP_DATA        = 3;
    const int SOURCE_WEB             = 4;
    const int SOURCE_MODEL_EXTRACT   = 5;

    int write(in MemoryEntryDraft draft, inout MemoryEntry entry);

    MemoryEntry[] query(in MemoryQuery query);

    MemoryEntry getById(in String memoryId);

    int deleteById(in String memoryId);

    int forget(in ForgetRequest request, inout ForgetJob job);

    ProvenanceInfo getProvenance(in String memoryId);

    int registerListener(in IAgentMemoryListener listener);

    int unregisterListener(in IAgentMemoryListener listener);

    MemoryStats getStats();
}

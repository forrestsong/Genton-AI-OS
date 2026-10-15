package com.ainative.memory;

/**
 * MemoryQuery - 记忆检索条件
 *
 * 服务端实现：AgentMemoryService.query(MemoryQuery query)
 *
 * 语义（与实现一致）：
 * - kind == -1 表示跨工作/长期/系统三个库检索，否则只查对应类别；
 * - timeFromRealtimeNanos / timeToRealtimeNanos 为 0 表示该侧不限；
 * - semanticQuery 当前实现为正文包含匹配（大小写不敏感）；
 * - entityFilter 为任一命中即可（OR）；
 * - topK 为结果条数上限。
 */
parcelable MemoryQuery {
    /** 记忆类别；-1 表示全部类别 */
    int kind;

    /** 语义查询串（可空） */
    String semanticQuery;

    /** 实体过滤（可空，任一命中即可） */
    String[] entityFilter;

    /** 起始时间（单调时钟纳秒；0 表示不限） */
    long timeFromRealtimeNanos;

    /** 结束时间（单调时钟纳秒；0 表示不限） */
    long timeToRealtimeNanos;

    /** 返回条数上限 */
    int topK;
}

package com.genton.perception;

/**
 * UiTreeRequest - UI 树捕获请求
 *
 * 服务端实现：AgentUiTreeService.captureUiTree(UiTreeRequest request)
 *
 * 语义（与实现一致）：
 * - maskPii / maskMode 控制 PII 掩码（maskMode 见 maskText）；
 * - incrementalMode 且 previousSequenceId > 0 时，命中同一 sessionId 的缓存
 *   才计算增量 diff；
 * - enableConsistencyCheck 为 true 时与无障碍回退通道做一致性校验；
 * - agentId / intentId 用于审计埋点。
 */
parcelable UiTreeRequest {
    /** 是否启用一致性校验（主通道 vs 无障碍回退） */
    boolean enableConsistencyCheck;

    /** 发起方 Agent ID（审计用） */
    String agentId;

    /** 关联意图 ID（审计用） */
    String intentId;

    /** 是否掩码 PII */
    boolean maskPii;

    /** 掩码模式（见 maskText） */
    int maskMode;

    /** 是否启用增量模式 */
    boolean incrementalMode;

    /** 上一次快照序号（增量基线；<=0 表示忽略） */
    long previousSequenceId;

    /** 会话 ID（增量缓存的键；可空） */
    String sessionId;
}

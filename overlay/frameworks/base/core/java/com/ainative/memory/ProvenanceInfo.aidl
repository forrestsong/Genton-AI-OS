package com.ainative.memory;

/**
 * ProvenanceInfo - 记忆溯源信息
 *
 * 服务端实现：AgentMemoryService（mProvenanceStore: memoryId -> ProvenanceInfo）
 *
 * 用于隐私仪表盘的"这条记忆哪来的"追溯，服务端 getProvenance(memoryId) 返回。
 */
parcelable ProvenanceInfo {
    /**
     * 来源类型：0=user_stmt 1=screen 2=notification 3=app_data 4=web 5=model_extract
     */
    int sourceKind;

    /**
     * 来源引用（会话 id / 包名 / URL 等）
     */
    String sourceRef;

    /**
     * 抽取时间（单调时钟纳秒）
     */
    long extractedAtRealtimeNanos;

    /**
     * 抽取模型标识（可审计可复现）
     */
    String extractionModelRef;

    /**
     * 内容指纹（哈希，校验"记忆未被静默改动"）
     */
    String contentFingerprint;

    /**
     * 来源可信度
     */
    int trust;
}

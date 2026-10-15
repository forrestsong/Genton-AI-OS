package com.ainative.agent;

/**
 * CapabilityToken - 能力令牌（运行时服务视角）
 *
 * 说明：AIDL 的跨模块类型引用需要 aidl_interface 声明 imports 与版本，
 * 因此本模块按同一契约各自维护一份 CapabilityToken 定义。字段与
 * com.ainative.policy.CapabilityToken 保持一致，避免调用方语义歧义。
 *
 * 时序语义：签发/过期字段一律使用单调时钟 SystemClock.elapsedRealtimeNanos()。
 * 服务端实现：IAgentRuntimeService.acquireCapabilityToken()。
 *
 * @hide
 */
parcelable CapabilityToken {
    /** 令牌 ID（UUID v4，由签名器生成并纳入签名载荷） */
    String tokenId;

    /** Agent ID */
    String agentId;

    /** 能力名 */
    String capability;

    /** 作用域意图 ID（scope 锚定：令牌只能用于签发时绑定的意图上下文） */
    String scopeIntentId;

    /** 签发时间（elapsedRealtimeNanos，单调时钟） */
    long issuedAtRealtimeNanos;

    /** 过期时间（elapsedRealtimeNanos，单调时钟；短时 TTL 由风险档决定） */
    long expiresAtRealtimeNanos;

    /** 签发方签名（SHA256withRSA，覆盖 tokenId/agentId/capability/scopeIntentId/expiresAt） */
    String issuerSignature;

    /** 在线撤销标记（撤销列表为准；快照字段供域服务展示/缓存失效参考） */
    boolean revoked;

    /** 签发时策略版本（策略热更新后旧版令牌被批量撤销） */
    String policyVersion;
}

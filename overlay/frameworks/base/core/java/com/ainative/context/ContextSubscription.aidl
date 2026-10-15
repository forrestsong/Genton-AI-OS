package com.ainative.context;

/**
 * ContextSubscription - 上下文订阅请求
 */
parcelable ContextSubscription {
    int fields;                    // 订阅哪些字段（位掩码）
    long minIntervalMs = 300;      // 推送节流（毫秒）
    boolean onlyOnChange = true;   // 默认仅变化字段推送
    boolean allowRevokeNotification = true;  // 字段被撤销时回调
    long ttlRealtimeNanos = 0;     // 订阅生命周期（0=常驻）
}

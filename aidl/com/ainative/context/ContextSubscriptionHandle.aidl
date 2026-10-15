// ContextSubscriptionHandle.aidl
package com.ainative.context;

/**
 * 上下文订阅句柄
 */
parcelable ContextSubscriptionHandle {
    /**
     * 订阅唯一标识符
     */
    String subscriptionId;

    /**
     * 订阅者身份
     */
    String subscriberId;

    /**
     * 订阅创建时间（System.currentTimeMillis()）
     */
    long createdAtMs;
}

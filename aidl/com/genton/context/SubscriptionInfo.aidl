// SubscriptionInfo.aidl
package com.genton.context;

/**
 * 订阅信息（管理查询用）
 */
parcelable SubscriptionInfo {
    /**
     * 订阅句柄
     */
    String subscriptionId;

    /**
     * 订阅者身份
     */
    String subscriberId;

    /**
     * 订阅的字段掩码
     */
    int fields;

    /**
     * 最小推送间隔（毫秒）
     */
    int minIntervalMs;

    /**
     * 订阅创建时间
     */
    long createdAtMs;

    /**
     * 已推送帧数
     */
    int framesPushed;

    /**
     * 上次推送时间
     */
    long lastPushMs;
}

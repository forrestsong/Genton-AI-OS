package com.ainative.events;

/**
 * SubscriptionInfo - 订阅信息（管理视图）
 * 
 * 用于 listSubscriptions 返回订阅统计信息。
 */
parcelable SubscriptionInfo {
    long handleId;
    String ownerAgentId;
    String[] eventTypes;
    boolean paused;
    long eventsDeliveredTotal;
    long eventsDroppedTotal;
}

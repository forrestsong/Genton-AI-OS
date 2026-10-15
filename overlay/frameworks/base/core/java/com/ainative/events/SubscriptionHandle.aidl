package com.ainative.events;

/**
 * SubscriptionHandle - 订阅句柄
 * 
 * 用于标识和管理事件订阅。
 */
parcelable SubscriptionHandle {
    long handleId;
    String ownerAgentId;             // 服务端按调用方填充，客户端不可伪造
}

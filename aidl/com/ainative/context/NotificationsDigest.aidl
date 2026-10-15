// NotificationsDigest.aidl
package com.ainative.context;

/**
 * 通知摘要
 */
parcelable NotificationsDigest {
    /**
     * 未读通知总数
     */
    int unreadCount;

    /**
     * 来源包列表（去重）
     */
    String[] sourcePackages;

    /**
     * 一句话摘要（小模型生成，不含敏感内容）
     */
    @nullable String summary;

    /**
     * 最近通知时间（System.currentTimeMillis()）
     */
    long lastNotificationMs;

    /**
     * 是否包含高优先级通知
     */
    boolean hasHighPriority;
}

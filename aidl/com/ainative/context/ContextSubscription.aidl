// ContextSubscription.aidl
package com.ainative.context;

/**
 * 上下文订阅配置
 */
parcelable ContextSubscription {
    /**
     * 订阅者身份（agentId 或 system service name）
     */
    String subscriberId;

    /**
     * 订阅的字段掩码（FIELD_* 按位或组合）
     */
    int fields;

    /**
     * 最小推送间隔（毫秒）- 用于节流
     * 0 = 立即推送，300 = 最多每300ms推送一次
     */
    int minIntervalMs;

    /**
     * 是否仅在字段实际变化时推送（false = 周期推送）
     */
    boolean onChangeOnly;

    /**
     * 订阅优先级（0 = 普通，1 = 高优，影响推送队列顺序）
     */
    int priority;
}

package com.ainative.events;

/**
 * EventSubscription - 事件订阅请求
 * 
 * 定义订阅的事件类型、过滤条件和 QoS 参数。
 */
parcelable EventSubscription {
    String[] eventTypes;             // 白名单子集（禁通配符）
    int minPriority = 1;             // PRIORITY_NORMAL，低于该优先级不入队
    int maxEventsPerMinute = 60;     // 令牌桶速率
    int maxBurst = 10;               // 突发上限
    int maxQueue = 100;              // 每订阅队列深度（超出→丢弃+上报）
    boolean paused = false;
    String[] packageFilter;          // 仅通知/前台类生效：限定事件源包
    long ttlRealtimeNanos = 0;       // 订阅生命周期（0=常驻）
    int highSensitivityGrant = 0;    // 1=该订阅包含 S2 事件且已获用户授权
}
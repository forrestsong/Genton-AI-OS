package com.ainative.events;

import com.ainative.events.EventSubscription;
import com.ainative.events.SubscriptionHandle;
import com.ainative.events.SubscriptionInfo;
import com.ainative.events.IAgentEventSink;

/**
 * IAgentEventStreamService - 事件流服务接口
 * 
 * 统一事件总线，为 AI Native OS 提供主动感知能力。
 * 
 * 权限要求：SUBSCRIBE_EVENTS (signature|privileged)
 */
interface IAgentEventStreamService {

    // 状态码
    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;    // 无订阅权限/敏感事件未授权
    const int STATUS_INVALID_SUB     = 2;    // 类型不在白名单/参数非法
    const int STATUS_HANDLE_NOT_FOUND = 3;
    const int STATUS_OVERLOADED      = 4;    // 订阅配额已满
    const int STATUS_INTERNAL_ERROR  = -1;

    // 事件类型常量（白名单）
    const String EVENT_NOTIFICATION_POSTED = "notification.posted";
    const String EVENT_NOTIFICATION_REMOVED = "notification.removed";
    const String EVENT_NOTIFICATION_REPLIED = "notification.replied";
    const String EVENT_FOREGROUND_CHANGED = "foreground.changed";
    const String EVENT_APP_INSTALLED = "app.installed";
    const String EVENT_APP_REMOVED = "app.removed";
    const String EVENT_SCREEN_ON = "screen.on";
    const String EVENT_SCREEN_OFF = "screen.off";
    const String EVENT_DEVICE_LOCKED = "device.locked";
    const String EVENT_DEVICE_UNLOCKED = "device.unlocked";
    const String EVENT_CALL_STATE = "telephony.call_state";
    const String EVENT_SCHEDULE_NEAR = "schedule.near";
    const String EVENT_SCHEDULE_STARTED = "schedule.started";
    const String EVENT_POWER_LOW = "power.low";
    const String EVENT_POWER_STATE_CHANGED = "power.state_changed";
    const String EVENT_NETWORK_CHANGED = "network.changed";
    const String EVENT_AUDIO_ROUTE_CHANGED = "audio.route_changed";
    const String EVENT_STORAGE_LOW = "storage.low";
    const String EVENT_LOCATION_COARSE = "location.coarse_significant";
    const String EVENT_AGENT_GOAL_PROGRESS = "agent.goal_progress";
    const String EVENT_AGENT_STATE_CHANGED = "agent.state_changed";
    const String EVENT_AGENT_BUDGET_WARNING = "agent.budget_warning";
    const String EVENT_SEMANTIC_ENTITY_UPDATED = "semantic.entity_updated";
    const String EVENT_SEMANTIC_MEMORY_INVALIDATED = "semantic.memory_page_invalidated";

    // 优先级
    const int PRIORITY_LOW    = 0;   // 不主动打扰，只入队列
    const int PRIORITY_NORMAL = 1;   // 可触发"建议式"提醒（气泡/胶囊）
    const int PRIORITY_HIGH   = 2;   // 可触发全屏/声音提醒

    // 敏感级
    const int SENSITIVITY_S0 = 0;    // 无个人内容
    const int SENSITIVITY_S1 = 1;    // 含摘要级个人内容（脱敏/匿名化）
    const int SENSITIVITY_S2 = 2;    // 位置类，须 capability + 用户开关

    /**
     * 订阅事件；成功签发 handle
     * 
     * @param subscription 订阅条件
     * @param sink 事件接收回调
     * @param handle 输出参数，订阅句柄
     * @return 状态码（STATUS_OK 表示成功）
     */
    int subscribe(in EventSubscription subscription, in IAgentEventSink sink,
                  out SubscriptionHandle handle);

    /**
     * 热更新订阅（改类型/限流/暂停，不重新 bind）
     * 
     * @param handle 订阅句柄
     * @param subscription 新的订阅条件
     * @return 状态码
     */
    int updateSubscription(in SubscriptionHandle handle, in EventSubscription subscription);

    /**
     * 取消订阅
     * 
     * @param handle 订阅句柄
     * @return 状态码
     */
    int unsubscribe(in SubscriptionHandle handle);

    /**
     * 挂起/恢复某订阅（如"用户进入免打扰时段"）
     * 
     * @param handle 订阅句柄
     * @param paused true=挂起，false=恢复
     * @return 状态码
     */
    int setSubscriptionPaused(in SubscriptionHandle handle, boolean paused);

    /**
     * 管理查询（治理/审计用）：列出调用方可见的订阅
     * 
     * @return 订阅信息列表
     */
    SubscriptionInfo[] listSubscriptions();
}
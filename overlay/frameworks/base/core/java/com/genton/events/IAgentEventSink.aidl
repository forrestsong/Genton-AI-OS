package com.genton.events;

import com.genton.events.SubscriptionHandle;
import com.genton.events.SystemEvent;

/**
 * IAgentEventSink - 事件接收回调接口
 * 
 * Agent 实现此接口以接收订阅的事件。
 */
interface IAgentEventSink {
    
    /**
     * 事件投递（oneway，按订阅内 seq 有序到达）
     * 
     * @param handle 订阅句柄
     * @param event 系统事件
     */
    oneway void onEvent(in SubscriptionHandle handle, in SystemEvent event);

    /**
     * 显式丢弃上报：限流/背压丢弃 must 告知，禁止 Agent 在残缺事件流上决策
     * 
     * @param handle 订阅句柄
     * @param droppedCount 丢弃数量
     * @param reason 丢弃原因（rate_limited|queue_full|ttl_expired）
     */
    oneway void onEventsDropped(in SubscriptionHandle handle, long droppedCount, String reason);
}
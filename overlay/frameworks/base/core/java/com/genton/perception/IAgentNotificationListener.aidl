package com.genton.perception;

import com.genton.perception.NotificationEnvelope;

/**
 * IAgentNotificationListener - 通知事件监听器
 * 
 * 用于接收通知发布和移除事件的回调接口。
 */
interface IAgentNotificationListener {
    
    /**
     * 通知发布事件
     * 
     * @param envelope 通知封装
     * @param reason 发布原因（对齐 NotificationListenerService.REASON_*）
     */
    oneway void onNotificationPosted(in NotificationEnvelope envelope, int reason);

    /**
     * 通知移除事件
     * 
     * @param key 通知唯一标识
     * @param reason 移除原因（对齐 NOTIFICATION_REMOVAL_*）
     */
    oneway void onNotificationRemoved(String key, int reason);
}

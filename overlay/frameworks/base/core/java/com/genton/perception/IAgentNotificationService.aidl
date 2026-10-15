package com.genton.perception;

import com.genton.perception.NotificationEnvelope;
import com.genton.perception.NotificationQuery;
import com.genton.perception.NotificationAction;
import com.genton.perception.IAgentNotificationListener;

/**
 * IAgentNotificationService - 通知感知服务接口
 * 
 * 为 AI Native OS 提供通知读取、清除和动作执行能力。
 * 
 * 权限要求：
 * - queryNotifications: READ_NOTIFICATIONS (signature|privileged)
 * - dismissNotification: DISMISS_NOTIFICATIONS (signature|privileged)
 * - performNotificationAction: PERFORM_NOTIFICATION_ACTIONS (signature|privileged)
 */
interface IAgentNotificationService {

    /**
     * 查询当前活跃通知
     * 
     * @param query 查询条件（包名过滤、重要性、PII 掩码等）
     * @return 通知列表（已按 query 处理）
     */
    List<NotificationEnvelope> queryNotifications(in NotificationQuery query);

    /**
     * 清除指定通知
     * 
     * @param key 通知唯一标识
     */
    void dismissNotification(String key);

    /**
     * 执行通知内联动作
     * 
     * @param key 通知唯一标识
     * @param action 要执行的动作（含 inline reply）
     */
    void performNotificationAction(String key, in NotificationAction action);

    /**
     * 注册通知事件监听器
     * 
     * @param agentId Agent 唯一标识
     * @param listener 监听器回调
     */
    void registerListener(String agentId, IAgentNotificationListener listener);

    /**
     * 注销通知事件监听器
     * 
     * @param agentId Agent 唯一标识
     */
    void unregisterListener(String agentId);

}
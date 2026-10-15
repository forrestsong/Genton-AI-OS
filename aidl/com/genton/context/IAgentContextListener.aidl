// IAgentContextListener.aidl
package com.genton.context;

import com.genton.context.ContextSubscriptionHandle;
import com.genton.context.ContextFrame;

/**
 * 上下文变化监听器
 */
interface IAgentContextListener {
    /**
     * 帧更新回调：只含订阅中"变化"的字段
     * 
     * @param handle 订阅句柄
     * @param frame 上下文帧（仅包含变化的字段，通过 fieldChangedMask 判断）
     */
    oneway void onFrameChanged(in ContextSubscriptionHandle handle, in ContextFrame frame);

    /**
     * 订阅暂停/失效通知
     * 例如：用户关闭位置授权导致 LOCATION 字段不可再交付
     * 
     * @param handle 订阅句柄
     * @param field 被撤销的字段掩码
     */
    oneway void onFieldRevoked(in ContextSubscriptionHandle handle, in int field);
}

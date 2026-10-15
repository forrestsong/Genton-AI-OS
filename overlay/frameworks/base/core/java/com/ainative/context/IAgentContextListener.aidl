package com.ainative.context;

import com.ainative.context.ContextSubscriptionHandle;
import com.ainative.context.ContextFrame;

/**
 * IAgentContextListener - 上下文变化监听器
 */
interface IAgentContextListener {
    
    /**
     * 帧更新：只含订阅中"变化"的字段
     * 
     * @param handle 订阅句柄
     * @param frame 上下文帧（fieldChangedMask 标识变化字段）
     */
    oneway void onFrameChanged(in ContextSubscriptionHandle handle, in ContextFrame frame);

    /**
     * 订阅暂停/失效通知（如用户关闭位置授权）
     * 
     * @param handle 订阅句柄
     * @param field 被撤销的字段
     */
    oneway void onFieldRevoked(in ContextSubscriptionHandle handle, int field);
}
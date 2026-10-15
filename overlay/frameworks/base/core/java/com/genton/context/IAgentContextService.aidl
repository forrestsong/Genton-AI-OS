package com.genton.context;

import com.genton.context.ContextFrame;
import com.genton.context.ContextSubscription;
import com.genton.context.ContextSubscriptionHandle;
import com.genton.context.SubscriptionInfo;
import com.genton.context.IAgentContextListener;

/**
 * IAgentContextService - 上下文服务接口
 * 
 * 提供系统当前状态的结构化上下文索引。
 * 
 * 权限要求：READ_CONTEXT (signature|privileged)
 */
interface IAgentContextService {

    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;
    const int STATUS_INVALID_REQ     = 2;
    const int STATUS_INTERNAL_ERROR  = -1;

    // 字段掩码
    const int FIELD_FOREGROUND_APP        = 1 << 0;
    const int FIELD_TOP_UI_SUMMARY        = 1 << 1;
    const int FIELD_MEDIA                 = 1 << 2;
    const int FIELD_NOTIFICATIONS_DIGEST  = 1 << 3;
    const int FIELD_SENSORS               = 1 << 4;
    const int FIELD_LOCATION              = 1 << 5;
    const int FIELD_AGENT_TASKS           = 1 << 6;
    const int FIELD_ACTIVE_INPUT          = 1 << 7;

    /**
     * 拉一帧（合并多次短间隔调用；内部有 ~300ms 缓存窗口）
     * 
     * @param fields 请求的字段（位掩码）
     * @param frame 输出参数，上下文帧
     * @return 状态码
     */
    int getCurrentFrame(int fields, out ContextFrame frame);

    /**
     * 订阅按字段推送（仅变化字段随帧返回）
     * 
     * @param subscription 订阅条件
     * @param listener 回调监听器
     * @param handle 输出参数，订阅句柄
     * @return 状态码
     */
    int subscribe(in ContextSubscription subscription, in IAgentContextListener listener,
                  out ContextSubscriptionHandle handle);

    /**
     * 取消订阅
     * 
     * @param handle 订阅句柄
     * @return 状态码
     */
    int unsubscribe(in ContextSubscriptionHandle handle);

    /**
     * 历史环（诊断/会话恢复用，上限 100 帧）
     * 
     * @param fields 请求的字段
     * @param sinceRealtimeNanos 起始时间戳
     * @return 历史帧数组
     */
    ContextFrame[] getHistory(int fields, long sinceRealtimeNanos);

    /**
     * 管理查询：谁在订阅哪些字段（隐私仪表盘）
     * 
     * @return 订阅信息列表
     */
    SubscriptionInfo[] listSubscriptions();
}
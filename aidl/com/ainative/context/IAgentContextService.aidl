// IAgentContextService.aidl
package com.ainative.context;

import com.ainative.context.ContextFrame;
import com.ainative.context.ContextSubscription;
import com.ainative.context.ContextSubscriptionHandle;
import com.ainative.context.IAgentContextListener;

/**
 * 上下文服务 - OS 统一持有"当前世界状态"的结构化上下文索引
 * 
 * 定位：前台应用、屏幕语义摘要、媒体、通知摘要、传感器、粗位置、活跃 Agent 任务等
 * 多个 Agent/意图以订阅式低耗视图消费，替代"各自截屏、各自轮询"
 * 
 * 红线：
 * ① 上下文索引永不持有安全窗口内容（UI 摘要层做掩码）
 * ② 敏感字段（位置等）按调用方 capability + 用户授权在服务端过滤
 * ③ 读取/订阅全部审计
 */
interface IAgentContextService {
    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;
    const int STATUS_INVALID_REQ     = 2;
    const int STATUS_INTERNAL_ERROR  = -1;

    // 字段掩码（与 ContextFrame 对应）
    const int FIELD_FOREGROUND_APP        = 1 << 0;  // 前台包名 + Activity
    const int FIELD_TOP_UI_SUMMARY        = 1 << 1;  // 前台UI语义摘要（小模型压缩）
    const int FIELD_MEDIA                 = 1 << 2;  // 媒体会话
    const int FIELD_NOTIFICATIONS_DIGEST  = 1 << 3;  // 未读通知摘要
    const int FIELD_SENSORS               = 1 << 4;  // 屏幕亮灭/电量/网络/音量
    const int FIELD_LOCATION              = 1 << 5;  // 粗位置标签（需授权）
    const int FIELD_AGENT_TASKS           = 1 << 6;  // 活跃Agent任务
    const int FIELD_ACTIVE_INPUT          = 1 << 7;  // 是否正在输入

    /**
     * 拉取当前上下文帧（合并多次短间隔调用；内部有 ~300ms 缓存窗口）
     * 
     * @param fields 字段掩码（FIELD_* 按位或组合）
     * @param frame 输出上下文帧
     * @return STATUS_OK / STATUS_DENIED / STATUS_INTERNAL_ERROR
     */
    int getCurrentFrame(in int fields, out ContextFrame frame);

    /**
     * 订阅上下文变化（仅变化字段随帧返回；minInterval 控制节流）
     * 
     * @param subscription 订阅配置（fields + minInterval）
     * @param listener 回调监听器
     * @param handle 输出订阅句柄
     * @return STATUS_OK / STATUS_DENIED / STATUS_INVALID_REQ
     */
    int subscribe(in ContextSubscription subscription, 
                  in IAgentContextListener listener,
                  out ContextSubscriptionHandle handle);

    /**
     * 取消订阅
     * 
     * @param handle 订阅句柄
     * @return STATUS_OK / STATUS_INVALID_REQ
     */
    int unsubscribe(in ContextSubscriptionHandle handle);

    /**
     * 历史环（诊断/会话恢复用，上限 100 帧）
     * 
     * @param fields 字段掩码
     * @param sinceRealtimeNanos 起始时间（相对启动时间）
     * @return 历史帧数组
     */
    ContextFrame[] getHistory(in int fields, in long sinceRealtimeNanos);

    /**
     * 管理查询：谁在订阅哪些字段（隐私仪表盘）
     * 
     * @return 订阅信息数组
     */
    SubscriptionInfo[] listSubscriptions();
}

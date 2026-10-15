package com.ainative.audit;

import com.ainative.audit.ActivityIndicator;

/**
 * 审计活性监听器（Audit Activity Listener）
 * 
 * 用途：
 * - 驱动 SystemUI 活性指示器（状态栏芯片）
 * - 实时显示"在读屏/在操作/在录音"
 * 
 * 特点：
 * - 高频低配：不含敏感负载
 * - oneway 回调：不阻塞审计服务
 * - 仅包含分类和 agentId
 */
interface IAuditListener {
    /**
     * 实时活性事件回调
     * 
     * 触发时机：
     * - CAT_PERCEPTION_READ：开始/结束读屏
     * - CAT_ACTION_EXECUTION：开始/结束执行动作
     * - CAT_SYSTEM：麦克风/摄像头开启/关闭
     * 
     * 脱敏保证：
     * - 不含动作参数、截图内容等敏感信息
     * - agentId 由 UI 层映射为显示名
     * 
     * @param indicator 活性指示器（分类 + agentId + active 标记）
     */
    oneway void onActivity(in ActivityIndicator indicator);
}

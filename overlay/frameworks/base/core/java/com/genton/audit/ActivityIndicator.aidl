package com.genton.audit;

/**
 * 活性指示器（Activity Indicator）
 * 
 * 用途：
 * - 驱动 SystemUI 状态栏"活性芯片"
 * - 实时显示"在读屏/在操作/在录音"
 * 
 * 特点：
 * - 高频低配：不含敏感负载
 * - 仅包含分类和 agentId
 * - 脱敏保证：agentId 由 UI 映射为显示名
 * 
 * 示例：
 * - "智能助手 正在读取屏幕"
 * - "日历 Bot 正在执行操作"
 * - "系统正在录音"
 */
parcelable ActivityIndicator {
    /**
     * 事件分类（关注感知读/动作执行/系统）
     * 
     * 指示器关心的分类：
     * - CAT_PERCEPTION_READ (1)：读屏/UI 树/截图
     *   → 显示："正在读取屏幕"
     * 
     * - CAT_ACTION_EXECUTION (2)：执行动作
     *   → 显示："正在执行操作"
     * 
     * - CAT_SYSTEM (7)：麦克风/摄像头
     *   → 显示："正在录音" / "正在拍照"
     * 
     * 其他分类（策略/记忆/确认）不触发活性指示器
     */
    int category;
    
    /**
     * 关联 Agent ID
     * 
     * 格式：agent_<uuid>
     * 
     * UI 层映射：
     * - "agent_shopping_assistant" → "购物助手"
     * - "agent_calendar_bot" → "日历 Bot"
     * - "" (空) → "系统"
     * 
     * 脱敏保证：
     * - 不暴露 Agent 内部实现细节
     * - 仅显示用户可理解的名称
     */
    String agentId;
    
    /**
     * 活动状态
     * 
     * true：开始活动（显示芯片）
     * false：结束活动（隐藏芯片）
     * 
     * 示例：
     * - active=true, category=CAT_PERCEPTION_READ → 显示"正在读屏"芯片
     * - active=false, category=CAT_PERCEPTION_READ → 隐藏"正在读屏"芯片
     * 
     * 聚合规则：
     * - 多个 Agent 同时活动 → 显示数量徽章（如"2 个应用正在活动"）
     * - 点击芯片 → 展开列表显示具体 Agent
     */
    boolean active;
    
    /**
     * 时间戳（SystemClock.elapsedRealtimeNanos）
     * 
     * 用途：
     * - 计算活动持续时间
     * - 防抖：短时间内频繁开关不触发 UI 更新
     */
    long timestampRealtimeNanos;
}

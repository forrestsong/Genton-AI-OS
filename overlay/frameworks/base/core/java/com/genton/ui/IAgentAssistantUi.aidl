// IAgentAssistantUi.aidl
// 助手 UI 命令接口 - 由 SystemUI 实现，大脑调用
package com.genton.ui;

import com.genton.ui.SuggestionCapsuleData;

/**
 * Agent 助手 UI 命令接口
 * 
 * 实现方：SystemUI
 * 调用方：Agent 大脑特权应用
 * 
 * 核心职责：
 * 1. 主动拉起助手界面（语音唤醒/主动提醒）
 * 2. 推送建议胶囊（主动服务，受免打扰约束）
 * 3. 导航到特定分区（任务/隐私/审计仪表盘）
 * 4. 更新活性指示器（读屏/注入/录音）
 * 
 * 安全约束：
 * - 本接口仅承载 UI 级命令，不含会话语义内容（防注入）
 * - 建议胶囊受用户免打扰设置约束
 * - 所有调用均为 oneway（异步），避免阻塞大脑
 */
interface IAgentAssistantUi {
    // 导航目标
    const int NAV_HOME    = 0;  // 回助理首页
    const int NAV_TASKS   = 1;  // 任务视图
    const int NAV_PRIVACY = 2;  // 隐私仪表盘
    const int NAV_AUDIT   = 3;  // 审计仪表盘
    
    // 活性指示器类型
    const int INDICATOR_SCREEN_READ = 0;  // 屏幕读取中
    const int INDICATOR_INJECT      = 1;  // 注入操作中
    const int INDICATOR_RECORDING   = 2;  // 录音中
    
    /**
     * 请求拉起助手界面
     * 
     * 使用场景：
     * - 语音唤醒后自动展开
     * - 主动提醒被用户点击
     * - 后台任务需要用户关注
     * 
     * @param reason 拉起原因（用于日志/审计）
     */
    oneway void requestAssistantSurface(in String reason);
    
    /**
     * 显示主动服务建议胶囊
     * 
     * 约束：
     * - NORMAL 级打扰，受免打扰模式约束
     * - PRIORITY_LOW 优先级不得调用
     * - 建议内容不含敏感信息（已脱敏）
     * 
     * @param capsule 胶囊数据（标题/摘要/动作/过期时间）
     */
    oneway void showSuggestion(in SuggestionCapsuleData capsule);
    
    /**
     * 导航到特定分区
     * 
     * @param section 目标分区（NAV_HOME/NAV_TASKS/NAV_PRIVACY/NAV_AUDIT）
     */
    oneway void navigateTo(in int section);
    
    /**
     * 设置活性指示器状态
     * 
     * 用于向用户披露当前正在进行的感知/操作活动
     * 
     * @param kind 指示器类型（INDICATOR_SCREEN_READ/INDICATOR_INJECT/INDICATOR_RECORDING）
     * @param active true=活跃；false=停止
     */
    oneway void setActivityIndicator(in int kind, in boolean active);
}

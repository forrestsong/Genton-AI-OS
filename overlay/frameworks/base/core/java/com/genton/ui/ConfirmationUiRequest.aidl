// ConfirmationUiRequest.aidl
// 确认 UI 请求数据结构
package com.genton.ui;

/**
 * 确认 UI 请求
 * 
 * 由策略引擎签发，携带 HMAC 签名的 confirmId（防 Agent 伪造）
 * SystemUI 展示全屏确认面板，用户决策后回灌到策略引擎
 */
parcelable ConfirmationUiRequest {
    /**
     * 确认 ID（由 IPolicyEngine 签发，HMAC 签名）
     * 
     * 格式：HMAC-SHA256(capability + targetSummary + timestamp, platform_key)
     * 防止 Agent 伪造确认请求
     */
    String confirmId;
    
    /**
     * 要放行的能力（如 agent.send_message、agent.modify_file）
     */
    String capability;
    
    /**
     * 脱敏目标描述（发给谁/改什么）
     * 
     * 示例：
     * - "发送消息到联系人 张三"
     * - "删除文件 document.txt"
     * - "修改系统设置 蓝牙"
     */
    String targetSummaryMasked;
    
    /**
     * 触发意图的脱敏摘要
     * 
     * 示例："用户请求'帮我发消息给张三'"
     */
    String sourceIntentSummary;
    
    /**
     * 风险等级标签（由策略给出，非 Agent 自报）
     * 
     * 示例："中等风险"、"高风险"、"敏感操作"
     */
    String riskLabel;
    
    /**
     * 决策所依据的策略版本（用于仪表盘回溯）
     */
    String ruleVersion;
    
    /**
     * 是否全屏独占（并发请求入队展示）
     * 
     * true：全屏独占，阻塞其他确认请求
     * false：可与其他 UI 共存（未来扩展）
     */
    boolean exclusive = true;
}

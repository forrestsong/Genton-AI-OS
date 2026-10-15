// TurnResultSummary.aidl
// 轮次结果摘要
package com.genton.agent;

/**
 * 轮次结果摘要
 * 
 * 一轮对话完成后的最终结果
 */
parcelable TurnResultSummary {
    /**
     * 最终回复文本（完整版，非流式增量）
     */
    String finalText;
    
    /**
     * 本轮产生的动作卡片 ID 列表
     * 
     * 对应 ActionCardData.cardId
     * 用于 UI 关联动作卡片到轮次
     */
    String[] actionCardIds;
    
    /**
     * 审计引用（用于"查看审计"入口）
     * 
     * 格式：audit://<event_id>
     * 点击后跳转到审计仪表盘的对应事件
     */
    String auditRef;
}

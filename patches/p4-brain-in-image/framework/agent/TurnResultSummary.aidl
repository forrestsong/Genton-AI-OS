// TurnResultSummary.aidl
// 轮次结果摘要
package com.ainative.agent;

/**
 * 轮次结果摘要
 * 
 * 一轮对话完成后的最终结果
 */
parcelable TurnResultSummary {
    /** 轮次终态 */
    const int STATUS_OK = 0;
    const int STATUS_CANCELLED = 1;
    const int STATUS_FAILED = 2;
    const int STATUS_TIMEOUT = 3;

    /**
     * 最终回复文本（完整版，非流式增量）
     */
    String finalText;

    /** 本轮消耗 token 数 */
    int tokensConsumed;

    /** 轮次 ID */
    String turnId;

    /** 终态状态码（STATUS_*） */
    int status;

    /** 失败原因（status=STATUS_FAILED 时非空） */
    String errorMessage;
    
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

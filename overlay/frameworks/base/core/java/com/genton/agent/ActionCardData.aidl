// ActionCardData.aidl
// 动作卡片数据
package com.genton.agent;

import com.genton.agent.FormPayload;

/**
 * 动作卡片数据
 * 
 * 展示单个动作的执行状态、结果、撤销选项、内联表单
 */
parcelable ActionCardData {
    /**
     * 卡片 ID（唯一标识）
     */
    String cardId;
    
    /**
     * 归属轮次 ID
     */
    String turnId;
    
    /**
     * 卡片类型
     * 
     * 0 = progress（执行中）
     * 1 = done（已完成）
     * 2 = needs_attention（需要用户关注）
     * 3 = undo_available（可撤销）
     * 4 = form（内联表单，L0.5 语义直连）
     */
    int type;
    
    /**
     * 标题
     */
    String title;
    
    /**
     * 摘要（脱敏）
     */
    String summaryMasked;
    
    /**
     * 审计引用（用于"查看详情"入口）
     */
    String auditRef;
    
    /**
     * 是否可撤销
     */
    boolean canUndo;
    
    /**
     * 内联表单负载（type=4 时携带）
     * 
     * 表单渲染与状态；同 cardId 推送即表单状态更新
     */
    FormPayload form;
}

// TaskCardData.aidl
// 任务卡片数据
package com.ainative.agent;

/**
 * 任务卡片数据
 * 
 * 对齐 ACB（Agent Control Block）状态机的任务视图
 * 用于 SystemUI 任务列表展示
 */
parcelable TaskCardData {
    /**
     * 任务 ID（对应 ACB.agentId）
     */
    String taskId;
    
    /**
     * Agent ID
     */
    String agentId;
    
    /**
     * 任务状态（对齐 ACB 状态机）
     * 
     * 0 = IDLE（空闲）
     * 1 = PLANNING（规划中）
     * 2 = EXECUTING（执行中）
     * 3 = SUSPENDED（挂起，等待用户确认）
     * 4 = TERMINATED（已终止）
     */
    int state;
    
    /**
     * 目标摘要（脱敏）
     * 
     * 示例："发送消息给联系人"、"创建文档"
     */
    String goalSummaryMasked;
    
    /**
     * 进度百分比
     * 
     * -1 = 不确定
     * 0~100 = 确定进度
     */
    int progress;
    
    /**
     * 预算警告标志
     * 
     * true = 预算即将耗尽（>80%）
     * false = 预算正常
     */
    boolean budgetWarning;
}

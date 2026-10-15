// Goal.aidl
// Agent 目标
package com.ainative.agent;

/**
 * Agent 目标
 * 
 * Agent 的目标栈（Goal DAG）中的单个目标
 */
parcelable Goal {
    /**
     * 目标 ID（唯一标识）
     */
    String goalId;
    
    /**
     * 父目标 ID
     * 
     * 空 = 顶层目标（对应用户的 sourceIntentId）
     */
    String parentGoalId;
    
    /**
     * 目标描述（脱敏摘要）
     */
    String description;
    
    /**
     * 目标状态
     * 
     * 0 = planned（已计划）
     * 1 = in_progress（进行中）
     * 2 = done（已完成）
     * 3 = failed（失败）
     * 4 = superseded（被取代）
     */
    int status;
    
    /**
     * 需要的能力列表
     */
    String[] requiredCapabilities;
}

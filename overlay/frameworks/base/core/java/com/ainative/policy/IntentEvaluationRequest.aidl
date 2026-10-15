package com.ainative.policy;

/**
 * 意图评估请求（规划前预检）
 * 
 * @hide
 */
parcelable IntentEvaluationRequest {
    /** 意图 ID（sourceIntentId，scope 锚点） */
    String intentId;
    
    /** Agent ID */
    String agentId;
    
    /** 脱敏目标描述（审计/确认面板展示） */
    String goalSummary;
    
    /** 意图来源：0=user, 1=parent_agent, 2=event_triggered */
    int derivedFrom;
    
    /** 请求的能力列表 */
    String[] requestedCapabilities;
    
    /** 高敏感标记（如涉位置/外发数据） */
    boolean[] highSensitivityFlags;
    
    /** 约束掩码：privacy_local_only/cloud_allowed/energy/cost */
    int[] constraintMask;
}

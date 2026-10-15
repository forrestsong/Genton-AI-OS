package com.ainative.policy;

import com.ainative.policy.TaintMapEntry;

/**
 * 能力请求（动作执行前申请令牌）
 * 
 * @hide
 */
parcelable CapabilityRequest {
    /** Agent ID */
    String agentId;
    
    /** 意图 ID（必须 = 动作的 sourceIntentId，防跨意图盗用） */
    String intentId;
    
    /** 能力名（须在能力/动作注册表已登记） */
    String capability;
    
    /** 动作 ID（注册表中的动作 id） */
    String actionId;
    
    /** 脱敏目标（发给谁/改什么） */
    String targetSummary;
    
    /** 参数来源打标（taint 延续） */
    TaintMapEntry[] argTaints;
    
    /** 风险自估（实际以策略判定为准） */
    int riskEstimate;
}

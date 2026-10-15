package com.ainative.policy;

/**
 * 策略规则视图（治理/仪表盘用，脱敏）
 * 
 * @hide
 */
parcelable PolicyRuleView {
    /** 规则 ID */
    String ruleId;
    
    /** 是否基线策略 */
    boolean baseline;
    
    /** 能力名 */
    String capability;
    
    /** 效果（allow/deny/confirmation...） */
    String effect;
    
    /** 命中次数 */
    int hitCount;
    
    /** 最后命中时间（nanos） */
    long lastHitAtRealtimeNanos;
}

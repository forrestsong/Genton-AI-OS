package com.ainative.policy;

/**
 * 策略引擎统计数据
 * 
 * @hide
 */
parcelable PolicyStats {
    /** 总裁决次数 */
    long totalEvaluations;
    
    /** GRANT 次数 */
    long grantCount;
    
    /** DENY 次数 */
    long denyCount;
    
    /** 需确认次数 */
    long confirmationCount;
    
    /** 需缓解次数 */
    long mitigationCount;
    
    /** 当前挂起的确认请求数 */
    int pendingConfirmations;
    
    /** 活跃令牌数 */
    int activeTokens;
    
    /** 当前策略版本 */
    String policyVersion;
    
    /** 策略加载时间（nanos） */
    long policyLoadedAtNanos;
}

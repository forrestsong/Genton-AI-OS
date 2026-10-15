package com.genton.execution;

/**
 * Taint 冲突记录（策略引擎返回）
 * 
 * 当参数来源等级不满足策略要求时返回此结构。
 * 
 * 示例场景：
 * 1. ui.type 动作要求 text 参数来自 USER
 * 2. 实际参数来自 EXTERNAL（通知内容）
 * 3. 策略引擎返回 TaintViolation:
 *    - argumentKey = "text"
 *    - requiredLevel = USER (1)
 *    - actualLevel = EXTERNAL (3)
 *    - suggestedMitigation = "require_user_confirmation"
 * 
 * 草案来源：D2 §6 Taint 机制
 */
parcelable TaintViolation {
    /**
     * 冲突的参数键名
     */
    String argumentKey;
    
    /**
     * 策略要求的最低信任等级
     */
    int requiredLevel;
    
    /**
     * 实际提供的信任等级
     */
    int actualLevel;
    
    /**
     * 冲突说明（如 "Payment action requires USER input, but got EXTERNAL source"）
     */
    String message;
    
    /**
     * 建议的缓解措施（如 "require_user_confirmation", "sanitize_input"）
     */
    String suggestedMitigation;
}

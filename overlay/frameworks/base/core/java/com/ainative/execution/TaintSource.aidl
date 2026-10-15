package com.ainative.execution;

/**
 * Taint 来源枚举（参数污染等级）
 * 
 * 用于标记动作参数的可信等级，策略引擎根据此等级决定是否允许执行。
 * 
 * 信任等级：TRUSTED_SYSTEM (0) > USER (1) > AGENT (2) > EXTERNAL (3)
 * 
 * 安全原则：
 * - 高风险动作（如 ui.type）要求 USER 或更高等级
 * - 低风险动作（如 query）接受任意等级
 * - Taint 冲突 = DENY 或 REQUIRE_MITIGATION
 * 
 * 草案来源：D2 §6 Taint 机制
 */
parcelable TaintSource {
    /**
     * 可信系统来源（如系统配置、内部动作）
     * 信任等级：0（最高）
     */
    const int TRUSTED_SYSTEM = 0;
    
    /**
     * 用户直接输入（如语音指令、文本输入）
     * 信任等级：1（高）
     */
    const int USER = 1;
    
    /**
     * Agent 生成（如 LLM 输出、规划器生成）
     * 信任等级：2（中）
     */
    const int AGENT = 2;
    
    /**
     * 外部来源（如通知内容、网页内容）
     * 信任等级：3（低）
     */
    const int EXTERNAL = 3;
    
    /**
     * 参数键名（如 "text", "query", "targetApp"）
     */
    String argumentKey;
    
    /**
     * 来源等级（TRUSTED_SYSTEM / USER / AGENT / EXTERNAL）
     */
    int sourceLevel;
    
    /**
     * 来源描述（用于审计，如 "user_voice_input", "notification_content"）
     */
    String sourceDescription;
}

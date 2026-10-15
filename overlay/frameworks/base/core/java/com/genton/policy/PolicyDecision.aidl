package com.genton.policy;

import com.genton.policy.CapabilityToken;

/**
 * 策略裁决结果
 * 
 * @hide
 */
parcelable PolicyDecision {
    /** 裁决类型（DEC_GRANT/DEC_DENY/DEC_REQUIRE_CONFIRMATION/DEC_REQUIRE_MITIGATION） */
    int decision;
    
    /** 理由（命中的规则 id + 说明） */
    String[] reasons;
    
    /** 确认 ID（decision=DEC_REQUIRE_CONFIRMATION 时返回） */
    String confirmId;
    
    /** 能力令牌（decision=DEC_GRANT 时返回） */
    CapabilityToken token;
    
    /** 需要的缓解措施（decision=DEC_REQUIRE_MITIGATION 时返回） */
    String[] requiredMitigations;
    
    /** 决策所依据的策略版本 */
    String ruleVersion;
    
    /** 路由指令（用于 notification/call_gate 域，-1=无路由） */
    int route;
    
    /** 路由域（action/notification/call_gate） */
    String domain;
    
    /** 路由参数（JSON 格式） */
    String routeParams;
}

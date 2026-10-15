package com.ainative.assets;

/**
 * 资产解析请求（给执行中介用）
 */
parcelable ResolveRequest {
    String agentId;                 // Agent标识
    String intentId;                // scope锚定（透传给策略）
    String actionDescription;       // 行为语义描述（如"打开天气看今日气温"）
    
    String targetApp;               // 目标包名（可能为空=系统技能）
    String deviceModel;             // 机型
    String targetAppVersion;        // 用于macro/dmi版本绑定
    
    int[] candidateKinds;           // 优先顺序（如 [DMI, MACRO]）
}

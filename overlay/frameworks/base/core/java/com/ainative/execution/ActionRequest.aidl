/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.ainative.execution;

import com.ainative.policy.TaintMapEntry;
import com.ainative.execution.TaintSource;

/**
 * 动作执行请求
 * 
 * 从 Agent Brain 或规划器提交的动作执行请求。
 * 经过策略引擎预检后，由执行中介调度到对应执行器。
 * 
 * 草案来源：D2 §4 执行中介
 */
parcelable ActionRequest {
    /**
     * 动作 ID（注册表中的唯一标识，如 "ui.tap", "app.launch"）
     */
    String actionId;
    
    /**
     * Agent 标识
     */
    String agentId;
    
    /**
     * 意图 ID（scope 锚点，防止令牌跨意图盗用）
     */
    String intentId;
    
    // 参数（JSON 格式）
    String argumentsJson;           // 动作参数（如 {"target": "Alice", "message": "Hello"}）
    
    // 参数来源标记（Taint）
    TaintMapEntry[] argTaints;      // 参数来源打标（防提示注入）
    
    // 目标摘要（脱敏，供审计/确认面板展示）
    String targetSummary;           // 如 "发送消息给 Alice"
    
    // 风险自估（调用方提供，实际以策略判定为准）
    int riskEstimate;               // 0=low, 1=medium, 2=high
    
    // 执行约束
    long timeoutMs;                 // 执行超时（毫秒，0=使用默认）
    boolean requireConfirmation;    // 强制用户确认（即使策略允许）
}

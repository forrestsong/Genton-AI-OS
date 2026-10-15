/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.ainative.execution;

/**
 * ActionInfo - 动作注册信息
 * 
 * 从动作注册表读取的动作元信息，供 Agent 规划使用。
 */
parcelable ActionInfo {
    String actionId;            // 动作 ID（唯一标识）
    String name;                // 动作名称
    String description;         // 动作描述
    
    // 能力要求
    String requiredCapability;  // 需要的能力（如 "agent.send_message"）
    
    // 参数 schema（JSON Schema 格式）
    String parametersSchema;    // 参数 schema
    
    // 风险等级
    int riskLevel;              // 0=low, 1=medium, 2=high
    
    // 执行器
    String executorType;        // 执行器类型（如 "ui.input"/"app.activity"）
    
    // 约束
    boolean requiresUserConfirmation;   // 是否总是需要用户确认
    String[] allowedSources;            // 允许的参数来源（如 ["USER", "AGENT"]）
}

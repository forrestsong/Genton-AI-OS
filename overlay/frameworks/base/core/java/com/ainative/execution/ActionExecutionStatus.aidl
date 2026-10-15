/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.ainative.execution;

/**
 * ActionExecutionStatus - 动作执行状态
 * 
 * 用于查询和回调的状态对象。
 */
parcelable ActionExecutionStatus {
    String actionRequestId;     // 请求 ID
    int state;                  // 当前状态（STATE_*）
    String actionId;            // 动作 ID
    
    // 执行结果
    boolean success;            // 是否成功（STATE_SUCCEEDED 时为 true）
    String resultJson;          // 结果数据（JSON 格式）
    String errorMessage;        // 错误信息（STATE_FAILED 时）
    int errorCode;              // 错误码
    
    // 时间戳
    long submittedAtMs;         // 提交时间
    long completedAtMs;         // 完成时间（0=未完成）
    
    // 确认信息（STATE_PENDING_CONFIRM 时）
    String confirmId;           // 确认 ID（供 SystemUI 使用）
    String confirmMessage;      // 确认提示信息
}

/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.ainative.execution;

import com.ainative.execution.ActionExecutionStatus;

/**
 * IActionCallback - 动作执行状态回调
 * 
 * 用于通知调用方动作执行的状态变更。
 * 
 * 回调时机：
 * - 状态转换时立即回调
 * - 执行完成（成功/失败）时回调
 * - 取消/撤销时回调
 */
interface IActionCallback {
    /**
     * 状态变更通知
     * 
     * @param status 当前状态
     */
    oneway void onStatusChanged(in ActionExecutionStatus status);
    
    /**
     * 执行完成通知（成功）
     * 
     * @param status 最终状态（STATE_SUCCEEDED）
     */
    oneway void onCompleted(in ActionExecutionStatus status);
    
    /**
     * 执行失败通知
     * 
     * @param status 最终状态（STATE_FAILED）
     */
    oneway void onFailed(in ActionExecutionStatus status);
    
    /**
     * 取消通知
     * 
     * @param status 最终状态（STATE_CANCELLED/STATE_REVOKED）
     */
    oneway void onCancelled(in ActionExecutionStatus status);
}

/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.genton.execution;

import com.genton.execution.ActionRequest;
import com.genton.execution.ActionInfo;
import com.genton.execution.ActionExecutionStatus;
import com.genton.execution.IActionCallback;

/**
 * IActionExecutionService - 执行中介（动作执行的唯一入口）
 * 
 * 职责：
 * 1. 动作注册表校验（存在性、参数 schema）
 * 2. 策略引擎集成（evaluateIntent、requestCapability）
 * 3. 用户确认流程（高风险动作）
 * 4. 执行器调度（根据 actionId 选择执行器）
 * 5. 审计落链（全部动作写审计）
 * 6. 8 态状态机管理
 * 
 * 状态机：
 * IDLE → POLICY_CHECK → [PENDING_CONFIRM] → EXECUTING → SUCCEEDED/FAILED
 *                                           ↓
 *                                       CANCELLED/REVOKED
 * 
 * 安全约束：
 * - 副作用动作只有一个入口（本服务）
 * - 模型不能直触 AMS/InputDispatcher
 * - 全部动作强制走策略预检
 * - 高风险动作强制用户确认
 * - Taint 来源校验（防提示注入）
 * 
 * 性能要求：
 * - 提交延迟 P50 < 5ms
 * - 状态查询 P50 < 1ms
 * 
 * @see ActionRequest
 * @see ActionExecutionStatus
 */
interface IActionExecutionService {
    // ========== 提交结果码 ==========
    
    const int SUBMIT_ACCEPTED = 0;              // 已受理，进入异步执行
    const int SUBMIT_UNKNOWN_ACTION = 1;        // 动作不在注册表
    const int SUBMIT_POLICY_DENIED = 2;         // 策略拒绝
    const int SUBMIT_ARGUMENT_INVALID = 3;      // 参数 schema 校验失败
    const int SUBMIT_TAINT_DENIED = 4;          // 参数来源含未授权 taint
    const int SUBMIT_CONFIRMATION_REQUIRED = 5; // 高风险，等待用户确认
    const int SUBMIT_RATE_LIMITED = 6;          // 频率限制
    const int SUBMIT_INTERNAL_ERROR = -1;       // 内部错误
    
    // ========== 动作状态（8 态状态机）==========
    
    const int STATE_IDLE = 0;           // 空闲（初始状态）
    const int STATE_POLICY_CHECK = 1;   // 策略校验中
    const int STATE_PENDING_CONFIRM = 2;// 等待用户确认（高风险）
    const int STATE_EXECUTING = 3;      // 执行中
    const int STATE_SUCCEEDED = 4;      // 执行成功
    const int STATE_FAILED = 5;         // 执行失败
    const int STATE_CANCELLED = 6;      // 用户/调用方取消
    const int STATE_REVOKED = 7;        // 令牌撤销（能力被撤回）
    
    // ========== 核心方法 ==========
    
    /**
     * 提交动作执行请求
     * 
     * 流程：
     * 1. 注册表校验（动作存在、参数 schema）
     * 2. Taint 来源校验
     * 3. 策略引擎预检（requestCapability）
     * 4. 高风险 → 用户确认流程
     * 5. 低风险 → 直接执行
     * 
     * @param request 动作请求（actionId、参数、来源标记）
     * @param callback 状态变更回调
     * @param actionRequestId [out] 请求 ID（单元素数组，取 actionRequestId[0]；用于查询/取消）
     * @return SUBMIT_ACCEPTED/DENIED/CONFIRMATION_REQUIRED
     */
    int submitAction(in ActionRequest request, 
                    in IActionCallback callback,
                    out String[] actionRequestId);
    
    /**
     * 取消动作执行
     * 
     * 仅对 POLICY_CHECK/PENDING_CONFIRM/EXECUTING 状态有效。
     * SUCCEEDED/FAILED/CANCELLED/REVOKED 状态不可取消。
     * 
     * @param actionRequestId 请求 ID
     * @return 0=成功, 1=请求不存在, 2=状态不可取消
     */
    int cancelAction(in String actionRequestId);
    
    /**
     * 查询动作执行状态
     * 
     * @param actionRequestId 请求 ID
     * @return 状态对象（含当前状态、错误信息、结果）
     */
    ActionExecutionStatus getActionStatus(in String actionRequestId);
    
    /**
     * 获取动作注册信息（供规划）
     * 
     * @param actionId 动作 ID
     * @return 动作信息（schema、能力要求、风险等级）
     */
    ActionInfo describeAction(in String actionId);
    
    /**
     * 列出全部可用动作
     * 
     * @return 动作 ID 列表
     */
    String[] listAvailableActions();
    
    /**
     * 用户确认决策回灌（SystemUI 调用）
     * 
     * 流程：
     * 1. 查找 PENDING_CONFIRM 状态的请求
     * 2. 用户允许 → 继续执行
     * 3. 用户拒绝 → 转 CANCELLED 状态
     * 
     * @param confirmId 确认 ID（从 PolicyDecision 获取）
     * @param userDecision USER_ALLOW_ONCE/USER_ALLOW_TASK/USER_DENY
     * @return 0=成功, 1=confirmId 无效
     */
    int reportUserConfirmation(in String confirmId, in int userDecision);
}

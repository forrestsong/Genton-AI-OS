// IAgentSandboxHostEvents.aidl
// 宿主事件回调接口 - 宿主实现，第三方 Agent 调用
package com.ainative.sandbox;

import com.ainative.sandbox.SandboxHandle;
import com.ainative.sandbox.SandboxToolCall;

/**
 * 沙箱宿主事件回调接口
 * 
 * 实现方：沙箱宿主（系统大脑特权 App）
 * 调用方：第三方 Agent（isolatedProcess）
 * 
 * 职责：
 * 1. 接收第三方 Agent 状态变化
 * 2. 接收工具调用请求（路由到策略引擎）
 * 3. 接收错误通知
 * 
 * 安全约束：
 * - oneway 异步，防止第三方阻塞宿主
 * - 工具请求仍需经过策略引擎评估
 * - 敏感操作需用户确认（高风险动作）
 */
interface IAgentSandboxHostEvents {
    /**
     * Agent 就绪通知
     * 
     * 第三方 Agent 完成初始化，准备接收用户消息
     * 
     * @param handle 沙箱句柄
     */
    oneway void onReady(in SandboxHandle handle);
    
    /**
     * 状态变化通知
     * 
     * 第三方 Agent 状态改变（idle/thinking/executing/waiting）
     * 
     * @param state 状态码（0=idle, 1=thinking, 2=executing, 3=waiting）
     * @param summaryMasked 状态摘要（已脱敏，可展示给用户）
     */
    oneway void onStateChanged(int state, in String summaryMasked);
    
    /**
     * 工具调用请求
     * 
     * 第三方 Agent 请求执行系统能力（通过沙箱网关）。
     * 宿主会：
     * 1. 校验能力授权（用户已授权该能力？）
     * 2. 路由到策略引擎评估
     * 3. 高风险操作弹出确认 UI
     * 4. 执行并审计
     * 5. 结果回传给 ISandboxedAgent.onToolResult
     * 
     * @param call 工具调用请求（callId、opType、参数）
     */
    oneway void onToolRequest(in SandboxToolCall call);
    
    /**
     * 错误通知
     * 
     * 第三方 Agent 内部错误（如模型 API 失败、超时）
     * 
     * @param code 错误码
     * @param message 错误信息（已脱敏）
     */
    oneway void onError(int code, in String message);
    
    /**
     * 任务完成通知
     * 
     * 第三方 Agent 完成用户请求的任务
     * 
     * @param turnId 轮次 ID
     * @param resultSummary 结果摘要（已脱敏）
     */
    oneway void onTaskCompleted(in String turnId, in String resultSummary);
}

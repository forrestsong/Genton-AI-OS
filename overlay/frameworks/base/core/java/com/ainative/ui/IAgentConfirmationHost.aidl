// IAgentConfirmationHost.aidl
// 确认承载接口 - 由 SystemUI 实现，执行层/策略引擎调用
package com.ainative.ui;

import com.ainative.ui.ConfirmationUiRequest;

/**
 * Agent 确认承载接口
 * 
 * 实现方：SystemUI（确认面板独占实现）
 * 调用方：执行层（ActionExecutionService）/ 策略引擎（PolicyEngineService）
 * 
 * 核心职责：
 * 1. 展示全屏确认请求（FLAG_SECURE，防截屏）
 * 2. 收集用户决策（批准/拒绝/永不）
 * 3. 回灌决策到策略引擎（IPolicyEngine.reportUserDecision）
 * 4. 防重放（confirmId 由策略引擎 HMAC 签发）
 * 
 * 安全约束：
 * - 确认请求必须携带策略签发的 confirmId（防 Agent 伪造）
 * - 用户决策直接回策略引擎，不经过大脑（防篡改）
 * - 并发请求入队展示（exclusive=true 时全屏独占）
 * - 确认超时自动拒绝（Fail-secure）
 * 
 * 确认流程：
 * 1. PolicyEngine.requestCapability() 返回 REQUIRE_CONFIRMATION + confirmId
 * 2. ActionExecutionService 调用本接口展示确认
 * 3. 用户决策后 SystemUI 调用 PolicyEngine.reportUserDecision(confirmId, decision)
 * 4. PolicyEngine 验证 confirmId 并签发令牌（或最终拒绝）
 */
interface IAgentConfirmationHost {
    // 返回码
    const int RESULT_OK       = 0;  // 成功排队展示
    const int RESULT_INVALID  = 1;  // confirmId 无效（签名校验失败）
    const int RESULT_DUPLICATE = 2;  // confirmId 重复（防重放）
    const int RESULT_DENIED   = 3;  // 权限拒绝
    
    // 取消原因
    const int DISMISS_TIMEOUT   = 0;  // 确认超时（默认 60 秒）
    const int DISMISS_CANCELLED = 1;  // 意图已撤销
    const int DISMISS_AGENT_TERMINATED = 2;  // Agent 终止
    
    /**
     * 展示确认请求
     * 
     * @param request 确认请求数据（confirmId、capability、targetSummary、riskLabel 等）
     * @return RESULT_OK 成功；RESULT_INVALID confirmId 无效；RESULT_DUPLICATE 重复请求
     */
    int showConfirmation(in ConfirmationUiRequest request);
    
    /**
     * 服务侧取消确认
     * 
     * 场景：
     * - 确认超时（策略引擎侧超时）
     * - 用户取消了原始意图
     * - Agent 进程终止
     * 
     * @param confirmId 确认 ID
     * @param reason 取消原因（DISMISS_TIMEOUT/DISMISS_CANCELLED/DISMISS_AGENT_TERMINATED）
     */
    oneway void dismissConfirmation(in String confirmId, in int reason);
}
